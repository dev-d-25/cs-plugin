package com.modsuite

import org.junit.Assert.*
import org.junit.Test

class EpisodePayloadTest {

    @Test
    fun `round trip preserves urls containing separator characters`() {
        val sources = listOf(
            SourceCandidate(1080, "1080p", "Fast Server", "https://gate.example/?sid=a|b|||c", SourceKind.GATE),
            SourceCandidate(720, "720p", "Server 2", "https://x.example/?t=a|b&u=c", SourceKind.DIRECT),
        )
        val decoded = EpisodePayload.decode(EpisodePayload.encode(sources))
        assertEquals(2, decoded.size)
        assertEquals(sources.map { it.url }, decoded.map { it.url })
        assertEquals(sources.map { it.quality }, decoded.map { it.quality })
        assertEquals(sources.map { it.server }, decoded.map { it.server })
    }

    @Test
    fun `round trip preserves quotes and unicode in server names`() {
        val sources = listOf(
            SourceCandidate(720, "720p", "Fast \"Server\" ⚡", "https://x.example/v", SourceKind.DIRECT),
        )
        assertEquals(sources, EpisodePayload.decode(EpisodePayload.encode(sources)))
    }

    @Test
    fun `legacy pipe payload still decodes`() {
        val decoded = EpisodePayload.decode("1080p|https://a.example/1|||720p|https://b.example/2")
        assertEquals(2, decoded.size)
        assertEquals(1080, decoded[0].quality)
        assertEquals("https://a.example/1", decoded[0].url)
        assertEquals(720, decoded[1].quality)
    }

    @Test
    fun `single raw url decodes to one candidate`() {
        val decoded = EpisodePayload.decode("https://driveseed.org/file/X")
        assertEquals(1, decoded.size)
        assertEquals(SourceKind.DRIVESEED, decoded[0].kind)
    }

    @Test
    fun `malformed payloads decode to empty, never throw`() {
        assertTrue(EpisodePayload.decode("").isEmpty())
        assertTrue(EpisodePayload.decode("{broken").isEmpty())
        assertTrue(EpisodePayload.decode("""{"sources":[]}""").isEmpty())
        assertTrue(EpisodePayload.decode("""{"sources":[{"quality":1}]}""").isEmpty())
    }

    @Test
    fun `episodes stay isolated after encode-decode`() {
        val ep1 = listOf(SourceCandidate(720, "720p", "Server 2", "https://gate.example/?sid=EP1", SourceKind.GATE))
        val ep2 = listOf(SourceCandidate(720, "720p", "Server 2", "https://gate.example/?sid=EP2", SourceKind.GATE))
        val d1 = EpisodePayload.decode(EpisodePayload.encode(ep1))
        val d2 = EpisodePayload.decode(EpisodePayload.encode(ep2))
        assertEquals(listOf("https://gate.example/?sid=EP1"), d1.map { it.url })
        assertEquals(listOf("https://gate.example/?sid=EP2"), d2.map { it.url })
    }
}
