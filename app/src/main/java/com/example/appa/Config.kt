package com.example.appa

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * User-supplied service configuration. Stored encrypted on-device (see
 * SecureStore.kt); never committed to git. All services are optional — the
 * app falls back to the placeholder catalog when live mode is off or a
 * service is missing.
 */
data class AppConfig(
    val tmdbToken: String = "",
    val sonarrUrl: String = "",
    val sonarrKey: String = "",
    val radarrUrl: String = "",
    val radarrKey: String = "",
    val prowlarrUrl: String = "",
    val prowlarrKey: String = "",
    val jellyfinUrl: String = "",
    val jellyfinKey: String = "",
    val liveMode: Boolean = false
) {
    /** Trim whitespace and trailing slashes so URLs concatenate cleanly. */
    fun normalized(): AppConfig = copy(
        tmdbToken = tmdbToken.trim(),
        sonarrUrl = sonarrUrl.trim().trimEnd('/'),
        sonarrKey = sonarrKey.trim(),
        radarrUrl = radarrUrl.trim().trimEnd('/'),
        radarrKey = radarrKey.trim(),
        prowlarrUrl = prowlarrUrl.trim().trimEnd('/'),
        prowlarrKey = prowlarrKey.trim(),
        jellyfinUrl = jellyfinUrl.trim().trimEnd('/'),
        jellyfinKey = jellyfinKey.trim()
    )

    val hasTmdb: Boolean get() = tmdbToken.isNotBlank()
    /** A service URL must be blank or carry an explicit http(s) scheme. */
    private fun validUrl(u: String) = u.isBlank() || u.startsWith("http://") || u.startsWith("https://")
    fun urlError(u: String): String? =
        if (validUrl(u)) null else "URL needs http:// or https://"

    val hasSonarr: Boolean get() = sonarrUrl.isNotBlank() && sonarrKey.isNotBlank() && validUrl(sonarrUrl)
    val hasRadarr: Boolean get() = radarrUrl.isNotBlank() && radarrKey.isNotBlank() && validUrl(radarrUrl)
    val hasProwlarr: Boolean get() = prowlarrUrl.isNotBlank() && prowlarrKey.isNotBlank() && validUrl(prowlarrUrl)
    val hasJellyfin: Boolean get() = jellyfinUrl.isNotBlank() && jellyfinKey.isNotBlank() && validUrl(jellyfinUrl)

    /** Live mode needs TMDB (metadata) plus at least one request backend. */
    val liveReady: Boolean
        get() = liveMode && hasTmdb && (hasSonarr || hasRadarr)

    fun toJson(): String = buildJsonObject {
            val p = { k: String, v: String -> put(k, JsonPrimitive(v)) }
            p("tmdbToken", tmdbToken)
            p("sonarrUrl", sonarrUrl); p("sonarrKey", sonarrKey)
            p("radarrUrl", radarrUrl); p("radarrKey", radarrKey)
            p("prowlarrUrl", prowlarrUrl); p("prowlarrKey", prowlarrKey)
            p("jellyfinUrl", jellyfinUrl); p("jellyfinKey", jellyfinKey)
            put("liveMode", JsonPrimitive(liveMode))
        }.toString()

    companion object {
        fun fromJson(text: String): AppConfig = runCatching {
            val o = jsonX.parseToJsonElement(text).obj()
            AppConfig(
                tmdbToken = o["tmdbToken"].str(),
                sonarrUrl = o["sonarrUrl"].str(), sonarrKey = o["sonarrKey"].str(),
                radarrUrl = o["radarrUrl"].str(), radarrKey = o["radarrKey"].str(),
                prowlarrUrl = o["prowlarrUrl"].str(), prowlarrKey = o["prowlarrKey"].str(),
                jellyfinUrl = o["jellyfinUrl"].str(), jellyfinKey = o["jellyfinKey"].str(),
                liveMode = o["liveMode"].str() == "true"
            )
        }.getOrDefault(AppConfig())
    }
}
