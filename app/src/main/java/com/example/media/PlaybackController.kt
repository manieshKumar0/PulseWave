package com.example.media

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.example.data.local.ListeningSignalEntity
import com.example.data.local.SignalDao
import com.example.data.model.DefaultSoundscapes
import com.example.data.model.SoundscapePreset
import com.example.data.model.Track
import com.example.data.remote.YouTubeMusicClient
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Calendar
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import kotlin.math.sin

data class PlaybackUiState(
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val activeSoundscape: SoundscapePreset = DefaultSoundscapes.presets.first(),
    val eqEnabled: Boolean = true,
    val bandLevels: List<Int> = DefaultSoundscapes.presets.first().bandLevelsMillibel,
    val bassBoost: Int = DefaultSoundscapes.presets.first().bassBoostStrength,
    val reverbPreset: Int = DefaultSoundscapes.presets.first().reverbPreset,
    val beatPulseEnergy: Float = 0.25f,
    val beatPhase: Float = 0f,
    val crossfadeSeconds: Int = 4,
    val sleepTimerRemainingMs: Long? = null,
    val sleepTimerTotalMs: Long? = null,
    val playbackError: String? = null
)

private data class PendingPlayRequest(
    val track: Track,
    val queue: List<Track>,
    val forceRefresh: Boolean
)

class PlaybackController internal constructor(
    private val context: Context,
    private val signalDao: SignalDao,
    private val youtubeClient: YouTubeMusicClient = YouTubeMusicClient.shared,
    injectedPlayer: Player? = null,
    autoConnectController: Boolean = true
) {
    constructor(context: Context, signalDao: SignalDao) : this(
        context = context,
        signalDao = signalDao,
        youtubeClient = YouTubeMusicClient.shared,
        injectedPlayer = null,
        autoConnectController = true
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: Player? = null
    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var resolvePlayJob: Job? = null
    private var pendingPlayRequest: PendingPlayRequest? = null

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var trackPlayStartedMs: Long = 0L
    private var maxPositionReachedMs: Long = 0L
    private var seekBackCounter: Int = 0
    private var lastTrackId: String? = null
    private var lastRetriedYouTubeId: String? = null
    private var youtubeRetryCount: Int = 0
    private var replayCounts = mutableMapOf<String, Int>()
    private var fadeInStartedAtMs: Long = 0L
    private var fadeInDurationMs: Long = 0L
    private var sleepTimerEndRealtimeMs: Long? = null
    private val playbackAttemptSeq = AtomicLong(0L)
    private val playRequestGeneration = AtomicLong(0L)
    @Volatile
    private var activeCorrelationId: String = "pb-0"
    @Volatile
    private var hasHandedUriForCurrentAttempt: Boolean = false

    internal fun cancelActiveResolutionForTest() {
        resolvePlayJob?.cancel(CancellationException("Playback cancelled"))
    }

    init {
        if (injectedPlayer != null) {
            mediaController = injectedPlayer
            attachPlayerListener(injectedPlayer)
            applyCurrentDsp()
            startProgressLoop()
        } else if (autoConnectController) {
            connectController()
        }
    }

    private fun connectController() {
        if (controllerFuture != null || mediaController != null) return
        val token = SessionToken(context, ComponentName(context, PulseMediaService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val controller = future.get()
                mediaController = controller
                attachPlayerListener(controller)
                applyCurrentDsp()
                startProgressLoop()
                pendingPlayRequest?.let { pending ->
                    pendingPlayRequest = null
                    playTrack(pending.track, pending.queue, forceRefresh = pending.forceRefresh)
                }
            } catch (_: Exception) {
                controllerFuture = null
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun attachPlayerListener(controller: Player) {
        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    AudioSpectrumBus.setPlaybackActive(false)
                }
                _state.update { it.copy(isPlaying = isPlaying) }
                val activeTrack = _state.value.currentTrack
                if (activeTrack != null && !activeTrack.isDownloaded && activeTrack.youtubeVideoId != null) {
                    Log.d(
                        "PULSE_YT_RESOLVER",
                        "stage_event corr=$activeCorrelationId stage=MEDIA3_PLAYING videoId=${activeTrack.youtubeVideoId} isPlaying=$isPlaying state=${playbackStateName(controller.playbackState)} handoffDone=$hasHandedUriForCurrentAttempt posMs=${controller.currentPosition.coerceAtLeast(0L)}"
                    )
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.update {
                    val resolvingActive = resolvePlayJob?.isActive == true && it.playbackError == null
                    it.copy(
                        isBuffering = (playbackState == Player.STATE_BUFFERING) || resolvingActive,
                        durationMs = controller.duration.takeIf { d -> d > 0L }
                            ?: (it.currentTrack?.durationMs ?: 0L)
                    )
                }
                val activeTrack = _state.value.currentTrack
                if (activeTrack != null && !activeTrack.isDownloaded && activeTrack.youtubeVideoId != null) {
                    Log.i(
                        "PULSE_YT_RESOLVER",
                        "stage_event corr=$activeCorrelationId stage=MEDIA3_STATE videoId=${activeTrack.youtubeVideoId} state=${playbackStateName(playbackState)} isBuffering=${playbackState == Player.STATE_BUFFERING} handoffDone=$hasHandedUriForCurrentAttempt posMs=${controller.currentPosition.coerceAtLeast(0L)} durMs=${controller.duration}"
                    )
                }
                if (playbackState == Player.STATE_ENDED) {
                    recordTrackSignal(completed = true)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (lastTrackId != null && lastTrackId != mediaItem?.mediaId) {
                    recordTrackSignal(completed = false)
                }
                val idx = controller.currentMediaItemIndex
                val queue = _state.value.queue
                val nextTrack = queue.getOrNull(idx)
                if (nextTrack != null) {
                    lastTrackId = nextTrack.id
                    lastRetriedYouTubeId = null
                    youtubeRetryCount = 0
                    trackPlayStartedMs = System.currentTimeMillis()
                    maxPositionReachedMs = 0L
                    seekBackCounter = 0
                    triggerFadeInIfEnabled(controller)
                    _state.update {
                        it.copy(
                            currentTrack = nextTrack,
                            currentIndex = idx,
                            durationMs = nextTrack.durationMs,
                            playbackError = null
                        )
                    }
                    // If this queue slot is still a lazy pulseyt:// URI, resolve it on-demand
                    // so HLS mimeType & headers are attached cleanly without multi-track prewarming.
                    val itemUriScheme = mediaItem?.localConfiguration?.uri?.scheme
                    val videoId = nextTrack.youtubeVideoId
                    if (!nextTrack.isDownloaded &&
                        itemUriScheme == Track.YOUTUBE_URI_SCHEME &&
                        videoId != null
                    ) {
                        val corrId = "pb-${playbackAttemptSeq.incrementAndGet()}"
                        activeCorrelationId = corrId
                        hasHandedUriForCurrentAttempt = false
                        val startTsMs = System.currentTimeMillis()
                        Log.i(
                            "PULSE_YT_RESOLVER",
                            "stage_event corr=$corrId stage=MEDIA3_QUEUE_TRANSITION trackId=${nextTrack.id} videoId=$videoId index=$idx reason=${transitionReasonName(reason)} hasResolvedUri=false startTsMs=$startTsMs"
                        )
                        resolvePlayJob?.cancel()
                        val transitionGeneration = playRequestGeneration.incrementAndGet()
                        _state.update { it.copy(isBuffering = true, playbackError = null) }
                        resolvePlayJob = scope.launch {
                            val startNs = System.nanoTime()
                            try {
                                val resolved = withContext(Dispatchers.IO) {
                                    youtubeClient.ensureAuthSessionHydrated(context)
                                    youtubeClient.resolveAudioStream(
                                        videoId = videoId,
                                        correlationId = corrId
                                    )
                                }
                                val endTsMs = System.currentTimeMillis()
                                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                                if (!isActive ||
                                    transitionGeneration != playRequestGeneration.get() ||
                                    _state.value.currentTrack?.id != nextTrack.id
                                ) {
                                    return@launch
                                }
                                val stream = resolved.getOrNull()
                                if (stream != null && idx in 0 until controller.mediaItemCount &&
                                    controller.getMediaItemAt(idx).mediaId == nextTrack.id
                                ) {
                                    val resolvedMime = if (stream.isHls) MimeTypes.APPLICATION_M3U8 else null
                                    hasHandedUriForCurrentAttempt = true
                                    Log.i(
                                        "PULSE_YT_RESOLVER",
                                        "stage_event corr=$corrId stage=MEDIA3_HANDOFF source=QUEUE_TRANSITION videoId=$videoId startTsMs=$startTsMs endTsMs=$endTsMs durationMs=$elapsedMs outcome=SUCCESS isHls=${stream.isHls} format=${resolvedMime ?: stream.mimeType} host=${YouTubeMusicClient.redactHostOnly(stream.url)} queueIndex=$idx queueSize=${controller.mediaItemCount}"
                                    )
                                    youtubeClient.registerResolvedStreamHeaders(stream)
                                    val alreadyPreparedHere = controller.playbackState == Player.STATE_READY &&
                                        controller.currentMediaItemIndex == idx
                                    if (alreadyPreparedHere) {
                                        _state.update { it.copy(isBuffering = false) }
                                    } else {
                                        val updated = nextTrack.toMediaItemOrNull(
                                            overrideUri = stream.url,
                                            mimeType = resolvedMime
                                        )
                                        if (updated != null) {
                                            val pos = controller.currentPosition.coerceAtLeast(0L)
                                            val resumePlay = controller.playWhenReady
                                            controller.replaceMediaItem(idx, updated)
                                            if (pos > 0L) controller.seekTo(idx, pos)
                                            controller.prepare()
                                            if (resumePlay) controller.play()
                                        }
                                    }
                                } else if (stream == null) {
                                    val rawErr = resolved.exceptionOrNull()
                                    val errMsg = rawErr?.message
                                        ?.takeIf { it.isNotBlank() }
                                        ?: "Audio stream unavailable"
                                    val timedOut = rawErr is SocketTimeoutException || rawErr?.cause is SocketTimeoutException
                                    val httpStatus = YouTubeMusicClient.extractHttpStatus(rawErr)
                                    val origin = YouTubeMusicClient.classifyFailureOrigin(rawErr, timedOut = timedOut)
                                    Log.w(
                                        "PULSE_YT_RESOLVER",
                                        "stage_event corr=$corrId stage=MEDIA3_QUEUE_TRANSITION_FAIL videoId=$videoId startTsMs=$startTsMs endTsMs=$endTsMs durationMs=$elapsedMs outcome=${if (timedOut) "TIMEOUT" else "FAILURE"} errorClass=${rawErr?.javaClass?.simpleName ?: "None"} rootCauseClass=${rawErr?.cause?.javaClass?.simpleName ?: "None"} httpStatus=$httpStatus usableStreamReturned=false isHls=false format=none cancelled=false timedOut=$timedOut failureOrigin=$origin phase=BEFORE_MEDIA3_HANDOFF msg=${YouTubeMusicClient.redactSensitiveUrls(errMsg)}"
                                    )
                                    controller.pause()
                                    controller.stop()
                                    _state.update {
                                        it.copy(
                                            isPlaying = false,
                                            isBuffering = false,
                                            positionMs = 0L,
                                            playbackError = errMsg
                                        )
                                    }
                                }
                            } catch (e: CancellationException) {
                                val endTsMs = System.currentTimeMillis()
                                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                                Log.i(
                                    "PULSE_YT_RESOLVER",
                                    "stage_event corr=$corrId stage=MEDIA3_QUEUE_TRANSITION_RESOLVE videoId=$videoId startTsMs=$startTsMs endTsMs=$endTsMs durationMs=$elapsedMs outcome=CANCELLED errorClass=CancellationException rootCauseClass=${e.cause?.javaClass?.simpleName ?: "None"} httpStatus=0 usableStreamReturned=false isHls=false format=none cancelled=true timedOut=false failureOrigin=LOCAL_APP phase=BEFORE_MEDIA3_HANDOFF"
                                )
                                if (transitionGeneration == playRequestGeneration.get()) {
                                    _state.update { current ->
                                        if (current.isBuffering || current.isPlaying) {
                                            current.copy(isPlaying = false, isBuffering = false)
                                        } else {
                                            current
                                        }
                                    }
                                }
                                throw e
                            } finally {
                                if (transitionGeneration == playRequestGeneration.get() &&
                                    !hasHandedUriForCurrentAttempt &&
                                    _state.value.isBuffering
                                ) {
                                    _state.update { it.copy(isBuffering = false) }
                                }
                            }
                        }
                    } else if (!nextTrack.isDownloaded && videoId != null) {
                        hasHandedUriForCurrentAttempt = true
                        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                            resolvePlayJob?.cancel()
                            playRequestGeneration.incrementAndGet()
                            Log.i(
                                "PULSE_YT_RESOLVER",
                                "stage_event corr=$activeCorrelationId stage=MEDIA3_QUEUE_TRANSITION trackId=${nextTrack.id} videoId=$videoId index=$idx reason=${transitionReasonName(reason)} hasResolvedUri=true host=${YouTubeMusicClient.redactHostOnly(mediaItem?.localConfiguration?.uri?.toString())}"
                            )
                        }
                    } else if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                        resolvePlayJob?.cancel()
                        playRequestGeneration.incrementAndGet()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (resolvePlayJob?.isActive == true && !hasHandedUriForCurrentAttempt) {
                    return
                }
                val rootCause = generateSequence(error as Throwable?) { it.cause }.lastOrNull()
                val rawRootMessage = rootCause?.message
                    ?.takeIf { it.isNotBlank() }
                    ?: error.localizedMessage
                    ?: "Audio stream unavailable"

                val httpStatusMatch = Regex("\\b(?:401|402|403|429)\\b")
                val isYouTubeTrack = _state.value.currentTrack?.youtubeVideoId != null
                val formattedPlayerError = if (
                    isYouTubeTrack &&
                    (httpStatusMatch.containsMatchIn(rawRootMessage) ||
                        rawRootMessage.startsWith("Response code:", ignoreCase = true))
                ) {
                    YouTubeMusicClient.humanReadableExtractionError(rootCause ?: error)
                } else {
                    rawRootMessage
                }

                val nonRetryableYouTubeRejection = Regex("\\b(?:402|429)\\b").containsMatchIn(formattedPlayerError) ||
                    YouTubeMusicClient.isYouTubeSignInSuggestionError(formattedPlayerError) ||
                    formattedPlayerError.contains("no longer supported", ignoreCase = true) ||
                    formattedPlayerError.contains("unavailable on YouTube in your region", ignoreCase = true) ||
                    formattedPlayerError.contains("private or restricted by the uploader", ignoreCase = true) ||
                    formattedPlayerError.contains("YouTube Music Premium", ignoreCase = true)

                val activeTrack = _state.value.currentTrack
                val activeVideoId = activeTrack?.youtubeVideoId
                val willAutoRetry = !nonRetryableYouTubeRejection &&
                    activeTrack != null &&
                    !activeTrack.isDownloaded &&
                    activeVideoId != null &&
                    (lastRetriedYouTubeId != activeVideoId || youtubeRetryCount < 1)
                val httpStatus = extractMedia3HttpStatus(error).takeIf { it > 0 }
                    ?: YouTubeMusicClient.extractHttpStatus(error)
                val failureOrigin = classifyMedia3FailureOrigin(error, httpStatus)
                val errorPhase = if (hasHandedUriForCurrentAttempt) {
                    "AFTER_MEDIA3_HANDOFF"
                } else {
                    "BEFORE_MEDIA3_HANDOFF"
                }
                if (activeTrack == null || (!activeTrack.isDownloaded && activeVideoId != null)) {
                    Log.w(
                        "PULSE_YT_RESOLVER",
                        "stage_event corr=$activeCorrelationId stage=MEDIA3_PLAYER_ERROR errorCode=${error.errorCodeName} errorClass=${error.javaClass.simpleName} rootCauseClass=${rootCause?.javaClass?.simpleName ?: "None"} httpStatus=$httpStatus failureOrigin=$failureOrigin phase=$errorPhase trackId=${activeTrack?.id} videoId=$activeVideoId nonRetryable=$nonRetryableYouTubeRejection willAutoRetry=$willAutoRetry msg=${YouTubeMusicClient.redactSensitiveUrls(rawRootMessage)}"
                    )
                }
                if (willAutoRetry && activeTrack != null && activeVideoId != null) {
                    if (lastRetriedYouTubeId != activeVideoId) {
                        lastRetriedYouTubeId = activeVideoId
                        youtubeRetryCount = 0
                    }
                    youtubeRetryCount++
                    val retryCorrId = "${activeCorrelationId}-r$youtubeRetryCount"
                    activeCorrelationId = retryCorrId
                    hasHandedUriForCurrentAttempt = false
                    val retryStartTsMs = System.currentTimeMillis()
                    val retryStartNs = System.nanoTime()
                    val resumePos = controller.currentPosition.coerceAtLeast(0L)
                    val idx = controller.currentMediaItemIndex.coerceAtLeast(0)
                    _state.update { it.copy(isBuffering = true, playbackError = null) }
                    resolvePlayJob?.cancel()
                    val retryGeneration = playRequestGeneration.incrementAndGet()
                    resolvePlayJob = scope.launch {
                        try {
                            val resolved = withContext(Dispatchers.IO) {
                                youtubeClient.ensureAuthSessionHydrated(context)
                                youtubeClient.markStreamFailedAndInvalidate(activeVideoId)
                                youtubeClient.resolveAudioStream(
                                    videoId = activeVideoId,
                                    forceRefresh = true,
                                    correlationId = retryCorrId
                                )
                            }
                            val retryEndTsMs = System.currentTimeMillis()
                            val retryElapsedMs = (System.nanoTime() - retryStartNs) / 1_000_000L
                            if (!isActive ||
                                retryGeneration != playRequestGeneration.get() ||
                                _state.value.currentTrack?.id != activeTrack.id
                            ) {
                                return@launch
                            }
                            val stream = resolved.getOrNull()
                            if (stream != null && idx in 0 until controller.mediaItemCount) {
                                val resolvedMime = if (stream.isHls) MimeTypes.APPLICATION_M3U8 else null
                                hasHandedUriForCurrentAttempt = true
                                Log.i(
                                    "PULSE_YT_RESOLVER",
                                    "stage_event corr=$retryCorrId stage=MEDIA3_HANDOFF source=PLAYER_ERROR_RETRY videoId=$activeVideoId startTsMs=$retryStartTsMs endTsMs=$retryEndTsMs durationMs=$retryElapsedMs outcome=SUCCESS isHls=${stream.isHls} format=${resolvedMime ?: stream.mimeType} host=${YouTubeMusicClient.redactHostOnly(stream.url)} queueIndex=$idx queueSize=${controller.mediaItemCount}"
                                )
                                youtubeClient.registerResolvedStreamHeaders(stream)
                                val replacement = activeTrack.toMediaItemOrNull(
                                    overrideUri = stream.url,
                                    mimeType = resolvedMime
                                )
                                if (replacement != null) {
                                    controller.replaceMediaItem(idx, replacement)
                                    if (resumePos > 1_500L) {
                                        controller.seekTo(idx, resumePos)
                                    }
                                    controller.prepare()
                                    controller.play()
                                    return@launch
                                }
                            }
                            val rawRetryErr = resolved.exceptionOrNull()
                            val errMsg = rawRetryErr?.message
                                ?.takeIf { it.isNotBlank() }
                                ?: formattedPlayerError
                            Log.w(
                                "PULSE_YT_RESOLVER",
                                "stage_event corr=$retryCorrId stage=PLAYBACK_RETRY_FAIL videoId=$activeVideoId startTsMs=$retryStartTsMs endTsMs=$retryEndTsMs durationMs=$retryElapsedMs outcome=FAILURE errorClass=${rawRetryErr?.javaClass?.simpleName ?: "None"} httpStatus=${YouTubeMusicClient.extractHttpStatus(rawRetryErr)} failureOrigin=${YouTubeMusicClient.classifyFailureOrigin(rawRetryErr)} phase=BEFORE_MEDIA3_HANDOFF msg=${YouTubeMusicClient.redactSensitiveUrls(errMsg)}"
                            )
                            _state.update {
                                it.copy(
                                    isPlaying = false,
                                    isBuffering = false,
                                    playbackError = errMsg
                                )
                            }
                        } catch (e: CancellationException) {
                            if (retryGeneration == playRequestGeneration.get()) {
                                _state.update { current ->
                                    if (current.isBuffering || current.isPlaying) {
                                        current.copy(isPlaying = false, isBuffering = false)
                                    } else {
                                        current
                                    }
                                }
                            }
                            throw e
                        } finally {
                            if (retryGeneration == playRequestGeneration.get() &&
                                !hasHandedUriForCurrentAttempt &&
                                _state.value.isBuffering
                            ) {
                                _state.update { it.copy(isBuffering = false) }
                            }
                        }
                    }
                    return
                }
                _state.update {
                    it.copy(
                        isPlaying = false,
                        isBuffering = false,
                        playbackError = formattedPlayerError
                    )
                }
            }
        })
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                val controller = mediaController
                val hasError = _state.value.playbackError != null
                val resolving = resolvePlayJob?.isActive == true
                val playing = !hasError && !resolving && controller?.isPlaying == true
                if (controller != null && !hasError && !resolving) {
                    val pos = controller.currentPosition.coerceAtLeast(0L)
                    if (pos > maxPositionReachedMs) maxPositionReachedMs = pos
                    val dur = controller.duration.takeIf { it > 0 }
                        ?: _state.value.currentTrack?.durationMs ?: 180_000L
                    val audioFrame = if (playing) {
                        AudioSpectrumBus.sampleNow(isPlaying = true)
                    } else {
                        AudioVisualizerFrame.EMPTY
                    }
                    val (energy, rawPhase) = if (!playing) {
                        0.12f to 0f
                    } else if (audioFrame.isLive) {
                        val liveEnergy = (
                            0.16f +
                                audioFrame.bassEnergy * 0.48f +
                                audioFrame.overallEnergy * 0.24f +
                                audioFrame.beatImpulse * 0.26f
                            ).coerceIn(0.16f, 1.0f)
                        liveEnergy to audioFrame.beatPhase
                    } else {
                        val bpm = (_state.value.currentTrack?.bpm ?: 118).coerceIn(60, 200)
                        val beatDurationMs = 60_000.0 / bpm
                        val fallbackPhase = ((pos % beatDurationMs) / beatDurationMs).toFloat().coerceIn(0f, 1f)
                        val beatAngle = (pos / beatDurationMs) * Math.PI * 2.0
                        val fallbackEnergy = ((sin(beatAngle) * 0.25 + 0.55).toFloat()).coerceIn(0.2f, 0.85f)
                        fallbackEnergy to fallbackPhase
                    }

                    applyCrossfadeEnvelope(controller, playing, pos, dur)

                    _state.update {
                        it.copy(
                            positionMs = pos,
                            durationMs = dur,
                            isPlaying = playing,
                            beatPulseEnergy = energy,
                            beatPhase = rawPhase
                        )
                    }
                } else if (hasError) {
                    _state.update {
                        if (it.isPlaying || it.isBuffering || it.beatPhase != 0f) {
                            it.copy(
                                isPlaying = false,
                                isBuffering = false,
                                beatPulseEnergy = 0.12f,
                                beatPhase = 0f
                            )
                        } else it
                    }
                }
                delay(if (playing) 50L else 180L)
            }
        }
    }

    private fun triggerFadeInIfEnabled(controller: Player?) {
        val crossfadeMs = _state.value.crossfadeSeconds * 1000L
        if (crossfadeMs > 0L) {
            fadeInDurationMs = (crossfadeMs / 2L).coerceIn(400L, 6000L)
            fadeInStartedAtMs = System.currentTimeMillis()
            controller?.volume = 0.04f
        } else {
            fadeInDurationMs = 0L
            controller?.volume = 1f
        }
    }

    private fun applyCrossfadeEnvelope(
        controller: Player,
        playing: Boolean,
        positionMs: Long,
        durationMs: Long
    ) {
        val sleepRemainingMs = sleepTimerEndRealtimeMs?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0L) }
        val sleepFadeFactor = if (playing && sleepRemainingMs != null && sleepRemainingMs <= 3_000L) {
            (sleepRemainingMs.toFloat() / 3_000f).coerceIn(0.06f, 1f)
        } else {
            1f
        }

        val crossfadeTotalMs = _state.value.crossfadeSeconds * 1000L
        if (crossfadeTotalMs <= 0L || !playing) {
            val nonCrossfadeVolume = if (playing) sleepFadeFactor else 1f
            if (kotlin.math.abs(controller.volume - nonCrossfadeVolume) > 0.01f) {
                controller.volume = nonCrossfadeVolume
            }
            return
        }

        val fadeInFactor = if (fadeInDurationMs > 0L) {
            val elapsed = (System.currentTimeMillis() - fadeInStartedAtMs).coerceAtLeast(0L)
            val tIn = (elapsed.toFloat() / fadeInDurationMs.toFloat()).coerceIn(0f, 1f)
            if (tIn >= 1f) {
                fadeInDurationMs = 0L
                1f
            } else {
                sin(tIn * (Math.PI / 2.0)).toFloat().coerceAtLeast(0.04f)
            }
        } else {
            1f
        }

        val fadeOutWindowMs = (crossfadeTotalMs / 2L)
            .coerceAtMost((durationMs * 0.35f).toLong().coerceAtLeast(500L))
        val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
        val fadeOutFactor = if (durationMs > 5_000L && controller.hasNextMediaItem() && remainingMs <= fadeOutWindowMs) {
            val tOut = (remainingMs.toFloat() / fadeOutWindowMs.toFloat()).coerceIn(0f, 1f)
            sin(tOut * (Math.PI / 2.0)).toFloat().coerceAtLeast(0.04f)
        } else {
            1f
        }

        val targetVolume = (minOf(fadeInFactor, fadeOutFactor) * sleepFadeFactor).coerceIn(0.04f, 1f)
        if (kotlin.math.abs(controller.volume - targetVolume) > 0.01f) {
            controller.volume = targetVolume
        }
    }

    fun setCrossfadeSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(0, 12)
        _state.update { it.copy(crossfadeSeconds = clamped) }
        if (clamped == 0) {
            fadeInDurationMs = 0L
            mediaController?.volume = 1f
        }
    }

    fun startSleepTimer(minutes: Int) {
        if (minutes <= 0) {
            cancelSleepTimer()
            return
        }
        val totalMs = minutes.coerceIn(1, 240) * 60_000L
        sleepTimerEndRealtimeMs = SystemClock.elapsedRealtime() + totalMs
        _state.update {
            it.copy(
                sleepTimerRemainingMs = totalMs,
                sleepTimerTotalMs = totalMs
            )
        }
        launchSleepTimerTicker()
    }

    fun extendSleepTimer(extraMinutes: Int = 5) {
        val currentEnd = sleepTimerEndRealtimeMs
        if (currentEnd == null) {
            startSleepTimer(extraMinutes)
            return
        }
        val addedMs = extraMinutes.coerceAtLeast(1) * 60_000L
        val newEnd = currentEnd + addedMs
        sleepTimerEndRealtimeMs = newEnd
        val remaining = (newEnd - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val newTotal = maxOf(_state.value.sleepTimerTotalMs ?: remaining, remaining)
        _state.update {
            it.copy(
                sleepTimerRemainingMs = remaining,
                sleepTimerTotalMs = newTotal
            )
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerEndRealtimeMs = null
        mediaController?.volume = 1f
        _state.update {
            it.copy(
                sleepTimerRemainingMs = null,
                sleepTimerTotalMs = null
            )
        }
    }

    private fun launchSleepTimerTicker() {
        sleepTimerJob?.cancel()
        sleepTimerJob = scope.launch {
            while (true) {
                val endTime = sleepTimerEndRealtimeMs ?: break
                val remaining = endTime - SystemClock.elapsedRealtime()
                if (remaining <= 0L) {
                    mediaController?.let { controller ->
                        if (controller.isPlaying) {
                            controller.pause()
                        }
                        controller.volume = 1f
                    }
                    sleepTimerEndRealtimeMs = null
                    _state.update {
                        it.copy(
                            sleepTimerRemainingMs = null,
                            sleepTimerTotalMs = null
                        )
                    }
                    break
                } else {
                    _state.update { it.copy(sleepTimerRemainingMs = remaining) }
                }
                delay(500L)
            }
        }
    }

    fun playTrack(
        track: Track,
        queue: List<Track> = listOf(track),
        forceRefresh: Boolean = false
    ) {
        val requestGeneration = playRequestGeneration.incrementAndGet()
        resolvePlayJob?.cancel()
        resolvePlayJob = null
        hasHandedUriForCurrentAttempt = false
        AudioSpectrumBus.clearPendingQueue()
        val safeQueue = if (queue.any { it.id == track.id }) queue else listOf(track) + queue
        val targetIndex = safeQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)

        if (lastTrackId == track.id) {
            replayCounts[track.id] = (replayCounts[track.id] ?: 0) + 1
        } else if (lastTrackId != null) {
            recordTrackSignal(completed = false)
        }

        lastTrackId = track.id
        lastRetriedYouTubeId = null
        youtubeRetryCount = 0
        trackPlayStartedMs = System.currentTimeMillis()
        maxPositionReachedMs = 0L
        seekBackCounter = 0

        val needsRemoteResolution = !track.isDownloaded &&
            track.playableUri.startsWith("pulseyt://") &&
            track.youtubeVideoId != null

        _state.update {
            it.copy(
                currentTrack = track,
                queue = safeQueue,
                currentIndex = targetIndex,
                positionMs = 0L,
                durationMs = track.durationMs,
                isPlaying = false,
                isBuffering = needsRemoteResolution,
                beatPulseEnergy = 0.14f,
                beatPhase = 0f,
                playbackError = null
            )
        }

        val controller = mediaController
        if (controller == null) {
            pendingPlayRequest = PendingPlayRequest(track, safeQueue, forceRefresh)
            connectController()
            return
        }

        if (!needsRemoteResolution) {
            val mediaItems = safeQueue.mapNotNull { it.toMediaItemOrNull() }
            if (mediaItems.isEmpty()) {
                controller.pause()
                controller.stop()
                controller.clearMediaItems()
                _state.update {
                    it.copy(isPlaying = false, isBuffering = false, positionMs = 0L, playbackError = "No playable audio URI for this track")
                }
                return
            }
            val safeIdx = targetIndex.coerceIn(0, mediaItems.lastIndex)
            triggerFadeInIfEnabled(controller)
            controller.setMediaItems(mediaItems, safeIdx, 0L)
            controller.prepare()
            controller.play()
            return
        }

        // Stop any previous track so prior audio/position does not bleed into a resolving or failed track
        controller.pause()
        controller.stop()
        controller.clearMediaItems()

        val videoId = track.youtubeVideoId!!
        val corrId = "pb-${playbackAttemptSeq.incrementAndGet()}"
        activeCorrelationId = corrId
        val reqStartTsMs = System.currentTimeMillis()
        resolvePlayJob = scope.launch {
            val startNs = System.nanoTime()
            try {
                val resolved = withContext(Dispatchers.IO) {
                    youtubeClient.ensureAuthSessionHydrated(context)
                    val diag = youtubeClient.getAuthDiagnostics()
                    Log.i(
                        "PULSE_YT_RESOLVER",
                        "stage_event corr=$corrId stage=PLAYBACK_REQUEST trackId=${track.id} videoId=$videoId startTsMs=$reqStartTsMs sourceType=${track.sourceType} isDownloaded=${track.isDownloaded} forceRefresh=$forceRefresh queueSize=${safeQueue.size} authState=${diag.state} hasAuthSession=${youtubeClient.hasAuthenticatedYouTubeSession()}"
                    )
                    youtubeClient.resolveAudioStream(
                        videoId = videoId,
                        forceRefresh = forceRefresh,
                        correlationId = corrId
                    )
                }
                val endTsMs = System.currentTimeMillis()
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                if (!isActive ||
                    requestGeneration != playRequestGeneration.get() ||
                    _state.value.currentTrack?.id != track.id
                ) {
                    return@launch
                }

                val stream = resolved.getOrNull()
                if (stream == null) {
                    val rawErr = resolved.exceptionOrNull()
                    val errMsg = rawErr?.message
                        ?.takeIf { it.isNotBlank() }
                        ?: "Audio stream unavailable"
                    val timedOut = rawErr is SocketTimeoutException || rawErr?.cause is SocketTimeoutException
                    val httpStatus = YouTubeMusicClient.extractHttpStatus(rawErr)
                    val origin = YouTubeMusicClient.classifyFailureOrigin(rawErr, timedOut = timedOut)
                    Log.w(
                        "PULSE_YT_RESOLVER",
                        "stage_event corr=$corrId stage=PLAYBACK_REQUEST trackId=${track.id} videoId=$videoId startTsMs=$reqStartTsMs endTsMs=$endTsMs durationMs=$elapsedMs outcome=${if (timedOut) "TIMEOUT" else "FAILURE"} errorClass=${rawErr?.javaClass?.simpleName ?: "None"} rootCauseClass=${rawErr?.cause?.javaClass?.simpleName ?: "None"} httpStatus=$httpStatus usableStreamReturned=false isHls=false format=none cancelled=false timedOut=$timedOut failureOrigin=$origin phase=BEFORE_MEDIA3_HANDOFF msg=${YouTubeMusicClient.redactSensitiveUrls(errMsg)}"
                    )
                    _state.update {
                        it.copy(
                            isPlaying = false,
                            isBuffering = false,
                            positionMs = 0L,
                            playbackError = errMsg
                        )
                    }
                    return@launch
                }

                val resolvedMime = if (stream.isHls) MimeTypes.APPLICATION_M3U8 else null
                youtubeClient.registerResolvedStreamHeaders(stream)
                val mediaItems = safeQueue.mapIndexedNotNull { idx, item ->
                    if (idx == targetIndex) {
                        item.toMediaItemOrNull(
                            overrideUri = stream.url,
                            mimeType = resolvedMime
                        )
                    } else {
                        item.toMediaItemOrNull()
                    }
                }
                if (mediaItems.isEmpty()) {
                    Log.w(
                        "PULSE_YT_RESOLVER",
                        "stage_event corr=$corrId stage=PLAYBACK_REQUEST trackId=${track.id} videoId=$videoId startTsMs=$reqStartTsMs endTsMs=$endTsMs durationMs=$elapsedMs outcome=FAILURE errorClass=IllegalStateException httpStatus=0 usableStreamReturned=false isHls=${stream.isHls} format=${resolvedMime ?: stream.mimeType} cancelled=false timedOut=false failureOrigin=LOCAL_APP phase=BEFORE_MEDIA3_HANDOFF msg=empty_media_items"
                    )
                    _state.update {
                        it.copy(isPlaying = false, isBuffering = false, positionMs = 0L, playbackError = "No playable audio URI for this track")
                    }
                    return@launch
                }
                val safeIdx = targetIndex.coerceIn(0, mediaItems.lastIndex)
                hasHandedUriForCurrentAttempt = true
                Log.i(
                    "PULSE_YT_RESOLVER",
                    "stage_event corr=$corrId stage=MEDIA3_HANDOFF source=PLAY_TRACK trackId=${track.id} videoId=$videoId startTsMs=$reqStartTsMs endTsMs=$endTsMs durationMs=$elapsedMs outcome=SUCCESS isHls=${stream.isHls} format=${resolvedMime ?: stream.mimeType} host=${YouTubeMusicClient.redactHostOnly(stream.url)} queueIndex=$safeIdx queueSize=${mediaItems.size}"
                )
                triggerFadeInIfEnabled(controller)
                controller.setMediaItems(mediaItems, safeIdx, 0L)
                controller.prepare()
                controller.play()
            } catch (e: CancellationException) {
                val endTsMs = System.currentTimeMillis()
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                Log.i(
                    "PULSE_YT_RESOLVER",
                    "stage_event corr=$corrId stage=PLAYBACK_REQUEST trackId=${track.id} videoId=$videoId startTsMs=$reqStartTsMs endTsMs=$endTsMs durationMs=$elapsedMs outcome=CANCELLED errorClass=CancellationException rootCauseClass=${e.cause?.javaClass?.simpleName ?: "None"} httpStatus=0 usableStreamReturned=false isHls=false format=none cancelled=true timedOut=false failureOrigin=LOCAL_APP phase=BEFORE_MEDIA3_HANDOFF"
                )
                if (requestGeneration == playRequestGeneration.get()) {
                    _state.update { current ->
                        if (current.isBuffering || current.isPlaying) {
                            current.copy(isPlaying = false, isBuffering = false)
                        } else {
                            current
                        }
                    }
                }
                throw e
            } finally {
                if (requestGeneration == playRequestGeneration.get() &&
                    !hasHandedUriForCurrentAttempt &&
                    _state.value.isBuffering
                ) {
                    _state.update { it.copy(isBuffering = false) }
                }
            }
        }
    }

    fun retryActiveTrack() {
        val track = _state.value.currentTrack ?: return
        val queue = _state.value.queue.ifEmpty { listOf(track) }
        val shouldForceRefresh = _state.value.playbackError != null
        playTrack(track, queue, forceRefresh = shouldForceRefresh)
    }

    fun togglePlayPause() {
        if (_state.value.playbackError != null) {
            retryActiveTrack()
            return
        }
        if (resolvePlayJob?.isActive == true) {
            return
        }
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0 && _state.value.currentTrack != null) {
            retryActiveTrack()
            return
        }
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    fun seekTo(positionMs: Long) {
        val controller = mediaController ?: return
        if (positionMs < controller.currentPosition - 3_000L) {
            seekBackCounter++
        }
        controller.seekTo(positionMs.coerceAtLeast(0L))
        _state.update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
    }

    fun skipNext() {
        val controller = mediaController ?: return
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem()
        }
    }

    fun skipPrevious() {
        val controller = mediaController ?: return
        if (controller.currentPosition > 4_000L) {
            seekBackCounter++
            controller.seekTo(0L)
        } else if (controller.hasPreviousMediaItem()) {
            controller.seekToPreviousMediaItem()
        }
    }

    fun toggleShuffle() {
        val controller = mediaController ?: return
        val next = !controller.shuffleModeEnabled
        controller.shuffleModeEnabled = next
        _state.update { it.copy(shuffleEnabled = next) }
    }

    fun cycleRepeatMode() {
        val controller = mediaController ?: return
        val next = when (controller.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        controller.repeatMode = next
        _state.update { it.copy(repeatMode = next) }
    }

    fun restoreEqualizerState(
        enabled: Boolean,
        soundscapeId: String,
        bands: List<Int>,
        bass: Int,
        reverb: Int
    ) {
        val safeBands = (0 until 5).map { idx ->
            (bands.getOrNull(idx) ?: 0).coerceIn(-1500, 1500)
        }
        val safeBass = bass.coerceIn(0, 1000)
        val safeReverb = reverb.coerceIn(0, 6)
        val matchedPreset = DefaultSoundscapes.presetForId(soundscapeId)
            ?: DefaultSoundscapes.customPreset(safeBands, safeBass, safeReverb)
        _state.update {
            it.copy(
                eqEnabled = enabled,
                activeSoundscape = matchedPreset,
                bandLevels = safeBands,
                bassBoost = safeBass,
                reverbPreset = safeReverb
            )
        }
        applyCurrentDsp()
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        _state.update { it.copy(eqEnabled = enabled) }
        applyCurrentDsp()
    }

    fun resetEqualizer() {
        val flatPreset = DefaultSoundscapes.presets.first()
        _state.update {
            it.copy(
                eqEnabled = true,
                activeSoundscape = flatPreset,
                bandLevels = flatPreset.bandLevelsMillibel,
                bassBoost = flatPreset.bassBoostStrength,
                reverbPreset = flatPreset.reverbPreset
            )
        }
        applyCurrentDsp()
    }

    fun applySoundscapePreset(preset: SoundscapePreset) {
        _state.update {
            it.copy(
                eqEnabled = true,
                activeSoundscape = preset,
                bandLevels = preset.bandLevelsMillibel,
                bassBoost = preset.bassBoostStrength,
                reverbPreset = preset.reverbPreset
            )
        }
        applyCurrentDsp()
    }

    fun updateCustomBand(index: Int, millibel: Int) {
        val updated = _state.value.bandLevels.toMutableList()
        if (index in updated.indices) {
            updated[index] = millibel.coerceIn(-1500, 1500)
            _state.update {
                it.copy(
                    eqEnabled = true,
                    bandLevels = updated,
                    activeSoundscape = DefaultSoundscapes.customPreset(
                        bandLevelsMillibel = updated,
                        bassBoostStrength = it.bassBoost,
                        reverbPreset = it.reverbPreset
                    )
                )
            }
            applyCurrentDsp()
        }
    }

    fun updateBassBoost(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        _state.update {
            it.copy(
                eqEnabled = true,
                bassBoost = clamped,
                activeSoundscape = DefaultSoundscapes.customPreset(
                    bandLevelsMillibel = it.bandLevels,
                    bassBoostStrength = clamped,
                    reverbPreset = it.reverbPreset
                )
            )
        }
        applyCurrentDsp()
    }

    fun updateReverbPreset(preset: Int) {
        val clamped = preset.coerceIn(0, 6)
        _state.update {
            it.copy(
                eqEnabled = true,
                reverbPreset = clamped,
                activeSoundscape = DefaultSoundscapes.customPreset(
                    bandLevelsMillibel = it.bandLevels,
                    bassBoostStrength = it.bassBoost,
                    reverbPreset = clamped
                )
            )
        }
        applyCurrentDsp()
    }

    fun morphSoundscape(from: SoundscapePreset, to: SoundscapePreset, fraction: Float) {
        val t = fraction.coerceIn(0f, 1f)
        val blendedBands = from.bandLevelsMillibel.indices.map { i ->
            val a = from.bandLevelsMillibel[i]
            val b = to.bandLevelsMillibel.getOrElse(i) { a }
            (a + ((b - a) * t)).toInt()
        }
        val blendedBass = (from.bassBoostStrength + ((to.bassBoostStrength - from.bassBoostStrength) * t)).toInt()
        val reverb = if (t < 0.5f) from.reverbPreset else to.reverbPreset
        _state.update {
            it.copy(
                eqEnabled = true,
                activeSoundscape = if (t >= 0.5f) to else from,
                bandLevels = blendedBands,
                bassBoost = blendedBass,
                reverbPreset = reverb
            )
        }
        applyCurrentDsp()
    }

    private fun applyCurrentDsp() {
        val controller = mediaController as? MediaController ?: return
        val s = _state.value
        val args = Bundle().apply {
            putBoolean(PulseMediaService.EXTRA_EQ_ENABLED, s.eqEnabled)
            putIntArray(PulseMediaService.EXTRA_BANDS, s.bandLevels.toIntArray())
            putInt(PulseMediaService.EXTRA_BASS, s.bassBoost)
            putInt(PulseMediaService.EXTRA_REVERB, s.reverbPreset)
        }
        controller.sendCustomCommand(
            SessionCommand(PulseMediaService.ACTION_APPLY_SOUNDSCAPE, Bundle.EMPTY),
            args
        )
    }

    fun updateTrackMetadata(updated: Track) {
        _state.update { current ->
            current.copy(
                currentTrack = if (current.currentTrack?.id == updated.id) updated else current.currentTrack,
                queue = current.queue.map { if (it.id == updated.id) updated else it }
            )
        }
    }

    fun syncToRoomPlayback(track: Track, isPlaying: Boolean, targetPosMs: Long) {
        val current = _state.value.currentTrack
        if (current?.id != track.id) {
            playTrack(track)
        }
        val controller = mediaController ?: return
        if (kotlin.math.abs(controller.currentPosition - targetPosMs) > 2_500L) {
            controller.seekTo(targetPosMs.coerceAtLeast(0L))
        }
        if (isPlaying && !controller.isPlaying) controller.play()
        else if (!isPlaying && controller.isPlaying) controller.pause()
    }

    private fun recordTrackSignal(completed: Boolean) {
        val track = _state.value.currentTrack ?: return
        val dur = _state.value.durationMs.coerceAtLeast(10_000L)
        val ratio = if (completed) 1.0f else (maxPositionReachedMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
        val dwell = (System.currentTimeMillis() - trackPlayStartedMs).coerceAtLeast(0L)
        val earlySkip = !completed && maxPositionReachedMs in 500L..15_000L && ratio < 0.18f
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val bucket = when (hour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Focus"
            in 17..21 -> "Evening"
            else -> "Late Night"
        }

        scope.launch(Dispatchers.IO) {
            signalDao.insertSignal(
                ListeningSignalEntity(
                    trackId = track.id,
                    genre = track.genre,
                    mood = track.mood,
                    bpm = track.bpm,
                    completionRatio = ratio,
                    earlySkip = earlySkip,
                    replayCount = replayCounts[track.id] ?: 0,
                    seekBackCount = seekBackCounter,
                    dwellMs = dwell,
                    liked = track.isLiked,
                    savedOffline = track.isDownloaded,
                    sharedInDm = false,
                    timeBucket = bucket
                )
            )
        }
    }

    private fun Track.toMediaItemOrNull(
        overrideUri: String? = null,
        mimeType: String? = null
    ): MediaItem? {
        val uriString = overrideUri ?: playableUri
        if (uriString.isBlank()) return null
        val artUri = displayArtworkUri
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .apply {
                if (artUri.isNotBlank()) {
                    setArtworkUri(Uri.parse(artUri))
                }
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uriString)
            .apply {
                if (!mimeType.isNullOrBlank()) {
                    setMimeType(mimeType)
                }
            }
            .setMediaMetadata(metadata)
            .build()
    }

    private fun playbackStateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }

    private fun transitionReasonName(reason: Int): String = when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
        else -> "UNKNOWN($reason)"
    }

    @OptIn(UnstableApi::class)
    private fun extractMedia3HttpStatus(error: Throwable?): Int {
        var cur = error
        var depth = 0
        while (cur != null && depth < 10) {
            if (cur is HttpDataSource.InvalidResponseCodeException) {
                return cur.responseCode
            }
            cur = cur.cause
            depth++
        }
        return 0
    }

    @OptIn(UnstableApi::class)
    private fun classifyMedia3FailureOrigin(error: PlaybackException, httpStatus: Int): String {
        if (httpStatus >= 400) return "UPSTREAM_SERVER"
        var cur: Throwable? = error
        var depth = 0
        while (cur != null && depth < 10) {
            when (cur) {
                is HttpDataSource.InvalidResponseCodeException -> return "UPSTREAM_SERVER"
                is HttpDataSource.HttpDataSourceException,
                is SocketTimeoutException,
                is UnknownHostException,
                is ConnectException,
                is SSLException -> return "NETWORK_OR_TIMEOUT"
            }
            cur = cur.cause
            depth++
        }
        return if (error.errorCodeName.contains("PARSING", ignoreCase = true)) {
            "EXTRACTOR"
        } else {
            "LOCAL_APP"
        }
    }

    fun release() {
        playRequestGeneration.incrementAndGet()
        resolvePlayJob?.cancel()
        resolvePlayJob = null
        progressJob?.cancel()
        sleepTimerJob?.cancel()
        AudioSpectrumBus.reset()
        _state.update { it.copy(isBuffering = false, isPlaying = false) }
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
