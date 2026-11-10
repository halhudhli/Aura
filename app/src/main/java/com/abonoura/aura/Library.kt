package com.abonoura.aura

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class LibraryTab(val label: String) {
    Folders("المجلدات"),
    All("كل الفيديوهات"),
    Recent("الأحدث"),
    Favorites("المفضلة"),
    History("سجل المشاهدة"),
}

@Composable
fun LibraryScreen(
    videos: List<Video>,
    loading: Boolean,
    tab: LibraryTab,
    onTab: (LibraryTab) -> Unit,
    onOpenFolder: (Folder) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onSettings: () -> Unit,
) {
    val colors = LocalAura.current
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var linkDialog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("بحث في الفيديوهات") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { }),
                    modifier = Modifier.weight(1f),
                )
                TopIcon(Icons.Rounded.Close, "إغلاق البحث") { searching = false; query = "" }
            } else {
                Image(painterResource(R.drawable.aura_logo), contentDescription = null, modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)))
                Text("Aura", color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp).weight(1f))
                TopIcon(Icons.Rounded.Search, "بحث") { searching = true }
                TopIcon(Icons.Rounded.Link, "فتح رابط") { linkDialog = true }
                TopIcon(Icons.Rounded.Settings, "الإعدادات", onSettings)
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryTab.entries.forEach { item -> Chip(item.label, item == tab) { onTab(item) } }
        }

        val q = query.trim()
        // Reading these two counters makes the lists refresh when favourites or history change.
        val favoritesVersion = Prefs.favoritesVersion
        val historyVersion = Prefs.historyVersion
        when {
            loading -> Hint("يتم تحميل الفيديوهات…")
            q.isNotEmpty() -> VideoList(videos.filter { it.title.contains(q, ignoreCase = true) }.sortedBy { it.title.lowercase() }, onPlay, "لا توجد نتائج")
            tab == LibraryTab.Folders -> {
                val folders = remember(videos) { Media.folders(videos) }
                if (folders.isEmpty()) Hint("لا توجد فيديوهات في الجهاز") else LazyColumn(contentPadding = listPadding()) {
                    items(folders, key = { it.id }) { folder -> FolderRow(folder) { onOpenFolder(folder) } }
                }
            }
            tab == LibraryTab.All -> VideoList(remember(videos) { videos.sortedBy { it.title.lowercase() } }, onPlay, "لا توجد فيديوهات في الجهاز")
            tab == LibraryTab.Recent -> VideoList(remember(videos) { videos.sortedByDescending { it.dateAdded } }, onPlay, "لا توجد فيديوهات في الجهاز")
            tab == LibraryTab.Favorites -> {
                val favorites = remember(videos, favoritesVersion) { Prefs.favorites().let { set -> videos.filter { it.path in set } } }
                VideoList(favorites.sortedBy { it.title.lowercase() }, onPlay, "لا توجد فيديوهات في المفضلة")
            }
            else -> {
                val history = remember(historyVersion) { Prefs.history() }
                if (history.isEmpty()) Hint("لم تُشاهد أي فيديوهات بعد") else LazyColumn(contentPadding = listPadding()) {
                    items(history, key = { it.source }) { entry ->
                        val video = videos.firstOrNull { it.path == entry.source }
                        if (video != null) {
                            VideoRow(video) { onPlay(PlayRequest(video.path, video.title)) }
                        } else {
                            LinkRow(entry) { onPlay(PlayRequest(entry.source, entry.title)) }
                        }
                    }
                }
            }
        }
    }

    if (linkDialog) {
        var link by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { linkDialog = false },
            title = { Text("فتح رابط فيديو") },
            text = {
                OutlinedTextField(value = link, onValueChange = { link = it }, singleLine = true, placeholder = { Text("https://…") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = link.trim()
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        linkDialog = false
                        onPlay(PlayRequest(url, url.substringAfterLast('/').substringBefore('?').ifBlank { url }))
                    }
                }) { Text("تشغيل") }
            },
            dismissButton = { TextButton(onClick = { linkDialog = false }) { Text("إلغاء") } },
        )
    }
}

@Composable
fun FolderScreen(folder: Folder, onPlay: (PlayRequest) -> Unit, onBack: () -> Unit) {
    val colors = LocalAura.current
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TopIcon(Icons.AutoMirrored.Rounded.ArrowBack, "رجوع", onBack)
            Text(folder.name, color = colors.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
        }
        VideoList(folder.videos, onPlay, "المجلد فارغ")
    }
}

@Composable
private fun listPadding() = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 24.dp)

@Composable
private fun VideoList(videos: List<Video>, onPlay: (PlayRequest) -> Unit, emptyText: String) {
    if (videos.isEmpty()) {
        Hint(emptyText)
        return
    }
    LazyColumn(contentPadding = listPadding(), modifier = Modifier.navigationBarsPadding()) {
        items(videos, key = { it.id }) { video -> VideoRow(video) { onPlay(PlayRequest(video.path, video.title)) } }
    }
}

@Composable
private fun VideoRow(video: Video, onClick: () -> Unit) {
    val colors = LocalAura.current
    val context = LocalContext.current
    var thumbnail by remember(video.id) { mutableStateOf<ImageBitmap?>(Media.cachedThumbnail(video.id)) }
    LaunchedEffect(video.id) { if (thumbnail == null) thumbnail = Media.thumbnail(context, video) }
    val historyVersion = Prefs.historyVersion
    val favoritesVersion = Prefs.favoritesVersion
    val progress = remember(video.path, historyVersion) { Prefs.progress(video.path) }
    val favorite = remember(video.path, favoritesVersion) { Prefs.isFavorite(video.path) }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).auraCard().clickable(onClick = onClick).padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(112.dp).height(64.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.35f))) {
            thumbnail?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (video.durationMs > 0L) {
                Text(
                    formatDuration(video.durationMs),
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(video.title, color = colors.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details = listOf(resolutionLabel(video.width, video.height), formatSize(video.sizeBytes)).filter { it.isNotBlank() }.joinToString(" · ")
            Text(details, color = colors.subText, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            if (progress != null && progress.positionMs > 0L && progress.durationMs > 0L) {
                ProgressLine(progress.positionMs.toFloat() / progress.durationMs)
            }
        }
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { Prefs.toggleFavorite(video.path) }, contentAlignment = Alignment.Center) {
            Icon(
                if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (favorite) "إزالة من المفضلة" else "إضافة إلى المفضلة",
                tint = if (favorite) colors.accent else colors.subText,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun LinkRow(entry: HistoryEntry, onClick: () -> Unit) {
    val colors = LocalAura.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).auraCard().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Link, contentDescription = null, tint = colors.accent, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(entry.title, color = colors.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (entry.durationMs > 0L) ProgressLine(entry.positionMs.toFloat() / entry.durationMs)
        }
    }
}

@Composable
private fun ProgressLine(fraction: Float) {
    val colors = LocalAura.current
    Box(Modifier.padding(top = 7.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(colors.subText.copy(alpha = 0.3f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(colors.accent))
    }
}

@Composable
private fun FolderRow(folder: Folder, onClick: () -> Unit) {
    val colors = LocalAura.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).auraCard().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(colors.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Folder, contentDescription = null, tint = colors.accent, modifier = Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(folder.name, color = colors.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${folder.videos.size} فيديو", color = colors.subText, fontSize = 12.sp)
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalAura.current
    val shape = RoundedCornerShape(16.dp)
    Text(
        label,
        color = if (selected) colors.onAccent else colors.text,
        fontSize = 13.sp,
        modifier = Modifier
            .then(if (selected) Modifier.clip(shape).background(colors.accent) else Modifier.auraCard(shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
fun TopIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = description, tint = LocalAura.current.text, modifier = Modifier.size(24.dp))
    }
}

@Composable
fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = LocalAura.current.subText, fontSize = 15.sp)
    }
}
