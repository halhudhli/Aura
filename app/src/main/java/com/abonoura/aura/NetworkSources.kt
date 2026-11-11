package com.abonoura.aura

import android.content.Context
import android.net.Uri
import com.jcraft.jsch.*
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.UUID

enum class NetworkProtocol { SMB,SFTP,FTP,FTPS }
data class NetworkSource(val id:String=UUID.randomUUID().toString(),val name:String,val protocol:NetworkProtocol,val host:String,val port:Int,val root:String="/",val user:String="",val password:String="",val domain:String="")
data class NetworkFile(val name:String,val path:String,val directory:Boolean,val size:Long)
class UnknownServerKey(val fingerprint:String):Exception("بصمة خادم SFTP: $fingerprint")

object NetworkSources {
    fun all(context:Context):List<NetworkSource> = runCatching {
        val json=JSONArray(PrivateSettings.get(context,"network_sources").ifBlank{"[]"})
        (0 until json.length()).map{json.getJSONObject(it)}.map{NetworkSource(it.getString("id"),it.getString("name"),NetworkProtocol.valueOf(it.getString("protocol")),it.getString("host"),it.getInt("port"),it.getString("root"),it.optString("user"),it.optString("password"),it.optString("domain"))}
    }.getOrDefault(emptyList())
    fun save(context:Context,source:NetworkSource) {
        require(source.host.matches(Regex("[A-Za-z0-9.:-]{1,253}")) && source.port in 1..65535){"اسم الخادم أو المنفذ غير صالح"}
        val items=(listOf(source)+all(context).filter{it.id!=source.id}).take(30);write(context,items)
    }
    fun remove(context:Context,id:String)=write(context,all(context).filter{it.id!=id})
    private fun write(context:Context,items:List<NetworkSource>) {
        val json=JSONArray();items.forEach{json.put(JSONObject().put("id",it.id).put("name",it.name).put("protocol",it.protocol.name).put("host",it.host).put("port",it.port).put("root",it.root).put("user",it.user).put("password",it.password).put("domain",it.domain))}
        PrivateSettings.put(context,"network_sources",json.toString())
    }
    fun uri(source:NetworkSource,path:String)=Uri.Builder().scheme("aura-network").authority(source.id).path(path).build().toString()
    fun resolve(context:Context,uri:String):Pair<NetworkSource,String> {
        val value=Uri.parse(uri);val source=all(context).firstOrNull{it.id==value.host} ?: error("مصدر الشبكة غير موجود")
        return source to (value.path ?: source.root)
    }
    suspend fun list(context:Context,source:NetworkSource,path:String):List<NetworkFile> = withContext(Dispatchers.IO) {
        when(source.protocol) {
            NetworkProtocol.SMB -> smb(source,path.trimEnd('/')+"/").use{directory->directory.listFiles().map{file->file.use{NetworkFile(it.name.trimEnd('/'),path.trimEnd('/')+"/"+it.name.trimEnd('/'),it.isDirectory,it.length())}}}
            NetworkProtocol.SFTP -> sftp(context,source).let{(session,channel)->try {
                @Suppress("UNCHECKED_CAST") val files=channel.ls(path) as java.util.Vector<ChannelSftp.LsEntry>
                files.filter{it.filename!="."&&it.filename!=".."}.map{NetworkFile(it.filename,path.trimEnd('/')+"/"+it.filename,it.attrs.isDir,it.attrs.size)}
            }finally{channel.disconnect();session.disconnect()}}
            else -> ftp(source).let{client->try {client.listFiles(path).filter{it.name!="."&&it.name!=".."}.map{NetworkFile(it.name,path.trimEnd('/')+"/"+it.name,it.isDirectory,it.size)}}finally{runCatching{client.logout()};client.disconnect()}}
        }.sortedWith(compareByDescending<NetworkFile>{it.directory}.thenBy{it.name.lowercase()})
    }
    data class Opened(val stream:InputStream,val length:Long)
    fun open(context:Context,uri:String,offset:Long):Opened {
        val (source,path)=resolve(context,uri)
        return when(source.protocol) {
            NetworkProtocol.SMB -> {
                val file=smb(source,path)
                try {
                    val size=file.length();val reader=SmbRandomAccessFile(file,"r");reader.seek(offset)
                    Opened(object:InputStream(){override fun read():Int=reader.read();override fun read(b:ByteArray,off:Int,len:Int)=reader.read(b,off,len);override fun close(){try{reader.close()}finally{file.close()}}},size)
                }catch(e:Exception){file.close();throw e}
            }
            NetworkProtocol.SFTP -> {
                val (session,channel)=sftp(context,source)
                try {val size=channel.stat(path).size;val input=channel.get(path,null,offset)
                    Opened(object:FilterInputStream(input){override fun close(){try{super.close()}finally{channel.disconnect();session.disconnect()}}},size)
                }catch(e:Exception){channel.disconnect();session.disconnect();throw e}
            }
            else -> {
                val client=ftp(source)
                try {val size=client.mlistFile(path)?.size ?: client.listFiles(path).firstOrNull()?.size ?: error("الملف غير موجود")
                    client.restartOffset=offset
                    val input=client.retrieveFileStream(path) ?: error("تعذّر فتح الملف من الخادم")
                    Opened(object:FilterInputStream(input){override fun close(){try{super.close()}finally{runCatching{client.completePendingCommand()};runCatching{client.logout()};client.disconnect()}}},size)
                }catch(e:Exception){client.disconnect();throw e}
            }
        }
    }
    private fun smb(source:NetworkSource,path:String):SmbFile {
        val props=Properties().apply{setProperty("jcifs.smb.client.minVersion","SMB202");setProperty("jcifs.smb.client.maxVersion","SMB311");setProperty("jcifs.smb.client.responseTimeout","15000");setProperty("jcifs.smb.client.connTimeout","10000")}
        val context=BaseContext(PropertyConfiguration(props)).withCredentials(NtlmPasswordAuthenticator(source.domain,source.user,source.password))
        val host=if(source.host.contains(':'))"[${source.host}]"else source.host
        val url=Uri.Builder().scheme("smb").encodedAuthority("$host:${source.port}").path(path).build().toString()
        return SmbFile(url,context)
    }
    private fun ftp(source:NetworkSource):FTPClient {
        val client=if(source.protocol==NetworkProtocol.FTPS)FTPSClient(false).apply{
            isEndpointCheckingEnabled=true
            val factory=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as java.security.KeyStore?)
            trustManager=factory.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().first()
        }else FTPClient()
        try {
            client.connectTimeout=10000;client.defaultTimeout=15000;client.setDataTimeout(java.time.Duration.ofSeconds(20))
            client.connect(source.host,source.port)
            require(client.login(source.user.ifBlank{"anonymous"},source.password)){"رفض الخادم بيانات الدخول"}
            if(client is FTPSClient){client.execPBSZ(0);client.execPROT("P")}
            client.enterLocalPassiveMode();client.setFileType(FTP.BINARY_FILE_TYPE)
            return client
        }catch(e:Exception){runCatching{client.disconnect()};throw e}
    }
    fun trust(context:Context,source:NetworkSource,fingerprint:String)=PrivateSettings.put(context,"ssh:${source.host}:${source.port}",fingerprint)
    private fun sftp(context:Context,source:NetworkSource):Pair<Session,ChannelSftp> {
        val jsch=JSch();var observed=""
        jsch.setHostKeyRepository(object:HostKeyRepository {
            override fun check(host:String,key:ByteArray):Int {
                observed="SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
                val trusted=PrivateSettings.get(context,"ssh:${source.host}:${source.port}")
                return if(trusted==observed)HostKeyRepository.OK else if(trusted.isBlank())HostKeyRepository.NOT_INCLUDED else HostKeyRepository.CHANGED
            }
            override fun add(hostkey:HostKey?,ui:UserInfo?)=Unit
            override fun remove(host:String?,type:String?)=Unit
            override fun remove(host:String?,type:String?,key:ByteArray?)=Unit
            override fun getKnownHostsRepositoryID()="Aura"
            override fun getHostKey():Array<HostKey> = emptyArray()
            override fun getHostKey(host:String?,type:String?):Array<HostKey> = emptyArray()
        })
        val session=jsch.getSession(source.user,source.host,source.port)
        session.setPassword(source.password);session.setConfig("StrictHostKeyChecking","yes");session.timeout=20000
        try {
            session.connect(15000);val channel=session.openChannel("sftp") as ChannelSftp;channel.connect(10000);return session to channel
        }catch(e:Exception){session.disconnect();if(observed.isNotBlank() && observed!=PrivateSettings.get(context,"ssh:${source.host}:${source.port}"))throw UnknownServerKey(observed);throw e}
    }
}
