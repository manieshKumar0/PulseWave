package com.example.data.remote

import android.util.Log
import com.example.data.model.Track
import com.example.data.model.TrackSourceType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.Dns
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
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "YouTubeMusicClient"

data class ResolvedAudioStream(
    val url: String,
    val mimeType: String,
    val bitrateBps: Int,
    val expiresAtMs: Long,
    val userAgent: String,
    val isHls: Boolean = false,
    val requestHeaders: Map<String, String> = mapOf("User-Agent" to userAgent)
)

internal data class InnerTubeFormatCandidate(
    val itag: Int,
    val url: String,
    val mimeType: String,
    val bitrateBps: Int
)

private object Ipv4PreferredDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        val ipv4 = all.filterIsInstance<Inet4Address>()
        return if (ipv4.isNotEmpty()) ipv4 + all.filter { it !is Inet4Address } else all
    }
}

private class SessionAwareNewPipeDownloader(
    private val client: OkHttpClient,
    private val sessionManager: YouTubeSessionManager,
    private val cookieProvider: () -> String
) : Downloader() {

    override fun execute(request: NewPipeRequest): NewPipeResponse {
        val session = sessionManager.currentHeaderProfile()
        val builder = Request.Builder().url(request.url())

        var hasUserAgent = false
        var hasAcceptLanguage = false
        var hasCookie = false

        request.headers().forEach { (key, values) ->
            when {
                key.equals("User-Agent", ignoreCase = true) -> hasUserAgent = true
                key.equals("Accept-Language", ignoreCase = true) -> hasAcceptLanguage = true
                key.equals("Cookie", ignoreCase = true) -> hasCookie = true
            }
            values.forEach { value ->
                builder.addHeader(key, value)
            }
        }
        val activeAuthCookie = cookieProvider().takeIf {
            it.isNotBlank() && (
                request.url().contains("youtube.com", ignoreCase = true) ||
                    request.url().contains("googlevideo.com", ignoreCase = true)
                )
        }
        if (!hasUserAgent) builder.header("User-Agent", session.desktopUserAgent)
        if (!hasAcceptLanguage) builder.header("Accept-Language", session.acceptLanguage)
        if (!hasCookie) builder.header("Cookie", activeAuthCookie ?: session.consentCookie)

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

        client.newCall(builder.build()).execute().use { response ->
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
    }
}

/**
 * Musify (`youtube_explode_dart`) aligned catalog search & audio stream resolver.
 *
 * - Runs `ANDROID_VR` (plain un-ciphered adaptive audio), `SAFARI` (HLS `.m3u8` manifest),
 *   and `TVHTML5_SIMPLY_EMBEDDED_PLAYER` / `IOS` concurrently via InnerTube `/youtubei/v1/player`
 *   with clean per-client headers (never injecting cross-client `WEB_REMIX` `visitorData`).
 * - Falls back quickly (< 3.5s) via parallel Piped/Invidious race (`PROXY_RACE`) and NewPipe.
 * - Shares an IPv4-preferred `OkHttpClient` + `User-Agent` interceptor directly with ExoPlayer's
 *   `OkHttpDataSource`, ensuring playback chunks and HLS segments never hit header/IP mismatches.
 */
class YouTubeMusicClient {

    @Volatile
    var preferHighQuality: Boolean = true

    private val streamHeadersByUrl = ConcurrentHashMap<String, Map<String, String>>()

    private val streamHeaderInterceptor = Interceptor { chain ->
        val original = chain.request()
        val urlStr = original.url.toString()
        val matchedHeaders = streamHeadersByUrl[urlStr]
        val inferredUa = matchedHeaders?.get("User-Agent")
            ?: if (urlStr.contains("googlevideo.com", ignoreCase = true)) {
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

    val playbackOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(Ipv4PreferredDns)
        .addInterceptor(streamHeaderInterceptor)
        .connectionPool(ConnectionPool(12, 5, TimeUnit.MINUTES))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val probeOkHttpClient: OkHttpClient = playbackOkHttpClient.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val proxyApiClient: OkHttpClient = playbackOkHttpClient.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3500, TimeUnit.MILLISECONDS)
        .build()

    private val sessionManager = YouTubeSessionManager()
    private val proxyResolver = YouTubeProxyResolver(proxyApiClient)
    private val fallbackResolver = FallbackAudioResolver(
        httpClient = proxyApiClient,
        verifyProbe = { url, headers -> verifySingleProbe(url, isHls = false, headers = headers) }
    )

    private data class TrackHint(
        val title: String,
        val artist: String,
        val durationMs: Long
    )

    private val streamCache = ConcurrentHashMap<String, ResolvedAudioStream>()
    private val inFlightResolutions = ConcurrentHashMap<String, CompletableDeferred<Result<ResolvedAudioStream>>>()
    private val lastStrategyByVideo = ConcurrentHashMap<String, StreamBackendStrategy>()
    private val failedStrategiesByVideo = ConcurrentHashMap<String, MutableSet<StreamBackendStrategy>>()
    private val trackHintsByVideo = ConcurrentHashMap<String, TrackHint>()
    private val visitorDataByClient = ConcurrentHashMap<String, String>()

    @Volatile
    private var authCookie: String = ""

    @Volatile
    private var authVisitorData: String = ""

    @Volatile
    private var newPipeInitialized = false

    fun setYouTubeAuthSession(cookie: String, visitorData: String = "") {
        val normalizedCookie = cookie.trim()
        val normalizedVisitor = visitorData.trim()
        if (normalizedCookie == authCookie && normalizedVisitor == authVisitorData) return
        authCookie = normalizedCookie
        authVisitorData = normalizedVisitor
        streamCache.clear()
        failedStrategiesByVideo.clear()
        lastStrategyByVideo.clear()
        visitorDataByClient.clear()
    }

    fun hasAuthenticatedYouTubeSession(): Boolean {
        return authCookie.isNotBlank() && extractSapisidFromCookie(authCookie) != null
    }

    fun registerTrackHint(track: Track) {
        val videoId = track.youtubeVideoId ?: return
        if (track.title.isBlank()) return
        if (trackHintsByVideo.size > 200) trackHintsByVideo.clear()
        trackHintsByVideo[videoId] = TrackHint(
            title = track.title,
            artist = track.artist,
            durationMs = track.durationMs
        )
    }

    private fun ensureNewPipeInitialized() {
        if (newPipeInitialized) return
        synchronized(this) {
            if (!newPipeInitialized) {
                NewPipe.init(
                    SessionAwareNewPipeDownloader(playbackOkHttpClient, sessionManager) { authCookie },
                    Localization("en", "US")
                )
                newPipeInitialized = true
            }
        }
    }

    suspend fun searchMusic(query: String, limit: Int = 25): List<Track> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        val results = LinkedHashMap<String, Track>()

        extractVideoId(trimmed)?.let { directId ->
            fetchTrackByVideoId(directId)?.let { directTrack ->
                results[directTrack.id] = directTrack
            }
        }

        val ytmSongs = runCatching {
            queryWebRemixSearch(query = trimmed, params = SONGS_FILTER_PARAMS, limit = limit)
        }.getOrElse { err ->
            Log.w(TAG, "WEB_REMIX filtered search failed: ${err.message}")
            emptyList()
        }
        ytmSongs.forEach { results.putIfAbsent(it.id, it) }

        if (results.size < 4) {
            val unfiltered = runCatching {
                queryWebRemixSearch(query = trimmed, params = null, limit = limit)
            }.getOrDefault(emptyList())
            unfiltered.forEach { results.putIfAbsent(it.id, it) }
        }

        if (results.isEmpty()) {
            val npResults = runCatching { searchViaNewPipe(trimmed, limit) }.getOrDefault(emptyList())
            npResults.forEach { results.putIfAbsent(it.id, it) }
        }

        results.values.take(limit)
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

    suspend fun prewarmAudioStreams(videoIds: List<String>, maxConcurrent: Int = 3) {
        val targets = videoIds
            .map { it.trim() }
            .filter { VIDEO_ID_REGEX.matches(it) }
            .distinct()
            .filter { id ->
                val cached = streamCache[id]
                cached == null || cached.expiresAtMs <= System.currentTimeMillis() + CACHE_SAFETY_BUFFER_MS
            }
            .take(maxConcurrent.coerceIn(1, 5))

        if (targets.isEmpty()) return
        withContext(Dispatchers.IO) {
            coroutineScope {
                targets.map { id ->
                    async { runCatching { resolveAudioStream(id) } }
                }.awaitAll()
            }
        }
    }

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

    suspend fun resolveAudioStream(
        videoId: String,
        preferHighQuality: Boolean = this.preferHighQuality,
        forceRefresh: Boolean = false,
        trackTitle: String? = null,
        trackArtist: String? = null,
        trackDurationMs: Long = 0L
    ): Result<ResolvedAudioStream> = withContext(Dispatchers.IO) {
        val cleanId = videoId.trim()
        if (cleanId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Invalid YouTube video ID"))
        }
        if (!trackTitle.isNullOrBlank()) {
            trackHintsByVideo[cleanId] = TrackHint(
                title = trackTitle,
                artist = trackArtist.orEmpty(),
                durationMs = trackDurationMs
            )
        }

        if (!forceRefresh) {
            streamCache[cleanId]?.let { cached ->
                if (cached.expiresAtMs > System.currentTimeMillis() + CACHE_SAFETY_BUFFER_MS) {
                    return@withContext Result.success(cached)
                }
            }
            inFlightResolutions[cleanId]?.let { existing ->
                return@withContext existing.await()
            }
        } else {
            streamCache.remove(cleanId)
        }

        val deferred = CompletableDeferred<Result<ResolvedAudioStream>>()
        val raceExisting = inFlightResolutions.putIfAbsent(cleanId, deferred)
        if (!forceRefresh && raceExisting != null) {
            return@withContext raceExisting.await()
        }

        try {
            val result = resolveAudioStreamInternal(cleanId, preferHighQuality)
            result.onSuccess { resolved ->
                streamCache[cleanId] = resolved
                if (streamHeadersByUrl.size > 80) streamHeadersByUrl.clear()
                streamHeadersByUrl[resolved.url] = resolved.requestHeaders
            }
            deferred.complete(result)
            result
        } catch (t: Throwable) {
            val failure = Result.failure<ResolvedAudioStream>(t)
            deferred.complete(failure)
            failure
        } finally {
            inFlightResolutions.remove(cleanId, deferred)
        }
    }

    fun markStreamFailedAndInvalidate(videoId: String) {
        val cleanId = videoId.trim()
        if (cleanId.isBlank()) return
        streamCache.remove(cleanId)?.let { streamHeadersByUrl.remove(it.url) }
        val failedStrategy = lastStrategyByVideo[cleanId]
        if (failedStrategy != null) {
            failedStrategiesByVideo.computeIfAbsent(cleanId) {
                Collections.synchronizedSet(mutableSetOf())
            }.add(failedStrategy)
        }
        sessionManager.rotateSession()
    }

    fun invalidateStreamCache(videoId: String) {
        streamCache.remove(videoId.trim())?.let { streamHeadersByUrl.remove(it.url) }
    }

    private suspend fun resolveAudioStreamInternal(
        cleanId: String,
        preferHighQuality: Boolean
    ): Result<ResolvedAudioStream> {
        val skipped = failedStrategiesByVideo[cleanId]?.toSet().orEmpty()
        var directError: Throwable? = null

        // Stage 1: Musify (youtube_explode_dart) concurrent manifest fetch across standalone clients
        // Uses per-client visitorData + optional WebView SAPISIDHASH session when connected
        val allowDirect = StreamBackendStrategy.INNERTUBE_VR !in skipped ||
            StreamBackendStrategy.INNERTUBE_SAFARI_HLS !in skipped ||
            StreamBackendStrategy.INNERTUBE_EMBED !in skipped

        if (allowDirect) {
            val (resolvedDirect, errDirect) = resolveViaMusifyParallelClients(
                videoId = cleanId,
                preferHighQuality = preferHighQuality,
                skipped = skipped
            )
            if (resolvedDirect != null) {
                return Result.success(resolvedDirect)
            }
            directError = errDirect
        }

        // Stage 2: Fast 3.5s parallel Piped/Invidious proxy race
        if (StreamBackendStrategy.PROXY_RACE !in skipped) {
            val proxyCandidates = proxyResolver.raceProxyCandidates(
                videoId = cleanId,
                preferHighQuality = preferHighQuality,
                timeoutMs = 3_500L
            )
            val ua = sessionManager.currentHeaderProfile().desktopUserAgent
            for (c in proxyCandidates.take(5)) {
                val headers = mapOf("User-Agent" to ua)
                if (verifySingleProbe(c.url, isHls = false, headers = headers)) {
                    lastStrategyByVideo[cleanId] = StreamBackendStrategy.PROXY_RACE
                    return Result.success(
                        ResolvedAudioStream(
                            url = c.url,
                            mimeType = c.mimeType,
                            bitrateBps = c.bitrateBps,
                            expiresAtMs = parseUrlExpirationMs(c.url),
                            userAgent = ua,
                            isHls = false,
                            requestHeaders = headers
                        )
                    )
                }
            }
        }

        // Stage 3: NewPipeExtractor fallback (4s bounded timeout — never skipped on single client error)
        if (StreamBackendStrategy.NEWPIPE_SESSION !in skipped) {
            val npResult = withTimeoutOrNull(4_000L) {
                runCatching { resolveViaNewPipe(cleanId, preferHighQuality) }.getOrNull()
            }
            if (npResult != null) {
                lastStrategyByVideo[cleanId] = StreamBackendStrategy.NEWPIPE_SESSION
                return Result.success(npResult)
            }
        }

        // Stage 4: Matched alternative CDN stream fallback (JioSaavn 320kbps / Audius)
        val hint = trackHintsByVideo[cleanId]
        if (hint != null) {
            val altStream = runCatching {
                fallbackResolver.resolveMatchingStream(
                    title = hint.title,
                    artist = hint.artist,
                    expectedDurationMs = hint.durationMs,
                    preferHighQuality = preferHighQuality
                )
            }.getOrNull()
            if (altStream != null) {
                lastStrategyByVideo[cleanId] = StreamBackendStrategy.PROXY_RACE
                return Result.success(altStream)
            }
        }

        return Result.failure(
            IOException(humanReadableExtractionError(directError), directError)
        )
    }

    private suspend fun resolveViaMusifyParallelClients(
        videoId: String,
        preferHighQuality: Boolean,
        skipped: Set<StreamBackendStrategy>
    ): Pair<ResolvedAudioStream?, Throwable?> = supervisorScope {
        val clientsToQuery = buildList {
            if (StreamBackendStrategy.INNERTUBE_VR !in skipped) {
                add(StreamBackendStrategy.INNERTUBE_VR to CLIENT_ANDROID_VR)
            }
            if (StreamBackendStrategy.INNERTUBE_EMBED !in skipped) {
                add(StreamBackendStrategy.INNERTUBE_EMBED to CLIENT_TV_EMBED)
                add(StreamBackendStrategy.INNERTUBE_EMBED to CLIENT_IOS)
            }
            if (StreamBackendStrategy.INNERTUBE_SAFARI_HLS !in skipped) {
                add(StreamBackendStrategy.INNERTUBE_SAFARI_HLS to CLIENT_SAFARI_HLS)
            }
        }
        if (clientsToQuery.isEmpty()) return@supervisorScope null to null

        val deferredEntries = clientsToQuery.map { (strategy, profile) ->
            strategy to async {
                runCatching {
                    withTimeoutOrNull(4_500L) {
                        fetchPlayerStream(videoId, profile, preferHighQuality)
                    }
                }
            }
        }

        var firstError: Throwable? = null
        for ((index, entry) in deferredEntries.withIndex()) {
            val (strategy, deferred) = entry
            val result = deferred.await()
            val stream = result.getOrNull()
            if (stream != null) {
                lastStrategyByVideo[videoId] = strategy
                for (j in (index + 1) until deferredEntries.size) {
                    deferredEntries[j].second.cancel()
                }
                return@supervisorScope stream to null
            }
            val err = result.exceptionOrNull()
            if (err != null && firstError == null) {
                firstError = err
            }
        }
        null to firstError
    }

    private fun getOrFetchClientVisitorData(profile: InnerTubeClientProfile): String? {
        visitorDataByClient[profile.clientName]?.let { return it }
        if (profile.clientName == "WEB" && authVisitorData.isNotBlank()) {
            return authVisitorData
        }
        val clientJson = JSONObject()
            .put("clientName", profile.clientName)
            .put("clientVersion", profile.clientVersion)
            .put("hl", "en")
            .put("gl", "US")
        profile.platform?.let { clientJson.put("platform", it) }

        val payload = JSONObject().put("context", JSONObject().put("client", clientJson))
        val reqBuilder = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/visitor_id?key=$INNERTUBE_API_KEY&prettyPrint=false")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("User-Agent", profile.userAgent)
            .header("X-YouTube-Client-Name", profile.clientIdHeader)
            .header("X-YouTube-Client-Version", profile.clientVersion)

        return runCatching {
            probeOkHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val visitor = JSONObject(resp.body?.string().orEmpty())
                    .optJSONObject("responseContext")
                    ?.optString("visitorData")
                    ?.takeIf { it.isNotBlank() }
                if (visitor != null) {
                    visitorDataByClient[profile.clientName] = visitor
                }
                visitor
            }
        }.getOrNull()
    }

    private fun fetchPlayerStream(
        videoId: String,
        profile: InnerTubeClientProfile,
        preferHighQuality: Boolean
    ): ResolvedAudioStream? {
        val visitor = getOrFetchClientVisitorData(profile)
        val clientJson = JSONObject()
            .put("clientName", profile.clientName)
            .put("clientVersion", profile.clientVersion)
            .put("hl", "en")
            .put("gl", "US")
        if (!visitor.isNullOrBlank()) {
            clientJson.put("visitorData", visitor)
        }
        profile.platform?.let { clientJson.put("platform", it) }
        profile.osName?.let { clientJson.put("osName", it) }
        profile.osVersion?.let { clientJson.put("osVersion", it) }
        profile.deviceMake?.let { clientJson.put("deviceMake", it) }
        profile.deviceModel?.let { clientJson.put("deviceModel", it) }
        profile.androidSdkVersion?.let { clientJson.put("androidSdkVersion", it) }
        if (profile.embedUrl != null) {
            clientJson.put("clientScreen", "EMBED")
        }

        val contextJson = JSONObject().put("client", clientJson)
        if (profile.embedUrl != null) {
            contextJson.put("thirdParty", JSONObject().put("embedUrl", profile.embedUrl))
        }

        val payload = JSONObject()
            .put("context", contextJson)
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)

        val reqBuilder = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?key=$INNERTUBE_API_KEY&prettyPrint=false")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("User-Agent", profile.userAgent)
            .header("X-YouTube-Client-Name", profile.clientIdHeader)
            .header("X-YouTube-Client-Version", profile.clientVersion)

        if (!visitor.isNullOrBlank()) {
            reqBuilder.header("X-Goog-Visitor-Id", visitor)
        }

        profile.origin?.let {
            reqBuilder.header("Origin", it)
            reqBuilder.header("Referer", "$it/")
        }

        if (authCookie.isNotBlank() && profile.supportsCookieAuth) {
            reqBuilder.header("Cookie", authCookie)
            val origin = profile.origin ?: "https://www.youtube.com"
            buildSapisidHashHeader(authCookie, origin)?.let { sapisidHash ->
                reqBuilder.header("Authorization", sapisidHash)
                reqBuilder.header("X-Origin", origin)
                reqBuilder.header("X-Goog-AuthUser", "0")
            }
        }

        playbackOkHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("InnerTube player HTTP ${response.code}")
            }
            val root = JSONObject(response.body?.string().orEmpty())
            val playability = root.optJSONObject("playabilityStatus")
            val status = playability?.optString("status").orEmpty()
            val streamingData = root.optJSONObject("streamingData")

            val streamHeaders = mapOf("User-Agent" to profile.userAgent)

            // Adaptive audioOnly formats (Musify primary path)
            val candidates = mutableListOf<InnerTubeFormatCandidate>()
            collectDirectCandidates(streamingData?.optJSONArray("adaptiveFormats"), audioOnly = true, out = candidates)
            if (candidates.isNotEmpty()) {
                val ordered = orderInnerTubeCandidates(candidates, preferHighQuality)
                for (candidate in ordered.take(2)) {
                    if (verifySingleProbe(candidate.url, isHls = false, headers = streamHeaders)) {
                        return ResolvedAudioStream(
                            url = candidate.url,
                            mimeType = candidate.mimeType,
                            bitrateBps = candidate.bitrateBps,
                            expiresAtMs = parseUrlExpirationMs(candidate.url),
                            userAgent = profile.userAgent,
                            isHls = false,
                            requestHeaders = streamHeaders
                        )
                    }
                }
            }

            // Safari HLS manifest fallback (Musify YoutubeApiClient.safari path)
            val hlsUrl = streamingData?.optString("hlsManifestUrl").orEmpty()
            if (hlsUrl.startsWith("http", ignoreCase = true) &&
                verifySingleProbe(hlsUrl, isHls = true, headers = streamHeaders)
            ) {
                return ResolvedAudioStream(
                    url = hlsUrl,
                    mimeType = "application/x-mpegURL",
                    bitrateBps = 128_000,
                    expiresAtMs = parseUrlExpirationMs(hlsUrl),
                    userAgent = profile.userAgent,
                    isHls = true,
                    requestHeaders = streamHeaders
                )
            }

            val reason = playability?.optString("reason").orEmpty()
            if (status.isNotBlank() && status != "OK") {
                throw IOException(if (reason.isNotBlank()) "$status: $reason" else status)
            }
            return null
        }
    }

    private fun resolveViaNewPipe(videoId: String, preferHighQuality: Boolean): ResolvedAudioStream? {
        ensureNewPipeInitialized()
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, watchUrl)
        val orderedStreams = selectOrderedNewPipeAudioStreams(streamInfo.audioStreams, preferHighQuality)
        val fallbackUa = sessionManager.currentHeaderProfile().desktopUserAgent

        for (audioStream in orderedStreams.take(3)) {
            val url = audioStream.content
            if (!url.startsWith("http", ignoreCase = true)) continue
            val ua = inferUserAgentForStreamUrl(url, fallbackDesktopUa = fallbackUa)
            val headers = mapOf("User-Agent" to ua)
            if (verifySingleProbe(url, isHls = false, headers = headers)) {
                val mime = audioStream.format?.mimeType?.takeIf { it.isNotBlank() } ?: "audio/mp4"
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
        }
        return null
    }

    private fun verifySingleProbe(
        url: String,
        isHls: Boolean,
        headers: Map<String, String>
    ): Boolean {
        val builder = Request.Builder().url(url).get()
        if (!isHls) {
            builder.header("Range", "bytes=0-1023")
        }
        headers.forEach { (k, v) -> builder.header(k, v) }
        return try {
            probeOkHttpClient.newCall(builder.build()).execute().use { resp ->
                val contentType = resp.header("Content-Type").orEmpty()
                resp.code in 200..299 && !contentType.startsWith("text/html", ignoreCase = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stream probe failed (${url.take(60)}...): ${e.message}")
            false
        }
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
                throw IOException("YouTube Music search HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            return parseWebRemixSearchJson(body, queryHint = query).take(limit)
        }
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

    private fun humanReadableExtractionError(error: Throwable?): String {
        val raw = error?.message.orEmpty()
        return when {
            raw.contains("Sign in to confirm", ignoreCase = true) ||
                raw.contains("LOGIN_REQUIRED", ignoreCase = true) ||
                raw.contains("bot", ignoreCase = true) ->
                if (hasAuthenticatedYouTubeSession()) {
                    "YouTube declined playback for this track on your current connection. Try refreshing or reconnecting YouTube in Settings."
                } else {
                    "YouTube requires sign-in on this network (bot check). Tap 'Connect YouTube' below or in Settings."
                }
            raw.contains("age", ignoreCase = true) ->
                "This YouTube track is age-restricted and requires connecting your YouTube account."
            raw.contains("unavailable", ignoreCase = true) ||
                raw.contains("UNPLAYABLE", ignoreCase = true) ->
                "This track is unavailable on YouTube in your region."
            raw.contains("403", ignoreCase = true) ->
                "YouTube stream access was rejected (403). Try reconnecting YouTube in Settings."
            raw.isNotBlank() ->
                "Could not resolve audio stream: $raw"
            else ->
                "Could not resolve audio stream"
        }
    }

    companion object {
        val shared: YouTubeMusicClient by lazy { YouTubeMusicClient() }

        private const val INNERTUBE_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val WEB_REMIX_CLIENT_VERSION = "1.20250310.01.00"
        private const val SONGS_FILTER_PARAMS = "EgWKAQIIAWoMEAMQBBAJEAoQBRAV"
        private const val DEFAULT_CACHE_TTL_MS = 4L * 60L * 60L * 1000L
        private const val CACHE_SAFETY_BUFFER_MS = 2L * 60L * 1000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")

        private val WATCH_PARAM_REGEX = Regex("[?&]v=([A-Za-z0-9_-]{11})")
        private val SHORT_URL_REGEX = Regex("(?:youtu\\.be/|youtube\\.com/(?:shorts|embed|live)/)([A-Za-z0-9_-]{11})")
        private val PULSEYT_URI_REGEX = Regex("^pulseyt://(?:watch\\?v=)?([A-Za-z0-9_-]{11})")
        private val EXPIRE_PARAM_REGEX = Regex("[?&]expire=(\\d+)")
        private val CLIENT_PARAM_REGEX = Regex("[?&]c=([A-Z0-9_]+)")

        private const val UA_ANDROID_VR =
            "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
        private const val UA_SAFARI =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"
        private const val UA_IOS =
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)"
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
            val osName: String? = null,
            val osVersion: String? = null,
            val deviceMake: String? = null,
            val deviceModel: String? = null,
            val androidSdkVersion: Int? = null,
            val embedUrl: String? = null,
            val origin: String? = null,
            val supportsCookieAuth: Boolean = false
        )

        private val CLIENT_ANDROID_VR = InnerTubeClientProfile(
            clientName = "ANDROID_VR",
            clientVersion = "1.65.10",
            clientIdHeader = "28",
            userAgent = UA_ANDROID_VR,
            osName = "Android",
            osVersion = "12L",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = 32,
            supportsCookieAuth = false
        )

        private val CLIENT_SAFARI_HLS = InnerTubeClientProfile(
            clientName = "WEB",
            clientVersion = "2.20250312.04.00",
            clientIdHeader = "1",
            userAgent = UA_SAFARI,
            platform = "DESKTOP",
            origin = "https://www.youtube.com",
            supportsCookieAuth = true
        )

        private val CLIENT_TV_EMBED = InnerTubeClientProfile(
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            clientIdHeader = "85",
            userAgent = UA_TV,
            embedUrl = "https://www.youtube.com/",
            origin = "https://www.youtube.com",
            supportsCookieAuth = true
        )

        private val CLIENT_IOS = InnerTubeClientProfile(
            clientName = "IOS",
            clientVersion = "20.10.4",
            clientIdHeader = "5",
            userAgent = UA_IOS,
            osName = "iPhone",
            osVersion = "18.3.2.22D82",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            supportsCookieAuth = false
        )

        internal fun isYouTubeSignInSuggestionError(message: String?): Boolean {
            if (message.isNullOrBlank()) return false
            return message.contains("Connect YouTube", ignoreCase = true) ||
                message.contains("LOGIN_REQUIRED", ignoreCase = true) ||
                message.contains("Sign in to confirm", ignoreCase = true) ||
                message.contains("requires sign-in", ignoreCase = true) ||
                message.contains("age-restricted", ignoreCase = true)
        }

        internal fun extractSapisidFromCookie(cookieHeader: String): String? {
            if (cookieHeader.isBlank()) return null
            val cookies = cookieHeader.split(";")
                .mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0) null
                    else part.substring(0, idx).trim() to part.substring(idx + 1).trim()
                }.toMap()
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
                "IOS" -> UA_IOS
                "ANDROID_VR" -> UA_ANDROID_VR
                "WEB" -> UA_SAFARI
                "ANDROID_MUSIC" ->
                    "com.google.android.apps.youtube.music/7.02.52 (Linux; U; Android 14; en_US)"
                "ANDROID", "ANDROID_TESTSUITE" ->
                    "com.google.android.youtube/19.29.37 (Linux; U; Android 14) gzip"
                "TVHTML5", "TVHTML5_SIMPLY_EMBEDDED_PLAYER" -> UA_TV
                else -> if (url.contains("hls_playlist", ignoreCase = true)) UA_SAFARI else fallbackDesktopUa
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
                ?: renderer.optJSONObject("overlay")
                    ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("musicPlayButtonRenderer")
                    ?.optJSONObject("playNavigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optString("videoId")
                    ?.trim()
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
