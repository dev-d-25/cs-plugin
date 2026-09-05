package com.modsuite

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MoviesModProvider : MainAPI() {
    override var mainUrl = "https://moviesmod.zone"
    private val mirrors = listOf(
        "https://moviesmod.zone"
    )
    override var name = "MoviesMod"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/dual-audio/" to "Dual Audio",
        "$mainUrl/web-series/" to "Web Series",
        "$mainUrl/anime/" to "Anime"
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
        val items = doc.select("article.latestPost, div.latestPost").mapNotNull { parseCard(it) }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}").document
        return doc.select("article.latestPost, div.latestPost").mapNotNull { parseCard(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.single-title, h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("div.entry-content p, div.thecontent p")?.text()?.trim()
        val year = Regex("""\((19|20)\d{2}\)""").find(title)?.value
            ?.removeSurrounding("(", ")")?.toIntOrNull()

        // Streaming only: zip/batch packs and promo links never play.
        val rawLinks = doc.select("div.entry-content a[href], div.thecontent a[href]").map {
            it.text().trim() to fixUrl(it.attr("href"))
        }.filter { (label, href) ->
            if (label.contains("batch", ignoreCase = true) ||
                label.contains("zip", ignoreCase = true) ||
                href.contains("modlist") || href.contains("mmodlist")
            ) return@filter false
            href.contains("archive") || href.contains("hubcloud") ||
                href.contains("filepress") || href.contains("vcloud") ||
                href.contains("gdflix") || href.contains("modpro")
        }
        // Qualities appear in title order: "480p [400MB] || 720p [800MB]".
        val qualities = Regex("""(480p|720p|1080p|2160p|4K)""", RegexOption.IGNORE_CASE)
            .findAll(title).map { it.value.lowercase().replace("4k", "2160p") }.toList()

        val isSeries = title.contains("season", ignoreCase = true) ||
            title.contains("episode", ignoreCase = true) ||
            title.contains("web series", ignoreCase = true)

        return if (isSeries) {
            // Per-episode entries, each carrying every quality as
            // "quality server|url" chunks joined by "|||".
            val episodes = mutableListOf<Episode>()
            if (rawLinks.isEmpty()) {
                episodes.add(newEpisode(url) { name = "Watch" })
            } else {
                val perQuality = rawLinks.mapIndexed { qi, (label, href) ->
                    val server = label.replace(Regex("""[✅🚀⚡⬇️📂✔️]+"""), "").trim()
                    val quality = qualities.getOrNull(qi) ?: "HD"
                    val tag = "$quality $server".trim()
                    if ((href.contains("links.modpro.blog/archives") ||
                            href.contains("leechpro.blog/archives")) && !href.contains("#")
                    ) {
                        tag to expandArchive(href)
                    } else {
                        tag to listOf("Watch" to href)
                    }
                }.filter { it.second.isNotEmpty() }
                val count = perQuality.maxOfOrNull { it.second.size } ?: 0
                if (count == 0) {
                    perQuality.forEach { (tag, subs) ->
                        subs.forEach { (_, href) ->
                            episodes.add(newEpisode("$tag|$href") { name = tag })
                        }
                    }
                } else {
                    for (i in 0 until count) {
                        val chunks = perQuality.mapNotNull { (tag, subs) ->
                            subs.getOrNull(i)?.let { (_, href) -> "$tag|$href" }
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

    private val episodeLabel = Regex("""episode\s*\d+""", RegexOption.IGNORE_CASE)

    // Archive hubs list their episodes as labeled sid links. Returns
    // (label, url) pairs, empty when the archive holds direct sources.
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

    // The gate hosts challenge non-browser clients. Every request in the
    // resolve chain carries desktop browser headers; without them the
    // app gets challenge pages instead of tokens.
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // Same middle as MoviesLeech (browser-verified): links.modpro.blog
    // archives -> cloud.unblockedgames ?sid= verification chain ->
    // driveseed.org/file/XXX -> rotating seed host (?url=<final>) ->
    // video-downloads.googleusercontent.com direct file (~3h, no resume).
    private suspend fun resolveDriveSeed(
        fileUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        tag: String = ""
    ): Boolean {
        return try {
            val doc = app.get(fileUrl, headers = browserHeaders, referer = fileUrl).document
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
            val res = app.get(seedUrl, headers = browserHeaders, referer = seedUrl)
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

    // NOTE: NiceHttp posts data maps with addEncoded, meaning values go
    // over the wire exactly as given. Base64 tokens contain + / and =,
    // so they must be URL-encoded first, or + arrives as a space and the
    // gate returns garbage. Python requests and URLSearchParams encode
    // automatically, which is why lab tests passed while the app failed.
    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    // Pure-HTTP bypass for the cloud.unblockedgames 3-click gate.
    // Port of the community runBypassChain (Modmovies Link Bypasser):
    //   POST _wp_http=<sid> -> parse landing form action + _wp_http2 +
    //   token -> POST them -> parse s_343('cookieName','cookieValue') ->
    //   GET ?go=<cookieName> with Cookie header -> meta refresh url= ->
    //   driveseed link. Verified live 2026-09-05. Tokens are SINGLE USE.
    private suspend fun bypassCloudLink(sidUrl: String): String? {
        val sid = Regex("[?&]sid=([^&]+)").find(sidUrl)?.groupValues?.getOrNull(1)
            ?: return null
        return try {
            // The shared app client keeps no cookies between calls, but the
            // gate sets session cookies that later stages require. Carry a
            // jar manually, exactly like a browser session would.
            val jar = mutableMapOf<String, String>()
            val r1 = app.post(
                "https://cloud.unblockedgames.world/",
                headers = browserHeaders,
                cookies = jar,
                data = mapOf("_wp_http" to enc(sid)),
                referer = sidUrl
            )
            jar.putAll(r1.cookies)
            val r1text = r1.text
            val action = Regex("""id="landing"[^>]*action="([^"]+)"""")
                .find(r1text)?.groupValues?.getOrNull(1) ?: return null
            val h2 = Regex("""name="_wp_http2"\s+value="([^"]+)"""")
                .find(r1text)?.groupValues?.getOrNull(1) ?: return null
            val token = Regex("""name="token"\s+value="([^"]+)"""")
                .find(r1text)?.groupValues?.getOrNull(1) ?: return null
            val r2 = app.post(
                action,
                headers = browserHeaders,
                cookies = jar,
                data = mapOf("_wp_http2" to enc(h2), "token" to enc(token)),
                referer = "https://cloud.unblockedgames.world/"
            )
            jar.putAll(r2.cookies)
            val r2text = r2.text
            val cm = Regex("""s_343\s*\(\s*'([^']+)'\s*,\s*'([^']+)'""").find(r2text)
                ?: return null
            val cname = cm.groupValues[1]
            val cval = cm.groupValues[2]
            jar[cname] = cval
            val r3 = app.get(
                "https://cloud.unblockedgames.world/?go=$cname",
                headers = browserHeaders,
                cookies = jar,
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
            val res = app.get(shortUrl, headers = browserHeaders, referer = "https://cloud.unblockedgames.world/")
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
        // Multi-source episode data: "1080p Fast Server|url|||720p|url".
        if (data.contains("|||")) {
            var multiFound = false
            for (entry in data.split("|||")) {
                val tag = entry.substringBefore("|", "").trim()
                val url = entry.substringAfter("|", "").trim()
                if (url.isBlank()) continue
                try {
                    if (resolveTarget(url, mainUrl, tag, subtitleCallback, callback)) {
                        multiFound = true
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            return multiFound
        }
        if (data.contains("driveseed.org/file")) {
            return resolveDriveSeed(data, subtitleCallback, callback)
        }
        if (data.contains("video-seed.dev") || data.contains("video-gen.xyz") ||
            (data.contains("?url=") && data.contains("googleusercontent.com"))
        ) {
            return resolveSeedPage(data, "720p", subtitleCallback, callback)
        }
        var found = false
        // data may be the detail page or an intermediate archive link
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
                // try next host, these link pages die often
                continue
            }
        }
        return found
    }
}
