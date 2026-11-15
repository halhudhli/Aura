package com.abonoura.aura

import java.net.URI

data class PlaylistEntry(val source: String,val title: String,val group: String="",val logo: String="",val epgId: String="",val durationSeconds: Long=-1,val headers: Map<String,String> = emptyMap()) {
    fun request()=PlayRequest(source,title,if(source.startsWith("aura-xtream://") || MediaTypes.classify(source)==MediaType.Playlist && !source.substringBefore('?').endsWith("m3u8",true))MediaType.Playlist else if(MediaTypes.classify(source)==MediaType.Audio)MediaType.Audio else MediaType.Video,headers=headers)
}
data class ParsedPlaylist(val entries: List<PlaylistEntry>,val isHls: Boolean=false,val epgUrl: String="")

object PlaylistParser {
    private val attributes=Regex("([A-Za-z0-9_-]+)\\s*=\\s*\"([^\"]*)\"|([A-Za-z0-9_-]+)\\s*=\\s*'([^']*)'")
    fun parse(text: String,base: String,resolveReference:(String,String)->String? = ::resolve): ParsedPlaylist {
        require(text.length<=12*1024*1024) { "القائمة كبيرة جدًا" }
        val lines=text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.toList()
        if(lines.any { it.startsWith("#EXT-X-",true) }) return ParsedPlaylist(emptyList(),true)
        if(lines.any { it.equals("[playlist]",true) })return pls(lines,base,resolveReference)
        var title="";var group="";var logo="";var id="";var duration=-1L;var headers=linkedMapOf<String,String>();var epg=""
        val out=mutableListOf<PlaylistEntry>()
        for(line in lines) {
            when {
                line.isBlank()->Unit
                line.startsWith("#EXTM3U",true)-> { val a=attrs(line);epg=resolveReference(a["url-tvg"] ?: a["x-tvg-url"].orEmpty(),base).orEmpty() }
                line.startsWith("#EXTINF:",true)-> {
                    val values=line.substringAfter(':');val a=attrs(values)
                    title=afterComma(values);group=a["group-title"].orEmpty();logo=resolveReference(a["tvg-logo"].orEmpty(),base).orEmpty();id=a["tvg-id"].orEmpty()
                    duration=values.substringBefore(' ').substringBefore(',').toDoubleOrNull()?.toLong() ?: -1
                }
                line.startsWith("#EXTGRP:",true)->group=line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:",true)-> {
                    val option=line.substringAfter(':');val key=option.substringBefore('=').lowercase();val value=option.substringAfter('=',"")
                    if(value.none { it=='\r' || it=='\n' })when(key) { "http-user-agent"->headers["User-Agent"]=value;"http-referrer","http-referer"->headers["Referer"]=value }
                }
                line.startsWith('#')->Unit
                else -> {
                    val source=resolveReference(line,base)
                    if(source!=null)out+=PlaylistEntry(source,title.ifBlank { MediaTypes.displayTitle(source) },group,logo,id,duration,headers.toMap())
                    title="";group="";logo="";id="";duration=-1;headers=linkedMapOf()
                    require(out.size<=30000) { "عدد عناصر القائمة كبير جدًا" }
                }
            }
        }
        return ParsedPlaylist(out.distinctBy { it.source to it.title },epgUrl=epg)
    }
    private fun attrs(line:String)=attributes.findAll(line).associate { match -> (match.groupValues[1].ifBlank { match.groupValues[3] }).lowercase() to match.groupValues[2].ifBlank { match.groupValues[4] } }
    private fun afterComma(line:String):String {
        var quote:Char?=null
        line.forEachIndexed { i,c ->
            if(c=='\"' || c=='\'') { if(quote==c)quote=null else if(quote==null)quote=c }
            if(c==',' && quote==null)return line.substring(i+1).trim()
        }
        return ""
    }
    internal fun resolve(value:String,base:String):String? {
        if(value.isBlank())return null
        return runCatching {
            val cleaned=value.replace(" ","%20")
            val result=URI(base).resolve(cleaned).toString()
            result.takeIf { MediaTypes.playableScheme(it) }
        }.getOrNull()
    }
    private fun pls(lines:List<String>,base:String,resolveReference:(String,String)->String?):ParsedPlaylist {
        val values=lines.filter { it.contains('=') }.associate { it.substringBefore('=').lowercase() to it.substringAfter('=').trim() }
        val entries=values.keys.mapNotNull { Regex("file(\\d+)").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.sorted().take(30000).mapNotNull { i ->
            resolveReference(values["file$i"].orEmpty(),base)?.let { PlaylistEntry(it,values["title$i"].orEmpty().ifBlank { MediaTypes.displayTitle(it) },durationSeconds=values["length$i"]?.toLongOrNull() ?: -1) }
        }
        return ParsedPlaylist(entries)
    }
}
