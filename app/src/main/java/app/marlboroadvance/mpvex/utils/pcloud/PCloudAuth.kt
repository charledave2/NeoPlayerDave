package app.marlboroadvance.mpvex.utils.pcloud

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.marlboroadvance.mpvex.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val PCLOUD_PREFS = "pcloud_oauth"
private const val KEY_ALIAS = "NeoPlayerDavePCloudAccessToken"
private const val REDIRECT_SCHEME = "mpvex"
private const val REDIRECT_HOST = "oauth"
private const val REDIRECT_PATH = "/callback"
private const val REDIRECT_URI = "mpvex://oauth/callback"
private const val US_API_HOST = "api.pcloud.com"
private const val EU_API_HOST = "eapi.pcloud.com"

data class PCloudCredentials(
  val accessToken: String,
  val apiHost: String,
  val userId: Long,
)

data class PCloudEntry(
  val name: String,
  val isFolder: Boolean,
  val folderId: Long?,
  val fileId: Long?,
  val size: Long,
)

object PCloudAuth {
  private const val PENDING_STATE = "pending_state"
  private const val LAST_MESSAGE = "last_message"
  private const val ACCESS_TOKEN = "access_token_encrypted"
  private const val API_HOST = "api_host"
  private const val USER_ID = "user_id"

  fun isConfigured(): Boolean = BuildConfig.PCLOUD_CLIENT_ID.isNotBlank()

  fun isConnected(context: Context): Boolean = credentials(context) != null

  fun credentials(context: Context): PCloudCredentials? {
    val prefs = context.getSharedPreferences(PCLOUD_PREFS, Context.MODE_PRIVATE)
    val token = try {
      decryptToken(prefs.getString(ACCESS_TOKEN, null))
    } catch (_: Exception) {
      prefs.edit().remove(ACCESS_TOKEN).apply()
      null
    } ?: return null

    val host = prefs.getString(API_HOST, null) ?: return null
    val userId = prefs.getLong(USER_ID, 0L)
    return PCloudCredentials(token, host, userId)
  }

  fun launchAuthorization(context: Context): Boolean {
    val clientId = BuildConfig.PCLOUD_CLIENT_ID.trim()
    if (clientId.isEmpty()) return false

    val stateBytes = ByteArray(32)
    SecureRandom().nextBytes(stateBytes)
    val state = Base64.encodeToString(
      stateBytes,
      Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
    )
    context.getSharedPreferences(PCLOUD_PREFS, Context.MODE_PRIVATE)
      .edit()
      .putString(PENDING_STATE, state)
      .remove(LAST_MESSAGE)
      .apply()

    val authorizationUri = Uri.parse("https://my.pcloud.com/oauth2/authorize")
      .buildUpon()
      .appendQueryParameter("client_id", clientId)
      .appendQueryParameter("response_type", "token")
      .appendQueryParameter("redirect_uri", REDIRECT_URI)
      .appendQueryParameter("state", state)
      .build()

    return try {
      val intent = Intent(Intent.ACTION_VIEW, authorizationUri)
      if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
      true
    } catch (_: Exception) {
      false
    }
  }

  fun handleRedirect(context: Context, uri: Uri?) {
    val prefs = context.getSharedPreferences(PCLOUD_PREFS, Context.MODE_PRIVATE)
    fun fail(message: String) {
      prefs.edit().remove(PENDING_STATE).putString(LAST_MESSAGE, message).apply()
    }

    if (uri == null ||
      uri.scheme != REDIRECT_SCHEME ||
      uri.host != REDIRECT_HOST ||
      uri.path != REDIRECT_PATH
    ) {
      fail("The pCloud sign-in callback was not valid.")
      return
    }

    val fragment = Uri.parse("https://callback.invalid/?" + uri.fragment.orEmpty())
    fun parameter(name: String): String? =
      fragment.getQueryParameter(name) ?: uri.getQueryParameter(name)

    val returnedState = parameter("state").orEmpty()
    val expectedState = prefs.getString(PENDING_STATE, null).orEmpty()
    if (expectedState.isEmpty() ||
      !MessageDigest.isEqual(
        expectedState.toByteArray(Charsets.UTF_8),
        returnedState.toByteArray(Charsets.UTF_8),
      )
    ) {
      fail("The pCloud sign-in could not be verified. Please try again.")
      return
    }

    parameter("error")?.let { error ->
      fail("pCloud sign-in was not completed: " + error)
      return
    }

    val token = parameter("access_token").orEmpty()
    val host = parameter("hostname").orEmpty().lowercase()
    val userId = parameter("uid")?.toLongOrNull() ?: 0L
    if (token.isBlank() || (host != US_API_HOST && host != EU_API_HOST)) {
      fail("pCloud did not return a valid account token. Please try again.")
      return
    }

    try {
      val encrypted = encryptToken(token)
      prefs.edit()
        .putString(ACCESS_TOKEN, encrypted)
        .putString(API_HOST, host)
        .putLong(USER_ID, userId)
        .remove(PENDING_STATE)
        .putString(LAST_MESSAGE, "Connected to pCloud.")
        .apply()
    } catch (_: Exception) {
      fail("The pCloud token could not be stored securely on this device.")
    }
  }

  fun takeLastMessage(context: Context): String? {
    val prefs = context.getSharedPreferences(PCLOUD_PREFS, Context.MODE_PRIVATE)
    val message = prefs.getString(LAST_MESSAGE, null)
    prefs.edit().remove(LAST_MESSAGE).apply()
    return message
  }

  fun signOut(context: Context) {
    context.getSharedPreferences(PCLOUD_PREFS, Context.MODE_PRIVATE)
      .edit()
      .remove(ACCESS_TOKEN)
      .remove(API_HOST)
      .remove(USER_ID)
      .remove(PENDING_STATE)
      .remove(LAST_MESSAGE)
      .apply()
  }

  private fun encryptionKey(): SecretKey {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

    val generator = KeyGenerator.getInstance(
      KeyProperties.KEY_ALGORITHM_AES,
      "AndroidKeyStore",
    )
    generator.init(
      KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true)
        .build(),
    )
    return generator.generateKey()
  }

  private fun encryptToken(token: String): String {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
    val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
    val payload = cipher.iv + encrypted
    return Base64.encodeToString(payload, Base64.NO_WRAP)
  }

  private fun decryptToken(encoded: String?): String? {
    if (encoded.isNullOrBlank()) return null
    val payload = Base64.decode(encoded, Base64.NO_WRAP)
    if (payload.size <= 12) return null
    val iv = payload.copyOfRange(0, 12)
    val encrypted = payload.copyOfRange(12, payload.size)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
    return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
  }
}

class PCloudOAuthCallbackActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    PCloudAuth.handleRedirect(this, intent?.data)
    finish()
  }

  override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    setIntent(intent)
    PCloudAuth.handleRedirect(this, intent?.data)
    finish()
  }
}

object PCloudApi {
  private val client = OkHttpClient()

  suspend fun listFolder(context: Context, folderId: Long): List<PCloudEntry> =
    withContext(Dispatchers.IO) {
      val response = call(context, "listfolder", "folderid" to folderId.toString())
      val metadata = response.optJSONObject("metadata")
        ?: throw IOException("pCloud returned no folder information.")
      val contents = metadata.optJSONArray("contents")
        ?: return@withContext emptyList()
      buildList {
        for (index in 0 until contents.length()) {
          val item = contents.optJSONObject(index) ?: continue
          val isFolder = item.optBoolean("isfolder", false)
          val id = if (isFolder) {
            item.optLong("folderid", 0L).takeIf { it > 0L }
          } else {
            item.optLong("fileid", 0L).takeIf { it > 0L }
          }
          if (id != null) {
            add(
              PCloudEntry(
                name = item.optString("name", "Untitled"),
                isFolder = isFolder,
                folderId = if (isFolder) id else null,
                fileId = if (isFolder) null else id,
                size = item.optLong("size", 0L),
              ),
            )
          }
        }
      }.sortedWith(compareBy<PCloudEntry> { !it.isFolder }.thenBy { it.name.lowercase() })
    }

  suspend fun getFileLink(context: Context, fileId: Long): String =
    withContext(Dispatchers.IO) {
      val response = call(context, "getfilelink", "fileid" to fileId.toString())
      val hosts = response.optJSONArray("hosts")
        ?: throw IOException("pCloud returned no streaming host.")
      val host = hosts.optString(0, "")
      val path = response.optString("path", "")
      if (!host.endsWith(".pcloud.com", ignoreCase = true) ||
        !path.startsWith("/")
      ) {
        throw IOException("pCloud returned an invalid streaming link.")
      }
      "https://" + host + path
    }

  private fun call(
    context: Context,
    method: String,
    parameter: Pair<String, String>,
  ): JSONObject {
    val credentials = PCloudAuth.credentials(context)
      ?: throw IOException("Connect to pCloud again to continue.")
    val uri = Uri.Builder()
      .scheme("https")
      .authority(credentials.apiHost)
      .appendPath(method)
      .appendQueryParameter(parameter.first, parameter.second)
      .build()
    val request = Request.Builder()
      .url(uri.toString())
      .header("Authorization", "Bearer " + credentials.accessToken)
      .header("Accept", "application/json")
      .get()
      .build()

    return client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        throw IOException("pCloud request failed (HTTP " + response.code + ").")
      }
      val body = response.body?.string()
        ?: throw IOException("pCloud returned an empty response.")
      val json = JSONObject(body)
      val result = json.optInt("result", -1)
      if (result != 0) {
        if (result == 1000) PCloudAuth.signOut(context)
        val message = when (result) {
          1000 -> "Your pCloud session ended. Please sign in again."
          2003 -> "pCloud denied access to this file or folder."
          2005, 2009 -> "This pCloud item could not be found."
          else -> "pCloud returned error " + result + "."
        }
        throw IOException(message)
      }
      json
    }
  }
}
