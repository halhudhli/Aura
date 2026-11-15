package com.abonoura.aura

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@Composable fun LibraryArtwork(item:LibraryItem,modifier:Modifier=Modifier,logo:String="") {
    val context=LocalContext.current
    val bitmap by produceState<Bitmap?>(null,item.source,logo) {
        value=withContext(Dispatchers.IO){runCatching {
            if(logo.startsWith("https://")||logo.startsWith("http://")) {
                val directory=File(context.cacheDir,"channel-logos").apply{mkdirs()}
                val key=MessageDigest.getInstance("SHA-256").digest(logo.toByteArray()).take(12).joinToString(""){"%02x".format(it)}
                val cache=File(directory,key)
                val bytes=if(cache.exists())cache.readBytes()else {
                    val c=URL(logo).openConnection() as HttpURLConnection
                    try {c.connectTimeout=4000;c.readTimeout=5000;c.inputStream.use{readLimited(it,1024*1024)}}finally{c.disconnect()}
                }
                val image=decodeArtwork(bytes)
                if(image!=null && !cache.exists()) {cache.writeBytes(bytes);directory.listFiles().orEmpty().sortedByDescending{it.lastModified()}.drop(120).forEach{it.delete()}}
                image
            }else if(item.source.startsWith("content:")||item.source.startsWith("file:")||item.source.startsWith('/')) {
                if(item.type==MediaType.Video && Build.VERSION.SDK_INT>=29 && item.source.startsWith("content:")) {
                    runCatching{context.contentResolver.loadThumbnail(Uri.parse(item.source),Size(256,144),null)}.getOrNull()
                }else {
                    val retriever=MediaMetadataRetriever()
                    try {
                        if(item.source.startsWith('/'))retriever.setDataSource(item.source)else retriever.setDataSource(context,Uri.parse(item.source))
                        if(item.type==MediaType.Audio)retriever.embeddedPicture?.takeIf{it.size<=4*1024*1024}?.let{decodeArtwork(it)}
                        else if(Build.VERSION.SDK_INT>=27)retriever.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,256,144)else null
                    }finally{retriever.release()}
                }
            }else null
        }.getOrNull()}
    }
    bitmap?.let{Image(it.asImageBitmap(),null,modifier,contentScale=ContentScale.Crop)}
        ?: Icon(if(item.type==MediaType.Audio)Icons.Rounded.Audiotrack else Icons.Rounded.PlayCircle,null,modifier,tint=LocalAura.current.accent)
}
private fun decodeArtwork(bytes:ByteArray):Bitmap? {
    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    if(bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192)return null
    val sample=(maxOf(bounds.outWidth,bounds.outHeight)/256).coerceAtLeast(1)
    return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample})
}
