package com.example.data.remote

import android.content.Context
import android.util.Log
import com.example.data.local.PreferencesRepository
import com.example.data.model.Track
import com.example.data.model.TrackSourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NewPipeRequest
import org.schabi.newpipe.extractor.downloader.Response as NewPipeResponse
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

private const val TAG = "YouTubeMusicClient"

enum class YouTubeAuthState {
    NOT_CONNECTED,
    CONFIGURED,
    NEEDS_REAUTH
}

data class CookieComponentSummary(
    val cookieCount: Int = 0,
    val hasSapisid: Boolean = false,
    val hasSid: Boolean = false,
    val hasHsidOrSsid: Boolean = false
) {
    val isAuthenticated: Boolean
        get() = hasSapisid && (hasSid || hasHsidOrSsid)
}

data class YouTubeAuthDiagnostics(
    val state: YouTubeAuthState,
    val cookieCount: Int,
    val hasSapisid: Boolean,
    val hasSid: Boolean,
    val hasHsidOrSsid: Boolean,
    val hasVisitorData: Boolean,
    val authUserIndex: String
)

data class ResolvedAudioStream(
    val url: String,
    val mimeType: String,
    val bitrateBps: Int,
    val expiresAtMs: Long,
    val userAgent: String,
    val isHls: Boolean = false,
    val requestHeaders: Map<String, String> = mapOf("User-Agent" to userAgent)
)

data class YouTubeSearchOutcome(
    val tracks: List<Track>,
    val errorMessage: String? = null
)

internal data class InnerTubeFormatCandidate(
    val itag: Int,
    val url: String,
    val mimeType: String,
    val bitrateBps: Int
)

internal data class ProbeOutcome(
    val ok: Boolean,
    val statusCode: Int = 0,
    val errorMessage: String? = null,
    val contentType: String = ""
)

private data class ResolverStageDetail(
    var usableStreamReturned: Boolean = false,
    var streamCount: Int = 0,
    var cipherOnlyCount: Int = 0,
    var hasHlsUrl: Boolean = false,
    var lastProbedIsHls: Boolean? = null,
    var lastProbedFormat: String = "none",
    var lastProbeHttpStatus: Int = 0,
    var playabilityStatus: String = ""
)

private object Ipv4PreferredDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        val ipv4 = all.filterIsInstance<Inet4Address>()
        return if (ipv4.isNotEmpty()) ipv4 + all.filter { it !is Inet4Address } else all
    }
}

internal fun Job.bindCallCancellation(call: okhttp3.Call): () -> Unit {
    val finished = AtomicBoolean(false)
    val watcher = CoroutineScope(this + Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
        suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation {
                if (!finished.get()) {
                    call.cancel()
                }
            }
        }
    }
    return {
        finished.set(true)
        watcher.cancel()
    }
}

internal class SessionAwareNewPipeDownloader(
    private val client: OkHttpClient,
    private val sessionManager: YouTubeSessionManager,
    private val authCookieProvider: () -> String?,
    private val activeJobProvider: () -> Job? = { activeResolutionJob.get() }
) : Downloader() {

    companion object {
        internal val activeResolutionJob = ThreadLocal<Job?>()
    }

    override fun execute(request: NewPipeRequest): NewPipeResponse {
        val job = activeJobProvider()
        if (job != null && !job.isActive) {
            throw CancellationException("Coroutine cancelled before NewPipe HTTP call")
        }
        val session = sessionManager.currentHeaderProfile()
        val parsedUrl = request.url().toHttpUrlOrNull()
        val host = parsedUrl?.host.orEmpty().lowercase()
        val isYouTubeHost = host == "youtube.com" || host.endsWith(".youtube.com")
        val builder = Request.Builder().url(request.url())

        var explicitUserAgent = ""
        var hasAcceptLanguage = false
        var hasCookie = false
        var hasAuthorization = false
        var hasAuthUser = false

        request.headers().forEach { (key, values) ->
            when {
                key.equals("User-Agent", ignoreCase = true) -> explicitUserAgent = values.firstOrNull().orEmpty()
                key.equals("Accept-Language", ignoreCase = true) -> hasAcceptLanguage = true
                key.equals("Cookie", ignoreCase = true) -> hasCookie = true
                key.equals("Authorization", ignoreCase = true) -> hasAuthorization = true
                key.equals("X-Goog-AuthUser", ignoreCase = true) -> hasAuthUser = true
            }
            values.forEach { value ->
                builder.addHeader(key, value)
            }
        }

        val isNonWebClientUa = explicitUserAgent.contains("Android", ignoreCase = true) ||
            explicitUserAgent.contains("iOS", ignoreCase = true) ||
            explicitUserAgent.contains("visionOS", ignoreCase = true) ||
            explicitUserAgent.contains("RealityDevice", ignoreCase = true) ||
            explicitUserAgent.contains("com.google.", ignoreCase = true) ||
            explicitUserAgent.contains("Cobalt", ignoreCase = true)

        // Attach desktop web auth/consent cookies only to `.youtube.com` web requests (never non-web clients).
        val activeAuthCookie = authCookieProvider()
            ?.takeIf { it.isNotBlank() && isYouTubeHost && !isNonWebClientUa }
        val attachedAuthCookie = !hasCookie && activeAuthCookie != null

        if (explicitUserAgent.isBlank()) builder.header("User-Agent", session.desktopUserAgent)
        if (!hasAcceptLanguage) builder.header("Accept-Language", session.acceptLanguage)
        if (!hasCookie && isYouTubeHost && !isNonWebClientUa) {
            builder.header("Cookie", activeAuthCookie ?: session.consentCookie)
        }
        if (activeAuthCookie != null && parsedUrl != null && parsedUrl.encodedPath.startsWith("/youtubei/v1/")) {
            val origin = "${parsedUrl.scheme}://${parsedUrl.host}"
            if (!hasAuthorization) {
                YouTubeMusicClient.buildSapisidHashHeader(activeAuthCookie, origin)?.let { sapisidHash ->
                    builder.header("Authorization", sapisidHash)
                    builder.header("X-Origin", origin)
                }
            }
            if (!hasAuthUser) {
                builder.header("X-Goog-AuthUser", "0")
            }
        }

        val data = request.dataToSend()
        when (request.httpMethod().uppercase()) {
            "POST" -> builder.post((data ?: ByteArray(0)).toRequestBody(null))
            "PUT" -> builder.put((data ?: ByteArray(0)).toRequestBody(null))
            "DELETE" -> {
                if (data != null) builder.delete(data.toRequestBody(null))
                else builder.delete()
            }
            "HEAD" -> builder.head()
            else -> builder.get()
        }

        val startNs = System.nanoTime()
        val call = client.newCall(builder.build())
        val unbindCancel = job?.bindCallCancellation(call)
        try {
            if (job != null && !job.isActive) {
                throw CancellationException("Coroutine cancelled before NewPipe HTTP call")
            }
            call.execute().use { response ->
                if (job != null && !job.isActive) {
                    throw CancellationException("Coroutine cancelled during NewPipe HTTP call")
                }
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                val hostPath = runCatching {
                    val u = response.request.url
                    "${u.host}${u.encodedPath}"
                }.getOrDefault("unknown")
                if (!response.isSuccessful) {
                    Log.w(
                        YT_DIAG_TAG,
                        "newpipe_http method=${request.httpMethod()} target=$hostPath httpStatus=${response.code} usedAuthCookie=$attachedAuthCookie durationMs=$elapsedMs"
                    )
                } else {
                    Log.d(
                        YT_DIAG_TAG,
                        "newpipe_http method=${request.httpMethod()} target=$hostPath httpStatus=${response.code} usedAuthCookie=$attachedAuthCookie durationMs=$elapsedMs"
                    )
                }
                if (response.code == 402) {
                    sessionManager.rotateSession()
                    throw YouTubeHttpException(402, "YouTube Music request rejected (HTTP 402)")
                }
                if (response.code == 429 || response.code == 403) {
                    sessionManager.rotateSession()
                }
                if (response.code == 429) {
                    throw ReCaptchaException("reCaptcha Challenge requested", request.url())
                }
                val bodyString = response.body?.string()
                val latestUrl = response.request.url.toString()
                return NewPipeResponse(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    bodyString,
                    latestUrl
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (job != null && !job.isActive) {
                throw CancellationException("NewPipe HTTP call cancelled", e)
            }
            throw e
        } finally {
            unbindCancel?.invoke()
        }
    }
}

/**
 * YouTube Music catalog search & on-demand audio stream resolver.
 *
 * - Separates metadata search from audio stream resolution: search & discovery only fetch
 *   track metadata, while stream URLs are resolved lazily when a user selects/transitions to a track.
 * - Resolves audio streams via `NewPipeExtractor` (`audioStreams` + `hlsUrl` manifest fallback) and
 *   YouTube `WEB` client (supporting authenticated session cookies + `SAPISIDHASH`), short-circuiting
 *   immediately on HTTP 402/429 without device-impersonation spoofing.
 * - Shares an IPv4-preferred `OkHttpClient` + LRU header interceptor directly with ExoPlayer's
 *   `OkHttpDataSource`, ensuring playback chunks, redirects, and HLS segments keep exact client headers.
 */
class YouTubeMusicClient internal constructor(
    overrideOkHttpClient: OkHttpClient? = null,
    private val internalStageResolver: (suspend (videoId: String, preferHighQuality: Boolean, corrId: String) -> Result<ResolvedAudioStream>)? = null
) {
    constructor() : this(null, null)

    @Volatile
    var preferHighQuality: Boolean = true

    private val streamHeadersByUrl: MutableMap<String, Map<String, String>> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Map<String, String>>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>?): Boolean {
                return size > 120
            }
        }
    )

    @Volatile
    private var lastHlsUserAgent: String? = null

    private val streamHeaderInterceptor = Interceptor { chain ->
        val original = chain.request()
        val urlStr = original.url.toString()
        val matchedHeaders = synchronized(streamHeadersByUrl) {
            streamHeadersByUrl[urlStr]
        }
        val explicitUa = original.header("User-Agent")
            ?.takeIf { it.isNotBlank() && !it.startsWith("okhttp", ignoreCase = true) }
        val hasExplicitClientParam = CLIENT_PARAM_REGEX.containsMatchIn(urlStr)
        val isHlsSubRequest = !hasExplicitClientParam && (
            urlStr.contains("hls_playlist", ignoreCase = true) ||
                urlStr.contains("hls_variant", ignoreCase = true) ||
                urlStr.contains(".m3u8", ignoreCase = true) ||
                urlStr.contains("/file/seg", ignoreCase = true)
            )
        val inferredUa = matchedHeaders?.get("User-Agent")
            ?: explicitUa
            ?: if (isHlsSubRequest && !lastHlsUserAgent.isNullOrBlank()) {
                lastHlsUserAgent
            } else if (urlStr.contains("googlevideo.com", ignoreCase = true)) {
                inferUserAgentForStreamUrl(urlStr)
            } else null

        val builder = original.newBuilder()
        if (!inferredUa.isNullOrBlank()) {
            builder.header("User-Agent", inferredUa)
        }
        matchedHeaders?.forEach { (k, v) ->
            if (!k.equals("User-Agent", ignoreCase = true)) {
                builder.header(k, v)
            }
        }
        chain.proceed(builder.build())
    }

    val playbackOkHttpClient: OkHttpClient = if (overrideOkHttpClient != null) {
        overrideOkHttpClient.newBuilder()
            .addInterceptor(streamHeaderInterceptor)
            .build()
    } else {
        OkHttpClient.Builder()
            .dns(Ipv4PreferredDns)
            .addInterceptor(streamHeaderInterceptor)
            .connectionPool(ConnectionPool(12, 5, TimeUnit.MINUTES))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val probeOkHttpClient: OkHttpClient = if (overrideOkHttpClient != null) {
        playbackOkHttpClient
    } else {
        playbackOkHttpClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    private val sessionManager = YouTubeSessionManager()
    private val resolutionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class InFlightResolution(
        val id: Long,
        val videoId: String,
        val resultDeferred: CompletableDeferred<Result<ResolvedAudioStream>> = CompletableDeferred(),
        initialWaiters: Int = 1
    ) {
        val waiters = AtomicInteger(initialWaiters)

        @Volatile
        var workerJob: Job? = null

        @Volatile
        var invalidated: Boolean = false

        fun tryAcquire(): Boolean {
            while (true) {
                if (invalidated || resultDeferred.isCancelled) return false
                val current = waiters.get()
                if (current <= 0) return false
                if (waiters.compareAndSet(current, current + 1)) {
                    if (invalidated || resultDeferred.isCancelled) {
                        waiters.decrementAndGet()
                        return false
                    }
                    return true
                }
            }
        }
    }

    private val streamCache = ConcurrentHashMap<String, ResolvedAudioStream>()
    private val inFlightResolutions = ConcurrentHashMap<String, InFlightResolution>()
    private val inFlightIdSeq = AtomicLong(0L)
    private val lastStrategyByVideo = ConcurrentHashMap<String, StreamBackendStrategy>()
    private val failedStrategiesByVideo = ConcurrentHashMap<String, MutableSet<StreamBackendStrategy>>()
    private val visitorDataByClient = ConcurrentHashMap<String, String>()
    private val correlationCounter = AtomicLong(0L)
    private val latestCorrelationByVideo = ConcurrentHashMap<String, String>()

    internal fun inFlightResolutionCountForTest(): Int = inFlightResolutions.size

    internal fun peekValidCachedStream(videoId: String): ResolvedAudioStream? {
        val cleanId = videoId.trim()
        if (cleanId.isBlank()) return null
        val cached = streamCache[cleanId] ?: return null
        return if (cached.expiresAtMs > System.currentTimeMillis() + CACHE_SAFETY_BUFFER_MS) {
            cached
        } else {
            streamCache.remove(cleanId, cached)
            null
        }
    }

    internal fun latestCorrelationIdFor(videoId: String): String {
        return latestCorrelationByVideo[videoId.trim()] ?: "res-uncorrelated"
    }

    @Volatile
    private var authCookie: String = ""

    @Volatile
    private var authVisitorData: String = ""

    @Volatile
    private var authUserIndex: String = "0"

    @Volatile
    private var authSessionRejected: Boolean = false

    @Volatile
    private var authHydratedFromStorage: Boolean = false

    @Volatile
    private var newPipeInitialized = false

    fun setYouTubeAuthSession(
        cookie: String,
        visitorData: String = "",
        authUser: String = "0",
        source: String = "runtime"
    ) {
        val normalizedCookie = cookie.trim()
        val normalizedVisitor = visitorData.trim()
        val normalizedAuthUser = authUser.trim().ifBlank { "0" }
        authHydratedFromStorage = true
        val unchanged = normalizedCookie == authCookie &&
            normalizedVisitor == authVisitorData &&
            normalizedAuthUser == authUserIndex &&
            !authSessionRejected
        if (unchanged) return

        authCookie = normalizedCookie
        authVisitorData = normalizedVisitor
        authUserIndex = normalizedAuthUser
        authSessionRejected = false
        streamCache.clear()
        failedStrategiesByVideo.clear()
        lastStrategyByVideo.clear()
        visitorDataByClient.clear()

        val diag = getAuthDiagnostics()
        Log.i(
            YT_DIAG_TAG,
            "auth_session_updated source=$source state=${diag.state} isAuth=${hasAuthenticatedYouTubeSession()} cookieCount=${diag.cookieCount} hasSapisid=${diag.hasSapisid} hasSid=${diag.hasSid} hasHsidOrSsid=${diag.hasHsidOrSsid} hasVisitorData=${diag.hasVisitorData}"
        )
    }

    suspend fun ensureAuthSessionHydrated(context: Context) {
        if (authHydratedFromStorage) return
        val restored = runCatching {
            PreferencesRepository(context.applicationContext).settingsFlow.first()
        }.getOrNull() ?: return
        setYouTubeAuthSession(
            cookie = restored.youtubeCookie,
            visitorData = restored.youtubeVisitorData,
            source = "datastore_preload"
        )
    }

    fun getAuthDiagnostics(): YouTubeAuthDiagnostics {
        val summary = inspectCookieComponents(authCookie)
        val state = when {
            !summary.isAuthenticated -> YouTubeAuthState.NOT_CONNECTED
            authSessionRejected -> YouTubeAuthState.NEEDS_REAUTH
            else -> YouTubeAuthState.CONFIGURED
        }
        return YouTubeAuthDiagnostics(
            state = state,
            cookieCount = summary.cookieCount,
            hasSapisid = summary.hasSapisid,
            hasSid = summary.hasSid,
            hasHsidOrSsid = summary.hasHsidOrSsid,
            hasVisitorData = authVisitorData.isNotBlank(),
            authUserIndex = authUserIndex
        )
    }

    fun hasConfiguredCookieSession(): Boolean = isValidAuthenticatedCookie(authCookie)

    fun wasAuthSessionRejected(): Boolean = authSessionRejected

    fun hasAuthenticatedYouTubeSession(): Boolean {
        return isValidAuthenticatedCookie(authCookie) && !authSessionRejected
    }

    fun registerTrackHint(@Suppress("UNUSED_PARAMETER") track: Track) {
        // Track hints are kept on Track objects directly; no cross-provider cache needed.
    }

    private fun ensureNewPipeInitialized() {
        if (newPipeInitialized) return
        synchronized(this) {
            if (!newPipeInitialized) {
                NewPipe.init(
                    SessionAwareNewPipeDownloader(
                        client = playbackOkHttpClient,
                        sessionManager = sessionManager,
                        authCookieProvider = {
                            if (hasAuthenticatedYouTubeSession()) authCookie else null
                        }
                    ),
                    Localization("en", "US")
                )
                newPipeInitialized = true
            }
        }
    }

    suspend fun searchMusic(query: String, limit: Int = 25): List<Track> {
        return searchMusicChecked(query, limit).tracks
    }

    suspend fun searchMusicChecked(query: String, limit: Int = 25): YouTubeSearchOutcome = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext YouTubeSearchOutcome(emptyList())

        val results = LinkedHashMap<String, Track>()
        var primaryError: Throwable? = null

        extractVideoId(trimmed)?.let { directId ->
            try {
                fetchTrackByVideoId(directId)?.let { directTrack ->
                    results[directTrack.id] = directTrack
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                primaryError = t
            }
        }

        val filteredResult = try {
            Result.success(queryWebRemixSearch(query = trimmed, params = SONGS_FILTER_PARAMS, limit = limit))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "WEB_REMIX filtered search failed: ${t.message}")
            Result.failure(t)
        }
        filteredResult.getOrNull()?.forEach { results.putIfAbsent(it.id, it) }
        val filteredErr = filteredResult.exceptionOrNull()
        if (filteredErr != null && primaryError == null) {
            primaryError = filteredErr
        }

        val hitRateOrPaymentGate = (filteredErr as? YouTubeHttpException)?.statusCode in setOf(402, 429)

        if (results.size < 4 && !hitRateOrPaymentGate) {
            try {
                queryWebRemixSearch(query = trimmed, params = null, limit = limit)
                    .forEach { results.putIfAbsent(it.id, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "WEB_REMIX unfiltered search failed: ${t.message}")
                if (primaryError == null || t is YouTubeHttpException) {
                    primaryError = t
                }
            }
        }

        if (results.isEmpty() && !hitRateOrPaymentGate) {
            try {
                searchViaNewPipe(trimmed, limit)
                    .forEach { results.putIfAbsent(it.id, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "NewPipe search fallback failed: ${t.message}")
                if (primaryError == null) primaryError = t
            }
        }

        val tracks = results.values.take(limit)
        val errorMessage = if (tracks.isEmpty() && primaryError != null) {
            humanReadableSearchError(primaryError)
        } else {
            null
        }
        YouTubeSearchOutcome(tracks = tracks, errorMessage = errorMessage)
    }

    suspend fun fetchDiscoveryCatalog(
        moodPrompt: String? = null,
        preferredGenres: List<String> = emptyList(),
        recentArtists: List<String> = emptyList(),
        limit: Int = 24
    ): List<Track> = withContext(Dispatchers.IO) {
        val queries = buildDiscoveryQueries(
            moodPrompt = moodPrompt,
            preferredGenres = preferredGenres,
            recentArtists = recentArtists
        )
        val perQuery = ((limit / queries.size.coerceAtLeast(1)) + 4).coerceIn(8, 18)

        val batches = coroutineScope {
            queries.map { q ->
                async {
                    val filtered = runCatching {
                        queryWebRemixSearch(query = q, params = SONGS_FILTER_PARAMS, limit = perQuery)
                    }.getOrDefault(emptyList())
                    if (filtered.isNotEmpty()) {
                        filtered
                    } else {
                        runCatching {
                            queryWebRemixSearch(query = q, params = null, limit = perQuery)
                        }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll()
        }

        val merged = LinkedHashMap<String, Track>()
        val maxBatchSize = batches.maxOfOrNull { it.size } ?: 0
        for (idx in 0 until maxBatchSize) {
            for (batch in batches) {
                val track = batch.getOrNull(idx) ?: continue
                if (merged.size < limit) {
                    merged.putIfAbsent(track.id, track)
                }
            }
            if (merged.size >= limit) break
        }

        if (merged.isEmpty() && queries.isNotEmpty()) {
            runCatching { searchViaNewPipe(queries.first(), limit) }
                .getOrDefault(emptyList())
                .forEach { merged.putIfAbsent(it.id, it) }
        }

        merged.values.toList()
    }

    /**
     * Fetches YouTube Music recommendations seeded directly from the songs/artists the user has liked.
     * Combines YouTube Music's Up-Next / Radio endpoint (`RDAMVM<videoId>`) for liked YouTube tracks
     * with targeted YouTube Music searches derived from liked song titles, artists, and genres.
     * Guaranteed to return ONLY YouTube Music tracks (`TrackSourceType.YOUTUBE`).
     */
    suspend fun fetchLikedBasedRecommendations(
        likedTracks: List<Track>,
        preferredGenres: List<String> = emptyList(),
        limit: Int = 12
    ): List<Track> = withContext(Dispatchers.IO) {
        val ytLikedSeeds = likedTracks
            .mapNotNull { track -> track.youtubeVideoId?.let { vid -> vid to track } }
            .distinctBy { it.first }
            .take(3)
        val seedVideoIdSet = likedTracks.mapNotNull { it.youtubeVideoId }.toSet()

        val radioBatches = if (ytLikedSeeds.isNotEmpty()) {
            coroutineScope {
                ytLikedSeeds.take(2).map { (videoId, seedTrack) ->
                    async {
                        val nextRecs = runCatching {
                            queryWebRemixNextRecommendations(videoId = videoId, limit = 10)
                        }.getOrDefault(emptyList())
                        val raw = if (nextRecs.isNotEmpty()) {
                            nextRecs
                        } else {
                            runCatching {
                                fetchRelatedViaNewPipe(videoId = videoId, limit = 8)
                            }.getOrDefault(emptyList())
                        }
                        raw.map { rec ->
                            if (rec.genre == "YouTube" && seedTrack.genre.isNotBlank() && !seedTrack.genre.equals("YouTube", true)) {
                                rec.copy(genre = seedTrack.genre)
                            } else rec
                        }
                    }
                }.awaitAll()
            }
        } else {
            emptyList()
        }

        val likedQueries = buildLikedRecommendationQueries(
            likedTracks = likedTracks,
            preferredGenres = preferredGenres
        )
        val perQuery = ((limit / likedQueries.size.coerceAtLeast(1)) + 3).coerceIn(6, 12)
        val searchBatches = coroutineScope {
            likedQueries.map { q ->
                async {
                    val filtered = runCatching {
                        queryWebRemixSearch(query = q, params = SONGS_FILTER_PARAMS, limit = perQuery)
                    }.getOrDefault(emptyList())
                    if (filtered.isNotEmpty()) {
                        filtered
                    } else {
                        runCatching {
                            queryWebRemixSearch(query = q, params = null, limit = perQuery)
                        }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll()
        }

        val merged = LinkedHashMap<String, Track>()
        val combinedBatches = radioBatches + searchBatches
        val maxBatchSize = combinedBatches.maxOfOrNull { it.size } ?: 0

        // Pass 1: prioritize fresh recommended YouTube songs (excluding the seed tracks themselves)
        for (idx in 0 until maxBatchSize) {
            for (batch in combinedBatches) {
                val track = batch.getOrNull(idx) ?: continue
                if (track.sourceType != TrackSourceType.YOUTUBE) continue
                val vid = track.youtubeVideoId
                if (vid != null && vid in seedVideoIdSet) continue
                if (merged.size < limit) {
                    merged.putIfAbsent(track.id, track)
                }
            }
            if (merged.size >= limit) break
        }

        // Pass 2: if fewer than target limit, allow liked YouTube tracks or NewPipe fallback
        if (merged.size < limit) {
            for (batch in combinedBatches) {
                for (track in batch) {
                    if (track.sourceType != TrackSourceType.YOUTUBE) continue
                    if (merged.size < limit) {
                        merged.putIfAbsent(track.id, track)
                    }
                }
            }
        }

        if (merged.isEmpty() && likedQueries.isNotEmpty()) {
            runCatching { searchViaNewPipe(likedQueries.first(), limit) }
                .getOrDefault(emptyList())
                .filter { it.sourceType == TrackSourceType.YOUTUBE }
                .forEach { merged.putIfAbsent(it.id, it) }
        }

        merged.values.take(limit)
    }

    // Speculative background prewarming of search/feed catalogs intentionally removed:
    // resolving streams strictly on-demand when a track is played prevents 402/429 request storms.

    suspend fun fetchTrackByVideoId(videoId: String): Track? = withContext(Dispatchers.IO) {
        val cleanId = videoId.trim()
        if (!VIDEO_ID_REGEX.matches(cleanId)) return@withContext null

        val oembedUrl = "https://www.youtube.com/oembed?url=${
            URLEncoder.encode("https://www.youtube.com/watch?v=$cleanId", "UTF-8")
        }&format=json"
        val oembedReq = Request.Builder()
            .url(oembedUrl)
            .header("User-Agent", sessionManager.currentHeaderProfile().desktopUserAgent)
            .build()
        val oembedTrack = runCatching {
            playbackOkHttpClient.newCall(oembedReq).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val json = JSONObject(resp.body?.string().orEmpty())
                val rawTitle = json.optString("title").trim()
                if (rawTitle.isBlank()) return@use null
                val author = json.optString("author_name")
                    .removeSuffix(" - Topic")
                    .trim()
                    .ifBlank { "YouTube Music" }
                val thumb = json.optString("thumbnail_url")
                    .ifBlank { "https://i.ytimg.com/vi/$cleanId/hqdefault.jpg" }
                buildYouTubeTrack(
                    videoId = cleanId,
                    title = rawTitle,
                    artist = author,
                    album = "YouTube Music",
                    durationMs = 210_000L,
                    artworkUrl = upscaleArtworkUrl(thumb),
                    queryHint = rawTitle
                )
            }
        }.getOrNull()
        if (oembedTrack != null) return@withContext oembedTrack

        runCatching {
            ensureNewPipeInitialized()
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$cleanId")
            val artist = info.uploaderName?.removeSuffix(" - Topic")?.trim()?.ifBlank { "YouTube Music" }
                ?: "YouTube Music"
            val thumb = info.thumbnails.maxByOrNull { it.width * it.height }?.url
                ?: "https://i.ytimg.com/vi/$cleanId/hqdefault.jpg"
            buildYouTubeTrack(
                videoId = cleanId,
                title = info.name.ifBlank { "YouTube Track" },
                artist = artist,
                album = "YouTube Music",
                durationMs = (info.duration.coerceAtLeast(30L)) * 1000L,
                artworkUrl = upscaleArtworkUrl(thumb),
                queryHint = info.name
            )
        }.getOrNull()
    }

    fun registerResolvedStreamHeaders(stream: ResolvedAudioStream) {
        if (stream.isHls && stream.userAgent.isNotBlank()) {
            lastHlsUserAgent = stream.userAgent
        }
        if (stream.url.isBlank() || stream.requestHeaders.isEmpty()) return
        synchronized(streamHeadersByUrl) {
            streamHeadersByUrl[stream.url] = stream.requestHeaders
        }
    }

    suspend fun resolveAudioStream(
        videoId: String,
        preferHighQuality: Boolean = this.preferHighQuality,
        forceRefresh: Boolean = false,
        @Suppress("UNUSED_PARAMETER") trackTitle: String? = null,
        @Suppress("UNUSED_PARAMETER") trackArtist: String? = null,
        @Suppress("UNUSED_PARAMETER") trackDurationMs: Long = 0L,
        correlationId: String? = null
    ): Result<ResolvedAudioStream> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val cleanId = videoId.trim()
        val corrId = correlationId?.takeIf { it.isNotBlank() } ?: "res-${correlationCounter.incrementAndGet()}"
        val resolveStartTsMs = System.currentTimeMillis()
        if (cleanId.isBlank()) {
            Log.w(
                YT_DIAG_TAG,
                "stage_event corr=$corrId stage=RESOLVE_AUDIO_STREAM videoId=blank startTsMs=$resolveStartTsMs endTsMs=$resolveStartTsMs durationMs=0 outcome=FAILURE errorClass=IllegalArgumentException rootCauseClass=IllegalArgumentException httpStatus=0 usableStreamReturned=false isHls=false format=none cancelled=false timedOut=false failureOrigin=LOCAL_APP"
            )
            return@withContext Result.failure(IllegalArgumentException("Invalid YouTube video ID"))
        }

        latestCorrelationByVideo[cleanId] = corrId
        val totalStartNs = System.nanoTime()
        val authDiag = getAuthDiagnostics()
        Log.i(
            YT_DIAG_TAG,
            "resolve_start corr=$corrId videoId=$cleanId startTsMs=$resolveStartTsMs preferHighQuality=$preferHighQuality forceRefresh=$forceRefresh authState=${authDiag.state} hasAuthSession=${hasAuthenticatedYouTubeSession()} hydrated=$authHydratedFromStorage cookieCount=${authDiag.cookieCount} hasSapisid=${authDiag.hasSapisid} hasSid=${authDiag.hasSid} hasVisitorData=${authDiag.hasVisitorData}"
        )

        if (!forceRefresh) {
            peekValidCachedStream(cleanId)?.let { cached ->
                registerResolvedStreamHeaders(cached)
                val endTsMs = System.currentTimeMillis()
                val durationMs = (System.nanoTime() - totalStartNs) / 1_000_000L
                Log.i(
                    YT_DIAG_TAG,
                    "stage_event corr=$corrId stage=RESOLVE_AUDIO_STREAM cacheHit=true videoId=$cleanId startTsMs=$resolveStartTsMs endTsMs=$endTsMs durationMs=$durationMs outcome=SUCCESS errorClass=NONE rootCauseClass=NONE httpStatus=200 usableStreamReturned=true isHls=${cached.isHls} format=${cached.mimeType} cancelled=false timedOut=false failureOrigin=NONE host=${safeUrlHost(cached.url)} bitrateBps=${cached.bitrateBps}"
                )
                return@withContext Result.success(cached)
            }
            inFlightResolutions[cleanId]?.let { existing ->
                if (existing.tryAcquire()) {
                    Log.d(YT_DIAG_TAG, "resolve_dedup_await corr=$corrId videoId=$cleanId entryId=${existing.id}")
                    return@withContext awaitInFlightResolution(
                        entry = existing,
                        cleanId = cleanId,
                        corrId = corrId,
                        resolveStartTsMs = resolveStartTsMs,
                        totalStartNs = totalStartNs,
                        dedupHit = true
                    )
                } else {
                    inFlightResolutions.remove(cleanId, existing)
                }
            }
        } else {
            streamCache.remove(cleanId)?.let {
                synchronized(streamHeadersByUrl) { streamHeadersByUrl.remove(it.url) }
            }
            failedStrategiesByVideo.remove(cleanId)
        }

        val newEntry = createInFlightResolution(
            cleanId = cleanId,
            preferHighQuality = preferHighQuality,
            corrId = corrId,
            resolveStartTsMs = resolveStartTsMs,
            totalStartNs = totalStartNs
        )

        if (forceRefresh) {
            inFlightResolutions.compute(cleanId) { _, existing ->
                existing?.invalidated = true
                newEntry
            }
            return@withContext awaitInFlightResolution(
                entry = newEntry,
                cleanId = cleanId,
                corrId = corrId,
                resolveStartTsMs = resolveStartTsMs,
                totalStartNs = totalStartNs,
                dedupHit = false
            )
        }

        var joinedExisting: InFlightResolution? = null
        while (true) {
            val raceExisting = inFlightResolutions.putIfAbsent(cleanId, newEntry)
            if (raceExisting == null) {
                break
            }
            if (raceExisting.tryAcquire()) {
                joinedExisting = raceExisting
                break
            }
            inFlightResolutions.remove(cleanId, raceExisting)
        }

        if (joinedExisting != null) {
            newEntry.invalidated = true
            newEntry.workerJob?.cancel()
            newEntry.resultDeferred.cancel()
            Log.d(YT_DIAG_TAG, "resolve_dedup_race_await corr=$corrId videoId=$cleanId entryId=${joinedExisting.id}")
            return@withContext awaitInFlightResolution(
                entry = joinedExisting,
                cleanId = cleanId,
                corrId = corrId,
                resolveStartTsMs = resolveStartTsMs,
                totalStartNs = totalStartNs,
                dedupHit = true
            )
        }

        awaitInFlightResolution(
            entry = newEntry,
            cleanId = cleanId,
            corrId = corrId,
            resolveStartTsMs = resolveStartTsMs,
            totalStartNs = totalStartNs,
            dedupHit = false
        )
    }

    private fun createInFlightResolution(
        cleanId: String,
        preferHighQuality: Boolean,
        corrId: String,
        resolveStartTsMs: Long,
        totalStartNs: Long
    ): InFlightResolution {
        val entry = InFlightResolution(
            id = inFlightIdSeq.incrementAndGet(),
            videoId = cleanId,
            initialWaiters = 1
        )
        entry.workerJob = resolutionScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = internalStageResolver?.invoke(cleanId, preferHighQuality, corrId)
                    ?: resolveAudioStreamInternal(cleanId, preferHighQuality, corrId)
                val endTsMs = System.currentTimeMillis()
                val totalMs = (System.nanoTime() - totalStartNs) / 1_000_000L
                if (!entry.invalidated) {
                    result.onSuccess { resolved ->
                        streamCache[cleanId] = resolved
                        registerResolvedStreamHeaders(resolved)
                    }
                }
                logOuterResolveOutcome(
                    corrId = corrId,
                    cleanId = cleanId,
                    startTsMs = resolveStartTsMs,
                    endTsMs = endTsMs,
                    durationMs = totalMs,
                    result = result,
                    dedupHit = false
                )
                entry.resultDeferred.complete(result)
            } catch (e: CancellationException) {
                val endTsMs = System.currentTimeMillis()
                val totalMs = (System.nanoTime() - totalStartNs) / 1_000_000L
                Log.i(
                    YT_DIAG_TAG,
                    "stage_event corr=$corrId stage=RESOLVE_AUDIO_STREAM videoId=$cleanId startTsMs=$resolveStartTsMs endTsMs=$endTsMs durationMs=$totalMs outcome=CANCELLED errorClass=CancellationException rootCauseClass=${e.cause?.javaClass?.simpleName ?: "None"} httpStatus=0 usableStreamReturned=false isHls=false format=none cancelled=true timedOut=false failureOrigin=LOCAL_APP"
                )
                entry.invalidated = true
                inFlightResolutions.remove(cleanId, entry)
                entry.resultDeferred.cancel(e)
                throw e
            } catch (t: Throwable) {
                val endTsMs = System.currentTimeMillis()
                val totalMs = (System.nanoTime() - totalStartNs) / 1_000_000L
                val failure = Result.failure<ResolvedAudioStream>(t)
                logOuterResolveOutcome(
                    corrId = corrId,
                    cleanId = cleanId,
                    startTsMs = resolveStartTsMs,
                    endTsMs = endTsMs,
                    durationMs = totalMs,
                    result = failure,
                    dedupHit = false
                )
                entry.resultDeferred.complete(failure)
            } finally {
                inFlightResolutions.remove(cleanId, entry)
            }
        }
        return entry
    }

    private suspend fun awaitInFlightResolution(
        entry: InFlightResolution,
        cleanId: String,
        corrId: String,
        resolveStartTsMs: Long,
        totalStartNs: Long,
        dedupHit: Boolean
    ): Result<ResolvedAudioStream> {
        var released = false
        fun releaseWaiter(cancelCause: CancellationException?) {
            if (released) return
            released = true
            val remaining = entry.waiters.decrementAndGet()
            if (cancelCause != null && remaining <= 0 && !entry.resultDeferred.isCompleted) {
                entry.invalidated = true
                inFlightResolutions.remove(cleanId, entry)
                entry.resultDeferred.cancel(cancelCause)
                entry.workerJob?.cancel(cancelCause)
            }
        }

        return try {
            currentCoroutineContext().ensureActive()
            entry.workerJob?.start()
            val awaited = entry.resultDeferred.await()
            if (!released) {
                released = true
                entry.waiters.decrementAndGet()
            }
            if (dedupHit) {
                val endTsMs = System.currentTimeMillis()
                val durationMs = (System.nanoTime() - totalStartNs) / 1_000_000L
                logOuterResolveOutcome(
                    corrId = corrId,
                    cleanId = cleanId,
                    startTsMs = resolveStartTsMs,
                    endTsMs = endTsMs,
                    durationMs = durationMs,
                    result = awaited,
                    dedupHit = true
                )
            }
            awaited
        } catch (e: CancellationException) {
            releaseWaiter(e)
            if (dedupHit) {
                val endTsMs = System.currentTimeMillis()
                val durationMs = (System.nanoTime() - totalStartNs) / 1_000_000L
                Log.i(
                    YT_DIAG_TAG,
                    "stage_event corr=$corrId stage=RESOLVE_AUDIO_STREAM dedupHit=true videoId=$cleanId startTsMs=$resolveStartTsMs endTsMs=$endTsMs durationMs=$durationMs outcome=CANCELLED errorClass=CancellationException rootCauseClass=${e.cause?.javaClass?.simpleName ?: "None"} httpStatus=0 usableStreamReturned=false isHls=false format=none cancelled=true timedOut=false failureOrigin=LOCAL_APP"
                )
            }
            throw e
        }
    }

    private fun logOuterResolveOutcome(
        corrId: String,
        cleanId: String,
        startTsMs: Long,
        endTsMs: Long,
        durationMs: Long,
        result: Result<ResolvedAudioStream>,
        dedupHit: Boolean
    ) {
        val resolved = result.getOrNull()
        if (resolved != null) {
            Log.i(
                YT_DIAG_TAG,
                "stage_event corr=$corrId stage=RESOLVE_AUDIO_STREAM dedupHit=$dedupHit videoId=$cleanId startTsMs=$startTsMs endTsMs=$endTsMs durationMs=$durationMs outcome=SUCCESS errorClass=NONE rootCauseClass=NONE httpStatus=200 usableStreamReturned=true isHls=${resolved.isHls} format=${resolved.mimeType} cancelled=false timedOut=false failureOrigin=NONE host=${safeUrlHost(resolved.url)} bitrateBps=${resolved.bitrateBps}"
            )
        } else {
            val err = result.exceptionOrNull()
            val rootCause = deepestCause(err)
            val timedOut = findCause<SocketTimeoutException>(err) != null
            val httpStatus = extractHttpStatus(err)
            val origin = classifyFailureOrigin(err, timedOut = timedOut, cancelled = false)
            val outcome = if (timedOut) "TIMEOUT" else "FAILURE"
            Log.w(
                YT_DIAG_TAG,
                "stage_event corr=$corrId stage=RESOLVE_AUDIO_STREAM dedupHit=$dedupHit videoId=$cleanId startTsMs=$startTsMs endTsMs=$endTsMs durationMs=$durationMs outcome=$outcome errorClass=${err?.javaClass?.simpleName ?: "None"} rootCauseClass=${rootCause?.javaClass?.simpleName ?: "None"} httpStatus=$httpStatus usableStreamReturned=false isHls=false format=none cancelled=false timedOut=$timedOut failureOrigin=$origin msg=${redactSensitiveUrls(err?.message)}"
            )
        }
    }

    fun markStreamFailedAndInvalidate(videoId: String) {
        val cleanId = videoId.trim()
        if (cleanId.isBlank()) return
        streamCache.remove(cleanId)?.let {
            synchronized(streamHeadersByUrl) { streamHeadersByUrl.remove(it.url) }
        }
        val failedStrategy = lastStrategyByVideo[cleanId]
        if (failedStrategy != null) {
            failedStrategiesByVideo.computeIfAbsent(cleanId) {
                Collections.synchronizedSet(mutableSetOf())
            }.add(failedStrategy)
        }
        Log.w(YT_DIAG_TAG, "stream_invalidated corr=${latestCorrelationIdFor(cleanId)} videoId=$cleanId failedStrategy=$failedStrategy")
        sessionManager.rotateSession()
    }

    fun invalidateStreamCache(videoId: String) {
        streamCache.remove(videoId.trim())?.let {
            synchronized(streamHeadersByUrl) { streamHeadersByUrl.remove(it.url) }
        }
    }

    private suspend fun resolveAudioStreamInternal(
        cleanId: String,
        preferHighQuality: Boolean,
        corrId: String
    ): Result<ResolvedAudioStream> {
        val skipped = failedStrategiesByVideo[cleanId]?.toSet().orEmpty()
        val authenticated = hasAuthenticatedYouTubeSession()
        val strategyOrder = buildList {
            if (authenticated) {
                if (StreamBackendStrategy.INNERTUBE_WEB_REMIX !in skipped) {
                    add(StreamBackendStrategy.INNERTUBE_WEB_REMIX)
                }
                if (StreamBackendStrategy.INNERTUBE_SAFARI_HLS !in skipped) {
                    add(StreamBackendStrategy.INNERTUBE_SAFARI_HLS)
                }
                if (StreamBackendStrategy.NEWPIPE_SESSION !in skipped) {
                    add(StreamBackendStrategy.NEWPIPE_SESSION)
                }
            } else {
                if (StreamBackendStrategy.NEWPIPE_SESSION !in skipped) {
                    add(StreamBackendStrategy.NEWPIPE_SESSION)
                }
                if (StreamBackendStrategy.INNERTUBE_SAFARI_HLS !in skipped) {
                    add(StreamBackendStrategy.INNERTUBE_SAFARI_HLS)
                }
                if (StreamBackendStrategy.INNERTUBE_WEB_REMIX !in skipped) {
                    add(StreamBackendStrategy.INNERTUBE_WEB_REMIX)
                }
            }
        }
        Log.d(
            YT_DIAG_TAG,
            "resolve_plan corr=$corrId videoId=$cleanId strategies=$strategyOrder skipped=$skipped authenticated=$authenticated"
        )

        var bestError: Throwable? = null
        var sawAuthRejectionInAttempt = false
        for (strategy in strategyOrder) {
            val stageStartTsMs = System.currentTimeMillis()
            val attemptStartNs = System.nanoTime()
            val stageDetail = ResolverStageDetail()
            val timeoutMs = when (strategy) {
                StreamBackendStrategy.NEWPIPE_SESSION -> NEWPIPE_TIMEOUT_MS
                StreamBackendStrategy.INNERTUBE_WEB_REMIX,
                StreamBackendStrategy.INNERTUBE_SAFARI_HLS -> WEB_CLIENT_TIMEOUT_MS
            }
            val attempt = try {
                withTimeoutOrNull(timeoutMs) {
                    try {
                        val stream = when (strategy) {
                            StreamBackendStrategy.NEWPIPE_SESSION ->
                                resolveViaNewPipe(cleanId, preferHighQuality, corrId, stageDetail)
                            StreamBackendStrategy.INNERTUBE_WEB_REMIX ->
                                fetchPlayerStream(cleanId, CLIENT_WEB_REMIX, preferHighQuality, corrId, stageDetail)
                            StreamBackendStrategy.INNERTUBE_SAFARI_HLS ->
                                fetchPlayerStream(cleanId, CLIENT_WEB_HLS, preferHighQuality, corrId, stageDetail)
                        }
                        Result.success(stream)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Result.failure(t)
                    }
                }
            } catch (e: CancellationException) {
                val stageEndTsMs = System.currentTimeMillis()
                val elapsedMs = (System.nanoTime() - attemptStartNs) / 1_000_000L
                Log.i(
                    YT_DIAG_TAG,
                    "stage_event corr=$corrId stage=$strategy videoId=$cleanId startTsMs=$stageStartTsMs endTsMs=$stageEndTsMs durationMs=$elapsedMs outcome=CANCELLED errorClass=CancellationException rootCauseClass=${e.cause?.javaClass?.simpleName ?: "None"} httpStatus=${stageDetail.lastProbeHttpStatus} usableStreamReturned=${stageDetail.usableStreamReturned} isHls=${stageDetail.lastProbedIsHls ?: false} format=${stageDetail.lastProbedFormat} cancelled=true timedOut=false failureOrigin=LOCAL_APP"
                )
                throw e
            }
            val stageEndTsMs = System.currentTimeMillis()
            val elapsedMs = (System.nanoTime() - attemptStartNs) / 1_000_000L

            if (attempt == null) {
                val timeoutErr = SocketTimeoutException("$strategy timed out after ${timeoutMs}ms")
                val httpStatus = stageDetail.lastProbeHttpStatus
                Log.w(
                    YT_DIAG_TAG,
                    "stage_event corr=$corrId stage=$strategy videoId=$cleanId startTsMs=$stageStartTsMs endTsMs=$stageEndTsMs durationMs=$elapsedMs timeoutMs=$timeoutMs outcome=TIMEOUT errorClass=SocketTimeoutException rootCauseClass=SocketTimeoutException httpStatus=$httpStatus usableStreamReturned=${stageDetail.usableStreamReturned} isHls=${stageDetail.lastProbedIsHls ?: false} format=${stageDetail.lastProbedFormat} cancelled=false timedOut=true failureOrigin=NETWORK_OR_TIMEOUT"
                )
                bestError = selectMoreInformativeError(bestError, timeoutErr)
                continue
            }

            val stream = attempt.getOrNull()
            if (stream != null) {
                lastStrategyByVideo[cleanId] = strategy
                Log.i(
                    YT_DIAG_TAG,
                    "stage_event corr=$corrId stage=$strategy videoId=$cleanId startTsMs=$stageStartTsMs endTsMs=$stageEndTsMs durationMs=$elapsedMs outcome=SUCCESS errorClass=NONE rootCauseClass=NONE httpStatus=200 usableStreamReturned=true isHls=${stream.isHls} format=${stream.mimeType} cancelled=false timedOut=false failureOrigin=NONE host=${safeUrlHost(stream.url)} streamCount=${stageDetail.streamCount} hasHlsUrl=${stageDetail.hasHlsUrl}"
                )
                return Result.success(stream)
            }

            val err = attempt.exceptionOrNull()
            val rootCause = deepestCause(err)
            val statusCode = extractHttpStatus(err).takeIf { it > 0 } ?: stageDetail.lastProbeHttpStatus
            val timedOut = findCause<SocketTimeoutException>(err) != null
            val origin = classifyFailureOrigin(
                error = err,
                timedOut = timedOut,
                cancelled = false,
                cipherOnlyCount = stageDetail.cipherOnlyCount,
                playabilityStatus = stageDetail.playabilityStatus
            )
            val outcome = if (timedOut) "TIMEOUT" else "FAILURE"
            Log.w(
                YT_DIAG_TAG,
                "stage_event corr=$corrId stage=$strategy videoId=$cleanId startTsMs=$stageStartTsMs endTsMs=$stageEndTsMs durationMs=$elapsedMs outcome=$outcome errorClass=${err?.javaClass?.simpleName ?: "NullStream"} rootCauseClass=${rootCause?.javaClass?.simpleName ?: "None"} httpStatus=$statusCode usableStreamReturned=${stageDetail.usableStreamReturned} isHls=${stageDetail.lastProbedIsHls ?: false} format=${stageDetail.lastProbedFormat} cancelled=false timedOut=$timedOut failureOrigin=$origin streamCount=${stageDetail.streamCount} cipherCount=${stageDetail.cipherOnlyCount} hasHlsUrl=${stageDetail.hasHlsUrl} playability=${stageDetail.playabilityStatus.ifBlank { "none" }} errorMsg=${redactSensitiveUrls(err?.message ?: "null_stream")}"
            )
            if (authenticated &&
                strategy != StreamBackendStrategy.NEWPIPE_SESSION &&
                (statusCode == 401 || stageDetail.playabilityStatus.equals("LOGIN_REQUIRED", ignoreCase = true))
            ) {
                sawAuthRejectionInAttempt = true
            }
            if (err != null) {
                bestError = selectMoreInformativeError(bestError, err)
                if (err is YouTubeHttpException && (err.statusCode == 402 || err.statusCode == 429)) {
                    val formatted = humanReadableExtractionError(bestError)
                    Log.w(
                        YT_DIAG_TAG,
                        "strategy_short_circuit corr=$corrId videoId=$cleanId strategy=$strategy httpStatus=${err.statusCode}"
                    )
                    return Result.failure(YouTubeHttpException(err.statusCode, formatted))
                }
            }
        }

        if (authenticated && sawAuthRejectionInAttempt) {
            authSessionRejected = true
            Log.w(YT_DIAG_TAG, "auth_session_marked_rejected corr=$corrId reason=all_authenticated_attempts_failed")
        }

        val formattedMessage = humanReadableExtractionError(
            error = bestError,
            hasAuthenticatedSession = hasAuthenticatedYouTubeSession(),
            hasConfiguredCookie = hasConfiguredCookieSession(),
            wasSessionRejected = wasAuthSessionRejected()
        )
        Log.w(
            YT_DIAG_TAG,
            "resolve_exhausted corr=$corrId videoId=$cleanId authState=${getAuthDiagnostics().state} bestErrorClass=${bestError?.javaClass?.simpleName} bestErrorMsg=${redactSensitiveUrls(bestError?.message)} uiMessage=${redactSensitiveUrls(formattedMessage)}"
        )
        val wrapped = if (bestError is YouTubeHttpException) {
            YouTubeHttpException(bestError.statusCode, formattedMessage)
        } else {
            IOException(formattedMessage, bestError)
        }
        return Result.failure(wrapped)
    }

    private suspend inline fun <T> executeCancellableCall(
        client: OkHttpClient,
        request: Request,
        crossinline block: (okhttp3.Response) -> T
    ): T {
        val job = currentCoroutineContext()[Job]
        currentCoroutineContext().ensureActive()
        val call = client.newCall(request)
        val unbindCancel = job?.bindCallCancellation(call)
        return try {
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                currentCoroutineContext().ensureActive()
                block(response)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            unbindCancel?.invoke()
        }
    }

    internal suspend fun fetchWebVisitorDataForTest(): String? =
        getOrFetchClientVisitorData(CLIENT_WEB_HLS)

    internal suspend fun fetchWebPlayerStreamForTest(videoId: String): ResolvedAudioStream? =
        fetchPlayerStream(videoId, CLIENT_WEB_HLS, preferHighQuality = true)

    private suspend fun getOrFetchClientVisitorData(profile: InnerTubeClientProfile): String? {
        currentCoroutineContext().ensureActive()
        visitorDataByClient[profile.clientName]?.let { return it }
        if ((profile.clientName == "WEB" || profile.clientName == "WEB_REMIX") && authVisitorData.isNotBlank()) {
            return authVisitorData
        }
        if (profile.clientName == "WEB_REMIX") {
            sessionManager.getOrFetchVisitorData(probeOkHttpClient)?.let { visitor ->
                visitorDataByClient["WEB_REMIX"] = visitor
                return visitor
            }
        }
        val clientJson = JSONObject()
            .put("clientName", profile.clientName)
            .put("clientVersion", profile.clientVersion)
            .put("hl", "en")
            .put("gl", "US")
        profile.platform?.let { clientJson.put("platform", it) }

        val payload = JSONObject().put("context", JSONObject().put("client", clientJson))
        val reqBuilder = Request.Builder()
            .url("${profile.hostBaseUrl}/youtubei/v1/visitor_id?key=$INNERTUBE_API_KEY&prettyPrint=false")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("User-Agent", profile.userAgent)
            .header("X-YouTube-Client-Name", profile.clientIdHeader)
            .header("X-YouTube-Client-Version", profile.clientVersion)

        profile.origin?.let {
            reqBuilder.header("Origin", it)
            reqBuilder.header("Referer", "$it/")
        }

        return try {
            executeCancellableCall(probeOkHttpClient, reqBuilder.build()) { resp ->
                if (!resp.isSuccessful) {
                    Log.w(YT_DIAG_TAG, "visitor_id client=${profile.clientName} httpStatus=${resp.code}")
                    return@executeCancellableCall null
                }
                val visitor = JSONObject(resp.body?.string().orEmpty())
                    .optJSONObject("responseContext")
                    ?.optString("visitorData")
                    ?.takeIf { it.isNotBlank() }
                if (visitor != null) {
                    visitorDataByClient[profile.clientName] = visitor
                }
                visitor
            }
        } catch (e: CancellationException) {
            throw e
        } catch (err: Throwable) {
            Log.w(YT_DIAG_TAG, "visitor_id client=${profile.clientName} error=${err.javaClass.simpleName}:${err.message}")
            null
        }
    }

    private suspend inline fun <T> withNewPipeCancellationJob(crossinline block: () -> T): T {
        ensureNewPipeInitialized()
        val currentJob = currentCoroutineContext()[Job]
        val prevJob = SessionAwareNewPipeDownloader.activeResolutionJob.get()
        SessionAwareNewPipeDownloader.activeResolutionJob.set(currentJob)
        return try {
            block()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            throw t
        } finally {
            SessionAwareNewPipeDownloader.activeResolutionJob.set(prevJob)
        }
    }

    private suspend fun resolveSignatureTimestampSafe(videoId: String): Int? {
        currentCoroutineContext().ensureActive()
        return try {
            withNewPipeCancellationJob {
                YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(YT_DIAG_TAG, "signature_timestamp_error videoId=$videoId err=${t.javaClass.simpleName}:${redactSensitiveUrls(t.message)}")
            null
        }
    }

    private suspend fun deobfuscateThrottlingUrlSafe(videoId: String, rawUrl: String): String {
        currentCoroutineContext().ensureActive()
        if (!rawUrl.contains("n=", ignoreCase = false)) return rawUrl
        return try {
            withNewPipeCancellationJob {
                YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, rawUrl)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(YT_DIAG_TAG, "n_param_deobf_error videoId=$videoId err=${t.javaClass.simpleName}:${redactSensitiveUrls(t.message)}")
            rawUrl
        }
    }

    private suspend fun fetchPlayerStream(
        videoId: String,
        profile: InnerTubeClientProfile,
        preferHighQuality: Boolean,
        corrId: String = "res-uncorrelated",
        stageDetail: ResolverStageDetail = ResolverStageDetail()
    ): ResolvedAudioStream? {
        currentCoroutineContext().ensureActive()
        val reqStartNs = System.nanoTime()
        val session = sessionManager.currentHeaderProfile()
        val visitor = getOrFetchClientVisitorData(profile)
        val sts = resolveSignatureTimestampSafe(videoId)
        val cpn = runCatching { YoutubeParsingHelper.generateContentPlaybackNonce() }.getOrNull()
        currentCoroutineContext().ensureActive()

        val clientJson = JSONObject()
            .put("clientName", profile.clientName)
            .put("clientVersion", profile.clientVersion)
            .put("hl", "en")
            .put("gl", "US")
        if (!visitor.isNullOrBlank()) {
            clientJson.put("visitorData", visitor)
        }
        profile.platform?.let { clientJson.put("platform", it) }
        if (profile.clientName == "WEB_REMIX") {
            clientJson.put("originalUrl", "https://music.youtube.com")
        }

        val payload = JSONObject()
            .put("context", JSONObject().put("client", clientJson))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        if (!cpn.isNullOrBlank()) {
            payload.put("cpn", cpn)
        }
        if (sts != null && sts > 0) {
            payload.put(
                "playbackContext",
                JSONObject().put(
                    "contentPlaybackContext",
                    JSONObject()
                        .put("signatureTimestamp", sts)
                        .put("html5Preference", "HTML5_PREF_WANTS")
                )
            )
        }

        val reqBuilder = Request.Builder()
            .url("${profile.hostBaseUrl}/youtubei/v1/player?key=$INNERTUBE_API_KEY&prettyPrint=false")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("User-Agent", profile.userAgent)
            .header("Accept-Language", session.acceptLanguage)
            .header("X-YouTube-Client-Name", profile.clientIdHeader)
            .header("X-YouTube-Client-Version", profile.clientVersion)

        if (!visitor.isNullOrBlank()) {
            reqBuilder.header("X-Goog-Visitor-Id", visitor)
        }

        profile.origin?.let {
            reqBuilder.header("Origin", it)
            reqBuilder.header("Referer", "$it/")
        }

        val usedAuthCookie = hasAuthenticatedYouTubeSession() && profile.supportsCookieAuth
        var hasSapisidHash = false
        if (usedAuthCookie) {
            reqBuilder.header("Cookie", authCookie)
            val origin = profile.origin ?: profile.hostBaseUrl
            buildSapisidHashHeader(authCookie, origin)?.let { sapisidHash ->
                hasSapisidHash = true
                reqBuilder.header("Authorization", sapisidHash)
                reqBuilder.header("X-Origin", origin)
                reqBuilder.header("X-Goog-AuthUser", authUserIndex)
            }
        } else if (profile.supportsCookieAuth) {
            reqBuilder.header("Cookie", session.consentCookie)
        }
        Log.d(
            YT_DIAG_TAG,
            "innertube_player_request corr=$corrId videoId=$videoId client=${profile.clientName} hasVisitor=${!visitor.isNullOrBlank()} hasSts=${sts != null} usedAuthCookie=$usedAuthCookie hasSapisidHash=$hasSapisidHash"
        )

        val rawBody = executeCancellableCall(playbackOkHttpClient, reqBuilder.build()) { response ->
            val playerElapsedMs = (System.nanoTime() - reqStartNs) / 1_000_000L
            stageDetail.lastProbeHttpStatus = response.code
            if (!response.isSuccessful) {
                Log.w(
                    YT_DIAG_TAG,
                    "innertube_player_http corr=$corrId videoId=$videoId client=${profile.clientName} version=${profile.clientVersion} httpStatus=${response.code} usedAuthCookie=$usedAuthCookie durationMs=$playerElapsedMs"
                )
                throw YouTubeHttpException(
                    response.code,
                    "InnerTube ${profile.clientName} player HTTP ${response.code}"
                )
            }
            response.body?.string().orEmpty()
        }
        val playerElapsedMs = (System.nanoTime() - reqStartNs) / 1_000_000L
        currentCoroutineContext().ensureActive()
        val root = JSONObject(rawBody)
        val playability = root.optJSONObject("playabilityStatus")
        val status = playability?.optString("status").orEmpty()
        val reason = playability?.optString("reason").orEmpty()
        stageDetail.playabilityStatus = status
        val streamingData = root.optJSONObject("streamingData")
        val hlsUrl = streamingData?.optString("hlsManifestUrl").orEmpty()

        var lastProbeError: ProbeOutcome? = null

        val adaptiveFormats = streamingData?.optJSONArray("adaptiveFormats")
        val candidates = mutableListOf<InnerTubeFormatCandidate>()
        collectDirectCandidates(adaptiveFormats, audioOnly = true, out = candidates)
        val rawCipherCount = countCipherOnlyAudioFormats(adaptiveFormats)
        if (rawCipherCount > 0) {
            val deciphered = decipherAudioCandidates(videoId, adaptiveFormats)
            deciphered.forEach { dec ->
                if (candidates.none { it.itag == dec.itag }) {
                    candidates.add(dec)
                }
            }
        }
        val hasHls = hlsUrl.startsWith("http", ignoreCase = true)
        stageDetail.streamCount = candidates.size
        stageDetail.cipherOnlyCount = if (candidates.isEmpty()) rawCipherCount else 0
        stageDetail.hasHlsUrl = hasHls
        stageDetail.usableStreamReturned = candidates.isNotEmpty() || hasHls
        Log.d(
            YT_DIAG_TAG,
            "innertube_player_parsed corr=$corrId videoId=$videoId client=${profile.clientName} durationMs=$playerElapsedMs playabilityStatus=$status reason=${redactSensitiveUrls(reason).take(120)} audioCount=${candidates.size} rawCipherCount=$rawCipherCount hasHls=$hasHls"
        )

        if (candidates.isNotEmpty()) {
            val ordered = orderInnerTubeCandidates(candidates, preferHighQuality)
            for (candidate in ordered.take(3)) {
                currentCoroutineContext().ensureActive()
                val playableUrl = deobfuscateThrottlingUrlSafe(videoId, candidate.url)
                val urlHeaders = streamHeadersForTargetUrl(playableUrl, profile.userAgent, usedAuthCookie)
                val probe = verifySingleProbe(playableUrl, isHls = false, headers = urlHeaders, corrId = corrId)
                stageDetail.lastProbedIsHls = false
                stageDetail.lastProbedFormat = candidate.mimeType
                stageDetail.lastProbeHttpStatus = probe.statusCode
                Log.d(
                    YT_DIAG_TAG,
                    "innertube_probe corr=$corrId videoId=$videoId client=${profile.clientName} itag=${candidate.itag} mime=${candidate.mimeType} isHls=false ok=${probe.ok} httpStatus=${probe.statusCode} contentType=${probe.contentType}"
                )
                if (probe.ok) {
                    return ResolvedAudioStream(
                        url = playableUrl,
                        mimeType = candidate.mimeType,
                        bitrateBps = candidate.bitrateBps,
                        expiresAtMs = parseUrlExpirationMs(playableUrl),
                        userAgent = profile.userAgent,
                        isHls = false,
                        requestHeaders = urlHeaders
                    )
                }
                lastProbeError = probe
                if (probe.statusCode == 402 || probe.statusCode == 429) {
                    throw YouTubeHttpException(
                        probe.statusCode,
                        probe.errorMessage ?: "Stream access rejected (HTTP ${probe.statusCode})"
                    )
                }
            }
        }

        if (hasHls) {
            currentCoroutineContext().ensureActive()
            val hlsHeaders = streamHeadersForTargetUrl(hlsUrl, profile.userAgent, usedAuthCookie)
            val hlsProbe = verifySingleProbe(hlsUrl, isHls = true, headers = hlsHeaders, corrId = corrId)
            stageDetail.lastProbedIsHls = true
            stageDetail.lastProbedFormat = "application/x-mpegURL"
            stageDetail.lastProbeHttpStatus = hlsProbe.statusCode
            Log.d(
                YT_DIAG_TAG,
                "innertube_probe corr=$corrId videoId=$videoId client=${profile.clientName} isHls=true ok=${hlsProbe.ok} httpStatus=${hlsProbe.statusCode} contentType=${hlsProbe.contentType}"
            )
            if (hlsProbe.ok) {
                return ResolvedAudioStream(
                    url = hlsUrl,
                    mimeType = "application/x-mpegURL",
                    bitrateBps = 128_000,
                    expiresAtMs = parseUrlExpirationMs(hlsUrl),
                    userAgent = profile.userAgent,
                    isHls = true,
                    requestHeaders = hlsHeaders
                )
            }
            lastProbeError = hlsProbe
        }

        extractPlayabilityFailure(root)?.let { throw it }
        if (lastProbeError != null && lastProbeError.statusCode >= 400) {
            throw YouTubeHttpException(
                lastProbeError.statusCode,
                lastProbeError.errorMessage ?: "YouTube stream probe HTTP ${lastProbeError.statusCode}"
            )
        }
        if (rawCipherCount > 0 && candidates.isEmpty()) {
            throw IOException(
                "${profile.clientName} player returned $rawCipherCount cipher-protected audio format(s) that could not be deciphered"
            )
        }
        return null
    }

    private suspend fun decipherAudioCandidates(
        videoId: String,
        formats: JSONArray?
    ): List<InnerTubeFormatCandidate> {
        if (formats == null) return emptyList()
        val deciphered = mutableListOf<InnerTubeFormatCandidate>()
        for (i in 0 until formats.length()) {
            currentCoroutineContext().ensureActive()
            val f = formats.optJSONObject(i) ?: continue
            val mime = f.optString("mimeType")
            if (!mime.startsWith("audio/", ignoreCase = true)) continue
            val directUrl = f.optString("url")
            if (directUrl.startsWith("http", ignoreCase = true)) continue
            val rawCipher = f.optString("signatureCipher").ifBlank { f.optString("cipher") }
            if (rawCipher.isBlank()) continue

            val parts = parseSignatureCipherParams(rawCipher)
            val baseUrl = parts["url"]?.takeIf { it.startsWith("http", ignoreCase = true) } ?: continue
            val obfuscatedSig = parts["s"]
            val sp = parts["sp"]?.takeIf { it.isNotBlank() } ?: "signature"

            val resolvedUrl = if (!obfuscatedSig.isNullOrBlank()) {
                val plainSig = try {
                    withNewPipeCancellationJob {
                        YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, obfuscatedSig)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(YT_DIAG_TAG, "sig_deobf_error videoId=$videoId err=${t.javaClass.simpleName}:${redactSensitiveUrls(t.message)}")
                    null
                } ?: continue
                assembleSignedStreamUrl(baseUrl, sp, plainSig)
            } else {
                baseUrl
            }

            deciphered.add(
                InnerTubeFormatCandidate(
                    itag = f.optInt("itag", 0),
                    url = resolvedUrl,
                    mimeType = mime.substringBefore(';').trim(),
                    bitrateBps = f.optInt("averageBitrate", f.optInt("bitrate", 128_000))
                )
            )
        }
        return deciphered
    }

    private suspend fun resolveViaNewPipe(
        videoId: String,
        preferHighQuality: Boolean,
        corrId: String = "res-uncorrelated",
        stageDetail: ResolverStageDetail = ResolverStageDetail()
    ): ResolvedAudioStream? {
        currentCoroutineContext().ensureActive()
        val npStartNs = System.nanoTime()
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val (rawAudioStreams, rawHlsUrl, firstInfoError) = withNewPipeCancellationJob {
            val extractor = ServiceList.YouTube.getStreamExtractor(watchUrl)
            extractor.fetchPage()
            var audioErr: Throwable? = null
            val streams = try {
                extractor.audioStreams
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                audioErr = t
                emptyList()
            }
            val hls = try {
                extractor.hlsUrl.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (audioErr == null) audioErr = t
                ""
            }
            Triple(streams, hls, audioErr)
        }
        currentCoroutineContext().ensureActive()
        val extractElapsedMs = (System.nanoTime() - npStartNs) / 1_000_000L
        val orderedStreams = selectOrderedNewPipeAudioStreams(rawAudioStreams, preferHighQuality)
        val hasHls = rawHlsUrl.startsWith("http", ignoreCase = true)
        stageDetail.streamCount = orderedStreams.size
        stageDetail.hasHlsUrl = hasHls
        stageDetail.usableStreamReturned = orderedStreams.isNotEmpty() || hasHls
        Log.d(
            YT_DIAG_TAG,
            "newpipe_extracted corr=$corrId videoId=$videoId durationMs=$extractElapsedMs hasAuthSession=${hasAuthenticatedYouTubeSession()} audioStreamCount=${orderedStreams.size} hasHls=$hasHls firstError=${firstInfoError?.javaClass?.simpleName}"
        )

        val fallbackUa = UA_VISIONOS
        val hasAuth = hasAuthenticatedYouTubeSession()

        var lastProbeError: ProbeOutcome? = null
        for (audioStream in orderedStreams.take(3)) {
            currentCoroutineContext().ensureActive()
            val url = audioStream.content
            if (!url.startsWith("http", ignoreCase = true)) continue
            val ua = inferUserAgentForStreamUrl(url, fallbackDesktopUa = fallbackUa)
            val headers = streamHeadersForTargetUrl(url, ua, hasAuth)
            val probe = verifySingleProbe(url, isHls = false, headers = headers, corrId = corrId)
            val mime = audioStream.format?.mimeType?.takeIf { it.isNotBlank() } ?: "audio/mp4"
            stageDetail.lastProbedIsHls = false
            stageDetail.lastProbedFormat = mime
            stageDetail.lastProbeHttpStatus = probe.statusCode
            Log.d(
                YT_DIAG_TAG,
                "newpipe_probe corr=$corrId videoId=$videoId mime=$mime itag=${audioStream.itagItem?.id ?: 0} isHls=false ok=${probe.ok} httpStatus=${probe.statusCode} contentType=${probe.contentType}"
            )
            if (probe.ok) {
                val bps = (audioStream.averageBitrate.takeIf { it > 0 } ?: audioStream.bitrate)
                    .coerceAtLeast(64) * 1000
                return ResolvedAudioStream(
                    url = url,
                    mimeType = mime,
                    bitrateBps = bps,
                    expiresAtMs = parseUrlExpirationMs(url),
                    userAgent = ua,
                    isHls = false,
                    requestHeaders = headers
                )
            }
            lastProbeError = probe
            if (probe.statusCode == 402 || probe.statusCode == 429) {
                throw YouTubeHttpException(
                    probe.statusCode,
                    probe.errorMessage ?: "YouTube stream access rejected (HTTP ${probe.statusCode})"
                )
            }
        }

        if (hasHls) {
            currentCoroutineContext().ensureActive()
            val hlsUa = inferUserAgentForStreamUrl(rawHlsUrl, fallbackDesktopUa = fallbackUa)
            val hlsHeaders = streamHeadersForTargetUrl(rawHlsUrl, hlsUa, hasAuth)
            val hlsProbe = verifySingleProbe(rawHlsUrl, isHls = true, headers = hlsHeaders, corrId = corrId)
            stageDetail.lastProbedIsHls = true
            stageDetail.lastProbedFormat = "application/x-mpegURL"
            stageDetail.lastProbeHttpStatus = hlsProbe.statusCode
            Log.d(
                YT_DIAG_TAG,
                "newpipe_probe corr=$corrId videoId=$videoId mime=application/x-mpegURL isHls=true ok=${hlsProbe.ok} httpStatus=${hlsProbe.statusCode} contentType=${hlsProbe.contentType}"
            )
            if (hlsProbe.ok) {
                return ResolvedAudioStream(
                    url = rawHlsUrl,
                    mimeType = "application/x-mpegURL",
                    bitrateBps = 128_000,
                    expiresAtMs = parseUrlExpirationMs(rawHlsUrl),
                    userAgent = hlsUa,
                    isHls = true,
                    requestHeaders = hlsHeaders
                )
            }
            lastProbeError = hlsProbe
            if (hlsProbe.statusCode == 402 || hlsProbe.statusCode == 429) {
                throw YouTubeHttpException(
                    hlsProbe.statusCode,
                    hlsProbe.errorMessage ?: "YouTube stream access rejected (HTTP ${hlsProbe.statusCode})"
                )
            }
        }

        if (lastProbeError != null && lastProbeError.statusCode >= 400) {
            throw YouTubeHttpException(
                lastProbeError.statusCode,
                lastProbeError.errorMessage ?: "YouTube stream probe HTTP ${lastProbeError.statusCode}"
            )
        }
        if (firstInfoError != null) {
            throw IOException(firstInfoError.message ?: firstInfoError.javaClass.simpleName, firstInfoError)
        }
        return null
    }

    private fun streamHeadersForTargetUrl(
        url: String,
        userAgent: String,
        includeYouTubeCookie: Boolean
    ): Map<String, String> {
        val host = url.toHttpUrlOrNull()?.host.orEmpty().lowercase()
        val isYouTubeHost = host == "youtube.com" || host.endsWith(".youtube.com")
        return buildMap {
            put("User-Agent", userAgent)
            if (includeYouTubeCookie && isYouTubeHost && authCookie.isNotBlank()) {
                put("Cookie", authCookie)
            }
        }
    }

    internal suspend fun verifySingleProbe(
        url: String,
        isHls: Boolean,
        headers: Map<String, String> = emptyMap(),
        corrId: String = "res-uncorrelated"
    ): ProbeOutcome {
        currentCoroutineContext().ensureActive()
        val builder = Request.Builder().url(url).get()
        if (!isHls) {
            builder.header("Range", "bytes=0-1023")
        }
        headers.forEach { (k, v) -> builder.header(k, v) }

        return try {
            executeCancellableCall(probeOkHttpClient, builder.build()) { resp ->
                val contentType = resp.header("Content-Type").orEmpty()
                val ok = resp.code in 200..299 && !contentType.startsWith("text/html", ignoreCase = true)
                if (ok) {
                    ProbeOutcome(ok = true, statusCode = resp.code, contentType = contentType)
                } else {
                    val msg = if (contentType.startsWith("text/html", ignoreCase = true)) {
                        "Unexpected HTML response (HTTP ${resp.code})"
                    } else {
                        "YouTube stream probe HTTP ${resp.code}"
                    }
                    ProbeOutcome(ok = false, statusCode = resp.code, errorMessage = msg, contentType = contentType)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(
                YT_DIAG_TAG,
                "stream_probe_error corr=$corrId host=${safeUrlHost(url)} isHls=$isHls error=${e.javaClass.simpleName}:${redactSensitiveUrls(e.message)}"
            )
            ProbeOutcome(ok = false, statusCode = 0, errorMessage = redactSensitiveUrls(e.message))
        }
    }

        private fun countCipherOnlyAudioFormats(formats: JSONArray?): Int {
            if (formats == null) return 0
            var count = 0
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val mime = f.optString("mimeType")
                val directUrl = f.optString("url")
                val cipher = f.optString("signatureCipher").ifBlank { f.optString("cipher") }
                if (mime.startsWith("audio/", ignoreCase = true) && !directUrl.startsWith("http", true) && cipher.isNotBlank()) {
                    count++
                }
            }
            return count
        }

        private fun collectDirectCandidates(
            formats: JSONArray?,
            audioOnly: Boolean,
            out: MutableList<InnerTubeFormatCandidate>
        ) {
            if (formats == null) return
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val mime = f.optString("mimeType")
                val url = f.optString("url")
                if (!url.startsWith("http", ignoreCase = true)) continue
                if (audioOnly && !mime.startsWith("audio/", ignoreCase = true)) continue
                if (!audioOnly && mime.isBlank()) continue
                out.add(
                    InnerTubeFormatCandidate(
                        itag = f.optInt("itag", 0),
                        url = url,
                        mimeType = mime.substringBefore(';').trim(),
                        bitrateBps = f.optInt("averageBitrate", f.optInt("bitrate", 128_000))
                    )
                )
            }
        }

    private fun queryWebRemixSearch(
        query: String,
        params: String?,
        limit: Int
    ): List<Track> {
        val session = sessionManager.currentHeaderProfile()
        val clientObj = JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", WEB_REMIX_CLIENT_VERSION)
            .put("hl", "en")
            .put("gl", "US")

        val payload = JSONObject()
            .put("context", JSONObject().put("client", clientObj))
            .put("query", query)
        if (!params.isNullOrBlank()) {
            payload.put("params", params)
        }

        val reqBuilder = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("User-Agent", session.desktopUserAgent)
            .header("Accept-Language", session.acceptLanguage)
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", WEB_REMIX_CLIENT_VERSION)

        sessionManager.getOrFetchVisitorData(playbackOkHttpClient)?.let { visitor ->
            reqBuilder.header("X-Goog-Visitor-Id", visitor)
        }

        playbackOkHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw YouTubeHttpException(
                    response.code,
                    "YouTube Music search HTTP ${response.code}"
                )
            }
            val body = response.body?.string().orEmpty()
            return parseWebRemixSearchJson(body, queryHint = query).take(limit)
        }
    }

    private fun queryWebRemixNextRecommendations(
        videoId: String,
        limit: Int
    ): List<Track> {
        val cleanId = videoId.trim()
        if (!VIDEO_ID_REGEX.matches(cleanId)) return emptyList()
        val session = sessionManager.currentHeaderProfile()
        val clientObj = JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", WEB_REMIX_CLIENT_VERSION)
            .put("hl", "en")
            .put("gl", "US")

        val payload = JSONObject()
            .put("context", JSONObject().put("client", clientObj))
            .put("videoId", cleanId)
            .put("playlistId", "RDAMVM$cleanId")
            .put("isAudioOnly", true)

        val reqBuilder = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/next?prettyPrint=false")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("User-Agent", session.desktopUserAgent)
            .header("Accept-Language", session.acceptLanguage)
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", WEB_REMIX_CLIENT_VERSION)

        sessionManager.getOrFetchVisitorData(playbackOkHttpClient)?.let { visitor ->
            reqBuilder.header("X-Goog-Visitor-Id", visitor)
        }

        playbackOkHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("YouTube Music next HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            return parseWebRemixNextJson(body, queryHint = "Liked Mix").take(limit)
        }
    }

    private fun fetchRelatedViaNewPipe(videoId: String, limit: Int): List<Track> {
        val cleanId = videoId.trim()
        if (!VIDEO_ID_REGEX.matches(cleanId)) return emptyList()
        ensureNewPipeInitialized()
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$cleanId")
        return info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { item ->
                val relId = extractVideoId(item.url) ?: return@mapNotNull null
                val title = item.name?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                val artist = item.uploaderName?.removeSuffix(" - Topic")?.trim()?.ifBlank { "YouTube Music" }
                    ?: "YouTube Music"
                val thumb = item.thumbnails.maxByOrNull { it.width * it.height }?.url
                    ?: "https://i.ytimg.com/vi/$relId/hqdefault.jpg"
                buildYouTubeTrack(
                    videoId = relId,
                    title = title,
                    artist = artist,
                    album = "YouTube Music",
                    durationMs = (item.duration.takeIf { it > 0 } ?: 190L) * 1000L,
                    artworkUrl = upscaleArtworkUrl(thumb),
                    queryHint = "Related"
                )
            }
            .take(limit)
    }

    private fun searchViaNewPipe(query: String, limit: Int): List<Track> {
        ensureNewPipeInitialized()
        val handler = ServiceList.YouTube.searchQHFactory.fromQuery(query)
        val searchInfo = SearchInfo.getInfo(ServiceList.YouTube, handler)
        return searchInfo.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { item ->
                val videoId = extractVideoId(item.url) ?: return@mapNotNull null
                val title = item.name?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                val artist = item.uploaderName?.removeSuffix(" - Topic")?.trim()?.ifBlank { "YouTube" }
                    ?: "YouTube"
                val thumb = item.thumbnails.maxByOrNull { it.width * it.height }?.url
                    ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                buildYouTubeTrack(
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    album = "YouTube",
                    durationMs = (item.duration.takeIf { it > 0 } ?: 180L) * 1000L,
                    artworkUrl = upscaleArtworkUrl(thumb),
                    queryHint = query
                )
            }
            .take(limit)
    }

    companion object {
        val shared: YouTubeMusicClient by lazy { YouTubeMusicClient() }

        private const val INNERTUBE_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val WEB_REMIX_CLIENT_VERSION = "1.20260804.16.00"
        private const val WEB_CLIENT_VERSION = "2.20260805.01.00"
        private const val SONGS_FILTER_PARAMS = "EgWKAQIIAWoMEAMQBBAJEAoQBRAV"
        private const val NEWPIPE_TIMEOUT_MS = 18_000L
        private const val WEB_CLIENT_TIMEOUT_MS = 10_000L
        private const val DEFAULT_CACHE_TTL_MS = 4L * 60L * 60L * 1000L
        private const val CACHE_SAFETY_BUFFER_MS = 2L * 60L * 1000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")

        private val WATCH_PARAM_REGEX = Regex("[?&]v=([A-Za-z0-9_-]{11})")
        private val SHORT_URL_REGEX = Regex("(?:youtu\\.be/|youtube\\.com/(?:shorts|embed|live)/)([A-Za-z0-9_-]{11})")
        private val PULSEYT_URI_REGEX = Regex("^pulseyt://(?:watch\\?v=)?([A-Za-z0-9_-]{11})")
        private val EXPIRE_PARAM_REGEX = Regex("[?&]expire=(\\d+)")
        private val CLIENT_PARAM_REGEX = Regex("(?:[?&]c=|/c/)([A-Z0-9_]+)")

        private val HTTP_401_REGEX = Regex("\\b401\\b")
        private val HTTP_402_REGEX = Regex("\\b402\\b")
        private val HTTP_403_REGEX = Regex("\\b403\\b")
        private val HTTP_429_REGEX = Regex("\\b429\\b")
        private val AGE_RESTRICTED_REGEX = Regex(
            "(?i)(?:age[-\\s_]*restricted|confirm\\s+your\\s+age|inappropriate\\s+for\\s+(?:some\\s+users|certain\\s+audiences)|\\bage\\s+verif)"
        )
        private val BOT_CHECK_REGEX = Regex(
            "(?i)(?:confirm\\s+you['’`]?re\\s+not\\s+a\\s+bot|sign\\s+in\\s+to\\s+confirm|\\bbot\\s+check\\b|\\bnot\\s+a\\s+bot\\b)"
        )

        private val SENSITIVE_URL_REGEX = Regex("https?://([^/\\s?#]+)[^\\s\"']*")
        private val SENSITIVE_KV_REGEX = Regex(
            "(?i)(cookie|authorization|sapisidhash|sapisid|__secure-[^=\\s]+|visitordata|potoken|token|sig|lsig|signature|key)=([^\\s;,\"'&]+)"
        )

        internal fun redactHostOnly(url: String?): String {
            if (url.isNullOrBlank()) return "unknown"
            return safeUrlHost(url)
        }

        internal fun redactSensitiveUrls(raw: String?): String {
            if (raw.isNullOrBlank()) return "none"
            val urlsRedacted = SENSITIVE_URL_REGEX.replace(raw) { match ->
                val host = match.groupValues.getOrElse(1) { "unknown" }
                "https://$host/<redacted>"
            }
            val kvRedacted = SENSITIVE_KV_REGEX.replace(urlsRedacted) { match ->
                "${match.groupValues[1]}=<redacted>"
            }
            return kvRedacted.replace('\n', ' ').replace('\r', ' ').trim().take(260)
        }

        internal fun extractHttpStatus(error: Throwable?): Int {
            findCause<YouTubeHttpException>(error)?.let { return it.statusCode }
            return 0
        }

        internal fun classifyFailureOrigin(
            error: Throwable?,
            timedOut: Boolean = false,
            cancelled: Boolean = false,
            cipherOnlyCount: Int = 0,
            playabilityStatus: String = ""
        ): String {
            if (cancelled || error is CancellationException) return "LOCAL_APP"
            if (timedOut || findCause<SocketTimeoutException>(error) != null || isNetworkConnectivityError(error)) {
                return "NETWORK_OR_TIMEOUT"
            }
            if (findCause<YouTubeHttpException>(error) != null) return "UPSTREAM_SERVER"
            if (findCause<ReCaptchaException>(error) != null ||
                findCause<SignInConfirmNotBotException>(error) != null ||
                findCause<AgeRestrictedContentException>(error) != null ||
                findCause<GeographicRestrictionException>(error) != null ||
                findCause<PrivateContentException>(error) != null ||
                findCause<PaidContentException>(error) != null ||
                findCause<YoutubeMusicPremiumContentException>(error) != null ||
                findCause<ContentNotAvailableException>(error) != null
            ) {
                return "UPSTREAM_SERVER"
            }
            if (playabilityStatus.isNotBlank() && !playabilityStatus.equals("OK", ignoreCase = true)) {
                return "UPSTREAM_SERVER"
            }
            if (cipherOnlyCount > 0 ||
                findCause<ParsingException>(error) != null ||
                findCause<ExtractionException>(error) != null
            ) {
                return "EXTRACTOR"
            }
            val msg = collectExceptionMessages(error)
            if (msg.contains("UNPLAYABLE", ignoreCase = true) ||
                msg.contains("LOGIN_REQUIRED", ignoreCase = true) ||
                msg.contains("reCaptcha", ignoreCase = true) ||
                msg.contains("no longer supported", ignoreCase = true) ||
                msg.contains("not available", ignoreCase = true) ||
                msg.contains("Unexpected HTML response", ignoreCase = true) ||
                msg.contains("stream probe HTTP", ignoreCase = true)
            ) {
                return "UPSTREAM_SERVER"
            }
            if (msg.contains("cipher-protected", ignoreCase = true) ||
                findCause<org.json.JSONException>(error) != null ||
                error == null
            ) {
                return "EXTRACTOR"
            }
            return if (error is IllegalArgumentException || error is IllegalStateException) {
                "LOCAL_APP"
            } else {
                "EXTRACTOR"
            }
        }

        private fun safeUrlHost(url: String): String {
            return runCatching { java.net.URI(url).host.orEmpty() }
                .getOrDefault("")
                .ifBlank { "unknown" }
        }

        private fun deepestCause(error: Throwable?): Throwable? {
            var cur = error ?: return null
            var depth = 0
            while (cur.cause != null && cur.cause !== cur && depth < 12) {
                cur = cur.cause!!
                depth++
            }
            return cur
        }

        private inline fun <reified T : Throwable> findCause(error: Throwable?): T? {
            var cur = error
            var depth = 0
            while (cur != null && depth < 8) {
                if (cur is T) return cur
                cur = cur.cause
                depth++
            }
            return null
        }

        private fun collectExceptionMessages(error: Throwable?): String {
            if (error == null) return ""
            val parts = LinkedHashSet<String>()
            var cur = error
            var depth = 0
            while (cur != null && depth < 8) {
                cur.message?.trim()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                cur = cur.cause
                depth++
            }
            return parts.joinToString(" | ")
        }

        private fun isRegionRestrictedError(error: Throwable?, message: String): Boolean {
            if (findCause<GeographicRestrictionException>(error) != null) return true
            return REGION_RESTRICTED_REGEX.containsMatchIn(message)
        }

        private fun isPrivateOrUploaderRestrictedError(error: Throwable?, message: String): Boolean {
            if (findCause<PrivateContentException>(error) != null) return true
            return PRIVATE_UPLOADER_RESTRICTED_REGEX.containsMatchIn(message)
        }

        private fun isPaidOrPremiumContentError(error: Throwable?, message: String): Boolean {
            if (findCause<PaidContentException>(error) != null ||
                findCause<YoutubeMusicPremiumContentException>(error) != null
            ) {
                return true
            }
            return PAID_PREMIUM_REGEX.containsMatchIn(message)
        }

        private fun isNetworkConnectivityError(error: Throwable?): Boolean {
            return findCause<UnknownHostException>(error) != null ||
                findCause<ConnectException>(error) != null ||
                findCause<SSLException>(error) != null
        }

        internal fun isAgeRestrictedError(error: Throwable?, message: String = error?.message.orEmpty()): Boolean {
            if (findCause<AgeRestrictedContentException>(error) != null) {
                return true
            }
            return AGE_RESTRICTED_REGEX.containsMatchIn(message)
        }

        internal fun isBotOrSignInRequiredError(error: Throwable?, message: String = error?.message.orEmpty()): Boolean {
            val status = findCause<YouTubeHttpException>(error)?.statusCode ?: 0
            return status == 401 ||
                findCause<SignInConfirmNotBotException>(error) != null ||
                HTTP_401_REGEX.containsMatchIn(message) ||
                message.contains("LOGIN_REQUIRED", ignoreCase = true) ||
                BOT_CHECK_REGEX.containsMatchIn(message)
        }

        internal fun extractPlayabilityFailure(root: JSONObject): IOException? {
            val playability = root.optJSONObject("playabilityStatus") ?: return null
            val status = playability.optString("status").trim()
            if (status.isBlank() || status.equals("OK", ignoreCase = true)) return null
            val reason = playability.optString("reason").trim()
            return IOException(if (reason.isNotBlank()) "$status: $reason" else status)
        }

        internal fun errorPriority(error: Throwable?): Int {
            val status = findCause<YouTubeHttpException>(error)?.statusCode ?: 0
            val msg = collectExceptionMessages(error)
            return when {
                status == 402 || HTTP_402_REGEX.containsMatchIn(msg) -> 100
                status == 429 || findCause<ReCaptchaException>(error) != null || HTTP_429_REGEX.containsMatchIn(msg) || msg.contains("reCaptcha", true) -> 95
                isBotOrSignInRequiredError(error, msg) -> 90
                isAgeRestrictedError(error, msg) -> 88
                isRegionRestrictedError(error, msg) ||
                    isPrivateOrUploaderRestrictedError(error, msg) ||
                    isPaidOrPremiumContentError(error, msg) -> 86
                status == 403 || HTTP_403_REGEX.containsMatchIn(msg) -> 82
                msg.contains("no longer supported", true) || msg.contains("unsupported", true) -> 78
                findCause<ContentNotAvailableException>(error) != null ||
                    msg.contains("UNPLAYABLE", true) ||
                    msg.contains("unavailable", true) -> 70
                isNetworkConnectivityError(error) -> 60
                findCause<SocketTimeoutException>(error) != null -> 20
                error != null -> 50
                else -> 0
            }
        }

        internal fun selectMoreInformativeError(current: Throwable?, candidate: Throwable?): Throwable? {
            if (candidate == null) return current
            if (current == null) return candidate
            return if (errorPriority(candidate) > errorPriority(current)) candidate else current
        }

        internal fun humanReadableSearchError(error: Throwable?): String {
            val status = findCause<YouTubeHttpException>(error)?.statusCode ?: 0
            val raw = error?.message.orEmpty()
            return when {
                status == 402 || HTTP_402_REGEX.containsMatchIn(raw) ->
                    "YouTube Music returned HTTP 402 during search (request restricted or rate-limited). Try again shortly or connect YouTube in Settings."
                status == 429 || findCause<ReCaptchaException>(error) != null || HTTP_429_REGEX.containsMatchIn(raw) || raw.contains("reCaptcha", true) ->
                    "YouTube Music temporarily rate-limited search (HTTP 429). Please wait a moment."
                status == 403 || HTTP_403_REGEX.containsMatchIn(raw) ->
                    "YouTube Music search was rejected (HTTP 403). Try reconnecting YouTube in Settings."
                findCause<SocketTimeoutException>(error) != null ->
                    "YouTube Music search timed out. Check your connection and retry."
                isNetworkConnectivityError(error) ->
                    "Network error while searching YouTube Music. Check your internet connection and retry."
                raw.isNotBlank() ->
                    "YouTube Music search failed: $raw"
                else ->
                    "YouTube Music search failed. Check your connection and try again."
            }
        }

        internal fun humanReadableExtractionError(
            error: Throwable?,
            hasAuthenticatedSession: Boolean = shared.hasAuthenticatedYouTubeSession(),
            hasConfiguredCookie: Boolean = hasAuthenticatedSession || shared.hasConfiguredCookieSession(),
            wasSessionRejected: Boolean = shared.wasAuthSessionRejected()
        ): String {
            val status = findCause<YouTubeHttpException>(error)?.statusCode ?: 0
            val raw = error?.message.orEmpty().trim()
            val combinedMsg = collectExceptionMessages(error).ifBlank { raw }
            return when {
                status == 402 || HTTP_402_REGEX.containsMatchIn(combinedMsg) ->
                    "YouTube Music rejected this stream request (HTTP 402). The connection or client may be rate-limited or restricted."
                status == 429 || findCause<ReCaptchaException>(error) != null || HTTP_429_REGEX.containsMatchIn(combinedMsg) || combinedMsg.contains("reCaptcha", true) ->
                    "YouTube rate-limited playback requests (HTTP 429). Please wait a moment and retry."
                isAgeRestrictedError(error, combinedMsg) ->
                    when {
                        hasAuthenticatedSession && !wasSessionRejected ->
                            "This YouTube track is age-restricted and could not be verified with your connected session. Try reconnecting YouTube in Settings."
                        wasSessionRejected || hasConfiguredCookie ->
                            "Your saved YouTube session could not verify age-restricted playback. Tap 'Connect YouTube' to reconnect."
                        else ->
                            "This YouTube track is age-restricted and requires connecting your YouTube account."
                    }
                isBotOrSignInRequiredError(error, combinedMsg) ->
                    when {
                        hasAuthenticatedSession && !wasSessionRejected ->
                            "YouTube declined playback for this track on your current connection. Try refreshing or reconnecting YouTube in Settings."
                        wasSessionRejected || hasConfiguredCookie ->
                            "Your saved YouTube session was rejected or expired. Tap 'Connect YouTube' to reconnect your account."
                        else ->
                            "YouTube requires sign-in on this network (bot check). Tap 'Connect YouTube' below or in Settings."
                    }
                isRegionRestrictedError(error, combinedMsg) ->
                    "This track is unavailable on YouTube in your region."
                isPrivateOrUploaderRestrictedError(error, combinedMsg) ->
                    "This YouTube track is private or restricted by the uploader."
                isPaidOrPremiumContentError(error, combinedMsg) ->
                    "This track requires YouTube Music Premium or channel membership and cannot be streamed here."
                status == 403 || HTTP_403_REGEX.containsMatchIn(combinedMsg) ->
                    if (hasAuthenticatedSession) {
                        "YouTube stream access was denied (HTTP 403) even with your connected session. Retry or reconnect YouTube in Settings."
                    } else {
                        "YouTube stream access was rejected (HTTP 403). Try connecting YouTube in Settings."
                    }
                raw.contains("no longer supported", ignoreCase = true) || combinedMsg.contains("no longer supported", ignoreCase = true) ->
                    if (hasAuthenticatedSession) {
                        "YouTube rejected this stream request as unsupported on this client/device ($raw). Try reconnecting YouTube in Settings or play local/offline tracks."
                    } else {
                        "YouTube rejected anonymous stream playback ($raw). Tap 'Connect YouTube' below or in Settings, or play local/offline tracks."
                    }
                findCause<ContentNotAvailableException>(error) != null ||
                    raw.contains("UNPLAYABLE", ignoreCase = true) ||
                    raw.contains("unavailable", ignoreCase = true) -> {
                    val detail = raw.ifBlank { combinedMsg }
                    if (detail.contains("not available on this app", ignoreCase = true)) {
                        "YouTube declined playback on this client ($detail)."
                    } else if (detail.isNotBlank()) {
                        "YouTube reported this stream as unavailable: $detail"
                    } else {
                        "YouTube reported this stream as unavailable."
                    }
                }
                isNetworkConnectivityError(error) ->
                    "Network error reaching YouTube${if (raw.isNotBlank()) " ($raw)" else ""}. Check your connection and retry."
                findCause<SocketTimeoutException>(error) != null ->
                    "YouTube stream resolution timed out. Check your connection and retry."
                raw.isNotBlank() ->
                    "Could not resolve audio stream: $raw"
                else ->
                    "Could not resolve audio stream"
            }
        }

        private val REGION_RESTRICTED_REGEX = Regex(
            "(?i)(?:in\\s+your\\s+(?:country|region)|country\\s+on\\s+copyright|not\\s+available\\s+in\\s+your\\s+(?:country|region)|\\bgeo[-\\s]*restricted\\b|\\bblocked\\s+(?:it|in\\s+your\\s+country)\\b)"
        )
        private val PRIVATE_UPLOADER_RESTRICTED_REGEX = Regex(
            "(?i)(?:\\bprivate\\s+video\\b|\\bthis\\s+video\\s+is\\s+private\\b|uploader\\s+has\\s+not\\s+made|restricted\\s+by\\s+the\\s+uploader)"
        )
        private val PAID_PREMIUM_REGEX = Regex(
            "(?i)(?:members[-\\s]*only|youtube\\s+music\\s+premium|requires\\s+payment|purchase\\s+to\\s+watch)"
        )

        private const val UA_ANDROID_VR =
            "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
        private const val UA_SAFARI =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"
        private const val UA_IOS =
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)"
        private val UA_VISIONOS: String by lazy {
            YoutubeParsingHelper.getVisionOsUserAgent(Localization("en", "US"))
        }
        private const val UA_TV =
            "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
        private const val DEFAULT_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"

        private data class InnerTubeClientProfile(
            val clientName: String,
            val clientVersion: String,
            val clientIdHeader: String,
            val userAgent: String,
            val platform: String? = null,
            val origin: String? = null,
            val hostBaseUrl: String = "https://www.youtube.com",
            val supportsCookieAuth: Boolean = false
        )

        private val CLIENT_WEB_REMIX = InnerTubeClientProfile(
            clientName = "WEB_REMIX",
            clientVersion = WEB_REMIX_CLIENT_VERSION,
            clientIdHeader = "67",
            userAgent = DEFAULT_DESKTOP_UA,
            platform = "DESKTOP",
            origin = "https://music.youtube.com",
            hostBaseUrl = "https://music.youtube.com",
            supportsCookieAuth = true
        )

        private val CLIENT_WEB_HLS = InnerTubeClientProfile(
            clientName = "WEB",
            clientVersion = WEB_CLIENT_VERSION,
            clientIdHeader = "1",
            userAgent = UA_SAFARI,
            platform = "DESKTOP",
            origin = "https://www.youtube.com",
            hostBaseUrl = "https://www.youtube.com",
            supportsCookieAuth = true
        )

        internal fun parseSignatureCipherParams(rawCipher: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            if (rawCipher.isBlank()) return result
            rawCipher.split("&").forEach { pair ->
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val key = runCatching { URLDecoder.decode(pair.substring(0, eq), "UTF-8") }
                        .getOrDefault(pair.substring(0, eq))
                    val value = runCatching { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }
                        .getOrDefault(pair.substring(eq + 1))
                    if (key.isNotBlank()) {
                        result[key] = value
                    }
                }
            }
            return result
        }

        internal fun assembleSignedStreamUrl(baseUrl: String, sp: String, plainSignature: String): String {
            val paramName = sp.ifBlank { "signature" }
            val encodedSig = URLEncoder.encode(plainSignature, "UTF-8")
            val sep = if (baseUrl.contains("?")) "&" else "?"
            return "$baseUrl$sep$paramName=$encodedSig"
        }

        internal fun isYouTubeSignInSuggestionError(message: String?): Boolean {
            if (message.isNullOrBlank()) return false
            return message.contains("Connect YouTube", ignoreCase = true) ||
                message.contains("reconnecting YouTube", ignoreCase = true) ||
                message.contains("reconnect your account", ignoreCase = true) ||
                message.contains("LOGIN_REQUIRED", ignoreCase = true) ||
                BOT_CHECK_REGEX.containsMatchIn(message) ||
                message.contains("requires sign-in", ignoreCase = true) ||
                message.contains("requires connecting your YouTube account", ignoreCase = true) ||
                message.contains("no longer supported", ignoreCase = true)
        }

        internal fun parseCookieHeader(cookieHeader: String): LinkedHashMap<String, String> {
            val map = LinkedHashMap<String, String>()
            if (cookieHeader.isBlank()) return map
            cookieHeader.split(";").forEach { part ->
                val idx = part.indexOf('=')
                if (idx > 0) {
                    val name = part.substring(0, idx).trim()
                    val value = part.substring(idx + 1).trim()
                    if (name.isNotBlank() && value.isNotBlank()) {
                        map[name] = value
                    }
                }
            }
            return map
        }

        internal fun mergeCookieHeaders(vararg headers: String): String {
            val merged = LinkedHashMap<String, String>()
            headers.forEach { header ->
                merged.putAll(parseCookieHeader(header))
            }
            return merged.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        }

        internal fun inspectCookieComponents(cookieHeader: String): CookieComponentSummary {
            val parsed = parseCookieHeader(cookieHeader)
            if (parsed.isEmpty()) return CookieComponentSummary()
            val hasSapisid = !parsed["SAPISID"].isNullOrBlank() ||
                !parsed["__Secure-3PAPISID"].isNullOrBlank() ||
                !parsed["__Secure-1PAPISID"].isNullOrBlank()
            val hasSid = !parsed["SID"].isNullOrBlank() ||
                !parsed["__Secure-3PSID"].isNullOrBlank() ||
                !parsed["__Secure-1PSID"].isNullOrBlank()
            val hasHsidOrSsid = !parsed["HSID"].isNullOrBlank() || !parsed["SSID"].isNullOrBlank()
            return CookieComponentSummary(
                cookieCount = parsed.size,
                hasSapisid = hasSapisid,
                hasSid = hasSid,
                hasHsidOrSsid = hasHsidOrSsid
            )
        }

        internal fun isValidAuthenticatedCookie(cookieHeader: String): Boolean {
            return inspectCookieComponents(cookieHeader).isAuthenticated
        }

        internal fun extractSapisidFromCookie(cookieHeader: String): String? {
            val cookies = parseCookieHeader(cookieHeader)
            if (cookies.isEmpty()) return null
            return cookies["SAPISID"]
                ?: cookies["__Secure-3PAPISID"]
                ?: cookies["__Secure-1PAPISID"]
        }

        internal fun buildSapisidHashHeader(
            cookieHeader: String,
            origin: String,
            timestampSeconds: Long = System.currentTimeMillis() / 1000L
        ): String? {
            val sapisid = extractSapisidFromCookie(cookieHeader)?.takeIf { it.isNotBlank() } ?: return null
            val input = "$timestampSeconds $sapisid $origin"
            val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            return "SAPISIDHASH ${timestampSeconds}_$hex"
        }

        fun extractVideoId(input: String?): String? {
            if (input.isNullOrBlank()) return null
            val trimmed = input.trim()
            if (VIDEO_ID_REGEX.matches(trimmed)) return trimmed
            PULSEYT_URI_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
            WATCH_PARAM_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
            SHORT_URL_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
            return null
        }

        internal fun inferUserAgentForStreamUrl(
            url: String,
            fallbackDesktopUa: String = DEFAULT_DESKTOP_UA
        ): String {
            return when (CLIENT_PARAM_REGEX.find(url)?.groupValues?.getOrNull(1)) {
                "VISIONOS" -> UA_VISIONOS
                "IOS" -> UA_IOS
                "ANDROID_VR" -> UA_ANDROID_VR
                "WEB_REMIX" -> DEFAULT_DESKTOP_UA
                "WEB" -> UA_SAFARI
                "ANDROID_MUSIC" ->
                    "com.google.android.apps.youtube.music/7.02.52 (Linux; U; Android 14; en_US)"
                "ANDROID", "ANDROID_TESTSUITE" ->
                    "com.google.android.youtube/19.29.37 (Linux; U; Android 14) gzip"
                "TVHTML5", "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> UA_TV
                else -> if (
                    url.contains("hls_playlist", ignoreCase = true) ||
                    url.contains("hls_variant", ignoreCase = true) ||
                    url.contains(".m3u8", ignoreCase = true) ||
                    url.contains("/file/seg.ts", ignoreCase = true)
                ) UA_SAFARI else fallbackDesktopUa
            }
        }

        internal fun buildDiscoveryQueries(
            moodPrompt: String?,
            preferredGenres: List<String>,
            recentArtists: List<String> = emptyList()
        ): List<String> {
            val prompt = moodPrompt?.trim()?.takeIf { it.isNotBlank() }
            if (prompt != null) {
                return listOf("$prompt songs", "$prompt music hits")
            }
            val artistSeed = recentArtists.firstOrNull { it.isNotBlank() && !it.equals("YouTube Music", true) }
            val topGenre = preferredGenres.firstOrNull { it.isNotBlank() }
            return buildList {
                if (artistSeed != null) add("$artistSeed top songs")
                if (topGenre != null && !topGenre.equals("Electronic", true)) add("top $topGenre hits songs")
                add("top trending global music hits")
                add("chill indie synthwave pop hits")
                add("arijit singh top hits")
            }.distinct().take(3)
        }

        internal fun buildLikedRecommendationQueries(
            likedTracks: List<Track>,
            preferredGenres: List<String> = emptyList()
        ): List<String> {
            val ignoredNames = setOf("youtube music", "youtube", "local device", "cloud drive", "unknown artist", "single")
            val queries = mutableListOf<String>()

            val validArtists = likedTracks
                .map { it.artist.trim() }
                .filter { it.isNotBlank() && it.lowercase() !in ignoredNames }
                .distinct()

            val validSongSeeds = likedTracks
                .filter { it.title.isNotBlank() && it.artist.trim().lowercase() !in ignoredNames }
                .distinctBy { it.trackKey }

            // Seed queries from specific liked songs and artists
            validSongSeeds.take(2).forEach { seed ->
                queries.add("${seed.artist} ${seed.title} similar songs")
            }
            validArtists.take(3).forEach { artist ->
                queries.add("$artist top hits songs")
            }

            val likedGenres = (likedTracks.map { it.genre } + preferredGenres)
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.equals("YouTube", true) && !it.equals("Imported", true) }
                .distinct()
            likedGenres.take(2).forEach { genre ->
                queries.add("best $genre songs hits")
            }

            if (queries.isEmpty()) {
                queries.add("top trending global music hits")
                queries.add("popular pop chill hits")
            }
            return queries.distinct().take(4)
        }

        internal fun parseWebRemixNextJson(rawJson: String, queryHint: String = ""): List<Track> {
            if (rawJson.isBlank()) return emptyList()
            val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
            val renderers = mutableListOf<JSONObject>()
            collectPlaylistPanelRenderers(root, renderers)

            val parsed = LinkedHashMap<String, Track>()
            for (renderer in renderers) {
                val track = parsePlaylistPanelRenderer(renderer, queryHint) ?: continue
                parsed.putIfAbsent(track.id, track)
            }
            return parsed.values.toList()
        }

        private fun collectPlaylistPanelRenderers(node: Any?, out: MutableList<JSONObject>) {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("playlistPanelVideoRenderer")?.let { out.add(it) }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k == "playlistPanelVideoRenderer") continue
                        collectPlaylistPanelRenderers(node.opt(k), out)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        collectPlaylistPanelRenderers(node.opt(i), out)
                    }
                }
            }
        }

        private fun parsePlaylistPanelRenderer(renderer: JSONObject, queryHint: String): Track? {
            val videoId = renderer.optString("videoId").trim()
            if (!VIDEO_ID_REGEX.matches(videoId)) return null

            val title = extractRunsText(renderer.optJSONObject("title")).trim()
            if (title.isBlank()) return null

            val bylineRuns = extractRunsList(
                renderer.optJSONObject("longBylineText") ?: renderer.optJSONObject("shortBylineText")
            )
            val metaTokens = splitMetadataTokens(bylineRuns)
            var durationMs = parseDurationTextMs(extractRunsText(renderer.optJSONObject("lengthText")))
            val nonDurationParts = mutableListOf<String>()
            for (token in metaTokens) {
                val parsedDur = parseDurationTextMs(token)
                if (parsedDur > 0L) {
                    if (durationMs <= 0L) durationMs = parsedDur
                } else if (!IGNORED_TYPE_TOKENS.contains(token.lowercase())) {
                    nonDurationParts.add(token)
                }
            }
            if (durationMs <= 0L) durationMs = 200_000L

            val artist = nonDurationParts.firstOrNull()?.takeIf { it.isNotBlank() } ?: "YouTube Music"
            val album = nonDurationParts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "YouTube Music"

            val thumbnails = renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            var rawArt = ""
            if (thumbnails != null && thumbnails.length() > 0) {
                rawArt = thumbnails.optJSONObject(thumbnails.length() - 1)?.optString("url").orEmpty()
            }
            val artwork = if (rawArt.isNotBlank()) upscaleArtworkUrl(rawArt)
            else "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

            return buildYouTubeTrack(
                videoId = videoId,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                artworkUrl = artwork,
                queryHint = queryHint
            )
        }

        private fun extractRunsList(textObj: JSONObject?): List<String> {
            val runs = textObj?.optJSONArray("runs") ?: return emptyList()
            return buildList {
                for (i in 0 until runs.length()) {
                    val txt = runs.optJSONObject(i)?.optString("text").orEmpty()
                    if (txt.isNotEmpty()) add(txt)
                }
            }
        }

        private fun extractRunsText(textObj: JSONObject?): String =
            extractRunsList(textObj).joinToString("")

        internal fun parseWebRemixSearchJson(rawJson: String, queryHint: String = ""): List<Track> {
            if (rawJson.isBlank()) return emptyList()
            val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
            val renderers = mutableListOf<JSONObject>()
            collectMusicListRenderers(root, renderers)

            val parsed = LinkedHashMap<String, Track>()
            for (renderer in renderers) {
                val track = parseMusicResponsiveRenderer(renderer, queryHint) ?: continue
                parsed.putIfAbsent(track.id, track)
            }
            return parsed.values.toList()
        }

        private fun collectMusicListRenderers(node: Any?, out: MutableList<JSONObject>) {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("musicResponsiveListItemRenderer")?.let { out.add(it) }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k == "musicResponsiveListItemRenderer") continue
                        collectMusicListRenderers(node.opt(k), out)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        collectMusicListRenderers(node.opt(i), out)
                    }
                }
            }
        }

        private fun parseMusicResponsiveRenderer(renderer: JSONObject, queryHint: String): Track? {
            val videoId = renderer.optJSONObject("playlistItemData")?.optString("videoId")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: renderer.optJSONObject("overlay")
                    ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("musicPlayButtonRenderer")
                    ?.optJSONObject("playNavigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: return null
            if (!VIDEO_ID_REGEX.matches(videoId)) return null

            val flexColumns = renderer.optJSONArray("flexColumns") ?: return null
            val col0Runs = extractFlexColumnRuns(flexColumns.optJSONObject(0))
            val title = col0Runs.joinToString("") { it.first }.trim()
            if (title.isBlank()) return null

            val col1Runs = extractFlexColumnRuns(flexColumns.optJSONObject(1))
            val metaTokens = splitMetadataTokens(col1Runs.map { it.first })
            var durationMs = 0L
            val nonDurationParts = mutableListOf<String>()
            for (token in metaTokens) {
                val parsedDur = parseDurationTextMs(token)
                if (parsedDur > 0L) {
                    durationMs = parsedDur
                } else if (!IGNORED_TYPE_TOKENS.contains(token.lowercase())) {
                    nonDurationParts.add(token)
                }
            }
            if (durationMs <= 0L) durationMs = 200_000L

            val artist = nonDurationParts.firstOrNull()?.takeIf { it.isNotBlank() } ?: "YouTube Music"
            val album = nonDurationParts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "YouTube Music"

            val thumbnails = renderer.optJSONObject("thumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
            var rawArt = ""
            if (thumbnails != null && thumbnails.length() > 0) {
                rawArt = thumbnails.optJSONObject(thumbnails.length() - 1)?.optString("url").orEmpty()
            }
            val artwork = if (rawArt.isNotBlank()) upscaleArtworkUrl(rawArt)
            else "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

            return buildYouTubeTrack(
                videoId = videoId,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                artworkUrl = artwork,
                queryHint = queryHint
            )
        }

        private fun extractFlexColumnRuns(col: JSONObject?): List<Pair<String, String?>> {
            val runs = col?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")
                ?: return emptyList()
            val list = mutableListOf<Pair<String, String?>>()
            for (i in 0 until runs.length()) {
                val r = runs.optJSONObject(i) ?: continue
                val txt = r.optString("text")
                val browseId = r.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId")
                if (txt.isNotEmpty()) list.add(txt to browseId)
            }
            return list
        }

        private fun splitMetadataTokens(runs: List<String>): List<String> {
            val joined = runs.joinToString("")
            return joined.split("•", "·", "|")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }

        internal fun parseDurationTextMs(raw: String): Long {
            val trimmed = raw.trim()
            if (!DURATION_PATTERN.matches(trimmed)) return 0L
            val parts = trimmed.split(":").mapNotNull { it.toLongOrNull() }
            return when (parts.size) {
                2 -> ((parts[0] * 60L) + parts[1]) * 1000L
                3 -> ((parts[0] * 3600L) + (parts[1] * 60L) + parts[2]) * 1000L
                else -> 0L
            }
        }

        internal fun upscaleArtworkUrl(url: String): String {
            val clean = url.trim()
            if (clean.isBlank()) return ""
            if (clean.contains("googleusercontent.com") && clean.contains("=w")) {
                return clean.replace(Regex("=w\\d+-h\\d+[^\\s]*"), "=w544-h544-l90-rj")
            }
            if (clean.contains("i.ytimg.com/vi/") && clean.contains("default.jpg")) {
                return clean.replace("mqdefault.jpg", "hqdefault.jpg")
            }
            return clean
        }

        private fun selectOrderedNewPipeAudioStreams(
            streams: List<AudioStream>,
            preferHighQuality: Boolean
        ): List<AudioStream> {
            val valid = streams.filter { it.content.startsWith("http", ignoreCase = true) }
            if (valid.isEmpty()) return emptyList()
            val (mp4, other) = valid.partition {
                val mime = it.format?.mimeType?.lowercase().orEmpty()
                mime.contains("mp4") || mime.contains("m4a")
            }
            val sortFn: (AudioStream) -> Int = { s ->
                val kbps = s.averageBitrate.takeIf { b -> b > 0 } ?: s.bitrate
                if (preferHighQuality) -kbps else kotlin.math.abs(kbps - 96)
            }
            return mp4.sortedBy(sortFn) + other.sortedBy(sortFn)
        }

        internal fun orderInnerTubeCandidates(
            candidates: List<InnerTubeFormatCandidate>,
            preferHighQuality: Boolean
        ): List<InnerTubeFormatCandidate> {
            if (candidates.isEmpty()) return emptyList()
            val (mp4Pool, otherPool) = candidates.partition {
                it.mimeType.contains("mp4", ignoreCase = true)
            }
            val sortFn: (InnerTubeFormatCandidate) -> Int = { c ->
                if (preferHighQuality) -c.bitrateBps else kotlin.math.abs(c.bitrateBps - 96_000)
            }
            return mp4Pool.sortedBy(sortFn) + otherPool.sortedBy(sortFn)
        }

        internal fun selectBestInnerTubeCandidate(
            candidates: List<InnerTubeFormatCandidate>,
            preferHighQuality: Boolean
        ): InnerTubeFormatCandidate? = orderInnerTubeCandidates(candidates, preferHighQuality).firstOrNull()

        internal fun parseUrlExpirationMs(url: String): Long {
            val sec = EXPIRE_PARAM_REGEX.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()
            return if (sec != null && sec > 1_000_000_000L) {
                sec * 1000L
            } else {
                System.currentTimeMillis() + DEFAULT_CACHE_TTL_MS
            }
        }

        private fun buildYouTubeTrack(
            videoId: String,
            title: String,
            artist: String,
            album: String,
            durationMs: Long,
            artworkUrl: String,
            queryHint: String
        ): Track {
            val (genre, mood, bpm) = inferMusicAttributes(title, artist, album, queryHint)
            return Track(
                id = "${Track.YOUTUBE_ID_PREFIX}$videoId",
                title = title,
                artist = artist,
                album = album,
                genre = genre,
                mood = mood,
                bpm = bpm,
                durationMs = durationMs.coerceAtLeast(15_000L),
                artworkUrl = artworkUrl,
                streamUrl = "",
                sourceType = TrackSourceType.YOUTUBE
            ).also { track ->
                shared.registerTrackHint(track)
            }
        }

        internal fun inferMusicAttributes(
            title: String,
            artist: String,
            album: String,
            queryHint: String
        ): Triple<String, String, Int> {
            val text = "$title $artist $album $queryHint".lowercase()
            return when {
                text.contains("lofi") || text.contains("lo-fi") || text.contains("chill") || text.contains("ambient") ->
                    Triple("Lo-Fi Chill", "Chill", 86)
                text.contains("arijit") || text.contains("pritam") || text.contains("shreya") ||
                    text.contains("bollywood") || text.contains("hindi") || text.contains("atif") ||
                    text.contains("rahman") || text.contains("jubin") || text.contains("tum ") ||
                    text.contains("kesariya") || text.contains("dil ") || text.contains("pyaar") ->
                    Triple("Bollywood", "Romantic", 96)
                text.contains("punjabi") || text.contains("diljit") || text.contains("sidhu") || text.contains("ap dhillon") ->
                    Triple("Punjabi Pop", "Energetic", 104)
                text.contains("hip hop") || text.contains("rap") || text.contains("drake") ||
                    text.contains("kendrick") || text.contains("eminem") || text.contains("travis") ->
                    Triple("Hip-Hop", "Bold", 94)
                text.contains("synth") || text.contains("edm") || text.contains("house") ||
                    text.contains("techno") || text.contains("phonk") || text.contains("club") ->
                    Triple("Electronic", "Energetic", 126)
                text.contains("rock") || text.contains("metal") || text.contains("linkin") || text.contains("coldplay") ->
                    Triple("Rock", "Intense", 122)
                text.contains("acoustic") || text.contains("unplugged") || text.contains("classical") || text.contains("piano") ->
                    Triple("Acoustic", "Calm", 82)
                else ->
                    Triple("Pop", "Atmospheric", 112)
            }
        }

        private val DURATION_PATTERN = Regex("^\\d{1,2}:\\d{2}(?::\\d{2})?$")
        private val IGNORED_TYPE_TOKENS = setOf("song", "video", "single", "ep", "album")
    }
}
