package com.example.media

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.PresetReverb
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.MainActivity
import com.example.data.model.DefaultSoundscapes
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PulseMediaService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var presetReverb: PresetReverb? = null

    private var lastEqEnabled: Boolean = true
    private var lastBands: List<Int> = DefaultSoundscapes.presets.first().bandLevelsMillibel
    private var lastBass: Int = DefaultSoundscapes.presets.first().bassBoostStrength
    private var lastReverb: Int = DefaultSoundscapes.presets.first().reverbPreset

    companion object {
        const val ACTION_APPLY_SOUNDSCAPE = "com.example.pulsewave.APPLY_SOUNDSCAPE"
        const val EXTRA_BANDS = "bands"
        const val EXTRA_BASS = "bass"
        const val EXTRA_REVERB = "reverb"
        const val EXTRA_EQ_ENABLED = "eq_enabled"
        private const val TAG = "PulseMediaService"
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                attachAudioEffects(audioSessionId)
            }
        })

        player = exoPlayer
        if (exoPlayer.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            attachAudioEffects(exoPlayer.audioSessionId)
        }

        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(pendingIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon()
                        .add(SessionCommand(ACTION_APPLY_SOUNDSCAPE, Bundle.EMPTY))
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
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
            })
            .build()
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = mediaSession?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
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
