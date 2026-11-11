package com.abonoura.aura

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player

@Composable fun AudioMiniPlayer(onOpen:()->Unit) {
    val c=LocalAura.current
    Row(Modifier.fillMaxWidth().padding(vertical=8.dp).auraCard().clickable(onClick=onOpen).padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
        Icon(Icons.Rounded.GraphicEq,null,tint=c.accent)
        Column(Modifier.weight(1f).padding(horizontal=12.dp)) {
            Text(AudioPlayback.title,color=c.text,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("${formatDuration(AudioPlayback.position)} / ${formatDuration(AudioPlayback.duration)}",color=c.subText,fontSize=11.sp)
        }
        IconButton(onClick={AudioPlayback.toggle()}){Icon(if(AudioPlayback.playing)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,"تشغيل / إيقاف",tint=c.accent)}
        IconButton(onClick={AudioPlayback.controller?.seekToNextMediaItem()}){Icon(Icons.Rounded.SkipNext,"التالي",tint=c.text)}
    }
}

@Composable fun AudioScreen(onBack:()->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val c=LocalAura.current;val player=AudioPlayback.controller
    var repeat by remember {mutableIntStateOf(player?.repeatMode ?: 0)}
    var shuffle by remember {mutableStateOf(player?.shuffleModeEnabled ?: false)}
    var speed by remember {mutableFloatStateOf(player?.playbackParameters?.speed ?: 1f)}
    var showEq by remember {mutableStateOf(false)}
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {TopIcon(Icons.AutoMirrored.Rounded.ArrowBack,"رجوع",onBack);Text("المشغّل الصوتي",fontSize=22.sp,color=c.text)}
        Icon(Icons.Rounded.Headphones,null,Modifier.size(120.dp).align(Alignment.CenterHorizontally),tint=c.accent)
        Text(AudioPlayback.title,color=c.text,fontSize=25.sp)
        AudioPlayback.error?.let {Text("تعذّر التشغيل: $it",color=MaterialTheme.colorScheme.error)}
        var seeking by remember {mutableStateOf<Float?>(null)}
        Slider(value=seeking ?: AudioPlayback.position.toFloat().coerceAtMost(AudioPlayback.duration.toFloat()),onValueChange={seeking=it},valueRange=0f..AudioPlayback.duration.coerceAtLeast(1).toFloat(),
            onValueChangeFinished={seeking?.let {player?.seekTo(it.toLong())};seeking=null})
        Text("${formatDuration(AudioPlayback.position)} / ${formatDuration(AudioPlayback.duration)}",color=c.subText)
        Row(Modifier.align(Alignment.CenterHorizontally),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={player?.seekToPreviousMediaItem()}){Icon(Icons.Rounded.SkipPrevious,"السابق",tint=c.text)}
            FilledIconButton(onClick={AudioPlayback.toggle()},modifier=Modifier.size(64.dp)){Icon(if(AudioPlayback.playing)Icons.Rounded.Pause else Icons.Rounded.PlayArrow,"تشغيل / إيقاف")}
            IconButton(onClick={player?.seekToNextMediaItem()}){Icon(Icons.Rounded.SkipNext,"التالي",tint=c.text)}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Chip("عشوائي",shuffle){shuffle=!shuffle;player?.shuffleModeEnabled=shuffle}
            Chip(when(repeat){Player.REPEAT_MODE_ONE->"تكرار واحد";Player.REPEAT_MODE_ALL->"تكرار الكل";else->"بلا تكرار"},repeat!=0){repeat=(repeat+1)%3;player?.repeatMode=repeat}
            Chip("$speed×",speed!=1f){speed=when(speed){1f->1.25f;1.25f->1.5f;1.5f->2f;else->1f};player?.setPlaybackSpeed(speed)}
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Chip(if(AudioPlayback.sleepUntil>0)"إلغاء مؤقت النوم"else"نوم بعد 30 دقيقة",AudioPlayback.sleepUntil>0){AudioPlayback.setSleep(if(AudioPlayback.sleepUntil>0)0 else 30)}
            Chip("معادل الصوت",showEq){showEq=!showEq}
        }
        if(showEq) {
            val eq=AudioPlayback.equalizer
            if(eq==null)Text("ابدأ تشغيل ملف صوتي لإظهار نطاقات المعادل المدعومة من الجهاز",color=c.subText)
            else Column(Modifier.fillMaxWidth().auraCard().padding(14.dp)) {
                val range=runCatching{eq.bandLevelRange}.getOrDefault(shortArrayOf(-1500,1500))
                val bands=runCatching{eq.numberOfBands.toInt()}.getOrDefault(0)
                repeat(bands) { band ->
                    val b=band.toShort();var level by remember(eq,b) {mutableFloatStateOf(runCatching{eq.getBandLevel(b).toFloat()}.getOrDefault(0f))}
                    val frequency=runCatching{eq.getCenterFreq(b)/1000}.getOrDefault(0)
                    Text("$frequency Hz · ${(level/100).toInt()} dB",color=c.text,fontSize=12.sp)
                    Slider(level,{level=it;AudioPlayback.setEqualizer(context,b,it.toInt().toShort())},valueRange=range[0].toFloat()..range[1].toFloat())
                }
            }
        }
        Text("قائمة التشغيل · ${AudioPlayback.queue.size}",color=c.accent,fontSize=18.sp)
        AudioPlayback.queue.forEachIndexed { index,item ->
            Row(Modifier.fillMaxWidth().auraCard().clickable {player?.seekToDefaultPosition(index);player?.play()}.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(item.second,color=c.text,modifier=Modifier.weight(1f),maxLines=2)
                IconButton(onClick={player?.removeMediaItem(index)}){Icon(Icons.Rounded.Close,"إزالة من القائمة",tint=c.subText)}
            }
        }
    }
}
