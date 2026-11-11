@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.abonoura.aura

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.widget.ImageView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.sin

object WallpaperFiles {
    data class Imported(val file: String, val kind: String)
    suspend fun import(context: Context, uri: Uri): Imported = withContext(Dispatchers.IO) {
        val mime=context.contentResolver.getType(uri).orEmpty()
        val video=mime.startsWith("video/")
        val target=File(context.filesDir,"wallpaper-${System.currentTimeMillis()}.${if(video) "mp4" else "img"}")
        try {
            val limit=if(video) 120L*1024*1024 else 32L*1024*1024
            val input=context.contentResolver.openInputStream(uri) ?: error("تعذّر فتح الملف")
            input.use { source -> target.outputStream().use { output ->
                val buffer=ByteArray(32768);var total=0L
                while(true) { val n=source.read(buffer);if(n<0)break;total+=n;require(total<=limit) { "حجم الخلفية أكبر من المسموح" };output.write(buffer,0,n) }
            } }
            require(target.length()>0) { "الملف فارغ" }
            val kind=if(video) {
                val retriever=MediaMetadataRetriever()
                try { retriever.setDataSource(target.path);require(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.let { it>0 }==true) { "ملف الفيديو غير صالح" } }
                finally { retriever.release() }
                "video"
            } else {
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeFile(target.path,bounds)
                require(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384) { "صيغة الصورة غير مدعومة" }
                "image"
            }
            Imported(target.name,kind)
        } catch(failure:Exception) { target.delete();throw failure }
    }
}

@Composable
fun WallpaperBackground(motionEnabled: Boolean = true) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var visible by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val observer=LifecycleEventObserver { _,_ -> visible=owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val animate=visible && motionEnabled && !Prefs.reduceMotion
    val file=Prefs.backgroundFile?.let { File(context.filesDir,it) }?.takeIf { it.isFile }
    if(file==null) {
        MountainWallpaper(animate)
        if(animate) AuroraMotion()
    } else if(Prefs.backgroundKind=="video") {
        VideoWallpaper(file,animate)
    } else {
        ImageWallpaper(file,animate)
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=Prefs.backgroundDim/100f)))
}

@Composable private fun MountainWallpaper(animate:Boolean) {
    val phase=if(animate) {
        val transition=rememberInfiniteTransition(label="mountain motion")
        transition.animateFloat(0f,6.2831855f,infiniteRepeatable(tween(40000,easing=LinearEasing)),label="pan")
    }else remember{mutableFloatStateOf(0f)}
    val blur=if(Build.VERSION.SDK_INT>=31)Modifier.blur(Prefs.backgroundBlur.dp)else Modifier
    Image(painterResource(R.drawable.aura_mountains),null,Modifier.fillMaxSize().graphicsLayer {
        scaleX=1.04f;scaleY=1.04f
        translationX=sin(phase.value)*12f;translationY=kotlin.math.cos(phase.value)*5f
    }.then(blur),contentScale=if(Prefs.backgroundFit)ContentScale.Fit else ContentScale.Crop)
}

@Composable
private fun AuroraMotion() {
    val transition=rememberInfiniteTransition(label="aurora")
    val phase by transition.animateFloat(0f,6.2831855f,infiniteRepeatable(tween(32000,easing=LinearEasing),RepeatMode.Restart),label="aurora drift")
    Canvas(Modifier.fillMaxSize()) {
        val drift=sin(phase)*size.width*.08f
        for(i in 0..2) {
            val y=size.height*(.14f+i*.085f)+sin(phase+i)*size.height*.025f
            val path=Path().apply {
                moveTo(-size.width*.15f,y)
                cubicTo(size.width*.2f+drift,y-size.height*.14f,size.width*.6f-drift,y+size.height*.13f,size.width*1.15f,y-size.height*.17f)
                lineTo(size.width*1.15f,y-size.height*.02f)
                cubicTo(size.width*.6f,y+size.height*.2f,size.width*.2f,y-size.height*.05f,-size.width*.15f,y+size.height*.08f)
                close()
            }
            drawPath(path,Brush.verticalGradient(listOf(Color.Transparent,Color(if(i%2==0) 0xFF42E8D5 else 0xFF8B5CF6).copy(alpha=.055f),Color.Transparent),startY=y-size.height*.2f,endY=y+size.height*.2f))
        }
        drawCircle(Brush.radialGradient(listOf(Color(0xFF42E8D5).copy(alpha=.07f),Color.Transparent),Offset(size.width*(.6f+sin(phase)*.12f),size.height*.22f),size.width*.35f),size.width*.35f,Offset(size.width*(.6f+sin(phase)*.12f),size.height*.22f))
    }
}

@Composable
private fun ImageWallpaper(file: File, animate: Boolean) {
    var drawable by remember(file.path) { mutableStateOf<android.graphics.drawable.Drawable?>(null) }
    LaunchedEffect(file.path) {
        drawable=withContext(Dispatchers.IO) {
            runCatching {
                if(Build.VERSION.SDK_INT>=28) ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder,info,_ ->
                    decoder.setTargetSampleSize(ceil(maxOf(info.size.width,info.size.height)/2048.0).toInt().coerceAtLeast(1))
                } else android.graphics.drawable.Drawable.createFromPath(file.path)
            }.getOrNull()
        }
    }
    val current=drawable
    DisposableEffect(current,animate) {
        (current as? Animatable)?.let { if(animate)it.start() else it.stop() }
        onDispose { (current as? Animatable)?.stop() }
    }
    AndroidView(factory={ ImageView(it) },update={ view ->
        if(view.drawable!==current)view.setImageDrawable(current)
        view.scaleType=if(Prefs.backgroundFit)ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        if(Build.VERSION.SDK_INT>=31)view.setRenderEffect(if(Prefs.backgroundBlur>0)RenderEffect.createBlurEffect(Prefs.backgroundBlur.toFloat(),Prefs.backgroundBlur.toFloat(),Shader.TileMode.CLAMP)else null)
    },modifier=Modifier.fillMaxSize())
}

@Composable
private fun VideoWallpaper(file: File, animate: Boolean) {
    val context=LocalContext.current
    val player=remember(file.path) { ExoPlayer.Builder(context).build().apply { volume=0f;trackSelectionParameters=trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO,true).build();repeatMode=Player.REPEAT_MODE_ONE;setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));prepare() } }
    LaunchedEffect(player,animate) { player.playWhenReady=animate }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory={ LayoutInflater.from(it).inflate(R.layout.wallpaper_video,null) as PlayerView }, update={ view ->
        view.player=player
        view.resizeMode=if(Prefs.backgroundFit) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        if(Build.VERSION.SDK_INT>=31)view.setRenderEffect(if(Prefs.backgroundBlur>0)RenderEffect.createBlurEffect(Prefs.backgroundBlur.toFloat(),Prefs.backgroundBlur.toFloat(),Shader.TileMode.CLAMP)else null)
    },onRelease={ it.player=null },modifier=Modifier.fillMaxSize())
}
