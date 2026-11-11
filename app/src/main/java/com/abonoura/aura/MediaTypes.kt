package com.abonoura.aura

import java.net.URI
import java.util.Locale

enum class MediaType(val label: String) { Video("الفيديو"), Audio("الصوت"), Playlist("القوائم") }

object MediaTypes {
    private val audio = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "aiff", "alac", "ape")
    private val playlists = setOf("m3u", "m3u8", "pls")
    fun classify(name: String, mime: String? = null): MediaType {
        val extension = name.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase(Locale.ROOT)
        val normalized = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (extension in playlists || normalized in setOf("audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/x-scpls")) return MediaType.Playlist
        if (normalized.startsWith("audio/") || normalized in setOf("application/ogg", "application/x-ogg") || extension in audio) return MediaType.Audio
        return MediaType.Video
    }
    fun playableScheme(source: String): Boolean = runCatching {
        URI(source).scheme?.lowercase(Locale.ROOT) in setOf("content", "file", "http", "https", "rtsp", "rtmp", "rtp", "udp", "srt", "smb", "ftp", "sftp", "nfs","aura-network")
    }.getOrDefault(false)
    fun displayTitle(name: String): String = name.substringBefore('?').substringAfterLast('/').substringBeforeLast('.').ifBlank { "وسائط" }
}
