package com.abonoura.aura
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable fun CastDialog(request:PlayRequest,onDismiss:()->Unit,onStarted:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var devices by remember{mutableStateOf<List<CastDevice>>(emptyList())};var busy by remember{mutableStateOf(false)}
    var selected by remember{mutableStateOf(DlnaCast.active)};var status by remember{mutableStateOf("ابحث عن شاشة تدعم DLNA على الشبكة نفسها. أبقِ أورا مفتوحًا أثناء بث ملف من الجهاز. توافق الصورة والترجمة يعتمد على الشاشة.")}
    AlertDialog(onDismissRequest=onDismiss,title={Text("البث إلى شاشة · DLNA")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(status);if(busy)LinearProgressIndicator()
        TextButton(enabled=!busy,onClick={scope.launch {busy=true;runCatching{DlnaCast.discover(context)}.onSuccess{devices=it;status=if(it.isEmpty())"لم نعثر على شاشة تدعم DLNA"else"اختر الشاشة"}.onFailure{status=it.message.orEmpty()};busy=false}}){Text("البحث عن الشاشات")}
        devices.forEach{device->TextButton(enabled=!busy,onClick={scope.launch{busy=true;runCatching{DlnaCast.play(context,device,request)}.onSuccess{selected=device;status="متصل: ${device.name}";onStarted()}.onFailure{status=it.message.orEmpty()};busy=false}}){Text(device.name)}}
        selected?.let{device->Row{listOf("Play" to "تشغيل","Pause" to "إيقاف مؤقت","Stop" to "إنهاء").forEach{(action,label)->TextButton(onClick={scope.launch{runCatching{DlnaCast.control(device,action)}.onSuccess{if(action=="Stop")selected=null}.onFailure{status=it.message.orEmpty()}}}){Text(label)}}}}
    }},confirmButton={TextButton(onClick=onDismiss){Text("إغلاق")}})
}
