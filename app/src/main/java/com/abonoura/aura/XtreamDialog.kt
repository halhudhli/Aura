package com.abonoura.aura
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable fun XtreamDialog(onDismiss:()->Unit,onImport:(String)->Unit) {
    val context=LocalContext.current
    var name by remember{mutableStateOf("")};var server by remember{mutableStateOf("")};var user by remember{mutableStateOf("")};var password by remember{mutableStateOf("")}
    var kind by remember{mutableStateOf("live")};var error by remember{mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("اشتراك Xtream")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("استخدم عنوان وبيانات اشتراكك. يُحفظ تسجيل الدخول مشفّرًا على الجهاز.")
        Xtream.portals(context).forEach{portal->TextButton(onClick={onImport(Xtream.source(portal,kind));onDismiss()}){Text("${portal.name} · استيراد")}}
        OutlinedTextField(name,{name=it},label={Text("اسم الاشتراك")},singleLine=true);OutlinedTextField(server,{server=it},label={Text("https://server:port")},singleLine=true)
        OutlinedTextField(user,{user=it},label={Text("المستخدم")},singleLine=true);OutlinedTextField(password,{password=it},label={Text("كلمة المرور")},singleLine=true,visualTransformation=PasswordVisualTransformation())
        Row{listOf("live" to "مباشر","vod" to "أفلام","series" to "مسلسلات").forEach{(value,label)->TextButton(onClick={kind=value}){Text(if(value==kind)"✓ $label"else label)}}}
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick={runCatching{val portal=XtreamPortal(name=name.ifBlank{"اشتراكي"},server=server.trim().trimEnd('/'),user=user,password=password);Xtream.save(context,portal);onImport(Xtream.source(portal,kind));onDismiss()}.onFailure{error=it.message}}){Text("حفظ واستيراد")}},dismissButton={TextButton(onClick=onDismiss){Text("إلغاء")}})
}
