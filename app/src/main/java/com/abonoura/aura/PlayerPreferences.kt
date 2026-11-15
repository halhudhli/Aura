package com.abonoura.aura

import android.content.Context

object PlayerPreferences {
    fun effects(context:Context):PlayerEffects {
        val p=context.getSharedPreferences("player_tools",Context.MODE_PRIVATE)
        return PlayerEffects(p.getInt("brightness",0),p.getInt("contrast",0),p.getInt("saturation",0),p.getInt("gamma",0),AudioMode.entries.firstOrNull{it.name==p.getString("audio","")} ?: AudioMode.Normal,
            upscale=p.getInt("upscale",0),smoothMotion=p.getBoolean("smooth",false),deband=p.getBoolean("deband",false),
            matchRefresh=p.getBoolean("match_refresh",true),spatial=p.getBoolean("spatial",false),levelling=p.getBoolean("levelling",false)).normalized()
    }
    fun save(context:Context,e:PlayerEffects) {context.getSharedPreferences("player_tools",Context.MODE_PRIVATE).edit().putInt("brightness",e.brightness).putInt("contrast",e.contrast).putInt("saturation",e.saturation).putInt("gamma",e.gamma).putString("audio",e.audioMode.name)
        .putInt("upscale",e.upscale).putBoolean("smooth",e.smoothMotion).putBoolean("deband",e.deband)
        .putBoolean("match_refresh",e.matchRefresh).putBoolean("spatial",e.spatial).putBoolean("levelling",e.levelling).apply()}

    /** Audio advance (ms) remembered per headset; "" is the device speaker. */
    fun audioDelay(context:Context,device:String):Int=context.getSharedPreferences("player_tools",Context.MODE_PRIVATE).getInt("delay_"+device,0)
    fun saveAudioDelay(context:Context,device:String,ms:Int){context.getSharedPreferences("player_tools",Context.MODE_PRIVATE).edit().putInt("delay_"+device,ms).apply()}
}
