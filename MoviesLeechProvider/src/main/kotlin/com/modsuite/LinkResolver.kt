package com.modsuite

import org.jsoup.Jsoup

/**
 * Final-link resolution (plan Phase 3 seam): archive/redirect/final stages.
 *
 * Input: one episode's (or one movie's) [SourceCandidate] list.
 * Output: user-visible [ResolvedLink]s — the exact data the provider hands
 * to CloudStream's `loadLinks()` callback as VIDEO ExtractorLinks.
 *
 * Transport ([PageClient]) and the verification gate ([GateBypass]) are
 * injected, so the whole chain is testable with fixtures and fakes.
 * Stage failures throw [ResolveError] with the stage name; the
 * per-candidate loop logs them and keeps resolving the other mirrors
 * instead of failing the whole episode.
 */
class LinkResolver(
    private val page: PageClient,
    private val gate: GateBypass,
    private val providerName: String,
    private val onLog: (stage: String, message: String) -> Unit = { _, _ -> },
) {

    data class ResolvedLink(
        /** Final direct video/download URL. */
        val url: String,
        /** Picker-visible name, e.g. "MoviesLeech · 1080p · Fast Server". */
        val name: String,
        val qualityLabel: String,
        val referer: String,
    )

    class ResolveError(val stage: String, message: String, cause: Throwable? = null) :
        Exception(message, cause)

    // -- Entry points ----------------------------------------------------

    /** Resolve every mirror of one episode/movie payload. */
    suspend fun resolveEpisode(sources: List<SourceCandidate>): List<ResolvedLink> {
        val out = mutableListOf<ResolvedLink>()
        for (s in sources) {
            try {
                out += resolveTarget(s.url, s.qualityLabel, displayServer(s))
            } catch (e: ResolveError) {
                onLog(e.stage, "${e.message} (server=${s.server} url=${s.url})")
            } catch (e: Exception) {
                onLog("resolve", "unexpected: ${e.message} (url=${s.url})")
            }
        }
        return out
    }

    /** Resolve a single raw target (detail-page scan / legacy data path). */
    suspend fun resolveTarget(target: String, qualityLabel: String, tag: String): List<ResolvedLink> {
        val name = mirrorName(providerName, qualityLabel.ifBlank { "HD" }, tag.ifBlank { "Server 1" })
        return when {
            target.contains("cloud.unblockedgames.world") -> {
                val dest = gate.bypass(target)
                    ?: throw ResolveError("gate", "bypass returned no destination")
                when {
                    dest.contains("driveseed.org/file") ->
                        resolveDriveSeed(dest, name, qualityLabel)
                    dest.contains("driveseed.org/r") ->
                        resolveShortLink(dest, name, qualityLabel)
                    else -> resolveSeedOrDirect(dest, name, qualityLabel)
                }
            }
            target.contains("driveseed.org/file") ->
                resolveDriveSeed(target, name, qualityLabel)
            target.contains("driveseed.org/r") ->
                resolveShortLink(target, name, qualityLabel)
            target.contains("video-seed.dev") || target.contains("video-gen.xyz") ||
                target.contains("googleusercontent.com") ->
                resolveSeedPage(target, name, qualityLabel)
            target.startsWith("http") ->
                resolveSeedOrDirect(target, name, qualityLabel)
            else -> throw ResolveError("target", "not a usable URL: $target")
        }
    }

    // -- DriveSeed stages -------------------------------------------------

    suspend fun resolveDriveSeed(fileUrl: String, name: String, fallbackQuality: String): List<ResolvedLink> {
        val res = try {
            page.get(fileUrl, referer = fileUrl, headers = CloudGateBypass.BROWSER_HEADERS)
        } catch (e: Exception) {
            throw ResolveError("driveseed-fetch", "GET $fileUrl failed: ${e.message}", e)
        }
        if (res.status !in 200..299) {
            throw ResolveError("driveseed-fetch", "HTTP ${res.status} for $fileUrl")
        }
        val quality = parseDriveSeedQuality(res.text).ifBlank { fallbackQuality.ifBlank { "720p" } }
        val seeds = parseSeedMirrors(res.text)
        if (seeds.isEmpty()) {
            throw ResolveError("driveseed-parse", "no seed mirrors on $fileUrl")
        }
        val out = mutableListOf<ResolvedLink>()
        for (seed in seeds) {
            val seedName = mirrorName(providerName, quality, seedServerTag(seed))
            try {
                out += resolveSeedPage(seed, seedName, quality)
            } catch (e: ResolveError) {
                onLog(e.stage, "${e.message} (seed=$seed)")
            }
        }
        return out
    }

    suspend fun resolveShortLink(shortUrl: String, name: String, qualityLabel: String): List<ResolvedLink> {
        val res = try {
            page.get(
                shortUrl,
                referer = CloudGateBypass.GATE_HOST + "/",
                headers = CloudGateBypass.BROWSER_HEADERS,
            )
        } catch (e: Exception) {
            throw ResolveError("shortlink-fetch", "GET $shortUrl failed: ${e.message}", e)
        }
        if (res.url.contains("driveseed.org/file")) {
            return resolveDriveSeed(res.url, name, qualityLabel)
        }
        val doc = Jsoup.parse(res.text, res.url)
        doc.select("a[href]").map { it.attr("abs:href").trim() }
            .firstOrNull { it.contains("driveseed.org/file") }
            ?.let { return resolveDriveSeed(it, name, qualityLabel) }
        // /r pages answer with a ~70-byte JS redirect and no anchor, e.g.
        // <script>window.location.replace("/file/XYZ")</script>.
        val redirect = extractJsRedirect(res.text)
            ?: doc.selectFirst("meta[http-equiv=refresh]")?.attr("content")
                ?.let { CloudGateBypass.extractRefreshUrl(it) }
        val fileLink = redirect?.let { MoviesLeechParser.resolveUrl(res.url, it) } ?: ""
        if (!fileLink.contains("driveseed.org/file")) {
            throw ResolveError("shortlink-parse", "no /file/ link at $shortUrl")
        }
        return resolveDriveSeed(fileLink, name, qualityLabel)
    }

    // -- Seed / final stages ----------------------------------------------

    suspend fun resolveSeedPage(seedUrl: String, name: String, qualityLabel: String): List<ResolvedLink> {
        // Fast path: the final file rides in ?url= (no second request).
        extractSeedFinalFromUrl(seedUrl)?.let { finalUrl ->
            return listOf(emitFinal(finalUrl, seedUrl, name, qualityLabel))
        }
        val res = try {
            page.get(seedUrl, referer = seedUrl, headers = CloudGateBypass.BROWSER_HEADERS)
        } catch (e: Exception) {
            throw ResolveError("seed-fetch", "GET $seedUrl failed: ${e.message}", e)
        }
        // The seed host often 302s (cdn.video-gen.xyz ->
        // video-seed.dev/?url=<final>). Parse the LANDED url first.
        extractSeedFinalFromUrl(res.url)?.let { finalUrl ->
            return listOf(emitFinal(finalUrl, res.url, name, qualityLabel))
        }
        val finalUrl = Jsoup.parse(res.text, res.url).select("a[href]")
            .map { it.attr("abs:href").trim() }
            .firstOrNull { it.contains("googleusercontent.com") }
            ?: throw ResolveError("seed-parse", "no googleusercontent link at $seedUrl")
        return listOf(emitFinal(finalUrl, seedUrl, name, qualityLabel))
    }

    /** Last-resort passthrough for hosts with no dedicated resolver. */
    private suspend fun resolveSeedOrDirect(target: String, name: String, qualityLabel: String): List<ResolvedLink> {
        if (target.contains("googleusercontent.com")) {
            return listOf(emitFinal(target, target, name, qualityLabel))
        }
        throw ResolveError("target", "no resolver for host: $target")
    }

    /**
     * Byte-range confirmation of the final URL (plan Phase 5): proves the
     * link is a direct file without downloading gigabytes, then emits it.
     * An inconclusive probe (server ignores Range/HEAD) still emits — the
     * player is the final judge — but the outcome is logged.
     */
    private suspend fun emitFinal(
        finalUrl: String,
        referer: String,
        name: String,
        qualityLabel: String,
    ): ResolvedLink {
        try {
            val probe = page.probe(finalUrl, referer = referer)
            onLog(
                "probe",
                "HTTP ${probe.status} contentType=${probe.contentType} url=$finalUrl",
            )
        } catch (e: Exception) {
            onLog("probe", "inconclusive (${e.message}) url=$finalUrl")
        }
        return ResolvedLink(
            url = finalUrl,
            name = name,
            qualityLabel = qualityLabel.ifBlank { "HD" },
            referer = referer,
        )
    }

    private fun displayServer(s: SourceCandidate): String =
        s.server.ifBlank { seedServerTag(s.url).ifBlank { "Server 1" } }

    companion object {
        /**
         * JS-redirect target from pages like the /r shortlinks:
         * window.location.replace("/file/X"), location.href = "...", etc.
         * Pure — unit-tested with the captured 70-byte page.
         */
        fun extractJsRedirect(html: String): String? {
            val patterns = listOf(
                Regex("""window\.location\.replace\s*\(\s*['"]([^'"]+)['"]"""),
                Regex("""window\.location(?:\.href)?\s*=\s*['"]([^'"]+)['"]"""),
                Regex("""location\.(?:replace|assign)\s*\(\s*['"]([^'"]+)['"]"""),
                Regex("""location\.href\s*=\s*['"]([^'"]+)['"]"""),
            )
            return patterns.firstNotNullOfOrNull {
                it.find(html)?.groupValues?.getOrNull(1)?.trim()
            }?.takeIf { it.isNotBlank() }
        }
        /**
         * Final file URL from a seed link's ?url= param (URL-decoded).
         * Pure — unit-tested with fixtures.
         */
        fun extractSeedFinalFromUrl(seedUrl: String): String? =
            Regex("""[?&]url=(https?[^&#]+)""").find(seedUrl)
                ?.groupValues?.getOrNull(1)
                ?.let {
                    try {
                        java.net.URLDecoder.decode(it, "UTF-8")
                    } catch (e: Exception) {
                        null
                    }
                }
                ?.takeIf { it.contains("googleusercontent.com") }

        /**
         * All seed mirrors on a DriveSeed file page. Matches by button
         * text AND by known seed hosts (a file page can hold Instant
         * Download V1/V2 plus an r2.dev Cloud Download at once).
         * Seed tokens rotate per page load — always from this document.
         */
        fun parseSeedMirrors(html: String): List<String> =
            Jsoup.parse(html).select("a[href]").mapNotNull {
                val label = it.text()
                val href = it.attr("href").trim()
                if (href.isBlank()) return@mapNotNull null
                if (label.contains("instant download", ignoreCase = true) ||
                    href.contains("video-gen.xyz") || href.contains("r2.dev") ||
                    href.contains("video-seed.dev")
                ) href else null
            }.distinct()

        /** Quality from the file name, e.g. "...S03.E01.480p.Hindi..." */
        fun parseDriveSeedQuality(html: String): String {
            val nameNode = Jsoup.parse(html).selectFirst(":containsOwn(Name :)")
            val fileName = nameNode?.parent()?.text() ?: html.take(2000)
            return Regex("""(480p|720p|1080p|2160p|4k)""", RegexOption.IGNORE_CASE)
                .find(fileName)?.value?.lowercase()?.replace("4k", "2160p")
                ?: ""
        }

        /** Short tag for a seed URL so V1/V2/r2 mirrors stay distinct. */
        fun seedServerTag(seedUrl: String): String = when {
            seedUrl.contains("instant.video-gen.xyz") -> "Instant V2"
            seedUrl.contains("cdn.video-gen.xyz") -> "Instant V1"
            seedUrl.contains("video-seed.dev") -> "Seed"
            seedUrl.contains("r2.dev") -> "Cloud"
            else -> ""
        }
    }
}
