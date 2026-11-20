package app.marlboroadvance.mpvex.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.twotone.Repeat
import androidx.compose.material.icons.twotone.Shuffle
import androidx.compose.material.icons.twotone.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `is`.xyz.mpv.MPVLib

@Composable
fun MusicPlayerControls(
  viewModel: PlayerViewModel,
  onBackPress: () -> Unit,
  onPrevious: () -> Unit,
  onNext: () -> Unit,
) {
  val paused = viewModel.paused ?: true
  val title by MPVLib.propString["media-title"].collectAsState()
  val artist by MPVLib.propString["metadata/artist"].collectAsState()
  val precisePosition by viewModel.precisePosition.collectAsState()
  val preciseDuration by viewModel.preciseDuration.collectAsState()
  val shuffle by viewModel.shuffleEnabled.collectAsState()
  val repeat by viewModel.repeatMode.collectAsState()
  var seekValue by remember { mutableFloatStateOf(0f) }
  val duration = preciseDuration.coerceAtLeast(0f)
  val position = if (seekValue > 0f) seekValue else precisePosition.coerceIn(0f, duration)
  val primary = MaterialTheme.colorScheme.primary
  val secondary = MaterialTheme.colorScheme.tertiary

  Column(
    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 24.dp, vertical = 18.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = onBackPress) { Icon(Icons.AutoMirrored.TwoTone.ArrowBack, contentDescription = "Back") }
      Text("Now playing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.weight(0.6f))
    Surface(
      modifier = Modifier.fillMaxWidth().weight(4f).clip(RoundedCornerShape(28.dp)),
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      tonalElevation = 2.dp,
    ) {
      Box(
        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(primary.copy(alpha = 0.34f), secondary.copy(alpha = 0.18f), MaterialTheme.colorScheme.surfaceContainerHigh))),
        contentAlignment = Alignment.Center,
      ) {
        Icon(Icons.TwoTone.MusicNote, contentDescription = null, modifier = Modifier.size(104.dp), tint = primary)
      }
    }
    Spacer(Modifier.weight(0.55f))
    Text(title?.takeIf(String::isNotBlank) ?: "Unknown track", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(artist?.takeIf(String::isNotBlank) ?: "Unknown artist", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(22.dp))
    Slider(
      value = position,
      onValueChange = { seekValue = it },
      onValueChangeFinished = { viewModel.seekTo(seekValue.toInt()); seekValue = 0f },
      valueRange = 0f..duration.coerceAtLeast(1f),
      modifier = Modifier.fillMaxWidth(),
      enabled = duration > 0f,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(formatTime(position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = { viewModel.toggleShuffle() }) {
        Icon(Icons.TwoTone.Shuffle, contentDescription = "Shuffle", tint = if (shuffle) primary else MaterialTheme.colorScheme.onSurfaceVariant)
      }
      IconButton(onClick = onPrevious, modifier = Modifier.size(52.dp)) {
        Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous track", modifier = Modifier.size(34.dp))
      }
      Surface(onClick = { viewModel.pauseUnpause() }, shape = RoundedCornerShape(24.dp), color = primary) {
        Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) {
          Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = if (paused) "Play" else "Pause", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(38.dp))
        }
      }
      IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) {
        Icon(Icons.Filled.SkipNext, contentDescription = "Next track", modifier = Modifier.size(34.dp))
      }
      IconButton(onClick = { viewModel.cycleRepeatMode() }) {
        Icon(Icons.TwoTone.Repeat, contentDescription = "Repeat", tint = if (repeat != RepeatMode.OFF) primary else MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Spacer(Modifier.weight(0.7f))
  }
}

private fun formatTime(seconds: Float): String {
  val total = seconds.toInt().coerceAtLeast(0)
  return "%d:%02d".format(total / 60, total % 60)
}
