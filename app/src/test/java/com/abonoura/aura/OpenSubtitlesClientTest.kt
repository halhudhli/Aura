package com.abonoura.aura

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OpenSubtitlesClientTest {
    @get:Rule val temporary = TemporaryFolder()

    private val subtitle = OnlineSubtitle(42, "film.ar.srt", "Film.2024.1080p", "ar", 200, false, false)
    private val srt = "1\n00:00:01,000 --> 00:00:03,000\nمرحبًا بالعالم\n"

    @Test fun searchPreservesEpisodeAndEncodesQueryAndLanguage() = runBlocking {
        val transport = FakeTransport(json("""{"data":[],"total_pages":0}"""))
        val query = subtitleQuery("مسلسل.S01E02.2024.1080p.WEB-DL.x265.mkv")
        assertEquals("مسلسل S01E02 2024", query)
        client(transport).search(query, "ar")
        val request = transport.requests.single()
        assertTrue(request.url.contains("languages=ar"))
        assertTrue(request.url.contains("query=%D9%85%D8%B3%D9%84%D8%B3%D9%84+S01E02+2024"))
        assertEquals("test-api-key", request.headers["Api-Key"])
        assertEquals("Aura v1.0", request.headers["User-Agent"])
        assertFalse(request.followRedirects)
    }

    @Test fun mapsEverySubtitleFileAndPaginatesWithoutDuplicateIds() = runBlocking {
        val transport = FakeTransport(json("""{
          "total_pages":2,"data":[
            {"attributes":{"release":"Movie Release","language":"ar","download_count":17,
              "hearing_impaired":true,"foreign_parts_only":true,
              "files":[{"file_id":42,"file_name":"disc1.srt"},{"file_id":43,"file_name":"disc2.srt"}]}},
            {"attributes":{"files":[{"file_id":42},{"file_id":0}]}},
            {"attributes":{}},{}
          ]}"""))
        val page = client(transport).search("movie", "ar")
        assertEquals(listOf(42, 43), page.subtitles.map { it.fileId })
        assertEquals(2, page.nextPage)
        assertTrue(page.subtitles.first().hearingImpaired)
        assertTrue(page.subtitles.first().foreignPartsOnly)
        assertEquals(17, page.subtitles.first().downloads)
    }

    @Test fun emptyResultsDoNotOfferAnExtraPage() = runBlocking {
        val page = client(FakeTransport(json("""{"data":[],"total_pages":7}"""))).search("movie", "ar")
        assertTrue(page.subtitles.isEmpty())
        assertNull(page.nextPage)
    }

    @Test fun missingKeyDoesNotMakeANetworkRequest() {
        val transport = FakeTransport()
        assertThrows(SubtitleServiceException::class.java) {
            runBlocking { OpenSubtitlesClient("", "Aura v1.0", transport).search("movie", "ar") }
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun malformedServerResponseIsReportedAsAServiceFailure() {
        for (body in listOf("<html>Bad gateway</html>", "{}")) {
            assertThrows(SubtitleServiceException::class.java) {
                runBlocking { client(FakeTransport(json(body))).search("movie", "ar") }
            }
        }
    }

    @Test fun quotaAndRateLimitFailuresDoNotExposeServerBodyOrKey() {
        for (status in listOf(401, 403, 406, 429, 503)) {
            val failure = assertThrows(SubtitleServiceException::class.java) {
                runBlocking {
                    client(FakeTransport(SubtitleResponse(status, "test-api-key server details".toByteArray())))
                        .search("movie", "ar")
                }
            }
            assertFalse(failure.message.orEmpty().contains("test-api-key"))
            if (status == 406) assertTrue(failure.message.orEmpty().contains("حد التحميل اليومي"))
            if (status == 429) assertTrue(failure.message.orEmpty().contains("انتظر"))
        }
    }

    @Test fun downloadsUtf8SubtitleWithoutForwardingApiKeyToFileHost() = runBlocking {
        val transport = downloadable()
        val file = client(transport).download(subtitle, temporary.newFolder())
        assertEquals(srt, file.readText())
        assertEquals("opensubtitles-42.srt", file.name)
        val body = JSONObject(String(transport.requests.first().body!!, Charsets.UTF_8))
        assertEquals(42, body.getInt("file_id"))
        assertEquals("srt", body.getString("sub_format"))
        val fileRequest = transport.requests.last()
        assertEquals("https://downloads.example/subtitle", fileRequest.url)
        assertEquals(setOf("User-Agent"), fileRequest.headers.keys)
        assertTrue(fileRequest.followRedirects)
    }

    @Test fun selectingCachedSubtitleDoesNotConsumeAnotherDownload() = runBlocking {
        val transport = downloadable()
        val client = client(transport)
        val folder = temporary.newFolder()
        val first = client.download(subtitle, folder)
        assertEquals(first, client.download(subtitle, folder))
        assertEquals(2, transport.requests.size)
    }

    @Test fun damagedCacheIsReplacedAndServerFilenameCannotEscapeDirectory() = runBlocking {
        val folder = temporary.newFolder()
        folder.resolve("opensubtitles-42.srt").writeText("invalid old download")
        val file = client(downloadable()).download(subtitle.copy(fileName = "../../outside.srt"), folder)
        assertEquals(folder.canonicalFile, file.parentFile.canonicalFile)
        assertEquals(srt, file.readText())
        assertEquals(listOf("opensubtitles-42.srt"), folder.list()!!.toList())
    }

    @Test fun invalidDownloadDoesNotSaveHtmlOrPartialSubtitle() {
        val folder = temporary.newFolder()
        val transport = FakeTransport(
            json("""{"link":"https://downloads.example/subtitle"}"""),
            json("<html>Sign in first</html>"),
        )
        assertThrows(SubtitleServiceException::class.java) {
            runBlocking { client(transport).download(subtitle, folder) }
        }
        assertTrue(folder.list()!!.isEmpty())
    }

    @Test fun insecureDownloadUrlIsRejectedBeforeRequestingTheFile() {
        val transport = FakeTransport(json("""{"link":"http://downloads.example/subtitle"}"""))
        assertThrows(SubtitleServiceException::class.java) {
            runBlocking { client(transport).download(subtitle, temporary.newFolder()) }
        }
        assertEquals(1, transport.requests.size)
    }

    @Test fun oversizedDownloadIsRejectedWithoutSavingIt() {
        val folder = temporary.newFolder()
        val transport = FakeTransport(
            json("""{"link":"https://downloads.example/subtitle"}"""),
            SubtitleResponse(200, ByteArray(MAX_SUBTITLE_BYTES + 1)),
        )
        assertThrows(SubtitleServiceException::class.java) {
            runBlocking { client(transport).download(subtitle, folder) }
        }
        assertTrue(folder.list()!!.isEmpty())
    }

    @Test fun streamedResponseLimitIsEnforcedEvenWithoutContentLength() {
        assertArrayEquals(byteArrayOf(1, 2, 3), readLimited(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3))
        assertThrows(SubtitleServiceException::class.java) {
            readLimited(ByteArrayInputStream(ByteArray(4)), 3)
        }
    }

    @Test fun cancellingSearchDoesNotDeliverResultsToAClosedDialog() = runBlocking {
        val started = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        var delivered = false
        val client = client(SubtitleTransport {
            started.countDown()
            blocked.await()
            json("""{"data":[]}""")
        })
        val job = launch {
            client.search("movie", "ar")
            delivered = true
        }
        // Launch first, then wait outside the runBlocking event loop.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            assertTrue(started.await(5, TimeUnit.SECONDS))
        }
        job.cancelAndJoin()
        assertFalse(delivered)
        blocked.countDown()
    }

    private fun client(transport: SubtitleTransport) = OpenSubtitlesClient("test-api-key", "Aura v1.0", transport)
    private fun json(value: String) = SubtitleResponse(200, value.toByteArray(Charsets.UTF_8))
    private fun downloadable() = FakeTransport(
        json("""{"link":"https://downloads.example/subtitle","file_name":"movie.srt"}"""),
        SubtitleResponse(200, srt.toByteArray(Charsets.UTF_8)),
    )

    private class FakeTransport(vararg responses: SubtitleResponse) : SubtitleTransport {
        private val pending = ArrayDeque(responses.toList())
        val requests = mutableListOf<SubtitleRequest>()
        override fun execute(request: SubtitleRequest): SubtitleResponse {
            requests += request
            return pending.removeFirst()
        }
    }
}
