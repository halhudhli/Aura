package com.abonoura.aura

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable fun NetworkBrowser(onBack:()->Unit,onPlay:(PlayRequest)->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val c=LocalAura.current
    var sources by remember{mutableStateOf(NetworkSources.all(context))};var selected by remember{mutableStateOf<NetworkSource?>(null)}
    var path by remember{mutableStateOf("/")};var files by remember{mutableStateOf<List<NetworkFile>>(emptyList())}
    var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)};var trust by remember{mutableStateOf<String?>(null)}
    var add by remember{mutableStateOf(false)}
    var reload by remember{mutableIntStateOf(0)}
    LaunchedEffect(selected,path,reload) {val source=selected ?: return@LaunchedEffect
        busy=true;error=null;trust=null
        runCatching{NetworkSources.list(context,source,path)}.onSuccess{files=it}.onFailure{files=emptyList();error=it.message;if(it is UnknownServerKey)trust=it.fingerprint};busy=false
    }
    fun back(){val source=selected;if(source==null)onBack()else if(path.trimEnd('/')==source.root.trimEnd('/'))selected=null else path=path.trimEnd('/').substringBeforeLast('/',"").ifBlank{"/"}}
    BackHandler{back()}
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row {TopIcon(Icons.AutoMirrored.Rounded.ArrowBack,"رجوع"){back()};Text("الشبكة وNAS",color=c.text,fontSize=24.sp)}
        Row {TextButton(onClick={add=true}){Text("إضافة خادم")};TextButton(onClick={reload++}){Text("تحديث")}}
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        trust?.let{fingerprint->Column(Modifier.auraCard().padding(12.dp)) {
            Text("تحقّق من البصمة لدى مسؤول خادمك قبل اعتمادها. إذا تغيرت بصمة محفوظة، تحقّق من سبب التغيير.",color=c.text)
            Text(fingerprint,color=c.accent,fontSize=12.sp)
            TextButton(onClick={selected?.let{NetworkSources.trust(context,it,fingerprint)};reload++}){Text("اعتماد هذه البصمة والاتصال")}
        }}
        val source=selected
        if(source==null)LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            items(sources,key={it.id}){server->Column(Modifier.fillMaxWidth().auraCard().clickable{selected=server;path=server.root}.padding(16.dp)) {
                Text(server.name,color=c.text,fontSize=20.sp);Text("${server.protocol} · ${server.host}",color=c.subText)
                TextButton(onClick={NetworkSources.remove(context,server.id);sources=NetworkSources.all(context)}){Text("حذف")}
            }}
            if(sources.isEmpty())item{Text("أضف خادم SMB أو SFTP أو FTP/FTPS، أو استخدم «إضافة مجلد» للوصول إلى مزوّد ملفات على جهازك.",color=c.subText)}
        }else {
            Text("${source.name} · $path",color=c.subText,fontSize=12.sp)
            LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(files,key={it.path}){file->Row(Modifier.fillMaxWidth().auraCard().clickable{
                    if(file.directory)path=file.path else {
                        val uri=NetworkSources.uri(source,file.path);val type=MediaTypes.classify(file.name)
                        MediaCatalog.remember(context,LibraryItem(uri,file.name,type,sizeBytes=file.size,folder=source.name))
                        onPlay(PlayRequest(uri,file.name,type,queue=files.filterNot{it.directory}.map{QueueItem(NetworkSources.uri(source,it.path),it.name)}))
                    }
                }.padding(14.dp)) {
                    Icon(if(file.directory)Icons.Rounded.Folder else Icons.Rounded.PlayArrow,null,tint=c.accent)
                    Spacer(Modifier.width(12.dp));Column{Text(file.name,color=c.text);if(!file.directory)Text(formatSize(file.size),color=c.subText,fontSize=12.sp)}
                }}
            }
        }
    }
    if(add) {
        var protocol by remember{mutableStateOf(NetworkProtocol.SMB)};var name by remember{mutableStateOf("")};var host by remember{mutableStateOf("")}
        var port by remember{mutableStateOf("445")};var root by remember{mutableStateOf("/")};var user by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var domain by remember{mutableStateOf("")}
        AlertDialog(onDismissRequest={add=false},title={Text("خادم وسائط")},text={Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row {NetworkProtocol.entries.forEach{p->TextButton(onClick={protocol=p;port=when(p){NetworkProtocol.SMB->"445";NetworkProtocol.SFTP->"22";else->"21"}}){Text(p.name)}}}
            OutlinedTextField(name,{name=it},label={Text("الاسم")},singleLine=true);OutlinedTextField(host,{host=it},label={Text("عنوان الخادم")},singleLine=true)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){OutlinedTextField(port,{port=it.filter{ch->ch.isDigit()}},Modifier.weight(1f),label={Text("المنفذ")},singleLine=true);OutlinedTextField(root,{root=it},Modifier.weight(2f),label={Text(if(protocol==NetworkProtocol.SMB)"/المشاركة/المجلد"else"المسار")},singleLine=true)}
            OutlinedTextField(user,{user=it},label={Text("المستخدم")},singleLine=true);OutlinedTextField(password,{password=it},label={Text("كلمة المرور")},singleLine=true,visualTransformation=PasswordVisualTransformation())
            if(protocol==NetworkProtocol.SMB)OutlinedTextField(domain,{domain=it},label={Text("النطاق (اختياري)")},singleLine=true)
            if(protocol==NetworkProtocol.FTP)Text("FTP ينقل بيانات الدخول دون تشفير. FTPS وSFTP يدعمان التشفير.",fontSize=11.sp)
        }},confirmButton={TextButton(onClick={runCatching{NetworkSources.save(context,NetworkSource(name=name.ifBlank{host},protocol=protocol,host=host.trim(),port=port.toIntOrNull()?:0,root=root.trim().let{if(it.startsWith('/'))it else"/$it"},user=user,password=password,domain=domain))}.onSuccess{sources=NetworkSources.all(context);add=false}.onFailure{error=it.message}}){Text("حفظ")}},dismissButton={TextButton(onClick={add=false}){Text("إلغاء")}})
    }
}
