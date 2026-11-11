package com.abonoura.aura

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Device-local appearance preferences, observable by Compose and applied to new player sessions. */
object SubtitleAppearanceStore {
    private var preferences: SharedPreferences? = null

    var value by mutableStateOf(SubtitleAppearance())
        private set

    fun init(context: Context) {
        if (preferences != null) return
        val store = context.applicationContext.getSharedPreferences("subtitle_appearance", Context.MODE_PRIVATE)
        preferences = store
        value = SubtitleAppearance.fromSettings(store.all)
    }

    fun update(appearance: SubtitleAppearance) {
        val normalized = appearance.normalized()
        value = normalized
        preferences?.edit()?.apply {
            normalized.toSettings().forEach { (key, setting) -> putString(key, setting) }
        }?.apply()
    }

    fun reset() = update(SubtitleAppearance())
}
