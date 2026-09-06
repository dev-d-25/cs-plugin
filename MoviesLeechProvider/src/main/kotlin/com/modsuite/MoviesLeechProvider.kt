package com.modsuite

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MoviesLeechProvider : MainAPI() {
    override var mainUrl = "https://moviesleech.art"
    override var name = "MoviesLeech"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Section URLs follow the official homepage pattern (mainPageOf +
    // getMainPage): the site's homepage IS its "latest" feed (20 posts),
    // while /latest-movies/ is a near-empty leftover page (3 posts).
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Movies",
        "$mainUrl/movies/hindi-movies/" to "Hindi Movies",
        "$mainUrl/movies/south-movies/" to "South Movies",
        "$mainUrl/web-series/" to "Web Series"
    )

    private val page: PageClient by lazy { AppPageClient() }
    private val gate: GateBypass by lazy {
        CloudGateBypass(page) { stage, msg -> diag(stage, msg) }
    }
    private val resolver: LinkResolver by lazy {
        LinkResolver(page, gate, name) { stage, msg -> diag(stage, msg) }
    }

    private fun diag(stage: String, msg: String) {
        Log.i(TAG, "[$stage] $msg")
    }

    private fun diagErr(stage: String, msg: String, e: Throwable? = null) {
        Log.w(TAG, "[$stage] $msg${e?.let { ": ${it.message}" } ?: ""}")
    }

    // ------------------------------------------------------------------
    // Browse
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/$page/"
        val text = app.get(url).text
        val items = MoviesLeechParser.parseCards(text, url).map { card ->
            if (card.isSeries) {
                newTvSeriesSearchResponse(card.title, card.url, TvType.TvSeries) {
                    this.posterUrl = card.poster
                }
            } else {
                newMovieSearchResponse(card.title, card.url, TvType.Movie) {
                    this.posterUrl = card.poster
                }
            }
        }
        return newHomePageResponse(request.name, items).also {
            // Answers "why isn't X on the home page" from logcat alone.
            diag("home", "${request.name}: ${items.size} items <- $url")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val text = app.get(url).text
        return MoviesLeechParser.parseCards(text, url).map { card ->
            if (card.isSeries) {
                newTvSeriesSearchResponse(card.title, card.url, TvType.TvSeries) {
                    this.posterUrl = card.poster
                }
            } else {
                newMovieSearchResponse(card.title, card.url, TvType.Movie) {
                    this.posterUrl = card.poster
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Detail (movies first, then series)
    // ------------------------------------------------------------------

    /**
     * Archive hubs sometimes render empty (JS gate). Retry and keep the
     * first non-empty result; an empty return is a *diagnosed* outcome,
     * not a silent fallback into the wrong resolver.
     */
    private suspend fun expandArchive(archiveUrl: String): List<Pair<String, String>> {
        repeat(3) { attempt ->
            try {
                val res = page.get(archiveUrl, referer = mainUrl)
                val out = MoviesLeechParser.parseArchiveLinks(res.text, res.url)
                if (out.isNotEmpty()) {
                    diag("archive", "expanded $archiveUrl -> ${out.size} links")
                    return out
                }
                diag("archive", "empty episode list (attempt ${attempt + 1}/3) $archiveUrl")
            } catch (e: Exception) {
                diagErr("archive", "fetch failed (attempt ${attempt + 1}/3) $archiveUrl", e)
            }
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val res = app.get(url)
        val detail = MoviesLeechParser.parseDetail(res.text, res.url)

        // Every raw link expanded: archive hubs become episode links
        // (series hubs) or server mirrors (movie hubs); direct links pass
        // through. Movies must never store a raw ARCHIVE url — no
        // resolver stage owns it, which surfaced as "No Links Found".
        val groups = MoviesLeechParser.buildQualityGroups(detail, ::expandArchive)

        return if (detail.isSeries) {
            val usable = groups.filter { it.episodes.isNotEmpty() }
            val plans = MoviesLeechParser.buildEpisodePlans(detail.title, usable, detail.poster)
            diag(
                "load-series",
                "${detail.title}: ${plans.size} episodes from ${groups.size} quality groups",
            )
            val episodes = if (plans.isEmpty()) {
                // No episodes parsed: single entry pointing at the detail
                // page, which loadLinks() scans as a page (with logging),
                // instead of fabricating archive URLs for the extractor.
                diag("load-series", "no episodes parsed, detail-page fallback for $url")
                mutableListOf(newEpisode(url) { name = "Watch" })
            } else {
                plans.map { plan ->
                    newEpisode(EpisodePayload.encode(plan.sources)) {
                        this.name = plan.name
                        this.season = plan.season
                        this.episode = plan.number
                        this.posterUrl = plan.poster
                    }
                }.toMutableList()
            }
            newTvSeriesLoadResponse(detail.title, url, TvType.TvSeries, episodes) {
                this.posterUrl = detail.poster
                this.plot = detail.plot
                this.year = detail.year
            }
        } else {
            // Movies: EVERY expanded link becomes a mirror (Phase 5).
            // Movie hubs list servers, so one hub yields Fast Server /
            // G-Direct / OneDrive mirrors at that hub's quality.
            val sources = MoviesLeechParser.flattenMovieSources(groups)
            diag("load-movie", "${detail.title}: ${sources.size} mirrors")
            val data = MoviesLeechParser.moviePayloadData(url, sources)
            newMovieLoadResponse(detail.title, url, TvType.Movie, data) {
                this.posterUrl = detail.poster
                this.plot = detail.plot
                this.year = detail.year
            }
        }
    }

    // ------------------------------------------------------------------
    // Links
    // ------------------------------------------------------------------

    private suspend fun emitResolved(
        resolved: LinkResolver.ResolvedLink,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // One line per picker row: name -> final URL. This is what maps
        // "which entries differ" questions to concrete mirrors.
        diag("link", "${resolved.name} -> ${resolved.url}")
        callback.invoke(
            newExtractorLink(
                name,
                resolved.name,
                resolved.url
            ) {
                this.type = ExtractorLinkType.VIDEO
                this.referer = resolved.referer
                this.quality = getQualityFromName(resolved.qualityLabel)
            }
        )
    }

    /** Generic-extractor fallback for hosts with no dedicated resolver. */
    private suspend fun fallbackExtractor(
        target: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val ok = loadExtractor(target, referer, subtitleCallback, callback)
            diag("extractor-fallback", "target=$target ok=$ok")
            ok
        } catch (e: Exception) {
            diagErr("extractor-fallback", "target=$target", e)
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Structured payload (or legacy pipe data / single URL): this
        // episode's (or movie's) mirrors only.
        val trimmed = data.trim()
        val looksLikePayload = trimmed.startsWith("{") ||
            trimmed.contains("|||") ||
            (trimmed.startsWith("http") && !trimmed.contains("moviesleech.art"))
        if (looksLikePayload) {
            val sources = try {
                EpisodePayload.decode(trimmed)
            } catch (e: Exception) {
                diagErr("payload", "decode failed", e)
                emptyList()
            }
            if (sources.isEmpty()) {
                diag("payload", "empty sources for ${trimmed.take(80)}")
                return false
            }
            // Legacy stored data can be a raw archive-hub URL (pre-JSON
            // builds). Expand it like load() does, then resolve everything
            // the hub lists — same as the old detail scan did.
            val effective = if (sources.size == 1 && sources[0].kind == SourceKind.ARCHIVE) {
                val expanded = expandArchive(sources[0].url)
                diag("payload", "legacy archive url expanded to ${expanded.size} links")
                expanded.map { (_, href) ->
                    SourceCandidate(0, "HD", "Server 1", href, classifySource(href))
                }.ifEmpty { sources }
            } else sources
            var found = false
            for (link in resolver.resolveEpisode(effective)) {
                emitResolved(link, subtitleCallback, callback)
                found = true
            }
            // Mirror errors are already logged per-mirror inside the
            // resolver; only fall through when NOTHING resolved.
            if (found) return true
            diag("loadLinks", "no mirror resolved from ${effective.size} sources")
            return false
        }

        // Detail-page scan: collect candidate targets and resolve each
        // through the dedicated chain (gate/seed/driveseed). Hosts with no
        // dedicated resolver go to the generic extractor as a logged
        // last resort — never silently.
        val docUrl = data
        val docText = try {
            app.get(docUrl, referer = mainUrl).text
        } catch (e: Exception) {
            diagErr("loadLinks", "detail fetch failed: $docUrl", e)
            return false
        }
        val doc = org.jsoup.Jsoup.parse(docText, docUrl)
        val anchors = doc.select("a[href]")
        val labels = anchors.mapNotNull {
            val href = MoviesLeechParser.resolveUrl(docUrl, it.attr("href").trim())
            val label = it.text().trim()
            if (href.contains("cloud.unblockedgames.world") && label.isNotBlank()) {
                href to label
            } else null
        }.toMap()
        val targets = anchors.map { MoviesLeechParser.resolveUrl(docUrl, it.attr("href").trim()) }
            .filter { href ->
                href.contains("driveseed.org/file") || href.contains("video-seed.dev") ||
                    href.contains("video-gen.xyz") || href.contains("googleusercontent.com") ||
                    href.contains("cloud.unblockedgames.world") ||
                    href.contains("hubcloud") || href.contains("filepress") ||
                    href.contains("vcloud") || href.contains("gdflix") ||
                    href.contains("drive") || href.contains("archive")
            }
            .distinct()
            .ifEmpty { listOf(data) }

        var found = false
        for (target in targets) {
            val tag = (labels[target] ?: "")
                .replace(Regex("""[✅🚀⚡⬇️📂✔️]+"""), "").trim()
            if (tag.contains("comment", ignoreCase = true)) continue
            try {
                for (link in resolver.resolveTarget(target, "HD", tag)) {
                    emitResolved(link, subtitleCallback, callback)
                    found = true
                }
            } catch (e: LinkResolver.ResolveError) {
                diagErr("loadLinks", "[${e.stage}] ${e.message} target=$target")
                if (e.stage == "target") {
                    if (fallbackExtractor(target, docUrl, subtitleCallback, callback)) found = true
                }
            } catch (e: Exception) {
                diagErr("loadLinks", "target=$target", e)
            }
        }
        return found
    }

    companion object {
        private const val TAG = "MoviesLeech"
    }
}
