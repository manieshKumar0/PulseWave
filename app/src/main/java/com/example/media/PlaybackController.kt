package com.example.media

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
import kotlin.math.asin
import kotlin.math.cos
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
    val smartFadeOutEnabled: Boolean = true,
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
    private var pauseFadeJob: Job? = null
    @Volatile
    private var isFadingOutToPause: Boolean = false
    private var pauseRequestedBySmartFade: Boolean = false
    private var sleepTimerEndRealtimeMs: Long? = null
    private val playbackAttemptSeq = AtomicLong(0L)
    private val playRequestGeneration = AtomicLong(0L)
    @Volatile
    private var activeCorrelationId: String = "pb-0"
    @Volatile
    private var hasHandedUriForCurrentAttempt: Boolean = false
    @Volatile
    private var isResolvingRemoteTrack: Boolean = false
    @Volatile
    private var awaitingMedia3TrackId: String? = null
    @Volatile
    private var replacingActiveTrackId: String? = null
    private var rapidAutoSkipCount: Int = 0

    private companion object {
        const val SMART_PAUSE_FADE_OUT_MS = 460L
        const val SMART_RESUME_FADE_IN_MS = 220L
    }

    internal fun cancelActiveResolutionForTest() {
        resolvePlayJob?.cancel(CancellationException("Playback cancelled"))
    }

    private inline fun runOnPlayerThread(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            scope.launch {
                block()
            }
        }
    }

    init {
        if (injectedPlayer != null) {
            mediaController = injectedPlayer
            runOnPlayerThread {
                attachPlayerListener(injectedPlayer)
                syncFromPlayer(injectedPlayer)
                applyCurrentDsp()
                startProgressLoop()
            }
        } else if (autoConnectController) {
            runOnPlayerThread {
                connectController()
            }
        }
    }

    private fun connectController() {
        runOnPlayerThread {
            if (controllerFuture != null || mediaController != null) return@runOnPlayerThread
            val token = SessionToken(context, ComponentName(context, PulseMediaService::class.java))
            val future = MediaController.Builder(context, token)
                .setApplicationLooper(Looper.getMainLooper())
                .buildAsync()
            controllerFuture = future
            future.addListener({
                try {
                    val controller = future.get()
                    mediaController = controller
                    attachPlayerListener(controller)
                    applyCurrentDsp()
                    val pending = pendingPlayRequest
                    if (pending != null) {
                        pendingPlayRequest = null
                        playTrack(pending.track, pending.queue, forceRefresh = pending.forceRefresh)
                    } else {
                        syncFromPlayer(controller)
                    }
                    startProgressLoop()
                } catch (_: Exception) {
                    controllerFuture = null
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    internal fun syncFromPlayer(controller: Player) {
        val count = controller.mediaItemCount
        if (count <= 0) return
        val restoredQueue = (0 until count).mapNotNull { idx ->
            PulseMediaItemMapper.fromMediaItem(controller.getMediaItemAt(idx))
        }
        if (restoredQueue.isEmpty()) return
        val idx = controller.currentMediaItemIndex.coerceIn(0, restoredQueue.lastIndex)
        val activeTrack = restoredQueue[idx]
        lastTrackId = activeTrack.id
        if (activeTrack.youtubeVideoId != null && !activeTrack.isDownloaded) {
            hasHandedUriForCurrentAttempt = true
        }
        _state.update {
            it.copy(
                currentTrack = activeTrack,
                queue = restoredQueue,
                currentIndex = idx,
                isPlaying = controller.isPlaying,
                isBuffering = controller.playbackState == Player.STATE_BUFFERING,
                positionMs = controller.currentPosition.coerceAtLeast(0L),
                durationMs = controller.duration.takeIf { d -> d > 0L } ?: activeTrack.durationMs,
                shuffleEnabled = controller.shuffleModeEnabled,
                repeatMode = controller.repeatMode,
                playbackError = null
            )
        }
    }

    private fun attachPlayerListener(controller: Player) {
        controller.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _state.update { it.copy(shuffleEnabled = shuffleModeEnabled) }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _state.update { it.copy(repeatMode = repeatMode) }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    AudioSpectrumBus.setPlaybackActive(false)
                    if (isFadingOutToPause && !pauseRequestedBySmartFade) {
                        cancelPauseFadeOut(restoreVolume = true)
                    }
                    pauseRequestedBySmartFade = false
                }
                _state.update { it.copy(isPlaying = isPlaying && !isFadingOutToPause) }
                val activeTrack = _state.value.currentTrack
                if (activeTrack != null && !activeTrack.isDownloaded && activeTrack.youtubeVideoId != null) {
                    Log.d(
                        "PULSE_YT_RESOLVER",
                        "stage_event corr=$activeCorrelationId stage=MEDIA3_PLAYING videoId=${activeTrack.youtubeVideoId} isPlaying=$isPlaying state=${playbackStateName(controller.playbackState)} handoffDone=$hasHandedUriForCurrentAttempt posMs=${controller.currentPosition.coerceAtLeast(0L)}"
                    )
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val readyTrack = _state.value.currentTrack
                    if (readyTrack != null && !readyTrack.isDownloaded && readyTrack.youtubeVideoId != null) {
                        hasHandedUriForCurrentAttempt = true
                    }
                    if (controller.currentPosition > 1_000L) {
                        rapidAutoSkipCount = 0
                    }
                }
                _state.update {
                    val resolvingActive = (isResolvingRemoteTrack || resolvePlayJob?.isActive == true) &&
                        it.playbackError == null
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
                // Clearing the player before loading a newly selected track emits
                // onMediaItemTransition(null, PLAYLIST_CHANGED) with currentMediaItemIndex = 0.
                // Never fall back to queue[0] or overwrite the pending track when nothing is loaded.
                if (mediaItem == null || controller.mediaItemCount == 0) {
                    return
                }
                val pendingTrackId = awaitingMedia3TrackId
                if (pendingTrackId != null) {
                    if (mediaItem.mediaId != pendingTrackId) {
                        return
                    }
                    awaitingMedia3TrackId = null
                }
                if ((isResolvingRemoteTrack || resolvePlayJob?.isActive == true) &&
                    !hasHandedUriForCurrentAttempt &&
                    mediaItem.mediaId != _state.value.currentTrack?.id
                ) {
                    return
                }
                val isRetryReplacementTransition =
                    mediaItem.mediaId == replacingActiveTrackId &&
                        (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                            reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                    mediaItem.mediaId != replacingActiveTrackId
                ) {
                    replacingActiveTrackId = null
                }
                val isSameTrackPlaylistUpdate =
                    mediaItem.mediaId == lastTrackId &&
                        (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ||
                            isRetryReplacementTransition)
                if (lastTrackId != null && lastTrackId != mediaItem.mediaId) {
                    recordTrackSignal(completed = false)
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        val elapsedOnPrev = System.currentTimeMillis() - trackPlayStartedMs
                        if (elapsedOnPrev < 1_200L && maxPositionReachedMs < 500L) {
                            rapidAutoSkipCount++
                        } else {
                            rapidAutoSkipCount = 0
                        }
                        if (rapidAutoSkipCount >= 2) {
                            rapidAutoSkipCount = 0
                            resolvePlayJob?.cancel()
                            resolvePlayJob = null
                            isResolvingRemoteTrack = false
                            awaitingMedia3TrackId = null
                            playRequestGeneration.incrementAndGet()
                            controller.pause()
                            controller.stop()
                            val queueSnapshot = _state.value.queue
                            val matchedStopIdx = queueSnapshot.indexOfFirst { it.id == mediaItem.mediaId }
                            val stopIdx = if (matchedStopIdx >= 0) {
                                matchedStopIdx
                            } else {
                                controller.currentMediaItemIndex.coerceAtLeast(0)
                            }
                            val stopTrack = if (matchedStopIdx >= 0) {
                                queueSnapshot[matchedStopIdx]
                            } else {
                                PulseMediaItemMapper.fromMediaItem(mediaItem) ?: queueSnapshot.getOrNull(stopIdx)
                            }
                            lastTrackId = stopTrack?.id
                            _state.update {
                                it.copy(
                                    currentTrack = stopTrack ?: it.currentTrack,
                                    currentIndex = stopIdx,
                                    isPlaying = false,
                                    isBuffering = false,
                                    positionMs = 0L,
                                    playbackError = "Playback stopped after consecutive unplayable tracks. Tap Play to retry or pick another song."
                                )
                            }
                            return
                        }
                    } else if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                        rapidAutoSkipCount = 0
                    }
                }
                val queue = _state.value.queue
                val matchedIdx = queue.indexOfFirst { it.id == mediaItem.mediaId }
                val idx = if (matchedIdx >= 0) {
                    matchedIdx
                } else {
                    controller.currentMediaItemIndex.coerceAtLeast(0)
                }
                val nextTrack = if (matchedIdx >= 0) {
                    queue[matchedIdx]
                } else {
                    PulseMediaItemMapper.fromMediaItem(mediaItem) ?: queue.getOrNull(idx)
                }
                if (nextTrack != null) {
                    val itemUriScheme = mediaItem.localConfiguration?.uri?.scheme
                    val videoId = nextTrack.youtubeVideoId
                    val isRemoteYouTubeItem = !nextTrack.isDownloaded && videoId != null
                    if (!isSameTrackPlaylistUpdate) {
                        resolvePlayJob?.cancel()
                        resolvePlayJob = null
                        isResolvingRemoteTrack = false
                        playRequestGeneration.incrementAndGet()
                        lastTrackId = nextTrack.id
                        lastRetriedYouTubeId = null
                        youtubeRetryCount = 0
                        trackPlayStartedMs = System.currentTimeMillis()
                        maxPositionReachedMs = 0L
                        seekBackCounter = 0
                        triggerFadeInIfEnabled(controller)
                        if (isRemoteYouTubeItem) {
                            if (itemUriScheme == Track.YOUTUBE_URI_SCHEME) {
                                activeCorrelationId = "pb-${playbackAttemptSeq.incrementAndGet()}"
                                hasHandedUriForCurrentAttempt =
                                    controller.playbackState == Player.STATE_READY
                                Log.i(
                                    "PULSE_YT_RESOLVER",
                                    "stage_event corr=$activeCorrelationId stage=MEDIA3_QUEUE_TRANSITION trackId=${nextTrack.id} videoId=$videoId index=$idx reason=${transitionReasonName(reason)} hasResolvedUri=false delegate=PULSE_MEDIA_SOURCE_FACTORY"
                                )
                            } else {
                                hasHandedUriForCurrentAttempt = true
                                Log.i(
                                    "PULSE_YT_RESOLVER",
                                    "stage_event corr=$activeCorrelationId stage=MEDIA3_QUEUE_TRANSITION trackId=${nextTrack.id} videoId=$videoId index=$idx reason=${transitionReasonName(reason)} hasResolvedUri=true host=${YouTubeMusicClient.redactHostOnly(mediaItem.localConfiguration?.uri?.toString())}"
                                )
                            }
                        }
                    } else if (isRemoteYouTubeItem && itemUriScheme != Track.YOUTUBE_URI_SCHEME) {
                        hasHandedUriForCurrentAttempt = true
                    }
                    _state.update {
                        it.copy(
                            currentTrack = nextTrack,
                            currentIndex = idx,
                            isBuffering = if (isSameTrackPlaylistUpdate) {
                                it.isBuffering || controller.playbackState == Player.STATE_BUFFERING
                            } else {
                                controller.playbackState == Player.STATE_BUFFERING ||
                                    (isRemoteYouTubeItem &&
                                        itemUriScheme == Track.YOUTUBE_URI_SCHEME &&
                                        controller.playbackState != Player.STATE_READY)
                            },
                            durationMs = if (isSameTrackPlaylistUpdate && it.durationMs > 0L) {
                                it.durationMs
                            } else {
                                nextTrack.durationMs
                            },
                            playbackError = if (isSameTrackPlaylistUpdate) it.playbackError else null
                        )
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if ((isResolvingRemoteTrack || resolvePlayJob?.isActive == true || awaitingMedia3TrackId != null) &&
                    !hasHandedUriForCurrentAttempt
                ) {
                    return
                }
                val rootCause = generateSequence(error as Throwable?) { it.cause }.lastOrNull()
                val rawRootMessage = rootCause?.message
                    ?.takeIf { it.isNotBlank() }
                    ?: error.localizedMessage
                    ?: "Audio stream unavailable"

                val isYouTubeTrack = _state.value.currentTrack?.youtubeVideoId != null
                val formattedPlayerError = YouTubeMusicClient.humanReadablePlaybackError(
                    error = rootCause ?: error,
                    isYouTubeTrack = isYouTubeTrack
                )

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
                            val targetIdx = controller.currentMediaItemIndex.takeIf {
                                it in 0 until controller.mediaItemCount &&
                                    controller.getMediaItemAt(it).mediaId == activeTrack.id
                            } ?: (0 until controller.mediaItemCount).firstOrNull {
                                controller.getMediaItemAt(it).mediaId == activeTrack.id
                            }
                            if (stream != null && targetIdx != null) {
                                val resolvedMime = if (stream.isHls) MimeTypes.APPLICATION_M3U8 else null
                                hasHandedUriForCurrentAttempt = true
                                Log.i(
                                    "PULSE_YT_RESOLVER",
                                    "stage_event corr=$retryCorrId stage=MEDIA3_HANDOFF source=PLAYER_ERROR_RETRY videoId=$activeVideoId startTsMs=$retryStartTsMs endTsMs=$retryEndTsMs durationMs=$retryElapsedMs outcome=SUCCESS isHls=${stream.isHls} format=${resolvedMime ?: stream.mimeType} host=${YouTubeMusicClient.redactHostOnly(stream.url)} queueIndex=$targetIdx queueSize=${controller.mediaItemCount}"
                                )
                                youtubeClient.registerResolvedStreamHeaders(stream)
                                val replacement = activeTrack.toMediaItemOrNull(
                                    overrideUri = stream.url,
                                    mimeType = resolvedMime
                                )
                                if (replacement != null) {
                                    replaceQueueItemPreservingShuffle(
                                        controller = controller,
                                        targetIdx = targetIdx,
                                        replacement = replacement,
                                        seekPosMs = if (resumePos > 1_500L) resumePos else 0L,
                                        resumePlayback = true
                                    )
                                    return@launch
                                }
                            }
                            val rawRetryErr = resolved.exceptionOrNull()
                            val errMsg = if (rawRetryErr != null) {
                                YouTubeMusicClient.humanReadablePlaybackError(
                                    error = rawRetryErr,
                                    isYouTubeTrack = true
                                )
                            } else {
                                formattedPlayerError
                            }
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
                val resolving = isResolvingRemoteTrack || resolvePlayJob?.isActive == true
                val fadingOut = isFadingOutToPause
                val rawPlaying = !hasError && !resolving && controller?.isPlaying == true
                val playing = rawPlaying && !fadingOut
                if (controller != null && !hasError && !resolving) {
                    val pos = controller.currentPosition.coerceAtLeast(0L)
                    if (pos > maxPositionReachedMs) maxPositionReachedMs = pos
                    if (pos > 1_200L) rapidAutoSkipCount = 0
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

                    if (!fadingOut) {
                        applyCrossfadeEnvelope(controller, playing, pos, dur)
                    }

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
                val stepDelay = when {
                    playing && fadeInDurationMs > 0L -> 20L
                    playing -> 50L
                    else -> 180L
                }
                delay(stepDelay)
            }
        }
    }

    private fun triggerFadeInIfEnabled(controller: Player?) {
        val crossfadeMs = _state.value.crossfadeSeconds * 1000L
        if (crossfadeMs > 0L) {
            fadeInDurationMs = (crossfadeMs / 2L).coerceIn(400L, 6000L)
            fadeInStartedAtMs = SystemClock.elapsedRealtime()
            controller?.volume = 0.04f
        } else {
            fadeInDurationMs = 0L
            controller?.volume = 1f
        }
    }

    private fun triggerResumeFadeIn(controller: Player) {
        val currentVol = controller.volume
        if (currentVol in 0.05f..0.95f) {
            val resumeFraction = (asin(currentVol.toDouble().coerceIn(0.05, 0.98)) / (Math.PI / 2.0)).toFloat()
            fadeInDurationMs = SMART_RESUME_FADE_IN_MS
            fadeInStartedAtMs = SystemClock.elapsedRealtime() - (resumeFraction * SMART_RESUME_FADE_IN_MS).toLong()
        } else {
            fadeInDurationMs = SMART_RESUME_FADE_IN_MS
            fadeInStartedAtMs = SystemClock.elapsedRealtime()
            controller.volume = 0.08f
        }
    }

    private fun applyCrossfadeEnvelope(
        controller: Player,
        playing: Boolean,
        positionMs: Long,
        durationMs: Long
    ) {
        if (isFadingOutToPause) return

        val sleepRemainingMs = sleepTimerEndRealtimeMs?.let { (it - SystemClock.elapsedRealtime()).coerceAtLeast(0L) }
        val sleepFadeFactor = if (playing && sleepRemainingMs != null && sleepRemainingMs <= 3_000L) {
            (sleepRemainingMs.toFloat() / 3_000f).coerceIn(0.06f, 1f)
        } else {
            1f
        }

        if (!playing) {
            if (kotlin.math.abs(controller.volume - 1f) > 0.01f) {
                controller.volume = 1f
            }
            return
        }

        val fadeInFactor = if (fadeInDurationMs > 0L) {
            val elapsed = (SystemClock.elapsedRealtime() - fadeInStartedAtMs).coerceAtLeast(0L)
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

        val crossfadeTotalMs = _state.value.crossfadeSeconds * 1000L
        val fadeOutFactor = if (
            crossfadeTotalMs > 0L &&
            durationMs > 5_000L &&
            controller.hasNextMediaItem()
        ) {
            val fadeOutWindowMs = (crossfadeTotalMs / 2L)
                .coerceAtMost((durationMs * 0.35f).toLong().coerceAtLeast(500L))
            val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
            if (remainingMs <= fadeOutWindowMs) {
                val tOut = (remainingMs.toFloat() / fadeOutWindowMs.toFloat()).coerceIn(0f, 1f)
                sin(tOut * (Math.PI / 2.0)).toFloat().coerceAtLeast(0.04f)
            } else {
                1f
            }
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
        if (clamped == 0 && !isFadingOutToPause) {
            fadeInDurationMs = 0L
            runOnPlayerThread {
                mediaController?.volume = 1f
            }
        }
    }

    fun setSmartFadeOutEnabled(enabled: Boolean) {
        _state.update { it.copy(smartFadeOutEnabled = enabled) }
        if (!enabled && isFadingOutToPause) {
            runOnPlayerThread {
                cancelPauseFadeOut(restoreVolume = false)
                val controller = mediaController ?: return@runOnPlayerThread
                if (controller.isPlaying) {
                    controller.pause()
                }
                controller.volume = 1f
            }
        }
    }

    private fun cancelPauseFadeOut(restoreVolume: Boolean) {
        pauseFadeJob?.cancel()
        pauseFadeJob = null
        isFadingOutToPause = false
        pauseRequestedBySmartFade = false
        if (restoreVolume) {
            mediaController?.volume = 1f
        }
    }

    private fun performSmartPauseFadeOut(controller: Player) {
        pauseFadeJob?.cancel()
        isFadingOutToPause = true
        pauseRequestedBySmartFade = false
        fadeInDurationMs = 0L
        AudioSpectrumBus.setPlaybackActive(false)
        _state.update { it.copy(isPlaying = false) }
        val startVolume = controller.volume.coerceIn(0.05f, 1f)
        val startedAt = SystemClock.elapsedRealtime()
        lateinit var job: Job
        job = scope.launch {
            try {
                while (isActive) {
                    val activeController = mediaController
                    if (activeController == null || activeController !== controller || !activeController.isPlaying) {
                        break
                    }
                    val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                    val t = (elapsed.toFloat() / SMART_PAUSE_FADE_OUT_MS.toFloat()).coerceIn(0f, 1f)
                    val envelope = cos(t * (Math.PI / 2.0)).toFloat().coerceIn(0f, 1f)
                    val perceptualCurve = envelope * envelope
                    activeController.volume = (startVolume * perceptualCurve).coerceIn(0f, 1f)
                    if (t >= 1f) {
                        break
                    }
                    delay(16L)
                }
                val activeController = mediaController
                if (activeController != null && activeController === controller) {
                    activeController.volume = 0f
                    if (activeController.isPlaying) {
                        pauseRequestedBySmartFade = true
                        activeController.pause()
                    }
                    delay(25L)
                    if (isActive && isFadingOutToPause) {
                        activeController.volume = 1f
                    }
                }
            } finally {
                if (pauseFadeJob === job) {
                    isFadingOutToPause = false
                    pauseRequestedBySmartFade = false
                    pauseFadeJob = null
                }
            }
        }
        pauseFadeJob = job
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
        runOnPlayerThread {
            mediaController?.volume = 1f
        }
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
        cancelPauseFadeOut(restoreVolume = false)
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
        rapidAutoSkipCount = 0
        trackPlayStartedMs = System.currentTimeMillis()
        maxPositionReachedMs = 0L
        seekBackCounter = 0

        val needsRemoteResolution = track.needsRemoteStreamResolution()

        isResolvingRemoteTrack = needsRemoteResolution
        awaitingMedia3TrackId = track.id
        replacingActiveTrackId = null

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

        runOnPlayerThread {
            if (requestGeneration != playRequestGeneration.get()) return@runOnPlayerThread
            val controller = mediaController
            if (controller == null) {
                pendingPlayRequest = PendingPlayRequest(track, safeQueue, forceRefresh)
                connectController()
                return@runOnPlayerThread
            }

            if (!needsRemoteResolution) {
                val resolvedPairs = safeQueue.mapNotNull { item ->
                    item.toMediaItemOrNull()?.let { item to it }
                }
                val playableQueue = resolvedPairs.map { it.first }
                val mediaItems = resolvedPairs.map { it.second }
                val safeIdx = playableQueue.indexOfFirst { it.id == track.id }
                if (mediaItems.isEmpty() || safeIdx < 0) {
                    isResolvingRemoteTrack = false
                    awaitingMedia3TrackId = null
                    controller.pause()
                    controller.stop()
                    controller.clearMediaItems()
                    _state.update {
                        it.copy(isPlaying = false, isBuffering = false, positionMs = 0L, playbackError = "No playable audio URI for this track")
                    }
                    return@runOnPlayerThread
                }
                if (playableQueue.size != safeQueue.size) {
                    _state.update { it.copy(queue = playableQueue, currentIndex = safeIdx) }
                }
                triggerFadeInIfEnabled(controller)
                controller.setMediaItems(mediaItems, safeIdx, 0L)
                awaitingMedia3TrackId = null
                controller.prepare()
                controller.play()
                return@runOnPlayerThread
            }

            // Stop any previous track so prior audio/position does not bleed into a resolving or failed track
            controller.pause()
            controller.stop()
            controller.clearMediaItems()
        }

        if (!needsRemoteResolution) return

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
                    val errMsg = YouTubeMusicClient.humanReadablePlaybackError(
                        error = rawErr,
                        isYouTubeTrack = true
                    )
                    val timedOut = rawErr is SocketTimeoutException || rawErr?.cause is SocketTimeoutException
                    val httpStatus = YouTubeMusicClient.extractHttpStatus(rawErr)
                    val origin = YouTubeMusicClient.classifyFailureOrigin(rawErr, timedOut = timedOut)
                    Log.w(
                        "PULSE_YT_RESOLVER",
                        "stage_event corr=$corrId stage=PLAYBACK_REQUEST trackId=${track.id} videoId=$videoId startTsMs=$reqStartTsMs endTsMs=$endTsMs durationMs=$elapsedMs outcome=${if (timedOut) "TIMEOUT" else "FAILURE"} errorClass=${rawErr?.javaClass?.simpleName ?: "None"} rootCauseClass=${rawErr?.cause?.javaClass?.simpleName ?: "None"} httpStatus=$httpStatus usableStreamReturned=false isHls=false format=none cancelled=false timedOut=$timedOut failureOrigin=$origin phase=BEFORE_MEDIA3_HANDOFF msg=${YouTubeMusicClient.redactSensitiveUrls(errMsg)}"
                    )
                    isResolvingRemoteTrack = false
                    awaitingMedia3TrackId = null
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
                val resolvedPairs = safeQueue.mapIndexedNotNull { idx, item ->
                    val mi = if (idx == targetIndex) {
                        item.toMediaItemOrNull(
                            overrideUri = stream.url,
                            mimeType = resolvedMime
                        )
                    } else {
                        item.toMediaItemOrNull()
                    }
                    mi?.let { item to it }
                }
                val playableQueue = resolvedPairs.map { it.first }
                val mediaItems = resolvedPairs.map { it.second }
                val safeIdx = playableQueue.indexOfFirst { it.id == track.id }
                if (mediaItems.isEmpty() || safeIdx < 0) {
                    Log.w(
                        "PULSE_YT_RESOLVER",
                        "stage_event corr=$corrId stage=PLAYBACK_REQUEST trackId=${track.id} videoId=$videoId startTsMs=$reqStartTsMs endTsMs=$endTsMs durationMs=$elapsedMs outcome=FAILURE errorClass=IllegalStateException httpStatus=0 usableStreamReturned=false isHls=${stream.isHls} format=${resolvedMime ?: stream.mimeType} cancelled=false timedOut=false failureOrigin=LOCAL_APP phase=BEFORE_MEDIA3_HANDOFF msg=empty_media_items"
                    )
                    isResolvingRemoteTrack = false
                    awaitingMedia3TrackId = null
                    _state.update {
                        it.copy(isPlaying = false, isBuffering = false, positionMs = 0L, playbackError = "No playable audio URI for this track")
                    }
                    return@launch
                }
                val controller = mediaController
                if (controller == null) {
                    isResolvingRemoteTrack = false
                    pendingPlayRequest = PendingPlayRequest(track, safeQueue, forceRefresh = false)
                    connectController()
                    return@launch
                }
                isResolvingRemoteTrack = false
                hasHandedUriForCurrentAttempt = true
                if (playableQueue.size != safeQueue.size) {
                    _state.update { it.copy(queue = playableQueue, currentIndex = safeIdx) }
                }
                Log.i(
                    "PULSE_YT_RESOLVER",
                    "stage_event corr=$corrId stage=MEDIA3_HANDOFF source=PLAY_TRACK trackId=${track.id} videoId=$videoId startTsMs=$reqStartTsMs endTsMs=$endTsMs durationMs=$elapsedMs outcome=SUCCESS isHls=${stream.isHls} format=${resolvedMime ?: stream.mimeType} host=${YouTubeMusicClient.redactHostOnly(stream.url)} queueIndex=$safeIdx queueSize=${mediaItems.size}"
                )
                runOnPlayerThread {
                    if (requestGeneration != playRequestGeneration.get()) return@runOnPlayerThread
                    triggerFadeInIfEnabled(controller)
                    controller.setMediaItems(mediaItems, safeIdx, 0L)
                    awaitingMedia3TrackId = null
                    controller.prepare()
                    controller.play()
                }
            } catch (e: CancellationException) {
                val endTsMs = System.currentTimeMillis()
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                Log.i(
                    "PULSE_YT_RESOLVER",
                    "stage_event corr=$corrId stage=PLAYBACK_REQUEST trackId=${track.id} videoId=$videoId startTsMs=$reqStartTsMs endTsMs=$endTsMs durationMs=$elapsedMs outcome=CANCELLED errorClass=CancellationException rootCauseClass=${e.cause?.javaClass?.simpleName ?: "None"} httpStatus=0 usableStreamReturned=false isHls=false format=none cancelled=true timedOut=false failureOrigin=LOCAL_APP phase=BEFORE_MEDIA3_HANDOFF"
                )
                if (requestGeneration == playRequestGeneration.get()) {
                    isResolvingRemoteTrack = false
                    awaitingMedia3TrackId = null
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
                if (requestGeneration == playRequestGeneration.get()) {
                    isResolvingRemoteTrack = false
                    if (!hasHandedUriForCurrentAttempt) {
                        awaitingMedia3TrackId = null
                        if (_state.value.isBuffering) {
                            _state.update { it.copy(isBuffering = false) }
                        }
                    }
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
        if (isResolvingRemoteTrack || resolvePlayJob?.isActive == true) {
            return
        }
        runOnPlayerThread {
            val controller = mediaController ?: return@runOnPlayerThread
            if (controller.mediaItemCount == 0 && _state.value.currentTrack != null) {
                retryActiveTrack()
                return@runOnPlayerThread
            }
            if (isFadingOutToPause) {
                cancelPauseFadeOut(restoreVolume = false)
                triggerResumeFadeIn(controller)
                if (!controller.isPlaying) {
                    controller.play()
                }
                _state.update { it.copy(isPlaying = true) }
                return@runOnPlayerThread
            }
            if (controller.isPlaying) {
                if (_state.value.smartFadeOutEnabled) {
                    performSmartPauseFadeOut(controller)
                } else {
                    controller.volume = 1f
                    controller.pause()
                }
            } else {
                if (_state.value.smartFadeOutEnabled) {
                    triggerResumeFadeIn(controller)
                } else {
                    controller.volume = 1f
                }
                controller.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val safePos = positionMs.coerceAtLeast(0L)
        _state.update { it.copy(positionMs = safePos) }
        runOnPlayerThread {
            val controller = mediaController ?: return@runOnPlayerThread
            if (safePos < controller.currentPosition - 3_000L) {
                seekBackCounter++
            }
            controller.seekTo(safePos)
        }
    }

    private fun Track.needsRemoteStreamResolution(): Boolean =
        !isDownloaded &&
            playableUri.startsWith(Track.YOUTUBE_URI_SCHEME) &&
            youtubeVideoId != null

    fun skipNext() {
        skipByStep(direction = 1)
    }

    fun skipPrevious() {
        skipByStep(direction = -1)
    }

    private fun skipByStep(direction: Int) {
        runOnPlayerThread {
            cancelPauseFadeOut(restoreVolume = true)
            val controller = mediaController
            if (direction < 0 &&
                controller != null &&
                !isResolvingRemoteTrack &&
                controller.currentPosition > 4_000L
            ) {
                seekBackCounter++
                controller.seekTo(0L)
                return@runOnPlayerThread
            }

            awaitingMedia3TrackId = null
            replacingActiveTrackId = null
            resolvePlayJob?.cancel()
            resolvePlayJob = null
            isResolvingRemoteTrack = false
            rapidAutoSkipCount = 0
            val requestGeneration = playRequestGeneration.incrementAndGet()

            if (controller == null || controller.mediaItemCount == 0) {
                val s = _state.value
                val targetIdx = computeQueueFallbackIndex(
                    queue = s.queue,
                    currentIndex = s.currentIndex,
                    direction = direction,
                    repeatMode = s.repeatMode,
                    shuffleEnabled = s.shuffleEnabled
                )
                if (targetIdx != null) {
                    playTrack(s.queue[targetIdx], s.queue)
                }
                return@runOnPlayerThread
            }

            val itemCount = controller.mediaItemCount
            val currentStateTrackId = _state.value.currentTrack?.id
            val rawControllerIdx = controller.currentMediaItemIndex
            val anchorIdx = if (currentStateTrackId != null &&
                rawControllerIdx in 0 until itemCount &&
                controller.getMediaItemAt(rawControllerIdx).mediaId == currentStateTrackId
            ) {
                rawControllerIdx
            } else if (currentStateTrackId != null) {
                (0 until itemCount).firstOrNull {
                    controller.getMediaItemAt(it).mediaId == currentStateTrackId
                } ?: rawControllerIdx
            } else {
                rawControllerIdx
            }.coerceIn(0, itemCount - 1)

            val timeline = controller.currentTimeline
            val effectiveRepeat = if (controller.repeatMode == Player.REPEAT_MODE_ONE) {
                Player.REPEAT_MODE_OFF
            } else {
                controller.repeatMode
            }
            val targetControllerIdx = if (!timeline.isEmpty && timeline.windowCount == itemCount) {
                if (direction > 0) {
                    timeline.getNextWindowIndex(anchorIdx, effectiveRepeat, controller.shuffleModeEnabled)
                } else {
                    timeline.getPreviousWindowIndex(anchorIdx, effectiveRepeat, controller.shuffleModeEnabled)
                }
            } else {
                val rawTarget = anchorIdx + direction
                when {
                    rawTarget in 0 until itemCount -> rawTarget
                    effectiveRepeat == Player.REPEAT_MODE_ALL && itemCount > 0 ->
                        if (direction > 0) 0 else itemCount - 1
                    else -> C.INDEX_UNSET
                }
            }
            if (targetControllerIdx == C.INDEX_UNSET || targetControllerIdx !in 0 until itemCount) {
                return@runOnPlayerThread
            }

            val targetMediaItem = controller.getMediaItemAt(targetControllerIdx)
            val queueSnapshot = _state.value.queue
            val matchedQueueIdx = queueSnapshot.indexOfFirst { it.id == targetMediaItem.mediaId }
            val targetTrack = if (matchedQueueIdx >= 0) {
                queueSnapshot[matchedQueueIdx]
            } else {
                PulseMediaItemMapper.fromMediaItem(targetMediaItem)
            }
            val needsRemoteUpfront = targetTrack != null &&
                targetTrack.needsRemoteStreamResolution() &&
                targetMediaItem.localConfiguration?.uri?.scheme == Track.YOUTUBE_URI_SCHEME

            if (!needsRemoteUpfront) {
                val recoverFromError = controller.playerError != null ||
                    controller.playbackState == Player.STATE_IDLE ||
                    !controller.playWhenReady
                if (anchorIdx == controller.currentMediaItemIndex) {
                    if (direction > 0 && controller.hasNextMediaItem()) {
                        controller.seekToNextMediaItem()
                    } else if (direction < 0 && controller.hasPreviousMediaItem()) {
                        controller.seekToPreviousMediaItem()
                    } else {
                        controller.seekTo(targetControllerIdx, 0L)
                    }
                } else {
                    controller.seekTo(targetControllerIdx, 0L)
                }
                if (recoverFromError) {
                    controller.prepare()
                    controller.play()
                }
                return@runOnPlayerThread
            }

            val remoteTrack = targetTrack!!
            val videoId = remoteTrack.youtubeVideoId!!
            val resolvedQueueIdx = if (matchedQueueIdx >= 0) matchedQueueIdx else targetControllerIdx

            if (lastTrackId != null && lastTrackId != remoteTrack.id) {
                recordTrackSignal(completed = false)
            }
            lastTrackId = remoteTrack.id
            lastRetriedYouTubeId = null
            youtubeRetryCount = 0
            trackPlayStartedMs = System.currentTimeMillis()
            maxPositionReachedMs = 0L
            seekBackCounter = 0
            hasHandedUriForCurrentAttempt = false
            isResolvingRemoteTrack = true
            awaitingMedia3TrackId = remoteTrack.id

            _state.update {
                it.copy(
                    currentTrack = remoteTrack,
                    currentIndex = resolvedQueueIdx,
                    positionMs = 0L,
                    durationMs = remoteTrack.durationMs,
                    isPlaying = false,
                    isBuffering = true,
                    beatPulseEnergy = 0.14f,
                    beatPhase = 0f,
                    playbackError = null
                )
            }

            // Pause and stop active audio cleanly without clearing queue / shuffle order
            controller.pause()
            controller.stop()

            val corrId = "pb-${playbackAttemptSeq.incrementAndGet()}"
            activeCorrelationId = corrId
            val reqStartTsMs = System.currentTimeMillis()
            resolvePlayJob = scope.launch {
                val startNs = System.nanoTime()
                try {
                    val resolved = withContext(Dispatchers.IO) {
                        youtubeClient.ensureAuthSessionHydrated(context)
                        youtubeClient.resolveAudioStream(
                            videoId = videoId,
                            forceRefresh = false,
                            correlationId = corrId
                        )
                    }
                    val endTsMs = System.currentTimeMillis()
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                    if (!isActive ||
                        requestGeneration != playRequestGeneration.get() ||
                        _state.value.currentTrack?.id != remoteTrack.id
                    ) {
                        return@launch
                    }

                    val stream = resolved.getOrNull()
                    if (stream == null) {
                        val rawErr = resolved.exceptionOrNull()
                        val errMsg = YouTubeMusicClient.humanReadablePlaybackError(
                            error = rawErr,
                            isYouTubeTrack = true
                        )
                        Log.w(
                            "PULSE_YT_RESOLVER",
                            "stage_event corr=$corrId stage=SKIP_RESOLVE trackId=${remoteTrack.id} videoId=$videoId startTsMs=$reqStartTsMs endTsMs=$endTsMs durationMs=$elapsedMs outcome=FAILURE errorClass=${rawErr?.javaClass?.simpleName ?: "None"} msg=${YouTubeMusicClient.redactSensitiveUrls(errMsg)}"
                        )
                        isResolvingRemoteTrack = false
                        awaitingMedia3TrackId = null
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
                    val replacement = remoteTrack.toMediaItemOrNull(
                        overrideUri = stream.url,
                        mimeType = resolvedMime
                    )
                    if (replacement == null) {
                        isResolvingRemoteTrack = false
                        awaitingMedia3TrackId = null
                        _state.update {
                            it.copy(
                                isPlaying = false,
                                isBuffering = false,
                                positionMs = 0L,
                                playbackError = "No playable audio URI for this track"
                            )
                        }
                        return@launch
                    }

                    val activeController = mediaController
                    if (activeController == null) {
                        isResolvingRemoteTrack = false
                        pendingPlayRequest = PendingPlayRequest(remoteTrack, _state.value.queue.ifEmpty { listOf(remoteTrack) }, forceRefresh = false)
                        connectController()
                        return@launch
                    }

                    Log.i(
                        "PULSE_YT_RESOLVER",
                        "stage_event corr=$corrId stage=MEDIA3_HANDOFF source=SKIP_STEP trackId=${remoteTrack.id} videoId=$videoId startTsMs=$reqStartTsMs endTsMs=$endTsMs durationMs=$elapsedMs outcome=SUCCESS isHls=${stream.isHls} format=${resolvedMime ?: stream.mimeType} host=${YouTubeMusicClient.redactHostOnly(stream.url)} queueIndex=$resolvedQueueIdx queueSize=${activeController.mediaItemCount}"
                    )

                    runOnPlayerThread {
                        if (requestGeneration != playRequestGeneration.get()) return@runOnPlayerThread
                        val liveCount = activeController.mediaItemCount
                        val liveIdx = if (targetControllerIdx in 0 until liveCount &&
                            activeController.getMediaItemAt(targetControllerIdx).mediaId == remoteTrack.id
                        ) {
                            targetControllerIdx
                        } else {
                            (0 until liveCount).firstOrNull {
                                activeController.getMediaItemAt(it).mediaId == remoteTrack.id
                            }
                        }
                        if (liveIdx == null) {
                            isResolvingRemoteTrack = false
                            awaitingMedia3TrackId = null
                            playTrack(remoteTrack, _state.value.queue.ifEmpty { listOf(remoteTrack) })
                            return@runOnPlayerThread
                        }
                        isResolvingRemoteTrack = false
                        hasHandedUriForCurrentAttempt = true
                        triggerFadeInIfEnabled(activeController)
                        if (liveIdx == activeController.currentMediaItemIndex) {
                            replaceQueueItemPreservingShuffle(
                                controller = activeController,
                                targetIdx = liveIdx,
                                replacement = replacement,
                                seekPosMs = 0L,
                                resumePlayback = true
                            )
                        } else {
                            activeController.replaceMediaItem(liveIdx, replacement)
                            activeController.seekTo(liveIdx, 0L)
                            activeController.prepare()
                            activeController.play()
                        }
                        awaitingMedia3TrackId = null
                    }
                } catch (e: CancellationException) {
                    if (requestGeneration == playRequestGeneration.get()) {
                        isResolvingRemoteTrack = false
                        awaitingMedia3TrackId = null
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
                    if (requestGeneration == playRequestGeneration.get()) {
                        isResolvingRemoteTrack = false
                        if (!hasHandedUriForCurrentAttempt) {
                            awaitingMedia3TrackId = null
                            if (_state.value.isBuffering) {
                                _state.update { it.copy(isBuffering = false) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun computeQueueFallbackIndex(
        queue: List<Track>,
        currentIndex: Int,
        direction: Int,
        repeatMode: Int,
        shuffleEnabled: Boolean
    ): Int? {
        if (queue.isEmpty()) return null
        val safeCur = currentIndex.coerceIn(0, queue.lastIndex)
        if (shuffleEnabled && queue.size > 1) {
            val candidates = queue.indices.filter { it != safeCur }
            return candidates.random()
        }
        val raw = safeCur + direction
        return when {
            raw in queue.indices -> raw
            repeatMode == Player.REPEAT_MODE_ALL -> if (direction > 0) 0 else queue.lastIndex
            else -> null
        }
    }

    private fun replaceQueueItemPreservingShuffle(
        controller: Player,
        targetIdx: Int,
        replacement: MediaItem,
        seekPosMs: Long,
        resumePlayback: Boolean
    ) {
        if (targetIdx !in 0 until controller.mediaItemCount) return
        val safePos = seekPosMs.coerceAtLeast(0L)
        if (targetIdx == controller.currentMediaItemIndex) {
            replacingActiveTrackId = replacement.mediaId
            val insertIdx = (targetIdx + 1).coerceAtMost(controller.mediaItemCount)
            controller.addMediaItem(insertIdx, replacement)
            controller.seekTo(insertIdx, safePos)
            controller.removeMediaItem(targetIdx)
            if (resumePlayback) {
                controller.prepare()
                controller.play()
            }
        } else {
            controller.replaceMediaItem(targetIdx, replacement)
        }
    }

    fun removeFromQueue(index: Int) {
        runOnPlayerThread {
            val currentState = _state.value
            val currentQueue = currentState.queue
            if (index !in currentQueue.indices) return@runOnPlayerThread

            val updatedQueue = currentQueue.toMutableList().apply { removeAt(index) }
            val removingActive = index == currentState.currentIndex ||
                (currentState.currentIndex !in currentQueue.indices &&
                    currentState.currentTrack?.id == currentQueue[index].id)
            val controller = mediaController

            if (updatedQueue.isEmpty()) {
                playRequestGeneration.incrementAndGet()
                resolvePlayJob?.cancel()
                resolvePlayJob = null
                isResolvingRemoteTrack = false
                awaitingMedia3TrackId = null
                replacingActiveTrackId = null
                pendingPlayRequest = null
                lastTrackId = null
                controller?.pause()
                controller?.stop()
                controller?.clearMediaItems()
                _state.update {
                    it.copy(
                        currentTrack = null,
                        queue = emptyList(),
                        currentIndex = -1,
                        isPlaying = false,
                        isBuffering = false,
                        positionMs = 0L,
                        durationMs = 0L,
                        playbackError = null
                    )
                }
                return@runOnPlayerThread
            }

            if (removingActive) {
                val nextIdx = index.coerceAtMost(updatedQueue.lastIndex)
                val nextTrack = updatedQueue[nextIdx]
                playTrack(nextTrack, updatedQueue)
            } else {
                val newCurrentIndex = if (index < currentState.currentIndex) {
                    (currentState.currentIndex - 1).coerceAtLeast(0)
                } else {
                    currentState.currentIndex
                }
                if (controller != null && index in 0 until controller.mediaItemCount) {
                    if (controller.mediaItemCount == currentQueue.size) {
                        controller.removeMediaItem(index)
                    } else if (controller.mediaItemCount > 1) {
                        val mappedId = currentQueue[index].id
                        val mediaIdx = (0 until controller.mediaItemCount).firstOrNull {
                            controller.getMediaItemAt(it).mediaId == mappedId
                        }
                        if (mediaIdx != null && mediaIdx != controller.currentMediaItemIndex) {
                            controller.removeMediaItem(mediaIdx)
                        }
                    }
                }
                _state.update {
                    it.copy(
                        queue = updatedQueue,
                        currentIndex = newCurrentIndex
                    )
                }
            }
        }
    }

    fun toggleShuffle() {
        runOnPlayerThread {
            val controller = mediaController ?: return@runOnPlayerThread
            val next = !controller.shuffleModeEnabled
            controller.shuffleModeEnabled = next
            _state.update { it.copy(shuffleEnabled = next) }
        }
    }

    fun cycleRepeatMode() {
        runOnPlayerThread {
            val controller = mediaController ?: return@runOnPlayerThread
            val next = when (controller.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            controller.repeatMode = next
            _state.update { it.copy(repeatMode = next) }
        }
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
        runOnPlayerThread {
            val controller = mediaController as? MediaController ?: return@runOnPlayerThread
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
    }

    fun updateTrackMetadata(updated: Track) {
        _state.update { current ->
            current.copy(
                currentTrack = if (current.currentTrack?.id == updated.id) updated else current.currentTrack,
                queue = current.queue.map { if (it.id == updated.id) updated else it }
            )
        }
        runOnPlayerThread {
            val controller = mediaController ?: return@runOnPlayerThread
            val count = controller.mediaItemCount
            for (idx in 0 until count) {
                val existing = controller.getMediaItemAt(idx)
                if (existing.mediaId == updated.id) {
                    val existingUri = existing.localConfiguration?.uri?.toString()
                    val existingMime = existing.localConfiguration?.mimeType
                    val replacement = updated.toMediaItemOrNull(
                        overrideUri = existingUri,
                        mimeType = existingMime
                    ) ?: continue
                    val displayChanged = existing.mediaMetadata.title?.toString() != replacement.mediaMetadata.title?.toString() ||
                        existing.mediaMetadata.artist?.toString() != replacement.mediaMetadata.artist?.toString() ||
                        existing.mediaMetadata.albumTitle?.toString() != replacement.mediaMetadata.albumTitle?.toString() ||
                        existing.mediaMetadata.artworkUri != replacement.mediaMetadata.artworkUri
                    if (displayChanged) {
                        controller.replaceMediaItem(idx, replacement)
                    }
                }
            }
        }
    }

    fun syncToRoomPlayback(track: Track, isPlaying: Boolean, targetPosMs: Long) {
        runOnPlayerThread {
            val current = _state.value.currentTrack
            if (current?.id != track.id) {
                playTrack(track)
            }
            val controller = mediaController ?: return@runOnPlayerThread
            if (kotlin.math.abs(controller.currentPosition - targetPosMs) > 2_500L) {
                controller.seekTo(targetPosMs.coerceAtLeast(0L))
            }
            if (isPlaying && !controller.isPlaying) controller.play()
            else if (!isPlaying && controller.isPlaying) controller.pause()
        }
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
    ): MediaItem? = PulseMediaItemMapper.toMediaItem(
        track = this,
        overrideUri = overrideUri,
        mimeType = mimeType
    )

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
        isResolvingRemoteTrack = false
        awaitingMedia3TrackId = null
        replacingActiveTrackId = null
        pauseFadeJob?.cancel()
        pauseFadeJob = null
        isFadingOutToPause = false
        pauseRequestedBySmartFade = false
        progressJob?.cancel()
        sleepTimerJob?.cancel()
        AudioSpectrumBus.reset()
        _state.update { it.copy(isBuffering = false, isPlaying = false) }
        runOnPlayerThread {
            controllerFuture?.let { MediaController.releaseFuture(it) }
        }
    }
}
