package com.abonoura.aura

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class Video(
    val id: Long,
    val uri: Uri,
    val path: String,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val folder: String,
    val folderId: Long,
    val dateAdded: Long,
)

data class Folder(val id: Long, val name: String, val videos: List<Video>)

object Media {
    /** Every video the device's media library knows about. */
    suspend fun loadVideos(context: Context): List<Video> = withContext(Dispatchers.IO) {
        val out = ArrayList<Video>()
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val columns = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.DATE_ADDED,
        )
        runCatching {
            context.contentResolver.query(collection, columns, null, null, null)?.use { c ->
                val id = c.getColumnIndexOrThrow(columns[0])
                val data = c.getColumnIndexOrThrow(columns[1])
                val name = c.getColumnIndexOrThrow(columns[2])
                val duration = c.getColumnIndexOrThrow(columns[3])
                val size = c.getColumnIndexOrThrow(columns[4])
                val width = c.getColumnIndexOrThrow(columns[5])
                val height = c.getColumnIndexOrThrow(columns[6])
                val bucket = c.getColumnIndexOrThrow(columns[7])
                val bucketId = c.getColumnIndexOrThrow(columns[8])
                val added = c.getColumnIndexOrThrow(columns[9])
                while (c.moveToNext()) {
                    val path = c.getString(data).orEmpty()
                    if (path.isBlank()) continue
                    val videoId = c.getLong(id)
                    out += Video(
                        id = videoId,
                        uri = ContentUris.withAppendedId(collection, videoId),
                        path = path,
                        title = c.getString(name)?.substringBeforeLast('.').orEmpty().ifBlank { path.substringAfterLast('/') },
                        durationMs = c.getLong(duration),
                        sizeBytes = c.getLong(size),
                        width = c.getInt(width),
                        height = c.getInt(height),
                        folder = c.getString(bucket) ?: path.substringBeforeLast('/').substringAfterLast('/'),
                        folderId = c.getLong(bucketId),
                        dateAdded = c.getLong(added),
                    )
                }
            }
        }
        out
    }

    fun folders(videos: List<Video>): List<Folder> =
        videos.groupBy { it.folderId }
            .map { (id, list) -> Folder(id, list.first().folder, list.sortedBy { it.title.lowercase() }) }
            .sortedBy { it.name.lowercase() }

    // ---- small pictures of the videos

    private val thumbnails = LruCache<Long, ImageBitmap>(120)

    fun cachedThumbnail(id: Long): ImageBitmap? = thumbnails.get(id)

    suspend fun thumbnail(context: Context, video: Video): ImageBitmap? = withContext(Dispatchers.IO) {
        thumbnails.get(video.id)?.let { return@withContext it }
        val bitmap: Bitmap? = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(video.uri, Size(320, 180), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    context.contentResolver, video.id, MediaStore.Video.Thumbnails.MINI_KIND, null,
                )
            }
        }.getOrNull()
        bitmap?.asImageBitmap()?.also { thumbnails.put(video.id, it) }
    }
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}

fun resolutionLabel(width: Int, height: Int): String {
    val shortSide = minOf(width, height)
    val longSide = maxOf(width, height)
    return when {
        shortSide <= 0 -> ""
        longSide >= 3800 || shortSide >= 2100 -> "4K"
        longSide >= 2500 || shortSide >= 1400 -> "1440p"
        longSide >= 1900 || shortSide >= 1000 -> "1080p"
        longSide >= 1200 || shortSide >= 700 -> "720p"
        else -> "${shortSide}p"
    }
}
