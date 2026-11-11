package com.abonoura.aura

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class Track(val id: Int, val label: String, val selected: Boolean, val externalPath: String? = null)
data class Chapter(val title: String, val positionMs: Long)

/** What the screen needs to know about playback, read from mpv a few times a second. */
data class PlaybackState(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val paused: Boolean = true,
    val buffering: Boolean = true,
    val ended: Boolean = false,
    val speed: Float = 1f,
    val audioTracks: List<Track> = emptyList(),
    val subtitleTracks: List<Track> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoCodec: String = "",
    val audioCodec: String = "",
    val fps: Double = 0.0,
    val error:String?=null,
)

/**
 * The mpv video surface. Every call into mpv goes through one background thread, so the screen
 * never waits on the player.
 */
class AuraMpvView(context: Context) : BaseMPVView(context, null) {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AuraMpv").apply { isDaemon = true }
    }
    private val released = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private var appliedAudioMode = AudioMode.Normal
    private var loadFailure:String?=null
    private var loadStarted=0L
    private var customFont=""
    private var attached=false

    override fun initOptions() {
        setVo("gpu")
        mpv.setOptionString("profile", "fast")
        mpv.setOptionString("hwdec", if (Prefs.hardwareDecoding) "auto" else "no")
        mpv.setOptionString("msg-level", "all=warn")
        mpv.setOptionString("volume-max", "200")
        mpv.setOptionString("network-timeout","20")
        mpv.setOptionString("screenshot-format", "png")
        // Subtitles: files that are not valid UTF-8 are read as Arabic Windows-1256, a subtitle
        // file next to the video is picked up by itself, and the bundled font covers Arabic and Latin.
        mpv.setOptionString("sub-codepage", "cp1256")
        mpv.setOptionString("sub-auto", "fuzzy")
        mpv.setOptionString("sub-fonts-dir", File(context.filesDir, "fonts").path)
        mpv.setOptionString("sub-font", "Almarai")
        mpv.setOptionString("slang",listOf(Prefs.subtitleLanguage,"ar","en").distinct().joinToString(","))
        mpv.setOptionString("sub-scale", Prefs.subtitleScale.toString())
        mpv.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
        mpv.setOptionString("demuxer-max-back-bytes", "${32 * 1024 * 1024}")
        mpv.setPropertyBoolean("keep-open", true)
        mpv.setPropertyBoolean("audio-fallback-to-null", true)
    }

    override fun postInitOptions() = Unit

    override fun observeProperties() = Unit

    // Surface destruction can wait for mpv's renderer. Keep it on the same worker as every
    // other engine call so removing a Compose player never blocks Android's main thread.
    override fun surfaceCreated(holder:SurfaceHolder) {
        val surface=holder.surface
        post {if(surface.isValid){attachSurface(surface);attached=true;setPropertyString("force-window","yes");setPropertyString("vo","gpu")}}
    }
    override fun surfaceChanged(holder:SurfaceHolder,format:Int,width:Int,height:Int) {
        post {setPropertyString("android-surface-size","${width}x$height")}
    }
    override fun surfaceDestroyed(holder:SurfaceHolder) {
        post {setPropertyString("vo","null");setPropertyString("force-window","no");if(attached){detachSurface();attached=false}}
    }

    /** Runs [block] on the mpv thread; ignored after release. */
    fun post(block: MPV.() -> Unit) {
        if (released.get()) return
        runCatching {
            worker.execute {
                if (!released.get()) runCatching { mpv.block() }
            }
        }
    }

    fun load(source: String, startMs: Long, headers: Map<String, String> = emptyMap()) = post {
        loadFailure=null;loadStarted=android.os.SystemClock.elapsedRealtime()
        try {
        val playable = when {
            source.startsWith("aura-network://") -> NetworkRelay.url(context,source)
            source.startsWith("content://") -> context.contentResolver.openFileDescriptor(Uri.parse(source), "r")?.use{it.detachFd()}?.let { "fdclose://$it" } ?: error("access")
            source.startsWith("file://") -> Uri.parse(source).path ?: return@post
            else -> source
        }
        setPropertyString("http-header-fields", headers.entries.joinToString(",") { (key, value) -> "$key: ${value.replace("\\", "\\\\").replace(",", "\\,")}" })
        setPropertyString("start", if (startMs > 0L) (startMs / 1000.0).toString() else "none")
        setPropertyBoolean("pause", false)
        command("loadfile", playable, "replace")
        }catch(_:Exception){loadFailure="تعذّر فتح المصدر. تحقق من وجود الملف وصلاحية الوصول أو اتصال الشبكة."}
    }

    fun setPaused(paused: Boolean) = post { setPropertyBoolean("pause", paused) }

    fun seekTo(positionMs: Long) = post { command("seek", (positionMs.coerceAtLeast(0L) / 1000.0).toString(), "absolute") }

    fun seekBy(seconds: Int) = post { command("seek", seconds.toString(), "relative") }

    fun setSpeed(speed: Float) = post { setPropertyDouble("speed", speed.toDouble()) }
    fun setZoom(scale:Float,x:Float,y:Float)=post {
        setPropertyDouble("video-zoom",(kotlin.math.ln(scale.coerceIn(1f,5f))/kotlin.math.ln(2f)).toDouble())
        setPropertyDouble("video-pan-x",x.coerceIn(-1f,1f).toDouble());setPropertyDouble("video-pan-y",y.coerceIn(-1f,1f).toDouble())
    }

    fun selectAudio(id: Int?) = post { if (id == null) setPropertyString("aid", "no") else setPropertyInt("aid", id) }

    fun selectSubtitle(id: Int?) = post { if (id == null) setPropertyString("sid", "no") else setPropertyInt("sid", id) }

    fun addSubtitleFile(path: String, title: String? = null, language: String? = null) = post {
        if (title == null) command("sub-add", path, "select")
        else command("sub-add", path, "select", title, language.orEmpty())
    }

    fun setSubtitleScale(scale: Float) = post { setPropertyDouble("sub-scale", scale.toDouble()) }

    fun setSubtitleDelay(ms: Int) = post { setPropertyDouble("sub-delay", ms / 1000.0) }
    fun selectSecondarySubtitle(id:Int?)=post {setPropertyString("secondary-sid",id?.toString() ?: "no");setPropertyDouble("secondary-sub-pos",5.0)}

    /** Captures the engine timestamp at the tap, without the UI polling interval's rounding. */
    fun capturePosition(onPosition: (Long) -> Unit) = post {
        val position = ((getPropertyDouble("time-pos") ?: 0.0) * 1000).toLong().coerceAtLeast(0L)
        main.post { if (!released.get()) onPosition(position) }
    }

    fun setSubtitleAppearance(style: SubtitleAppearance) = post {
        if(style.customFont!=customFont){customFont=style.customFont;setPropertyString("sub-fonts-dir",File(context.filesDir,"fonts").path)}
        applyAppearance(style)
    }

    private fun MPV.applyAppearance(style: SubtitleAppearance) {
        val value = style.normalized()
        setPropertyString("sub-font", value.customFont.ifBlank{value.font.mpvName})
        setPropertyBoolean("sub-filter-sdh",value.removeSdh)
        setPropertyBoolean("sub-bold", value.bold)
        setPropertyString("sub-color", value.mpvTextColor)
        setPropertyDouble("sub-border-size", if (value.outlineEnabled) 2.0 else 0.0)
        setPropertyString("sub-border-color", value.mpvOutlineColor)
        setPropertyDouble("sub-shadow-offset", if (value.shadowEnabled) value.shadowStrength.toDouble() else 0.0)
        setPropertyDouble("sub-pos", value.position.toDouble())
        // Keep authored ASS styling until the user customizes the appearance.
        setPropertyString("sub-ass-override", if (value == SubtitleAppearance()) "scale" else "force")
    }

    fun setEffects(effects: PlayerEffects) = post {
        val value = effects.normalized()
        setPropertyDouble("brightness", value.brightness.toDouble())
        setPropertyDouble("contrast", value.contrast.toDouble())
        setPropertyDouble("saturation", value.saturation.toDouble())
        setPropertyDouble("gamma", value.gamma.toDouble())
        if (value.audioMode != appliedAudioMode) {
            runCatching { command("af", "remove", "@aura_audio") }
            value.audioMode.mpvFilter?.let { command("af", "add", "@aura_audio:$it") }
            appliedAudioMode = value.audioMode
        }
    }

    fun setRepeat(startMs: Long?, endMs: Long?) = post {
        setPropertyString("ab-loop-b", "no")
        setPropertyString("ab-loop-a", "no")
        if (startMs != null && endMs != null && endMs > startMs) {
            setPropertyDouble("ab-loop-a", startMs / 1000.0)
            setPropertyDouble("ab-loop-b", endMs / 1000.0)
        }
    }

    fun screenshot(file: File, onResult: (File?) -> Unit) = post {
        val result = runCatching {
            command("sync", "screenshot-to-file", file.path, "subtitles")
            file.takeIf { it.isFile && it.length() > 0L }
        }.getOrNull()
        main.post { if (!released.get()) onResult(result) }
    }

    fun setAudioDelay(ms: Int) = post { setPropertyDouble("audio-delay", ms / 1000.0) }

    /** 0 = fit, 1 = zoom (fills the screen, edges cropped), 2 = stretch. */
    fun setResize(mode: Int) = post {
        when (mode) {
            1 -> { setPropertyString("keepaspect", "yes"); setPropertyDouble("panscan", 1.0) }
            2 -> { setPropertyString("keepaspect", "no"); setPropertyDouble("panscan", 0.0) }
            else -> { setPropertyString("keepaspect", "yes"); setPropertyDouble("panscan", 0.0) }
        }
    }

    /** Reads the current state on the mpv thread and hands it to [onState] (also on that thread). */
    fun readState(onState: (PlaybackState) -> Unit) = post {
        val nodes = runCatching { getPropertyNode("track-list")?.asArray()?.toList() }.getOrNull().orEmpty()
        val primarySubtitle=getPropertyInt("sid")
        fun tracks(type: String): List<Track> = nodes
            .filter { it.text("type") == type }
            .mapIndexedNotNull { index, node ->
                val id = node.int("id") ?: return@mapIndexedNotNull null
                val title = node.text("title")
                val language = node.text("lang")
                val codec = node.text("codec")
                val label = listOfNotNull(title, language?.uppercase(), codec?.uppercase().takeIf { title == null })
                    .joinToString(" · ")
                    .ifBlank { "${index + 1}" }
                Track(id, label, if(type=="sub")id==primarySubtitle else node.flag("selected") ?: false, node.text("external-filename"))
            }
        val chapters = runCatching { getPropertyNode("chapter-list")?.asArray()?.toList() }.getOrNull().orEmpty()
            .mapIndexedNotNull { index, node ->
                val time = runCatching { node["time"]?.asDouble() }.getOrNull() ?: return@mapIndexedNotNull null
                Chapter(node.text("title") ?: "الفصل ${index + 1}", (time * 1000).toLong().coerceAtLeast(0L))
            }
        val snapshot = PlaybackState(
                positionMs = ((getPropertyDouble("time-pos") ?: 0.0) * 1000).toLong(),
                durationMs = ((getPropertyDouble("duration") ?: 0.0) * 1000).toLong(),
                paused = getPropertyBoolean("pause") ?: true,
                buffering = (getPropertyBoolean("paused-for-cache") ?: false) || (getPropertyBoolean("seeking") ?: false),
                ended = getPropertyBoolean("eof-reached") ?: false,
                speed = (getPropertyDouble("speed") ?: 1.0).toFloat(),
                audioTracks = tracks("audio"),
                subtitleTracks = tracks("sub"),
                chapters = chapters,
                videoWidth = (getPropertyDouble("width") ?: 0.0).toInt(),
                videoHeight = (getPropertyDouble("height") ?: 0.0).toInt(),
                videoCodec = getPropertyString("video-format").orEmpty(),
                audioCodec = getPropertyString("audio-codec-name").orEmpty(),
                fps = getPropertyDouble("estimated-vf-fps") ?: 0.0,
                error=loadFailure ?: if(loadStarted>0 && android.os.SystemClock.elapsedRealtime()-loadStarted>5000 && getPropertyBoolean("idle-active")==true)"تعذّر تشغيل المصدر؛ تحقق من الرابط والصيغة والصلاحيات."else null,
            )
        main.post { if (!released.get()) onState(snapshot) }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        runCatching { holder.removeCallback(this) }
        runCatching {
            worker.execute {
                runCatching {
                    if(mpv.isInitialized) {
                        mpv.setPropertyBoolean("pause",true)
                        mpv.setPropertyString("vo","null")
                        mpv.setPropertyString("force-window","no")
                        if(attached){mpv.detachSurface();attached=false}
                    }
                }
                runCatching { mpv.destroy() }
            }
            worker.shutdown()
        }
    }
}

private fun MPVNode.text(key: String): String? = runCatching { this[key]?.asString() }.getOrNull()?.takeIf { it.isNotBlank() }

private fun MPVNode.int(key: String): Int? = runCatching { this[key]?.asInt()?.toInt() }.getOrNull()

private fun MPVNode.flag(key: String): Boolean? = runCatching { this[key]?.asBoolean() }.getOrNull()
