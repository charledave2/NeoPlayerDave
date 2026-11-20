package app.marlboroadvance.mpvex.utils.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Looks up a missing local cover through MusicBrainz and its Cover Art Archive. */
object AlbumArtworkProvider {
  private val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(25, TimeUnit.SECONDS)
    .build()
  private val musicBrainzLock = Mutex()
  @Volatile private var lastMusicBrainzRequestAt = 0L

  suspend fun findCover(context: Context, artist: String, album: String): Bitmap? = withContext(Dispatchers.IO) {
    val cleanArtist = artist.trim()
    val cleanAlbum = album.trim()
    if (cleanArtist.isBlank() || cleanAlbum.isBlank()) return@withContext null

    val cacheDir = File(context.cacheDir, "music_artwork").apply { mkdirs() }
    val key = sha256("${normalize(cleanArtist)}|${normalize(cleanAlbum)}")
    val imageFile = File(cacheDir, "$key.jpg")
    val missingFile = File(cacheDir, "$key.missing")
    decodeCached(imageFile)?.let { return@withContext it }
    if (missingFile.exists() && System.currentTimeMillis() - missingFile.lastModified() < NEGATIVE_CACHE_MS) {
      return@withContext null
    }

    val releaseId = findExactRelease(cleanArtist, cleanAlbum)
    if (releaseId == null) {
      missingFile.parentFile?.mkdirs()
      missingFile.createNewFile()
      missingFile.setLastModified(System.currentTimeMillis())
      return@withContext null
    }

    val coverUrl = "https://coverartarchive.org/release/$releaseId/front-250"
    val request = Request.Builder().url(coverUrl).header("User-Agent", USER_AGENT).get().build()
    val imageBytes = runCatching {
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@use null
        val body = response.body ?: return@use null
        if (!body.contentType()?.type.equals("image", ignoreCase = true)) return@use null
        body.byteStream().use { input ->
          val output = ByteArrayOutputStream()
          val buffer = ByteArray(8 * 1024)
          var total = 0
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_COVER_BYTES) return@use null
            output.write(buffer, 0, count)
          }
          output.toByteArray().takeIf(ByteArray::isNotEmpty)
        }
      }
    }.getOrNull() ?: return@withContext null

    val temporary = File(cacheDir, "$key.tmp")
    runCatching {
      FileOutputStream(temporary).use { it.write(imageBytes) }
      if (!temporary.renameTo(imageFile)) {
        temporary.copyTo(imageFile, overwrite = true)
        temporary.delete()
      }
      missingFile.delete()
    }.getOrElse {
      temporary.delete()
      return@withContext null
    }
    decodeCached(imageFile)
  }

  private suspend fun findExactRelease(artist: String, album: String): String? = musicBrainzLock.withLock {
    val now = System.currentTimeMillis()
    val waitMs = MIN_MUSICBRAINZ_INTERVAL_MS - (now - lastMusicBrainzRequestAt)
    if (waitMs > 0) delay(waitMs)
    lastMusicBrainzRequestAt = System.currentTimeMillis()

    val query = "release:\"${escapeQuery(album)}\" AND artist:\"${escapeQuery(artist)}\""
    val url = Uri.parse("https://musicbrainz.org/ws/2/release/").buildUpon()
      .appendQueryParameter("query", query)
      .appendQueryParameter("fmt", "json")
      .appendQueryParameter("limit", "5")
      .build()
    val request = Request.Builder().url(url.toString()).header("User-Agent", USER_AGENT).get().build()
    runCatching {
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@use null
        val json = response.body?.string()?.let(::JSONObject) ?: return@use null
        val releases = json.optJSONArray("releases") ?: return@use null
        val wantedAlbum = normalize(album)
        val wantedArtist = normalize(artist)
        (0 until releases.length()).asSequence()
          .mapNotNull { releases.optJSONObject(it) }
          .filter { normalize(it.optString("title")) == wantedAlbum && artistMatches(it, wantedArtist) }
          .maxByOrNull { it.optInt("score", 0) }
          ?.optString("id")
          ?.takeIf(String::isNotBlank)
      }
    }.getOrNull()
  }

  private fun artistMatches(release: JSONObject, wantedArtist: String): Boolean {
    val credits = release.optJSONArray("artist-credit") ?: return false
    val names = buildList {
      for (index in 0 until credits.length()) {
        when (val credit = credits.opt(index)) {
          is JSONObject -> add(credit.optString("name").ifBlank { credit.optJSONObject("artist")?.optString("name").orEmpty() })
          is String -> add(credit)
        }
      }
    }
    return names.any { normalize(it).contains(wantedArtist) || wantedArtist.contains(normalize(it)) }
  }

  private fun decodeCached(file: File): Bitmap? = runCatching {
    if (!file.isFile) return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
  }.getOrNull()

  private fun normalize(value: String): String = value
    .lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")

  private fun escapeQuery(value: String): String = value
    .replace('\\', ' ')
    .replace('"', ' ')
    .trim()

  private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

  private const val USER_AGENT = "PCloudMediaManager/1.3.0 (https://github.com/charledave2/NeoPlayerDave)"
  private const val MIN_MUSICBRAINZ_INTERVAL_MS = 1_100L
  private const val NEGATIVE_CACHE_MS = 30L * 24 * 60 * 60 * 1000
  private const val MAX_COVER_BYTES = 8 * 1024 * 1024
}
