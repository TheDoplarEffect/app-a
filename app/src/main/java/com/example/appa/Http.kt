package com.example.appa

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTP layer on java.net (works on Android and the JVM for tests). */

data class Resp(val status: Int, val body: String, val bytes: ByteArray, val headers: Map<String, List<String>>) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    val ok: Boolean get() = status in 200..299
    /** True when the request never reached the server (timeout, DNS, refused…). */
    val networkError: Boolean get() = status == 0
}

typealias HttpTransport = (method: String, url: String, headers: Map<String, String>, body: String?, followRedirects: Boolean) -> Resp

/**
 * Real transport backed by HttpURLConnection. TOTAL: never throws — an
 * IOException becomes [Resp] with status 0 ("network error"), so callers
 * don't each need their own runCatching.
 */
val realHttp: HttpTransport = { method, url, headers, body, followRedirects ->
    runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = followRedirects
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            Resp(status, String(bytes, Charsets.UTF_8), bytes, conn.headerFields.mapValues { it.value ?: emptyList() })
        } finally {
            conn.disconnect()
        }
    }.getOrElse { Resp(0, "", ByteArray(0), emptyMap()) }
}

/**
 * Resolve a Prowlarr download URL to a magnet link. Prowlarr answers 3xx with
 * the real Location; only a magnet: (or .torrent) target is accepted — anything
 * else falls back to the download URL so we never hand out a random HTML page.
 */
fun magnetFromDownloadUrl(downloadUrl: String, http: HttpTransport = realHttp): String {
    val resp = http("GET", downloadUrl, mapOf("User-Agent" to "Mozilla/5.0"), null, false)
    if (resp.status in 300..399) {
        val loc = resp.header("Location") ?: return downloadUrl
        val l = loc.lowercase()
        if (l.startsWith("magnet:") || l.contains(".torrent")) return loc
        return downloadUrl
    }
    return downloadUrl
}

fun httpGetJson(url: String, headers: Map<String, String>, http: HttpTransport = realHttp): kotlinx.serialization.json.JsonElement {
    val resp = http("GET", url, headers, null, true)
    if (!resp.ok) throw ApiException("HTTP ${resp.status} for $url", resp.status)
    return kotlinx.serialization.json.Json.parseToJsonElement(resp.body)
}

/**
 * GET JSON with a small retry for transient failures (network error or 5xx):
 * [attempts] tries with linear backoff. 2xx/3xx/4xx are returned immediately.
 */
fun httpGetJsonRetrying(
    url: String,
    headers: Map<String, String>,
    http: HttpTransport = realHttp,
    attempts: Int = 3,
    backoffMs: Long = 600
): kotlinx.serialization.json.JsonElement {
    var last: ApiException? = null
    repeat(attempts.coerceAtLeast(1)) { i ->
        if (i > 0) sleepQuiet(backoffMs * i)
        val resp = http("GET", url, headers, null, true)
        if (resp.ok) return kotlinx.serialization.json.Json.parseToJsonElement(resp.body)
        if (resp.status in 400..499) throw ApiException("HTTP ${resp.status} for $url", resp.status) // client error: retrying won't help
        last = ApiException("HTTP ${resp.status} for $url", resp.status)
    }
    throw last ?: ApiException("HTTP error for $url")
}

private fun sleepQuiet(ms: Long) {
    try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
}

class ApiException(message: String, val status: Int = 0) : Exception(message)

/** URL-encode a query parameter. */
fun qenc(s: String): String =
    java.net.URLEncoder.encode(s, Charsets.UTF_8.name()).replace("+", "%20")
