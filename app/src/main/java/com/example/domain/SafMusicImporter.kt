package com.example.domain

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.example.data.model.Track
import com.example.data.model.TrackSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class SafMusicImporter(private val context: Context) {

    companion object {
        const val DEVICE_TRACK_ID_PREFIX = "device_ms_"
        const val DEFAULT_MIN_DURATION_MS = 120_000L

        private val ALBUM_ART_URI: Uri = Uri.parse("content://media/external/audio/albumart")

        fun requiredDeviceAudioPermission(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

        fun hasDeviceAudioPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                requiredDeviceAudioPermission()
            ) == PackageManager.PERMISSION_GRANTED

        fun isEligibleDeviceAudio(
            durationMs: Long,
            isMusic: Int,
            isNotification: Int,
            isAlarm: Int,
            isRingtone: Int,
            mimeType: String? = null,
            displayName: String? = null,
            minDurationMs: Long = DEFAULT_MIN_DURATION_MS
        ): Boolean {
            if (durationMs <= minDurationMs) return false
            if (isNotification == 1 || isAlarm == 1) return false
            if (isRingtone == 1 && isMusic != 1) return false

            val normalizedMime = mimeType?.lowercase().orEmpty()
            val normalizedName = displayName?.lowercase().orEmpty()
            val isAudioFile = normalizedMime.startsWith("audio/") ||
                normalizedName.endsWith(".mp3") ||
                normalizedName.endsWith(".m4a") ||
                normalizedName.endsWith(".aac") ||
                normalizedName.endsWith(".flac") ||
                normalizedName.endsWith(".wav") ||
                normalizedName.endsWith(".ogg") ||
                normalizedName.endsWith(".opus")

            return isMusic == 1 || isAudioFile || (normalizedMime.isBlank() && normalizedName.isBlank())
        }
    }

    private val artworkDir: File
        get() = File(context.filesDir, "local_artwork").apply { mkdirs() }

    suspend fun scanDeviceAudio(
        minDurationMs: Long = DEFAULT_MIN_DURATION_MS
    ): List<Track> = withContext(Dispatchers.IO) {
        if (!hasDeviceAudioPermission(context)) return@withContext emptyList()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.IS_MUSIC,
            MediaStore.Audio.Media.IS_RINGTONE,
            MediaStore.Audio.Media.IS_ALARM,
            MediaStore.Audio.Media.IS_NOTIFICATION
        )

        val selection = "${MediaStore.Audio.Media.DURATION} > ?"
        val selectionArgs = arrayOf(minDurationMs.toString())
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        val results = mutableListOf<Track>()

        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val isMusicCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)
                val isRingtoneCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_RINGTONE)
                val isAlarmCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_ALARM)
                val isNotifCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_NOTIFICATION)

                while (cursor.moveToNext()) {
                    val audioId = cursor.getLong(idCol)
                    if (audioId <= 0L) continue

                    val durationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L
                    val isMusic = if (isMusicCol >= 0 && !cursor.isNull(isMusicCol)) cursor.getInt(isMusicCol) else 0
                    val isRingtone = if (isRingtoneCol >= 0 && !cursor.isNull(isRingtoneCol)) cursor.getInt(isRingtoneCol) else 0
                    val isAlarm = if (isAlarmCol >= 0 && !cursor.isNull(isAlarmCol)) cursor.getInt(isAlarmCol) else 0
                    val isNotif = if (isNotifCol >= 0 && !cursor.isNull(isNotifCol)) cursor.getInt(isNotifCol) else 0
                    val displayName = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                    val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) else null

                    if (!isEligibleDeviceAudio(
                            durationMs = durationMs,
                            isMusic = isMusic,
                            isNotification = isNotif,
                            isAlarm = isAlarm,
                            isRingtone = isRingtone,
                            mimeType = mimeType,
                            displayName = displayName,
                            minDurationMs = minDurationMs
                        )
                    ) {
                        continue
                    }

                    val rawTitle = if (titleCol >= 0) cursor.getString(titleCol).orEmpty() else ""
                    val rawArtist = if (artistCol >= 0) cursor.getString(artistCol).orEmpty() else ""
                    val rawAlbum = if (albumCol >= 0) cursor.getString(albumCol).orEmpty() else ""
                    val albumId = if (albumIdCol >= 0 && !cursor.isNull(albumIdCol)) cursor.getLong(albumIdCol) else 0L

                    val (parsedArtist, parsedTitle) = parseFilenameMetadata(displayName.ifBlank { rawTitle.ifBlank { "Track" } })
                    val hasValidArtist = isValidTag(rawArtist)
                    val title = when {
                        isValidTag(rawTitle) && (hasValidArtist || parsedArtist == null) -> rawTitle.trim()
                        else -> parsedTitle
                    }
                    val artist = if (hasValidArtist) rawArtist.trim() else (parsedArtist ?: "Local Device")
                    val album = if (isValidTag(rawAlbum)) rawAlbum.trim() else "On Device"

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        audioId
                    ).toString()
                    val albumArtUri = if (albumId > 0L) {
                        ContentUris.withAppendedId(ALBUM_ART_URI, albumId).toString()
                    } else ""

                    results += Track(
                        id = "${DEVICE_TRACK_ID_PREFIX}${audioId}",
                        title = title,
                        artist = artist,
                        album = album,
                        genre = "Device Audio",
                        mood = "Personal",
                        bpm = 116,
                        durationMs = durationMs,
                        artworkUrl = albumArtUri,
                        localUri = contentUri,
                        sourceType = TrackSourceType.LOCAL_SAF
                    )
                }
            }
        } catch (_: SecurityException) {
            return@withContext emptyList()
        } catch (_: Exception) {
            return@withContext results
        }
        results
    }

    suspend fun importAudioUris(
        uris: List<Uri>,
        isCloudProvider: Boolean
    ): List<Track> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        uris.mapNotNull { uri ->
            try {
                try {
                    resolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // Some cloud DocumentsProviders offer temporary read grants
                }

                val trackId = "saf_${UUID.nameUUIDFromBytes(uri.toString().toByteArray())}"
                val displayName = queryDisplayName(uri)
                val retriever = MediaMetadataRetriever()
                val (parsedArtist, parsedTitle) = parseFilenameMetadata(displayName)
                var title = parsedTitle
                var artist = parsedArtist ?: if (isCloudProvider) "Cloud Drive" else "Local Device"
                var album = if (isCloudProvider) "Cloud Synced" else "On Device"
                var genre = "Imported"
                var durationMs = 180_000L
                var artworkUrl = ""

                try {
                    retriever.setDataSource(context, uri)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.takeIf { isValidTag(it) }?.let { title = it }
                    (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
                        ?.takeIf { isValidTag(it) }?.let { artist = it }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                        ?.takeIf { isValidTag(it) }?.let { album = it }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                        ?.takeIf { isValidTag(it) }?.let { genre = it }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()?.takeIf { it > 0 }?.let { durationMs = it }

                    val artBytes = retriever.embeddedPicture
                    if (artBytes != null && artBytes.isNotEmpty()) {
                        artworkUrl = saveArtworkBytes(trackId, artBytes)
                    }
                } catch (_: Exception) {
                    // Fallback to filename metadata
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {
                    }
                }

                val source = if (isCloudProvider || isLikelyCloudUri(uri)) {
                    TrackSourceType.CLOUD_SAF
                } else {
                    TrackSourceType.LOCAL_SAF
                }

                Track(
                    id = trackId,
                    title = title.trim(),
                    artist = artist.trim(),
                    album = album.trim(),
                    genre = genre.trim(),
                    mood = "Personal",
                    bpm = 116,
                    durationMs = durationMs,
                    artworkUrl = artworkUrl,
                    localUri = uri.toString(),
                    sourceType = source
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun resolveEmbeddedArtwork(trackId: String, uriString: String): String =
        withContext(Dispatchers.IO) {
            val existing = artworkFileFor(trackId)
            if (existing.exists() && existing.length() > 0L) {
                return@withContext Uri.fromFile(existing).toString()
            }
            if (uriString.isBlank()) return@withContext ""
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uriString))
                val artBytes = retriever.embeddedPicture
                if (artBytes != null && artBytes.isNotEmpty()) {
                    return@withContext saveArtworkBytes(trackId, artBytes)
                }
            } catch (_: Exception) {
                // Embedded artwork unavailable or URI grant expired
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }
            ""
        }

    private fun saveArtworkBytes(trackId: String, bytes: ByteArray): String {
        val target = artworkFileFor(trackId)
        target.writeBytes(bytes)
        return Uri.fromFile(target).toString()
    }

    private fun artworkFileFor(trackId: String): File {
        val safeName = trackId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        return File(artworkDir, "$safeName.jpg")
    }

    private fun isValidTag(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.isNotBlank() &&
            !trimmed.equals("<unknown>", ignoreCase = true) &&
            !trimmed.equals("unknown", ignoreCase = true)
    }

    private fun parseFilenameMetadata(displayName: String): Pair<String?, String> {
        val base = displayName.substringBeforeLast('.')
            .replace('_', ' ')
            .replace(Regex("^\\d{1,3}[\\s.\\-_]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "Imported Audio" }

        val separators = listOf(" - ", " – ", " — ")
        for (sep in separators) {
            val idx = base.indexOf(sep)
            if (idx > 1 && idx + sep.length < base.length - 1) {
                val left = base.substring(0, idx).trim()
                val right = base.substring(idx + sep.length).trim()
                if (left.isNotBlank() && right.isNotBlank()) {
                    return left to right
                }
            }
        }
        return null to base
    }

    private fun queryDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx) ?: "Track"
                }
            }
        return uri.lastPathSegment ?: "Track"
    }

    private fun isLikelyCloudUri(uri: Uri): Boolean {
        val auth = uri.authority?.lowercase().orEmpty()
        return auth.contains("drive") ||
            auth.contains("onedrive") ||
            auth.contains("dropbox") ||
            auth.contains("nextcloud") ||
            auth.contains("box")
    }
}
