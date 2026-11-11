package com.abonoura.aura

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MediaCenterScreen(incoming:List<IncomingMedia>,onConsumed:()->Unit,onPlay:(PlayRequest)->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val c=LocalAura.current
    var access by remember { mutableStateOf(libraryAccess(context)) };var revision by remember { mutableIntStateOf(0) }
    var media by remember { mutableStateOf<List<LibraryItem>>(emptyList()) };var playlists by remember { mutableStateOf<List<SavedPlaylist>>(emptyList()) }
    var loading by remember { mutableStateOf(false) };var tab by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(false) };var audioExpanded by remember { mutableStateOf(false) }
    var network by remember{mutableStateOf(false)}
    var xtream by remember{mutableStateOf(false)}
    var search by remember { mutableStateOf("") };var linkDialog by remember { mutableStateOf(false) };var playlistLink by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) };var grid by remember { mutableStateOf(false) };var sort by remember { mutableIntStateOf(0) }
    var folder by remember { mutableStateOf<String?>(null) };var selectedPlaylist by remember { mutableStateOf<String?>(null) };var group by remember { mutableStateOf<String?>(null) }
    var favoriteOnly by remember { mutableStateOf(false) }
    var audioGroup by remember{mutableIntStateOf(0)}
    var epg by remember {mutableStateOf<List<TvProgramme>>(emptyList())}
    var epgDialog by remember {mutableStateOf(false)}
    var epgOffset by remember {mutableIntStateOf(0)}
    val epgByChannel=remember(epg){epg.groupBy{it.channel}}
    var clock by remember {mutableLongStateOf(System.currentTimeMillis())}
    LaunchedEffect(tab){if(tab==3)while(true){clock=System.currentTimeMillis();kotlinx.coroutines.delay(30000)}}
    val favoritesVersion=Prefs.favoritesVersion;val historyVersion=Prefs.historyVersion
    val lifecycle=LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME) {access=libraryAccess(context);revision++} }
        lifecycle.lifecycle.addObserver(observer);onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { AudioPlayback.connect(context) }
    LaunchedEffect(access,revision) { loading=true;media=MediaCatalog.load(context,access);playlists=PlaylistRepository.load(context);loading=false }
    LaunchedEffect(selectedPlaylist,playlists) {
        epg=emptyList();val list=playlists.firstOrNull{it.id==selectedPlaylist}
        if(list!=null && list.epgUrl.isNotBlank())runCatching{EpgRepository.load(context,list.id,list.epgUrl)}.onSuccess{epg=it}.onFailure{message="دليل البرامج: ${it.message}"}
    }
    fun importPlaylist(source:String,title:String?=null) { scope.launch {
        loading=true
        runCatching { PlaylistRepository.import(context,source,title) }.onSuccess {
            playlists=PlaylistRepository.load(context);selectedPlaylist=it.id;tab=3
        }.onFailure {
            if(it.message?.contains("HLS")==true)onPlay(PlayRequest(source,title ?: "بث مباشر")) else message=it.message ?: "تعذّر فتح القائمة"
        };loading=false
    } }
    fun play(request:PlayRequest) {
        when(request.mediaType) {
            MediaType.Audio -> AudioPlayback.play(context,listOf(request))
            MediaType.Playlist -> importPlaylist(request.source,request.title)
            MediaType.Video -> {
                val list=playlists.firstOrNull{p->p.entries.any{it.source==request.source}}
                val queue=list?.entries?.map{QueueItem(it.source,it.title,it.headers)} ?: media.filter{it.type==MediaType.Video}.map{QueueItem(it.source,it.title)}
                onPlay(request.copy(queue=queue))
            }
        }
    }
    LaunchedEffect(incoming) {
        if(incoming.isEmpty())return@LaunchedEffect
        val received=incoming.toList();onConsumed()
        received.forEach { MediaCatalog.remember(context,LibraryItem(it.uri.toString(),it.title,it.type)) }
        if(received.all {it.type==MediaType.Audio})AudioPlayback.play(context,received.map { PlayRequest(it.uri.toString(),it.title,it.type,it.startMs,it.headers) })
        else received.first().let { play(PlayRequest(it.uri.toString(),it.title,it.type,it.startMs,it.headers)) }
        revision++
    }
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {access=libraryAccess(context);revision++}
    fun retain(uri:Uri) { runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    val filePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> scope.launch {
        val items=withContext(Dispatchers.IO) { uris.map { uri -> retain(uri);MediaCatalog.inspect(context,uri).also {MediaCatalog.remember(context,it)} } }
        revision++;if(items.size==1)play(items.first().playRequest())else if(items.isNotEmpty())message="أُضيف ${items.size} ملفًا إلى المكتبة"
    } }
    val folderPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri!=null) {retain(uri);scope.launch {loading=true;val count=withContext(Dispatchers.IO) { importMediaTree(context,uri) };message="أُضيف $count ملفًا من المجلد";revision++;loading=false} }
    }
    if(settings) {BackHandler {settings=false};SettingsScreen(BuildConfig.VERSION_NAME){settings=false};return}
    if(audioExpanded) {BackHandler {audioExpanded=false};AudioScreen {audioExpanded=false};return}
    if(network){NetworkBrowser({network=false}){request->if(request.mediaType==MediaType.Playlist){network=false;importPlaylist(request.source,request.title)}else play(request)};return}
    if(folder!=null || selectedPlaylist!=null)BackHandler {folder=null;selectedPlaylist=null;group=null}
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        val wide=maxWidth>=840.dp
        Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            if(wide)Column(Modifier.width(152.dp).fillMaxHeight().padding(10.dp).auraCard().padding(12.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Image(painterResource(R.drawable.aura_logo),"أورا",Modifier.size(80.dp).align(Alignment.CenterHorizontally))
                CenterTabs(tab,{tab=it;folder=null;selectedPlaylist=null;group=null},true)
                Spacer(Modifier.weight(1f));TextButton(onClick={settings=true}) {Text("الإعدادات")}
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal=if(wide)8.dp else 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if(tab==0)"أهلًا بك في أورا" else listOf("الرئيسية","ملفاتك","مكتبة الصوت","البث والقوائم")[tab],color=c.text,fontSize=25.sp,fontWeight=FontWeight.Bold)
                        Text("شاهد، استمع، وتابع من حيث توقفت",color=c.subText,fontSize=12.sp)
                    }
                    IconButton(onClick={revision++}) {Icon(Icons.Rounded.Refresh,"تحديث",tint=c.accent)}
                    IconButton(onClick={settings=true}) {Icon(Icons.Rounded.Settings,"الإعدادات",tint=c.text)}
                }
                OutlinedTextField(search,{search=it},Modifier.fillMaxWidth(),placeholder={Text("ابحث في مكتبتك والقنوات")},leadingIcon={Icon(Icons.Rounded.Search,null)},singleLine=true)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Chip("فتح ملفات",false){filePicker.launch(arrayOf("video/*","audio/*","application/x-mpegurl","application/vnd.apple.mpegurl"))}
                    Chip("رابط",false){playlistLink=false;linkDialog=true};Chip("إضافة مجلد",false){folderPicker.launch(null)}
                    Chip("قائمة M3U / PLS",false){playlistLink=true;linkDialog=true}
                    Chip("الشبكة وNAS",false){network=true}
                    Chip("Xtream",false){xtream=true}
                }
                if(!access.videos || !access.audio)TextButton(onClick={permissions.launch(requestedLibraryPermissions())}) {Text(if(access.selectedOnly)"الوصول إلى فيديوهات محددة · إدارة الصلاحيات" else "السماح بعرض ملفات الجهاز تلقائيًا")}
                message?.let { text -> Row(Modifier.fillMaxWidth().auraCard().padding(8.dp),verticalAlignment=Alignment.CenterVertically) {Text(text,color=c.text,modifier=Modifier.weight(1f));TextButton(onClick={message=null}){Text("إغلاق")}} }
                if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
                when(tab) {
                    0 -> LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(bottom=16.dp)) {
                        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            listOf(MediaType.Video,MediaType.Audio,MediaType.Playlist).forEach { type -> Column(Modifier.weight(1f).auraCard().clickable { tab=if(type==MediaType.Audio)2 else if(type==MediaType.Playlist)3 else 1 }.padding(16.dp)) {
                                Text(if(type==MediaType.Playlist)playlists.size.toString() else media.count{it.type==type}.toString(),fontSize=27.sp,color=c.accent,fontWeight=FontWeight.Bold);Text(type.label,color=c.text,fontSize=13.sp)
                            } }
                        } }
                        val history=Prefs.history().filter { it.positionMs>0 && it.positionMs<it.durationMs-3000 }
                        if(history.isNotEmpty()) {
                            item {Text("أكمل المشاهدة",fontSize=19.sp,color=c.text,fontWeight=FontWeight.SemiBold)}
                            items(history.take(4),key={"history:"+it.source}) { entry -> Column(Modifier.fillMaxWidth().auraCard().clickable {play(PlayRequest(entry.source,entry.title,startMs=entry.positionMs))}.padding(16.dp)) {
                                Text(entry.title,color=c.text,maxLines=1,overflow=TextOverflow.Ellipsis);Text("${formatDuration(entry.positionMs)} / ${formatDuration(entry.durationMs)}",color=c.subText,fontSize=12.sp)
                                LinearProgressIndicator(progress={entry.positionMs.toFloat()/entry.durationMs.coerceAtLeast(1)},modifier=Modifier.fillMaxWidth().padding(top=8.dp))
                            } }
                        }
                        item {Text("أُضيف مؤخرًا",color=c.text,fontSize=19.sp,fontWeight=FontWeight.SemiBold)}
                        items(media.filter{it.title.contains(search,true)}.sortedByDescending{it.dateAdded}.take(12),key={it.source}) { MediaItemRow(it,{play(it.playRequest())}) }
                        if(media.isEmpty())item { EmptyLibrary("مكتبتك جاهزة لاستقبال ملفاتك", "افتح ملفات أو مجلدًا، أو اسمح بالوصول إلى وسائط الجهاز") }
                    }
                    1,2 -> {
                        val type=if(tab==2)MediaType.Audio else MediaType.Video
                        fun groupName(item:LibraryItem)=when{tab!=2||audioGroup==0->item.folder;audioGroup==1->item.artist.ifBlank{"فنان غير معروف"};else->item.album.ifBlank{"ألبوم غير معروف"}}
                        val candidates=media.filter {it.type==type && (it.title.contains(search,true)||it.artist.contains(search,true)||it.album.contains(search,true)) && (folder==null||groupName(it)==folder) && (!favoriteOnly||Prefs.isFavorite(it.source))}
                        val ordered=when(sort){1->candidates.sortedBy{it.title.lowercase()};2->candidates.sortedByDescending{it.durationMs};else->candidates.sortedByDescending{it.dateAdded}}
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Chip(if(grid)"شبكة"else"قائمة",grid){grid=!grid};Chip(listOf("الأحدث","الاسم","المدة")[sort],false){sort=(sort+1)%3};Chip("المفضلة",favoriteOnly){favoriteOnly=!favoriteOnly}
                            if(tab==2)Chip("تشغيل الكل",false){AudioPlayback.play(context,ordered.map{it.playRequest()})}
                            if(tab==2)Chip(listOf("المجلدات","الفنانون","الألبومات")[audioGroup],audioGroup>0){audioGroup=(audioGroup+1)%3;folder=null}
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            Chip("الكل",folder==null){folder=null};media.filter{it.type==type}.map{groupName(it)}.distinct().sorted().forEach { name -> Chip(name,folder==name){folder=name} }
                        }
                        if(ordered.isEmpty())Box(Modifier.weight(1f)){EmptyLibrary("لا توجد ملفات هنا","جرّب تغيير البحث أو إضافة ملفات")}
                        else if(grid)LazyVerticalGrid(GridCells.Adaptive(220.dp),Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {items(ordered,key={it.source}) { MediaItemRow(it,{play(it.playRequest())}) }}
                        else LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {items(ordered,key={it.source}){MediaItemRow(it,{play(it.playRequest())})}}
                    }
                    3 -> {
                        val list=playlists.firstOrNull{it.id==selectedPlaylist}
                        if(list==null)LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            item {Text("استورد ملف قائمة أو رابط اشتراكك. القنوات تأتي من المصدر الذي تختاره.",color=c.subText,fontSize=13.sp)}
                            items(playlists,key={it.id}) { p -> Column(Modifier.fillMaxWidth().auraCard().clickable {selectedPlaylist=p.id;group=null}.padding(16.dp)) {
                                Text(p.name,color=c.text,fontSize=19.sp);Text("${p.entries.size} عنصر · ${p.entries.map{it.group}.distinct().size} مجموعة",color=c.subText,fontSize=12.sp)
                                Row {TextButton(onClick={importPlaylist(p.source,p.name)}){Text("تحديث")};TextButton(onClick={scope.launch{PlaylistRepository.delete(context,p.id);playlists=PlaylistRepository.load(context)}}){Text("حذف")}}
                            } }
                            if(playlists.isEmpty())item {EmptyLibrary("البث وقوائم التشغيل","يدعم ملفات M3U وPLS وروابط HLS")}
                        }else {
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                Chip("القوائم",false){selectedPlaylist=null;group=null};Chip("الكل",group==null){group=null};Chip("المفضلة",favoriteOnly){favoriteOnly=!favoriteOnly}
                                Chip("دليل البرامج",epg.isNotEmpty()){epgDialog=true}
                                list.entries.map{it.group}.filter{it.isNotBlank()}.distinct().forEach {g->Chip(g,group==g){group=g}}
                            }
                            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                items(list.entries.filter{it.title.contains(search,true)&&(group==null||it.group==group)&&(!favoriteOnly||Prefs.isFavorite(it.source))},key={it.source+it.title}) {entry ->
                                    Column {MediaItemRow(LibraryItem(entry.source,entry.title,entry.request().mediaType,folder=entry.group),{play(entry.request())},entry.logo)
                                        val now=clock-epgOffset*60000L
                                        val current=epgByChannel[entry.epgId]?.firstOrNull{now>=it.start && now<it.end}
                                        current?.let {Text("الآن: ${it.title}",color=c.accent,fontSize=12.sp,modifier=Modifier.padding(horizontal=16.dp,vertical=4.dp))}
                                    }
                                }
                            }
                        }
                    }
                }
                if(AudioPlayback.title.isNotBlank())AudioMiniPlayer {audioExpanded=true}
                if(!wide)CenterTabs(tab,{tab=it;folder=null;selectedPlaylist=null;group=null},false)
            }
        }
    }
    if(linkDialog) {
        var url by remember {mutableStateOf("")};var name by remember{mutableStateOf("")}
        AlertDialog(onDismissRequest={linkDialog=false},title={Text(if(playlistLink)"إضافة قائمة"else"فتح رابط")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(url,{url=it},label={Text("الرابط")},singleLine=true);OutlinedTextField(name,{name=it},label={Text("الاسم (اختياري)")},singleLine=true)
        }},confirmButton={TextButton(enabled=MediaTypes.playableScheme(url.trim()),onClick={val source=url.trim();linkDialog=false
            if(playlistLink)importPlaylist(source,name.takeIf{it.isNotBlank()})else play(PlayRequest(source,name.ifBlank{"وسائط مباشرة"},MediaTypes.classify(source)))
        }){Text("فتح")}},dismissButton={TextButton(onClick={linkDialog=false}){Text("إلغاء")}})
    }
    if(xtream)XtreamDialog({xtream=false}){source->importPlaylist(source)}
    if(epgDialog) {
        val list=playlists.firstOrNull{it.id==selectedPlaylist}
        var url by remember {mutableStateOf(list?.epgUrl.orEmpty())}
        AlertDialog(onDismissRequest={epgDialog=false},title={Text("دليل البرامج XMLTV")},text={Column {
            OutlinedTextField(url,{url=it},label={Text("رابط XMLTV أو XMLTV.GZ")})
            Text("تصحيح الوقت: $epgOffset دقيقة")
            Row {TextButton(onClick={epgOffset-=30}){Text("−30")};TextButton(onClick={epgOffset=0}){Text("إعادة")};TextButton(onClick={epgOffset+=30}){Text("+30")}}
        }},confirmButton={TextButton(onClick={epgDialog=false;if(list!=null)scope.launch {
            val updated=list.copy(epgUrl=url.trim());PlaylistRepository.save(context,updated);playlists=PlaylistRepository.load(context)
            runCatching{EpgRepository.load(context,list.id,url.trim(),true)}.onSuccess{epg=it}.onFailure{message=it.message}
        }}){Text("حفظ وتحديث")}},dismissButton={TextButton(onClick={epgDialog=false}){Text("إلغاء")}})
    }
}

@Composable private fun CenterTabs(selected:Int,onSelect:(Int)->Unit,vertical:Boolean) {
    val labels=listOf("الرئيسية","الملفات","الصوت","البث");val icons=listOf(Icons.Rounded.Home,Icons.Rounded.Folder,Icons.Rounded.Headphones,Icons.Rounded.LiveTv)
    if(vertical)labels.forEachIndexed{i,label->TextButton(onClick={onSelect(i)}) {Icon(icons[i],null);Spacer(Modifier.width(8.dp));Text(label,color=if(i==selected)LocalAura.current.accent else LocalAura.current.text)}}
    else NavigationBar(containerColor=LocalAura.current.card) {labels.forEachIndexed{i,label->NavigationBarItem(selected==i,{onSelect(i)},{Icon(icons[i],null)},label={Text(label)})}}
}
@Composable private fun MediaItemRow(item:LibraryItem,onPlay:()->Unit,logo:String="") {
    val c=LocalAura.current;val version=Prefs.favoritesVersion
    Row(Modifier.fillMaxWidth().auraCard().clickable(onClick=onPlay).padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        LibraryArtwork(item,Modifier.width(68.dp).height(48.dp),logo)
        Column(Modifier.weight(1f)) {Text(item.title,color=c.text,maxLines=2,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.Medium);Text(listOf(item.folder,item.artist,item.album,if(item.durationMs>0)formatDuration(item.durationMs)else"").filter{it.isNotBlank()}.joinToString(" · "),color=c.subText,fontSize=11.sp,maxLines=1)}
        IconButton(onClick={Prefs.toggleFavorite(item.source)}){Icon(if(Prefs.isFavorite(item.source))Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,"المفضلة",tint=c.accent)}
    }
}
@Composable private fun EmptyLibrary(title:String,description:String) {
    Column(Modifier.fillMaxWidth().padding(30.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {Icon(Icons.Rounded.VideoLibrary,null,tint=LocalAura.current.accent,modifier=Modifier.size(46.dp));Text(title,color=LocalAura.current.text,fontSize=18.sp);Text(description,color=LocalAura.current.subText,fontSize=13.sp)}
}
private fun importMediaTree(context:android.content.Context,uri:Uri):Int {
    val root=DocumentFile.fromTreeUri(context,uri) ?: return 0;var count=0;val imported=mutableListOf<LibraryItem>()
    fun visit(directory:DocumentFile,depth:Int) {
        if(depth>12||count>=5000)return
        directory.listFiles().forEach { file -> if(count<5000) {
            if(file.isDirectory)visit(file,depth+1)
            else if(file.type?.startsWith("audio/")==true||file.type?.startsWith("video/")==true||file.name?.substringAfterLast('.')?.lowercase() in setOf("m3u","m3u8","pls","mkv","mp4","mp3","flac","webm","avi","m4a","opus")) {
                imported+=MediaCatalog.inspect(context,file.uri).copy(folder=directory.name ?: "مجلد");count++
            }
        } }
    }
    runCatching {visit(root,0)};MediaCatalog.rememberAll(context,imported);return count
}
