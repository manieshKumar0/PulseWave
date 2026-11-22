package com.example.data.remote

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

internal const val YT_DIAG_TAG = "PULSE_YT_RESOLVER"

internal enum class StreamBackendStrategy {
    INNERTUBE_WEB_REMIX,
    INNERTUBE_SAFARI_HLS,
    NEWPIPE_SESSION
}

internal class YouTubeHttpException(
    val statusCode: Int,
    message: String
) : IOException(message)

/**
 * Manages rotating browser/session headers (`User-Agent`, `Accept-Language`, consent cookies,
 * and WEB_REMIX visitorData) for NewPipeExtractor and YouTube Music catalog search.
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
        Log.i(YT_DIAG_TAG, "session_rotate profileIndex=$next")
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
                    .put("clientVersion", "1.20260804.16.00")
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
                if (!resp.isSuccessful) {
                    Log.w(YT_DIAG_TAG, "web_remix_visitor_id httpStatus=${resp.code}")
                    return@use null
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                json.optJSONObject("responseContext")
                    ?.optString("visitorData")
                    ?.takeIf { it.isNotBlank() }
            }
        }.onFailure { err ->
            Log.w(YT_DIAG_TAG, "web_remix_visitor_id exception=${err.javaClass.simpleName} msg=${err.message}")
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
