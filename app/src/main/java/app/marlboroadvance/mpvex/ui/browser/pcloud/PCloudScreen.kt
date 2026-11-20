package app.marlboroadvance.mpvex.ui.browser.pcloud

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.twotone.Cloud
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.utils.media.MediaUtils
import app.marlboroadvance.mpvex.utils.pcloud.PCloudApi
import app.marlboroadvance.mpvex.utils.pcloud.PCloudAuth
import app.marlboroadvance.mpvex.utils.pcloud.PCloudEntry
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object PCloudScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var connected by remember { mutableStateOf(PCloudAuth.isConnected(context)) }
    var currentFolderId by rememberSaveable { mutableStateOf(0L) }
    var currentFolderName by rememberSaveable { mutableStateOf("pCloud") }
    var folderStack by remember { mutableStateOf(emptyList<Pair<Long, String>>()) }
    var entries by remember { mutableStateOf(emptyList<PCloudEntry>()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var accountMessage by remember { mutableStateOf<String?>(null) }
    var refreshCounter by remember { mutableIntStateOf(0) }

    fun goBack() {
      if (folderStack.isNotEmpty()) {
        val previous = folderStack.last()
        folderStack = folderStack.dropLast(1)
        currentFolderId = previous.first
        currentFolderName = previous.second
      } else {
        backstack.removeLastOrNull()
      }
    }

    BackHandler { goBack() }

    DisposableEffect(lifecycleOwner) {
      val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
          connected = PCloudAuth.isConnected(context)
          accountMessage = PCloudAuth.takeLastMessage(context)
          refreshCounter++
        }
      }
      lifecycleOwner.lifecycle.addObserver(observer)
      onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(connected, currentFolderId, refreshCounter) {
      if (!connected) {
        entries = emptyList()
        return@LaunchedEffect
      }

      isLoading = true
      error = null
      try {
        entries = PCloudApi.listFolder(context, currentFolderId)
      } catch (exception: Exception) {
        error = exception.message ?: "Could not load this pCloud folder."
        connected = PCloudAuth.isConnected(context)
      } finally {
        isLoading = false
      }
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = currentFolderName,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          },
          navigationIcon = {
            IconButton(onClick = { goBack() }) {
              Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
          },
          actions = {
            if (connected) {
              TextButton(
                onClick = {
                  PCloudAuth.signOut(context)
                  connected = false
                  entries = emptyList()
                  folderStack = emptyList()
                  currentFolderId = 0L
                  currentFolderName = "pCloud"
                  error = null
                },
              ) {
                Text("Disconnect")
              }
            }
          },
        )
      },
    ) { padding ->
      if (!connected) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          Icon(
            imageVector = Icons.TwoTone.Cloud,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
          )
          Spacer(modifier = Modifier.height(16.dp))
          Text(
            text = "Connect your pCloud account",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "Sign in securely in your browser, then browse and stream your pCloud files here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(20.dp))
          Button(
            onClick = {
              if (!PCloudAuth.launchAuthorization(context)) {
                error = "pCloud sign-in could not open. Check that the client ID is configured."
              }
            },
            enabled = PCloudAuth.isConfigured(),
          ) {
            Text("Sign in with pCloud")
          }
          if (!PCloudAuth.isConfigured()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
              text = "Add the GitHub Actions secret PCLOUD_CLIENT_ID to enable sign-in. The client secret is not used in the Android app.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          error?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
          }
          accountMessage?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      } else {
        LazyColumn(
          modifier = Modifier
            .fillMaxSize()
            .padding(padding),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          item {
            accountMessage?.let {
              Text(
                text = it,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
              )
            }
            Text(
              text = "Your files are accessed through pCloud OAuth. WebDAV connections remain available in Network.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          if (isLoading) {
            item {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(28.dp),
                contentAlignment = Alignment.Center,
              ) {
                androidx.compose.material3.CircularProgressIndicator()
              }
            }
          }
          error?.let { message ->
            item {
              Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
              )
            }
          }
          items(entries, key = { (it.folderId ?: it.fileId).toString() }) { entry ->
            PCloudEntryCard(
              entry = entry,
              onClick = {
                if (entry.isFolder) {
                  folderStack = folderStack + (currentFolderId to currentFolderName)
                  currentFolderId = entry.folderId ?: return@PCloudEntryCard
                  currentFolderName = entry.name
                } else {
                  val fileId = entry.fileId ?: return@PCloudEntryCard
                  scope.launch {
                    isLoading = true
                    error = null
                    try {
                      val link = PCloudApi.getFileLink(context, fileId)
                      MediaUtils.playFile(link, context, "pcloud")
                    } catch (exception: Exception) {
                      error = exception.message ?: "Could not open this pCloud file."
                      connected = PCloudAuth.isConnected(context)
                    } finally {
                      isLoading = false
                    }
                  }
                }
              },
            )
          }
        }
      }
    }
  }
}

@Composable
fun PCloudAccessCard(onClick: () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Icon(
        imageVector = Icons.TwoTone.Cloud,
        contentDescription = null,
        modifier = Modifier.size(32.dp),
        tint = MaterialTheme.colorScheme.primary,
      )
      Column(modifier = Modifier.weight(1f)) {
        Text("pCloud", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
          "Sign in to browse and stream cloud files",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      FilledTonalButton(onClick = onClick) {
        Text("Open")
      }
    }
  }
}

@Composable
private fun PCloudEntryCard(entry: PCloudEntry, onClick: () -> Unit) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    shape = RoundedCornerShape(18.dp),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Icon(
        imageVector = if (entry.isFolder) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = MaterialTheme.colorScheme.primary,
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          entry.name,
          style = MaterialTheme.typography.bodyLarge,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (!entry.isFolder && entry.size > 0) {
          Text(
            formatPCloudSize(entry.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

private fun formatPCloudSize(size: Long): String {
  val units = arrayOf("B", "KB", "MB", "GB", "TB")
  var remaining = size.toDouble()
  var unit = 0
  while (remaining >= 1024 && unit < units.lastIndex) {
    remaining /= 1024
    unit++
  }
  return String.format(java.util.Locale.US, "%.1f %s", remaining, units[unit])
}
