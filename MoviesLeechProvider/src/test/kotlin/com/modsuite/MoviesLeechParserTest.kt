package com.modsuite

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MoviesLeechParserTest {

    private val base = "https://moviesleech.art"

    // -- Search / home cards -------------------------------------------

    @Test
    fun `search cards keep poster title and type, drop dupes and junk`() {
        val cards = MoviesLeechParser.parseCards(fixture("search.html"), base)
        assertEquals(2, cards.size)

        val movie = cards.first { !it.isSeries }
        assertEquals("Movie Abc (2024) Hindi 720p", movie.title)
        assertEquals("https://moviesleech.art/download-movie-abc-2024/", movie.url)
        // data-src wins over placeholder src
        assertEquals("https://moviesleech.art/posters/abc.jpg", movie.poster)

        val series = cards.first { it.isSeries }
        assertEquals("Show Xyz Season 1 Hindi", series.title)
        assertEquals("https://moviesleech.art/posters/xyz.jpg", series.poster)
    }

    @Test
    fun `homepage cards parse in full`() {
        // Regression: the "Latest Movies" section pointed at
        // /latest-movies/ (3 posts) instead of the homepage feed (20).
        // Homepage cards use h2.title > a and plain img src.
        val cards = MoviesLeechParser.parseCards(fixture("home.html"), base)
        assertEquals(3, cards.size)
        assertTrue(cards[0].title.contains("Mirzapur"))
        assertEquals(
            "https://moviesleech.art/wp-content/uploads/2026/09/mirzapur.jpg",
            cards[0].poster,
        )
        assertTrue(cards.none { it.isSeries })
    }

    // -- Movie detail ---------------------------------------------------

    @Test
    fun `movie detail keeps every playable link and drops zip-promo`() {
        val detail = MoviesLeechParser.parseDetail(fixture("movie-detail.html"), base)
        assertTrue(detail.title.contains("Movie Abc"))
        assertEquals(2024, detail.year)
        assertEquals("https://moviesleech.art/posters/abc.jpg", detail.poster)
        assertFalse(detail.isSeries)
        assertEquals(listOf("480p", "720p", "1080p"), detail.qualities)
        // 3 playable links; batch ZIP + modlist promo excluded
        assertEquals(3, detail.rawLinks.size)
        assertTrue(detail.rawLinks.none { it.url.contains("batch") || it.url.contains("modlist") })
    }

    @Test
    fun `movie sources retain all qualities and servers`() = runBlocking {
        val detail = MoviesLeechParser.parseDetail(fixture("movie-detail.html"), base)
        var expanded = false
        val groups = MoviesLeechParser.buildQualityGroups(detail) {
            expanded = true
            emptyList()
        }
        // Direct links skip expansion entirely.
        assertFalse(expanded)
        val sources = MoviesLeechParser.flattenMovieSources(groups)
        // Regression: the old code kept only rawLinks.firstOrNull().
        assertEquals(3, sources.size)
        assertEquals(listOf(480, 720, 1080), sources.map { it.quality })
        val names = sources.map { mirrorName("MoviesLeech", it.qualityLabel, it.server) }
        assertTrue(names.any { it == "MoviesLeech · 1080p · Fast Server" })
        assertTrue(names.any { it.contains("Server 2") })
    }

    @Test
    fun `4k title variants normalize to 2160p`() {
        assertEquals(listOf("2160p"), MoviesLeechParser.qualitiesFromTitle("Film 4K Hindi"))
        assertEquals(2160, qualityFromLabel("4K"))
        assertEquals(0, qualityFromLabel("CAM"))
        assertEquals("HD", qualityLabel(0))
    }

    // -- Series detail + episode plans ----------------------------------

    @Test
    fun `series episodes align by index with S01E01 names`() {
        val detail = MoviesLeechParser.parseDetail(fixture("series-detail.html"), base)
        assertTrue(detail.isSeries)
        assertEquals(3, detail.rawLinks.size) // 2 archives + 1 direct (zip excluded)

        val archiveEps = MoviesLeechParser.parseArchive(
            fixture("archive.html"), "https://leechpro.blog/archives/arc480",
        )
        val groups = listOf(
            MoviesLeechParser.QualityGroup("480p", "Fast Server", archiveEps),
            MoviesLeechParser.QualityGroup("720p", "Server 2", archiveEps),
            MoviesLeechParser.QualityGroup("1080p", "OneDrive", listOf("OneDrive Direct" to "https://cloud.unblockedgames.world/?sid=SID_DIRECT")),
        )
        val plans = MoviesLeechParser.buildEpisodePlans("Show Xyz Season 1", groups, null)
        assertEquals(3, plans.size)
        assertEquals("S01E01", plans[0].name)
        assertEquals(1, plans[0].season)
        assertEquals(1, plans[0].number)

        // Episode 1 carries ONLY episode-1 links (no leakage from ep2/3).
        val ep1Urls = plans[0].sources.map { it.url }
        assertEquals(3, ep1Urls.size)
        assertTrue(ep1Urls.all { it.contains("SID_EP1") || it.contains("SID_DIRECT") })
        assertTrue(plans[1].sources.all { it.url.contains("SID_EP2") })
    }

    @Test
    fun `unequal episode counts keep the episode, missing quality skipped`() {
        val full = listOf("Episode 1" to "u1", "Episode 2" to "u2", "Episode 3" to "u3")
        val short = listOf("Episode 1" to "v1", "Episode 2" to "v2")
        val groups = listOf(
            MoviesLeechParser.QualityGroup("720p", "Server 2", full),
            MoviesLeechParser.QualityGroup("480p", "Fast Server", short),
        )
        val plans = MoviesLeechParser.buildEpisodePlans("Show Season 2", groups, null)
        assertEquals(3, plans.size)
        assertEquals("S02E03", plans[2].name)
        assertEquals(2, plans[2].season)
        // Last episode has only the quality that actually lists it.
        assertEquals(1, plans[2].sources.size)
        assertEquals("720p", plans[2].sources[0].qualityLabel)
    }

    @Test
    fun `season number parsed from title, defaults to 1`() {
        assertEquals(2, MoviesLeechParser.seasonFromTitle("Show Season 2 Hindi"))
        assertEquals(1, MoviesLeechParser.seasonFromTitle("Show Hindi"))
    }

    // -- Archive pages ---------------------------------------------------

    @Test
    fun `archive collects episode links, drops dupes and junk`() {
        val eps = MoviesLeechParser.parseArchive(fixture("archive.html"), "https://leechpro.blog/archives/x")
        // Episode 2 mirror dupe removed; comment + about links ignored.
        assertEquals(3, eps.size)
        assertEquals("Episode 1", eps[0].first)
        assertTrue(eps.all { it.second.contains("cloud.unblockedgames.world") })
    }

    @Test
    fun `empty archive signals JS gate with empty list`() {
        assertTrue(
            MoviesLeechParser.parseArchive(fixture("archive-empty.html"), "https://leechpro.blog/archives/x").isEmpty()
        )
    }

    // -- URL resolution edge cases ---------------------------------------

    @Test
    fun `resolveUrl handles absolute relative and protocol-relative urls`() {
        val page = "https://leechpro.blog/archives/arc480"
        assertEquals(
            "https://cloud.unblockedgames.world/?sid=1",
            MoviesLeechParser.resolveUrl(page, "https://cloud.unblockedgames.world/?sid=1"),
        )
        assertEquals("https://cdn.example/x", MoviesLeechParser.resolveUrl(page, "//cdn.example/x"))
        assertEquals("https://leechpro.blog/other", MoviesLeechParser.resolveUrl(page, "/other"))
        assertEquals("https://leechpro.blog/archives/rel", MoviesLeechParser.resolveUrl(page, "rel"))
        assertEquals("", MoviesLeechParser.resolveUrl(page, "  "))
    }

    @Test
    fun `server names stay readable in the picker`() {
        assertEquals("Fast Server", MoviesLeechParser.serverNameFromLabel("✅ Fast Server 1080p"))
        assertEquals("Server 1", MoviesLeechParser.serverNameFromLabel("Watch"))
        assertEquals("Server 2", MoviesLeechParser.serverNameFromLabel("Server 2 - 720p"))
    }
}
