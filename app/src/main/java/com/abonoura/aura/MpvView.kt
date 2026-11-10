package com.abonoura.aura

import android.content.Context
import android.os.Handler
import android.os.Looper
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

    override fun initOptions() {
        setVo("gpu")
        mpv.setOptionString("profile", "fast")
        mpv.setOptionString("hwdec", if (Prefs.hardwareDecoding) "auto" else "no")
        mpv.setOptionString("msg-level", "all=warn")
        mpv.setOptionString("volume-max", "200")
        // Subtitles: files that are not valid UTF-8 are read as Arabic Windows-1256, a subtitle
        // file next to the video is picked up by itself, and the bundled font covers Arabic and Latin.
        mpv.setOptionString("sub-codepage", "cp1256")
        mpv.setOptionString("sub-auto", "fuzzy")
        mpv.setOptionString("sub-fonts-dir", File(context.filesDir, "fonts").path)
        mpv.setOptionString("sub-font", "Almarai")
        mpv.setOptionString("sub-scale", Prefs.subtitleScale.toString())
        mpv.setOptionString("demuxer-max-bytes", "${64 * 1024 * 1024}")
        mpv.setOptionString("demuxer-max-back-bytes", "${32 * 1024 * 1024}")
        mpv.setPropertyBoolean("keep-open", true)
        mpv.setPropertyBoolean("audio-fallback-to-null", true)
    }

    override fun postInitOptions() = Unit

    override fun observeProperties() = Unit

    /** Runs [block] on the mpv thread; ignored after release. */
    fun post(block: MPV.() -> Unit) {
        if (released.get()) return
        runCatching {
            worker.execute {
                if (!released.get()) runCatching { mpv.block() }
            }
        }
    }

    fun load(source: String, startMs: Long) = post {
        setPropertyString("start", if (startMs > 0L) (startMs / 1000.0).toString() else "none")
        setPropertyBoolean("pause", false)
        command("loadfile", source, "replace")
    }

    fun setPaused(paused: Boolean) = post { setPropertyBoolean("pause", paused) }

    fun seekTo(positionMs: Long) = post { command("seek", (positionMs.coerceAtLeast(0L) / 1000.0).toString(), "absolute") }

    fun seekBy(seconds: Int) = post { command("seek", seconds.toString(), "relative") }

    fun setSpeed(speed: Float) = post { setPropertyDouble("speed", speed.toDouble()) }

    fun selectAudio(id: Int?) = post { if (id == null) setPropertyString("aid", "no") else setPropertyInt("aid", id) }

    fun selectSubtitle(id: Int?) = post { if (id == null) setPropertyString("sid", "no") else setPropertyInt("sid", id) }

    fun addSubtitleFile(path: String, title: String? = null, language: String? = null) = post {
        if (title == null) command("sub-add", path, "select")
        else command("sub-add", path, "select", title, language.orEmpty())
    }

    fun setSubtitleScale(scale: Float) = post { setPropertyDouble("sub-scale", scale.toDouble()) }

    fun setSubtitleDelay(ms: Int) = post { setPropertyDouble("sub-delay", ms / 1000.0) }

    /** Captures the engine timestamp at the tap, without the UI polling interval's rounding. */
    fun capturePosition(onPosition: (Long) -> Unit) = post {
        val position = ((getPropertyDouble("time-pos") ?: 0.0) * 1000).toLong().coerceAtLeast(0L)
        main.post { if (!released.get()) onPosition(position) }
    }

    fun setSubtitleAppearance(style: SubtitleAppearance) = post { applyAppearance(style) }

    private fun MPV.applyAppearance(style: SubtitleAppearance) {
        val value = style.normalized()
        setPropertyString("sub-font", value.font.mpvName)
        setPropertyBoolean("sub-bold", value.bold)
        setPropertyString("sub-color", value.mpvTextColor)
        setPropertyDouble("sub-border-size", if (value.outlineEnabled) 2.0 else 0.0)
        setPropertyString("sub-border-color", value.mpvOutlineColor)
        setPropertyDouble("sub-shadow-offset", if (value.shadowEnabled) value.shadowStrength.toDouble() else 0.0)
        setPropertyDouble("sub-pos", value.position.toDouble())
        // Apply the chosen appearance to styled ASS tracks as well.
        setPropertyString("sub-ass-override", "force")
    }

    fun setEffects(effects: PlayerEffects) = post {
        val value = effects.normalized()
        setPropertyDouble("brightness", value.brightness.toDouble())
        setPropertyDouble("contrast", value.contrast.toDouble())
        setPropertyDouble("saturation", value.saturation.toDouble())
        setPropertyDouble("gamma", value.gamma.toDouble())
        runCatching { command("af", "remove", "@aura_audio") }
        value.audioMode.mpvFilter?.let { command("af", "add", "@aura_audio:$it") }
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
            command("screenshot-to-file", file.path, "subtitles")
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
                Track(id, label, node.flag("selected") ?: false, node.text("external-filename"))
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
            )
        main.post { if (!released.get()) onState(snapshot) }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        runCatching { holder.removeCallback(this) }
        runCatching {
            worker.execute {
                runCatching { mpv.destroy() }
            }
            worker.shutdown()
        }
    }
}

private fun MPVNode.text(key: String): String? = runCatching { this[key]?.asString() }.getOrNull()?.takeIf { it.isNotBlank() }

private fun MPVNode.int(key: String): Int? = runCatching { this[key]?.asInt()?.toInt() }.getOrNull()

private fun MPVNode.flag(key: String): Boolean? = runCatching { this[key]?.asBoolean() }.getOrNull()
