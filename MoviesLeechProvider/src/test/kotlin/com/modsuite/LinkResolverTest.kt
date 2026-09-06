package com.modsuite

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LinkResolverTest {

    private val finalUrl = "https://video-downloads.googleusercontent.com/final123/video.mp4"
    private val logs = mutableListOf<Pair<String, String>>()

    private fun resolver(page: PageClient, gate: GateBypass = FakeGate()) =
        LinkResolver(page, gate, "MoviesLeech") { s, m -> logs.add(s to m) }

    // -- Seed fast path: ?url= needs no page fetch, only a probe --------

    @Test
    fun `seed link with url param resolves without fetching`() = runBlocking {
        val page = FakePageClient()
        val seed = "https://instant.video-gen.xyz/?url=" +
            java.net.URLEncoder.encode(finalUrl, "UTF-8")
        val out = resolver(page).resolveSeedPage(seed, "MoviesLeech · 720p", "720p")
        assertEquals(1, out.size)
        // User-visible contract: direct VIDEO url + readable name.
        assertEquals(finalUrl, out[0].url)
        assertEquals("MoviesLeech · 720p", out[0].name)
        assertEquals("720p", out[0].qualityLabel)
        assertTrue(page.calls.none { it.method == "GET" })
        assertEquals(1, page.calls.count { it.method == "PROBE" })
    }

    @Test
    fun `seed page anchors scanned when no url param present`() = runBlocking {
        val page = FakePageClient(
            getHandler = { u, _, _, _ ->
                assertEquals("https://cdn.video-gen.xyz/dl/seed456", u)
                PageResponse(u, 200, fixture("seed-page.html"))
            },
        )
        val out = resolver(page).resolveSeedPage(
            "https://cdn.video-gen.xyz/dl/seed456", "MoviesLeech · 720p", "720p",
        )
        assertEquals(1, out.size)
        assertTrue(out[0].url.contains("googleusercontent.com"))
    }

    // -- DriveSeed file page: every mirror emitted -----------------------

    @Test
    fun `driveseed file emits every mirror separately`() = runBlocking {
        val page = FakePageClient(
            getHandler = { u, _, _, _ ->
                when {
                    u == "https://driveseed.org/file/FILE123" ->
                        PageResponse(u, 200, fixture("driveseed-file.html"))
                    u == "https://cdn.video-gen.xyz/dl/seed456" ->
                        // 302-style landing carrying the final ?url=
                        PageResponse("$u?url=" + java.net.URLEncoder.encode(finalUrl, "UTF-8"), 200, "<html></html>")
                    u == "https://example.r2.dev/cloud789/file.mkv" ->
                        PageResponse(u, 200, fixture("seed-page.html"))
                    u == "https://resume.example.net/dl/resume123" ->
                        PageResponse(
                            "$u?url=" + java.net.URLEncoder.encode(finalUrl, "UTF-8"),
                            200, "<html></html>",
                        )
                    else -> error("unexpected GET $u")
                }
            },
        )
        val out = resolver(page).resolveDriveSeed(
            "https://driveseed.org/file/FILE123", "MoviesLeech · 720p", "720p",
        )
        // V2 (?url= fast path) + V1 (redirect) + r2 (anchor scan) + Resume.
        assertEquals(4, out.size)
        assertTrue(out.all { it.url.contains("googleusercontent.com") })
        // Quality comes from the file name, not a hardcoded default.
        assertTrue(out.all { it.qualityLabel == "720p" })
        val names = out.map { it.name }
        assertTrue(names.any { it.contains("Instant V2") })
        assertTrue(names.any { it.contains("Instant V1") })
        assertTrue(names.any { it.contains("Cloud") })
        assertTrue(names.any { it.contains("Resume") })
    }

    @Test
    fun `shortlink js redirect resolves to file page`() = runBlocking {
        val short = "https://driveseed.org/r?key=K&id=I"
        val file = "https://driveseed.org/file/Nix6LuRyiobOMsBTHFlY"
        val page = FakePageClient(
            getHandler = { u, _, _, _ ->
                when (u) {
                    short -> PageResponse(u, 200, fixture("shortlink-js.html"))
                    file -> PageResponse(u, 200, fixture("driveseed-file.html"))
                    // V2 carries ?url=, V1/r2 need a fetch.
                    "https://instant.video-gen.xyz/?url=" + java.net.URLEncoder.encode(finalUrl, "UTF-8") ->
                        error("fast path must not fetch")
                    "https://cdn.video-gen.xyz/dl/seed456" ->
                        PageResponse("$u?url=" + java.net.URLEncoder.encode(finalUrl, "UTF-8"), 200, "<html></html>")
                    "https://example.r2.dev/cloud789/file.mkv" ->
                        PageResponse(u, 200, fixture("seed-page.html"))
                    else -> error("unexpected GET $u")
                }
            },
        )
        // Regression: /r pages answer with a 70-byte JS redirect, no
        // anchor — anchor-only scanning killed every shortlink mirror.
        val out = resolver(page).resolveShortLink(short, "MoviesLeech · 480p", "480p")
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it.url.contains("googleusercontent.com") })
        // Relative /file/... path resolved against the /r page URL.
        assertTrue(page.calls.any { it.method == "GET" && it.url == file })
    }

    @Test
    fun `failing mirror does not kill the other mirrors`() = runBlocking {
        val page = FakePageClient(
            getHandler = { u, _, _, _ ->
                if (u == "https://driveseed.org/file/FILE123") {
                    PageResponse(u, 200, fixture("driveseed-file.html"))
                } else {
                    throw java.io.IOException("403 for $u")
                }
            },
        )
        // ?url= fast path still works (no GET), others fail -> 1 link.
        val out = resolver(page).resolveDriveSeed(
            "https://driveseed.org/file/FILE123", "MoviesLeech · 720p", "720p",
        )
        assertEquals(1, out.size)
        assertTrue(logs.any { it.first == "seed-fetch" || it.first == "shortlink-fetch" || it.first.contains("seed") })
    }

    // -- Full chain through a fake gate -----------------------------------

    @Test
    fun `gate target resolves end to end to a direct link`() = runBlocking {
        val gateUrl = "https://cloud.unblockedgames.world/?sid=SID_EP1"
        val page = FakePageClient(
            getHandler = { u, _, _, _ ->
                when (u) {
                    "https://driveseed.org/r?key=K&id=I" ->
                        PageResponse("https://driveseed.org/file/FILE123", 200, "<html></html>")
                    "https://driveseed.org/file/FILE123" ->
                        PageResponse(u, 200, fixture("driveseed-file.html"))
                    else -> error("unexpected GET $u")
                }
            },
        )
        val gate = FakeGate { if (it == gateUrl) "https://driveseed.org/r?key=K&id=I" else null }
        val sources = listOf(
            SourceCandidate(720, "720p", "Server 2", gateUrl, SourceKind.GATE),
        )
        val out = resolver(page, gate).resolveEpisode(sources)
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { it.url.contains("googleusercontent.com") })
        assertEquals(listOf(gateUrl), gate.seen)
    }

    @Test
    fun `failed gate logs stage and other sources still resolve`() = runBlocking {
        val page = FakePageClient()
        val gate = FakeGate { null } // bypass fails for everything
        val sources = listOf(
            SourceCandidate(720, "720p", "Server 2", "https://cloud.unblockedgames.world/?sid=BAD", SourceKind.GATE),
            SourceCandidate(
                720, "720p", "Seed",
                "https://instant.video-gen.xyz/?url=" + java.net.URLEncoder.encode(finalUrl, "UTF-8"),
                SourceKind.SEED,
            ),
        )
        val out = resolver(page, gate).resolveEpisode(sources)
        assertEquals(1, out.size)
        assertEquals(finalUrl, out[0].url)
        assertTrue(logs.any { it.first == "gate" })
    }

    @Test
    fun `unknown host raises a diagnosed target error`() = runBlocking {
        val page = FakePageClient()
        try {
            resolver(page).resolveTarget("https://unknown-host.example/x", "HD", "")
            fail("expected ResolveError")
        } catch (e: LinkResolver.ResolveError) {
            assertEquals("target", e.stage)
        }
    }

    // -- Pure helpers ------------------------------------------------------

    @Test
    fun `mirrors resolve concurrently, not one by one`() = runBlocking {
        val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        val maxSeen = java.util.concurrent.atomic.AtomicInteger(0)
        val page = FakePageClient(
            probeHandler = { u ->
                val now = inFlight.incrementAndGet()
                maxSeen.updateAndGet { m -> maxOf(m, now) }
                kotlinx.coroutines.delay(300)
                inFlight.decrementAndGet()
                ProbeResult(u, 200, "video/mp4")
            },
        )
        val sources = listOf(
            SourceCandidate(480, "480p", "Fast Server", "https://instant.video-gen.xyz/?url=" + java.net.URLEncoder.encode(finalUrl, "UTF-8"), SourceKind.SEED),
            SourceCandidate(720, "720p", "Server 2", "https://instant.video-gen.xyz/?url=" + java.net.URLEncoder.encode("$finalUrl?x=2", "UTF-8"), SourceKind.SEED),
        )
        val out = resolver(page).resolveEpisode(sources)
        assertEquals(2, out.size)
        // Sequential resolution could never overlap the two probes.
        assertTrue("expected concurrent probes, max in-flight=${maxSeen.get()}", maxSeen.get() >= 2)
    }

    @Test
    fun `mirror names never repeat the quality`() {
        assertEquals(
            "MoviesLeech · 480p · Instant V1",
            mirrorName("MoviesLeech", "480p", "Instant V1 480p"),
        )
    }
    @Test
    fun `relative seed hrefs absolutize against the file page`() {
        val html = """<html><body><a href="/zfile/abc123">Resume Cloud</a><a href="https://cdn.video-gen.xyz/x">Instant Download</a></body></html>"""
        assertEquals(
            listOf("https://driveseed.org/zfile/abc123", "https://cdn.video-gen.xyz/x"),
            LinkResolver.parseSeedMirrors(html, "https://driveseed.org/file/XYZ"),
        )
    }

    @Test
    fun `identical finals emit once`() = runBlocking {
        val page = FakePageClient()
        val s1 = "https://instant.video-gen.xyz/?url=" + java.net.URLEncoder.encode(finalUrl, "UTF-8")
        val s2 = "https://instant.video-gen.xyz/?url=" + java.net.URLEncoder.encode("$finalUrl?other=1", "UTF-8")
        val sources = listOf(
            SourceCandidate(1080, "1080p", "Fast Server", s1, SourceKind.GATE),
            SourceCandidate(1080, "1080p", "Server 2", s1, SourceKind.GATE),
            SourceCandidate(1080, "1080p", "Server 2", s2, SourceKind.GATE),
        )
        val out = resolver(page).resolveEpisode(sources)
        // First two collapse (same final), third survives.
        assertEquals(2, out.size)
    }

    @Test
    fun `seed final extraction decodes url param`() {
        val seed = "https://x.example/?url=" + java.net.URLEncoder.encode(finalUrl, "UTF-8")
        assertEquals(finalUrl, LinkResolver.extractSeedFinalFromUrl(seed))
        assertNull(LinkResolver.extractSeedFinalFromUrl("https://x.example/?url=https://other.example/f"))
        assertNull(LinkResolver.extractSeedFinalFromUrl("https://x.example/noparam"))
    }

    @Test
    fun `source classification and mirror naming`() {
        assertEquals(SourceKind.GATE, classifySource("https://cloud.unblockedgames.world/?sid=1"))
        assertEquals(SourceKind.ARCHIVE, classifySource("https://leechpro.blog/archives/x"))
        assertEquals(SourceKind.DRIVESEED, classifySource("https://driveseed.org/file/x"))
        assertEquals(SourceKind.SEED, classifySource("https://video-seed.dev/?url=x"))
        assertEquals("MoviesLeech · 1080p · Fast Server", mirrorName("MoviesLeech", "1080p", "Fast Server"))
    }
}
