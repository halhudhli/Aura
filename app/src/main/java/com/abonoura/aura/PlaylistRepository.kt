package com.abonoura.aura

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class SavedPlaylist(val id:String,val name:String,val source:String,val entries:List<PlaylistEntry>,val epgUrl:String="",val updated:Long=System.currentTimeMillis())

object PlaylistRepository {
    private const val LIMIT=12*1024*1024
    private fun directory(context:Context)=File(context.filesDir,"playlists").apply { mkdirs() }
    suspend fun load(context:Context):List<SavedPlaylist> = withContext(Dispatchers.IO) { directory(context).listFiles().orEmpty().filter { it.extension=="json" }.mapNotNull { runCatching { decode(JSONObject(it.readText())) }.getOrNull() }.sortedByDescending { it.updated } }
    suspend fun import(context:Context,source:String,name:String?=null):SavedPlaylist=withContext(Dispatchers.IO) {
        val xtream=if(source.startsWith("aura-xtream://"))Xtream.playlist(context,source)else null
        val parsed=xtream?.second ?: run {
            val bytes=if(source.startsWith("http://") || source.startsWith("https://"))fetch(source) else if(source.startsWith("aura-network://"))NetworkSources.open(context,source,0).stream.use{readLimited(it,LIMIT)}else context.contentResolver.openInputStream(Uri.parse(source))?.use { readLimited(it,LIMIT) } ?: error("تعذّر قراءة القائمة")
            var missingLocalAccess=false
            val parsed=PlaylistParser.parse(SubtitleText.decode(bytes),source){value,base->
                if(base.startsWith("content://") && value.isNotBlank() && !runCatching{java.net.URI(value.replace(" ","%20")).isAbsolute}.getOrDefault(false)) {
                    val resolved=resolveDocument(context,base,value)
                    if(resolved==null)missingLocalAccess=true
                    resolved
                }else PlaylistParser.resolve(value,base)
            }
            require(!missingLocalAccess){"القائمة تحتوي ملفات محلية؛ أضف المجلد الذي يحتويها عبر «إضافة مجلد»، ثم أعد فتح القائمة"}
            parsed
        }
        require(!parsed.isHls) { "هذا رابط بث HLS؛ افتحه كفيديو مباشر" }
        require(parsed.entries.isNotEmpty()) { "لم نعثر على عناصر قابلة للتشغيل في القائمة" }
        val id=MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        val saved=SavedPlaylist(id,name?.trim()?.takeIf { it.isNotEmpty() } ?: xtream?.first ?: "قائمتي",source,parsed.entries,parsed.epgUrl)
        save(context,saved)
        saved
    }
    suspend fun save(context:Context,playlist:SavedPlaylist)=withContext(Dispatchers.IO) {
        val entries=JSONArray();playlist.entries.forEach { e ->
            entries.put(JSONObject().put("url",e.source).put("title",e.title).put("group",e.group).put("logo",e.logo).put("epgId",e.epgId).put("duration",e.durationSeconds).put("headers",JSONObject(e.headers)))
        }
        val json=JSONObject().put("id",playlist.id).put("name",playlist.name).put("source",playlist.source).put("epg",playlist.epgUrl).put("updated",playlist.updated).put("entries",entries)
        val dir=directory(context);val tmp=File.createTempFile("playlist-",".tmp",dir)
        try { tmp.writeText(json.toString());check(tmp.renameTo(File(dir,"${playlist.id}.json"))) }finally { tmp.delete() }
    }
    suspend fun delete(context:Context,id:String)=withContext(Dispatchers.IO) { require(id.matches(Regex("[a-f0-9]{24}")));File(directory(context),"$id.json").delete() }
    private fun decode(json:JSONObject):SavedPlaylist {
        val array=json.getJSONArray("entries")
        val entries=(0 until array.length()).map { i -> val e=array.getJSONObject(i);val headers=e.optJSONObject("headers")
            PlaylistEntry(e.getString("url"),e.optString("title"),e.optString("group"),e.optString("logo"),e.optString("epgId"),e.optLong("duration",-1),headers?.keys()?.asSequence()?.associateWith { headers.getString(it) }.orEmpty())
        }
        return SavedPlaylist(json.getString("id"),json.getString("name"),json.getString("source"),entries,json.optString("epg"),json.optLong("updated"))
    }
    private fun resolveDocument(context:Context,base:String,value:String):String?=runCatching {
        val uri=Uri.parse(base);val id=android.provider.DocumentsContract.getDocumentId(uri)
        val parent=id.substringBeforeLast('/',"")
        if(parent.isEmpty())return null
        val path=java.nio.file.Paths.get(parent,Uri.decode(value)).normalize().toString()
        val tree=context.contentResolver.persistedUriPermissions.firstOrNull{grant->grant.isReadPermission && runCatching {
            val root=android.provider.DocumentsContract.getTreeDocumentId(grant.uri)
            path==root || path.startsWith(root.trimEnd('/')+"/")
        }.getOrDefault(false)} ?: return null
        android.provider.DocumentsContract.buildDocumentUriUsingTree(tree.uri,path).toString()
    }.getOrNull()
    private fun fetch(source:String):ByteArray {
        val c=URL(source).openConnection() as HttpURLConnection
        try { c.connectTimeout=15000;c.readTimeout=20000;c.setRequestProperty("User-Agent","Aura/2");require(c.responseCode in 200..299) { "تعذّر تحميل القائمة (${c.responseCode})" };return c.inputStream.use { readLimited(it,LIMIT) } }
        finally { c.disconnect() }
    }
}
