package com.modsuite

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Pure HTML parsing for MoviesLeech (plan Phase 3 seam).
 *
 * Every function here is deterministic: HTML string in, data out.
 * No CloudStream, no Android, no network — fully unit-testable with
 * fixtures under src/test/resources/fixtures/.
 */
object MoviesModParser {

    // Mirrors MainAPI.fixUrl semantics (verified against the CloudStream
    // library bytecode) but scoped to the *page* URL instead of mainUrl,
    // so archive-subdomain pages resolve their own relative links.
    // Absolute hrefs — everything observed live — pass through unchanged.
    fun resolveUrl(baseUrl: String, href: String): String {
        val h = href.trim()
        if (h.isEmpty()) return ""
        if (h.startsWith("http") || h.startsWith("{\"") || h.startsWith("[")) return h
        if (h.startsWith("//")) return "https:$h"
        val origin = Regex("""^(https?://[^/]+)""").find(baseUrl)?.groupValues?.getOrNull(1)
            ?: baseUrl.trimEnd('/')
        if (h.startsWith("/")) return origin + h
        val dir = baseUrl.substringBeforeLast("/", baseUrl)
        return "$dir/$h"
    }

    // ------------------------------------------------------------------
    // Search / home cards
    // ------------------------------------------------------------------

    data class CardResult(
        val title: String,
        val url: String,
        val poster: String?,
        val isSeries: Boolean,
    )

    fun parseCards(html: String, baseUrl: String): List<CardResult> {
        val doc: Document = Jsoup.parse(html, baseUrl)
        return doc.select("article, div.latestPost, div.post").mapNotNull { el ->
            val a = el.selectFirst("a[href*=/download-]") ?: return@mapNotNull null
            val title = el.selectFirst(".title, h2, h3")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val href = a.attr("href").trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val poster = el.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }.trim().ifBlank { null }
            }
            CardResult(
                title = title,
                url = resolveUrl(baseUrl, href),
                poster = poster,
                isSeries = isSeriesTitle(title),
            )
        }.distinctBy { it.url }
    }

    fun isSeriesTitle(title: String): Boolean =
        title.contains("season", ignoreCase = true) ||
            title.contains("episode", ignoreCase = true) ||
            title.contains("web series", ignoreCase = true)

    // ------------------------------------------------------------------
    // Detail page
    // ------------------------------------------------------------------

    data class RawLink(val label: String, val url: String)

    data class DetailResult(
        val title: String,
        val poster: String?,
        val plot: String?,
        val year: Int?,
        val isSeries: Boolean,
        /** Quality labels in title order, e.g. ["480p", "720p"]. */
        val qualities: List<String>,
        /** Playable raw links only (ZIP/batch/promo already excluded). */
        val rawLinks: List<RawLink>,
    )

    private val qualityInTitle = Regex("""(480p|720p|1080p|2160p|4K)""", RegexOption.IGNORE_CASE)
    private val yearInTitle = Regex("""\((19|20)\d{2}\)""")
    private val junkLabel = Regex("""batch|zip""", RegexOption.IGNORE_CASE)

    fun qualitiesFromTitle(title: String): List<String> =
        qualityInTitle.findAll(title)
            .map { it.value.lowercase().replace("4k", "2160p") }
            .toList()

    fun parseDetail(html: String, baseUrl: String): DetailResult {
        val doc: Document = Jsoup.parse(html, baseUrl)
        val title = doc.selectFirst("h1.single-title, h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown"
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.trim()?.ifBlank { null }
        val plot = doc.selectFirst("div.entry-content p, div.thecontent p")?.text()?.trim()
        val year = yearInTitle.find(title)?.value
            ?.removeSurrounding("(", ")")?.toIntOrNull()

        // Same-site links are tags/nav (1080p-movies, genre, year, ...),
        // never download targets — download hosts always live off-site.
        val pageOrigin = Regex("""^(https?://[^/]+)""").find(baseUrl)
            ?.groupValues?.getOrNull(1)?.lowercase() ?: ""
        val rawLinks = doc.select("div.entry-content a[href], div.thecontent a[href]")
            .map { it.text().trim() to it.attr("href").trim() }
            .filter { (label, href) ->
                if (href.isBlank()) return@filter false
                // Streaming only: ZIP/batch packs and promo links are
                // useless in the app; drop them before they become
                // episodes or mirrors.
                if (junkLabel.containsMatchIn(label)) return@filter false
                if (label.contains("comment", ignoreCase = true)) return@filter false
                if (href.contains("modlist")) return@filter false
                val url = resolveUrl(baseUrl, href)
                if (pageOrigin.isNotEmpty() && url.lowercase().startsWith(pageOrigin)) {
                    return@filter false
                }
                // Usable stages: archive hubs, the verification gate, the
                // DriveSeed/seed chain, and other known file hosts.
                isUsableHref(url)
            }
            .map { (label, href) -> RawLink(label, resolveUrl(baseUrl, href)) }

        return DetailResult(
            title = title,
            poster = poster,
            plot = plot,
            year = year,
            isSeries = isSeriesTitle(title),
            qualities = qualitiesFromTitle(title),
            rawLinks = rawLinks,
        )
    }

    // ------------------------------------------------------------------
    // Archive pages (per-episode hub links)
    // ------------------------------------------------------------------

    private val episodeLabel = Regex("""episode\s*\d+""", RegexOption.IGNORE_CASE)

    /**
     * Episode links from an archive hub page, in page order.
     * Archive pages observed live point DIRECTLY at the gate host, so
     * collection is by label, not by href pattern. Returns label+URL.
     * Empty list = JS-gated/empty hub (caller retries or degrades).
     */
    fun parseArchive(html: String, baseUrl: String): List<Pair<String, String>> =
        Jsoup.parse(html, baseUrl).select("a[href]").mapNotNull {
            val label = it.text().trim()
            val href = it.attr("href").trim()
            if (label.isNotBlank() && href.isNotBlank() &&
                episodeLabel.containsMatchIn(label)
            ) {
                label to resolveUrl(baseUrl, href)
            } else null
        }.distinctBy { it.second }

    /** Episode index (0-based) from a label like "Episode 12". */
    fun episodeIndexFromLabel(label: String): Int? =
        Regex("""episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(label)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.minus(1)

    /**
     * Server mirrors from a movie-style hub page. Movie hubs do not list
     * episodes; they list per-server gate links ("Fast Server (G-Drive)",
     * "G-Direct", "OneDrive", ...). Junk and nav links are excluded,
     * duplicates collapse by URL.
     */
    fun parseArchiveServers(html: String, baseUrl: String): List<Pair<String, String>> {
        // Same-site links are hub navigation (about, categories,
        // pagespeed), not mirrors — servers always live off-site.
        val baseHost = Regex("""^(https?://[^/]+)""").find(baseUrl)
            ?.groupValues?.getOrNull(1)?.lowercase() ?: ""
        return Jsoup.parse(html, baseUrl).select("a[href]").mapNotNull {
            val label = it.text().trim()
            val href = it.attr("href").trim()
            if (label.isBlank() || href.isBlank()) return@mapNotNull null
            if (junkLabel.containsMatchIn(label)) return@mapNotNull null
            if (label.contains("comment", ignoreCase = true)) return@mapNotNull null
            val url = resolveUrl(baseUrl, href)
            if (baseHost.isNotEmpty() && url.lowercase().startsWith(baseHost)) return@mapNotNull null
            if (!isUsableHref(url)) return@mapNotNull null
            label to url
        }.distinctBy { it.second }
    }

    private fun isUsableHref(url: String): Boolean {
        val u = url.lowercase()
        if (!u.startsWith("http")) return false
        return u.contains("archive") || u.contains("hubcloud") ||
            u.contains("filepress") || u.contains("vcloud") ||
            u.contains("gdflix") || u.contains("leech") ||
            u.contains("unblockedgames") || u.contains("driveseed") ||
            u.contains("googleusercontent") || u.contains("video-seed") ||
            u.contains("video-gen")
    }

    /**
     * One entry point for hub expansion: episode links when the hub lists
     * episodes (series), server links when it lists servers (movies).
     */
    fun parseArchiveLinks(html: String, baseUrl: String): List<Pair<String, String>> {
        val episodes = parseArchive(html, baseUrl)
        if (episodes.isNotEmpty()) return episodes
        return parseArchiveServers(html, baseUrl)
    }

    /** Season number from a detail title like "Show Season 2"; default 1. */
    fun seasonFromTitle(title: String): Int =
        Regex("""season\s*(\d+)""", RegexOption.IGNORE_CASE).find(title)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..99 }
            ?: 1

    // ------------------------------------------------------------------
    // Episode / movie source plans (pure; archive expansion done by caller)
    // ------------------------------------------------------------------

    /** One quality's expanded links: quality label + episode (label, url). */
    data class QualityGroup(
        val qualityLabel: String,
        /** Server name from the detail-page anchor (archive episode labels
         * are just "Episode N", so the group carries the real server). */
        val server: String,
        val episodes: List<Pair<String, String>>,
    )

    data class EpisodePlan(
        val season: Int,
        val number: Int,
        /** Display name, e.g. "S01E01". */
        val name: String,
        val poster: String?,
        /** This episode's mirrors only — never another episode's links. */
        val sources: List<SourceCandidate>,
    )

    /**
     * Align per-quality episode lists by index. Missing qualities do not
     * remove the episode; unequal counts are handled by index (a quality
     * with fewer entries simply has no mirror for later episodes).
     */
    fun buildEpisodePlans(
        title: String,
        qualityGroups: List<QualityGroup>,
        poster: String?,
    ): List<EpisodePlan> {
        val season = seasonFromTitle(title)
        val count = qualityGroups.maxOfOrNull { it.episodes.size } ?: 0
        val plans = mutableListOf<EpisodePlan>()
        for (i in 0 until count) {
            val sources = qualityGroups.mapNotNull { group ->
                group.episodes.getOrNull(i)?.let { (epLabel, url) ->
                    val q = qualityFromLabel(group.qualityLabel)
                    val epServer = serverNameFromLabel(epLabel)
                    SourceCandidate(
                        quality = q,
                        qualityLabel = group.qualityLabel.ifBlank { qualityLabel(q) },
                        server = if (epServer == "Server 1") group.server.ifBlank { epServer } else epServer,
                        url = url,
                        kind = classifySource(url),
                    )
                }
            }
            if (sources.isEmpty()) continue
            val number = i + 1
            plans.add(
                EpisodePlan(
                    season = season,
                    number = number,
                    name = "S%02dE%02d".format(season, number),
                    poster = poster,
                    sources = sources,
                )
            )
        }
        return plans
    }

    /**
     * Expand every raw detail link into a quality group. Archive hubs go
     * through [expand] (episode links for series hubs, server links for
     * movie hubs); direct links pass through untouched. Pure given the
     * expander, so tests inject a fake while the provider injects the
     * live retrying fetch.
     */
    suspend fun buildQualityGroups(
        detail: DetailResult,
        expand: suspend (archiveUrl: String) -> List<Pair<String, String>>,
    ): List<QualityGroup> =
        detail.rawLinks.mapIndexed { index, raw ->
            val qualityLabel = detail.qualities.getOrNull(index) ?: ""
            val expanded =
                if (classifySource(raw.url) == SourceKind.ARCHIVE && !raw.url.contains("#")) {
                    expand(raw.url)
                } else {
                    listOf(raw.label to raw.url)
                }
            QualityGroup(
                qualityLabel = qualityLabel,
                server = serverNameFromLabel(raw.label),
                episodes = expanded,
            )
        }

    /**
     * Movie mirrors: flatten every expanded group link into a candidate.
     * Archive hubs are already expanded by [buildQualityGroups], so the
     * payload never contains a raw ARCHIVE url (no resolver stage owns
     * those — storing them was the "No Links Found" cause for movies).
     */
    fun flattenMovieSources(groups: List<QualityGroup>): List<SourceCandidate> =
        groups.flatMap { group ->
            group.episodes.map { (epLabel, url) ->
                val q = qualityFromLabel(group.qualityLabel)
                val epServer = serverNameFromLabel(epLabel)
                SourceCandidate(
                    quality = q,
                    qualityLabel = group.qualityLabel.ifBlank { qualityLabel(q) },
                    server = if (epServer == "Server 1") group.server.ifBlank { epServer } else epServer,
                    url = url,
                    kind = classifySource(url),
                )
            }
        }

    /**
     * Movie episode data: encoded mirrors, or the detail URL when
     * expansion found nothing (loadLinks() then scans the page with
     * logging instead of failing on an empty payload).
     */
    fun moviePayloadData(pageUrl: String, sources: List<SourceCandidate>): String =
        if (sources.isEmpty()) pageUrl else EpisodePayload.encode(sources)

    /**
     * Server name from an anchor label, e.g. "✅ Fast Server 1080p" ->
     * "Fast Server". Unknown/empty labels fall back to a generic name so
     * mirrors stay distinguishable in the picker.
     */
    fun serverNameFromLabel(label: String): String {
        var name = label
            .replace(Regex("""[✅🚀⚡⬇️📂✔️]+"""), " ")
            .replace(qualityToken, " ")
            .replace(Regex("""\[.*?]"""), " ")
            .replace(Regex("""\(.*?\)"""), " ")
            .replace(Regex("""(?i)\b(episode\s*\d+|watch|download|click here.*|continue|verify.*)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim { it in " -–—:|" }
            .trim()
        if (name.isBlank()) name = "Server 1"
        return name
    }
}
