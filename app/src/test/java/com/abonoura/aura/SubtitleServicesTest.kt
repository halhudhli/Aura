package com.abonoura.aura

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

class SubtitleServicesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val arabic = subtitleLanguages.first { it.code == "ar" }
    private val movie = SubtitleMedia("tt0133093", "The Matrix", SubtitleMediaKind.Movie, "1999")
    private val addon = SubtitleAddon.fromManifest("subsense", "SubSense", "https://subsense.example/private-config/manifest.json")!!
    private val subtitle = OnlineSubtitle("result-42", "Movie Release", "ar", "SubDL", "SubSense", "https://files.example/subtitle")
    private val srt = "1\n00:00:01,000 --> 00:00:03,000\nمرحبًا بالعالم\n"
    private val response = """{"subtitles":[{"id":"42","lang":"ara","source":"SubDL","releaseName":"Movie Release","url":"https://files.example/subtitle"}]}"""

    @Test fun detectsSeriesAndKeepsItsSeasonAndEpisode() {
        val hint = subtitleVideoHint("مسلسل.S02E07.1080p.WEB-DL.mkv")
        assertEquals("مسلسل", hint.query)
        assertEquals(SubtitleMediaKind.Series, hint.kind)
        assertEquals(2, hint.season)
        assertEquals(7, hint.episode)
        assertEquals(3, subtitleVideoHint("Show 1x03.mkv").episode)
    }

    @Test fun cleansMovieReleaseWithoutDestroyingNumericTitles() {
        assertEquals("The Matrix", subtitleVideoHint("The.Matrix.1999.1080p.BluRay.mkv").query)
        assertEquals("1917", subtitleVideoHint("1917.2019.1080p.mkv").query)
        assertEquals(SubtitleMediaKind.Movie, subtitleVideoHint("فيلم.mp4").kind)
    }

    @Test fun privateManifestIsPreservedAndNeverAppearsInDisplayName() {
        val configured = SubtitleAddon.fromManifest("pro", "OpenSubtitles PRO", "stremio://pro.example/private-config/manifest.json")!!
        assertEquals("OpenSubtitles PRO", configured.toString())
        assertTrue(configured.searchUrl(movie, 1, 1, "The Matrix.mkv").startsWith("https://pro.example/private-config/subtitles/movie/tt0133093/"))
        assertTrue(configured.searchUrl(movie, 1, 1, "The Matrix.mkv").endsWith("filename=The%20Matrix.mkv.json"))
    }

    @Test fun invalidManifestsAreNotUsedAsCredentialsOrUrls() {
        for (value in listOf("plain-key", "http://example.com/config/manifest.json", "https://example.com/configure", "https://user:pass@example.com/manifest.json")) {
            assertNull(SubtitleAddon.fromManifest("test", "Test", value))
        }
    }

    @Test fun titleSearchEncodesUnicodeAndFiltersUnsupportedIds() = runBlocking {
        val transport = fake("""{"metas":[{"id":"tt0133093","name":"The Matrix","releaseInfo":"1999"},{"id":"tmdb:1","name":"Other"}]}""")
        val titles = client(transport).titles("فيلم & Movie", SubtitleMediaKind.Movie)
        assertEquals(listOf(movie), titles)
        assertTrue(transport.requests.single().url.contains("search=%D9%81%D9%8A%D9%84%D9%85%20%26%20Movie.json"))
    }

    @Test fun explicitImdbIdDoesNotRequireCatalogSearch() = runBlocking {
        val transport = fake("{}")
        assertEquals("tt0133093", client(transport).titles("tt0133093", SubtitleMediaKind.Movie).single().id)
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun addonRequestIncludesCorrectEpisodeAndOnlyVideoTitle() = runBlocking {
        val transport = fake(response)
        val items = client(transport).subtitles(movie.copy(kind = SubtitleMediaKind.Series), arabic, 2, 7, "Show.S02E07.mkv").subtitles
        val request = transport.requests.single()
        assertTrue(request.url.contains("/series/tt0133093%3A2%3A7/filename=Show.S02E07.mkv.json"))
        assertEquals("SubDL", items.single().source)
        assertEquals("ar", items.single().language)
        assertEquals("Movie Release", items.single().release)
    }

    @Test fun languageAliasesAreNormalizedAndOtherLanguagesAreNotMixedIn() = runBlocking {
        val transport = fake("""{"subtitles":[
            {"lang":"ara","url":"https://files.example/ar"},
            {"lang":"eng","url":"https://files.example/en"},
            {"lang":"ar","url":"https://files.example/ar"},
            {"lang":"ara","url":"file:///device/file.srt"}
        ]}""")
        val results = client(transport).subtitles(movie, arabic, 1, 1, "movie")
        assertEquals(1, results.subtitles.size)
        assertEquals("https://files.example/ar", results.subtitles.single().downloadUrl)
    }

    @Test fun aFailedProviderDoesNotDiscardWorkingProviders() = runBlocking {
        val second = SubtitleAddon.fromManifest("pro", "OpenSubtitles PRO", "https://pro.example/config/manifest.json")!!
        val transport = RecordingTransport { request ->
            if (request.url.contains("pro.example")) SubtitleResponse(503, byteArrayOf()) else json(response)
        }
        val results = SubtitleSearchClient(listOf(addon, second), transport).subtitles(movie, arabic, 1, 1, "movie")
        assertEquals(1, results.subtitles.size)
        assertEquals(1, results.warnings.size)
        assertTrue(results.warnings.single().contains("OpenSubtitles PRO"))
        assertFalse(results.warnings.single().contains("private-config"))
    }

    @Test fun malformedAddonResponseIsAProviderWarning() = runBlocking {
        for (body in listOf("<html>Bad gateway</html>", "{}")) {
            val result = client(fake(body)).subtitles(movie, arabic, 1, 1, "movie")
            assertTrue(result.subtitles.isEmpty())
            assertEquals(1, result.warnings.size)
        }
    }

    @Test fun unconfiguredClientDoesNotRequestSubtitles() {
        val transport = fake("{}")
        assertThrows(SubtitleServiceException::class.java) {
            runBlocking { SubtitleSearchClient(emptyList(), transport).subtitles(movie, arabic, 1, 1, "movie") }
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun downloadsArabicUtf8AndReusesCachedFileWithoutAnotherRequest() = runBlocking {
        val transport = fake(srt)
        val client = client(transport)
        val folder = temporary.newFolder()
        val file = client.download(subtitle, folder)
        assertEquals(srt, file.readText())
        assertEquals(file, client.download(subtitle, folder))
        assertEquals(1, transport.requests.size)
        assertEquals("https://files.example/subtitle", transport.requests.single().url)
        assertTrue(transport.requests.single().followRedirects)
    }

    @Test fun supportsVttAndStyledAssSubtitles() = runBlocking {
        val formats = mapOf(
            "vtt" to "WEBVTT\n\n00:01.000 --> 00:03.000\nمرحبًا\n",
            "ass" to "[Script Info]\nTitle: Test\n[Events]\nDialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,مرحبًا\n",
        )
        for ((extension, text) in formats) {
            val file = client(fake(text)).download(subtitle, temporary.newFolder())
            assertEquals(extension, file.extension)
            assertEquals(text, file.readText())
        }
    }

    @Test fun decompressesGzipAndPreservesArabicEncoding() = runBlocking {
        val compressed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(srt.toByteArray()) } }.toByteArray()
        val transport = RecordingTransport { SubtitleResponse(200, compressed) }
        assertEquals(srt, client(transport).download(subtitle, temporary.newFolder()).readText())
    }

    @Test fun untrustedLabelsCannotChooseTheLocalFilePath() = runBlocking {
        val folder = temporary.newFolder()
        val file = client(fake(srt)).download(subtitle.copy(id = "../../outside", release = "../../outside.srt"), folder)
        assertEquals(folder.canonicalFile, file.parentFile.canonicalFile)
        assertFalse(file.name.contains("outside"))
    }

    @Test fun invalidOrInsecureDownloadsDoNotSavePartialFiles() {
        for (text in listOf("<html>Sign in first</html>", "{\"error\":\"not found\"}", "")) {
            val folder = temporary.newFolder()
            assertThrows(SubtitleServiceException::class.java) { runBlocking { client(fake(text)).download(subtitle, folder) } }
            assertTrue(folder.list()!!.isEmpty())
        }
        val transport = fake(srt)
        assertThrows(SubtitleServiceException::class.java) {
            runBlocking { client(transport).download(subtitle.copy(downloadUrl = "http://files.example/subtitle"), temporary.newFolder()) }
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun oversizedResponsesAndDecompressedFilesAreBounded() {
        assertArrayEquals(byteArrayOf(1, 2, 3), readLimited(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3))
        assertThrows(SubtitleServiceException::class.java) { readLimited(ByteArrayInputStream(ByteArray(4)), 3) }
        val compressed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(ByteArray(MAX_SUBTITLE_BYTES + 1)) } }.toByteArray()
        val transport = RecordingTransport { SubtitleResponse(200, compressed) }
        val folder = temporary.newFolder()
        assertThrows(SubtitleServiceException::class.java) { runBlocking { client(transport).download(subtitle, folder) } }
        assertTrue(folder.list()!!.isEmpty())
    }

    @Test fun quotaAndRateErrorsDoNotExposePrivateServerDetails() {
        for (status in listOf(401, 403, 406, 429, 503)) {
            val failure = assertThrows(SubtitleServiceException::class.java) {
                runBlocking { client(RecordingTransport { SubtitleResponse(status, "private-config".toByteArray()) }).download(subtitle, temporary.newFolder()) }
            }
            assertFalse(failure.message.orEmpty().contains("private-config"))
            if (status == 406) assertTrue(failure.message.orEmpty().contains("حد التحميل"))
            if (status == 429) assertTrue(failure.message.orEmpty().contains("انتظر"))
        }
    }

    @Test fun cancellingRequestDoesNotDeliverResultsToAClosedDialog() = runBlocking {
        val started = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        var delivered = false
        val client = client(RecordingTransport { started.countDown(); blocked.await(); json("""{"metas":[]}""") })
        val job = launch { client.titles("movie", SubtitleMediaKind.Movie); delivered = true }
        withContext(Dispatchers.IO) { assertTrue(started.await(5, TimeUnit.SECONDS)) }
        job.cancelAndJoin()
        assertFalse(delivered)
        blocked.countDown()
    }

    private fun client(transport: SubtitleTransport) = SubtitleSearchClient(listOf(addon), transport)
    private fun fake(body: String) = RecordingTransport { json(body) }
    private fun json(body: String) = SubtitleResponse(200, body.toByteArray(Charsets.UTF_8))
    private class RecordingTransport(private val handler: (SubtitleRequest) -> SubtitleResponse) : SubtitleTransport {
        val requests = CopyOnWriteArrayList<SubtitleRequest>()
        override fun execute(request: SubtitleRequest): SubtitleResponse { requests += request; return handler(request) }
    }
}
