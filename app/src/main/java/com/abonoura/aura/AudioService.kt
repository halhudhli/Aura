@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.abonoura.aura

import android.content.ComponentName
import android.content.Context
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import org.json.JSONArray
import org.json.JSONObject
import com.google.common.util.concurrent.Futures

/** Owns audio independently of the Activity; Media3 supplies notification and headset controls. */
class AudioService : MediaSessionService() {
    private var session: MediaSession? = null
    private var equalizer: Equalizer? = null
    private val checkpoint=Handler(Looper.getMainLooper())
    private val savePosition=object:Runnable {override fun run(){session?.player?.let{player->
        getSharedPreferences("audio_queue",MODE_PRIVATE).edit().putLong("position",player.currentPosition).putInt("index",player.currentMediaItemIndex.coerceAtLeast(0)).apply()
    };checkpoint.postDelayed(this,10000)}}
    override fun onCreate() {
        super.onCreate()
        val dataSource=ResolvingDataSource.Factory(DefaultDataSource.Factory(this)){spec->
            AudioHeaders.values[spec.uri.toString()]?.let{spec.withAdditionalHeaders(it)} ?: spec
        }
        val player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(dataSource)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
            addListener(object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    equalizer?.release()
                    equalizer = runCatching { Equalizer(0, audioSessionId).apply { enabled = true } }.getOrNull()
                    equalizer?.let{eq->val prefs=getSharedPreferences("audio_equalizer",MODE_PRIVATE);runCatching{for(b in 0 until eq.numberOfBands){val band=b.toShort();val frequency=eq.getCenterFreq(band);if(prefs.contains("freq:$frequency"))eq.setBandLevel(band,prefs.getInt("freq:$frequency",0).coerceIn(eq.bandLevelRange[0].toInt(),eq.bandLevelRange[1].toInt()).toShort())}}}
                    AudioPlayback.equalizer = equalizer
                }
                override fun onEvents(player: Player, events: Player.Events) { persist(player) }
            })
        }
        val activity=android.app.PendingIntent.getActivity(this,0,android.content.Intent(this,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).setCallback(object:MediaSession.Callback {
            override fun onConnect(session:MediaSession,controller:MediaSession.ControllerInfo):MediaSession.ConnectionResult {
                if(controller.packageName!=packageName && !controller.isTrusted)return MediaSession.ConnectionResult.reject()
                return super.onConnect(session,controller)
            }
            override fun onAddMediaItems(session:MediaSession,controller:MediaSession.ControllerInfo,items:List<MediaItem>):com.google.common.util.concurrent.ListenableFuture<List<MediaItem>> {
                return Futures.immediateFuture(items.map{item->
                    val source=item.mediaId.ifBlank{item.requestMetadata.mediaUri?.toString().orEmpty()}
                    require(MediaTypes.playableScheme(source)||source.startsWith('/')){"مصدر الصوت غير صالح"}
                    val headers=runCatching{JSONObject(item.requestMetadata.extras?.getString("headers").orEmpty().ifBlank{"{}"})}.getOrDefault(JSONObject())
                    audioItem(this@AudioService,source,item.mediaMetadata.title?.toString().orEmpty(),headers.keys().asSequence().associateWith{headers.getString(it)})
                })
            }
        }).build()
        restore(player)
        checkpoint.post(savePosition)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        if (session?.player?.playWhenReady != true) stopSelf()
    }
    private fun persist(player: Player) {
        val items = JSONArray()
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            items.put(JSONObject().put("uri", item.mediaId).put("title", item.mediaMetadata.title.toString()).put("headers",item.requestMetadata.extras?.getString("headers").orEmpty()))
        }
        getSharedPreferences("audio_queue", MODE_PRIVATE).edit().putString("items",items.toString())
            .putInt("index",player.currentMediaItemIndex.coerceAtLeast(0)).putLong("position",player.currentPosition)
            .putInt("repeat",player.repeatMode).putBoolean("shuffle",player.shuffleModeEnabled).apply()
    }
    private fun restore(player: Player) = runCatching {
        val prefs=getSharedPreferences("audio_queue",MODE_PRIVATE)
        val array=JSONArray(prefs.getString("items","[]"))
        val items=(0 until array.length()).map { array.getJSONObject(it) }.map {
            val headers=runCatching{JSONObject(it.optString("headers").ifBlank{"{}"})}.getOrDefault(JSONObject())
            audioItem(this,it.getString("uri"),it.optString("title"),headers.keys().asSequence().associateWith{key->headers.getString(key)})
        }
        if(items.isNotEmpty()) {
            player.setMediaItems(items,prefs.getInt("index",0).coerceIn(items.indices),prefs.getLong("position",0).coerceAtLeast(0))
            player.repeatMode=prefs.getInt("repeat",Player.REPEAT_MODE_OFF)
            player.shuffleModeEnabled=prefs.getBoolean("shuffle",false)
            player.prepare() // Restoration never starts sound without a user action.
        }
    }.let { Unit }
    override fun onDestroy() {
        checkpoint.removeCallbacks(savePosition)
        session?.let { persist(it.player);it.player.release();it.release() }
        equalizer?.release();AudioPlayback.equalizer=null;session=null
        super.onDestroy()
    }
}

private object AudioHeaders {val values=java.util.concurrent.ConcurrentHashMap<String,Map<String,String>>()}
private fun audioItem(context:Context,source:String,title:String,headers:Map<String,String> = emptyMap()):MediaItem {
    val uri=if(source.startsWith("aura-network://"))NetworkRelay.url(context,source)else source
    if(AudioHeaders.values.size>5000)AudioHeaders.values.clear()
    if(headers.isNotEmpty())AudioHeaders.values[uri]=headers
    val extras=android.os.Bundle().apply{putString("headers",JSONObject(headers).toString())}
    return MediaItem.Builder().setUri(uri).setMediaId(source)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setExtras(extras).build())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setIsPlayable(true).build()).build()
}

object AudioPlayback {
    var controller by mutableStateOf<MediaController?>(null); private set
    var title by mutableStateOf(""); private set
    var playing by mutableStateOf(false); private set
    var position by mutableStateOf(0L); private set
    var duration by mutableStateOf(0L); private set
    var queue by mutableStateOf<List<Pair<String,String>>>(emptyList()); private set
    var error by mutableStateOf<String?>(null); private set
    var sleepUntil by mutableStateOf(0L); private set
    internal var equalizer: Equalizer? = null
    private var connecting=false
    private var pending:(() -> Unit)?=null
    private val handler=Handler(Looper.getMainLooper())
    private val tick=object:Runnable {
        override fun run() {
            controller?.let { p ->
                title=p.mediaMetadata.title?.toString().orEmpty();playing=p.isPlaying
                position=p.currentPosition.coerceAtLeast(0);duration=p.duration.coerceAtLeast(0)
                if(sleepUntil>0 && System.currentTimeMillis()>=sleepUntil) { p.pause();sleepUntil=0 }
            }
            handler.postDelayed(this,750)
        }
    }
    fun connect(context:Context) {
        if(controller!=null || connecting)return
        connecting=true
        val future=MediaController.Builder(context.applicationContext,SessionToken(context,ComponentName(context,AudioService::class.java))).buildAsync()
        future.addListener({
            connecting=false
            runCatching { future.get() }.onSuccess { p ->
                controller=p
                queue=(0 until p.mediaItemCount).map{i->val m=p.getMediaItemAt(i);m.mediaId to m.mediaMetadata.title.toString()}
                p.addListener(object:Player.Listener {
                    override fun onEvents(player:Player,events:Player.Events) {
                        queue=(0 until player.mediaItemCount).map { i -> val m=player.getMediaItemAt(i);m.mediaId to m.mediaMetadata.title.toString() }
                        error=player.playerError?.localizedMessage
                    }
                })
                handler.removeCallbacks(tick);handler.post(tick)
                pending?.invoke();pending=null
            }.onFailure { error=it.localizedMessage }
        },ContextCompat.getMainExecutor(context))
    }
    fun play(context:Context,items:List<PlayRequest>,index:Int=0) {
        if(items.isEmpty())return
        val action={ controller?.apply {
            setMediaItems(items.map { audioItem(context,it.source,it.title,it.headers) },index.coerceIn(items.indices),items[index.coerceIn(items.indices)].startMs)
            prepare();play()
        };Unit }
        if(controller==null) { pending=action;connect(context) }else action()
    }
    fun setSleep(minutes:Int) { sleepUntil=if(minutes<=0)0 else System.currentTimeMillis()+minutes*60000L }
    fun setEqualizer(context:Context,band:Short,level:Short) {equalizer?.let{eq->runCatching{eq.setBandLevel(band,level);context.getSharedPreferences("audio_equalizer",Context.MODE_PRIVATE).edit().putInt("freq:${eq.getCenterFreq(band)}",level.toInt()).apply()}}}
    fun toggle() { controller?.let { if(it.playWhenReady)it.pause()else it.play() } }
}
