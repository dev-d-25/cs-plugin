package com.modsuite

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MoviesLeechProvider : MainAPI() {
    override var mainUrl = "https://moviesleech.art"
    override var name = "MoviesLeech"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/latest-movies/" to "Latest Movies",
        "$mainUrl/movies/hindi-movies/" to "Hindi Movies",
        "$mainUrl/movies/south-movies/" to "South Movies",
        "$mainUrl/web-series/" to "Web Series"
    )

    private fun parseCard(el: org.jsoup.nodes.Element): SearchResponse? {
        val a = el.selectFirst("a[href*=/download-]") ?: return null
        val title = el.selectFirst(".title, h2, h3")?.text()?.trim() ?: return null
        if (title.isBlank()) return null
        val href = fixUrl(a.attr("href"))
        val poster = el.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        val type = if (title.contains("season", ignoreCase = true)) TvType.TvSeries else TvType.Movie
        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/$page/"
        val doc = app.get(url).document
        val items = doc.select("article, div.latestPost, div.post").mapNotNull {
            // homepage uses article cards, be lenient here
            parseCard(it)
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}").document
        return doc.select("article, div.latestPost").mapNotNull { parseCard(it) }
            .distinctBy { it.url }
    }

    // Browser-verified: leechpro archive pages list Episode 1..N links
    // pointing DIRECTLY at cloud.unblockedgames.world/?sid=... So collect
    // by label, not by href pattern.
    private val episodeLabel = Regex("""episode\s*\d+""", RegexOption.IGNORE_CASE)

    // leechpro archive pages render the episode list after a 2s JS timer.
    // Expand them server-side so each Episode points at its own link
    // instead of the hub page.
    private suspend fun expandArchive(archiveUrl: String): List<Pair<String, String>> {
        // Archive hubs are flaky: they sometimes render empty (JS gate).
        // Retry and keep the first non-empty result.
        repeat(3) {
            try {
                val doc = app.get(archiveUrl, referer = mainUrl).document
                val out = doc.select("a[href]").mapNotNull {
                    val href = fixUrl(it.attr("href"))
                    val label = it.text().trim()
                    if (label.isNotBlank() && episodeLabel.containsMatchIn(label)) {
                        label to href
                    } else null
                }.distinctBy { it.second }
                if (out.isNotEmpty()) return out
            } catch (e: Exception) {
            }
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {        val doc = app.get(url).document
        val title = doc.selectFirst("h1.single-title, h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("div.entry-content p, div.thecontent p")?.text()?.trim()
        val year = Regex("""\((19|20)\d{2}\)""").find(title)?.value
            ?.removeSurrounding("(", ")")?.toIntOrNull()

        // Streaming only: zip/batch packs and promo links are useless in
        // the app, drop them before they ever become episodes.
        val rawLinks = doc.select("div.entry-content a[href], div.thecontent a[href]").map {
            it.text().trim() to fixUrl(it.attr("href"))
        }.filter { (label, href) ->
            if (label.contains("batch", ignoreCase = true) ||
                label.contains("zip", ignoreCase = true) ||
                href.contains("modlist")
            ) return@filter false
            href.contains("archive") || href.contains("hubcloud") ||
                href.contains("filepress") || href.contains("vcloud") ||
                href.contains("gdflix") || href.contains("leech")
        }
        // Qualities appear in title order: "480p [500MB] || 720p [1.4GB]".
        val qualities = Regex("""(480p|720p|1080p|2160p|4K)""", RegexOption.IGNORE_CASE)
            .findAll(title).map { it.value.lowercase().replace("4k", "2160p") }.toList()

        val isSeries = title.contains("season", ignoreCase = true) ||
            title.contains("episode", ignoreCase = true)

        return if (isSeries) {
            // Per-episode entries. Each episode carries every quality as
            // "quality|url" chunks joined by "|||", so tapping Episode 1
            // offers 480p / 720p / 1080p mirrors like the reference apps.
            val episodes = mutableListOf<Episode>()
            if (rawLinks.isEmpty()) {
                episodes.add(newEpisode(url) { name = "Watch" })
            } else {
                val perQuality = rawLinks.mapIndexed { qi, (_, href) ->
                    val quality = qualities.getOrNull(qi) ?: "HD"
                    if (href.contains("leechpro.blog/archives") && !href.contains("#")) {
                        quality to expandArchive(href)
                    } else {
                        quality to listOf("Watch" to href)
                    }
                }.filter { it.second.isNotEmpty() }
                val count = perQuality.maxOfOrNull { it.second.size } ?: 0
                if (count == 0) {
                    perQuality.forEach { (quality, subs) ->
                        subs.forEach { (_, href) ->
                            episodes.add(newEpisode(href) { name = quality })
                        }
                    }
                } else {
                    for (i in 0 until count) {
                        val chunks = perQuality.mapNotNull { (quality, subs) ->
                            subs.getOrNull(i)?.let { (_, href) -> "$quality|$href" }
                        }
                        if (chunks.isEmpty()) continue
                        episodes.add(newEpisode(chunks.joinToString("|||")) {
                            name = "Episode ${i + 1}"
                        })
                    }
                }
            }
            if (episodes.isEmpty()) episodes.add(newEpisode(url) { name = "Watch" })
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            val data = rawLinks.firstOrNull()?.second ?: url
            newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    // Full chain, reproduced end to end in a real browser:
    //   leechpro archive -> episode link (cloud.unblockedgames ?sid=) ->
    //   landing auto-submit -> START VERIFICATION -> article page
    //   VERIFY TO CONTINUE -> CLICK HERE TO CONTINUE -> GO TO DOWNLOAD
    //   (?go=pepe-XXX) -> driveseed.org/file/XXX (INSTANT DOWNLOAD anchor,
    //   href host rotates: cdn.video-gen.xyz, video-seed.dev, ...)
    //   -> seed page ?url=<final> -> video-downloads.googleusercontent.com
    //   (direct file, HTTP 200 video/*, valid ~3h, no pause/resume).
    private suspend fun resolveDriveSeed(
        fileUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        tag: String = ""
    ): Boolean {
        return try {
            val doc = app.get(fileUrl, referer = fileUrl).document
            // file name carries quality, e.g. Mirzapur.S03.E01.480p.Hindi...
            val fileName = doc.selectFirst(":containsOwn(Name :)")?.parent()?.text() ?: ""
            val quality = Regex("""(480p|720p|1080p|2160p|4k)""", RegexOption.IGNORE_CASE)
                .find(fileName)?.value?.lowercase()?.replace("4k", "2160p") ?: "720p"
            // Match by button text AND by known seed hosts: a file page can
            // hold several mirrors at once (Instant Download V2 on
            // instant.video-gen.xyz, V1 on cdn.video-gen.xyz, sometimes an
            // r2.dev Cloud Download). Resolve each so every forwardable
            // link is emitted. Seed tokens rotate per page load, so always
            // use fresh ones from this same document.
            val seedUrls = doc.select("a[href]").mapNotNull {
                val label = it.text()
                val href = fixUrl(it.attr("href"))
                if (label.contains("instant download", ignoreCase = true) ||
                    href.contains("video-gen.xyz") || href.contains("r2.dev")
                ) href else null
            }.distinct()
            if (seedUrls.isEmpty()) {
                return loadExtractor(fileUrl, fileUrl, subtitleCallback, callback)
            }
            var found = false
            for (seedUrl in seedUrls) {
                try {
                    if (resolveSeedPage(seedUrl, quality, subtitleCallback, callback, tag)) {
                        found = true
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            return found
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun resolveSeedPage(
        seedUrl: String,
        qualityLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        tag: String = ""
    ): Boolean {
        // Mirror tag travels into the visible link name.
        val linkName = "$name $qualityLabel $tag".replace(Regex("""\s+"""), " ").trim()
        return try {
            // Seed pages carry the final file in the ?url= query param,
            // e.g. video-seed.dev/?url=https://video-downloads.googleusercontent.com/...
            Regex("""[?&]url=(https?[^&]+)""").find(seedUrl)?.groupValues
                ?.getOrNull(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?.takeIf { it.contains("googleusercontent.com") }?.let { finalUrl ->
                    callback.invoke(
                        newExtractorLink(
                            name,
                            linkName,
                            finalUrl
                        ) {
                            this.type = ExtractorLinkType.VIDEO
                            this.referer = seedUrl
                            this.quality = getQualityFromName(qualityLabel)
                        }
                    )
                    return true
                }
            val res = app.get(seedUrl, referer = seedUrl)
            // The seed host often 302-redirects (cdn.video-gen.xyz ->
            // video-seed.dev/?url=<final>). Parse the landed URL first.
            Regex("""[?&]url=(https?://.+)$""").find(res.url)?.groupValues
                ?.getOrNull(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?.takeIf { it.contains("googleusercontent.com") }?.let { finalUrl ->
                    callback.invoke(
                        newExtractorLink(
                            name,
                            linkName,
                            finalUrl
                        ) {
                            this.type = ExtractorLinkType.VIDEO
                            this.referer = res.url
                            this.quality = getQualityFromName(qualityLabel)
                        }
                    )
                    return true
                }
            val doc = res.document
            val finalUrl = doc.select("a[href]").map { fixUrl(it.attr("href")) }
                .firstOrNull { it.contains("googleusercontent.com") }
                ?: return loadExtractor(seedUrl, seedUrl, subtitleCallback, callback)
            callback.invoke(
                newExtractorLink(
                    name,
                    linkName,
                    finalUrl
                ) {
                    this.type = ExtractorLinkType.VIDEO
                    this.referer = seedUrl
                    this.quality = getQualityFromName(qualityLabel)
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    // Pure-HTTP bypass for the cloud.unblockedgames 3-click gate.
    // Port of the community runBypassChain (Modmovies Link Bypasser,
    // also documented in bypass-all-shortlinks and uBO discussions):
    //   POST _wp_http=<sid> -> parse landing form action + _wp_http2 +
    //   token -> POST them -> parse s_343('cookieName','cookieValue') ->
    //   GET ?go=<cookieName> with Cookie header -> meta refresh url= ->
    //   driveseed link. No WebView, no clicks. Verified live 2026-09-05:
    //   Episode sid -> ... -> https://driveseed.org/r?key=..&id=.. pattern.
    // Tokens are SINGLE USE: never fetch ?go= twice ("Do Not Double Click").
    private suspend fun bypassCloudLink(sidUrl: String): String? {
        val sid = Regex("[?&]sid=([^&]+)").find(sidUrl)?.groupValues?.getOrNull(1)
            ?: return null
        return try {
            val r1 = app.post(
                "https://cloud.unblockedgames.world/",
                data = mapOf("_wp_http" to sid),
                referer = sidUrl
            ).text
            val action = Regex("""id="landing"[^>]*action="([^"]+)"""")
                .find(r1)?.groupValues?.getOrNull(1) ?: return null
            val h2 = Regex("""name="_wp_http2"\s+value="([^"]+)"""")
                .find(r1)?.groupValues?.getOrNull(1) ?: return null
            val token = Regex("""name="token"\s+value="([^"]+)"""")
                .find(r1)?.groupValues?.getOrNull(1) ?: return null
            val r2 = app.post(
                action,
                data = mapOf("_wp_http2" to h2, "token" to token),
                referer = "https://cloud.unblockedgames.world/"
            ).text
            val cm = Regex("""s_343\s*\(\s*'([^']+)'\s*,\s*'([^']+)'""").find(r2)
                ?: return null
            val cname = cm.groupValues[1]
            val cval = cm.groupValues[2]
            val r3 = app.get(
                "https://cloud.unblockedgames.world/?go=$cname",
                headers = mapOf("Cookie" to "$cname=$cval"),
                referer = action
            )
            if (r3.url.contains("driveseed.org")) return r3.url
            Regex("""url=(https?://[^\s"']+)""", RegexOption.IGNORE_CASE)
                .find(r3.text)?.groupValues?.getOrNull(1)
                ?.replace("&amp;", "&")?.trim()
        } catch (e: Exception) {
            null
        }
    }

    // Short /r?key=..&id=.. links: follow once, continue on /file/.
    private suspend fun resolveShortLink(
        shortUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        tag: String = ""
    ): Boolean {
        return try {
            val res = app.get(shortUrl, referer = "https://cloud.unblockedgames.world/")
            if (res.url.contains("driveseed.org/file")) {
                return resolveDriveSeed(res.url, subtitleCallback, callback, tag)
            }
            val fileLink = res.document.select("a[href]").map { fixUrl(it.attr("href")) }
                .firstOrNull { it.contains("driveseed.org/file") }
                ?: return false
            resolveDriveSeed(fileLink, subtitleCallback, callback, tag)
        } catch (e: Exception) {
            false
        }
    }

    // One target in, one verdict out. Shared by the multi-source branch
    // ("quality|url|||quality|url") and the normal archive scan below.
    private suspend fun resolveTarget(
        target: String,
        referer: String,
        tag: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            when {
                target.contains("cloud.unblockedgames.world") -> {
                    val dest = bypassCloudLink(target)
                    if (dest == null) {
                        loadExtractor(target, referer, subtitleCallback, callback)
                    } else if (dest.contains("driveseed.org/file")) {
                        resolveDriveSeed(dest, subtitleCallback, callback, tag)
                    } else if (dest.contains("driveseed.org/r")) {
                        resolveShortLink(dest, subtitleCallback, callback, tag)
                    } else {
                        loadExtractor(dest, referer, subtitleCallback, callback)
                    }
                }
                target.contains("driveseed.org/file") ->
                    resolveDriveSeed(target, subtitleCallback, callback, tag)
                target.contains("video-seed.dev") || target.contains("video-gen.xyz") ||
                    (target.contains("googleusercontent.com")) ->
                    resolveSeedPage(target, "720p", subtitleCallback, callback, tag)
                else -> loadExtractor(target, referer, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Multi-source episode data: "480p|url|||720p|url|||1080p|url".
        // Each chunk resolves to its own mirror entry.
        if (data.contains("|||")) {
            var found = false
            for (entry in data.split("|||")) {
                val tag = entry.substringBefore("|", "").trim()
                val url = entry.substringAfter("|", "").trim()
                if (url.isBlank()) continue
                try {
                    if (resolveTarget(url, mainUrl, tag, subtitleCallback, callback)) {
                        found = true
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            return found
        }
        // Direct DriveSeed / seed links skip straight to the resolver.
        // Seed hosts rotate (video-seed.dev, cdn.video-gen.xyz, ...) so
        // match broadly here and let resolveSeedPage sort it out.
        if (data.contains("driveseed.org/file")) {
            return resolveDriveSeed(data, subtitleCallback, callback)
        }
        if (data.contains("video-seed.dev") || data.contains("video-gen.xyz") ||
            (data.contains("?url=") && data.contains("googleusercontent.com"))
        ) {
            return resolveSeedPage(data, "720p", subtitleCallback, callback)
        }
        var found = false
        val doc = try {
            app.get(data, referer = mainUrl).document
        } catch (e: Exception) {
            return false
        }
        val anchors = doc.select("a[href]")
        // Server labels on archive pages (Fast Server, Server 2, OneDrive,
        // Episode N) so every mirror is named in the final list.
        val labels = anchors.mapNotNull {
            val href = fixUrl(it.attr("href"))
            val label = it.text().trim()
            if (href.contains("cloud.unblockedgames.world") && label.isNotBlank()) {
                href to label
            } else null
        }.toMap()
        val targets = anchors.map { fixUrl(it.attr("href")) }
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

        for (target in targets) {
            // Server tag keeps mirrors distinguishable: "Fast Server",
            // "Server 2", "OneDrive", "Episode 3", ...
            val tag = (labels[target] ?: "")
                .replace(Regex("""[✅🚀⚡⬇️📂✔️]+"""), "").trim()
            // Skip junk anchors (comment-section links share the gate host).
            if (tag.contains("comment", ignoreCase = true)) continue
            try {
                if (resolveTarget(target, data, tag, subtitleCallback, callback)) {
                    found = true
                }
            } catch (e: Exception) {
                continue
            }
        }
        return found
    }
}
