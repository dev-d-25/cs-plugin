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
                    else -> error("unexpected GET $u")
                }
            },
        )
        val out = resolver(page).resolveDriveSeed(
            "https://driveseed.org/file/FILE123", "MoviesLeech · 720p", "720p",
        )
        // V2 (?url= fast path) + V1 (redirect) + r2 (anchor scan).
        assertEquals(3, out.size)
        assertTrue(out.all { it.url.contains("googleusercontent.com") })
        // Quality comes from the file name, not a hardcoded default.
        assertTrue(out.all { it.qualityLabel == "720p" })
        val names = out.map { it.name }
        assertTrue(names.any { it.contains("Instant V2") })
        assertTrue(names.any { it.contains("Instant V1") })
        assertTrue(names.any { it.contains("Cloud") })
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
