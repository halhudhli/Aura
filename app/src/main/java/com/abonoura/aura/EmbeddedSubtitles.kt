package com.abonoura.aura

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

data class EmbeddedTextTrack(val index:Int,val language:String,val mime:String)
object EmbeddedSubtitles {
    private val supported=setOf("text/3gpp","application/x-subrip","text/vtt","text/x-ssa")
    suspend fun tracks(context:Context,request:PlayRequest):List<EmbeddedTextTrack> = withContext(Dispatchers.IO) {
        val extractor=open(context,request)
        try {(0 until extractor.trackCount).mapNotNull {index->val format=extractor.getTrackFormat(index);val mime=format.getString(MediaFormat.KEY_MIME).orEmpty()
            if(mime in supported)EmbeddedTextTrack(index,runCatching{format.getString(MediaFormat.KEY_LANGUAGE)}.getOrNull().orEmpty(),mime)else null
        }}finally{extractor.release()}
    }
    suspend fun timeline(context:Context,request:PlayRequest,track:EmbeddedTextTrack):List<SubtitleCue> = withContext(Dispatchers.IO) {
        val extractor=open(context,request)
        try {
            extractor.selectTrack(track.index)
            val buffer=ByteBuffer.allocate(256*1024);val out=mutableListOf<SubtitleCue>()
            while(out.size<20000) {
                currentCoroutineContext().ensureActive();buffer.clear()
                val size=extractor.readSampleData(buffer,0);if(size<0)break
                require(size<=buffer.capacity()){"جملة ترجمة كبيرة جدًا"}
                require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED==0){"الترجمة محمية ولا يمكن استخراجها"}
                val start=(extractor.sampleTime/1000).coerceAtLeast(0);val bytes=ByteArray(size);buffer.position(0);buffer.get(bytes)
                val text=if(track.mime=="text/3gpp" && size>=2) {
                    val length=(((bytes[0].toInt() and 255) shl 8)+(bytes[1].toInt() and 255)).coerceAtMost(size-2)
                    String(bytes,2,length,Charsets.UTF_8)
                }else SubtitleText.decode(bytes).let{raw->SubtitleCues.parse(raw).takeIf{it.isNotEmpty()}?.joinToString("\n"){it.text} ?: raw}
                if(out.isNotEmpty() && start>out.last().startMs)out[out.lastIndex]=out.last().copy(endMs=minOf(start,out.last().endMs))
                if(text.isNotBlank())out+=SubtitleCue(start,start+4000,SubtitleCues.displayText(text))
                if(!extractor.advance())break
            }
            out.filter{it.text.isNotBlank() && it.endMs>it.startMs}
        }finally{extractor.release()}
    }
    private fun open(context:Context,request:PlayRequest):MediaExtractor {
        val extractor=MediaExtractor()
        try {
            val source=if(request.source.startsWith("aura-network://"))NetworkRelay.url(context,request.source)else request.source
            if(source.startsWith("content:")||source.startsWith("file:"))extractor.setDataSource(context,Uri.parse(source),null)
            else if(source.startsWith("http"))extractor.setDataSource(source,request.headers)else extractor.setDataSource(source)
            return extractor
        }catch(e:Exception){extractor.release();throw e}
    }
}
