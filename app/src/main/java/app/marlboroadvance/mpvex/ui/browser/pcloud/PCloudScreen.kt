package app.marlboroadvance.mpvex.ui.browser.pcloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material.icons.twotone.Cloud
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.net.URL

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
    var libraryRefreshLoaded by remember { mutableIntStateOf(-1) }
    var mediaFilter by rememberSaveable { mutableIntStateOf(0) }
    var selectedPhoto by remember { mutableStateOf<Pair<String, String>?>(null) }
    var libraryEntries by remember { mutableStateOf(emptyList<PCloudEntry>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }

    fun goBack() {
      if (mediaFilter == 4 && folderStack.isNotEmpty()) {
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
          val wasConnected = connected
          connected = PCloudAuth.isConnected(context)
          accountMessage = PCloudAuth.takeLastMessage(context)
          if (!wasConnected && connected) refreshCounter++
        }
      }
      lifecycleOwner.lifecycle.addObserver(observer)
      onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(connected, currentFolderId, refreshCounter, mediaFilter) {
      if (!connected) {
        entries = emptyList()
        libraryEntries = emptyList()
        return@LaunchedEffect
      }

      if (mediaFilter == 4) {
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
      } else if (libraryRefreshLoaded != refreshCounter) {
        isLoading = true
        error = null
        try {
          libraryEntries = PCloudApi.listMediaLibrary(context)
          libraryRefreshLoaded = refreshCounter
        } catch (exception: Exception) {
          error = exception.message ?: "Could not scan the pCloud library."
          connected = PCloudAuth.isConnected(context)
        } finally {
          isLoading = false
        }
      }
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = if (mediaFilter == 4) currentFolderName else "pCloud · ${listOf("All", "Videos", "Photos", "Music")[mediaFilter]}",
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
            IconButton(onClick = { showSearch = !showSearch; if (!showSearch) query = "" }) {
              Icon(Icons.TwoTone.Search, contentDescription = "Search pCloud media")
            }
            IconButton(onClick = { refreshCounter++ }) {
              Icon(Icons.Filled.Refresh, contentDescription = "Refresh pCloud library")
            }
            if (connected) {
              TextButton(
                onClick = {
                  PCloudAuth.signOut(context)
                  connected = false
                  entries = emptyList()
                  libraryEntries = emptyList()
                  libraryRefreshLoaded = -1
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
            if (mediaFilter == 4) {
              Text(
                text = "Browse folders in your pCloud account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            } else {
              Text(
                text = "Media from across your pCloud folders, grouped by type.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            ScrollableTabRow(selectedTabIndex = mediaFilter, edgePadding = 0.dp) {
              listOf("All", "Videos", "Photos", "Music", "Folders").forEachIndexed { index, label ->
                Tab(selected = mediaFilter == index, onClick = { mediaFilter = index; query = "" }, text = { Text(label) })
              }
            }
            if (showSearch) {
              androidx.compose.material3.TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                placeholder = { Text(if (mediaFilter == 4) "Search this folder" else "Search pCloud media") },
                singleLine = true,
              )
            }
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
          val sourceEntries = if (mediaFilter == 4) entries else libraryEntries
          val filteredEntries = sourceEntries.filter { entry ->
            val matchesType = if (mediaFilter == 4) true else when (mediaFilter) {
              0 -> mediaKind(entry.name) != "other"
              1 -> mediaKind(entry.name) == "video"
              2 -> mediaKind(entry.name) == "image"
              3 -> mediaKind(entry.name) == "audio"
              else -> false
            }
            val matchesSearch = query.isBlank() || entry.name.contains(query, true) || entry.path.contains(query, true)
            matchesType && (mediaFilter == 4 || !entry.isFolder) && matchesSearch
          }
          if (!isLoading && error == null && filteredEntries.isEmpty()) {
            item {
              Text(
                text = if (query.isNotBlank()) "No matching pCloud media." else if (mediaFilter == 4) "This folder is empty." else "No media found in your pCloud account.",
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
              )
            }
          }
          items(filteredEntries, key = { (it.folderId ?: it.fileId).toString() }) { entry ->
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
                      if (mediaKind(entry.name) == "image") selectedPhoto = entry.name to link
                      else MediaUtils.playFile(link, context, "pcloud", audioOnly = mediaKind(entry.name) == "audio")
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
    selectedPhoto?.let { (name, url) ->
      PCloudPhotoDialog(name, url) { selectedPhoto = null }
    }
  }
}

private fun mediaKind(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
  "mp4", "m4v", "mkv", "avi", "mov", "webm", "wmv", "mpeg", "mpg", "3gp", "ts" -> "video"
  "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "tif", "tiff" -> "image"
  "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "ape", "alac", "aiff" -> "audio"
  else -> "other"
}

@Composable
private fun PCloudPhotoDialog(name: String, url: String, onDismiss: () -> Unit) {
  val bitmap = produceState<Bitmap?>(null, url) {
    value = withContext(Dispatchers.IO) { decodeRemoteImage(url) }
  }.value
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    text = {
      Box(Modifier.fillMaxWidth().height(440.dp), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
          ?: androidx.compose.material3.CircularProgressIndicator()
      }
    },
  )
}


private fun decodeRemoteImage(url: String): Bitmap? = runCatching {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  URL(url).openConnection().apply { connectTimeout = 15_000; readTimeout = 20_000 }.getInputStream().use {
    BitmapFactory.decodeStream(it, null, bounds)
  }
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
  var sample = 1
  while (bounds.outWidth / sample > 2400 || bounds.outHeight / sample > 2400) sample *= 2
  val options = BitmapFactory.Options().apply { inSampleSize = sample }
  URL(url).openConnection().apply { connectTimeout = 15_000; readTimeout = 20_000 }.getInputStream().use {
    BitmapFactory.decodeStream(it, null, options)
  }
}.getOrNull()

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
        if (!entry.isFolder && entry.path.isNotBlank() && entry.path != entry.name) {
          Text(
            entry.path.substringBeforeLast('/', entry.path),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
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
