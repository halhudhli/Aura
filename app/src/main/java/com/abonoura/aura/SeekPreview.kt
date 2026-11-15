package com.abonoura.aura

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable fun SeekPreview(source:String,position:Long,modifier:Modifier=Modifier) {
    val context=LocalContext.current
    val bucket=position/1500
    val image by produceState<android.graphics.Bitmap?>(null,source,bucket) {
        if(Build.VERSION.SDK_INT<27 || source.startsWith("http") || source.startsWith("rtsp"))return@produceState
        delay(180)
        value=withContext(Dispatchers.IO) {
            val retriever=MediaMetadataRetriever()
            try {
                if(source.startsWith("content:")||source.startsWith("file:"))retriever.setDataSource(context,Uri.parse(source))else retriever.setDataSource(source)
                retriever.getScaledFrameAtTime(position.coerceAtLeast(0)*1000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,320,180)
            }catch(_:Exception){null}finally{retriever.release()}
        }
    }
    Column(modifier.background(Color.Black.copy(alpha=.8f)).padding(6.dp)) {
        image?.let {Image(it.asImageBitmap(),null,Modifier.width(200.dp).height(112.dp))}
        Text(formatDuration(position),color=Color.White)
    }
}
