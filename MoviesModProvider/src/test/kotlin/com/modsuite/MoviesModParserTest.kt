package com.modsuite

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MoviesModParserTest {

    private val base = "https://moviesmod.zone"

    @Test
    fun `search cards keep poster title and type`() {
        val cards = MoviesModParser.parseCards(fixture("search.html"), base)
        assertEquals(2, cards.size)
        val movie = cards.first { !it.isSeries }
        assertTrue(movie.title.contains("Sinners"))
        assertEquals("https://moviesmod.zone/posters/sinners.jpg", movie.poster)
        assertTrue(cards.first { it.isSeries }.title.contains("Dark Matter"))
    }

    @Test
    fun `web series titles count as series`() {
        assertTrue(MoviesModParser.isSeriesTitle("Show Hindi Web Series"))
        assertTrue(MoviesModParser.isSeriesTitle("Show Season 2"))
        assertFalse(MoviesModParser.isSeriesTitle("Film (2024) Hindi"))
    }

    @Test
    fun `movie detail keeps hubs, drops tags and batch`() = runBlocking {
        val detail = MoviesModParser.parseDetail(fixture("movie-detail-archives.html"), base)
        assertFalse(detail.isSeries)
        assertEquals(2025, detail.year)
        assertEquals(listOf("480p", "720p", "1080p"), detail.qualities)
        // 3 modpro hubs; same-site tags + batch pack excluded.
        assertEquals(3, detail.rawLinks.size)
        assertTrue(detail.rawLinks.all { it.url.contains("links.modpro.blog/archives") })

        val groups = MoviesModParser.buildQualityGroups(detail) { archiveUrl ->
            MoviesModParser.parseArchiveLinks(fixture("archive-servers.html"), archiveUrl)
        }
        val sources = MoviesModParser.flattenMovieSources(groups)
        assertTrue(sources.isNotEmpty())
        assertTrue(sources.none { it.kind == SourceKind.ARCHIVE })
        assertEquals(setOf(480, 720, 1080), sources.map { it.quality }.toSet())
        val names = sources.map { mirrorName("MoviesMod", it.qualityLabel, it.server) }
        assertTrue(names.any { it == "MoviesMod · 1080p · Fast Server" })
    }

    @Test
    fun `series detail expands episode hubs per quality`() = runBlocking {
        val detail = MoviesModParser.parseDetail(fixture("series-detail.html"), base)
        assertTrue(detail.isSeries)
        // 2 "Episode Links" hubs; Batch/Zip excluded.
        assertEquals(2, detail.rawLinks.size)
        val groups = MoviesModParser.buildQualityGroups(detail) { archiveUrl ->
            MoviesModParser.parseArchiveLinks(fixture("archive.html"), archiveUrl)
        }.filter { it.episodes.isNotEmpty() }
        val plans = MoviesModParser.buildEpisodePlans(detail.title, groups, detail.poster)
        assertEquals(3, plans.size)
        assertEquals("S01E01", plans[0].name)
        assertTrue(plans.all { it.sources.size == 2 })
        assertTrue(plans[0].sources.all { it.url.contains("SID_EP1") })
    }

    @Test
    fun `movie payload falls back to detail url when empty`() {
        val url = "$base/download-sinners-2025-english-480p-720p-1080p/"
        assertEquals(url, MoviesModParser.moviePayloadData(url, emptyList()))
    }

    @Test
    fun `resolveUrl handles off-site and relative hrefs`() {
        val page = "https://links.modpro.blog/archives/132381"
        assertEquals(
            "https://cloud.unblockedgames.world/?sid=1",
            MoviesModParser.resolveUrl(page, "https://cloud.unblockedgames.world/?sid=1"),
        )
        assertEquals("https://links.modpro.blog/other", MoviesModParser.resolveUrl(page, "/other"))
    }
}
