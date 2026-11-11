package com.abonoura.aura

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private var playing by mutableStateOf<PlayRequest?>(null)
    private var inPip by mutableStateOf(false)
    private var videoIsPlaying = false

    private val versionName: String by lazy {
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        SubtitleAppearanceStore.init(this)
        handleIntent(intent)
        setContent {
            // Aura's own screens are Arabic, right to left.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AuraTheme { Root() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Open with Aura": a video file or a link handed over by another app. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val title = displayName(uri) ?: uri.lastPathSegment ?: "فيديو"
        val source = when (uri.scheme) {
            "http", "https" -> uri.toString()
            "file" -> uri.path ?: return
            else -> realPath(uri) ?: runCatching {
                // No readable path: hand mpv an open file descriptor instead.
                contentResolver.openFileDescriptor(uri, "r")?.detachFd()?.let { "fdclose://$it" }
            }.getOrNull() ?: return
        }
        playing = PlayRequest(source, title.substringBeforeLast('.').ifBlank { title })
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun realPath(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.takeIf { it.isNotBlank() && java.io.File(it).canRead() }
    }.getOrNull()

    private fun enterPip() {
        runCatching {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (playing != null && videoIsPlaying && Prefs.autoPip) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    @Composable
    private fun Root() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        var granted by androidx.compose.runtime.remember {
            mutableStateOf(ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED)
        }
        val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
        var videos by androidx.compose.runtime.remember { mutableStateOf<List<Video>>(emptyList()) }
        var loading by androidx.compose.runtime.remember { mutableStateOf(true) }
        var tab by androidx.compose.runtime.remember { mutableStateOf(LibraryTab.Folders) }
        var openFolderId by androidx.compose.runtime.remember { mutableStateOf<Long?>(null) }
        var settings by androidx.compose.runtime.remember { mutableStateOf(false) }

        // The list is read again whenever the player closes, so new files and progress show up.
        LaunchedEffect(granted, playing == null) {
            if (granted && playing == null) {
                videos = Media.loadVideos(this@MainActivity)
                loading = false
            }
        }

        val request = playing
        when {
            request != null -> key(request.source) { PlayerScreen(
                request = request,
                inPip = inPip,
                onEnterPip = { enterPip() },
                onPlayingChanged = { videoIsPlaying = it },
                onClose = { playing = null },
            ) }
            !granted -> PermissionScreen { askPermission.launch(permission) }
            settings -> {
                BackHandler { settings = false }
                SettingsScreen(versionName) { settings = false }
            }
            openFolderId != null -> {
                BackHandler { openFolderId = null }
                val folder = Media.folders(videos).firstOrNull { it.id == openFolderId }
                if (folder == null) openFolderId = null else FolderScreen(folder, onPlay = { playing = it }, onBack = { openFolderId = null })
            }
            else -> LibraryScreen(
                videos = videos,
                loading = loading,
                tab = tab,
                onTab = { tab = it },
                onOpenFolder = { openFolderId = it.id },
                onPlay = { playing = it },
                onSettings = { settings = true },
            )
        }
    }

    @Composable
    private fun PermissionScreen(onAllow: () -> Unit) {
        val colors = LocalAura.current
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(R.drawable.aura_logo), contentDescription = null, modifier = Modifier.size(132.dp))
            Text("AURA · أورا", color = colors.text, fontSize = 28.sp)
            Text(
                "لعرض الفيديوهات الموجودة في الجهاز يلزم السماح بالوصول إليها. لا تغادر الفيديوهات الجهاز، ولا توجد إعلانات ولا تتبّع.",
                color = colors.subText,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
            )
            Chip("السماح بالوصول إلى الفيديوهات", true, onAllow)
        }
    }
}
