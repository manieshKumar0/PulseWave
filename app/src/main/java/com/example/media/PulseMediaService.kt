package com.example.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.PresetReverb
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.MainActivity
import com.example.R
import com.example.data.model.DefaultSoundscapes
import com.example.data.model.Track
import com.example.data.remote.ResolvedAudioStream
import com.example.data.remote.YouTubeMusicClient
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class PulseMediaService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var pulseMediaSourceFactory: PulseMediaSourceFactory? = null
    private var bitmapLoader: CoilBitmapLoader? = null
    private var resumptionStore: PulsePlaybackResumptionStore? = null

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var presetReverb: PresetReverb? = null

    private var lastEqEnabled: Boolean = true
    private var lastBands: List<Int> = DefaultSoundscapes.presets.first().bandLevelsMillibel
    private var lastBass: Int = DefaultSoundscapes.presets.first().bassBoostStrength
    private var lastReverb: Int = DefaultSoundscapes.presets.first().reverbPreset

    companion object {
        const val ACTION_APPLY_SOUNDSCAPE = "com.example.pulsewave.APPLY_SOUNDSCAPE"
        const val ACTION_OPEN_NOW_PLAYING = "com.example.pulsewave.OPEN_NOW_PLAYING"
        const val EXTRA_OPEN_NOW_PLAYING = "open_now_playing"
        const val EXTRA_BANDS = "bands"
        const val EXTRA_BASS = "bass"
        const val EXTRA_REVERB = "reverb"
        const val EXTRA_EQ_ENABLED = "eq_enabled"
        const val NOTIFICATION_CHANNEL_ID = "pulsewave_playback_channel"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "PulseMediaService"
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        ensurePlaybackNotificationChannel()
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID)
            .setChannelId(NOTIFICATION_CHANNEL_ID)
            .setChannelName(R.string.notification_channel_playback_name)
            .build()
            .apply {
                setSmallIcon(R.drawable.ic_music_notification)
            }
        setMediaNotificationProvider(notificationProvider)
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                Log.w(TAG, "Foreground service start not allowed while app is backgrounded")
            }
        })

        resumptionStore = PulsePlaybackResumptionStore(applicationContext)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Share YouTubeMusicClient's IPv4-preferred OkHttpClient (same IP stack, TLS, connection pool, and per-URL User-Agent interceptor)
        val okHttpDataSourceFactory = OkHttpDataSource.Factory(YouTubeMusicClient.shared.playbackOkHttpClient)

        val upstreamFactory = DefaultDataSource.Factory(this, okHttpDataSourceFactory)

        val resolvingDataSourceFactory = ResolvingDataSource.Factory(upstreamFactory) { dataSpec ->
            val uri = dataSpec.uri
            if (uri.scheme == Track.YOUTUBE_URI_SCHEME) {
                val videoId = YouTubeMusicClient.extractVideoId(uri.toString())
                    ?: throw IOException("Invalid YouTube track URI")
                val corrId = YouTubeMusicClient.shared.latestCorrelationIdFor(videoId)
                val startTsMs = System.currentTimeMillis()
                val startNs = System.nanoTime()
                val resolved = runBlocking {
                    YouTubeMusicClient.shared.ensureAuthSessionHydrated(this@PulseMediaService)
                    val diag = YouTubeMusicClient.shared.getAuthDiagnostics()
                    Log.d(
                        "PULSE_YT_RESOLVER",
                        "stage_event corr=$corrId stage=MEDIA_SERVICE_DATASOURCE_START videoId=$videoId startTsMs=$startTsMs scheme=${uri.scheme} authState=${diag.state} hasAuthSession=${YouTubeMusicClient.shared.hasAuthenticatedYouTubeSession()}"
                    )
                    YouTubeMusicClient.shared.resolveAudioStream(videoId, correlationId = corrId)
                }.getOrElse { err ->
                    val endTsMs = System.currentTimeMillis()
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                    val timedOut = err is java.net.SocketTimeoutException || err.cause is java.net.SocketTimeoutException
                    val httpStatus = YouTubeMusicClient.extractHttpStatus(err)
                    val origin = YouTubeMusicClient.classifyFailureOrigin(err, timedOut = timedOut)
                    Log.w(
                        "PULSE_YT_RESOLVER",
                        "stage_event corr=$corrId stage=MEDIA_SERVICE_DATASOURCE videoId=$videoId startTsMs=$startTsMs endTsMs=$endTsMs durationMs=$elapsedMs outcome=${if (timedOut) "TIMEOUT" else "FAILURE"} errorClass=${err.javaClass.simpleName} rootCauseClass=${err.cause?.javaClass?.simpleName ?: "None"} httpStatus=$httpStatus usableStreamReturned=false isHls=false format=none cancelled=false timedOut=$timedOut failureOrigin=$origin msg=${YouTubeMusicClient.redactSensitiveUrls(err.message)}"
                    )
                    throw IOException(err.message ?: "YouTube audio stream unavailable", err)
                }
                val endTsMs = System.currentTimeMillis()
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                val parsedUrl = Uri.parse(resolved.url)
                Log.i(
                    "PULSE_YT_RESOLVER",
                    "stage_event corr=$corrId stage=MEDIA_SERVICE_DATASOURCE videoId=$videoId startTsMs=$startTsMs endTsMs=$endTsMs durationMs=$elapsedMs outcome=SUCCESS errorClass=NONE rootCauseClass=NONE httpStatus=200 usableStreamReturned=true isHls=${resolved.isHls} format=${resolved.mimeType} cancelled=false timedOut=false failureOrigin=NONE host=${YouTubeMusicClient.redactHostOnly(resolved.url)} headerKeys=${resolved.requestHeaders.keys}"
                )
                YouTubeMusicClient.shared.registerResolvedStreamHeaders(resolved)
                dataSpec.buildUpon()
                    .setUri(parsedUrl)
                    .setHttpRequestHeaders(
                        dataSpec.httpRequestHeaders + resolved.requestHeaders
                    )
                    .build()
            } else {
                dataSpec
            }
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 90_000,
                /* bufferForPlaybackMs = */ 350,
                /* bufferForPlaybackAfterRebufferMs = */ 1_000
            )
            .setBackBuffer(
                /* backBufferDurationMs = */ 30_000,
                /* retainBackBufferFromKeyframe = */ false
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val delegateMediaSourceFactory = DefaultMediaSourceFactory(resolvingDataSourceFactory)
        val mediaSourceFactory = PulseMediaSourceFactory(
            delegateFactory = delegateMediaSourceFactory,
            upstreamDataSourceFactory = upstreamFactory,
            youtubeClient = YouTubeMusicClient.shared,
            appContext = this
        ).also { pulseMediaSourceFactory = it }

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(TeeAudioProcessor(AudioSpectrumBus.audioBufferSink)))
                    .build()
            }
        }

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMaxSeekToPreviousPositionMs(4_000L)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                attachAudioEffects(audioSessionId)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                AudioSpectrumBus.setPlaybackActive(isPlaying)
                persistPlaybackSnapshot(exoPlayer)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                persistPlaybackSnapshot(exoPlayer)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                    persistPlaybackSnapshot(exoPlayer)
                }
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                    persistPlaybackSnapshot(exoPlayer)
                }
            }
        })

        player = exoPlayer
        if (exoPlayer.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            attachAudioEffects(exoPlayer.audioSessionId)
        }

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_NOW_PLAYING
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_NOW_PLAYING, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val sessionBitmapLoader = CoilBitmapLoader(this).also { bitmapLoader = it }

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(pendingIntent)
            .setBitmapLoader(sessionBitmapLoader)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val sessionCommandsBuilder = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon()
                        .add(SessionCommand(ACTION_APPLY_SOUNDSCAPE, Bundle.EMPTY))
                    val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(playerCommands)
                        .setAvailableSessionCommands(sessionCommandsBuilder.build())
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == ACTION_APPLY_SOUNDSCAPE) {
                        val eqEnabled = args.getBoolean(EXTRA_EQ_ENABLED, lastEqEnabled)
                        val bands = args.getIntArray(EXTRA_BANDS)?.toList() ?: lastBands
                        val bass = args.getInt(EXTRA_BASS, lastBass)
                        val reverb = args.getInt(EXTRA_REVERB, lastReverb)
                        lastEqEnabled = eqEnabled
                        lastBands = bands
                        lastBass = bass
                        lastReverb = reverb
                        applyDspSettings(eqEnabled, bands, bass, reverb)
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }

                @OptIn(UnstableApi::class)
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val snapshot = resumptionStore?.loadSnapshot(isForPlayback = true)
                    return if (snapshot != null) {
                        Futures.immediateFuture(snapshot)
                    } else {
                        Futures.immediateFailedFuture(IllegalStateException("No previous PulseWave playback state"))
                    }
                }
            })
            .build()
    }

    private fun ensurePlaybackNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val existing = nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_playback_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_playback_description)
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    private fun persistPlaybackSnapshot(exoPlayer: ExoPlayer) {
        val count = exoPlayer.mediaItemCount
        if (count <= 0) return
        val items = (0 until count).map { exoPlayer.getMediaItemAt(it) }
        resumptionStore?.saveSnapshot(
            items = items,
            currentIndex = exoPlayer.currentMediaItemIndex,
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        )
    }

    private fun attachAudioEffects(audioSessionId: Int) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId == 0) return
        releaseEffects()
        try {
            equalizer = Equalizer(0, audioSessionId).apply { enabled = lastEqEnabled }
            bassBoost = BassBoost(0, audioSessionId).apply { enabled = lastEqEnabled && lastBass > 0 }
            presetReverb = PresetReverb(0, audioSessionId).apply { enabled = lastEqEnabled && lastReverb > 0 }
            applyDspSettings(lastEqEnabled, lastBands, lastBass, lastReverb)
        } catch (e: Exception) {
            Log.w(TAG, "Hardware DSP audio effect attachment skipped: ${e.message}")
        }
    }

    private fun applyDspSettings(
        eqEnabled: Boolean,
        bands: List<Int>?,
        bassStrength: Int,
        reverbPreset: Int
    ) {
        try {
            equalizer?.let { eq ->
                eq.enabled = eqEnabled
                if (eqEnabled) {
                    val count = eq.numberOfBands.toInt().coerceAtMost(5)
                    val range = eq.bandLevelRange
                    val minLevel = range[0].toInt()
                    val maxLevel = range[1].toInt()
                    bands?.forEachIndexed { idx, value ->
                        if (idx < count) {
                            eq.setBandLevel(idx.toShort(), value.coerceIn(minLevel, maxLevel).toShort())
                        }
                    }
                }
            }
            bassBoost?.let { bb ->
                bb.enabled = eqEnabled && bassStrength > 0
                if (eqEnabled && bb.strengthSupported) {
                    bb.setStrength(bassStrength.coerceIn(0, 1000).toShort())
                }
            }
            presetReverb?.let { rev ->
                rev.enabled = eqEnabled && reverbPreset > 0
                if (eqEnabled) {
                    rev.preset = reverbPreset.coerceIn(0, 6).toShort()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update DSP settings: ${e.message}")
        }
    }

    private fun releaseEffects() {
        try { equalizer?.release() } catch (_: Exception) {}
        try { bassBoost?.release() } catch (_: Exception) {}
        try { presetReverb?.release() } catch (_: Exception) {}
        equalizer = null
        bassBoost = null
        presetReverb = null
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        player?.let { persistPlaybackSnapshot(it) }
        val p = mediaSession?.player
        if (!isPlaybackOngoing || p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            pauseAllPlayersAndStopSelf()
        }
    }

    @OptIn(UnstableApi::class)
    override fun onDestroy() {
        player?.let { persistPlaybackSnapshot(it) }
        AudioSpectrumBus.reset()
        bitmapLoader?.release()
        bitmapLoader = null
        pulseMediaSourceFactory?.release()
        pulseMediaSourceFactory = null
        releaseEffects()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        player = null
        super.onDestroy()
    }
}

/**
 * Resolves queued `pulseyt://` [MediaItem]s into the matching Media3 [MediaSource]
 * ([HlsMediaSource] for HLS manifests or [ProgressiveMediaSource] for direct streams)
 * so ExoPlayer pre-buffering never misclassifies an `.m3u8` stream as progressive audio.
 * Local, downloaded, and already-resolved items delegate untouched to [delegateFactory].
 */
@OptIn(UnstableApi::class)
internal class PulseMediaSourceFactory(
    private val delegateFactory: MediaSource.Factory,
    upstreamDataSourceFactory: DataSource.Factory,
    private val youtubeClient: YouTubeMusicClient,
    private val appContext: android.content.Context? = null,
    private val streamResolver: suspend (String) -> Result<ResolvedAudioStream> = { videoId ->
        appContext?.let { youtubeClient.ensureAuthSessionHydrated(it) }
        val corrId = youtubeClient.latestCorrelationIdFor(videoId)
        youtubeClient.resolveAudioStream(videoId = videoId, correlationId = corrId)
    },
    private val peekCachedStream: (String) -> ResolvedAudioStream? = { videoId ->
        youtubeClient.peekValidCachedStream(videoId)
    }
) : MediaSource.Factory {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hlsMediaSourceFactory = HlsMediaSource.Factory(upstreamDataSourceFactory)
    private val progressiveMediaSourceFactory = ProgressiveMediaSource.Factory(upstreamDataSourceFactory)

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider
    ): MediaSource.Factory {
        delegateFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
        hlsMediaSourceFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
        progressiveMediaSourceFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy
    ): MediaSource.Factory {
        delegateFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        hlsMediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        progressiveMediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = delegateFactory.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri
        if (uri?.scheme != Track.YOUTUBE_URI_SCHEME) {
            return delegateFactory.createMediaSource(mediaItem)
        }
        val videoId = YouTubeMusicClient.extractVideoId(uri.toString())
            ?: return delegateFactory.createMediaSource(mediaItem)

        peekCachedStream(videoId)?.let { cached ->
            youtubeClient.registerResolvedStreamHeaders(cached)
            return buildResolvedChildMediaSource(mediaItem, cached)
        }

        return ResolvingYouTubeMediaSource(
            originalMediaItem = mediaItem,
            videoId = videoId,
            scope = scope,
            peekCachedStream = peekCachedStream,
            streamResolver = streamResolver,
            childFactory = { resolved ->
                youtubeClient.registerResolvedStreamHeaders(resolved)
                buildResolvedChildMediaSource(mediaItem, resolved)
            }
        )
    }

    internal fun buildResolvedChildMediaSource(
        originalMediaItem: MediaItem,
        resolved: ResolvedAudioStream
    ): MediaSource {
        val isHlsStream = resolved.isHls ||
            resolved.mimeType.equals(MimeTypes.APPLICATION_M3U8, ignoreCase = true) ||
            resolved.url.contains(".m3u8", ignoreCase = true) ||
            resolved.url.contains("hls_playlist", ignoreCase = true) ||
            resolved.url.contains("hls_variant", ignoreCase = true)

        val resolvedItem = originalMediaItem.buildUpon()
            .setUri(Uri.parse(resolved.url))
            .setMimeType(if (isHlsStream) MimeTypes.APPLICATION_M3U8 else null)
            .build()

        return if (isHlsStream) {
            hlsMediaSourceFactory.createMediaSource(resolvedItem)
        } else {
            progressiveMediaSourceFactory.createMediaSource(resolvedItem)
        }
    }

    fun release() {
        scope.cancel()
    }
}

@OptIn(UnstableApi::class)
internal class ResolvingYouTubeMediaSource(
    private val originalMediaItem: MediaItem,
    private val videoId: String,
    private val scope: CoroutineScope,
    private val peekCachedStream: (String) -> ResolvedAudioStream? = { null },
    private val streamResolver: suspend (String) -> Result<ResolvedAudioStream>,
    private val childFactory: (ResolvedAudioStream) -> MediaSource
) : CompositeMediaSource<Void?>() {

    private var resolveJob: Job? = null
    private val released = AtomicBoolean(false)

    @Volatile
    private var currentMediaItem: MediaItem = originalMediaItem

    @Volatile
    private var childSource: MediaSource? = null

    @Volatile
    private var resolutionError: IOException? = null

    @Volatile
    private var playbackHandler: Handler? = null

    internal fun preparedChildSourceForTest(): MediaSource? = childSource

    override fun getMediaItem(): MediaItem = currentMediaItem

    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean {
        return mediaItem.mediaId == currentMediaItem.mediaId &&
            mediaItem.localConfiguration?.uri == currentMediaItem.localConfiguration?.uri &&
            mediaItem.localConfiguration?.mimeType == currentMediaItem.localConfiguration?.mimeType
    }

    override fun updateMediaItem(mediaItem: MediaItem) {
        currentMediaItem = mediaItem
        val child = childSource ?: return
        val childLocalConfig = child.mediaItem.localConfiguration
        val childUpdated = if (childLocalConfig != null) {
            mediaItem.buildUpon()
                .setUri(childLocalConfig.uri)
                .setMimeType(childLocalConfig.mimeType)
                .build()
        } else {
            mediaItem
        }
        if (child.canUpdateMediaItem(childUpdated)) {
            child.updateMediaItem(childUpdated)
        }
    }

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        released.set(false)
        resolutionError = null
        val handler = Handler(Looper.myLooper() ?: Looper.getMainLooper())
        playbackHandler = handler
        peekCachedStream(videoId)?.let { cached ->
            val created = childFactory(cached)
            childSource = created
            prepareChildSource(null, created)
            return
        }
        resolveJob = scope.launch {
            val result = try {
                streamResolver(videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Result.failure(t)
            }
            handler.post {
                if (released.get()) return@post
                val stream = result.getOrNull()
                if (stream != null) {
                    val created = childFactory(stream)
                    childSource = created
                    prepareChildSource(null, created)
                } else {
                    val err = result.exceptionOrNull()
                    val ioErr = (err as? IOException)
                        ?: IOException(err?.message ?: "YouTube audio stream unavailable", err)
                    resolutionError = ioErr
                    val failing = FailingChildMediaSource(currentMediaItem, ioErr)
                    childSource = failing
                    prepareChildSource(null, failing)
                }
            }
        }
    }

    override fun maybeThrowSourceInfoRefreshError() {
        resolutionError?.let { throw it }
        super.maybeThrowSourceInfoRefreshError()
    }

    override fun onChildSourceInfoRefreshed(
        childSourceId: Void?,
        mediaSource: MediaSource,
        newTimeline: Timeline
    ) {
        if (mediaSource !is FailingChildMediaSource && !newTimeline.isEmpty) {
            val window = newTimeline.getWindow(0, Timeline.Window())
            if (window.durationUs == 0L) {
                val emptyErr = IOException("Resolved YouTube audio stream has 0ms duration")
                resolutionError = emptyErr
                val handler = playbackHandler ?: Handler(Looper.myLooper() ?: Looper.getMainLooper())
                handler.post {
                    if (released.get() || childSource !== mediaSource) return@post
                    releaseChildSource(null)
                    val failing = FailingChildMediaSource(currentMediaItem, emptyErr)
                    childSource = failing
                    prepareChildSource(null, failing)
                }
                return
            }
        }
        refreshSourceInfo(newTimeline)
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        val child = childSource
            ?: throw IllegalStateException("YouTube MediaSource period requested before child source prepared")
        return child.createPeriod(id, allocator, startPositionUs)
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        childSource?.releasePeriod(mediaPeriod)
    }

    override fun releaseSourceInternal() {
        released.set(true)
        resolveJob?.cancel()
        resolveJob = null
        playbackHandler = null
        childSource = null
        resolutionError = null
        super.releaseSourceInternal()
    }
}

@OptIn(UnstableApi::class)
private class FailingChildMediaSource(
    private val mediaItem: MediaItem,
    private val error: IOException
) : BaseMediaSource() {

    override fun getMediaItem(): MediaItem = mediaItem

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        refreshSourceInfo(
            SinglePeriodTimeline(
                C.TIME_UNSET,
                /* isSeekable= */ false,
                /* isDynamic= */ false,
                /* useDynamicConfiguration= */ false,
                /* manifest= */ null,
                mediaItem
            )
        )
    }

    override fun maybeThrowSourceInfoRefreshError() {
        // Defer throwing until createPeriod / maybeThrowPrepareError so pre-buffering a failing
        // upcoming track does not interrupt the currently playing track.
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod = FailingChildMediaPeriod(error)

    override fun releasePeriod(mediaPeriod: MediaPeriod) = Unit

    override fun releaseSourceInternal() = Unit
}

@OptIn(UnstableApi::class)
private class FailingChildMediaPeriod(
    private val error: IOException
) : MediaPeriod {
    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) = Unit

    override fun maybeThrowPrepareError() {
        throw error
    }

    override fun getTrackGroups(): TrackGroupArray = TrackGroupArray.EMPTY

    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<out SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long
    ): Long = positionUs

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = Unit

    override fun readDiscontinuity(): Long = C.TIME_UNSET

    override fun seekToUs(positionUs: Long): Long = positionUs

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long = positionUs

    override fun getBufferedPositionUs(): Long = 0L

    override fun getNextLoadPositionUs(): Long = 0L

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean = false

    override fun isLoading(): Boolean = false

    override fun reevaluateBuffer(positionUs: Long) = Unit
}
