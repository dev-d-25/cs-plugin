package com.modsuite

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Wiring smoke test: proves the shared resolver/gate copies in this
 * module resolve the JS-shortlink chain (full suites live in leech).
 */
class ModLinkSmokeTest {

    private val finalUrl = "https://video-downloads.googleusercontent.com/final123/video.mp4"

    @Test
    fun `shortlink js redirect resolves to file page`() = runBlocking {
        val short = "https://driveseed.org/r?key=K&id=I"
        val file = "https://driveseed.org/file/Nix6LuRyiobOMsBTHFlY"
        val logs = mutableListOf<Pair<String, String>>()
        val page = FakePageClient(
            getHandler = { u, _, _, _ ->
                when (u) {
                    short -> PageResponse(u, 200, fixture("shortlink-js.html"))
                    file -> PageResponse(u, 200, fixture("driveseed-file.html"))
                    "https://cdn.video-gen.xyz/dl/seed456" ->
                        PageResponse("$u?url=" + java.net.URLEncoder.encode(finalUrl, "UTF-8"), 200, "<html></html>")
                    "https://example.r2.dev/cloud789/file.mkv" ->
                        PageResponse(u, 200, fixture("seed-page.html"))
                    "https://resume.example.net/dl/resume123" ->
                        PageResponse("$u?url=" + java.net.URLEncoder.encode(finalUrl, "UTF-8"), 200, "<html></html>")
                    else -> error("unexpected GET $u")
                }
            },
        )
        val resolver = LinkResolver(page, object : GateBypass {
            override suspend fun bypass(sidUrl: String): String? = null
        }, "MoviesMod") { s, m -> logs.add(s to m) }
        val out = resolver.resolveShortLink(short, "MoviesMod · 480p", "480p")
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it.url.contains("googleusercontent.com") })
        assertTrue(page.calls.any { it.method == "GET" && it.url == file })
    }

    @Test
    fun `relative seed hrefs absolutize against the file page`() {
        val html = """<html><body><a href="/zfile/abc123">Resume Cloud</a><a href="https://cdn.video-gen.xyz/x">Instant Download</a></body></html>"""
        assertEquals(
            listOf("https://driveseed.org/zfile/abc123", "https://cdn.video-gen.xyz/x"),
            LinkResolver.parseSeedMirrors(html, "https://driveseed.org/file/XYZ"),
        )
    }
}
