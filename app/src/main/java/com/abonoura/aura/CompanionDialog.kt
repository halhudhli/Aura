package com.abonoura.aura

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.File

@Composable fun CompanionDialog(title:String,subtitlePath:String?,onDismiss:()->Unit,onSubtitle:(String)->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var question by remember{mutableStateOf("")};var answer by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
    var job by remember{mutableStateOf<Job?>(null)}
    DisposableEffect(Unit){onDispose{job?.cancel()}}
    AlertDialog(onDismissRequest={job?.cancel();onDismiss()},title={Text("مرافق أورا · Gemini")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("عند الإرسال يُرسل السؤال وعنوان الملف إلى Google. ترجمة الملف تُرسل نص الترجمة فقط، وتستخدم حسابك وحصته.")
        OutlinedTextField(question,{question=it.take(4000)},label={Text("اسأل عن العمل أو اكتب طلبك")})
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(answer.isNotBlank())Text(answer)
        if(subtitlePath!=null)TextButton(enabled=!busy,onClick={job=scope.launch {
            busy=true
            try {
                val cues=withContext(Dispatchers.IO){File(android.net.Uri.parse(subtitlePath).path ?: subtitlePath).inputStream().use{SubtitleCues.parse(SubtitleText.decode(readLimited(it,8*1024*1024)))}}
                val translated=Gemini.translate(context,cues,"Arabic"){done,total->answer="تُرجمت $done من $total جملة"}
                val output=withContext(Dispatchers.IO){File(File(context.filesDir,"subtitles").apply{mkdirs()},"translated-${System.currentTimeMillis()}.srt").apply{writeText(SubtitleAligner.srt(translated))}}
                onSubtitle(output.path);answer="اكتملت الترجمة وحُفظ الملف الأصلي"
            }catch(e:CancellationException){throw e}catch(e:Exception){answer=e.message ?: "تعذّرت الترجمة"}finally{busy=false}
        }}){Text("ترجمة الملف الحالي إلى العربية")}
    }},confirmButton={TextButton(enabled=!busy&&question.isNotBlank(),onClick={job=scope.launch{busy=true;try{answer=Gemini.answer(context,"عنوان الوسائط: $title\nسؤال المستخدم: $question\nأجب بالعربية، ولا تختلق معلومات عن محتوى لم تشاهده.")}catch(e:CancellationException){throw e}catch(e:Exception){answer=e.message.orEmpty()}finally{busy=false}}}){Text("إرسال")}},dismissButton={TextButton(onClick={job?.cancel();onDismiss()}){Text(if(busy)"إلغاء"else"إغلاق")}})
}
