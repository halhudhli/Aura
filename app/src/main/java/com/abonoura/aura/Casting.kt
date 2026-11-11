package com.abonoura.aura

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.net.*
import java.util.UUID
import java.util.concurrent.Executors
import javax.xml.parsers.DocumentBuilderFactory

data class CastDevice(val name:String,val controlUrl:String,val serviceType:String)

object DlnaCast {
    var active:CastDevice?=null;private set
    private var local:LocalCastServer?=null
    suspend fun discover(context:Context):List<CastDevice> = withContext(Dispatchers.IO) {
        val wifi=context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock=wifi.createMulticastLock("Aura discovery").apply{setReferenceCounted(false);acquire()}
        val locations=linkedSetOf<String>()
        try {DatagramSocket().use { socket ->
            socket.soTimeout=650
            val request="M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n".toByteArray()
            repeat(2){socket.send(DatagramPacket(request,request.size,InetAddress.getByName("239.255.255.250"),1900))}
            val deadline=System.currentTimeMillis()+4200
            while(System.currentTimeMillis()<deadline) {
                currentCoroutineContext().ensureActive()
                try {val packet=DatagramPacket(ByteArray(8192),8192);socket.receive(packet)
                    val text=String(packet.data,0,packet.length)
                    text.lineSequence().firstOrNull{it.startsWith("location:",true)}?.substringAfter(':')?.trim()?.let{if(locations.size<40)locations+=it}
                }catch(_:SocketTimeoutException){}
            }
        }
            locations.mapNotNull { location ->currentCoroutineContext().ensureActive();runCatching{describe(location)}.getOrNull() }.distinctBy{it.controlUrl}
        }finally{lock.release()}
    }
    internal fun xml(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
    private fun describe(location:String):CastDevice? {
        val url=URL(location);require(url.protocol in setOf("http","https"))
        val c=url.openConnection() as HttpURLConnection
        val bytes=try{c.connectTimeout=3000;c.readTimeout=3000;c.inputStream.use{readLimited(it,512*1024)}}finally{c.disconnect()}
        val factory=DocumentBuilderFactory.newInstance()
        runCatching{factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)}
        runCatching{factory.setFeature("http://xml.org/sax/features/external-general-entities",false)}
        runCatching{factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false)}
        val builder=factory.newDocumentBuilder().apply{setEntityResolver{_,_->InputSource(StringReader(""))}}
        val document=builder.parse(bytes.inputStream())
        val name=document.getElementsByTagName("friendlyName").item(0)?.textContent ?: "شاشة DLNA"
        val services=document.getElementsByTagName("service")
        for(i in 0 until services.length) {
            val service=services.item(i) as? Element ?: continue
            val type=service.getElementsByTagName("serviceType").item(0)?.textContent.orEmpty()
            if(type.matches(Regex("urn:schemas-upnp-org:service:AVTransport:[0-9]+"))) {
                val control=service.getElementsByTagName("controlURL").item(0)?.textContent ?: continue
                val base=document.getElementsByTagName("URLBase").item(0)?.textContent?.takeIf{it.isNotBlank()} ?: location
                return CastDevice(name,URI(base).resolve(control).toString(),type)
            }
        }
        return null
    }
    suspend fun play(context:Context,device:CastDevice,request:PlayRequest) = withContext(Dispatchers.IO) {
        local?.close();local=null
        val source=if(request.source.startsWith("http://")||request.source.startsWith("https://")) {
            require(request.headers.isEmpty()){"هذا المصدر يحتاج ترويسات خاصة لا تقبلها الشاشة عبر DLNA"};request.source
        }else {
            val connectivity=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network=connectivity.activeNetwork ?: error("اتصل بالشبكة المحلية")
            val caps=connectivity.getNetworkCapabilities(network)
            require(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true||caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)==true){"اتصل بالشبكة المحلية نفسها مع الشاشة"}
            val address=connectivity.getLinkProperties(network)?.linkAddresses?.map{it.address}?.firstOrNull{it is Inet4Address && !it.isLoopbackAddress} ?: error("عنوان الشبكة غير متاح")
            LocalCastServer(context.applicationContext,request.source,InetAddress.getByName(URL(device.controlUrl).host)).also{local=it}.url(address.hostAddress.orEmpty())
        }
        val mime=java.net.URLConnection.guessContentTypeFromName(request.source) ?: "video/mp4"
        val metadata="<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"0\" restricted=\"1\"><dc:title>${xml(request.title)}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo=\"http-get:*:$mime:*\">${xml(source)}</res></item></DIDL-Lite>"
        try {soap(device,"SetAVTransportURI",mapOf("InstanceID" to "0","CurrentURI" to source,"CurrentURIMetaData" to metadata));soap(device,"Play",mapOf("InstanceID" to "0","Speed" to "1"));active=device}
        catch(e:Exception){local?.close();local=null;throw e}
    }
    suspend fun control(device:CastDevice,action:String)=withContext(Dispatchers.IO) {
        require(action in setOf("Play","Pause","Stop"))
        soap(device,action,if(action=="Play")mapOf("InstanceID" to "0","Speed" to "1")else mapOf("InstanceID" to "0"))
        if(action=="Stop"){local?.close();local=null;active=null}
    }
    private fun soap(device:CastDevice,action:String,args:Map<String,String>) {
        val c=URL(device.controlUrl).openConnection() as HttpURLConnection
        try {c.requestMethod="POST";c.connectTimeout=5000;c.readTimeout=10000;c.doOutput=true;c.setRequestProperty("Content-Type","text/xml; charset=\"utf-8\"");c.setRequestProperty("SOAPAction","\"${device.serviceType}#$action\"")
            val body="<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"${device.serviceType}\">"+args.entries.joinToString(""){(key,value)->"<$key>${xml(value)}</$key>"}+"</u:$action></s:Body></s:Envelope>"
            c.outputStream.use{it.write(body.toByteArray())};require(c.responseCode in 200..299){"رفضت الشاشة أمر $action (${c.responseCode})؛ قد تكون الصيغة غير مدعومة"}
        }finally{c.disconnect()}
    }
}

/** Only the selected receiver and a random URL can read the selected file. */
private class LocalCastServer(private val context:Context,private val source:String,private val receiver:InetAddress) {
    private val socket=ServerSocket(0)
    private val token=UUID.randomUUID().toString()
    private val workers=Executors.newFixedThreadPool(2){Thread(it,"AuraCastFile").apply{isDaemon=true}}
    init {Thread({while(!socket.isClosed)runCatching{val client=socket.accept();workers.execute{serve(client)}}},"AuraCast").apply{isDaemon=true;start()}}
    fun url(host:String)="http://$host:${socket.localPort}/$token"
    fun close(){runCatching{socket.close()};workers.shutdownNow()}
    private fun open(offset:Long):Pair<InputStream,Long> {
        if(source.startsWith("aura-network://"))return NetworkSources.open(context,source,offset).let{it.stream to it.length}
        val uri=Uri.parse(source)
        val length=if(uri.scheme=="content")context.contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use{if(it.moveToFirst()&&!it.isNull(0))it.getLong(0)else -1} ?: -1 else File(if(uri.scheme=="file")uri.path.orEmpty()else source).length()
        require(length>=0){"حجم الملف غير معروف؛ لا يمكن بثه مباشرة إلى الشاشة"}
        val input=if(uri.scheme=="content"||uri.scheme=="file")context.contentResolver.openInputStream(uri) ?: error("تعذّر قراءة الملف")else File(source).inputStream()
        try {var remaining=offset;while(remaining>0){val skipped=input.skip(remaining);if(skipped<=0){if(input.read()<0)break;remaining--}else remaining-=skipped};return input to length}catch(e:Exception){input.close();throw e}
    }
    private fun serve(client:Socket) {client.use{c->runCatching {
        c.soTimeout=10000
        if(c.inetAddress!=receiver)return
        val reader=c.getInputStream().bufferedReader();val first=reader.readLine()?.split(' ') ?: return
        if(first.getOrNull(1)!="/$token"||first[0] !in setOf("GET","HEAD"))return
        var range:String?=null;var count=0
        while(count++<64){val line=reader.readLine() ?: break;if(line.isEmpty())break;if(line.startsWith("Range:",true))range=line.substringAfter(':').trim()}
        val initial=HttpRanges.initialOffset(range)
        var opened=open(initial)
        val resolved=HttpRanges.resolve(range,opened.second)
        if(resolved==null){opened.first.close();c.getOutputStream().write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${opened.second}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray());return}
        if(resolved.start!=initial){opened.first.close();opened=open(resolved.start)}
        val (input,size)=opened;val offset=resolved.start
        input.use{stream->
            val end=resolved.end
            val length=resolved.length
            val output=c.getOutputStream();val type=java.net.URLConnection.guessContentTypeFromName(source) ?: "video/mp4"
            output.write((if(!resolved.partial)"HTTP/1.1 200 OK\r\n"else"HTTP/1.1 206 Partial Content\r\n").toByteArray())
            output.write("Content-Type: $type\r\nContent-Length: $length\r\nAccept-Ranges: bytes\r\nConnection: close\r\n".toByteArray())
            if(resolved.partial)output.write("Content-Range: bytes $offset-$end/$size\r\n".toByteArray())
            output.write("\r\n".toByteArray())
            if(first[0]=="GET"){val buffer=ByteArray(65536);var left=length;while(left>0){val n=stream.read(buffer,0,minOf(left,buffer.size.toLong()).toInt());if(n<0)break;output.write(buffer,0,n);left-=n}}
        }
    }}}
}
