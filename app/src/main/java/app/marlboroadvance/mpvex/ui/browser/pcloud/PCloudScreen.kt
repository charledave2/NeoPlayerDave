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
  override fun Content() = ContentInternal(embedded = false, initialFilter = 0)

  @Composable
  fun ContentFor(
    filter: Int,
    sectionTitle: String = "",
    categoryTitle: String = "",
    searchQuery: String = "",
    refreshSignal: Int = 0,
    sortMode: Int = 0,
  ) = ContentInternal(
    embedded = true,
    initialFilter = filter,
    sectionTitle = sectionTitle,
    categoryTitle = categoryTitle,
    externalSearchQuery = searchQuery,
    externalRefreshSignal = refreshSignal,
    externalSortMode = sortMode,
  )

  @Composable
  private fun ContentInternal(
    embedded: Boolean,
    initialFilter: Int,
    sectionTitle: String = "",
    categoryTitle: String = "",
    externalSearchQuery: String = "",
    externalRefreshSignal: Int = 0,
    externalSortMode: Int = 0,
  ) {
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
    var mediaFilter by rememberSaveable(embedded, initialFilter) { mutableIntStateOf(initialFilter.coerceIn(0, 4)) }
    var selectedPhoto by remember { mutableStateOf<Pair<String, String>?>(null) }
    var libraryEntries by remember { mutableStateOf(emptyList<PCloudEntry>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var selectedCloudGroup by rememberSaveable(embedded, initialFilter, sectionTitle, categoryTitle) { mutableStateOf<String?>(null) }
    val effectiveRefreshSignal = refreshCounter * 1_000_000 + externalRefreshSignal
    val effectiveSearchQuery = if (embedded) externalSearchQuery else query

    fun goBack() {
      if (mediaFilter == 4 && folderStack.isNotEmpty()) {
        val previous = folderStack.last()
        folderStack = folderStack.dropLast(1)
        currentFolderId = previous.first
        currentFolderName = previous.second
      } else if (!embedded) {
        backstack.removeLastOrNull()
      }
    }

    BackHandler(enabled = !embedded || (mediaFilter == 4 && folderStack.isNotEmpty())) { goBack() }

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

    LaunchedEffect(connected, currentFolderId, refreshCounter, externalRefreshSignal, mediaFilter) {
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
      } else if (libraryRefreshLoaded != effectiveRefreshSignal) {
        isLoading = true
        error = null
        try {
          libraryEntries = PCloudApi.listMediaLibrary(context)
          libraryRefreshLoaded = effectiveRefreshSignal
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
        if (!embedded) TopAppBar(
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
            if (embedded && mediaFilter == 4 && folderStack.isNotEmpty()) {
              TextButton(onClick = { goBack() }) { Text("← Parent folder") }
            }
            accountMessage?.let {
              Text(
                text = it,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
              )
            }
            if (embedded) {
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
              ) { Text("Disconnect pCloud") }
            }
            if (mediaFilter == 4) {
              Text(
                text = "Browse folders in your pCloud account.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            } else {
              Text(
                text = if (embedded) "$sectionTitle · $categoryTitle in pCloud" else "Media from across your pCloud folders, grouped by type.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            if (!embedded) {
              ScrollableTabRow(selectedTabIndex = mediaFilter, edgePadding = 0.dp) {
                listOf("All", "Videos", "Photos", "Music", "Folders").forEachIndexed { index, label ->
                  Tab(selected = mediaFilter == index, onClick = { mediaFilter = index; query = "" }, text = { Text(label) })
                }
              }
            }
            if (showSearch && !embedded) {
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
          val typedEntries = sourceEntries.filter { entry ->
            val matchesType = if (mediaFilter == 4) true else when (mediaFilter) {
              0 -> mediaKind(entry.name) != "other"
              1 -> mediaKind(entry.name) == "video"
              2 -> mediaKind(entry.name) == "image"
              3 -> mediaKind(entry.name) == "audio"
              else -> false
            }
            val matchesSearch = effectiveSearchQuery.isBlank() || entry.name.contains(effectiveSearchQuery, true) || entry.path.contains(effectiveSearchQuery, true)
            val matchesCategory = if (!embedded || mediaFilter == 4 || sectionTitle != "Videos") true else when (categoryTitle) {
              "Movies" -> !isShowEntry(entry) && !isOtherVideoEntry(entry)
              "TV Shows" -> isShowEntry(entry)
              "Other Videos" -> isOtherVideoEntry(entry)
              else -> true
            }
            matchesType && (mediaFilter == 4 || !entry.isFolder) && matchesSearch && matchesCategory
          }.let { matches ->
            when (externalSortMode) {
              1 -> matches.sortedByDescending { it.modifiedAt }
              2 -> matches.sortedByDescending { it.size }
              else -> matches.sortedBy { it.name.lowercase() }
            }
          }
          val supportsGrouping = embedded && mediaFilter != 4 && (
            (sectionTitle == "Music" && categoryTitle in setOf("Albums", "Artists", "Genres", "Playlists")) ||
              (sectionTitle == "Photos" && categoryTitle in setOf("Albums", "Folders", "Years"))
            )
          val genreFoldersPresent = sectionTitle == "Music" && categoryTitle == "Genres" && sourceEntries.any { entry ->
            if (mediaKind(entry.name) != "audio") return@any false
            val folders = entry.path.split('/').dropLast(1)
            folders.indices.any { index ->
              (folders[index].equals("genre", true) || folders[index].equals("genres", true)) && index + 1 < folders.size
            }
          }
          val playlistFoldersPresent = sectionTitle == "Music" && categoryTitle == "Playlists" && sourceEntries.any { entry ->
            if (mediaKind(entry.name) != "audio") return@any false
            val folders = entry.path.split('/').dropLast(1)
            folders.indices.any { index -> folders[index].equals("playlists", true) && index + 1 < folders.size }
          }
          val categoryEntries = if (
            (embedded && sectionTitle == "Music" && categoryTitle == "Genres" && !genreFoldersPresent) ||
            (embedded && sectionTitle == "Music" && categoryTitle == "Playlists" && !playlistFoldersPresent)
          ) emptyList() else typedEntries
          val filteredEntries = categoryEntries.filter { entry ->
            selectedCloudGroup == null || cloudGroupName(entry, sectionTitle, categoryTitle) == selectedCloudGroup
          }
          val groupedEntries = if (supportsGrouping && selectedCloudGroup == null) {
            val groups = categoryEntries.groupBy { cloudGroupName(it, sectionTitle, categoryTitle) }.toList()
            when (externalSortMode) {
              1 -> groups.sortedByDescending { (_, members) -> members.maxOfOrNull { it.modifiedAt } ?: 0L }
              2 -> groups.sortedByDescending { (_, members) -> members.sumOf { it.size } }
              else -> groups.sortedBy { it.first.lowercase() }
            }
          } else emptyList()
          val unsupportedCategoryMessage = when {
            embedded && sectionTitle == "Music" && categoryTitle == "Genres" && !genreFoldersPresent ->
              "pCloud’s file listing does not include audio genre tags. Add genre folders to browse tracks by folder, or use Local storage for device genre tags."
            embedded && sectionTitle == "Music" && categoryTitle == "Playlists" && !playlistFoldersPresent ->
              "Organize cloud tracks under Playlists/<name>/ to browse playlist folders. pCloud playlist files are not parsed yet."
            else -> null
          }
          if (selectedCloudGroup != null) {
            item { TextButton(onClick = { selectedCloudGroup = null }) { Text("← $categoryTitle · $selectedCloudGroup") } }
          }
          if (unsupportedCategoryMessage != null) {
            item { Text(unsupportedCategoryMessage, color = MaterialTheme.colorScheme.onSurfaceVariant) }
          }
          if (!isLoading && error == null && filteredEntries.isEmpty() && unsupportedCategoryMessage == null) {
            item {
              Text(
                text = when {
                  unsupportedCategoryMessage != null -> ""
                  effectiveSearchQuery.isNotBlank() -> "No matching pCloud media."
                  mediaFilter == 4 -> "This folder is empty."
                  else -> "No media found in your pCloud account."
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
              )
            }
          }
          if (supportsGrouping && selectedCloudGroup == null) {
            items(groupedEntries, key = { it.first }) { (name, members) ->
              PCloudGroupCard(name, members.size) { selectedCloudGroup = name }
            }
          } else items(filteredEntries, key = { (it.folderId ?: it.fileId).toString() }) { entry ->
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

private fun isShowEntry(entry: PCloudEntry): Boolean {
  val episodePattern = Regex("(?i).*(s\\d{1,2}e\\d{1,2}|\\d{1,2}x\\d{1,2}).*")
  val path = "/${entry.path.trim('/')}/"
  return episodePattern.matches(entry.name) || episodePattern.matches(entry.path) ||
    listOf("/tv/", "/series/", "/shows/", "/seasons/").any { path.contains(it, true) }
}

private fun isOtherVideoEntry(entry: PCloudEntry): Boolean {
  val path = "/${entry.path.replace('\\', '/').trim('/')}/".lowercase()
  return listOf("/dcim/", "/camera/", "/screenshots/", "/screen recordings/", "/screenrecord/", "/downloads/", "/whatsapp video/", "/telegram/", "/recordings/").any(path::contains)
}

private fun cloudGroupName(entry: PCloudEntry, section: String, category: String): String {
  val parts = entry.path.replace('\\', '/').split('/').filter(String::isNotBlank)
  val folders = parts.dropLast(1)
  if (section == "Photos" && category == "Years") {
    return Regex("(?:19|20)\\d{2}").find(entry.path)?.value ?: "Unknown year"
  }
  if (section == "Music" && category == "Genres") {
    val genreIndex = folders.indexOfLast { it.equals("genre", true) || it.equals("genres", true) }
    return folders.getOrNull(genreIndex + 1) ?: "Unsorted"
  }
  if (section == "Music" && category == "Playlists") {
    val playlistIndex = folders.indexOfLast { it.equals("playlists", true) }
    return folders.getOrNull(playlistIndex + 1) ?: "Unsorted"
  }
  return when {
    section == "Music" && category == "Artists" -> folders.getOrNull(folders.lastIndex - 1) ?: folders.lastOrNull() ?: "Unsorted"
    else -> folders.lastOrNull() ?: "Unsorted"
  }
}

@Composable
private fun PCloudGroupCard(name: String, count: Int, onClick: () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    shape = RoundedCornerShape(18.dp),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
      Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
