package com.abonoura.aura

import android.content.Context
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** A loopback-only HTTP range adapter. Credentials never enter the playback URL. */
object NetworkRelay {
    private var server:ServerSocket?=null
    private val sources=ConcurrentHashMap<String,String>()
    private val tokens=ConcurrentHashMap<String,String>()
    private val workers=Executors.newFixedThreadPool(4){Thread(it,"AuraNetwork").apply{isDaemon=true}}
    @Synchronized fun url(context:Context,source:String):String {
        if(server==null) {
            val socket=ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));server=socket
            val app=context.applicationContext
            Thread({while(!socket.isClosed)runCatching{val connection=socket.accept();workers.execute{serve(app,connection)}}},"AuraLoopback").apply{isDaemon=true;start()}
        }
        val existing=tokens[source]
        // Audio queues resolve their items up front; retain the complete supported playlist.
        val token=existing ?: UUID.randomUUID().toString().also{
            if(sources.size>=40000)sources.keys.firstOrNull()?.let{old->sources.remove(old)?.let{value->tokens.remove(value,old)}}
            sources[it]=source;tokens[source]=it
        }
        return "http://127.0.0.1:${server!!.localPort}/$token"
    }
    private fun serve(context:Context,socket:Socket) {
        socket.use { client ->runCatching {
            client.soTimeout=15000
            val reader=client.getInputStream().bufferedReader(Charsets.US_ASCII)
            val request=reader.readLine()?.split(' ') ?: return
            val method=request.getOrNull(0);val token=request.getOrNull(1)?.removePrefix("/")?.substringBefore('?')
            val output=client.getOutputStream()
            if(method !in setOf("GET","HEAD") || token==null || sources[token]==null){output.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray());return}
            var range:String?=null
            var headers=0
            while(headers++<64){val line=reader.readLine() ?: break;if(line.isEmpty())break;if(line.length>8192)return;if(line.startsWith("Range:",true))range=line.substringAfter(':').trim()}
            val initial=HttpRanges.initialOffset(range)
            var opened=NetworkSources.open(context,sources.getValue(token),initial)
            val requested=HttpRanges.resolve(range,opened.length)
            if(requested==null){opened.stream.close();output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${opened.length}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray());return}
            if(requested.start!=initial){opened.stream.close();opened=NetworkSources.open(context,sources.getValue(token),requested.start)}
            val offset=requested.start
            opened.stream.use{input->
                if(offset>=opened.length && opened.length>0){output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${opened.length}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray());return}
                val end=requested.end
                val length=requested.length
                val headers=buildString{append(if(requested.partial)"HTTP/1.1 206 Partial Content\r\n"else"HTTP/1.1 200 OK\r\n");append("Content-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\nContent-Length: $length\r\nConnection: close\r\n");if(requested.partial)append("Content-Range: bytes $offset-$end/${opened.length}\r\n");append("\r\n")}
                output.write(headers.toByteArray())
                if(method=="GET") {val buffer=ByteArray(64*1024);var remaining=length;while(remaining>0){val read=input.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt());if(read<0)break;output.write(buffer,0,read);remaining-=read}}
            }
        } }
    }
}
