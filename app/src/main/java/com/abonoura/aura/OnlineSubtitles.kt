package com.abonoura.aura

import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SubtitleLanguage(val code: String, val label: String)

val subtitleLanguages = listOf(
    SubtitleLanguage("ar", "العربية"),
    SubtitleLanguage("en", "الإنجليزية"),
    SubtitleLanguage("fr", "الفرنسية"),
    SubtitleLanguage("es", "الإسبانية"),
    SubtitleLanguage("tr", "التركية"),
    SubtitleLanguage("ur", "الأردية"),
)

data class OnlineSubtitle(
    val fileId: Int,
    val fileName: String,
    val release: String,
    val language: String,
    val downloads: Int,
    val hearingImpaired: Boolean,
    val foreignPartsOnly: Boolean,
)

data class SubtitlePage(val subtitles: List<OnlineSubtitle>, val nextPage: Int?)

class SubtitleServiceException(message: String) : IOException(message)

/** Only the displayed title is used for search, never the video or its full device path. */
fun subtitleQuery(title: String): String = title
    .replace(Regex("\\.(mkv|mp4|avi|mov|webm|m4v|ts)$", RegexOption.IGNORE_CASE), "")
    .replace(Regex("[._]+"), " ")
    .replace(Regex("\\b(2160p|1080p|720p|480p|bluray|brrip|webrip|web-dl|hdtv|x264|x265|h264|h265|hevc)\\b.*", RegexOption.IGNORE_CASE), "")
    .replace(Regex("\\s+"), " ")
    .trim(' ', '-', '[', ']', '(', ')')

/** OpenSubtitles REST API. Searches and quota-consuming downloads happen only on user action. */
class OpenSubtitlesClient internal constructor(
    private val apiKey: String,
    private val userAgent: String,
    private val transport: SubtitleTransport,
) {
    constructor(apiKey: String, userAgent: String) : this(apiKey, userAgent, UrlSubtitleTransport())

    val configured: Boolean get() = apiKey.isNotBlank()

    suspend fun search(query: String, language: String, page: Int = 1): SubtitlePage = runInterruptible(Dispatchers.IO) {
        requireConfigured()
        require(query.isNotBlank() && page >= 1)
        require(subtitleLanguages.any { it.code == language })
        val params = sortedMapOf(
            "languages" to language,
            "order_by" to "download_count",
            "order_direction" to "desc",
            "page" to page.toString(),
            "query" to query.trim(),
        ).entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        val root = api("/subtitles?$params")
        val data = root.optJSONArray("data") ?: throw invalidResponse()
        val results = buildList {
            for (i in 0 until data.length()) {
                val attributes = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                val files = attributes.optJSONArray("files") ?: continue
                for (j in 0 until files.length()) {
                    val file = files.optJSONObject(j) ?: continue
                    val id = file.optInt("file_id")
                    if (id <= 0) continue
                    val name = file.optString("file_name").ifBlank { "ترجمة $id" }
                    add(OnlineSubtitle(
                        fileId = id,
                        fileName = name,
                        release = attributes.optString("release").ifBlank { name },
                        language = attributes.optString("language").ifBlank { language },
                        downloads = attributes.optInt("download_count").coerceAtLeast(0),
                        hearingImpaired = attributes.optBoolean("hearing_impaired"),
                        foreignPartsOnly = attributes.optBoolean("foreign_parts_only"),
                    ))
                }
            }
        }.distinctBy { it.fileId }
        SubtitlePage(results, (page + 1).takeIf { results.isNotEmpty() && page < root.optInt("total_pages", 1) })
    }

    suspend fun download(subtitle: OnlineSubtitle, directory: File): File = runInterruptible(Dispatchers.IO) {
        requireConfigured()
        require(subtitle.fileId > 0)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create subtitle directory")
        // IDs, not server-provided filenames, determine local paths. Re-selecting a cached file uses no quota.
        val target = File(directory, "opensubtitles-${subtitle.fileId}.srt")
        if (target.isFile && target.length() in 1..MAX_SUBTITLE_BYTES.toLong()) {
            if (validSrt(SubtitleText.decode(target.readBytes()))) return@runInterruptible target
        }
        val result = api("/download", JSONObject().put("file_id", subtitle.fileId).put("sub_format", "srt"))
        val link = result.optString("link")
        httpsUrl(link)
        // Deliberately do not forward Api-Key to the file server or any redirects.
        val response = transport.execute(SubtitleRequest(
            url = link,
            headers = mapOf("User-Agent" to userAgent),
            maxBytes = MAX_SUBTITLE_BYTES,
            followRedirects = true,
        ))
        checkStatus(response.status)
        if (response.body.size > MAX_SUBTITLE_BYTES) throw invalidResponse()
        val text = SubtitleText.decode(response.body)
        if (!validSrt(text)) throw SubtitleServiceException("الملف المستلم ليس ترجمة صالحة. جرّب نتيجة أخرى.")
        interrupted()
        val temporary = File.createTempFile("subtitle-", ".tmp", directory)
        try {
            temporary.writeText(text, Charsets.UTF_8)
            interrupted()
            if (!temporary.renameTo(target)) throw IOException("Cannot save subtitle")
        } finally {
            temporary.delete()
        }
        target
    }

    private fun api(path: String, body: JSONObject? = null): JSONObject {
        val response = transport.execute(SubtitleRequest(
            url = "https://api.opensubtitles.com/api/v1$path",
            headers = buildMap {
                put("Api-Key", apiKey)
                put("User-Agent", userAgent)
                put("Accept", "application/json")
                if (body != null) put("Content-Type", "application/json")
            },
            body = body?.toString()?.toByteArray(Charsets.UTF_8),
            maxBytes = MAX_JSON_BYTES,
        ))
        checkStatus(response.status)
        if (response.body.size > MAX_JSON_BYTES) throw invalidResponse()
        return try {
            JSONObject(String(response.body, Charsets.UTF_8))
        } catch (_: org.json.JSONException) {
            throw invalidResponse()
        }
    }

    private fun requireConfigured() {
        if (!configured) throw SubtitleServiceException("البحث عن الترجمة غير مفعّل في هذه النسخة بعد.")
    }

    private fun checkStatus(status: Int) {
        when (status) {
            in 200..299 -> Unit
            401, 403 -> throw SubtitleServiceException("تعذّر الوصول إلى خدمة الترجمة. حاول لاحقًا أو حدّث التطبيق.")
            406 -> throw SubtitleServiceException("بلغت حد التحميل اليومي لدى OpenSubtitles. جرّب لاحقًا.")
            429 -> throw SubtitleServiceException("طلبات كثيرة إلى خدمة الترجمة. انتظر قليلًا ثم أعد المحاولة.")
            else -> throw SubtitleServiceException("خدمة الترجمة غير متاحة الآن. حاول مرة أخرى لاحقًا.")
        }
    }
}

private const val MAX_JSON_BYTES = 2 * 1024 * 1024
internal const val MAX_SUBTITLE_BYTES = 8 * 1024 * 1024

private fun invalidResponse() = SubtitleServiceException("تعذّر قراءة استجابة خدمة الترجمة. حاول مرة أخرى.")

private fun validSrt(text: String): Boolean = Regex(
    "(?m)^\\s*\\d{1,3}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->\\s*\\d{1,3}:\\d{2}:\\d{2}[,.]\\d{3}"
).containsMatchIn(text)

private fun interrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
}

private fun httpsUrl(value: String): URL {
    val url = runCatching { URL(value) }.getOrElse { throw invalidResponse() }
    if (url.protocol != "https" || url.host.isBlank() || url.userInfo != null) throw invalidResponse()
    return url
}

internal data class SubtitleRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
    val maxBytes: Int,
    val followRedirects: Boolean = false,
)

internal data class SubtitleResponse(val status: Int, val body: ByteArray)

internal fun interface SubtitleTransport {
    fun execute(request: SubtitleRequest): SubtitleResponse
}

/** Bounded responses, HTTPS-only file redirects, finite timeouts and no automatic POST retries. */
private class UrlSubtitleTransport : SubtitleTransport {
    override fun execute(request: SubtitleRequest): SubtitleResponse {
        var url = httpsUrl(request.url)
        repeat(5) { redirect ->
            interrupted()
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = false
                request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                request.body?.let { body ->
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308) && request.followRedirects && redirect < 4) {
                    val location = connection.getHeaderField("Location") ?: throw invalidResponse()
                    url = httpsUrl(URL(url, location).toString())
                } else {
                    if (connection.contentLengthLong > request.maxBytes) throw invalidResponse()
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val bytes = stream?.use { readLimited(it, request.maxBytes) } ?: byteArrayOf()
                    return SubtitleResponse(status, bytes)
                }
            } finally {
                connection.disconnect()
            }
        }
        throw invalidResponse()
    }
}

internal fun readLimited(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        interrupted()
        val count = input.read(buffer)
        if (count < 0) break
        if (count > limit - output.size()) throw SubtitleServiceException("ملف الترجمة أكبر من الحجم المسموح.")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
