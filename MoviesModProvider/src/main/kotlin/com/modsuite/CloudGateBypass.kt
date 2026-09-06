package com.modsuite

// Shared seam, copy of MoviesLeechProvider/CloudGateBypass.kt (only the
// MoviesLeechParser.* reference was retargeted to MoviesModParser).
// Keep logic in sync. Site-specific code: MoviesModParser/Provider.

import org.jsoup.Jsoup

/**
 * Pure-HTTP bypass for the cloud.unblockedgames 3-click gate (plan Phase 7).
 *
 * This is a *separate compatibility adapter*, not generic link logic:
 * if the live gate ever needs browser JS or human verification, only this
 * file changes. Every stage failure is reported through [onLog] with the
 * stage name and cause — never swallowed into a bare null.
 *
 * Chain (reproduced end to end in a real browser):
 *   POST _wp_http=<sid> -> landing form (action + _wp_http2 + token) ->
 *   POST them -> s_343('cookieName','cookieValue') ->
 *   GET ?go=<cookieName> with session cookies -> meta refresh url= ->
 *   driveseed link. Tokens are SINGLE USE: ?go= is fetched exactly once.
 */
class CloudGateBypass(
    private val page: PageClient,
    private val onLog: (stage: String, message: String) -> Unit = { _, _ -> },
) : GateBypass {

    override suspend fun bypass(sidUrl: String): String? {
        val sid = Regex("""[?&]sid=([^&#]+)""").find(sidUrl)
            ?.groupValues?.getOrNull(1)?.trim()
        if (sid.isNullOrBlank()) {
            onLog("gate-sid", "no sid param in $sidUrl")
            return null
        }

        // The shared app client keeps no cookies between calls, but the
        // gate sets session cookies that later stages require. Carry a
        // jar manually, exactly like a browser session would.
        val jar = mutableMapOf<String, String>()
        val r1 = try {
            page.post(
                GATE_HOST + "/",
                headers = BROWSER_HEADERS,
                cookies = jar,
                data = mapOf("_wp_http" to enc(sid)),
                referer = sidUrl,
            )
        } catch (e: Exception) {
            onLog("gate-stage1-post", "POST _wp_http failed: ${e.message}")
            return null
        }
        jar.putAll(r1.cookies)
        if (r1.status !in 200..299) {
            onLog("gate-stage1-post", "unexpected HTTP ${r1.status}")
            return null
        }

        // Parse the landing form by input NAME, not attribute order or
        // quote style — the old regexes broke on any server-side change.
        val landing = Jsoup.parse(r1.text, GATE_HOST)
        val form = landing.selectFirst("form#landing")
            ?: landing.select("form").firstOrNull { f ->
                f.selectFirst("input[name=_wp_http2]") != null
            }
        if (form == null) {
            onLog("gate-stage1-parse", "landing form not found (chars=${r1.text.length})")
            return null
        }
        val rawAction = form.attr("action").trim()
        val action = MoviesModParser.resolveUrl(GATE_HOST, rawAction).ifBlank { GATE_HOST + "/" }
        val h2 = form.selectFirst("input[name=_wp_http2]")?.attr("value")?.trim()
        val token = form.selectFirst("input[name=token]")?.attr("value")?.trim()
        if (h2.isNullOrBlank() || token.isNullOrBlank()) {
            onLog("gate-stage1-parse", "missing _wp_http2 or token in landing form")
            return null
        }

        val r2 = try {
            page.post(
                action,
                headers = BROWSER_HEADERS,
                cookies = jar,
                data = mapOf("_wp_http2" to enc(h2), "token" to enc(token)),
                referer = GATE_HOST + "/",
            )
        } catch (e: Exception) {
            onLog("gate-stage2-post", "POST _wp_http2/token failed: ${e.message}")
            return null
        }
        jar.putAll(r2.cookies)

        // s_343('name','value') — accept single or double quotes and
        // arbitrary spacing; the old regex required exact single quotes.
        val cm = Regex("""s_343\s*\(\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]""")
            .find(r2.text)
        if (cm == null) {
            onLog("gate-stage2-parse", "s_343 cookie pair not found (chars=${r2.text.length})")
            return null
        }
        val cname = cm.groupValues[1]
        val cval = cm.groupValues[2]
        if (cname.isBlank() || cval.isBlank()) {
            onLog("gate-stage2-parse", "empty s_343 cookie name/value")
            return null
        }
        jar[cname] = cval

        val r3 = try {
            page.get(
                "$GATE_HOST/?go=$cname",
                headers = BROWSER_HEADERS,
                cookies = jar,
                referer = action,
            )
        } catch (e: Exception) {
            onLog("gate-stage3-get", "GET ?go= failed: ${e.message}")
            return null
        }
        if (r3.url.contains("driveseed.org")) return r3.url
        // Meta-refresh fallback, parsed via jsoup first (tolerant of
        // attribute order), regex second.
        Jsoup.parse(r3.text, GATE_HOST)
            .selectFirst("meta[http-equiv=refresh]")?.attr("content")
            ?.let { extractRefreshUrl(it) }
            ?.let { return it }
        return Regex("""url=(https?://[^'"'\s<>]+)""", RegexOption.IGNORE_CASE)
            .find(r3.text)?.groupValues?.getOrNull(1)
            ?.replace("&amp;", "&")?.trim()
            ?: run {
                onLog("gate-stage3-parse", "no driveseed target in ?go= response")
                null
            }
    }

    companion object {
        const val GATE_HOST = "https://cloud.unblockedgames.world"

        val BROWSER_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
        )

        /**
         * NiceHttp posts data maps with addEncoded: values go over the
         * wire exactly as given. Base64 tokens contain + / and =, so they
         * must be URL-encoded first or + arrives as a space and the gate
         * returns garbage.
         */
        fun enc(value: String): String =
            java.net.URLEncoder.encode(value, "UTF-8")

        fun extractRefreshUrl(metaContent: String): String? =
            Regex("""url\s*=\s*['"]?([^'"'\s]+)""", RegexOption.IGNORE_CASE)
                .find(metaContent)?.groupValues?.getOrNull(1)
                // Raw HTML carries &amp; entities (jsoup attr values are
                // already decoded); normalize before trimming delimiters.
                ?.replace("&amp;", "&")?.trim()
                ?.trimEnd(';')
                ?.takeIf { it.startsWith("http") }
    }
}

/** Seam for tests: the resolver never touches the real gate directly. */
interface GateBypass {
    suspend fun bypass(sidUrl: String): String?
}
