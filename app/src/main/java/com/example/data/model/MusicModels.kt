package com.example.data.model

import androidx.compose.runtime.Immutable

enum class TrackSourceType(val label: String) {
    YOUTUBE("YouTube Music"),
    STREAM("Stream"),
    CURATED("Curated"),
    LOCAL_SAF("Device"),
    CLOUD_SAF("Cloud Drive")
}

@Immutable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "Single",
    val genre: String = "Electronic",
    val mood: String = "Atmospheric",
    val bpm: Int = 118,
    val durationMs: Long = 180_000L,
    val artworkUrl: String = "",
    val streamUrl: String = "",
    val localUri: String = "",
    val downloadedFilePath: String = "",
    val cachedArtworkPath: String = "",
    val sourceType: TrackSourceType = TrackSourceType.STREAM,
    val isDownloaded: Boolean = false,
    val isLiked: Boolean = false,
    val syncedLrcText: String = "",
    val plainLyrics: String = "",
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0L
) {
    companion object {
        const val YOUTUBE_ID_PREFIX = "yt_"
        const val YOUTUBE_URI_SCHEME = "pulseyt"
    }

    val youtubeVideoId: String?
        get() = when {
            id.startsWith(YOUTUBE_ID_PREFIX) -> id.removePrefix(YOUTUBE_ID_PREFIX).takeIf { it.isNotBlank() }
            streamUrl.startsWith("$YOUTUBE_URI_SCHEME://") -> {
                val queryMatch = Regex("[?&]v=([A-Za-z0-9_-]{6,})").find(streamUrl)?.groupValues?.getOrNull(1)
                queryMatch ?: streamUrl.removePrefix("$YOUTUBE_URI_SCHEME://").substringBefore('?').trim('/').takeIf { it.isNotBlank() }
            }
            else -> null
        }

    val isYouTubeTrack: Boolean
        get() = sourceType == TrackSourceType.YOUTUBE || youtubeVideoId != null

    val canDownloadOffline: Boolean
        get() = !isDownloaded &&
            sourceType != TrackSourceType.LOCAL_SAF &&
            sourceType != TrackSourceType.CLOUD_SAF &&
            (streamUrl.startsWith("http", ignoreCase = true) || isYouTubeTrack)

    val trackKey: String
        get() = "${title.lowercase().replace(Regex("[^a-z0-9]+"), "_")}_${artist.lowercase().replace(Regex("[^a-z0-9]+"), "_")}"
            .trim('_')
            .take(100)
            .ifBlank { id.take(100) }

    val playableUri: String
        get() = when {
            isDownloaded && downloadedFilePath.isNotBlank() -> "file://$downloadedFilePath"
            localUri.isNotBlank() -> localUri
            streamUrl.startsWith("http", ignoreCase = true) -> streamUrl
            youtubeVideoId != null -> "$YOUTUBE_URI_SCHEME://watch?v=$youtubeVideoId"
            else -> streamUrl
        }

    val displayArtworkUri: String
        get() = when {
            cachedArtworkPath.isNotBlank() -> "file://$cachedArtworkPath"
            else -> artworkUrl
        }
}

@Immutable
data class LyricLine(
    val timestampMs: Long,
    val text: String
)

@Immutable
data class SoundscapePreset(
    val id: String,
    val name: String,
    val subtitle: String,
    val bandLevelsMillibel: List<Int>, // 5 bands typically in [-1500, 1500]
    val bassBoostStrength: Int,        // 0..1000
    val reverbPreset: Int,             // 0..6 (PresetReverb constants)
    val ambientLabel: String,
    val crossfadeSeconds: Int = 4
)

@Immutable
data class SonicDnaProfile(
    val energy: Int = 70,
    val valence: Int = 62,
    val tempo: Int = 68,
    val acoustic: Int = 34,
    val electronic: Int = 82,
    val chill: Int = 55,
    val topGenres: List<String> = listOf("Electronic", "Synthwave", "Indie"),
    val topMoods: List<String> = listOf("Energetic", "Atmospheric", "Dreamy"),
    val totalPlays: Int = 0,
    val avgCompletionPct: Int = 84
)

@Immutable
data class RecommendedPick(
    val track: Track,
    val score: Double,
    val reasonTag: String,
    val explanation: String,
    val category: String // "For You", "Replay Radar", "Friend Wave", "Mood DJ"
)

@Immutable
data class DiscoveryFeedBundle(
    val djHeadline: String = "Pulse DJ tuned to your late-night wave",
    val djSubheadline: String = "Blended from your completion rates, replays, and friend pulse",
    val heroPicks: List<RecommendedPick> = emptyList(),
    val replayPicks: List<RecommendedPick> = emptyList(),
    val friendWavePicks: List<RecommendedPick> = emptyList(),
    val explorePicks: List<RecommendedPick> = emptyList(),
    val youtubeLikedRecs: List<Track> = emptyList(),
    val sonicDna: SonicDnaProfile = SonicDnaProfile(),
    val poweredByGemini: Boolean = false
)

object DefaultSoundscapes {
    val bandLabels = listOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")
    val reverbLabels = listOf("Off", "Small", "Med", "Room+", "Hall", "Arena", "Plate")

    val presets = listOf(
        SoundscapePreset(
            id = "obsidian_studio",
            name = "Flat Studio",
            subtitle = "Pure balanced studio reference curve",
            bandLevelsMillibel = listOf(0, 0, 0, 0, 0),
            bassBoostStrength = 0,
            reverbPreset = 0,
            ambientLabel = "Flat Reference"
        ),
        SoundscapePreset(
            id = "eq_rock",
            name = "Rock",
            subtitle = "Punchy low-end kick & crisp guitar edge",
            bandLevelsMillibel = listOf(500, 300, -100, 350, 500),
            bassBoostStrength = 420,
            reverbPreset = 0,
            ambientLabel = "Stage Punch"
        ),
        SoundscapePreset(
            id = "eq_jazz",
            name = "Jazz",
            subtitle = "Warm upright bass & intimate brass clarity",
            bandLevelsMillibel = listOf(350, 200, -150, 250, 400),
            bassBoostStrength = 260,
            reverbPreset = 1, // SMALLROOM
            ambientLabel = "Jazz Lounge"
        ),
        SoundscapePreset(
            id = "eq_pop",
            name = "Pop",
            subtitle = "Forward vocal presence & tight radio sheen",
            bandLevelsMillibel = listOf(-100, 250, 450, 300, -50),
            bassBoostStrength = 300,
            reverbPreset = 0,
            ambientLabel = "Vocal Shine"
        ),
        SoundscapePreset(
            id = "eq_classical",
            name = "Classical",
            subtitle = "Orchestral dynamic depth & concert hall space",
            bandLevelsMillibel = listOf(400, 250, -150, 250, 400),
            bassBoostStrength = 120,
            reverbPreset = 5, // LARGEHALL
            ambientLabel = "Concert Hall"
        ),
        SoundscapePreset(
            id = "eq_bass_boost",
            name = "Bass Boost",
            subtitle = "Maximum sub-bass slam & warm chest impact",
            bandLevelsMillibel = listOf(750, 550, 100, 50, 150),
            bassBoostStrength = 850,
            reverbPreset = 0,
            ambientLabel = "Heavy Sub"
        ),
        SoundscapePreset(
            id = "neon_rain",
            name = "Lo-Fi Reverb",
            subtitle = "Warm lo-fi mids, soft highs & dreamy room space",
            bandLevelsMillibel = listOf(280, 180, 120, -200, -340),
            bassBoostStrength = 340,
            reverbPreset = 2, // MEDIUMROOM
            ambientLabel = "Lo-Fi Spatial"
        ),
        SoundscapePreset(
            id = "velvet_club",
            name = "Midnight Club",
            subtitle = "Deep sub punch + lush hall bloom",
            bandLevelsMillibel = listOf(550, 320, -80, 180, 340),
            bassBoostStrength = 680,
            reverbPreset = 4, // MEDIUMHALL
            ambientLabel = "Deep Sub + Hall"
        ),
        SoundscapePreset(
            id = "stadium_hyper",
            name = "Hyperwave Arena",
            subtitle = "Wide stadium presence + vocal sparkle",
            bandLevelsMillibel = listOf(420, 150, 260, 400, 480),
            bassBoostStrength = 520,
            reverbPreset = 6, // PLATE
            ambientLabel = "Wide Arena"
        ),
        SoundscapePreset(
            id = "vinyl_warmth",
            name = "Analog Vinyl",
            subtitle = "Rolled-off highs with rich analog chest",
            bandLevelsMillibel = listOf(380, 260, 140, -100, -320),
            bassBoostStrength = 410,
            reverbPreset = 1, // SMALLROOM
            ambientLabel = "Tape Warmth"
        )
    )

    fun customPreset(
        bandLevelsMillibel: List<Int>,
        bassBoostStrength: Int,
        reverbPreset: Int
    ): SoundscapePreset = SoundscapePreset(
        id = "custom",
        name = "Custom EQ",
        subtitle = "Personalized 5-band equalizer profile",
        bandLevelsMillibel = bandLevelsMillibel,
        bassBoostStrength = bassBoostStrength,
        reverbPreset = reverbPreset,
        ambientLabel = "Custom Curve"
    )

    fun presetForId(id: String): SoundscapePreset? = presets.firstOrNull { it.id == id }
}
