package com.abonoura.aura

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.UUID

data class XtreamPortal(val id:String=UUID.randomUUID().toString(),val name:String,val server:String,val user:String,val password:String)
object Xtream {
    fun portals(context:Context):List<XtreamPortal> = runCatching{
        val array=JSONArray(PrivateSettings.get(context,"xtream_portals").ifBlank{"[]"})
        (0 until array.length()).map{array.getJSONObject(it)}.map{XtreamPortal(it.getString("id"),it.getString("name"),it.getString("server"),it.getString("user"),it.getString("password"))}
    }.getOrDefault(emptyList())
    fun save(context:Context,portal:XtreamPortal) {
        val uri=java.net.URI(portal.server)
        require(uri.scheme in setOf("http","https") && !uri.host.isNullOrBlank() && uri.userInfo==null){"عنوان الخدمة غير صالح"}
        val array=JSONArray();(listOf(portal)+portals(context).filter{it.id!=portal.id}).take(20).forEach{array.put(JSONObject().put("id",it.id).put("name",it.name).put("server",it.server.trimEnd('/')).put("user",it.user).put("password",it.password))}
        PrivateSettings.put(context,"xtream_portals",array.toString())
    }
    fun source(portal:XtreamPortal,kind:String)="aura-xtream://${portal.id}/$kind"
    suspend fun playlist(context:Context,source:String):Pair<String,ParsedPlaylist> = withContext(Dispatchers.IO) {
        val uri=Uri.parse(source);val portal=portals(context).firstOrNull{it.id==uri.host} ?: error("إعدادات الاشتراك غير موجودة")
        val segments=uri.pathSegments;val kind=segments.firstOrNull().orEmpty()
        val user=encode(portal.user);val pass=encode(portal.password)
        fun media(type:String,id:String,extension:String)="${portal.server.trimEnd('/')}/$type/$user/$pass/${encode(id)}.${extension.filter{it.isLetterOrDigit()}.ifBlank{"mp4"}}"
        val entries=when(kind) {
            "live","vod" -> {
                val categoryAction=if(kind=="live")"get_live_categories"else"get_vod_categories"
                val categories=JSONArray(fetch(portal,categoryAction));val groups=(0 until categories.length()).map{categories.getJSONObject(it)}.associate{it.optString("category_id") to it.optString("category_name")}
                val streams=JSONArray(fetch(portal,if(kind=="live")"get_live_streams"else"get_vod_streams"))
                (0 until streams.length().coerceAtMost(30000)).map {i->val s=streams.getJSONObject(i)
                    PlaylistEntry(media(if(kind=="live")"live"else"movie",s.get("stream_id").toString(),if(kind=="live")"ts"else s.optString("container_extension","mp4")),s.optString("name"),groups[s.optString("category_id")].orEmpty(),s.optString("stream_icon"),s.optString("epg_channel_id"))
                }
            }
            "series" -> if(segments.size==1) {
                val series=JSONArray(fetch(portal,"get_series"))
                (0 until series.length().coerceAtMost(30000)).map{i->val s=series.getJSONObject(i);PlaylistEntry(source(portal,"series/${s.get("series_id")}"),s.optString("name"),"المسلسلات",s.optString("cover"))}
            }else {
                val id=segments[1];require(id.matches(Regex("[0-9]+")))
                val info=JSONObject(fetch(portal,"get_series_info","series_id" to id));val episodes=info.optJSONObject("episodes") ?: error("لا توجد حلقات متاحة")
                episodes.keys().asSequence().sortedWith(compareBy<String>{it.toIntOrNull()?:Int.MAX_VALUE}.thenBy{it}).flatMap{season->
                    val values=episodes.getJSONArray(season)
                    (0 until values.length()).map{values.getJSONObject(it)}.sortedBy{it.optInt("episode_num")}.map{e->PlaylistEntry(media("series",e.get("id").toString(),e.optString("container_extension","mp4")),e.optString("title").ifBlank{"الحلقة ${e.optInt("episode_num")}"},"الموسم $season")}.asSequence()
                }.take(30000).toList()
            }
            else->error("نوع الاشتراك غير صالح")
        }
        portal.name to ParsedPlaylist(entries)
    }
    private fun encode(value:String)=URLEncoder.encode(value,"UTF-8").replace("+","%20")
    private fun fetch(portal:XtreamPortal,action:String,extra:Pair<String,String>?=null):String {
        val url="${portal.server.trimEnd('/')}/player_api.php?username=${encode(portal.user)}&password=${encode(portal.password)}&action=$action"+(extra?.let{"&${it.first}=${encode(it.second)}"} ?: "")
        val c=URL(url).openConnection() as HttpURLConnection
        try {c.connectTimeout=12000;c.readTimeout=25000;require(c.responseCode in 200..299){"تعذّر تحميل بيانات الاشتراك (${c.responseCode})"};return c.inputStream.use{String(readLimited(it,24*1024*1024),Charsets.UTF_8)}}finally{c.disconnect()}
    }
}
