package com.modsuite

/**
 * Transport seam (plan Phase 3): every HTTP call the provider makes goes
 * through this interface so parsers/resolvers can be tested with a fake
 * client instead of CloudStream's Android HTTP stack or the live site.
 */
interface PageClient {
    suspend fun get(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
    ): PageResponse

    suspend fun post(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        data: Map<String, String> = emptyMap(),
    ): PageResponse

    /**
     * Lightweight final-URL check: a HEAD request that never downloads a
     * body, used to confirm a resolved link without fetching gigabytes.
     */
    suspend fun probe(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): ProbeResult
}

data class PageResponse(
    /** Landed URL after redirects. */
    val url: String,
    val status: Int,
    val text: String,
    val cookies: Map<String, String> = emptyMap(),
    val contentType: String? = null,
)

data class ProbeResult(
    val url: String,
    val status: Int,
    val contentType: String? = null,
)
