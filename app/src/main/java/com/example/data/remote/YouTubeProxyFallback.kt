package com.example.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "YouTubeProxyFallback"

internal enum class StreamBackendStrategy {
    INNERTUBE_VR,
    INNERTUBE_SAFARI_HLS,
    INNERTUBE_EMBED,
    PROXY_RACE,
    NEWPIPE_SESSION
}

internal data class ProxyStreamCandidate(
    val url: String,
    val mimeType: String,
    val bitrateBps: Int,
    val itag: Int,
    val host: String,
    val isProxied: Boolean
)

/**
 * Manages rotating browser/session headers (`User-Agent`, `Accept-Language`, consent cookies,
 * and WEB_REMIX visitorData) for NewPipeExtractor and YouTube Music catalog search.
 * Note: Standalone InnerTube clients (ANDROID_VR, IOS, TVHTML5, SAFARI) deliberately do NOT
 * reuse WEB_REMIX visitorData so YouTube does not reject cross-client tokens with 403.
 */
internal class YouTubeSessionManager {

    private val rotationIndex = AtomicInteger(0)

    @Volatile
    private var cachedVisitorData: String? = null

    @Volatile
    private var visitorFetchedAtMs: Long = 0L

    fun currentHeaderProfile(): SessionHeaderProfile {
        val idx = (rotationIndex.get() and Int.MAX_VALUE) % HEADER_PROFILES.size
        return HEADER_PROFILES[idx]
    }

    fun rotateSession(): SessionHeaderProfile {
        val next = (rotationIndex.incrementAndGet() and Int.MAX_VALUE) % HEADER_PROFILES.size
        cachedVisitorData = null
        visitorFetchedAtMs = 0L
        Log.i(TAG, "Rotated YouTube session profile -> #$next")
        return HEADER_PROFILES[next]
    }

    fun getOrFetchVisitorData(client: OkHttpClient, forceRefresh: Boolean = false): String? {
        val now = System.currentTimeMillis()
        val existing = cachedVisitorData
        if (!forceRefresh && !existing.isNullOrBlank() && (now - visitorFetchedAtMs) < VISITOR_TTL_MS) {
            return existing
        }
        synchronized(this) {
            val recheck = cachedVisitorData
            if (!forceRefresh && !recheck.isNullOrBlank() && (System.currentTimeMillis() - visitorFetchedAtMs) < VISITOR_TTL_MS) {
                return recheck
            }
            val fetched = fetchVisitorIdFromInnerTube(client)
            if (!fetched.isNullOrBlank()) {
                cachedVisitorData = fetched
                visitorFetchedAtMs = System.currentTimeMillis()
            }
            return cachedVisitorData
        }
    }

    private fun fetchVisitorIdFromInnerTube(client: OkHttpClient): String? {
        val profile = currentHeaderProfile()
        val payload = JSONObject().put(
            "context",
            JSONObject().put(
                "client",
                JSONObject()
                    .put("clientName", "WEB_REMIX")
                    .put("clientVersion", "1.20250310.01.00")
                    .put("hl", "en")
                    .put("gl", "US")
            )
        )
        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/visitor_id?prettyPrint=false")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", profile.desktopUserAgent)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val json = JSONObject(resp.body?.string().orEmpty())
                json.optJSONObject("responseContext")
                    ?.optString("visitorData")
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    internal data class SessionHeaderProfile(
        val desktopUserAgent: String,
        val acceptLanguage: String,
        val consentCookie: String
    )

    companion object {
        private const val VISITOR_TTL_MS = 25L * 60L * 1000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val HEADER_PROFILES = listOf(
            SessionHeaderProfile(
                desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36",
                acceptLanguage = "en-US,en;q=0.9",
                consentCookie = "CONSENT=PENDING+987; SOCS=CAESHAgBEhJnd3NfMjAyNTAzMTAtMF9SQzEaAmVuIAEaBgiA_LyuBg"
            ),
            SessionHeaderProfile(
                desktopUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
                acceptLanguage = "en-GB,en;q=0.9,hi;q=0.8",
                consentCookie = "CONSENT=YES+cb.20240901-07-p0.en+FX+410; SOCS=CAI"
            ),
            SessionHeaderProfile(
                desktopUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36",
                acceptLanguage = "en-IN,en-US;q=0.9,en;q=0.8",
                consentCookie = "CONSENT=PENDING+512; SOCS=CAESEwgDEgk1ODIzOTIwNzQaAmVuIAEaBgiA0J2uBg"
            )
        )
    }
}

/**
 * Parallel Piped & Invidious failover resolver with a hard timeout so proxy lookup never
 * stalls playback for more than ~3.5 seconds even when hosts are unreachable.
 */
internal class YouTubeProxyResolver(
    private val httpClient: OkHttpClient
) {
    @Volatile
    private var promotedHost: String? = null

    suspend fun raceProxyCandidates(
        videoId: String,
        preferHighQuality: Boolean,
        timeoutMs: Long = 3_500L
    ): List<ProxyStreamCandidate> = withContext(Dispatchers.IO) {
        val invidiousTargets = buildList {
            promotedHost?.takeIf { it in INVIDIOUS_INSTANCES }?.let { add(it) }
            addAll(INVIDIOUS_INSTANCES.take(4))
        }.distinct()

        val pipedTargets = buildList {
            promotedHost?.takeIf { it in PIPED_INSTANCES }?.let { add(it) }
            addAll(PIPED_INSTANCES.take(4))
        }.distinct()

        withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val invJobs = invidiousTargets.map { host ->
                    async { fetchSingleInvidious(host, videoId, preferHighQuality) }
                }
                val pipedJobs = pipedTargets.map { host ->
                    async { fetchSinglePiped(host, videoId, preferHighQuality) }
                }
                (invJobs + pipedJobs).awaitAll().flatten()
            }
        } ?: emptyList()
    }

    private fun fetchSingleInvidious(
        baseUrl: String,
        videoId: String,
        preferHighQuality: Boolean
    ): List<ProxyStreamCandidate> {
        val req = Request.Builder()
            .url("$baseUrl/api/v1/videos/$videoId?fields=adaptiveFormats")
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", PROXY_API_UA)
            .build()
        val body = runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull() ?: return emptyList()

        val candidates = parseInvidiousFormatsJson(
            rawJson = body,
            instanceBaseUrl = baseUrl,
            videoId = videoId,
            preferHighQuality = preferHighQuality
        )
        if (candidates.isNotEmpty()) promotedHost = baseUrl
        return candidates
    }

    private fun fetchSinglePiped(
        baseUrl: String,
        videoId: String,
        preferHighQuality: Boolean
    ): List<ProxyStreamCandidate> {
        val req = Request.Builder()
            .url("$baseUrl/streams/$videoId")
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", PROXY_API_UA)
            .build()
        val body = runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull() ?: return emptyList()

        val candidates = parsePipedStreamsJson(
            rawJson = body,
            instanceBaseUrl = baseUrl,
            preferHighQuality = preferHighQuality
        )
        if (candidates.isNotEmpty()) promotedHost = baseUrl
        return candidates
    }

    companion object {
        private const val PROXY_API_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"

        private val INVIDIOUS_INSTANCES = listOf(
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de",
            "https://invidious.private.coffee",
            "https://yewtu.be"
        )

        private val PIPED_INSTANCES = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.tokhmi.xyz",
            "https://api.piped.private.coffee",
            "https://pipedapi.adminforge.de"
        )

        internal fun parseInvidiousFormatsJson(
            rawJson: String,
            instanceBaseUrl: String,
            videoId: String,
            preferHighQuality: Boolean
        ): List<ProxyStreamCandidate> {
            val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
            val formats = root.optJSONArray("adaptiveFormats") ?: return emptyList()
            val proxied = mutableListOf<ProxyStreamCandidate>()
            val direct = mutableListOf<ProxyStreamCandidate>()

            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val type = f.optString("type")
                if (!type.startsWith("audio/", ignoreCase = true)) continue
                val mime = type.substringBefore(';').trim()
                val itag = f.optString("itag").toIntOrNull() ?: 140
                val bps = f.optString("bitrate").toIntOrNull() ?: f.optInt("bitrate", 128_000)
                val rawUrl = f.optString("url")

                val proxyUrl = "${instanceBaseUrl.trimEnd('/')}/latest_version?id=$videoId&itag=$itag&local=true"
                proxied.add(
                    ProxyStreamCandidate(
                        url = proxyUrl,
                        mimeType = mime,
                        bitrateBps = bps,
                        itag = itag,
                        host = instanceBaseUrl,
                        isProxied = true
                    )
                )

                val resolvedDirectUrl = when {
                    rawUrl.startsWith("http", ignoreCase = true) -> rawUrl
                    rawUrl.startsWith("/") -> "${instanceBaseUrl.trimEnd('/')}$rawUrl"
                    else -> ""
                }
                if (resolvedDirectUrl.isNotBlank()) {
                    direct.add(
                        ProxyStreamCandidate(
                            url = resolvedDirectUrl,
                            mimeType = mime,
                            bitrateBps = bps,
                            itag = itag,
                            host = instanceBaseUrl,
                            isProxied = !resolvedDirectUrl.contains("googlevideo.com", ignoreCase = true)
                        )
                    )
                }
            }

            return orderProxyCandidates(proxied, preferHighQuality) +
                orderProxyCandidates(direct, preferHighQuality)
        }

        internal fun parsePipedStreamsJson(
            rawJson: String,
            instanceBaseUrl: String,
            preferHighQuality: Boolean
        ): List<ProxyStreamCandidate> {
            val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
            val streams = root.optJSONArray("audioStreams") ?: return emptyList()
            val parsed = mutableListOf<ProxyStreamCandidate>()

            for (i in 0 until streams.length()) {
                val s = streams.optJSONObject(i) ?: continue
                val url = s.optString("url")
                if (!url.startsWith("http", ignoreCase = true)) continue
                val mime = s.optString("mimeType", "audio/mp4").substringBefore(';').trim()
                val bitrate = s.optInt("bitrate", 128_000)
                val itag = s.optInt("itag", 140)
                val isProxied = !url.contains("googlevideo.com", ignoreCase = true)
                parsed.add(
                    ProxyStreamCandidate(
                        url = url,
                        mimeType = mime,
                        bitrateBps = bitrate,
                        itag = itag,
                        host = instanceBaseUrl,
                        isProxied = isProxied
                    )
                )
            }

            val (proxied, direct) = parsed.partition { it.isProxied }
            return orderProxyCandidates(proxied, preferHighQuality) +
                orderProxyCandidates(direct, preferHighQuality)
        }

        private fun orderProxyCandidates(
            items: List<ProxyStreamCandidate>,
            preferHighQuality: Boolean
        ): List<ProxyStreamCandidate> {
            val (mp4, other) = items.partition { it.mimeType.contains("mp4", ignoreCase = true) }
            val sortFn: (ProxyStreamCandidate) -> Int = { c ->
                if (preferHighQuality) -c.bitrateBps else kotlin.math.abs(c.bitrateBps - 96_000)
            }
            return mp4.sortedBy(sortFn) + other.sortedBy(sortFn)
        }
    }
}
