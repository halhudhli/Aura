package com.abonoura.aura

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.File

@Composable fun AutomaticSyncDialog(path:String,request:PlayRequest,onDismiss:()->Unit,onApply:(String)->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var result by remember{mutableStateOf<SubtitleAlignment?>(null)}
    var cues by remember{mutableStateOf<List<SubtitleCue>>(emptyList())}
    var tracks by remember{mutableStateOf<List<EmbeddedTextTrack>>(emptyList())}
    var status by remember{mutableStateOf("اختر ترجمة مدمجة في الفيديو أو ملفًا متزامنًا ليكون مرجعًا. تُقارَن حدود الجمل محليًا؛ لا يُرسل الصوت أو الفيديو لأي خدمة.")}
    var job by remember{mutableStateOf<Job?>(null)}
    var busy by remember{mutableStateOf(false)}
    DisposableEffect(Unit){onDispose{job?.cancel()}}
    fun analyze(reference:suspend ()->List<SubtitleCue>) {
        job=scope.launch {
            busy=true;result=null;tracks=emptyList()
            try {
                val activeJob=currentCoroutineContext().job
                val source=withContext(Dispatchers.IO){File(Uri.parse(path).path ?: path).inputStream().use{SubtitleCues.parse(SubtitleText.decode(readLimited(it,8*1024*1024)))}}
                val target=reference();cues=source
                val fit=withContext(Dispatchers.Default){SubtitleAligner.align(source,target){!activeJob.isActive}}
                result=fit
                status=if(fit==null)"الترجمة لا تحتوي جملًا كافية للمقارنة"else"الثقة: ${(fit.confidence*100).toInt()}% · إزاحة: ${fit.offsetMs} ms · معامل التوقيت: ${"%.5f".format(java.util.Locale.ROOT,fit.scale)}"+
                    if(fit.confidence<.65)"\nالنتيجة ضعيفة؛ اختر مرجعًا آخر."else"\nسيُنشأ ملف مصحّح، ويظل الملف الأصلي محفوظًا."
            }catch(e:CancellationException){throw e}catch(e:Exception){status=e.message ?: "تعذّرت المزامنة"}finally{busy=false}
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri:Uri?->
        if(uri!=null)analyze {withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)?.use{SubtitleCues.parse(SubtitleText.decode(readLimited(it,8*1024*1024)))} ?: error("تعذّر قراءة المرجع")}}
    }
    AlertDialog(onDismissRequest={job?.cancel();onDismiss()},title={Text("المزامنة التلقائية للتوقيت")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(status);if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(enabled=!busy,onClick={picker.launch(arrayOf("*/*"))}){Text("اختيار ملف مرجعي")}
        TextButton(enabled=!busy,onClick={job=scope.launch {
            busy=true
            runCatching{EmbeddedSubtitles.tracks(context,request)}.onSuccess{tracks=it;status=if(it.isEmpty())"لم يوفّر أندرويد مسار ترجمة نصية قابلاً للاستخراج من هذه الحاوية؛ استخدم ملفًا مرجعيًا."else"اختر مسارًا مضبوطًا مع الفيديو"}.onFailure{status="تعذّر استخراج المسارات من هذه الحاوية؛ استخدم ملفًا مرجعيًا."}
            busy=false
        }}){Text("استخراج مرجع من الفيديو")}
        tracks.forEach{track->TextButton(enabled=!busy,onClick={analyze{EmbeddedSubtitles.timeline(context,request,track)}}){Text("المسار ${track.index+1} · ${track.language.ifBlank{"لغة غير محددة"}}")}}
    }},confirmButton={TextButton(enabled=!busy && (result?.confidence ?: 0.0)>=.65,onClick={job=scope.launch {
        val fit=result ?: return@launch;busy=true
        try {
            val output=withContext(Dispatchers.IO){File(context.filesDir,"subtitles").apply{mkdirs()}.let{File(it,"sync-${System.currentTimeMillis()}.srt")}.apply{writeText(SubtitleAligner.srt(fit.apply(cues)))}}
            onApply(output.path)
        }catch(e:Exception){status="تعذّر حفظ الملف المصحّح"}finally{busy=false}
    }}){Text("تطبيق")}},dismissButton={TextButton(onClick={job?.cancel();onDismiss()}){Text(if(busy)"إلغاء التحليل"else"إغلاق")}})
}
