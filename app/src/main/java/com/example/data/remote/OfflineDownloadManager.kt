package com.example.data.remote

import android.content.Context
import com.example.data.local.LocalLibraryRepository
import com.example.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class OfflineDownloadManager(
    private val context: Context,
    private val libraryRepo: LocalLibraryRepository,
    private val lrclibClient: LrclibClient
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    suspend fun downloadTrack(track: Track): Result<Track> = withContext(Dispatchers.IO) {
        val url = track.streamUrl
        if (url.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Track has no downloadable stream URL"))
        }

        try {
            updateProgress(track.id, 0.05f)
            libraryRepo.upsertTrack(track)

            val audioDir = File(context.filesDir, "offline_audio").apply { mkdirs() }
            val artDir = File(context.filesDir, "offline_artwork").apply { mkdirs() }
            val safeName = track.id.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            val targetFile = File(audioDir, "$safeName.mp3")
            val tempFile = File(audioDir, "$safeName.tmp")
            var downloadedMime = "audio/mpeg"

            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("Download failed with HTTP ${response.code}")
                }
                response.header("Content-Type")?.takeIf { it.isNotBlank() }?.let {
                    downloadedMime = it.substringBefore(';').trim()
                }
                val body = response.body ?: throw IllegalStateException("Empty audio response body")
                val totalBytes = body.contentLength()
                var bytesCopied = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            bytesCopied += read
                            if (totalBytes > 0) {
                                updateProgress(track.id, (bytesCopied.toFloat() / totalBytes).coerceIn(0.05f, 0.90f))
                            }
                        }
                        output.flush()
                    }
                }
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            // Cache cover artwork locally so artwork renders cleanly while offline
            val cachedArtPath = downloadArtworkIfAvailable(
                artworkUrl = track.artworkUrl,
                artDir = artDir,
                safeName = safeName
            )
            updateProgress(track.id, 0.95f)

            val lyrics = if (track.syncedLrcText.isBlank() && track.plainLyrics.isBlank()) {
                lrclibClient.fetchLyrics(track.title, track.artist, track.durationMs, track.album)
            } else {
                LyricsPayload(track.syncedLrcText, track.plainLyrics)
            }

            val artBytes = cachedArtPath.takeIf { it.isNotBlank() }?.let { File(it).length() } ?: 0L
            val totalSizeBytes = targetFile.length() + artBytes

            val enrichedTrack = track.copy(
                isDownloaded = true,
                downloadedFilePath = targetFile.absolutePath,
                cachedArtworkPath = cachedArtPath.ifBlank { track.cachedArtworkPath },
                syncedLrcText = lyrics.syncedLrc.ifBlank { track.syncedLrcText },
                plainLyrics = lyrics.plainLyrics.ifBlank { track.plainLyrics }
            )

            val saved = libraryRepo.markTrackDownloaded(
                track = enrichedTrack,
                audioFilePath = targetFile.absolutePath,
                artworkFilePath = cachedArtPath,
                fileSizeBytes = totalSizeBytes,
                mimeType = downloadedMime
            )
            updateProgress(track.id, null)
            Result.success(saved)
        } catch (e: Exception) {
            updateProgress(track.id, null)
            Result.failure(e)
        }
    }

    suspend fun removeOfflineDownload(track: Track): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            if (track.downloadedFilePath.isNotBlank()) {
                File(track.downloadedFilePath).delete()
            }
            if (track.cachedArtworkPath.isNotBlank()) {
                File(track.cachedArtworkPath).delete()
            }
            libraryRepo.removeOfflineDownloadRecord(track.id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun downloadArtworkIfAvailable(
        artworkUrl: String,
        artDir: File,
        safeName: String
    ): String {
        if (!artworkUrl.startsWith("http", ignoreCase = true)) return ""
        val artFile = File(artDir, "$safeName.jpg")
        if (artFile.exists() && artFile.length() > 0L) return artFile.absolutePath
        return try {
            val req = Request.Builder().url(artworkUrl).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                val body = resp.body ?: return ""
                FileOutputStream(artFile).use { out ->
                    body.byteStream().copyTo(out)
                    out.flush()
                }
            }
            if (artFile.exists() && artFile.length() > 0L) artFile.absolutePath else ""
        } catch (_: Exception) {
            artFile.delete()
            ""
        }
    }

    private fun updateProgress(trackId: String, progress: Float?) {
        val mutable = _downloadProgress.value.toMutableMap()
        if (progress == null) {
            mutable.remove(trackId)
        } else {
            mutable[trackId] = progress
        }
        _downloadProgress.value = mutable
    }
}
