package com.example.domain

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.model.Track
import com.example.data.model.TrackSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class SafMusicImporter(private val context: Context) {

    private val artworkDir: File
        get() = File(context.filesDir, "local_artwork").apply { mkdirs() }

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
