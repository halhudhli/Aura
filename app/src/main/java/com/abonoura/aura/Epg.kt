package com.abonoura.aura

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory

data class TvProgramme(val channel:String,val title:String,val start:Long,val end:Long,val description:String="")
object Xmltv {
    fun timestamp(value:String):Long?=runCatching {
        val parts=value.trim().split(Regex("\\s+"));val digits=parts[0]
        require(digits.length>=12)
        val padded=digits.take(14).padEnd(14,'0')
        val time=LocalDateTime.parse(padded,DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val offset=parts.getOrNull(1)?.let {ZoneOffset.of(it)} ?: ZoneOffset.UTC
        time.toInstant(offset).toEpochMilli()
    }.getOrNull()
    fun parse(bytes:ByteArray,now:Long=System.currentTimeMillis()):List<TvProgramme> {
        require(bytes.size<=24*1024*1024)
        val result=mutableListOf<TvProgramme>();val text=StringBuilder()
        var channel="";var start=0L;var end=0L;var title="";var description="";var capture="";var inProgramme=false
        val factory=SAXParserFactory.newInstance().apply {isNamespaceAware=false}
        val reader=factory.newSAXParser().xmlReader
        runCatching{reader.setFeature("http://xml.org/sax/features/external-general-entities",false)}
        runCatching{reader.setFeature("http://xml.org/sax/features/external-parameter-entities",false)}
        runCatching{reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)}
        reader.entityResolver=org.xml.sax.EntityResolver { _,_ -> InputSource(StringReader("")) }
        reader.contentHandler=object:DefaultHandler() {
            override fun startElement(uri:String?,localName:String?,qName:String,attributes:Attributes) {
                if(qName=="programme") {
                    channel=attributes.getValue("channel").orEmpty();start=timestamp(attributes.getValue("start").orEmpty()) ?: 0
                    end=timestamp(attributes.getValue("stop").orEmpty()) ?: 0;title="";description="";inProgramme=true
                }
                if(inProgramme && qName in setOf("title","desc")) {capture=qName;text.setLength(0)}
            }
            override fun characters(ch:CharArray,start:Int,length:Int) { if(capture.isNotEmpty() && text.length<12000)text.append(ch,start,length.coerceAtMost(12000-text.length)) }
            override fun endElement(uri:String?,localName:String?,qName:String) {
                if(qName==capture) {if(qName=="title" && title.isEmpty())title=text.toString().trim();if(qName=="desc")description=text.toString().trim();capture=""}
                if(qName=="programme") {
                    if(channel.isNotBlank() && title.isNotBlank() && end>start && end>now-86400000 && start<now+7*86400000L && result.size<150000)result+=TvProgramme(channel,title,start,end,description)
                    inProgramme=false
                }
            }
        }
        reader.parse(InputSource(ByteArrayInputStream(bytes)))
        return result.sortedBy{it.start}
    }
}

object EpgRepository {
    suspend fun load(context:Context,id:String,url:String,refresh:Boolean=false):List<TvProgramme> = withContext(Dispatchers.IO) {
        require(id.matches(Regex("[a-f0-9]{24}")))
        val file=File(context.cacheDir,"epg-$id.xml")
        if(refresh || !file.exists() || System.currentTimeMillis()-file.lastModified()>6*3600000) {
            require(url.startsWith("http://")||url.startsWith("https://")) {"أضف رابط XMLTV صالحًا"}
            val c=URL(url).openConnection() as HttpURLConnection
            val bytes=try {c.connectTimeout=15000;c.readTimeout=25000;require(c.responseCode in 200..299){"تعذّر تحميل دليل البرامج"}
                c.inputStream.use {raw->val input=java.io.PushbackInputStream(raw,2);val magic=ByteArray(2);val n=input.read(magic);if(n>0)input.unread(magic,0,n)
                    val data=if(n==2 && magic[0]==0x1f.toByte() && magic[1]==0x8b.toByte())GZIPInputStream(input)else input
                    data.use{readLimited(it,24*1024*1024)}
                }
            }finally{c.disconnect()}
            // Parse before replacing a working cache.
            val programmes=Xmltv.parse(bytes)
            file.writeBytes(bytes);return@withContext programmes
        }
        Xmltv.parse(file.readBytes())
    }
}
