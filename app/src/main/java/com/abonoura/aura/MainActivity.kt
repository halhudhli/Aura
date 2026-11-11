package com.abonoura.aura

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

class MainActivity : ComponentActivity() {
    private var playing by mutableStateOf<PlayRequest?>(null)
    private var incoming by mutableStateOf<List<IncomingMedia>>(emptyList())
    private var inPip by mutableStateOf(false)
    private var videoIsPlaying=false
    override fun onCreate(savedInstanceState:Bundle?) {
        enableEdgeToEdge();super.onCreate(savedInstanceState)
        Prefs.init(this);SubtitleAppearanceStore.init(this)
        incoming=IncomingMediaReader.read(intent,contentResolver)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AuraTheme(motionEnabled=playing==null) {
                    MediaCenterScreen(incoming,onConsumed={incoming=emptyList()},onPlay={request ->
                        AudioPlayback.controller?.pause();playing=request
                    })
                    playing?.let { request -> key(request.source) {
                        BackHandler { playing=null }
                        val index=request.queue.indexOfFirst{it.source==request.source}
                        fun navigate(i:Int) {request.queue.getOrNull(i)?.let{playing=PlayRequest(it.source,it.title,headers=it.headers,queue=request.queue)}}
                        PlayerScreen(request,inPip,{enterPip()},{videoIsPlaying=it},{playing=null},
                            onNext=if(index>=0 && index<request.queue.lastIndex)({navigate(index+1)})else null,
                            onPrevious=if(index>0)({navigate(index-1)})else null)
                    } }
                }
            }
        }
    }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent);setIntent(intent)
        playing=null;incoming=IncomingMediaReader.read(intent,contentResolver)
    }
    private fun enterPip() { runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16,9)).build()) } }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if(playing!=null && videoIsPlaying && Prefs.autoPip)enterPip()
    }
    override fun onPictureInPictureModeChanged(value:Boolean,newConfig:Configuration) {
        super.onPictureInPictureModeChanged(value,newConfig);inPip=value
    }
}
