package app.marlboroadvance.mpvex.domain.network

/**
 * Represents a file or directory on a network share
 */
data class NetworkFile(
  val name: String,
  val path: String,
  val size: Long,
  val isDirectory: Boolean,
  val lastModified: Long = 0,
  val mimeType: String? = null,
)

/** Best-effort media type for network clients that do not provide MIME metadata. */
fun NetworkFile.resolvedMimeType(): String? {
  if (!mimeType.isNullOrBlank()) return mimeType
  return when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "avi" -> "video/x-msvideo"
    "mov" -> "video/quicktime"
    "wmv" -> "video/x-ms-wmv"
    "flv" -> "video/x-flv"
    "webm" -> "video/webm"
    "mpeg", "mpg" -> "video/mpeg"
    "3gp" -> "video/3gpp"
    "ts" -> "video/mp2t"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "aac" -> "audio/aac"
    "flac" -> "audio/flac"
    "ogg", "opus" -> "audio/ogg"
    "wav" -> "audio/wav"
    "wma" -> "audio/x-ms-wma"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "heic" -> "image/heic"
    "heif" -> "image/heif"
    else -> null
  }
}
