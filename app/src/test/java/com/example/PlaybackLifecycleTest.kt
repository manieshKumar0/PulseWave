package com.example

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.ListeningSignalEntity
import com.example.data.local.SignalDao
import com.example.data.model.Track
import com.example.data.model.TrackSourceType
import com.example.data.remote.ResolvedAudioStream
import com.example.data.remote.SessionAwareNewPipeDownloader
import com.example.data.remote.YouTubeMusicClient
import com.example.data.remote.YouTubeSessionManager
import com.example.media.PlaybackController
import com.example.media.PulseMediaItemMapper
import com.example.media.PulseMediaSourceFactory
import com.example.media.PulsePlaybackResumptionStore
import com.example.media.ResolvingYouTubeMediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.downloader.Request as NewPipeRequest
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackLifecycleTest {

    @Test
    fun networkCancellation_propagatesToOkHttpCallsAndClosesBodies() = runBlocking {
        val visitorCallReached = CountDownLatch(1)
        val visitorCallExited = CountDownLatch(1)
        val visitorCallCancelled = AtomicBoolean(false)
        val blockingClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val call = chain.call()
                visitorCallReached.countDown()
                try {
                    val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (!call.isCanceled() && System.nanoTime() < deadlineNs) {
                        Thread.sleep(10)
                    }
                    if (call.isCanceled()) {
                        visitorCallCancelled.set(true)
                        throw IOException("Canceled")
                    }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"responseContext":{"visitorData":"v1"}}""".toResponseBody("application/json".toMediaType()))
                        .build()
                } finally {
                    visitorCallExited.countDown()
                }
            }
            .build()

        val client = YouTubeMusicClient(overrideOkHttpClient = blockingClient)
        client.setYouTubeAuthSession("", visitorData = "test_visitor_data")
        val job = async(Dispatchers.IO) {
            client.fetchWebPlayerStreamForTest("dQw4w9WgXcQ")
        }
        assertTrue("HTTP call should start", visitorCallReached.await(3, TimeUnit.SECONDS))
        job.cancel(CancellationException("User skipped"))

        var caughtCancel = false
        try {
            job.await()
        } catch (_: CancellationException) {
            caughtCancel = true
        }
        assertTrue("CancellationException must propagate without swallowing", caughtCancel)
        assertTrue("Blocked OkHttp interceptor must wake up promptly on cancel", visitorCallExited.await(3, TimeUnit.SECONDS))
        assertTrue("OkHttp Call.cancel() must be invoked on coroutine cancellation", visitorCallCancelled.get())

        // Verify probe response body is closed and CancellationException propagates in verifySingleProbe too
        val probeReached = CountDownLatch(1)
        val probeExited = CountDownLatch(1)
        val probeCancelled = AtomicBoolean(false)
        val probeClient = YouTubeMusicClient(
            overrideOkHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    probeReached.countDown()
                    val call = chain.call()
                    try {
                        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while (!call.isCanceled() && System.nanoTime() < deadlineNs) {
                            Thread.sleep(10)
                        }
                        if (call.isCanceled()) {
                            probeCancelled.set(true)
                        }
                        throw IOException("Canceled")
                    } finally {
                        probeExited.countDown()
                    }
                }
                .build()
        )
        val probeJob = async(Dispatchers.IO) {
            probeClient.verifySingleProbe("https://example.com/audio.m4a", isHls = false)
        }
        assertTrue(probeReached.await(3, TimeUnit.SECONDS))
        probeJob.cancel(CancellationException("Skip probe"))
        var probeCaughtCancel = false
        try {
            probeJob.await()
        } catch (_: CancellationException) {
            probeCaughtCancel = true
        }
        assertTrue(probeCaughtCancel)
        assertTrue(probeExited.await(3, TimeUnit.SECONDS))
        assertTrue(probeCancelled.get())

        // Verify NewPipe downloader cancels blocking OkHttp call and closes body when bound Job cancels
        val npCallReached = CountDownLatch(1)
        val npCallExited = CountDownLatch(1)
        val npCallCancelled = AtomicBoolean(false)
        val npBodyClosed = AtomicBoolean(false)
        val trackedBody = object : ResponseBody() {
            override fun contentType() = "text/html".toMediaType()
            override fun contentLength() = -1L
            override fun source(): BufferedSource = Buffer().writeUtf8("<html></html>")
            override fun close() {
                npBodyClosed.set(true)
                super.close()
            }
        }
        val npHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val call = chain.call()
                npCallReached.countDown()
                try {
                    val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (!call.isCanceled() && System.nanoTime() < deadlineNs) {
                        Thread.sleep(10)
                    }
                    if (call.isCanceled()) {
                        npCallCancelled.set(true)
                        throw IOException("Canceled")
                    }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(trackedBody)
                        .build()
                } finally {
                    npCallExited.countDown()
                }
            }
            .build()
        val holderJob = Job()
        val downloader = SessionAwareNewPipeDownloader(
            client = npHttpClient,
            sessionManager = YouTubeSessionManager(),
            authCookieProvider = { null },
            activeJobProvider = { holderJob }
        )
        val npAsync = async(Dispatchers.IO) {
            downloader.execute(
                NewPipeRequest.Builder()
                    .get("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                    .build()
            )
        }
        assertTrue(npCallReached.await(3, TimeUnit.SECONDS))
        holderJob.cancel(CancellationException("NewPipe timeout/cancel"))
        var npCaughtCancel = false
        try {
            npAsync.await()
        } catch (_: CancellationException) {
            npCaughtCancel = true
        }
        assertTrue("NewPipe downloader must propagate CancellationException", npCaughtCancel)
        assertTrue(npCallExited.await(3, TimeUnit.SECONDS))
        assertTrue("NewPipe downloader must invoke Call.cancel()", npCallCancelled.get())

        // Also check normal completion closes body cleanly
        val completeClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(trackedBody)
                    .build()
            }
            .build()
        val completedDownloader = SessionAwareNewPipeDownloader(
            client = completeClient,
            sessionManager = YouTubeSessionManager(),
            authCookieProvider = { null },
            activeJobProvider = { Job() }
        )
        completedDownloader.execute(
            NewPipeRequest.Builder()
                .get("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                .build()
        )
        assertTrue("ResponseBody must be closed after execution", npBodyClosed.get())
    }

    @Test
    fun cancelledResolution_doesNotPoisonSubsequentRequestOrLeakMapEntry() = runBlocking {
        val attempt = AtomicInteger(0)
        val firstStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()

        val client = YouTubeMusicClient(
            internalStageResolver = { videoId, _, _ ->
                val n = attempt.incrementAndGet()
                if (n == 1) {
                    firstStarted.complete(Unit)
                    firstGate.await()
                }
                Result.success(fakeStream("https://cdn.example.com/$videoId/attempt_$n.m4a"))
            }
        )

        val job1 = async(Dispatchers.IO) {
            client.resolveAudioStream("vidCancel01")
        }
        withTimeout(3_000) { firstStarted.await() }
        job1.cancel(CancellationException("Caller abandoned"))

        var caught = false
        try {
            job1.await()
        } catch (_: CancellationException) {
            caught = true
        }
        assertTrue(caught)
        assertEquals("Cancelled single-waiter entry must be evicted immediately", 0, client.inFlightResolutionCountForTest())

        // Subsequent request must run cleanly and succeed
        val secondResult = withTimeout(3_000) {
            client.resolveAudioStream("vidCancel01")
        }
        assertTrue("Second request must succeed after first was cancelled", secondResult.isSuccess)
        assertEquals("https://cdn.example.com/vidCancel01/attempt_2.m4a", secondResult.getOrThrow().url)
        assertEquals(0, client.inFlightResolutionCountForTest())
    }

    @Test
    fun forceRefresh_andIndependentCallers_doNotReuseStaleOrCancelOthers() = runBlocking {
        val staleStarted = CompletableDeferred<Unit>()
        val staleGate = CompletableDeferred<Result<ResolvedAudioStream>>()
        val freshStarted = CompletableDeferred<Unit>()
        val freshGate = CompletableDeferred<Result<ResolvedAudioStream>>()
        val callCount = AtomicInteger(0)

        val client = YouTubeMusicClient(
            internalStageResolver = { _, _, _ ->
                when (callCount.incrementAndGet()) {
                    1 -> {
                        staleStarted.complete(Unit)
                        staleGate.await()
                    }
                    else -> {
                        freshStarted.complete(Unit)
                        freshGate.await()
                    }
                }
            }
        )

        val job1 = async(Dispatchers.IO) {
            client.resolveAudioStream("vidForce01", forceRefresh = false)
        }
        withTimeout(3_000) { staleStarted.await() }

        // forceRefresh must bypass stale in-flight entry without cancelling job1
        val job2 = async(Dispatchers.IO) {
            client.resolveAudioStream("vidForce01", forceRefresh = true)
        }
        withTimeout(3_000) { freshStarted.await() }

        // A third non-forceRefresh caller should now join job2 (the fresh entry), not stale job1
        val job3 = async(Dispatchers.IO) {
            client.resolveAudioStream("vidForce01", forceRefresh = false)
        }

        // Complete job2 + job3 with fresh URL BEFORE stale job1 finishes
        val freshStream = fakeStream("https://cdn.example.com/fresh.m4a")
        freshGate.complete(Result.success(freshStream))
        assertEquals(freshStream.url, withTimeout(3_000) { job2.await() }.getOrThrow().url)
        assertEquals(freshStream.url, withTimeout(3_000) { job3.await() }.getOrThrow().url)

        // Now stale job1 finishes with stale URL — must succeed for job1 cleanly AND must NOT overwrite fresh streamCache!
        val staleStream = fakeStream("https://cdn.example.com/stale.m4a")
        staleGate.complete(Result.success(staleStream))
        assertEquals(staleStream.url, withTimeout(3_000) { job1.await() }.getOrThrow().url)
        assertEquals(
            "Stale invalidated entry must not overwrite fresh cache entry",
            freshStream.url,
            client.peekValidCachedStream("vidForce01")?.url
        )

        // Verify independent caller isolation: cancelling Caller A does not cancel Caller B sharing the same entry
        val sharedStarted = CompletableDeferred<Unit>()
        val sharedGate = CompletableDeferred<Result<ResolvedAudioStream>>()
        val sharedCount = AtomicInteger(0)
        val sharedClient = YouTubeMusicClient(
            internalStageResolver = { _, _, _ ->
                sharedCount.incrementAndGet()
                sharedStarted.complete(Unit)
                sharedGate.await()
            }
        )
        val callerA = async(Dispatchers.IO) { sharedClient.resolveAudioStream("vidShared01") }
        withTimeout(3_000) { sharedStarted.await() }
        val callerB = async(Dispatchers.IO) { sharedClient.resolveAudioStream("vidShared01") }
        // Give callerB a brief moment to acquire shared entry before cancelling callerA
        Thread.sleep(40)
        callerA.cancel(CancellationException("Caller A cancelled"))

        var callerACancelled = false
        try {
            callerA.await()
        } catch (_: CancellationException) {
            callerACancelled = true
        }
        assertTrue(callerACancelled)

        val sharedStream = fakeStream("https://cdn.example.com/shared_ok.m4a")
        sharedGate.complete(Result.success(sharedStream))
        assertEquals(sharedStream.url, withTimeout(3_000) { callerB.await() }.getOrThrow().url)
        assertEquals(1, sharedCount.get())
    }

    @Test
    fun identitySafeCleanup_olderRequestFinishingDoesNotRemoveNewerEntry() = runBlocking {
        val olderStarted = CompletableDeferred<Unit>()
        val olderGate = CompletableDeferred<Result<ResolvedAudioStream>>()
        val newerStarted = CompletableDeferred<Unit>()
        val newerGate = CompletableDeferred<Result<ResolvedAudioStream>>()
        val counter = AtomicInteger(0)

        val client = YouTubeMusicClient(
            internalStageResolver = { _, _, _ ->
                when (counter.incrementAndGet()) {
                    1 -> {
                        olderStarted.complete(Unit)
                        olderGate.await()
                    }
                    2 -> {
                        newerStarted.complete(Unit)
                        newerGate.await()
                    }
                    else -> Result.success(fakeStream("https://cdn.example.com/unexpected_third.m4a"))
                }
            }
        )

        val older = async(Dispatchers.IO) { client.resolveAudioStream("vidIdentity01") }
        withTimeout(3_000) { olderStarted.await() }

        val newer = async(Dispatchers.IO) { client.resolveAudioStream("vidIdentity01", forceRefresh = true) }
        withTimeout(3_000) { newerStarted.await() }
        assertEquals(1, client.inFlightResolutionCountForTest())

        // Older finishes BEFORE newer — identity-safe remove(key, oldEntry) MUST NOT remove newer entry!
        olderGate.complete(Result.failure(IOException("older failed")))
        assertTrue(withTimeout(3_000) { older.await() }.isFailure)
        assertEquals("Newer in-flight entry must still remain registered", 1, client.inFlightResolutionCountForTest())

        // Third caller must deduplicate onto the surviving newer entry rather than launching a 3rd request
        val joiner = async(Dispatchers.IO) { client.resolveAudioStream("vidIdentity01", forceRefresh = false) }
        val newerStream = fakeStream("https://cdn.example.com/newer_winner.m4a")
        newerGate.complete(Result.success(newerStream))

        assertEquals(newerStream.url, withTimeout(3_000) { newer.await() }.getOrThrow().url)
        assertEquals(newerStream.url, withTimeout(3_000) { joiner.await() }.getOrThrow().url)
        assertEquals("Only 2 resolutions should ever have executed", 2, counter.get())
        assertEquals(0, client.inFlightResolutionCountForTest())
    }

    @Test
    fun stalePlaybackRequest_cannotOverwriteNewerTrackStateOrError() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakePlayer = FakeMediaPlayer()
        val trackAStarted = CompletableDeferred<Unit>()
        val trackAGate = CompletableDeferred<Result<ResolvedAudioStream>>()
        val trackBGate = CompletableDeferred<Result<ResolvedAudioStream>>()
        val calls = CopyOnWriteArrayList<String>()

        val ytClient = YouTubeMusicClient(
            internalStageResolver = { videoId, _, _ ->
                calls.add(videoId)
                when (videoId) {
                    "aaaaaaaaaaa" -> {
                        if (!trackAStarted.isCompleted) trackAStarted.complete(Unit)
                        trackAGate.await()
                    }
                    "bbbbbbbbbbb" -> trackBGate.await()
                    else -> Result.failure(IOException("Unexpected $videoId"))
                }
            }
        )

        val controller = PlaybackController(
            context = context,
            signalDao = NoOpSignalDao(),
            youtubeClient = ytClient,
            injectedPlayer = fakePlayer.player,
            autoConnectController = false
        )

        val trackA = Track(id = "yt_aaaaaaaaaaa", title = "Track A", artist = "Artist A", sourceType = TrackSourceType.YOUTUBE)
        val trackB = Track(id = "yt_bbbbbbbbbbb", title = "Track B", artist = "Artist B", sourceType = TrackSourceType.YOUTUBE)

        controller.playTrack(trackA, listOf(trackA, trackB))
        shadowOf(Looper.getMainLooper()).idle()
        withTimeout(3_000) { trackAStarted.await() }
        assertTrue(controller.state.value.isBuffering)

        // Rapidly switch to Track B before Track A resolves
        controller.playTrack(trackB, listOf(trackA, trackB))
        assertEquals(trackB.id, controller.state.value.currentTrack?.id)
        assertTrue(controller.state.value.isBuffering)

        // Track A resolves late (failure) — MUST NOT overwrite Track B or set playbackError!
        trackAGate.complete(Result.failure(IOException("Track A stale error")))
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(40)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(trackB.id, controller.state.value.currentTrack?.id)
        assertNull("Stale Track A failure must not leak into Track B playbackError", controller.state.value.playbackError)
        assertTrue("Track B must remain buffering while its own resolution is pending", controller.state.value.isBuffering)

        // Complete Track B cleanly
        val streamB = fakeStream("https://cdn.example.com/track_b.m4a")
        trackBGate.complete(Result.success(streamB))
        val deadline = System.currentTimeMillis() + 3_000L
        while (fakePlayer.lastPreparedItems.isEmpty() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(trackB.id, controller.state.value.currentTrack?.id)
        assertNull(controller.state.value.playbackError)
        assertTrue(fakePlayer.lastPreparedItems.any { it.localConfiguration?.uri.toString() == streamB.url })

        controller.release()
    }

    @Test
    fun bufferingState_clearsOnCancellationAndFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakePlayer = FakeMediaPlayer()
        val gateStarted = CompletableDeferred<Unit>()
        val infiniteGate = CompletableDeferred<Result<ResolvedAudioStream>>()

        val ytClient = YouTubeMusicClient(
            internalStageResolver = { _, _, _ ->
                gateStarted.complete(Unit)
                infiniteGate.await()
            }
        )

        val controller = PlaybackController(
            context = context,
            signalDao = NoOpSignalDao(),
            youtubeClient = ytClient,
            injectedPlayer = fakePlayer.player,
            autoConnectController = false
        )

        val track = Track(id = "yt_ccccccccccc", title = "Track C", artist = "Artist C", sourceType = TrackSourceType.YOUTUBE)
        controller.playTrack(track)
        assertTrue(controller.state.value.isBuffering)
        assertNull(controller.state.value.playbackError)

        shadowOf(Looper.getMainLooper()).idle()
        withTimeout(3_000) { gateStarted.await() }

        // Cancel active resolution directly — must clear isBuffering without setting playbackError
        controller.cancelActiveResolutionForTest()
        val deadline = System.currentTimeMillis() + 3_000L
        while (controller.state.value.isBuffering && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse("isBuffering must clear after cancellation", controller.state.value.isBuffering)
        assertFalse(controller.state.value.isPlaying)
        assertNull("Cancellation must not surface a false playbackError", controller.state.value.playbackError)

        // Genuine failure must clear isBuffering and set playbackError
        val failingClient = YouTubeMusicClient(
            internalStageResolver = { _, _, _ -> Result.failure(IOException("Simulated upstream failure")) }
        )
        val failController = PlaybackController(
            context = context,
            signalDao = NoOpSignalDao(),
            youtubeClient = failingClient,
            injectedPlayer = fakePlayer.player,
            autoConnectController = false
        )
        failController.playTrack(track)
        val failDeadline = System.currentTimeMillis() + 3_000L
        while (failController.state.value.playbackError == null && System.currentTimeMillis() < failDeadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(failController.state.value.isBuffering)
        assertEquals("Simulated upstream failure", failController.state.value.playbackError)

        controller.release()
        failController.release()
    }

    @Test
    fun localPlayback_remainsUnaffectedAndPreemptsRemoteResolution() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakePlayer = FakeMediaPlayer()
        val ytResolveCount = AtomicInteger(0)
        val ytStarted = CompletableDeferred<Unit>()
        val ytGate = CompletableDeferred<Result<ResolvedAudioStream>>()

        val ytClient = YouTubeMusicClient(
            internalStageResolver = { _, _, _ ->
                ytResolveCount.incrementAndGet()
                ytStarted.complete(Unit)
                ytGate.await()
            }
        )

        val controller = PlaybackController(
            context = context,
            signalDao = NoOpSignalDao(),
            youtubeClient = ytClient,
            injectedPlayer = fakePlayer.player,
            autoConnectController = false
        )

        val ytTrack = Track(id = "yt_ddddddddddd", title = "Remote YT", artist = "YT Artist", sourceType = TrackSourceType.YOUTUBE)
        val localTrack = Track(
            id = "device_ms_42",
            title = "Local Audio",
            artist = "Local Artist",
            localUri = "content://media/external/audio/media/42",
            sourceType = TrackSourceType.LOCAL_SAF
        )

        controller.playTrack(ytTrack, listOf(ytTrack))
        shadowOf(Looper.getMainLooper()).idle()
        withTimeout(3_000) { ytStarted.await() }

        // Immediately switch to local track — must bypass YouTube resolution and hand local URI straight to Media3
        controller.playTrack(localTrack, listOf(localTrack))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(localTrack.id, controller.state.value.currentTrack?.id)
        assertFalse(controller.state.value.isBuffering)
        assertNull(controller.state.value.playbackError)
        assertEquals(1, ytResolveCount.get())
        assertTrue(fakePlayer.lastPreparedItems.any { it.localConfiguration?.uri.toString() == localTrack.playableUri })

        // Late remote completion must not affect local playback
        ytGate.complete(Result.failure(IOException("Late YT failure")))
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(30)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(localTrack.id, controller.state.value.currentTrack?.id)
        assertNull(controller.state.value.playbackError)

        controller.release()
    }

    @Test
    fun hlsQueueHandling_reproducesDefaultMediaSourceBugAndVerifiesPulseMediaSourceFactory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dataSourceFactory = DefaultDataSource.Factory(context)
        val resolvingFactory = ResolvingDataSource.Factory(dataSourceFactory) { spec -> spec }

        val queuedPulseYtItem = MediaItem.Builder()
            .setMediaId("yt_NJAv_7lHUIU")
            .setUri("pulseyt://watch?v=NJAv_7lHUIU")
            .build()

        // 1. Reproduce defect: plain DefaultMediaSourceFactory inspects only un-resolved URI/mimeType -> picks ProgressiveMediaSource
        val plainFactory = DefaultMediaSourceFactory(resolvingFactory)
        val plainSource = plainFactory.createMediaSource(queuedPulseYtItem)
        assertTrue(
            "DefaultMediaSourceFactory misclassifies pulseyt:// without mimeType as ProgressiveMediaSource",
            plainSource is ProgressiveMediaSource
        )

        // 2. Verify PulseMediaSourceFactory picks HlsMediaSource for cached/resolved HLS streams,
        //    ProgressiveMediaSource for direct streams, ResolvingYouTubeMediaSource for uncached queue items,
        //    and leaves local URIs untouched on DefaultMediaSourceFactory.
        val ytClient = YouTubeMusicClient()
        val cachedHls = ResolvedAudioStream(
            url = "https://manifest.googlevideo.com/api/manifest/hls_playlist/playlist/index.m3u8",
            mimeType = "application/x-mpegURL",
            bitrateBps = 128_000,
            expiresAtMs = System.currentTimeMillis() + 3_600_000L,
            userAgent = "Safari",
            isHls = true
        )
        val cachedDirect = ResolvedAudioStream(
            url = "https://rr1---sn.googlevideo.com/videoplayback?mime=audio/mp4",
            mimeType = "audio/mp4",
            bitrateBps = 128_000,
            expiresAtMs = System.currentTimeMillis() + 3_600_000L,
            userAgent = "Safari",
            isHls = false
        )

        val hlsFactory = PulseMediaSourceFactory(
            delegateFactory = plainFactory,
            upstreamDataSourceFactory = dataSourceFactory,
            youtubeClient = ytClient,
            peekCachedStream = { vid -> if (vid == "NJAv_7lHUIU") cachedHls else cachedDirect }
        )
        val createdHls: MediaSource = hlsFactory.createMediaSource(queuedPulseYtItem)
        assertTrue("Queued pulseyt:// resolving to HLS must create HlsMediaSource", createdHls is HlsMediaSource)

        val directItem = MediaItem.Builder()
            .setMediaId("yt_dQw4w9WgXcQ")
            .setUri("pulseyt://watch?v=dQw4w9WgXcQ")
            .build()
        val createdProgressive = hlsFactory.createMediaSource(directItem)
        assertTrue(
            "Queued pulseyt:// resolving to direct stream must create ProgressiveMediaSource",
            createdProgressive is ProgressiveMediaSource
        )

        val uncachedHlsReady = CountDownLatch(1)
        val uncachedFactory = PulseMediaSourceFactory(
            delegateFactory = plainFactory,
            upstreamDataSourceFactory = dataSourceFactory,
            youtubeClient = ytClient,
            streamResolver = { Result.success(cachedHls) },
            peekCachedStream = { null }
        )
        val resolvingSource = uncachedFactory.createMediaSource(queuedPulseYtItem)
        assertTrue(resolvingSource is ResolvingYouTubeMediaSource)
        val typedResolving = resolvingSource as ResolvingYouTubeMediaSource
        val caller = MediaSource.MediaSourceCaller { _, _ -> uncachedHlsReady.countDown() }
        typedResolving.prepareSource(caller, null, androidx.media3.exoplayer.analytics.PlayerId.UNSET)

        val waitDeadline = System.currentTimeMillis() + 3_000L
        while (typedResolving.preparedChildSourceForTest() == null && System.currentTimeMillis() < waitDeadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "ResolvingYouTubeMediaSource must dynamically select HlsMediaSource when resolved stream is HLS",
            typedResolving.preparedChildSourceForTest() is HlsMediaSource
        )
        typedResolving.releaseSource(caller)

        val localItem = MediaItem.Builder()
            .setMediaId("device_ms_1")
            .setUri("file:///sdcard/Music/song.mp3")
            .build()
        val localSource = uncachedFactory.createMediaSource(localItem)
        assertTrue("Local tracks must still go through DefaultMediaSourceFactory", localSource is ProgressiveMediaSource)

        hlsFactory.release()
        uncachedFactory.release()
    }

    @Test
    fun mediaSessionSupport_resumptionStoreSyncAndMetadataPropagationWork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ytTrack = Track(
            id = "yt_NJAv_7lHUIU",
            title = "Kesariya",
            artist = "Arijit Singh",
            album = "Brahmastra",
            genre = "Bollywood",
            mood = "Romantic",
            bpm = 96,
            durationMs = 268_000L,
            artworkUrl = "https://i.ytimg.com/vi/NJAv_7lHUIU/hqdefault.jpg",
            sourceType = TrackSourceType.YOUTUBE,
            isLiked = true
        )
        val localTrack = Track(
            id = "device_ms_7",
            title = "Night Drive",
            artist = "Synth Lab",
            album = "After Hours",
            genre = "Synthwave",
            durationMs = 210_000L,
            localUri = "content://media/external/audio/media/7",
            sourceType = TrackSourceType.LOCAL_SAF
        )

        // 1. PulseMediaItemMapper preserves Track fields + rich MediaMetadata
        val ytItem = PulseMediaItemMapper.toMediaItem(
            track = ytTrack,
            overrideUri = "https://rr1---sn.googlevideo.com/temp-expired-url"
        )!!
        assertEquals("Kesariya", ytItem.mediaMetadata.title.toString())
        assertEquals("Arijit Singh", ytItem.mediaMetadata.artist.toString())
        assertEquals("Brahmastra", ytItem.mediaMetadata.albumTitle.toString())
        assertEquals(268_000L, ytItem.mediaMetadata.durationMs)
        val restoredYt = PulseMediaItemMapper.fromMediaItem(ytItem)!!
        assertEquals(ytTrack.id, restoredYt.id)
        assertEquals(ytTrack.artist, restoredYt.artist)
        assertEquals(ytTrack.genre, restoredYt.genre)
        assertEquals(TrackSourceType.YOUTUBE, restoredYt.sourceType)
        assertTrue(restoredYt.isLiked)

        val localItem = PulseMediaItemMapper.toMediaItem(localTrack)!!

        // 2. PulsePlaybackResumptionStore stores durable pulseyt:// URI (not temporary CDN URL) and restores position
        val store = PulsePlaybackResumptionStore(context)
        store.clear()
        store.saveSnapshot(
            items = listOf(ytItem, localItem),
            currentIndex = 0,
            positionMs = 45_200L
        )
        val resumedForUi = store.loadSnapshot(isForPlayback = false)
        assertNotNull(resumedForUi)
        assertEquals(2, resumedForUi!!.mediaItems.size)
        assertEquals(0, resumedForUi.startIndex)
        assertEquals(45_200L, resumedForUi.startPositionMs)
        assertEquals(
            "YouTube item in resumption store must use durable pulseyt:// scheme",
            "pulseyt://watch?v=NJAv_7lHUIU",
            resumedForUi.mediaItems[0].localConfiguration?.uri.toString()
        )
        assertEquals(
            localTrack.playableUri,
            resumedForUi.mediaItems[1].localConfiguration?.uri.toString()
        )

        // 3. Reconnecting PlaybackController hydrates active track, queue, playing state, and position from running Player
        val fakePlayer = FakeMediaPlayer()
        fakePlayer.player.setMediaItems(listOf(ytItem, localItem), 1, 0L)
        fakePlayer.positionMs = 19_000L
        fakePlayer.durationMs = 210_000L
        fakePlayer.player.prepare()
        fakePlayer.player.play()

        val reconnected = PlaybackController(
            context = context,
            signalDao = NoOpSignalDao(),
            injectedPlayer = fakePlayer.player,
            autoConnectController = false
        )
        assertEquals(localTrack.id, reconnected.state.value.currentTrack?.id)
        assertEquals(2, reconnected.state.value.queue.size)
        assertEquals(1, reconnected.state.value.currentIndex)
        assertTrue(reconnected.state.value.isPlaying)
        assertEquals(19_000L, reconnected.state.value.positionMs)

        // 4. updateTrackMetadata propagates updated title/artist/artwork into the live MediaItem on the Player
        //    even when called from a background IO worker thread (MediaController requires Main Looper)
        fakePlayer.enforceMainThread = true
        val bgUpdated = localTrack.copy(title = "Night Drive (Remastered)")
        runBlocking {
            val workerJob = async(Dispatchers.IO) {
                reconnected.updateTrackMetadata(bgUpdated)
            }
            workerJob.await()
        }
        val flushDeadline = System.currentTimeMillis() + 3_000L
        while (fakePlayer.items[1].mediaMetadata.title.toString() != "Night Drive (Remastered)" &&
            System.currentTimeMillis() < flushDeadline
        ) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("Night Drive (Remastered)", reconnected.state.value.currentTrack?.title)
        assertEquals(
            "Night Drive (Remastered)",
            fakePlayer.items[1].mediaMetadata.title.toString()
        )
        assertEquals(
            localTrack.playableUri,
            fakePlayer.items[1].localConfiguration?.uri.toString()
        )

        store.clear()
        reconnected.release()
    }

    @Test
    fun smartFadeOut_tapersVolumeBeforePauseAndRestoresVolume() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakePlayer = FakeMediaPlayer()
        val controller = PlaybackController(
            context = context,
            signalDao = NoOpSignalDao(),
            youtubeClient = YouTubeMusicClient(),
            injectedPlayer = fakePlayer.player,
            autoConnectController = false
        )
        controller.setCrossfadeSeconds(0)
        controller.setSmartFadeOutEnabled(true)

        val localTrack = Track(
            id = "device_ms_fade_1",
            title = "Fade Out Song",
            artist = "Pulse Artist",
            localUri = "content://media/external/audio/media/901",
            sourceType = TrackSourceType.LOCAL_SAF
        )

        controller.playTrack(localTrack, listOf(localTrack))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(fakePlayer.isPlaying)
        assertEquals(1f, fakePlayer.volume, 0.02f)

        // Manual pause triggers Smart Fade-Out
        controller.togglePlayPause()
        assertFalse(controller.state.value.isPlaying)

        // Advance halfway (~200ms): still playing while volume tapers smoothly below 1f
        val observedMidVolumes = mutableListOf<Float>()
        repeat(12) {
            shadowOf(Looper.getMainLooper()).idleFor(18, TimeUnit.MILLISECONDS)
            observedMidVolumes.add(fakePlayer.volume)
        }
        assertTrue("Expected player to still be playing mid fade-out", fakePlayer.isPlaying)
        assertTrue(
            "Expected tapered volume below 0.85 mid fade-out, got $observedMidVolumes",
            observedMidVolumes.last() < 0.85f && observedMidVolumes.last() > 0f
        )

        // Advance past fade-out + 25ms settle: player pauses cleanly and restores volume to 1f
        repeat(24) {
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
        }
        assertFalse(fakePlayer.isPlaying)
        assertFalse(controller.state.value.isPlaying)
        assertEquals(1f, fakePlayer.volume, 0.02f)

        controller.release()
    }

    @Test
    fun smartFadeOut_midFadeResumeCancelsPauseAndRestoresPlayback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakePlayer = FakeMediaPlayer()
        val controller = PlaybackController(
            context = context,
            signalDao = NoOpSignalDao(),
            youtubeClient = YouTubeMusicClient(),
            injectedPlayer = fakePlayer.player,
            autoConnectController = false
        )
        controller.setCrossfadeSeconds(0)
        controller.setSmartFadeOutEnabled(true)

        val localTrack = Track(
            id = "device_ms_fade_2",
            title = "Resume Mid Fade",
            artist = "Pulse Artist",
            localUri = "content://media/external/audio/media/902",
            sourceType = TrackSourceType.LOCAL_SAF
        )

        controller.playTrack(localTrack, listOf(localTrack))
        shadowOf(Looper.getMainLooper()).idle()

        controller.togglePlayPause()
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idleFor(18, TimeUnit.MILLISECONDS)
        }
        assertTrue(fakePlayer.isPlaying)
        assertTrue(fakePlayer.volume < 0.9f)

        // User taps Play before fade completes -> must stay playing and ramp back to full volume
        controller.togglePlayPause()
        assertTrue(controller.state.value.isPlaying)
        assertTrue(fakePlayer.isPlaying)

        repeat(20) {
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
        }
        assertTrue(fakePlayer.isPlaying)
        assertTrue(controller.state.value.isPlaying)
        assertEquals(1f, fakePlayer.volume, 0.05f)

        controller.release()
    }

    @Test
    fun smartFadeOut_whenDisabledPausesImmediatelyWithoutFade() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakePlayer = FakeMediaPlayer()
        val controller = PlaybackController(
            context = context,
            signalDao = NoOpSignalDao(),
            youtubeClient = YouTubeMusicClient(),
            injectedPlayer = fakePlayer.player,
            autoConnectController = false
        )
        controller.setCrossfadeSeconds(0)
        controller.setSmartFadeOutEnabled(false)

        val localTrack = Track(
            id = "device_ms_fade_3",
            title = "Instant Pause",
            artist = "Pulse Artist",
            localUri = "content://media/external/audio/media/903",
            sourceType = TrackSourceType.LOCAL_SAF
        )

        controller.playTrack(localTrack, listOf(localTrack))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(fakePlayer.isPlaying)

        controller.togglePlayPause()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(fakePlayer.isPlaying)
        assertFalse(controller.state.value.isPlaying)
        assertEquals(1f, fakePlayer.volume, 0.01f)

        controller.release()
    }

    @Test
    fun updateTrackMetadata_likeToggleDoesNotDipVolumeOrReplaceActiveItem() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakePlayer = FakeMediaPlayer()
        val controller = PlaybackController(
            context = context,
            signalDao = NoOpSignalDao(),
            youtubeClient = YouTubeMusicClient(),
            injectedPlayer = fakePlayer.player,
            autoConnectController = false
        )
        // Crossfade enabled by default (4s) — previously triggered a 0.04f volume dip on Like!
        assertEquals(4, controller.state.value.crossfadeSeconds)

        val localTrack = Track(
            id = "device_ms_like_1",
            title = "Steady Volume Song",
            artist = "Pulse Artist",
            localUri = "content://media/external/audio/media/904",
            sourceType = TrackSourceType.LOCAL_SAF,
            durationMs = 200_000L,
            isLiked = false
        )

        controller.playTrack(localTrack, listOf(localTrack))
        shadowOf(Looper.getMainLooper()).idle()
        // Finish initial fade-in completely so volume reaches 1f
        controller.setCrossfadeSeconds(0)
        shadowOf(Looper.getMainLooper()).idle()
        controller.setCrossfadeSeconds(4)
        fakePlayer.volume = 1f
        fakePlayer.replaceMediaItemCount = 0

        // Toggling Like must update state immediately without replacing active MediaItem or dipping volume
        controller.updateTrackMetadata(localTrack.copy(isLiked = true))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(controller.state.value.currentTrack?.isLiked == true)
        assertEquals(0, fakePlayer.replaceMediaItemCount)
        assertEquals(1f, fakePlayer.volume, 0.01f)

        // Even if display metadata (title) updates in-place and fires PLAYLIST_CHANGED, volume must not dip
        controller.updateTrackMetadata(localTrack.copy(isLiked = true, title = "Steady Volume (Remastered)"))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("Steady Volume (Remastered)", controller.state.value.currentTrack?.title)
        assertEquals(1, fakePlayer.replaceMediaItemCount)
        assertEquals(1f, fakePlayer.volume, 0.01f)

        controller.release()
    }

    private fun fakeStream(url: String): ResolvedAudioStream = ResolvedAudioStream(
        url = url,
        mimeType = "audio/mp4",
        bitrateBps = 128_000,
        expiresAtMs = System.currentTimeMillis() + 3_600_000L,
        userAgent = "TestAgent"
    )

    private class NoOpSignalDao : SignalDao {
        override fun observeRecentSignals(): Flow<List<ListeningSignalEntity>> = flowOf(emptyList())
        override suspend fun getRecentSignals(): List<ListeningSignalEntity> = emptyList()
        override suspend fun insertSignal(signal: ListeningSignalEntity) {}
    }

    private class FakeMediaPlayer {
        val listeners = CopyOnWriteArrayList<Player.Listener>()
        val items = mutableListOf<MediaItem>()
        var lastPreparedItems: List<MediaItem> = emptyList()
        var volume: Float = 1f
        var playWhenReady: Boolean = false
        var isPlaying: Boolean = false
        var playbackState: Int = Player.STATE_IDLE
        var currentIndex: Int = 0
        var positionMs: Long = 0L
        var durationMs: Long = 0L
        var enforceMainThread: Boolean = false
        var replaceMediaItemCount: Int = 0

        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { _, method, args ->
            if (enforceMainThread && Looper.myLooper() != Looper.getMainLooper()) {
                throw IllegalStateException("MediaController method ${method.name} is called from a wrong thread")
            }
            when (method.name) {
                "addListener" -> {
                    (args?.getOrNull(0) as? Player.Listener)?.let { listeners.add(it) }
                    Unit
                }
                "removeListener" -> {
                    (args?.getOrNull(0) as? Player.Listener)?.let { listeners.remove(it) }
                    Unit
                }
                "setMediaItems" -> {
                    @Suppress("UNCHECKED_CAST")
                    val list = args?.getOrNull(0) as? List<MediaItem> ?: emptyList()
                    items.clear()
                    items.addAll(list)
                    lastPreparedItems = list.toList()
                    if (args != null && args.size >= 2 && args[1] is Int) {
                        currentIndex = (args[1] as Int).coerceAtLeast(0)
                    } else {
                        currentIndex = 0
                    }
                    Unit
                }
                "replaceMediaItem" -> {
                    val idx = args?.getOrNull(0) as? Int ?: 0
                    val item = args?.getOrNull(1) as? MediaItem
                    if (item != null && idx in items.indices) {
                        replaceMediaItemCount++
                        items[idx] = item
                        lastPreparedItems = items.toList()
                        if (idx == currentIndex) {
                            listeners.forEach {
                                it.onMediaItemTransition(
                                    item,
                                    Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
                                )
                            }
                        }
                    }
                    Unit
                }
                "clearMediaItems" -> {
                    items.clear()
                    Unit
                }
                "prepare" -> {
                    playbackState = Player.STATE_READY
                    listeners.forEach { it.onPlaybackStateChanged(playbackState) }
                    Unit
                }
                "play" -> {
                    playWhenReady = true
                    isPlaying = true
                    listeners.forEach { it.onIsPlayingChanged(true) }
                    Unit
                }
                "pause" -> {
                    playWhenReady = false
                    isPlaying = false
                    listeners.forEach { it.onIsPlayingChanged(false) }
                    Unit
                }
                "stop" -> {
                    isPlaying = false
                    playbackState = Player.STATE_IDLE
                    Unit
                }
                "getVolume" -> volume
                "setVolume" -> {
                    volume = (args?.getOrNull(0) as? Float) ?: 1f
                    Unit
                }
                "getMediaItemCount" -> items.size
                "getMediaItemAt" -> items[args?.getOrNull(0) as Int]
                "getCurrentMediaItemIndex" -> currentIndex
                "getPlaybackState" -> playbackState
                "isPlaying" -> isPlaying
                "getPlayWhenReady" -> playWhenReady
                "getCurrentPosition" -> positionMs
                "getDuration" -> durationMs
                "hasNextMediaItem" -> currentIndex < items.lastIndex
                "hasPreviousMediaItem" -> currentIndex > 0
                "getShuffleModeEnabled" -> false
                "getRepeatMode" -> Player.REPEAT_MODE_OFF
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    Double::class.javaPrimitiveType -> 0.0
                    else -> null
                }
            }
        } as Player
    }
}
