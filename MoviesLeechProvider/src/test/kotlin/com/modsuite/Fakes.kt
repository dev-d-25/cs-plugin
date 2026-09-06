package com.modsuite

import org.junit.Assert.*
import org.junit.Test

/** Shared fakes for resolver/gate tests. */
class FakePageClient(
    var getHandler: (url: String, referer: String?, headers: Map<String, String>, cookies: Map<String, String>) -> PageResponse =
        { u, _, _, _ -> error("unexpected GET $u") },
    var postHandler: (url: String, referer: String?, headers: Map<String, String>, cookies: Map<String, String>, data: Map<String, String>) -> PageResponse =
        { u, _, _, _, _ -> error("unexpected POST $u") },
    var probeHandler: (url: String) -> ProbeResult =
        { u -> ProbeResult(u, 200, "video/mp4") },
) : PageClient {
    data class Call(
        val method: String,
        val url: String,
        val referer: String?,
        val cookies: Map<String, String>,
        val data: Map<String, String>,
    )

    val calls = mutableListOf<Call>()

    override suspend fun get(
        url: String,
        referer: String?,
        headers: Map<String, String>,
        cookies: Map<String, String>,
    ): PageResponse {
        calls.add(Call("GET", url, referer, cookies, emptyMap()))
        return getHandler(url, referer, headers, cookies)
    }

    override suspend fun post(
        url: String,
        referer: String?,
        headers: Map<String, String>,
        cookies: Map<String, String>,
        data: Map<String, String>,
    ): PageResponse {
        calls.add(Call("POST", url, referer, cookies, data))
        return postHandler(url, referer, headers, cookies, data)
    }

    override suspend fun probe(
        url: String,
        referer: String?,
        headers: Map<String, String>,
    ): ProbeResult {
        calls.add(Call("PROBE", url, referer, emptyMap(), emptyMap()))
        return probeHandler(url)
    }
}

fun fixture(name: String): String {
    val cl = FakePageClient::class.java.classLoader ?: error("no classloader")
    return cl.getResource("fixtures/$name")?.readText() ?: error("missing fixture $name")
}

class FakeGate(var destFor: (String) -> String? = { null }) : GateBypass {
    val seen = mutableListOf<String>()
    override suspend fun bypass(sidUrl: String): String? {
        seen.add(sidUrl)
        return destFor(sidUrl)
    }
}
