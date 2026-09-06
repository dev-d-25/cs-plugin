package com.modsuite

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for the "No Links Found" report on movies.
 *
 * Real movie detail pages link to ARCHIVE hubs (not direct files), and
 * real movie hubs list SERVER links (Fast Server, G-Direct, OneDrive),
 * not episode links. v5 stored the raw hub URLs in the movie payload,
 * which no resolver stage owns -> zero links -> "No Links Found".
 */
class MovieArchiveTest {

    private val base = "https://moviesleech.art"

    @Test
    fun `movie hubs expose server mirrors, deduped by url`() {
        val servers = MoviesLeechParser.parseArchiveServers(
            fixture("archive-servers.html"), "https://leechpro.blog/archives/1071",
        )
        // "Other Download Links" shares G-Direct's URL -> dropped.
        assertEquals(4, servers.size)
        assertTrue(servers.any { it.first.contains("Fast Server") })
        assertTrue(servers.any { it.first.contains("OneDrive") })
        assertTrue(servers.all { it.second.contains("cloud.unblockedgames.world") })
    }

    @Test
    fun `archive links prefer episodes, fall back to servers`() {
        val fromEpisodes = MoviesLeechParser.parseArchiveLinks(
            fixture("archive.html"), "https://leechpro.blog/archives/x",
        )
        assertEquals(3, fromEpisodes.size)
        assertEquals("Episode 1", fromEpisodes[0].first)

        val fromServers = MoviesLeechParser.parseArchiveLinks(
            fixture("archive-servers.html"), "https://leechpro.blog/archives/1071",
        )
        assertEquals(4, fromServers.size)
    }

    @Test
    fun `movie groups flatten to resolvable gate mirrors`() = runBlocking {
        // Live movie pages link ARCHIVE hubs labeled "Download Links".
        val detail = MoviesLeechParser.parseDetail(fixture("movie-detail-archives.html"), base)
        // Same-site tag links (1080p/720p/480p-movies) are nav, not mirrors.
        assertEquals(3, detail.rawLinks.size)
        assertTrue(detail.rawLinks.all { classifySource(it.url) == SourceKind.ARCHIVE })

        val groups = MoviesLeechParser.buildQualityGroups(detail) { archiveUrl ->
            MoviesLeechParser.parseArchiveLinks(
                fixture("archive-servers.html"), archiveUrl,
            )
        }
        val sources = MoviesLeechParser.flattenMovieSources(groups)
        // Every mirror must be owned by a resolver stage: no raw ARCHIVE
        // URLs may reach the payload (that was the No Links Found cause).
        assertTrue(sources.isNotEmpty())
        assertTrue(sources.none { it.kind == SourceKind.ARCHIVE })
        assertTrue(sources.all { it.kind == SourceKind.GATE })
        // Qualities follow title order; servers come from the hub labels.
        assertEquals(setOf(480, 720, 1080), sources.map { it.quality }.toSet())
        val names = sources.map { mirrorName("MoviesLeech", it.qualityLabel, it.server) }
        assertTrue(names.any { it.contains("Fast Server") })
        assertTrue(names.any { it.contains("OneDrive") })
    }

    @Test
    fun `movie payload falls back to detail url when expansion is empty`() {
        val url = "$base/download-gulity-2020-hindi-movie/"
        assertEquals(url, MoviesLeechParser.moviePayloadData(url, emptyList()))
        val sources = listOf(
            SourceCandidate(720, "720p", "Fast Server", "https://cloud.unblockedgames.world/?sid=1", SourceKind.GATE),
        )
        val data = MoviesLeechParser.moviePayloadData(url, sources)
        assertEquals(sources, EpisodePayload.decode(data))
    }

    @Test
    fun `direct non-archive movie links skip expansion`() = runBlocking {
        val detail = MoviesLeechParser.DetailResult(
            title = "Film 720p",
            poster = null,
            plot = null,
            year = null,
            isSeries = false,
            qualities = listOf("720p"),
            rawLinks = listOf(
                MoviesLeechParser.RawLink("HubCloud", "https://hubcloud.art/drive/x"),
            ),
        )
        var expanded = false
        val groups = MoviesLeechParser.buildQualityGroups(detail) {
            expanded = true
            emptyList()
        }
        assertFalse(expanded)
        val sources = MoviesLeechParser.flattenMovieSources(groups)
        assertEquals(1, sources.size)
        assertEquals("https://hubcloud.art/drive/x", sources[0].url)
    }
}
