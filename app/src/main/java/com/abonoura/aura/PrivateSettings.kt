package com.abonoura.aura

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keys/passwords stay in app storage, encrypted by a non-exportable Android Keystore key. */
object PrivateSettings {
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey("aura-private-v1",null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder("aura-private-v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun put(context:Context,name:String,value:String) {
        val prefs=context.getSharedPreferences("private_settings",Context.MODE_PRIVATE)
        if(value.isBlank()){prefs.edit().remove(name).apply();return}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key())}
        val encrypted=cipher.doFinal(value.toByteArray())
        prefs.edit().putString(name,Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(encrypted,Base64.NO_WRAP)).apply()
    }
    @Synchronized fun get(context:Context,name:String):String=runCatching {
        val value=context.getSharedPreferences("private_settings",Context.MODE_PRIVATE).getString(name,null) ?: return ""
        val parts=value.split(':');require(parts.size==2)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))}
        String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8)
    }.getOrDefault("")
}
