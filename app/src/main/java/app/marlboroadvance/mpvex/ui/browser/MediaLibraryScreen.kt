package app.marlboroadvance.mpvex.ui.browser

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Size
import android.Manifest
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Album
import androidx.compose.material.icons.twotone.Cloud
import androidx.compose.material.icons.twotone.Image
import androidx.compose.material.icons.twotone.MusicNote
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material.icons.twotone.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import app.marlboroadvance.mpvex.presentation.Screen
import app.marlboroadvance.mpvex.ui.player.PlayerActivity
import app.marlboroadvance.mpvex.ui.browser.pcloud.PCloudScreen
import app.marlboroadvance.mpvex.ui.utils.LocalBackStack
import app.marlboroadvance.mpvex.utils.media.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.Locale

private enum class LibraryCategory(val title: String) {
  Movies("Movies"), Shows("TV Shows"), Photos("Photos"), Music("Music"), Folders("Folders"),
}

private data class LibraryMedia(
  val id: Long,
  val title: String,
  val subtitle: String,
  val uri: Uri,
  val mimeType: String,
  val duration: Long = 0L,
  val path: String = "",
  val albumId: Long = -1L,
)

@Serializable
object MediaLibraryScreen : Screen {
  @OptIn(com.google.accompanist.permissions.ExperimentalPermissionsApi::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backstack = LocalBackStack.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var categoryIndex by rememberSaveable { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var selectedPhoto by remember { mutableStateOf<LibraryMedia?>(null) }
    var media by remember { mutableStateOf(emptyList<LibraryMedia>()) }
    var loading by remember { mutableStateOf(true) }
    val category = LibraryCategory.entries[categoryIndex]
    val mediaPermissions = remember {
      if (Build.VERSION.SDK_INT >= 34) listOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
      ) else if (Build.VERSION.SDK_INT >= 33) listOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_AUDIO,
      ) else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val permissionState = rememberMultiplePermissionsState(mediaPermissions)
    val hasAllFilesAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
    val categoryPermission = when {
      Build.VERSION.SDK_INT < 33 -> Manifest.permission.READ_EXTERNAL_STORAGE
      category == LibraryCategory.Photos -> Manifest.permission.READ_MEDIA_IMAGES
      category == LibraryCategory.Music -> Manifest.permission.READ_MEDIA_AUDIO
      else -> Manifest.permission.READ_MEDIA_VIDEO
    }
    val hasMediaAccess = hasAllFilesAccess ||
      context.checkSelfPermission(categoryPermission) == PackageManager.PERMISSION_GRANTED ||
      (category == LibraryCategory.Photos && Build.VERSION.SDK_INT >= 34 &&
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)

    DisposableEffect(lifecycleOwner) {
      val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) refreshKey++
      }
      lifecycleOwner.lifecycle.addObserver(observer)
      onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(category, refreshKey) {
      if (category == LibraryCategory.Folders) return@LaunchedEffect
      loading = true
      media = withContext(Dispatchers.IO) { loadMedia(context, category) }
      loading = false
    }

    val filtered = remember(media, query, category) {
      val tvEpisode = Regex("(?i).*(s\\d{1,2}e\\d{1,2}|\\d{1,2}x\\d{1,2}).*")
      media.filter { item ->
        val isShow = tvEpisode.matches(item.title) || tvEpisode.matches(item.path)
        val matchesCategory = when (category) {
          LibraryCategory.Movies -> !isShow
          LibraryCategory.Shows -> isShow
          else -> true
        }
        matchesCategory && (query.isBlank() || item.title.contains(query, true) || item.subtitle.contains(query, true) || item.path.contains(query, true))
      }
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text("Library", fontWeight = FontWeight.SemiBold) },
          actions = {
            IconButton(onClick = { backstack.add(PCloudScreen) }) {
              Icon(Icons.TwoTone.Cloud, contentDescription = "Open pCloud library")
            }
            IconButton(onClick = { showSearch = !showSearch; if (!showSearch) query = "" }) {
              Icon(Icons.TwoTone.Search, contentDescription = "Search media")
            }
          },
        )
      },
    ) { padding ->
      Column(Modifier.fillMaxSize().padding(padding)) {
        ScrollableTabRow(selectedTabIndex = categoryIndex, edgePadding = 16.dp) {
          LibraryCategory.entries.forEachIndexed { index, item ->
            Tab(
              selected = categoryIndex == index,
              onClick = { categoryIndex = index; query = ""; showSearch = false },
              text = { Text(item.title) },
            )
          }
        }
        if (showSearch) {
          androidx.compose.material3.TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search ${category.title.lowercase()}") },
            singleLine = true,
          )
        }

        when {
          category == LibraryCategory.Folders -> app.marlboroadvance.mpvex.ui.browser.folderlist.FolderListScreen.Content()
          !hasMediaAccess -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text("Allow media access to build your library", style = MaterialTheme.typography.titleMedium)
              Spacer(Modifier.height(12.dp))
              Button(onClick = { permissionState.launchMultiplePermissionRequest() }) { Text("Allow access") }
            }
          }
          loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator()
          }
          filtered.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(categoryIcon(category), contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
              Spacer(Modifier.height(12.dp))
              Text("No ${category.title.lowercase()} found", style = MaterialTheme.typography.titleMedium)
              Text("Media on this device will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
          category == LibraryCategory.Photos -> LazyVerticalGrid(
            columns = GridCells.Adaptive(118.dp),
            contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            items(filtered.size, key = { filtered[it].id }) { index ->
              PhotoTile(filtered[index], onClick = { selectedPhoto = filtered[index] })
            }
          }
          category == LibraryCategory.Music -> LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            items(filtered, key = { it.id }) { item ->
              MusicRow(item) { playAudioQueue(filtered, filtered.indexOf(item), context) }
            }
          }
          else -> LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            items(filtered, key = { it.id }) { item ->
              MediaRow(item, category) { MediaUtils.playFile(item.uri, context, "media_library") }
            }
          }
        }
      }
    }

    selectedPhoto?.let { photo ->
      PhotoViewer(photo, onDismiss = { selectedPhoto = null })
    }
  }
}

private fun categoryIcon(category: LibraryCategory) = when (category) {
  LibraryCategory.Photos -> Icons.TwoTone.Image
  LibraryCategory.Music -> Icons.TwoTone.MusicNote
  LibraryCategory.Folders -> Icons.TwoTone.Album
  else -> Icons.TwoTone.VideoLibrary
}

private suspend fun loadMedia(context: android.content.Context, category: LibraryCategory): List<LibraryMedia> = try {
  val resolver = context.contentResolver
  val (collection, projection, type) = when (category) {
    LibraryCategory.Photos -> Triple(
      MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
      arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATA),
      "photo",
    )
    LibraryCategory.Music -> Triple(
      MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
      arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.IS_MUSIC),
      "audio",
    )
    else -> Triple(
      MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
      arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATA, MediaStore.Video.Media.DATE_ADDED),
      "video",
    )
  }
  val results = mutableListOf<LibraryMedia>()
  resolver.query(collection, projection, if (type == "audio") "${MediaStore.Audio.Media.IS_MUSIC} != 0" else null, null, if (type == "audio") "${MediaStore.Audio.Media.ARTIST} COLLATE NOCASE, ${MediaStore.Audio.Media.ALBUM} COLLATE NOCASE, ${MediaStore.Audio.Media.TITLE} COLLATE NOCASE" else "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
    while (cursor.moveToNext()) {
      val id = cursor.getLong(0)
      val title = cursor.getString(1)?.takeIf(String::isNotBlank) ?: "Untitled"
      val uri = ContentUris.withAppendedId(collection, id)
      when (type) {
        "photo" -> {
          val folder = cursor.getString(2).orEmpty()
          val mime = cursor.getString(3).orEmpty()
          val path = cursor.getString(5).orEmpty()
          results += LibraryMedia(id, title, folder, uri, mime, path = path)
        }
        "audio" -> {
          val artist = cursor.getString(2)?.takeUnless { it == "<unknown>" }.orEmpty()
          val album = cursor.getString(3)?.takeUnless { it == "<unknown>" }.orEmpty()
          val albumId = cursor.getLong(4)
          val mime = cursor.getString(5).orEmpty()
          val duration = cursor.getLong(6)
          val path = cursor.getString(7).orEmpty()
          results += LibraryMedia(id, title, listOf(artist, album).filter(String::isNotBlank).joinToString(" · "), uri, mime, duration, path, albumId)
        }
        else -> {
          val folder = cursor.getString(2).orEmpty()
          val mime = cursor.getString(3).orEmpty()
          val duration = cursor.getLong(4)
          val path = cursor.getString(5).orEmpty()
          results += LibraryMedia(id, title.substringBeforeLast('.'), folder, uri, mime, duration, path)
        }
      }
    }
  }
  results
} catch (_: Exception) {
  emptyList()
}

@Composable
private fun MediaRow(item: LibraryMedia, category: LibraryCategory, onClick: () -> Unit) {
  Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      if (category == LibraryCategory.Movies || category == LibraryCategory.Shows) VideoPoster(item)
      else Icon(categoryIcon(category), contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
      Column(Modifier.weight(1f)) {
        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        Text(item.subtitle.ifBlank { item.mimeType }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      if (item.duration > 0) Text(formatDuration(item.duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun VideoPoster(item: LibraryMedia) {
  val context = LocalContext.current
  val bitmap = produceState<Bitmap?>(null, item.uri) {
    value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.loadThumbnail(item.uri, Size(160, 100), null) }.getOrNull()
      }
    } else null
  }.value
  if (bitmap != null) {
    Image(bitmap.asImageBitmap(), contentDescription = "Video thumbnail", Modifier.size(width = 66.dp, height = 48.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
  } else {
    Icon(Icons.TwoTone.VideoLibrary, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
  }
}

@Composable
private fun MusicRow(item: LibraryMedia, onClick: () -> Unit) {
  val context = LocalContext.current
  val cover = produceState<Bitmap?>(null, item.albumId) {
    value = withContext(Dispatchers.IO) {
      runCatching {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, item.albumId)
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
      }.getOrNull()
    }
  }.value
  Row(
    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    if (cover != null) Image(cover.asImageBitmap(), contentDescription = "Album art", Modifier.size(52.dp), contentScale = ContentScale.Crop)
    else Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
      Icon(Icons.TwoTone.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
    Column(Modifier.weight(1f)) {
      Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
      Text(item.subtitle.ifBlank { "Unknown artist" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(formatDuration(item.duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun PhotoTile(item: LibraryMedia, onClick: () -> Unit) {
  val context = LocalContext.current
  val bitmap = produceState<Bitmap?>(null, item.uri) {
    value = withContext(Dispatchers.IO) { decodePreview(context, item.uri, 640) }
  }.value
  Card(onClick = onClick, shape = RoundedCornerShape(12.dp)) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainer)) {
      bitmap?.let { Image(it.asImageBitmap(), contentDescription = item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
  }
}

@Composable
private fun PhotoViewer(photo: LibraryMedia, onDismiss: () -> Unit) {
  val context = LocalContext.current
  val bitmap = produceState<Bitmap?>(null, photo.uri) {
    value = withContext(Dispatchers.IO) { decodePreview(context, photo.uri, 2400) }
  }.value
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    title = { Text(photo.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    text = {
      Box(Modifier.fillMaxWidth().height(480.dp), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = photo.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
      }
    },
  )
}

private fun decodePreview(context: android.content.Context, uri: Uri, maxDimension: Int): Bitmap? = runCatching {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
  var sample = 1
  while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
  val options = BitmapFactory.Options().apply { inSampleSize = sample }
  context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}.getOrNull()

private fun playAudioQueue(items: List<LibraryMedia>, index: Int, context: android.content.Context) {
  val intent = Intent(Intent.ACTION_VIEW, items[index].uri).apply {
    setClass(context, PlayerActivity::class.java)
    setDataAndType(items[index].uri, items[index].mimeType.ifBlank { "audio/*" })
    putExtra("audio_only", true)
    putExtra("launch_source", "music_library")
    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    putParcelableArrayListExtra("playlist", ArrayList(items.map { it.uri }))
    putExtra("playlist_index", index)
  }
  context.startActivity(intent)
}

private fun formatDuration(milliseconds: Long): String {
  val seconds = milliseconds / 1000
  return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
}
