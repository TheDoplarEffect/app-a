package com.example.appa

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/** Minimal HTTP layer on java.net (works on Android and the JVM for tests). */

data class Resp(val status: Int, val body: String, val bytes: ByteArray, val headers: Map<String, List<String>>) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key?.equals(name, ignoreCase = true) == true }?.value?.firstOrNull()
    val ok: Boolean get() = status in 200..299
    /** True when the request never reached the server (timeout, DNS, refused…). */
    val networkError: Boolean get() = status == 0
}

typealias HttpTransport = (method: String, url: String, headers: Map<String, String>, body: String?, followRedirects: Boolean) -> Resp

private const val MAX_REDIRECTS = 5
private const val MAX_BODY_BYTES = 8 * 1024 * 1024 // cap every response body (OOM guard)

/** Read at most [cap] bytes from [s]. */
private fun readCapped(s: InputStream, cap: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(cap, 1 shl 20))
    val tmp = ByteArray(8192)
    var total = 0
    while (total < cap) {
        val n = s.read(tmp, 0, minOf(tmp.size, cap - total))
        if (n < 0) break
        out.write(tmp, 0, n)
        total += n
    }
    return out.toByteArray()
}
/** Headers that must never be forwarded to a different host on redirect. */
private val SENSITIVE_HEADERS = setOf("authorization", "x-api-key", "cookie", "proxy-authorization")

private fun hostOf(url: String): String? = runCatching { URL(url).host }.getOrNull()

private fun sameHost(a: String, b: String): Boolean {
    val ha = hostOf(a) ?: return false
    val hb = hostOf(b) ?: return false
    return ha.equals(hb, ignoreCase = true)
}

/**
 * SSRF guard for URLs that come from external services (e.g. a Prowlarr
 * `downloadUrl`): must be http(s), carry no embedded credentials, and must
 * not resolve to loopback / link-local / cloud-metadata addresses.
 */
fun isSafeExternalUrl(url: String): Boolean {
    val u = runCatching { URL(url) }.getOrNull() ?: return false
    if (u.protocol != "http" && u.protocol != "https") return false
    if (u.userInfo != null) return false
    val host = u.host
    if (host.isEmpty()) return false
    if (host.equals("metadata.google.internal", ignoreCase = true)) return false
    return runCatching {
        // reject loopback / link-local (incl. cloud metadata 169.254.169.254);
        // site-local (LAN) stays allowed — the user's own *arr stack is on the LAN
        InetAddress.getAllByName(host).all { !it.isLoopbackAddress && !it.isLinkLocalAddress }
    }.getOrElse { false }
}

/**
 * Real transport backed by HttpURLConnection. TOTAL: never throws — an
 * IOException becomes [Resp] with status 0 ("network error"), so callers
 * don't each need their own runCatching.
 *
 * Redirects are followed MANUALLY (never via instanceFollowRedirects, which
 * would forward Authorization/X-Api-Key to any host): sensitive headers are
 * dropped when the redirect crosses hosts.
 */
val realHttp: HttpTransport = { method0, url, headers0, body0, followRedirects ->
    var method = method0
    var body = body0
    runCatching {
        var currentUrl = url
        var currentHeaders = headers0
        var resp: Resp
        var hops = 0
        while (true) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.instanceFollowRedirects = false // manual, see above
                currentHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val bodyNow = body
                if (bodyNow != null && method != "GET" && method != "HEAD") {
                    conn.doOutput = true
                    if (currentHeaders.keys.none { it.equals("Content-Type", ignoreCase = true) })
                        conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(bodyNow.toByteArray(Charsets.UTF_8)) }
                }
                val status = conn.responseCode
                val stream = if (status >= 400) conn.errorStream else conn.inputStream
                val bytes = stream?.use { readCapped(it, MAX_BODY_BYTES) } ?: ByteArray(0)
                // headerFields contains a null key for the status line — drop it (NPE guard)
                resp = Resp(status, String(bytes, Charsets.UTF_8), bytes,
                    conn.headerFields.filterKeys { it != null }.mapValues { it.value ?: emptyList() })
            } finally {
                conn.disconnect()
            }
            val loc = if (resp.status in 300..399) resp.header("Location") else null
            if (followRedirects && loc != null && hops < MAX_REDIRECTS) {
                val next = runCatching { URL(URL(currentUrl), loc).toString() }.getOrNull()
                // re-validate every hop: a redirect must stay http(s) and off loopback/metadata
                if (next == null || !isSafeExternalUrl(next)) break
                if (!sameHost(currentUrl, next))
                    currentHeaders = currentHeaders.filterKeys { !SENSITIVE_HEADERS.contains(it.lowercase()) }
                currentUrl = next
                hops++
                if (resp.status == 301 || resp.status == 302 || resp.status == 303) {
                    // per RFC 7231 these rewrite the method to GET and drop the body
                    method = "GET"
                    body = null
                }
                continue
            }
            break
        }
        resp
    }.getOrElse { Resp(0, "", ByteArray(0), emptyMap()) }
}

/**
 * Resolve a Prowlarr download URL to a magnet link. Prowlarr answers 3xx with
 * the real Location; only a magnet: (or .torrent) target is accepted — anything
 * else falls back to the download URL so we never hand out a random HTML page.
 */
fun magnetFromDownloadUrl(downloadUrl: String, http: HttpTransport = realHttp): String {
    if (!isSafeExternalUrl(downloadUrl)) return downloadUrl
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
