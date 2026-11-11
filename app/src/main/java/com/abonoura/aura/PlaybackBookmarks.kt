package com.abonoura.aura

import android.content.Context
import java.security.MessageDigest
import kotlin.math.abs

const val MAX_PLAYBACK_BOOKMARKS = 30
private const val BOOKMARK_DUPLICATE_WINDOW_MS = 1_000L

/** Adds a position without changing existing marks when the list is full or the mark is nearby. */
fun addBookmark(positions: List<Long>, positionMs: Long): List<Long> {
    require(positionMs >= 0L)
    val current = normalizeBookmarks(positions)
    if (current.size >= MAX_PLAYBACK_BOOKMARKS || current.any { abs(it - positionMs) <= BOOKMARK_DUPLICATE_WINDOW_MS }) return current
    return (current + positionMs).sorted()
}

/** Playback positions only, kept in app-private preferences. Sources are represented by a hash. */
object PlaybackBookmarks {
    fun note(context:Context,source:String,positionMs:Long):String=preferences(context).getString("note:${sourceKey(source)}:$positionMs","").orEmpty()
    fun saveNote(context:Context,source:String,positionMs:Long,note:String) {preferences(context).edit().putString("note:${sourceKey(source)}:$positionMs",note.take(1000)).apply()}
    @Synchronized
    fun load(context: Context, source: String): List<Long> {
        val encoded = preferences(context).getString(sourceKey(source), "").orEmpty()
        return normalizeBookmarks(encoded.split(',').mapNotNull { it.toLongOrNull() })
    }

    @Synchronized
    fun add(context: Context, source: String, positionMs: Long): List<Long> {
        val result = addBookmark(load(context, source), positionMs)
        save(context, source, result)
        return result
    }

    @Synchronized
    fun remove(context: Context, source: String, positionMs: Long): List<Long> {
        require(positionMs >= 0L)
        val result = load(context, source).filterNot { it == positionMs }
        save(context, source, result)
        preferences(context).edit().remove("note:${sourceKey(source)}:$positionMs").apply()
        return result
    }

    private fun save(context: Context, source: String, positions: List<Long>) {
        val editor = preferences(context).edit()
        val key = sourceKey(source)
        if (positions.isEmpty()) editor.remove(key)
        else editor.putString(key, positions.joinToString(","))
        editor.apply()
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences("playback_bookmarks", Context.MODE_PRIVATE)

    private fun sourceKey(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

private fun normalizeBookmarks(positions: List<Long>): List<Long> = buildList {
    for (position in positions.filter { it >= 0L }.sorted()) {
        if (lastOrNull()?.let { position - it <= BOOKMARK_DUPLICATE_WINDOW_MS } != true) add(position)
        if (size == MAX_PLAYBACK_BOOKMARKS) break
    }
}
