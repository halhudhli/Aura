package com.abonoura.aura

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns

data class IncomingMedia(val uri: Uri, val title: String, val type: MediaType, val headers: Map<String, String> = emptyMap(), val startMs: Long = 0L)

/** Preserve content URIs: a URI grant does not imply that a raw filesystem path is readable. */
object IncomingMediaReader {
    fun read(intent: Intent?, resolver: ContentResolver): List<IncomingMedia> {
        if (intent == null) return emptyList()
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(stream(intent))
            Intent.ACTION_SEND_MULTIPLE -> streams(intent)
            else -> emptyList()
        }.ifEmpty { (0 until (intent.clipData?.itemCount ?: 0)).mapNotNull { intent.clipData?.getItemAt(it)?.uri } }
        val headers = buildMap {
            val raw = intent.getStringArrayExtra("headers")
            raw?.toList()?.chunked(2)?.filter { it.size == 2 }?.forEach { pair ->
                if (pair.none { it.contains('\r') || it.contains('\n') } && pair[0].matches(Regex("[A-Za-z0-9-]+"))) put(pair[0], pair[1])
            }
        }
        val start = when (val position = intent.extras?.get("position")) { is Number -> position.toLong(); else -> 0L }.coerceAtLeast(0)
        return uris.distinct().filter { MediaTypes.playableScheme(it.toString()) }.map { uri ->
            val name = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: intent.getStringExtra(Intent.EXTRA_TITLE) ?: intent.getStringExtra("title") ?: uri.lastPathSegment ?: "وسائط"
            val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: intent.type
            IncomingMedia(uri, name, MediaTypes.classify(name, mime), headers, start)
        }
    }
    private fun stream(intent: Intent): Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else {
        @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }
    private fun streams(intent: Intent): List<Uri> = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty() else {
        @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    }
}
