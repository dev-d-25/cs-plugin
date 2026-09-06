package com.modsuite

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CloudGateBypassTest {

    private val logs = mutableListOf<Pair<String, String>>()

    private fun gate(page: PageClient) =
        CloudGateBypass(page) { s, m -> logs.add(s to m) }

    @Test
    fun `full gate chain reaches driveseed and propagates cookies`() = runBlocking {
        val sidUrl = "https://cloud.unblockedgames.world/?sid=SID_EP1"
        val page = FakePageClient(
            postHandler = { u, _, _, cookies, data ->
                when {
                    u == "https://cloud.unblockedgames.world/" -> {
                        assertEquals("SID_EP1", java.net.URLDecoder.decode(data["_wp_http"]!!, "UTF-8"))
                        PageResponse(u, 200, fixture("gate-stage1.html"), mapOf("sess" to "abc"))
                    }
                    u == "https://cloud.unblockedgames.world/verify-step" -> {
                        // Session cookie from stage 1 must travel onward.
                        assertEquals("abc", cookies["sess"])
                        PageResponse(u, 200, fixture("gate-stage2.html"), mapOf("s2" to "1"))
                    }
                    else -> error("unexpected POST $u")
                }
            },
            getHandler = { u, _, _, cookies ->
                assertEquals("https://cloud.unblockedgames.world/?go=go_cookie_1", u)
                // s_343 cookie set by the adapter must travel on the ?go= call.
                assertEquals("cookie_value_1", cookies["go_cookie_1"])
                assertEquals("abc", cookies["sess"])
                PageResponse(u, 200, fixture("gate-stage3.html"))
            },
        )
        val dest = gate(page).bypass(sidUrl)
        assertEquals("https://driveseed.org/r?key=K123&id=I456", dest)
        // Tokens are single use: exactly one ?go= request.
        assertEquals(1, page.calls.count { it.method == "GET" && it.url.contains("?go=") })
    }

    @Test
    fun `landed driveseed url returns immediately`() = runBlocking {
        val page = FakePageClient(
            postHandler = { u, _, _, _, _ ->
                if (u == "https://cloud.unblockedgames.world/") {
                    PageResponse(u, 200, fixture("gate-stage1.html"))
                } else {
                    PageResponse(u, 200, fixture("gate-stage2.html"))
                }
            },
            getHandler = { _, _, _, _ ->
                PageResponse("https://driveseed.org/file/DIRECT", 200, "<html></html>")
            },
        )
        val dest = gate(page).bypass("https://cloud.unblockedgames.world/?sid=X")
        assertEquals("https://driveseed.org/file/DIRECT", dest)
    }

    @Test
    fun `missing sid returns null with a diagnosed stage`() = runBlocking {
        val page = FakePageClient()
        assertNull(gate(page).bypass("https://cloud.unblockedgames.world/"))
        assertTrue(logs.any { it.first == "gate-sid" })
        assertTrue(page.calls.isEmpty())
    }

    @Test
    fun `missing landing form returns null with a diagnosed stage`() = runBlocking {
        val page = FakePageClient(
            postHandler = { u, _, _, _, _ ->
                PageResponse(u, 200, "<html><body>challenge</body></html>")
            },
        )
        assertNull(gate(page).bypass("https://cloud.unblockedgames.world/?sid=X"))
        assertTrue(logs.any { it.first == "gate-stage1-parse" })
    }

    @Test
    fun `missing s_343 pair returns null with a diagnosed stage`() = runBlocking {
        val page = FakePageClient(
            postHandler = { u, _, _, _, _ ->
                if (u == "https://cloud.unblockedgames.world/") {
                    PageResponse(u, 200, fixture("gate-stage1.html"))
                } else {
                    PageResponse(u, 200, "<html><body>no cookie script</body></html>")
                }
            },
        )
        assertNull(gate(page).bypass("https://cloud.unblockedgames.world/?sid=X"))
        assertTrue(logs.any { it.first == "gate-stage2-parse" })
    }

    @Test
    fun `refresh meta extraction tolerates spacing and entities`() {
        assertEquals(
            "https://driveseed.org/r?key=K&id=I",
            CloudGateBypass.extractRefreshUrl("2; url=https://driveseed.org/r?key=K&amp;id=I"),
        )
        assertNull(CloudGateBypass.extractRefreshUrl("2"))
    }
}
