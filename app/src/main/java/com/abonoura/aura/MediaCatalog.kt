package com.abonoura.aura

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LibraryItem(
    val source: String, val title: String, val type: MediaType,
    val durationMs: Long = 0, val sizeBytes: Long = 0,
    val folder: String = "مضاف يدويًا", val dateAdded: Long = 0,
    val artist: String = "", val album: String = "", val width: Int = 0, val height: Int = 0,
) {
    fun playRequest() = PlayRequest(source, title, type)
    internal fun json() = JSONObject().put("source",source).put("title",title).put("type",type.name)
        .put("duration",durationMs).put("size",sizeBytes).put("folder",folder).put("added",dateAdded).put("artist",artist).put("album",album)
    companion object {
        internal fun fromJson(json: JSONObject): LibraryItem? {
            val source=json.optString("source")
            if(source.isBlank())return null
            return LibraryItem(source,json.optString("title").ifBlank { "وسائط" }, MediaType.entries.firstOrNull { it.name==json.optString("type") } ?: MediaTypes.classify(source),
                json.optLong("duration"),json.optLong("size"),json.optString("folder").ifBlank { "مضاف يدويًا" },json.optLong("added"),json.optString("artist"),json.optString("album"))
        }
    }
}

object MediaCatalog {
    suspend fun load(context: Context, access: LibraryAccess): List<LibraryItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<LibraryItem>()
        if(access.videos) items += Media.loadVideos(context).map { LibraryItem(it.uri.toString(),it.title,MediaType.Video,it.durationMs,it.sizeBytes,it.folder,it.dateAdded,width=it.width,height=it.height) }
        if(access.audio) {
            val collection=MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val columns=arrayOf(MediaStore.Audio.Media._ID,MediaStore.Audio.Media.DISPLAY_NAME,MediaStore.Audio.Media.DURATION,MediaStore.Audio.Media.SIZE,MediaStore.Audio.Media.DATA,MediaStore.Audio.Media.DATE_ADDED,MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.ALBUM)
            runCatching { context.contentResolver.query(collection,columns,null,null,null)?.use { c ->
                while(c.moveToNext()) {
                    val id=c.getLong(0); val path=c.getString(4).orEmpty()
                    items += LibraryItem(ContentUris.withAppendedId(collection,id).toString(),c.getString(1).orEmpty().substringBeforeLast('.').ifBlank { "صوت" },MediaType.Audio,
                        c.getLong(2),c.getLong(3),File(path).parentFile?.name ?: "الصوت",c.getLong(5),c.getString(6).orEmpty().takeUnless { it=="<unknown>" }.orEmpty(),c.getString(7).orEmpty())
                }
            } }
        }
        (items + imported(context)).distinctBy { it.source }
    }
    fun inspect(context: Context, uri: Uri, fallback: String? = null): LibraryItem {
        var name=fallback ?: uri.lastPathSegment ?: "وسائط";var size=0L
        runCatching { context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { c ->
            if(c.moveToFirst()) { name=c.getString(0) ?: name;size=c.getLong(1) }
        } }
        return LibraryItem(uri.toString(),name,MediaTypes.classify(name,runCatching { context.contentResolver.getType(uri) }.getOrNull()),sizeBytes=size,dateAdded=System.currentTimeMillis()/1000)
    }
    private fun prefs(context: Context)=context.getSharedPreferences("aura_catalog",Context.MODE_PRIVATE)
    fun imported(context: Context): List<LibraryItem> = runCatching {
        val array=JSONArray(prefs(context).getString("imported","[]"));(0 until array.length()).mapNotNull { LibraryItem.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    @Synchronized fun remember(context: Context,item: LibraryItem) {
        rememberAll(context,listOf(item))
    }
    @Synchronized fun rememberAll(context:Context,items:List<LibraryItem>) {
        val array=JSONArray();(items+imported(context)).distinctBy{it.source}.take(5000).forEach { array.put(it.json()) }
        prefs(context).edit().putString("imported",array.toString()).apply()
    }
    @Synchronized fun forget(context: Context,source: String) {
        val array=JSONArray();imported(context).filter { it.source!=source }.forEach { array.put(it.json()) }
        prefs(context).edit().putString("imported",array.toString()).apply()
    }
}
