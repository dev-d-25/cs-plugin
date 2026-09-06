package com.modsuite

/**
 * One playable/downloadable mirror candidate (plan Phase 3 seam).
 *
 * Pure data: no CloudStream, no Android, no network. Safe to unit test
 * on the JVM.
 */
data class SourceCandidate(
    /** Numeric quality, e.g. 480 / 720 / 1080 / 2160. */
    val quality: Int,
    /** Display label, e.g. "720p" / "2160p". */
    val qualityLabel: String,
    /** Server/mirror label, e.g. "Fast Server", "Server 2". */
    val server: String,
    val url: String,
    val kind: SourceKind,
)

enum class SourceKind {
    ARCHIVE,
    GATE,
    DRIVESEED,
    SEED,
    DIRECT,
    UNKNOWN,
}

/** Classify a raw URL into the resolver stage that owns it. */
fun classifySource(url: String): SourceKind {
    val u = url.lowercase()
    return when {
        u.contains("leechpro.blog/archives") || u.contains("archive") -> SourceKind.ARCHIVE
        u.contains("cloud.unblockedgames.world") -> SourceKind.GATE
        u.contains("driveseed.org/file") -> SourceKind.DRIVESEED
        u.contains("driveseed.org/r") -> SourceKind.DRIVESEED
        u.contains("video-seed.dev") || u.contains("video-gen.xyz") ||
            u.contains("googleusercontent.com") -> SourceKind.SEED
        u.startsWith("http") -> SourceKind.DIRECT
        else -> SourceKind.UNKNOWN
    }
}

internal val qualityToken = Regex("""(480p|720p|1080p|2160p|4k)""", RegexOption.IGNORE_CASE)

/**
 * Numeric quality from a free-form label. Handles "4K"/"4k"/"2160p"
 * variants (plan edge case). Unknown labels map to 0, never throw.
 */
fun qualityFromLabel(label: String?): Int {
    val hit = label?.let { qualityToken.find(it)?.value?.lowercase() } ?: return 0
    return when (hit) {
        "480p" -> 480
        "720p" -> 720
        "1080p" -> 1080
        "2160p", "4k" -> 2160
        else -> 0
    }
}

/** Canonical display label for a numeric quality ("HD" when unknown). */
fun qualityLabel(quality: Int): String = when (quality) {
    480 -> "480p"
    720 -> "720p"
    1080 -> "1080p"
    2160 -> "2160p"
    else -> "HD"
}

/**
 * Mirror name for the CloudStream picker (plan Phase 5 contract), e.g.
 * "MoviesLeech · 1080p · Fast Server".
 */
fun mirrorName(providerName: String, qualityLabel: String, server: String): String {
    val parts = listOf(providerName.trim(), qualityLabel.trim(), server.trim())
        .filter { it.isNotEmpty() }
    return parts.joinToString(" · ")
}
