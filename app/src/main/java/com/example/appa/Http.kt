package com.example.appa

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTP layer on java.net (works on Android and the JVM for tests). */

data class Resp(val status: Int, val body: String, val bytes: ByteArray, val headers: Map<String, List<String>>) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    val ok: Boolean get() = status in 200..299
}

typealias HttpTransport = (method: String, url: String, headers: Map<String, String>, body: String?, followRedirects: Boolean) -> Resp

/** Real transport backed by HttpURLConnection. */
val realHttp: HttpTransport = { method, url, headers, body, followRedirects ->
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = method
        conn.connectTimeout = 25_000
        conn.readTimeout = 30_000
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
}

/**
 * Resolve a Prowlarr download URL to a magnet link. Prowlarr answers 301 with
 * the real Location; for magnet-capable indexers that Location is the magnet
 * URI itself. Falls back to the download URL when it is not a redirect.
 */
fun magnetFromDownloadUrl(downloadUrl: String, http: HttpTransport = realHttp): String {
    val resp = http("GET", downloadUrl, mapOf("User-Agent" to "Mozilla/5.0"), null, false)
    val loc = resp.header("Location")
    if (resp.status in 300..399 && loc != null) {
        if (loc.startsWith("magnet:", ignoreCase = true)) return loc
        return loc // direct .torrent URL — still usable as a download link
    }
    return downloadUrl
}

fun httpGetJson(url: String, headers: Map<String, String>, http: HttpTransport = realHttp): kotlinx.serialization.json.JsonElement {
    val resp = http("GET", url, headers, null, true)
    if (!resp.ok) throw ApiException("HTTP ${resp.status} for $url", resp.status)
    return kotlinx.serialization.json.Json.parseToJsonElement(resp.body)
}

class ApiException(message: String, val status: Int = 0) : Exception(message)

/** URL-encode a query parameter. */
fun qenc(s: String): String =
    java.net.URLEncoder.encode(s, Charsets.UTF_8.name()).replace("+", "%20")
