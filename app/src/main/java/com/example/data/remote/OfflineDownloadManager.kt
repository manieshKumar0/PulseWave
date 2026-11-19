package com.example.data.remote

import android.content.Context
import com.example.data.local.LocalLibraryRepository
import com.example.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class OfflineDownloadManager(
    private val context: Context,
    private val libraryRepo: LocalLibraryRepository,
    private val lrclibClient: LrclibClient,
    private val youTubeClient: YouTubeMusicClient = YouTubeMusicClient.shared
) {
    private val client: OkHttpClient = youTubeClient.playbackOkHttpClient
    private val activeDownloads = ConcurrentHashMap.newKeySet<String>()

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    suspend fun downloadTrack(track: Track): Result<Track> = withContext(Dispatchers.IO) {
        if (!activeDownloads.add(track.id)) {
            return@withContext Result.failure(IllegalStateException("Download already in progress"))
        }
        val audioDir = File(context.filesDir, "offline_audio").apply { mkdirs() }
        val artDir = File(context.filesDir, "offline_artwork").apply { mkdirs() }
        val safeName = track.id.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        val tempFile = File(audioDir, "$safeName.tmp")

        try {
            updateProgress(track.id, 0.03f)

            var candidateStream = resolveDirectDownloadTarget(track, forceRefresh = false)
            var downloadedMime = candidateStream.mimeType.ifBlank { "audio/mpeg" }

            var attempt = 0
            var downloadCompleted = false
            var lastError: Exception? = null

            while (attempt < 2 && !downloadCompleted) {
                attempt++
                val reqBuilder = Request.Builder().url(candidateStream.url)
                candidateStream.headers.forEach { (k, v) ->
                    reqBuilder.header(k, v)
                }

                try {
                    client.newCall(reqBuilder.build()).execute().use { response ->
                        if (response.code in listOf(403, 404, 410) && attempt == 1 && track.youtubeVideoId != null) {
                            youTubeClient.markStreamFailedAndInvalidate(track.youtubeVideoId!!)
                            candidateStream = resolveDirectDownloadTarget(track, forceRefresh = true)
                            downloadedMime = candidateStream.mimeType.ifBlank { downloadedMime }
                            return@use
                        }
                        if (!response.isSuccessful) {
                            throw IllegalStateException("Download failed with HTTP ${response.code}")
                        }
                        response.header("Content-Type")?.takeIf { it.isNotBlank() }?.let {
                            downloadedMime = it.substringBefore(';').trim()
                        }
                        val body = response.body ?: throw IllegalStateException("Empty audio response body")
                        val totalBytes = body.contentLength()
                        var bytesCopied = 0L
                        var lastReportedProgress = 0.03f

                        body.byteStream().use { input ->
                            FileOutputStream(tempFile).use { output ->
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    output.write(buffer, 0, read)
                                    bytesCopied += read

                                    val nextProgress = if (totalBytes > 0L) {
                                        (0.08f + (bytesCopied.toFloat() / totalBytes.toFloat()) * 0.82f)
                                            .coerceIn(0.08f, 0.90f)
                                    } else {
                                        (0.10f + (bytesCopied.toFloat() / 5_000_000f) * 0.78f)
                                            .coerceIn(0.10f, 0.88f)
                                    }
                                    if (nextProgress - lastReportedProgress >= 0.02f) {
                                        lastReportedProgress = nextProgress
                                        updateProgress(track.id, nextProgress)
                                    }
                                }
                                output.flush()
                            }
                        }

                        if (!tempFile.exists() || tempFile.length() == 0L) {
                            throw IllegalStateException("Downloaded audio file was empty")
                        }
                        downloadCompleted = true
                    }
                } catch (e: Exception) {
                    lastError = e
                    if (attempt == 1 && track.youtubeVideoId != null) {
                        youTubeClient.markStreamFailedAndInvalidate(track.youtubeVideoId!!)
                        val refreshed = runCatching {
                            resolveDirectDownloadTarget(track, forceRefresh = true)
                        }.getOrNull()
                        if (refreshed != null && refreshed.url != candidateStream.url) {
                            candidateStream = refreshed
                            downloadedMime = refreshed.mimeType.ifBlank { downloadedMime }
                            continue
                        }
                    }
                    break
                }
            }

            if (!downloadCompleted) {
                throw (lastError ?: IllegalStateException("Download failed"))
            }

            val extension = when {
                downloadedMime.contains("mp4", ignoreCase = true) ||
                    downloadedMime.contains("m4a", ignoreCase = true) -> "m4a"
                downloadedMime.contains("webm", ignoreCase = true) -> "webm"
                downloadedMime.contains("ogg", ignoreCase = true) -> "ogg"
                else -> "mp3"
            }
            val targetFile = File(audioDir, "$safeName.$extension")
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            updateProgress(track.id, 0.94f)
            val cachedArtPath = downloadArtworkIfAvailable(
                artworkUrl = track.artworkUrl,
                artDir = artDir,
                safeName = safeName
            )

            val lyrics = if (track.syncedLrcText.isBlank() && track.plainLyrics.isBlank()) {
                runCatching {
                    lrclibClient.fetchLyrics(track.title, track.artist, track.durationMs, track.album)
                }.getOrDefault(LyricsPayload("", ""))
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
            Result.success(saved)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            if (tempFile.exists()) {
                runCatching { tempFile.delete() }
            }
            activeDownloads.remove(track.id)
            updateProgress(track.id, null)
        }
    }

    private suspend fun resolveDirectDownloadTarget(
        track: Track,
        forceRefresh: Boolean
    ): DownloadTarget {
        if (!forceRefresh &&
            track.streamUrl.startsWith("http", ignoreCase = true) &&
            !track.streamUrl.contains(".m3u8", ignoreCase = true)
        ) {
            return DownloadTarget(
                url = track.streamUrl,
                mimeType = "audio/mpeg",
                headers = emptyMap()
            )
        }

        val videoId = track.youtubeVideoId
        if (videoId != null) {
            val resolved = youTubeClient.resolveAudioStream(
                videoId = videoId,
                forceRefresh = forceRefresh,
                trackTitle = track.title,
                trackArtist = track.artist,
                trackDurationMs = track.durationMs
            ).getOrNull()

            if (resolved != null && !resolved.isHls && !resolved.url.contains(".m3u8", ignoreCase = true)) {
                return DownloadTarget(
                    url = resolved.url,
                    mimeType = resolved.mimeType,
                    headers = resolved.requestHeaders
                )
            }
        }

        val fallback = FallbackAudioResolver(client) { url, headers ->
            probeDirectStream(url, headers)
        }.resolveMatchingStream(
            title = track.title,
            artist = track.artist,
            expectedDurationMs = track.durationMs,
            preferHighQuality = youTubeClient.preferHighQuality
        )
        if (fallback != null && !fallback.isHls) {
            return DownloadTarget(
                url = fallback.url,
                mimeType = fallback.mimeType,
                headers = fallback.requestHeaders
            )
        }

        throw IllegalStateException("Direct downloadable audio is unavailable for this track right now")
    }

    private fun probeDirectStream(url: String, headers: Map<String, String>): Boolean {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-1")
                .apply {
                    headers.forEach { (k, v) -> header(k, v) }
                }
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                resp.isSuccessful || resp.code == 206
            }
        } catch (_: Exception) {
            false
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
        _downloadProgress.update { current ->
            if (progress == null) current - trackId
            else current + (trackId to progress)
        }
    }

    private data class DownloadTarget(
        val url: String,
        val mimeType: String,
        val headers: Map<String, String>
    )
}
