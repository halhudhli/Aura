package com.abonoura.aura

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SubtitleServiceException(message: String, val httpStatus: Int? = null) : IOException(message)

internal const val MAX_SUBTITLE_BYTES = 8 * 1024 * 1024
internal const val MAX_JSON_BYTES = 2 * 1024 * 1024

internal fun invalidSubtitleResponse() = SubtitleServiceException("تعذّر قراءة استجابة خدمة الترجمة. حاول مرة أخرى.")

internal fun checkSubtitleStatus(status: Int) {
    when (status) {
        in 200..299 -> Unit
        401, 403 -> throw SubtitleServiceException("تعذّر الوصول إلى مصدر الترجمة. تحقق من إعداد الإضافة أو حاول لاحقًا.", status)
        406 -> throw SubtitleServiceException("بلغت حد التحميل لدى مصدر الترجمة. جرّب لاحقًا.", status)
        429 -> throw SubtitleServiceException("طلبات كثيرة إلى مصدر الترجمة. انتظر قليلًا ثم أعد المحاولة.", status)
        else -> throw SubtitleServiceException("مصدر الترجمة غير متاح الآن. حاول مرة أخرى لاحقًا.", status)
    }
}

internal fun subtitleInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
}

internal fun subtitleHttpsUrl(value: String): URL {
    val url = runCatching { URL(value) }.getOrElse { throw invalidSubtitleResponse() }
    if (url.protocol != "https" || url.host.isBlank() || url.userInfo != null) throw invalidSubtitleResponse()
    return url
}

internal fun subtitlePathPart(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal data class SubtitleRequest(
    val url: String,
    val maxBytes: Int = MAX_JSON_BYTES,
    val followRedirects: Boolean = false,
)

internal data class SubtitleResponse(val status: Int, val body: ByteArray)

internal fun interface SubtitleTransport {
    fun execute(request: SubtitleRequest): SubtitleResponse
}

internal fun SubtitleTransport.json(url: String): JSONObject {
    val response = execute(SubtitleRequest(url, followRedirects = true))
    checkSubtitleStatus(response.status)
    if (response.body.size > MAX_JSON_BYTES) throw invalidSubtitleResponse()
    return try {
        JSONObject(String(response.body, Charsets.UTF_8))
    } catch (_: org.json.JSONException) {
        throw invalidSubtitleResponse()
    }
}

/** Manifest configuration stays in its original URL; it is never sent in headers to file servers. */
internal class UrlSubtitleTransport : SubtitleTransport {
    override fun execute(request: SubtitleRequest): SubtitleResponse {
        var url = subtitleHttpsUrl(request.url)
        repeat(5) { redirect ->
            subtitleInterrupted()
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = false
                // An honest non-Stremio UA avoids SubSense returning localhost streaming-server URLs.
                connection.setRequestProperty("User-Agent", "Aura/1.0")
                connection.setRequestProperty("Accept", if (request.maxBytes == MAX_JSON_BYTES) "application/json" else "*/*")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308) && request.followRedirects && redirect < 4) {
                    val location = connection.getHeaderField("Location") ?: throw invalidSubtitleResponse()
                    url = subtitleHttpsUrl(URL(url, location).toString())
                } else {
                    if (connection.contentLengthLong > request.maxBytes) throw invalidSubtitleResponse()
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    return SubtitleResponse(status, stream?.use { readLimited(it, request.maxBytes) } ?: byteArrayOf())
                }
            } finally {
                connection.disconnect()
            }
        }
        throw invalidSubtitleResponse()
    }
}

internal fun readLimited(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        subtitleInterrupted()
        val count = input.read(buffer)
        if (count < 0) break
        if (count > limit - output.size()) throw SubtitleServiceException("ملف الترجمة أكبر من الحجم المسموح.")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
