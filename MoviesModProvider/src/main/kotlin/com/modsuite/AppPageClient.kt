package com.modsuite

// Shared transport, identical copy of MoviesLeechProvider/AppPageClient.kt.
// Keep in sync.

import com.lagradost.cloudstream3.*

/**
 * Production [PageClient]: delegates to CloudStream's Android HTTP stack
 * (`app`, i.e. NiceHttp) on the device. Only the argument shapes already
 * proven in this module are used (get/post/head with headers/referer/
 * cookies/data); everything else lives in the testable layers above.
 */
class AppPageClient : PageClient {

    override suspend fun get(
        url: String,
        referer: String?,
        headers: Map<String, String>,
        cookies: Map<String, String>,
    ): PageResponse {
        val res = app.get(url, headers = headers, referer = referer ?: "", cookies = cookies)
        return PageResponse(
            url = res.url,
            status = res.code,
            text = res.text,
            cookies = res.cookies,
            contentType = res.headers["Content-Type"],
        )
    }

    override suspend fun post(
        url: String,
        referer: String?,
        headers: Map<String, String>,
        cookies: Map<String, String>,
        data: Map<String, String>,
    ): PageResponse {
        val res = app.post(
            url,
            headers = headers,
            cookies = cookies,
            data = data,
            referer = referer ?: "",
        )
        return PageResponse(
            url = res.url,
            status = res.code,
            text = res.text,
            cookies = res.cookies,
            contentType = res.headers["Content-Type"],
        )
    }

    override suspend fun probe(
        url: String,
        referer: String?,
        headers: Map<String, String>,
    ): ProbeResult {
        val res = app.head(url, headers = headers, referer = referer ?: "")
        return ProbeResult(
            url = res.url,
            status = res.code,
            contentType = res.headers["Content-Type"],
        )
    }
}
