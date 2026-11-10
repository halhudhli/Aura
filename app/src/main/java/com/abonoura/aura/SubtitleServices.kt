package com.abonoura.aura

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.supervisorScope
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

data class SubtitleLanguage(val code: String, val label: String, val addonCodes: Set<String>)

val subtitleLanguages = listOf(
    SubtitleLanguage("ar", "العربية", setOf("ar", "ara", "arabic")),
    SubtitleLanguage("en", "الإنجليزية", setOf("en", "eng", "english")),
    SubtitleLanguage("fr", "الفرنسية", setOf("fr", "fra", "fre", "french")),
    SubtitleLanguage("es", "الإسبانية", setOf("es", "spa", "spanish")),
    SubtitleLanguage("tr", "التركية", setOf("tr", "tur", "turkish")),
    SubtitleLanguage("ur", "الأردية", setOf("ur", "urd", "urdu")),
)

enum class SubtitleMediaKind(val apiType: String, val label: String) { Movie("movie", "فيلم"), Series("series", "مسلسل") }
data class SubtitleMedia(val id: String, val name: String, val kind: SubtitleMediaKind, val year: String)
data class SubtitleVideoHint(val query: String, val kind: SubtitleMediaKind, val season: Int, val episode: Int)

data class OnlineSubtitle(
    val id: String,
    val release: String,
    val language: String,
    val source: String,
    val addonName: String,
    val downloadUrl: String,
)

data class SubtitleResults(val subtitles: List<OnlineSubtitle>, val warnings: List<String>)

/** Only the displayed title is used for search, never the video or its full device path. */
fun subtitleVideoHint(title: String): SubtitleVideoHint {
    val episode = Regex("(?i)\\bS(\\d{1,3})E(\\d{1,4})\\b|\\b(\\d{1,3})x(\\d{1,4})\\b").find(title.replace('_', ' ').replace('.', ' '))
    val clean = title
        .replace(Regex("\\.(mkv|mp4|avi|mov|webm|m4v|ts)$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[._]+"), " ")
        .replace(Regex("(?i)\\bS\\d{1,3}E\\d{1,4}\\b.*|\\b\\d{1,3}x\\d{1,4}\\b.*"), "")
        .replace(Regex("\\b(2160p|1080p|720p|480p|bluray|brrip|webrip|web-dl|hdtv|x264|x265|h264|h265|hevc)\\b.*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+[\\[(]?(19|20)\\d{2}[)\\]]?\\s*$"), "")
        .replace(Regex("\\s+"), " ").trim(' ', '-', '[', ']', '(', ')')
    return SubtitleVideoHint(
        clean,
        if (episode == null) SubtitleMediaKind.Movie else SubtitleMediaKind.Series,
        episode?.let { (it.groupValues[1].ifEmpty { it.groupValues[3] }).toInt() } ?: 1,
        episode?.let { (it.groupValues[2].ifEmpty { it.groupValues[4] }).toInt() } ?: 1,
    )
}

/** A configured Stremio subtitle addon, without exposing its private manifest in toString/logs. */
class SubtitleAddon private constructor(val id: String, val name: String, private val base: String) {
    override fun toString(): String = name

    internal fun searchUrl(media: SubtitleMedia, season: Int, episode: Int, filename: String): String {
        require(media.id.matches(Regex("tt\\d+")))
        require(season >= 0 && episode >= 1)
        val id = media.id + if (media.kind == SubtitleMediaKind.Series) ":$season:$episode" else ""
        return "$base/subtitles/${media.kind.apiType}/${subtitlePathPart(id)}/filename=${subtitlePathPart(filename)}.json"
    }

    companion object {
        fun fromManifest(id: String, name: String, manifest: String): SubtitleAddon? = runCatching {
            val value = manifest.trim().replace(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://").trimEnd('/')
            val url = subtitleHttpsUrl(value)
            require(url.path.endsWith("/manifest.json") && url.query == null && url.ref == null)
            SubtitleAddon(id, name, value.removeSuffix("/manifest.json"))
        }.getOrNull()
    }
}

class SubtitleSearchClient internal constructor(private val addons: List<SubtitleAddon>, private val transport: SubtitleTransport) {
    constructor(addons: List<SubtitleAddon>) : this(addons, UrlSubtitleTransport())
    val configured: Boolean get() = addons.isNotEmpty()
    val sourceNames: String get() = addons.joinToString(" + ") { it.name }

    /** Addons use IMDb IDs, so the user chooses the correct title/year before a subtitle search. */
    suspend fun titles(query: String, kind: SubtitleMediaKind): List<SubtitleMedia> = runInterruptible(Dispatchers.IO) {
        require(query.isNotBlank())
        val imdb = query.trim().takeIf { it.matches(Regex("tt\\d+")) }
        if (imdb != null) return@runInterruptible listOf(SubtitleMedia(imdb, imdb, kind, ""))
        val root = transport.json("https://v3-cinemeta.strem.io/catalog/${kind.apiType}/top/search=${subtitlePathPart(query.trim())}.json")
        val metas = root.optJSONArray("metas") ?: throw invalidSubtitleResponse()
        buildList {
            for (i in 0 until metas.length()) {
                val meta = metas.optJSONObject(i) ?: continue
                val id = meta.optString("imdb_id").ifBlank { meta.optString("id") }
                val name = meta.optString("name")
                if (id.matches(Regex("tt\\d+")) && name.isNotBlank()) {
                    add(SubtitleMedia(id, name, kind, meta.optString("releaseInfo").ifBlank { meta.optString("year") }))
                }
            }
        }.distinctBy { it.id }.take(40)
    }

    suspend fun subtitles(media: SubtitleMedia, language: SubtitleLanguage, season: Int, episode: Int, filename: String): SubtitleResults = supervisorScope {
        if (!configured) throw SubtitleServiceException("البحث عن الترجمة غير مفعّل في هذه النسخة بعد.")
        val responses = addons.map { addon ->
            async {
                try {
                    val items = runInterruptible(Dispatchers.IO) {
                        val root = transport.json(addon.searchUrl(media, season, episode, filename))
                        val subtitles = root.optJSONArray("subtitles") ?: throw invalidSubtitleResponse()
                        buildList {
                            for (i in 0 until subtitles.length()) {
                                val sub = subtitles.optJSONObject(i) ?: continue
                                val lang = sub.optString("lang").lowercase()
                                if (lang !in language.addonCodes) continue
                                val url = sub.optString("url").replace(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
                                if (runCatching { subtitleHttpsUrl(url) }.isFailure) continue
                                val label = sub.optString("releaseName").ifBlank { sub.optString("fileName") }.ifBlank { sub.optString("label") }.ifBlank { "ترجمة ${i + 1}" }
                                add(OnlineSubtitle(
                                    id = "${addon.id}:${media.id}:$season:$episode:${subtitleDigest(url)}",
                                    release = label,
                                    language = language.code,
                                    source = when (val source = sub.optString("source").ifBlank { addon.name }) {
                                        "subdl" -> "SubDL"
                                        "subsource" -> "SubSource"
                                        "opensubtitles" -> "OpenSubtitles"
                                        else -> source
                                    },
                                    addonName = addon.name,
                                    downloadUrl = url,
                                ))
                            }
                        }.distinctBy { it.downloadUrl }
                    }
                    SubtitleResults(items, emptyList())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A slow or broken provider must not discard working providers' results.
                    SubtitleResults(emptyList(), listOf("تعذّر الوصول إلى ${addon.name}. يمكنك إعادة البحث لاحقًا."))
                }
            }
        }.awaitAll()
        SubtitleResults(responses.flatMap { it.subtitles }.distinctBy { it.downloadUrl }, responses.flatMap { it.warnings })
    }

    suspend fun download(subtitle: OnlineSubtitle, directory: File): File = runInterruptible(Dispatchers.IO) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create subtitle directory")
        val name = "online-${subtitleDigest(subtitle.id)}"
        for (extension in listOf("srt", "vtt", "ass")) {
            val cached = File(directory, "$name.$extension")
            if (cached.isFile && cached.length() in 1..MAX_SUBTITLE_BYTES.toLong() && subtitleFormat(SubtitleText.decode(cached.readBytes())) != null) return@runInterruptible cached
        }
        subtitleHttpsUrl(subtitle.downloadUrl)
        val response = transport.execute(SubtitleRequest(subtitle.downloadUrl, MAX_SUBTITLE_BYTES, followRedirects = true))
        checkSubtitleStatus(response.status)
        if (response.body.size > MAX_SUBTITLE_BYTES) throw invalidSubtitleResponse()
        val bytes = if (response.body.size >= 2 && response.body[0] == 0x1f.toByte() && response.body[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(response.body)).use { readLimited(it, MAX_SUBTITLE_BYTES) }
        } else response.body
        val text = SubtitleText.decode(bytes)
        val extension = subtitleFormat(text) ?: throw SubtitleServiceException("الملف المستلم ليس ترجمة صالحة. جرّب نتيجة أخرى.")
        subtitleInterrupted()
        val temporary = File.createTempFile("subtitle-", ".tmp", directory)
        val target = File(directory, "$name.$extension")
        try {
            temporary.writeText(text, Charsets.UTF_8)
            subtitleInterrupted()
            if (!temporary.renameTo(target)) throw IOException("Cannot save subtitle")
        } finally {
            temporary.delete()
        }
        target
    }
}

internal fun subtitleFormat(text: String): String? {
    val start = text.trimStart()
    if (start.startsWith("<!DOCTYPE", true) || start.startsWith("<html", true)) return null
    if (start.startsWith("[Script Info]", true) && text.contains("[Events]", true) && text.contains("Dialogue:", true)) return "ass"
    val times = Regex("(?m)^\\s*(?:\\d{1,3}:)?\\d{2}:\\d{2}[,.]\\d{3}\\s*-->\\s*(?:\\d{1,3}:)?\\d{2}:\\d{2}[,.]\\d{3}")
    if (!times.containsMatchIn(text)) return null
    return if (start.startsWith("WEBVTT", true)) "vtt" else "srt"
}

private fun subtitleDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
