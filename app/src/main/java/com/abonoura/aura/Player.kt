package com.abonoura.aura

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** What to play: a file path or a link, with the name shown in the player. */
data class PlayRequest(val source: String, val title: String)

private enum class Panel { None, Subtitles, Audio, Speed, Appearance, Tools, Effects, Chapters, Bookmarks, Info }

private enum class DragKind { None, Seek, Brightness, Volume }

@Composable
fun PlayerScreen(request: PlayRequest, inPip: Boolean, onEnterPip: () -> Unit, onPlayingChanged: (Boolean) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val colors = LocalAura.current
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<AuraMpvView?>(null) }
    var state by remember { mutableStateOf(PlaybackState()) }
    var controls by remember { mutableStateOf(true) }
    var activity_tick by remember { mutableIntStateOf(0) }
    var locked by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.None) }
    var onlineSubtitles by remember(request.source) { mutableStateOf(false) }
    var resize by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    var subtitleDelay by remember { mutableIntStateOf(0) }
    var audioDelay by remember { mutableIntStateOf(0) }
    var landscape by remember { mutableStateOf(true) }
    var seekPreviewMs by remember { mutableLongStateOf(-1L) }
    var holdBoost by remember { mutableStateOf(false) }
    var syncByEar by remember { mutableStateOf(false) }
    var earCapture by remember { mutableStateOf(EarSyncCapture()) }
    var syncGeneration by remember { mutableIntStateOf(0) }
    var visualPath by remember { mutableStateOf<String?>(null) }
    var visualPosition by remember { mutableStateOf<Long?>(null) }
    var resumeAfterVisual by remember { mutableStateOf(false) }
    var effects by remember { mutableStateOf(PlayerEffects()) }
    var repeatStart by remember { mutableStateOf<Long?>(null) }
    var repeatEnd by remember { mutableStateOf<Long?>(null) }
    var sleepDeadline by remember { mutableStateOf<Long?>(null) }
    var sleepRemaining by remember { mutableStateOf<Long?>(null) }
    var bookmarks by remember(request.source) { mutableStateOf(PlaybackBookmarks.load(context, request.source)) }
    val latestState by rememberUpdatedState(state)
    val latestInPip by rememberUpdatedState(inPip)

    fun flash(text: String) { message = text }
    fun touch() { activity_tick++ }
    fun resetCapture() { syncGeneration++; earCapture = EarSyncCapture() }
    fun changeSubtitleDelay(value: Int) {
        resetCapture()
        subtitleDelay = value.coerceIn(MIN_SUBTITLE_DELAY_MS, MAX_SUBTITLE_DELAY_MS)
        view?.setSubtitleDelay(subtitleDelay)
    }
    fun seekTo(position: Long) { resetCapture(); view?.seekTo(position) }
    fun seekBy(seconds: Int) { resetCapture(); view?.seekBy(seconds) }
    fun captureEar(heard: Boolean) {
        if (latestState.paused || latestState.buffering || latestState.subtitleTracks.none { it.selected }) return
        val generation = syncGeneration
        view?.capturePosition { position ->
            if (!syncByEar || latestInPip || generation != syncGeneration) return@capturePosition
            val capture = if (heard) earCapture.captureHeard(position) else earCapture.captureSaw(position)
            val correction = capture.resolveDelay(subtitleDelay)
            if (correction == null) earCapture = capture else {
                changeSubtitleDelay(correction)
                flash("تم ضبط توقيت الترجمة")
            }
        }
    }
    fun dismissVisual() {
        resetCapture()
        visualPath = null
        visualPosition = null
        if (resumeAfterVisual) view?.setPaused(false)
        resumeAfterVisual = false
    }

    // Full screen, landscape, screen kept on — and everything restored on the way out.
    DisposableEffect(landscape) {
        activity?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        onDispose { }
    }
    DisposableEffect(Unit) {
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            insets?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.attributes = window?.attributes?.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            onPlayingChanged(false)
        }
    }

    fun saveProgress() {
        val s = latestState
        if (Prefs.resume && s.durationMs > 0L) {
            // A video watched to the end starts from the beginning next time.
            val position = if (s.durationMs - s.positionMs < 15_000L) 0L else s.positionMs
            Prefs.saveProgress(request.source, request.title, position, s.durationMs)
        }
    }

    fun close() {
        saveProgress()
        onClose()
    }

    BackHandler {
        when {
            syncByEar -> { syncByEar = false; resetCapture() }
            panel != Panel.None -> panel = Panel.None
            locked -> flash("الشاشة مقفلة · يُضغط على القفل للفتح")
            else -> close()
        }
    }

    // Reads the state four times a second, and saves the position every few seconds.
    LaunchedEffect(view) {
        val v = view ?: return@LaunchedEffect
        var ticks = 0
        while (true) {
            v.readState { fresh ->
                val jump = fresh.positionMs - state.positionMs
                if (jump < -250L || jump > maxOf(2_000L, (state.speed * 1_000).toLong())) resetCapture()
                state = fresh
            }
            ticks++
            if (ticks % 20 == 0) saveProgress()
            delay(250L)
        }
    }
    LaunchedEffect(state.paused) { onPlayingChanged(!state.paused) }
    LaunchedEffect(inPip) {
        if (inPip) { syncByEar = false; resetCapture(); visualPath = null; visualPosition = null }
    }
    LaunchedEffect(state.paused, state.subtitleTracks.firstOrNull { it.selected }?.id, state.audioTracks.firstOrNull { it.selected }?.id, audioDelay) { resetCapture() }
    LaunchedEffect(view, SubtitleAppearanceStore.value) { view?.setSubtitleAppearance(SubtitleAppearanceStore.value) }
    LaunchedEffect(view, effects) { view?.setEffects(effects) }
    LaunchedEffect(sleepDeadline) {
        val deadline = sleepDeadline ?: run { sleepRemaining = null; return@LaunchedEffect }
        while (true) {
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            sleepRemaining = remaining
            if (remaining == 0L) {
                view?.setPaused(true)
                sleepDeadline = null
                flash("انتهى مؤقت الإيقاف")
                break
            }
            delay(minOf(1000L, remaining))
        }
    }
    LaunchedEffect(controls, activity_tick, state.paused, panel, scrubbing, onlineSubtitles, syncByEar, visualPath) {
        if (!controls || state.paused || panel != Panel.None || scrubbing || onlineSubtitles || syncByEar || visualPath != null) return@LaunchedEffect
        delay(3_500L)
        controls = false
    }
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        delay(1_200L)
        message = null
    }

    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = withContext(Dispatchers.IO) { runCatching { importSubtitle(context, uri) }.getOrNull() }
            if (path != null) {
                view?.addSubtitleFile(path)
                changeSubtitleDelay(0)
                flash("تم تحميل ملف الترجمة")
            } else flash("تعذّر فتح ملف الترجمة")
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                AuraMpvView(viewContext).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    runCatching {
                        installFonts(viewContext)
                        Utils.copyAssets(viewContext)
                        initialize(viewContext.filesDir.path, viewContext.cacheDir.path)
                        val start = if (Prefs.resume) Prefs.progress(request.source)?.positionMs ?: 0L else 0L
                        load(request.source, start)
                    }
                    view = this
                }
            },
            onRelease = { released ->
                if (view === released) view = null
                released.release()
            },
        )

        if (!inPip) {
            // Touch layer: taps, double taps, hold for speed, and the drag gestures.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(locked) {
                        detectTapGestures(
                            onTap = {
                                if (locked) flash("الشاشة مقفلة") else { controls = !controls; touch() }
                            },
                            onDoubleTap = { offset ->
                                if (locked) return@detectTapGestures
                                val third = size.width / 3f
                                when {
                                    offset.x < third -> { seekBy(-Prefs.seekSeconds); flash("−${Prefs.seekSeconds} ث") }
                                    offset.x > third * 2f -> { seekBy(Prefs.seekSeconds); flash("+${Prefs.seekSeconds} ث") }
                                    else -> view?.setPaused(!latestState.paused)
                                }
                            },
                            onLongPress = {
                                if (locked || !Prefs.gestures || latestState.paused) return@detectTapGestures
                                holdBoost = true
                                view?.setSpeed(Prefs.holdSpeed)
                                flash("السرعة ${Prefs.holdSpeed}×")
                            },
                            onPress = {
                                tryAwaitRelease()
                                if (holdBoost) {
                                    holdBoost = false
                                    view?.setSpeed(1f)
                                }
                            },
                        )
                    }
                    .pointerInput(locked) {
                        if (locked || !Prefs.gestures) return@pointerInput
                        var kind = DragKind.None
                        var startPosition = 0L
                        var seekOffsetMs = 0f
                        var level = 0f
                        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        detectDragGestures(
                            onDragStart = { kind = DragKind.None; seekOffsetMs = 0f; startPosition = latestState.positionMs },
                            onDragEnd = {
                                if (kind == DragKind.Seek && seekPreviewMs >= 0L) seekTo(seekPreviewMs)
                                seekPreviewMs = -1L
                                kind = DragKind.None
                            },
                            onDragCancel = { seekPreviewMs = -1L; kind = DragKind.None },
                        ) { change, drag ->
                            if (kind == DragKind.None) {
                                kind = when {
                                    abs(drag.x) > abs(drag.y) -> DragKind.Seek
                                    change.position.x < size.width / 2f -> DragKind.Brightness
                                    else -> DragKind.Volume
                                }
                                level = when (kind) {
                                    DragKind.Brightness -> activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
                                    DragKind.Volume -> audio?.let { it.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / it.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) } ?: 0.5f
                                    else -> 0f
                                }
                            }
                            when (kind) {
                                DragKind.Seek -> {
                                    // A full swipe across the screen moves two minutes.
                                    seekOffsetMs += drag.x / size.width * 120_000f
                                    val duration = latestState.durationMs
                                    seekPreviewMs = (startPosition + seekOffsetMs.toLong()).coerceIn(0L, if (duration > 0L) duration else Long.MAX_VALUE)
                                }
                                DragKind.Brightness -> {
                                    level = (level - drag.y / size.height).coerceIn(0.01f, 1f)
                                    activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = level } }
                                    flash("السطوع ${(level * 100).roundToInt()}%")
                                }
                                DragKind.Volume -> {
                                    level = (level - drag.y / size.height).coerceIn(0f, 1f)
                                    audio?.let { it.setStreamVolume(AudioManager.STREAM_MUSIC, (level * it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).roundToInt(), 0) }
                                    flash("الصوت ${(level * 100).roundToInt()}%")
                                }
                                DragKind.None -> Unit
                            }
                        }
                    },
            )

            if (state.buffering && !state.paused) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center).size(44.dp))
            }

            val centerText = if (seekPreviewMs >= 0L) "${formatDuration(seekPreviewMs)} / ${formatDuration(state.durationMs)}" else message
            if (centerText != null) {
                Text(
                    centerText,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }

            // Locked: only the unlock button shows.
            if (locked) {
                AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                    RoundButton(Icons.Rounded.Lock, "فتح القفل") { locked = false; touch() }
                }
            }

            AnimatedVisibility(controls && !locked, enter = fadeIn(), exit = fadeOut()) {
                // The player keeps a left-to-right layout so the seek bar and skip buttons read naturally.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent, Color.Black.copy(alpha = 0.7f))),
                        ),
                    ) {
                        Row(
                            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "رجوع") { close() }
                            Text(
                                request.title,
                                color = Color.White,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            )
                            RoundButton(Icons.Rounded.LockOpen, "قفل الشاشة") { locked = true; touch() }
                            Spacer(Modifier.width(8.dp))
                            RoundButton(Icons.Rounded.PictureInPictureAlt, "صورة داخل صورة") { onEnterPip() }
                        }

                        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                            RoundButton(Icons.Rounded.Replay10, "رجوع ${Prefs.seekSeconds} ثوان", 52) { seekBy(-Prefs.seekSeconds); touch() }
                            RoundButton(if (state.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (state.paused) "تشغيل" else "إيقاف مؤقت", 68) {
                                view?.setPaused(!state.paused); touch()
                            }
                            RoundButton(Icons.Rounded.Forward10, "تقديم ${Prefs.seekSeconds} ثوان", 52) { seekBy(Prefs.seekSeconds); touch() }
                        }

                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                            val duration = state.durationMs.coerceAtLeast(1L).toFloat()
                            Slider(
                                value = if (scrubbing) scrubValue else state.positionMs.toFloat().coerceIn(0f, duration),
                                onValueChange = { scrubbing = true; scrubValue = it; touch() },
                                onValueChangeFinished = { seekTo(scrubValue.toLong()); scrubbing = false },
                                valueRange = 0f..duration,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = colors.accent,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                                ),
                                modifier = Modifier.fillMaxWidth().height(28.dp),
                            )
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${formatDuration(if (scrubbing) scrubValue.toLong() else state.positionMs)} / ${formatDuration(state.durationMs)}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                )
                                Spacer(Modifier.width(12.dp))
                                FlatButton(Icons.Rounded.MoreVert, "أدوات المشغّل") { panel = Panel.Tools }
                                FlatButton(Icons.Rounded.Subtitles, "الترجمة") { panel = Panel.Subtitles }
                                FlatButton(Icons.Rounded.Audiotrack, "الصوت") { panel = Panel.Audio }
                                FlatButton(Icons.Rounded.Speed, "السرعة") { panel = Panel.Speed }
                                FlatButton(Icons.Rounded.AspectRatio, "الأبعاد") {
                                    resize = (resize + 1) % 3
                                    view?.setResize(resize)
                                    flash(listOf("ملاءمة", "تكبير", "تمديد")[resize])
                                    touch()
                                }
                                FlatButton(Icons.Rounded.ScreenRotation, "تدوير") { landscape = !landscape; touch() }
                            }
                        }
                    }
                }
            }

            if (panel != Panel.None) {
                SidePanel(
                    title = when (panel) {
                        Panel.Subtitles -> "الترجمة"; Panel.Audio -> "الصوت"; Panel.Speed -> "السرعة"
                        Panel.Appearance -> "شكل الترجمة"; Panel.Tools -> "أدوات المشغّل"
                        Panel.Effects -> "الصورة والصوت"; Panel.Chapters -> "الفصول"
                        Panel.Bookmarks -> "العلامات المحفوظة"; else -> "معلومات التشغيل"
                    },
                    onClose = { panel = Panel.None },
                ) {
                    when (panel) {
                        Panel.Subtitles -> {
                            Choice("البحث عن ترجمة عبر الإنترنت…", false) { panel = Panel.None; onlineSubtitles = true }
                            Choice("المزامنة بالسمع والرؤية…", false) {
                                if (state.subtitleTracks.none { it.selected }) flash("حدد ترجمة أولًا")
                                else { resetCapture(); panel = Panel.None; syncByEar = true }
                            }
                            Choice("المزامنة باختيار جملة…", false) {
                                val path = state.subtitleTracks.firstOrNull { it.selected }?.externalPath
                                if (path == null) flash("اختر ترجمة من ملف أو من البحث أولًا")
                                else {
                                    resetCapture(); syncByEar = false; panel = Panel.None
                                    visualPosition = null
                                    resumeAfterVisual = !state.paused
                                    visualPath = if (path.startsWith("file://")) Uri.parse(path).path else path
                                }
                            }
                            Choice("شكل الترجمة…", false) { panel = Panel.Appearance }
                            Choice("بدون ترجمة", state.subtitleTracks.none { it.selected }) { view?.selectSubtitle(null) }
                            state.subtitleTracks.forEach { track -> Choice(track.label, track.selected) { view?.selectSubtitle(track.id) } }
                            Choice("ترجمة من ملف…", false) { subtitlePicker.launch("*/*") }
                            Stepper("حجم الترجمة", "${(Prefs.subtitleScale * 100).roundToInt()}%",
                                onMinus = { Prefs.putSubScale(Prefs.subtitleScale - 0.1f); view?.setSubtitleScale(Prefs.subtitleScale) },
                                onPlus = { Prefs.putSubScale(Prefs.subtitleScale + 0.1f); view?.setSubtitleScale(Prefs.subtitleScale) })
                            Stepper("توقيت الترجمة", delayLabel(subtitleDelay),
                                onMinus = { changeSubtitleDelay(subtitleDelay - 100) },
                                onPlus = { changeSubtitleDelay(subtitleDelay + 100) })
                            Choice("إعادة توقيت الترجمة إلى الصفر", false) { changeSubtitleDelay(0) }
                        }
                        Panel.Audio -> {
                            state.audioTracks.forEach { track -> Choice(track.label, track.selected) { view?.selectAudio(track.id) } }
                            Stepper("توقيت الصوت", delayLabel(audioDelay),
                                onMinus = { audioDelay -= 50; view?.setAudioDelay(audioDelay) },
                                onPlus = { audioDelay += 50; view?.setAudioDelay(audioDelay) })
                        }
                        Panel.Speed -> listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                            Choice("${speed}×", abs(state.speed - speed) < 0.01f) { view?.setSpeed(speed) }
                        }
                        Panel.Appearance -> SubtitleAppearancePanel(
                            style = SubtitleAppearanceStore.value,
                            onChange = { SubtitleAppearanceStore.update(it) },
                            onReset = { SubtitleAppearanceStore.reset() },
                        )
                        Panel.Effects -> PlayerEffectsPanel(effects, { effects = it }, { effects = PlayerEffects() })
                        Panel.Tools -> {
                            Choice("شكل الترجمة", false) { panel = Panel.Appearance }
                            Choice("الصورة ووضوح الحوار والصوت الليلي", false) { panel = Panel.Effects }
                            Choice("الفصول (${state.chapters.size})", false) { panel = Panel.Chapters }
                            Choice("العلامات المحفوظة (${bookmarks.size})", false) { panel = Panel.Bookmarks }
                            Choice("إضافة علامة عند الموضع الحالي", false) {
                                view?.capturePosition { position ->
                                    val previous = bookmarks
                                    bookmarks = PlaybackBookmarks.add(context, request.source, position)
                                    flash(if (previous == bookmarks) "الموضع محفوظ أو بلغت حد العلامات" else "تم حفظ العلامة")
                                }
                            }
                            Choice("بداية التكرار A: ${repeatStart?.let { formatDuration(it) } ?: "غير محددة"}", repeatStart != null) {
                                resetCapture()
                                view?.capturePosition { position -> repeatStart = position; repeatEnd = null; view?.setRepeat(null, null) }
                            }
                            Choice("نهاية التكرار B: ${repeatEnd?.let { formatDuration(it) } ?: "غير محددة"}", repeatEnd != null) {
                                view?.capturePosition { position ->
                                    val start = repeatStart
                                    if (start == null || position - start < 500L) flash("حدد بداية A ثم نهاية بعدها بنصف ثانية على الأقل")
                                    else { resetCapture(); repeatEnd = position; view?.setRepeat(start, position); flash("تم تفعيل تكرار المقطع") }
                                }
                            }
                            if (repeatStart != null) Choice("إلغاء تكرار المقطع", false) { repeatStart = null; repeatEnd = null; resetCapture(); view?.setRepeat(null, null) }
                            Text("مؤقت الإيقاف${sleepRemaining?.let { " · ${formatDuration(it)}" } ?: ""}", color = Color.White, modifier = Modifier.padding(top = 10.dp))
                            listOf(15, 30, 60, 90).forEach { minutes ->
                                Choice("بعد $minutes دقيقة", false) { sleepDeadline = SystemClock.elapsedRealtime() + minutes * 60_000L; flash("تم ضبط مؤقت الإيقاف") }
                            }
                            if (sleepDeadline != null) Choice("إلغاء المؤقت", false) { sleepDeadline = null }
                            Choice("لقطة للشاشة ومشاركتها", false) {
                                panel = Panel.None
                                val target = File(context.cacheDir, "screenshots").apply { mkdirs() }
                                view?.screenshot(File(target, "Aura-${System.currentTimeMillis()}.png")) { file ->
                                    if (file == null) flash("تعذّر التقاط صورة من هذا الفيديو")
                                    else runCatching {
                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                            type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }, "لقطة من Aura"))
                                    }.onFailure { flash("تعذّر فتح المشاركة") }
                                }
                            }
                            Choice("معلومات التشغيل", false) { panel = Panel.Info }
                        }
                        Panel.Chapters -> {
                            if (state.chapters.isEmpty()) Text("لا يحتوي هذا الفيديو على فصول متاحة.", color = Color.White)
                            state.chapters.forEach { chapter -> Choice("${formatDuration(chapter.positionMs)} · ${chapter.title}", false) { seekTo(chapter.positionMs); panel = Panel.None } }
                        }
                        Panel.Bookmarks -> {
                            if (bookmarks.isEmpty()) Text("أضف علامة من أدوات المشغّل للعودة إلى المشهد لاحقًا.", color = Color.White)
                            bookmarks.forEach { position -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) { Choice(formatDuration(position), false) { seekTo(position); panel = Panel.None } }
                                androidx.compose.material3.TextButton(onClick = { bookmarks = PlaybackBookmarks.remove(context, request.source, position) }) { Text("حذف") }
                            } }
                        }
                        Panel.Info -> {
                            listOf(
                                request.title,
                                "الدقة: ${state.videoWidth} × ${state.videoHeight}",
                                "الفيديو: ${state.videoCodec.ifBlank { "غير متاح" }}",
                                "الصوت: ${state.audioCodec.ifBlank { "غير متاح" }}",
                                "الإطارات: ${if (state.fps > 0) String.format(java.util.Locale.US, "%.2f", state.fps) else "غير متاح"}",
                                "المدة: ${formatDuration(state.durationMs)}",
                                "مسارات الصوت: ${state.audioTracks.size} · الترجمة: ${state.subtitleTracks.size}",
                            ).forEach { Text(it, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp)) }
                        }
                        Panel.None -> Unit
                    }
                }
            }

            if (syncByEar) SubtitleSyncCard(
                subtitleDelay, earCapture, { captureEar(true) }, { captureEar(false) },
                onReset = { resetCapture() }, onClose = { syncByEar = false; resetCapture() },
                captureEnabled = !state.paused && !state.buffering && state.subtitleTracks.any { it.selected },
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            )

            visualPath?.let { path -> VisualSubtitleSyncDialog(
                path, visualPosition, state.paused,
                onCapture = {
                    val generation = syncGeneration
                    view?.capturePosition { position ->
                        if (visualPath == path && syncGeneration == generation) {
                            visualPosition = position
                            view?.setPaused(true)
                        }
                    }
                },
                onTogglePlayback = { view?.setPaused(!state.paused) },
                onChoose = { cue -> visualPosition?.let { changeSubtitleDelay(subtitleDelayForCue(it, cue)); dismissVisual(); flash("تم ضبط التوقيت على الجملة المحددة") } },
                onDismiss = { dismissVisual() },
            ) }

            if (onlineSubtitles) {
                key(request.source) {
                    OnlineSubtitlesDialog(
                        videoTitle = request.title,
                        onDismiss = { onlineSubtitles = false; panel = Panel.Subtitles },
                        onDownloaded = { file, subtitle ->
                            view?.let { player ->
                                player.addSubtitleFile(file.path, subtitle.release, subtitle.language)
                                changeSubtitleDelay(0)
                                flash("تم تحميل الترجمة وتشغيلها")
                            }
                            onlineSubtitles = false
                            controls = true
                            touch()
                        },
                    )
                }
            }
        }
    }
}

private fun delayLabel(ms: Int): String = when {
    ms == 0 -> "بدون تعديل"
    ms > 0 -> "تأخير ${ms} مللي ثانية"
    else -> "تقديم ${-ms} مللي ثانية"
}

/** Copies the bundled subtitle font next to mpv's files once. */
private fun installFonts(context: Context) {
    val dir = File(context.filesDir, "fonts").apply { mkdirs() }
    listOf("Almarai-Regular.ttf", "Almarai-Bold.ttf").forEach { name ->
        val target = File(dir, name)
        if (!target.exists() || target.length() == 0L) {
            context.assets.open("fonts/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }
}

/** Reads the picked subtitle file in its real encoding and stores it as UTF-8 for the player. */
private fun importSubtitle(context: Context, uri: Uri): String? {
    val bytes = context.contentResolver.openInputStream(uri)?.use { readLimited(it, MAX_SUBTITLE_BYTES) } ?: return null
    if (bytes.isEmpty() || bytes.size > 8 * 1024 * 1024) return null
    val text = SubtitleText.decode(bytes)
    val head = text.trimStart().take(200)
    val extension = when {
        head.startsWith("WEBVTT", ignoreCase = true) -> "vtt"
        head.startsWith("[Script Info]", ignoreCase = true) -> "ass"
        else -> "srt"
    }
    val dir = File(context.cacheDir, "subtitles").apply { mkdirs() }
    val file = File(dir, "picked-${System.currentTimeMillis()}.$extension")
    file.writeText(text, Charsets.UTF_8)
    return file.path
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, sizeDp: Int = 42, onClick: () -> Unit) {
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.38f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size((sizeDp * 0.55f).dp))
    }
}

@Composable
private fun FlatButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun SidePanel(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = onClose)) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .widthIn(max = 340.dp)
                .fillMaxWidth(0.8f)
                .background(Color(0xF2141414))
                .clickable(enabled = false) { }
                .padding(18.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            content()
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = LocalAura.current.accent, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Stepper(title: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(title, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            StepButton("−", onMinus)
            Text(value, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            StepButton("+", onPlus)
        }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 20.sp) }
}
