package com.abonoura.aura

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in live search only; never download files or print private addon configuration. */
class SubtitleIntegrationTest {
    @Test fun searchesConfiguredAddons() = runBlocking {
        assumeTrue(System.getenv("AURA_LIVE_SUBTITLE_TEST") == "true")
        val configs = listOf("SUBSENSE_API_KEY" to "SubSense", "OPENSUBTITLESPRO_API_KEY" to "OpenSubtitles PRO")
        val configured = configs.mapNotNull { (key, name) ->
            val value = System.getenv(key).orEmpty()
            if (value.isBlank()) null else {
                val addon = SubtitleAddon.fromManifest(key, name, value)
                assertTrue("$key must contain a full HTTPS manifest URL", addon != null)
                addon
            }
        }
        assumeTrue("Add at least one manifest secret for the live check", configured.isNotEmpty())
        val client = SubtitleSearchClient(configured)
        val title = try { client.titles("The Matrix", SubtitleMediaKind.Movie).first { it.id == "tt0133093" } }
        catch (_: Exception) { throw AssertionError("The public title catalog could not resolve The Matrix") }
        val results = client.subtitles(title, subtitleLanguages.first { it.code == "ar" }, 1, 1, "The.Matrix.1999.1080p.mkv")
        assertTrue(results.diagnostics.joinToString(" "), results.warnings.isEmpty())
        assertTrue("No Arabic subtitles returned; check addon language configuration", results.subtitles.isNotEmpty())
    }
}
