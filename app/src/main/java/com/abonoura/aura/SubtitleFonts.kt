package com.abonoura.aura

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object SubtitleFonts {
    suspend fun import(context:Context,uri:Uri):String=withContext(Dispatchers.IO) {
        val bytes=context.contentResolver.openInputStream(uri)?.use{readLimited(it,8*1024*1024)} ?: error("تعذّر قراءة الخط")
        val family=family(bytes) ?: error("اختر خط TTF أو OTF يحتوي اسم العائلة")
        require(family.length in 1..150 && family.none{it=='\n'||it=='\r'})
        val file=File(File(context.filesDir,"fonts").apply{mkdirs()},"custom-${bytes.contentHashCode().toUInt()}.ttf")
        try {file.writeBytes(bytes);Typeface.createFromFile(file);family}catch(e:Exception){file.delete();throw e}
    }
    internal fun family(bytes:ByteArray):String?=runCatching {
        val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        fun u16(offset:Int)=buffer.getShort(offset).toInt() and 65535
        val count=u16(4);require(count<=200)
        var table=-1
        repeat(count){i->val offset=12+i*16;if(String(bytes,offset,4,Charsets.US_ASCII)=="name")table=buffer.getInt(offset+8)}
        require(table>=0)
        val records=u16(table+2);val strings=table+u16(table+4);require(records<=5000)
        val names=mutableListOf<Triple<Int,Int,String>>()
        repeat(records){i->
            val record=table+6+i*12;val platform=u16(record);val language=u16(record+4);val name=u16(record+6)
            val length=u16(record+8);val offset=strings+u16(record+10)
            if(name in setOf(1,16) && length in 1..300 && offset>=0 && offset+length<=bytes.size) {
                val text=String(bytes,offset,length,if(platform==0||platform==3)Charsets.UTF_16BE else Charsets.ISO_8859_1).trim().replace("\u0000","")
                names+=Triple(if(name==16)2 else 1,if(language==0x409)2 else if(language==0)1 else 0,text)
            }
        }
        names.sortedWith(compareByDescending<Triple<Int,Int,String>>{it.first}.thenByDescending{it.second}).firstOrNull()?.third
    }.getOrNull()
}
