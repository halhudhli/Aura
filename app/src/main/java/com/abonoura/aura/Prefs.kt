package com.abonoura.aura

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One entry of «سجل المشاهدة». */
data class HistoryEntry(val source: String, val title: String, val positionMs: Long, val durationMs: Long, val time: Long)

/**
 * Everything Aura remembers, kept on the device only: the chosen look, the player options and
 * where each video stopped. Values the screens draw from are Compose state, so a change shows at once.
 */
object Prefs {
    private lateinit var sp: SharedPreferences

    var look by mutableStateOf(AuraLook.Glass)
        private set
    var backgroundFile by mutableStateOf<String?>(null)
        private set
    var backgroundDim by mutableStateOf(20)
        private set
    var resume by mutableStateOf(true)
        private set
    var gestures by mutableStateOf(true)
        private set
    var hardwareDecoding by mutableStateOf(true)
        private set
    var autoPip by mutableStateOf(true)
        private set
    var seekSeconds by mutableStateOf(10)
        private set
    var holdSpeed by mutableStateOf(2f)
        private set
    var subtitleScale by mutableStateOf(1f)
        private set
    var subtitleLanguage by mutableStateOf("ar")
        private set
    var historyVersion by mutableStateOf(0)
        private set

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences("aura", Context.MODE_PRIVATE)
        look = AuraLook.entries.firstOrNull { it.name == sp.getString("look", null) } ?: AuraLook.Glass
        backgroundFile = sp.getString("bg_file", null)
        backgroundDim = sp.getInt("bg_dim", 20)
        resume = sp.getBoolean("resume", true)
        gestures = sp.getBoolean("gestures", true)
        hardwareDecoding = sp.getBoolean("hwdec", true)
        autoPip = sp.getBoolean("auto_pip", true)
        seekSeconds = sp.getInt("seek_seconds", 10)
        holdSpeed = sp.getFloat("hold_speed", 2f)
        subtitleScale = sp.getFloat("sub_scale", 1f)
        subtitleLanguage = sp.getString("sub_language", "ar").orEmpty()
            .takeIf { code -> subtitleLanguages.any { it.code == code } } ?: "ar"
    }

    fun putLook(value: AuraLook) { look = value; sp.edit().putString("look", value.name).apply() }
    fun putBackground(file: String?) { backgroundFile = file; sp.edit().putString("bg_file", file).apply() }
    fun putDim(value: Int) { backgroundDim = value.coerceIn(0, 80); sp.edit().putInt("bg_dim", backgroundDim).apply() }
    fun putResume(value: Boolean) { resume = value; sp.edit().putBoolean("resume", value).apply() }
    fun putGestures(value: Boolean) { gestures = value; sp.edit().putBoolean("gestures", value).apply() }
    fun putHardware(value: Boolean) { hardwareDecoding = value; sp.edit().putBoolean("hwdec", value).apply() }
    fun putAutoPip(value: Boolean) { autoPip = value; sp.edit().putBoolean("auto_pip", value).apply() }
    fun putSeek(value: Int) { seekSeconds = value; sp.edit().putInt("seek_seconds", value).apply() }
    fun putHold(value: Float) { holdSpeed = value; sp.edit().putFloat("hold_speed", value).apply() }
    fun putSubScale(value: Float) { subtitleScale = value.coerceIn(0.5f, 2.5f); sp.edit().putFloat("sub_scale", subtitleScale).apply() }
    fun putSubtitleLanguage(value: String) { subtitleLanguage = value; sp.edit().putString("sub_language", value).apply() }

    // ---- where each video stopped, and the watch history (newest first, 60 entries at most)

    private const val SEP = "\u001F"

    fun history(): List<HistoryEntry> =
        sp.getString("history", "").orEmpty().split("\n").mapNotNull { line ->
            val p = line.split(SEP)
            if (p.size < 5) null else HistoryEntry(p[0], p[1], p[2].toLongOrNull() ?: 0L, p[3].toLongOrNull() ?: 0L, p[4].toLongOrNull() ?: 0L)
        }

    fun progress(source: String): HistoryEntry? = history().firstOrNull { it.source == source }

    fun saveProgress(source: String, title: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        val entry = HistoryEntry(source, title.replace("\n", " ").replace(SEP, " "), positionMs, durationMs, System.currentTimeMillis())
        val list = (listOf(entry) + history().filter { it.source != source }).take(60)
        sp.edit().putString(
            "history",
            list.joinToString("\n") { listOf(it.source, it.title, it.positionMs, it.durationMs, it.time).joinToString(SEP) },
        ).apply()
        historyVersion++
    }

    // ---- favourites (by file path)

    var favoritesVersion by mutableStateOf(0)
        private set

    fun favorites(): Set<String> = sp.getStringSet("favorites", emptySet()).orEmpty()

    fun isFavorite(path: String): Boolean = path in favorites()

    fun toggleFavorite(path: String) {
        val current = favorites().toMutableSet()
        if (!current.add(path)) current.remove(path)
        sp.edit().putStringSet("favorites", current).apply()
        favoritesVersion++
    }

    fun clearHistory() {
        sp.edit().remove("history").apply()
        historyVersion++
    }
}
