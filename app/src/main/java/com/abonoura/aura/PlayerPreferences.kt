package com.abonoura.aura

import android.content.Context

object PlayerPreferences {
    fun effects(context:Context):PlayerEffects {
        val p=context.getSharedPreferences("player_tools",Context.MODE_PRIVATE)
        return PlayerEffects(p.getInt("brightness",0),p.getInt("contrast",0),p.getInt("saturation",0),p.getInt("gamma",0),AudioMode.entries.firstOrNull{it.name==p.getString("audio","")} ?: AudioMode.Normal).normalized()
    }
    fun save(context:Context,e:PlayerEffects) {context.getSharedPreferences("player_tools",Context.MODE_PRIVATE).edit().putInt("brightness",e.brightness).putInt("contrast",e.contrast).putInt("saturation",e.saturation).putInt("gamma",e.gamma).putString("audio",e.audioMode.name).apply()}
}
