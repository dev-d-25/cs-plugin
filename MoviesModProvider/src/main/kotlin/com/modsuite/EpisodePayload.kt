package com.modsuite

// Shared seam, identical copy of MoviesLeechProvider/EpisodePayload.kt.
// Keep in sync. Site-specific code lives in MoviesModParser/Provider.

/**
 * Structured per-episode/per-movie source data (plan Phase 4).
 *
 * The old format joined mirrors as "quality|url|||quality|url", which
 * breaks as soon as a URL or token contains a separator character. This
 * payload is a small JSON document instead:
 *
 *   {"sources":[{"quality":1080,"server":"Fast Server","url":"..."}]}
 *
 * Each Episode carries ONLY its own mirrors, so resolving one episode
 * never leaks another episode's links.
 *
 * Implemented with a hand-rolled encoder/parser (stdlib only) so the
 * payload layer stays unit-testable on plain JVM without JSON deps.
 */
object EpisodePayload {

    fun encode(sources: List<SourceCandidate>): String {
        val items = sources.joinToString(",") { s ->
            """{"quality":${s.quality},"server":${quote(s.server)},"url":${quote(s.url)}}"""
        }
        return """{"sources":[$items]}"""
    }

    /** Decode current JSON format; falls back to the legacy pipe format. */
    fun decode(data: String): List<SourceCandidate> {
        val text = data.trim()
        if (text.startsWith("{")) return parseJson(text)
        if (text.contains("|")) return decodeLegacy(text)
        if (text.startsWith("http")) {
            return listOf(
                SourceCandidate(
                    quality = 0,
                    qualityLabel = "HD",
                    server = "Server 1",
                    url = text,
                    kind = classifySource(text),
                )
            )
        }
        return emptyList()
    }

    // -- JSON (minimal, shaped exactly like encode() output) --------------

    private fun quote(raw: String): String {
        val sb = StringBuilder("\"")
        for (c in raw) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }

    private fun parseJson(text: String): List<SourceCandidate> {
        return try {
            val out = mutableListOf<SourceCandidate>()
            // Find each {...} object inside the "sources" array.
            val arrayBody = text.substringAfter("[", "").substringBeforeLast("]", "")
            var i = 0
            while (i < arrayBody.length) {
                val start = arrayBody.indexOf("{", i)
                if (start < 0) break
                var depth = 0
                var inStr = false
                var esc = false
                var end = -1
                for (j in start until arrayBody.length) {
                    val c = arrayBody[j]
                    if (inStr) {
                        if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                    } else {
                        when (c) {
                            '"' -> inStr = true
                            '{' -> depth++
                            '}' -> {
                                depth--
                                if (depth == 0) {
                                    end = j
                                    break
                                }
                            }
                        }
                    }
                }
                if (end < 0) break
                parseObject(arrayBody.substring(start, end + 1))?.let { out.add(it) }
                i = end + 1
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseObject(obj: String): SourceCandidate? {
        // Split top-level "key":value pairs (commas inside strings/objects
        // must not split). Values here are only numbers or strings.
        val fields = mutableMapOf<String, String>()
        var i = 1
        val end = obj.length - 1
        while (i < end) {
            while (i < end && (obj[i].isWhitespace() || obj[i] == ',')) i++
            if (i >= end || obj[i] != '"') break
            val keyEnd = findStringEnd(obj, i)
            if (keyEnd < 0) break
            val key = unescape(obj.substring(i + 1, keyEnd))
            var v = keyEnd + 1
            while (v < end && (obj[v].isWhitespace() || obj[v] == ':')) v++
            if (v >= end) break
            if (obj[v] == '"') {
                val vEnd = findStringEnd(obj, v)
                if (vEnd < 0) break
                fields[key] = unescape(obj.substring(v + 1, vEnd))
                i = vEnd + 1
            } else {
                var vEnd = v
                while (vEnd < end && obj[vEnd] != ',' && obj[vEnd] != '}') vEnd++
                fields[key] = obj.substring(v, vEnd).trim()
                i = vEnd
            }
        }
        val url = fields["url"]?.takeIf { it.isNotBlank() } ?: return null
        val quality = fields["quality"]?.toIntOrNull() ?: 0
        val server = fields["server"]?.takeIf { it.isNotBlank() } ?: "Server 1"
        return SourceCandidate(
            quality = quality,
            qualityLabel = if (quality != 0) qualityLabel(quality) else "HD",
            server = server,
            url = url,
            kind = classifySource(url),
        )
    }

    private fun findStringEnd(s: String, openQuote: Int): Int {
        var i = openQuote + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == '"') return i
            i++
        }
        return -1
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '"', '\\', '/' -> sb.append(s[i + 1])
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = s.substring(i + 2, minOf(i + 6, s.length))
                        sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                        i += 4
                    }
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    // -- Legacy "quality|url|||quality|url" (read-only, for stored data) --

    internal fun decodeLegacy(data: String): List<SourceCandidate> =
        data.split("|||").mapNotNull { entry ->
            val sep = entry.indexOf("|")
            if (sep < 0) return@mapNotNull null
            val tag = entry.substring(0, sep).trim()
            val url = entry.substring(sep + 1).trim()
            if (url.isBlank()) return@mapNotNull null
            val q = qualityFromLabel(tag)
            SourceCandidate(
                quality = q,
                qualityLabel = if (q != 0) qualityLabel(q) else tag.ifBlank { "HD" },
                server = "Server 1",
                url = url,
                kind = classifySource(url),
            )
        }
}
