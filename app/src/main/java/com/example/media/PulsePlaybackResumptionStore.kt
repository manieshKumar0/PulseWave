package com.example.media

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaSession
import com.example.data.model.Track
import com.example.data.model.TrackSourceType
import com.example.data.remote.YouTubeMusicClient
import org.json.JSONArray
import org.json.JSONObject

internal object PulseMediaItemMapper {
    private const val EXTRA_SOURCE_TYPE = "pulse_source_type"
    private const val EXTRA_ALBUM = "pulse_album"
    private const val EXTRA_GENRE = "pulse_genre"
    private const val EXTRA_MOOD = "pulse_mood"
    private const val EXTRA_BPM = "pulse_bpm"
    private const val EXTRA_DURATION_MS = "pulse_duration_ms"
    private const val EXTRA_ARTWORK_URL = "pulse_artwork_url"
    private const val EXTRA_STREAM_URL = "pulse_stream_url"
    private const val EXTRA_LOCAL_URI = "pulse_local_uri"
    private const val EXTRA_DOWNLOADED_PATH = "pulse_downloaded_path"
    private const val EXTRA_CACHED_ART_PATH = "pulse_cached_art_path"
    private const val EXTRA_IS_DOWNLOADED = "pulse_is_downloaded"
    private const val EXTRA_IS_LIKED = "pulse_is_liked"
    private const val EXTRA_PLAY_COUNT = "pulse_play_count"
    private const val EXTRA_LAST_PLAYED_AT = "pulse_last_played_at"

    @OptIn(UnstableApi::class)
    fun toMediaItem(
        track: Track,
        overrideUri: String? = null,
        mimeType: String? = null,
        existingExtras: Bundle? = null
    ): MediaItem? {
        val uriString = overrideUri ?: track.playableUri
        if (uriString.isBlank()) return null

        val artUri = track.displayArtworkUri
        val extras = Bundle().apply {
            existingExtras?.let { putAll(it) }
            putString(EXTRA_SOURCE_TYPE, track.sourceType.name)
            putString(EXTRA_ALBUM, track.album)
            putString(EXTRA_GENRE, track.genre)
            putString(EXTRA_MOOD, track.mood)
            putInt(EXTRA_BPM, track.bpm)
            putLong(EXTRA_DURATION_MS, track.durationMs)
            putString(EXTRA_ARTWORK_URL, track.artworkUrl)
            putString(EXTRA_STREAM_URL, track.streamUrl)
            putString(EXTRA_LOCAL_URI, track.localUri)
            putString(EXTRA_DOWNLOADED_PATH, track.downloadedFilePath)
            putString(EXTRA_CACHED_ART_PATH, track.cachedArtworkPath)
            putBoolean(EXTRA_IS_DOWNLOADED, track.isDownloaded)
            putBoolean(EXTRA_IS_LIKED, track.isLiked)
            putInt(EXTRA_PLAY_COUNT, track.playCount)
            putLong(EXTRA_LAST_PLAYED_AT, track.lastPlayedAt)
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setGenre(track.genre)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .apply {
                if (track.durationMs > 0L) {
                    setDurationMs(track.durationMs)
                }
                if (artUri.isNotBlank()) {
                    setArtworkUri(Uri.parse(artUri))
                }
            }
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uriString)
            .apply {
                if (!mimeType.isNullOrBlank()) {
                    setMimeType(mimeType)
                }
            }
            .setMediaMetadata(metadata)
            .build()
    }

    fun fromMediaItem(item: MediaItem): Track? {
        val id = item.mediaId.takeIf { it.isNotBlank() } ?: return null
        val meta = item.mediaMetadata
        val extras = meta.extras
        val sourceTypeName = extras?.getString(EXTRA_SOURCE_TYPE).orEmpty()
        val parsedSource = TrackSourceType.entries.firstOrNull { it.name == sourceTypeName }
            ?: if (id.startsWith(Track.YOUTUBE_ID_PREFIX)) TrackSourceType.YOUTUBE else TrackSourceType.STREAM

        val uriStr = item.localConfiguration?.uri?.toString().orEmpty()
        val artStr = extras?.getString(EXTRA_ARTWORK_URL)
            ?: meta.artworkUri?.toString().orEmpty()
        val localUriStr = extras?.getString(EXTRA_LOCAL_URI)
            ?: uriStr.takeIf { it.startsWith("content://") }
            ?: ""
        val streamUrlStr = extras?.getString(EXTRA_STREAM_URL)
            ?.takeIf { it.isNotBlank() }
            ?: if (uriStr.startsWith("http", ignoreCase = true) && !id.startsWith(Track.YOUTUBE_ID_PREFIX)) uriStr else ""
        val downloadedPath = extras?.getString(EXTRA_DOWNLOADED_PATH).orEmpty()
        val isDownloaded = extras?.getBoolean(EXTRA_IS_DOWNLOADED, false) ?: false

        return Track(
            id = id,
            title = meta.title?.toString()?.takeIf { it.isNotBlank() } ?: "Untitled Track",
            artist = meta.artist?.toString()?.takeIf { it.isNotBlank() } ?: "Unknown Artist",
            album = extras?.getString(EXTRA_ALBUM)
                ?: meta.albumTitle?.toString()
                ?: "Single",
            genre = extras?.getString(EXTRA_GENRE)
                ?: meta.genre?.toString()
                ?: "Music",
            mood = extras?.getString(EXTRA_MOOD) ?: "Atmospheric",
            bpm = extras?.getInt(EXTRA_BPM, 118) ?: 118,
            durationMs = extras?.getLong(EXTRA_DURATION_MS, 180_000L)?.takeIf { it > 0L } ?: 180_000L,
            artworkUrl = artStr,
            streamUrl = streamUrlStr,
            localUri = localUriStr,
            downloadedFilePath = downloadedPath,
            cachedArtworkPath = extras?.getString(EXTRA_CACHED_ART_PATH).orEmpty(),
            sourceType = parsedSource,
            isDownloaded = isDownloaded,
            isLiked = extras?.getBoolean(EXTRA_IS_LIKED, false) ?: false,
            playCount = extras?.getInt(EXTRA_PLAY_COUNT, 0) ?: 0,
            lastPlayedAt = extras?.getLong(EXTRA_LAST_PLAYED_AT, 0L) ?: 0L
        )
    }
}

/**
 * Lightweight synchronous store used by [PulseMediaService] to persist the last active queue
 * and playback position for Media3 playback resumption (lock screen / Bluetooth / OS restart).
 */
internal class PulsePlaybackResumptionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "pulse_media_resumption_prefs"
        private const val KEY_QUEUE_JSON = "queue_json"
        private const val KEY_START_INDEX = "start_index"
        private const val KEY_POSITION_MS = "position_ms"
        private const val MAX_PERSISTED_ITEMS = 25
    }

    fun saveSnapshot(
        items: List<MediaItem>,
        currentIndex: Int,
        positionMs: Long
    ) {
        if (items.isEmpty()) {
            clear()
            return
        }
        val clampedIndex = currentIndex.coerceIn(0, items.lastIndex)
        // Center persisted slice cleanly around clampedIndex if queue is large
        val windowStart = (clampedIndex - MAX_PERSISTED_ITEMS / 2).coerceAtLeast(0)
        val windowEnd = (windowStart + MAX_PERSISTED_ITEMS).coerceAtMost(items.size)
        val sliced = items.subList(windowStart, windowEnd)
        val adjustedIndex = (clampedIndex - windowStart).coerceIn(0, sliced.lastIndex)

        val arr = JSONArray()
        for (item in sliced) {
            val serialized = serializeItem(item) ?: continue
            arr.put(serialized)
        }
        if (arr.length() == 0) {
            clear()
            return
        }

        prefs.edit()
            .putString(KEY_QUEUE_JSON, arr.toString())
            .putInt(KEY_START_INDEX, adjustedIndex.coerceIn(0, arr.length() - 1))
            .putLong(KEY_POSITION_MS, positionMs.coerceAtLeast(0L))
            .apply()
    }

    @OptIn(UnstableApi::class)
    fun loadSnapshot(isForPlayback: Boolean): MediaSession.MediaItemsWithStartPosition? {
        val raw = prefs.getString(KEY_QUEUE_JSON, null)?.takeIf { it.isNotBlank() } ?: return null
        val startIndex = prefs.getInt(KEY_START_INDEX, 0).coerceAtLeast(0)
        val positionMs = prefs.getLong(KEY_POSITION_MS, 0L).coerceAtLeast(0L)

        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        val items = mutableListOf<MediaItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val item = deserializeItem(obj, addCompletionExtras = !isForPlayback && i == startIndex, positionMs = positionMs)
            if (item != null) {
                items.add(item)
            }
        }
        if (items.isEmpty()) return null
        val safeStartIdx = startIndex.coerceIn(0, items.lastIndex)
        return MediaSession.MediaItemsWithStartPosition(items, safeStartIdx, positionMs)
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_QUEUE_JSON)
            .remove(KEY_START_INDEX)
            .remove(KEY_POSITION_MS)
            .apply()
    }

    private fun serializeItem(item: MediaItem): JSONObject? {
        val id = item.mediaId.takeIf { it.isNotBlank() } ?: return null
        val rawUri = item.localConfiguration?.uri?.toString().orEmpty()
        val track = PulseMediaItemMapper.fromMediaItem(item)
        val videoId = when {
            track?.isYouTubeTrack == true -> {
                track.youtubeVideoId ?: YouTubeMusicClient.extractVideoId(rawUri)
            }
            track == null -> YouTubeMusicClient.extractVideoId(rawUri)
            else -> null
        }
        // Always persist durable URI: offline file if downloaded, otherwise pulseyt:// for YouTube or raw URI
        val durableUri = when {
            track != null && track.isDownloaded && track.downloadedFilePath.isNotBlank() ->
                "file://${track.downloadedFilePath}"
            videoId != null -> "${Track.YOUTUBE_URI_SCHEME}://watch?v=$videoId"
            else -> rawUri
        }
        if (durableUri.isBlank()) return null

        val meta = item.mediaMetadata
        return JSONObject().apply {
            put("mediaId", id)
            put("uri", durableUri)
            put("title", meta.title?.toString().orEmpty())
            put("artist", meta.artist?.toString().orEmpty())
            put("album", meta.albumTitle?.toString().orEmpty())
            put("genre", meta.genre?.toString().orEmpty())
            put("artworkUri", meta.artworkUri?.toString().orEmpty())
            put("durationMs", track?.durationMs ?: 180_000L)
            put("sourceType", track?.sourceType?.name ?: TrackSourceType.STREAM.name)
            put("isDownloaded", track?.isDownloaded ?: false)
            put("downloadedPath", track?.downloadedFilePath.orEmpty())
            put("localUri", track?.localUri.orEmpty())
            put("cachedArtPath", track?.cachedArtworkPath.orEmpty())
            put("isLiked", track?.isLiked ?: false)
            put("bpm", track?.bpm ?: 118)
            put("mood", track?.mood ?: "Atmospheric")
        }
    }

    @OptIn(UnstableApi::class)
    private fun deserializeItem(
        obj: JSONObject,
        addCompletionExtras: Boolean,
        positionMs: Long
    ): MediaItem? {
        val id = obj.optString("mediaId").takeIf { it.isNotBlank() } ?: return null
        val uri = obj.optString("uri").takeIf { it.isNotBlank() } ?: return null
        val durationMs = obj.optLong("durationMs", 180_000L).coerceAtLeast(1_000L)
        val sourceType = TrackSourceType.entries.firstOrNull {
            it.name == obj.optString("sourceType")
        } ?: if (id.startsWith(Track.YOUTUBE_ID_PREFIX)) TrackSourceType.YOUTUBE else TrackSourceType.STREAM

        val track = Track(
            id = id,
            title = obj.optString("title").ifBlank { "Untitled Track" },
            artist = obj.optString("artist").ifBlank { "Unknown Artist" },
            album = obj.optString("album").ifBlank { "Single" },
            genre = obj.optString("genre").ifBlank { "Music" },
            mood = obj.optString("mood").ifBlank { "Atmospheric" },
            bpm = obj.optInt("bpm", 118),
            durationMs = durationMs,
            artworkUrl = obj.optString("artworkUri"),
            localUri = obj.optString("localUri"),
            downloadedFilePath = obj.optString("downloadedPath"),
            cachedArtworkPath = obj.optString("cachedArtPath"),
            sourceType = sourceType,
            isDownloaded = obj.optBoolean("isDownloaded", false),
            isLiked = obj.optBoolean("isLiked", false)
        )

        val extraCompletion = if (addCompletionExtras && durationMs > 0L) {
            Bundle().apply {
                val pct = (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)
                putInt(
                    MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                    if (positionMs > 0L) MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
                    else MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
                )
                putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, pct)
            }
        } else null

        return PulseMediaItemMapper.toMediaItem(
            track = track,
            overrideUri = uri,
            existingExtras = extraCompletion
        )
    }
}
