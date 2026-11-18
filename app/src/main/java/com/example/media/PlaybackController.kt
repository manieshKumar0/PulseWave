package com.example.media

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.example.data.local.ListeningSignalEntity
import com.example.data.local.SignalDao
import com.example.data.model.DefaultSoundscapes
import com.example.data.model.SoundscapePreset
import com.example.data.model.Track
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
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

class PlaybackController(
    private val context: Context,
    private val signalDao: SignalDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var trackPlayStartedMs: Long = 0L
    private var maxPositionReachedMs: Long = 0L
    private var seekBackCounter: Int = 0
    private var lastTrackId: String? = null
    private var replayCounts = mutableMapOf<String, Int>()
    private var fadeInStartedAtMs: Long = 0L
    private var fadeInDurationMs: Long = 0L
    private var sleepTimerEndRealtimeMs: Long? = null

    init {
        connectController()
    }

    private fun connectController() {
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
            } catch (_: Exception) {
                // Controller will retry on next action
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun attachPlayerListener(controller: MediaController) {
        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.update {
                    it.copy(
                        isBuffering = playbackState == Player.STATE_BUFFERING,
                        durationMs = controller.duration.coerceAtLeast(it.currentTrack?.durationMs ?: 0L)
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
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.update {
                    it.copy(
                        isPlaying = false,
                        isBuffering = false,
                        playbackError = error.localizedMessage ?: "Audio stream unavailable"
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
                val playing = controller?.isPlaying == true
                if (controller != null) {
                    val pos = controller.currentPosition.coerceAtLeast(0L)
                    if (pos > maxPositionReachedMs) maxPositionReachedMs = pos
                    val dur = controller.duration.takeIf { it > 0 }
                        ?: _state.value.currentTrack?.durationMs ?: 180_000L
                    val bpm = (_state.value.currentTrack?.bpm ?: 118).coerceIn(60, 200)
                    val beatDurationMs = 60_000.0 / bpm
                    val rawPhase = ((pos % beatDurationMs) / beatDurationMs).toFloat().coerceIn(0f, 1f)
                    val beatAngle = (pos / beatDurationMs) * Math.PI * 2.0
                    val energy = if (playing) {
                        ((sin(beatAngle) * 0.35 + 0.65).toFloat()).coerceIn(0.2f, 1.0f)
                    } else 0.14f

                    applyCrossfadeEnvelope(controller, playing, pos, dur)

                    _state.update {
                        it.copy(
                            positionMs = pos,
                            durationMs = dur,
                            isPlaying = playing,
                            beatPulseEnergy = energy,
                            beatPhase = if (playing) rawPhase else 0f
                        )
                    }
                }
                delay(if (playing) 50L else 180L)
            }
        }
    }

    private fun triggerFadeInIfEnabled(controller: MediaController?) {
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
        controller: MediaController,
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

    fun playTrack(track: Track, queue: List<Track> = listOf(track)) {
        val safeQueue = if (queue.any { it.id == track.id }) queue else listOf(track) + queue
        val targetIndex = safeQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)

        if (lastTrackId == track.id) {
            replayCounts[track.id] = (replayCounts[track.id] ?: 0) + 1
        } else if (lastTrackId != null) {
            recordTrackSignal(completed = false)
        }

        lastTrackId = track.id
        trackPlayStartedMs = System.currentTimeMillis()
        maxPositionReachedMs = 0L
        seekBackCounter = 0

        _state.update {
            it.copy(
                currentTrack = track,
                queue = safeQueue,
                currentIndex = targetIndex,
                durationMs = track.durationMs,
                playbackError = null
            )
        }

        val controller = mediaController
        if (controller != null) {
            val mediaItems = safeQueue.mapNotNull { it.toMediaItemOrNull() }
            if (mediaItems.isEmpty()) {
                _state.update { it.copy(playbackError = "No playable audio URI for this track") }
                return
            }
            val safeIdx = targetIndex.coerceIn(0, mediaItems.lastIndex)
            triggerFadeInIfEnabled(controller)
            controller.setMediaItems(mediaItems, safeIdx, 0L)
            controller.prepare()
            controller.play()
        } else {
            connectController()
        }
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
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
        val controller = mediaController ?: return
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

    private fun Track.toMediaItemOrNull(): MediaItem? {
        val uriString = playableUri
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
            .setMediaMetadata(metadata)
            .build()
    }

    fun release() {
        progressJob?.cancel()
        sleepTimerJob?.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
