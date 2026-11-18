package com.example.data.local

import androidx.room.withTransaction
import com.example.data.model.Track
import com.example.data.model.TrackSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

data class OfflineCacheSummary(
    val cachedTrackCount: Int = 0,
    val downloadedCount: Int = 0,
    val localImportedCount: Int = 0,
    val totalBytesUsed: Long = 0L
)

class LocalLibraryRepository(
    private val db: PulseDatabase
) {
    private val trackDao: TrackDao = db.trackDao()
    private val downloadedTrackDao: DownloadedTrackDao = db.downloadedTrackDao()
    private val signalDao: SignalDao = db.signalDao()

    val allTracks: Flow<List<Track>> = trackDao.observeAllTracks()
        .map { list -> list.map { it.toDomain() } }

    val offlinePlayableTracks: Flow<List<Track>> = trackDao.observeOfflinePlayableTracks()
        .map { list -> list.map { it.toDomain() } }

    val downloadedTracks: Flow<List<Track>> = trackDao.observeDownloadedTracks()
        .map { list -> list.map { it.toDomain() } }

    val downloadedEntries: Flow<List<DownloadedTrackEntity>> = downloadedTrackDao.observeAll()

    val recentSignals: Flow<List<ListeningSignalEntity>> = signalDao.observeRecentSignals()

    val cacheSummary: Flow<OfflineCacheSummary> = combine(
        trackDao.observeAllTracks(),
        downloadedTrackDao.observeTotalBytes()
    ) { tracks, totalBytes ->
        val downloaded = tracks.count { it.isDownloaded }
        val localImported = tracks.count {
            it.sourceType == TrackSourceType.LOCAL_SAF.name ||
                it.sourceType == TrackSourceType.CLOUD_SAF.name
        }
        OfflineCacheSummary(
            cachedTrackCount = tracks.size,
            downloadedCount = downloaded,
            localImportedCount = localImported,
            totalBytesUsed = totalBytes.coerceAtLeast(0L)
        )
    }

    suspend fun getTrackById(id: String): Track? = withContext(Dispatchers.IO) {
        trackDao.getTrackById(id)?.toDomain()
    }

    suspend fun getOfflinePlayableSnapshot(): List<Track> = withContext(Dispatchers.IO) {
        trackDao.getAllTracksSnapshot()
            .filter {
                it.isDownloaded ||
                    it.sourceType == TrackSourceType.LOCAL_SAF.name ||
                    it.sourceType == TrackSourceType.CLOUD_SAF.name
            }
            .map { it.toDomain() }
    }

    suspend fun cacheRemoteCatalog(remoteTracks: List<Track>) = withContext(Dispatchers.IO) {
        if (remoteTracks.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        db.withTransaction {
            val existingById = trackDao.getAllTracksSnapshot().associateBy { it.id }
            val merged = remoteTracks.map { remote ->
                val existing = existingById[remote.id]
                if (existing == null) {
                    TrackEntity.fromDomain(remote, now = now)
                } else {
                    val keepLocalSource = existing.sourceType == TrackSourceType.LOCAL_SAF.name ||
                        existing.sourceType == TrackSourceType.CLOUD_SAF.name
                    existing.copy(
                        title = remote.title.ifBlank { existing.title },
                        artist = remote.artist.ifBlank { existing.artist },
                        album = remote.album.ifBlank { existing.album },
                        genre = remote.genre.ifBlank { existing.genre },
                        mood = remote.mood.ifBlank { existing.mood },
                        bpm = if (remote.bpm > 0) remote.bpm else existing.bpm,
                        durationMs = if (remote.durationMs > 0L) remote.durationMs else existing.durationMs,
                        artworkUrl = remote.artworkUrl.ifBlank { existing.artworkUrl },
                        streamUrl = remote.streamUrl.ifBlank { existing.streamUrl },
                        sourceType = if (keepLocalSource) existing.sourceType else remote.sourceType.name,
                        isLiked = existing.isLiked || remote.isLiked,
                        syncedLrcText = existing.syncedLrcText.ifBlank { remote.syncedLrcText },
                        plainLyrics = existing.plainLyrics.ifBlank { remote.plainLyrics },
                        cachedAt = now
                    )
                }
            }
            trackDao.upsertAll(merged)
        }
    }

    suspend fun upsertTrack(track: Track) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val existing = trackDao.getTrackById(track.id)
            val entity = mergePreservingLocalFields(track, existing, now)
            trackDao.upsertTrack(entity)
        }
    }

    suspend fun upsertAll(tracks: List<Track>) = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        db.withTransaction {
            val existingById = trackDao.getAllTracksSnapshot().associateBy { it.id }
            val merged = tracks.map { track ->
                mergePreservingLocalFields(track, existingById[track.id], now)
            }
            trackDao.upsertAll(merged)
        }
    }

    suspend fun recordTrackPlayed(track: Track): Track = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val existing = trackDao.getTrackById(track.id)
            val base = mergePreservingLocalFields(track, existing, now)
            val updated = base.copy(
                playCount = (existing?.playCount ?: track.playCount) + 1,
                lastPlayedAt = now,
                cachedAt = now
            )
            trackDao.upsertTrack(updated)
            updated.toDomain()
        }
    }

    suspend fun setLiked(track: Track, liked: Boolean): Track = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val existing = trackDao.getTrackById(track.id)
            val entity = mergePreservingLocalFields(track.copy(isLiked = liked), existing, now)
                .copy(isLiked = liked)
            trackDao.upsertTrack(entity)
            entity.toDomain()
        }
    }

    suspend fun updateLyrics(trackId: String, syncedLrc: String, plain: String) = withContext(Dispatchers.IO) {
        trackDao.updateLyrics(trackId, syncedLrc, plain)
    }

    suspend fun updateArtwork(trackId: String, artworkUrl: String) = withContext(Dispatchers.IO) {
        trackDao.updateArtwork(trackId, artworkUrl)
    }

    suspend fun markTrackDownloaded(
        track: Track,
        audioFilePath: String,
        artworkFilePath: String,
        fileSizeBytes: Long,
        mimeType: String = "audio/mpeg"
    ): Track = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val existing = trackDao.getTrackById(track.id)
            val resolvedArt = artworkFilePath.ifBlank { existing?.cachedArtworkPath.orEmpty() }
            val merged = mergePreservingLocalFields(track, existing, now).copy(
                isDownloaded = true,
                downloadedFilePath = audioFilePath,
                cachedArtworkPath = resolvedArt,
                syncedLrcText = track.syncedLrcText.ifBlank { existing?.syncedLrcText.orEmpty() },
                plainLyrics = track.plainLyrics.ifBlank { existing?.plainLyrics.orEmpty() },
                cachedAt = now
            )
            trackDao.upsertTrack(merged)
            downloadedTrackDao.upsert(
                DownloadedTrackEntity(
                    trackId = track.id,
                    localAudioPath = audioFilePath,
                    localArtworkPath = resolvedArt,
                    fileSizeBytes = fileSizeBytes.coerceAtLeast(0L),
                    mimeType = mimeType,
                    downloadedAt = now
                )
            )
            merged.toDomain()
        }
    }

    suspend fun removeOfflineDownloadRecord(trackId: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            trackDao.setDownloadState(trackId, downloaded = false, filePath = "")
            trackDao.updateCachedArtwork(trackId, "")
            downloadedTrackDao.deleteById(trackId)
        }
    }

    suspend fun searchCachedTracks(query: String): List<Track> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()
        trackDao.searchCachedTracks(trimmed).map { it.toDomain() }
    }

    suspend fun verifyOfflineCacheIntegrity() = withContext(Dispatchers.IO) {
        db.withTransaction {
            val downloaded = trackDao.getDownloadedTracksSnapshot()
            downloaded.forEach { entity ->
                val audioExists = entity.downloadedFilePath.isNotBlank() &&
                    File(entity.downloadedFilePath).let { it.exists() && it.length() > 0L }
                if (!audioExists) {
                    trackDao.setDownloadState(entity.id, downloaded = false, filePath = "")
                    downloadedTrackDao.deleteById(entity.id)
                }
            }

            val allSnapshots = trackDao.getAllTracksSnapshot()
            allSnapshots.filter { it.cachedArtworkPath.isNotBlank() }.forEach { entity ->
                val artExists = File(entity.cachedArtworkPath).let { it.exists() && it.length() > 0L }
                if (!artExists) {
                    trackDao.updateCachedArtwork(entity.id, "")
                }
            }

            val entries = downloadedTrackDao.getAllSnapshot()
            val trackIds = allSnapshots.mapTo(HashSet()) { it.id }
            entries.forEach { entry ->
                val fileValid = entry.localAudioPath.isNotBlank() &&
                    File(entry.localAudioPath).let { it.exists() && it.length() > 0L }
                if (!fileValid || entry.trackId !in trackIds) {
                    downloadedTrackDao.deleteById(entry.trackId)
                }
            }
        }
    }

    suspend fun clearRemoteMetadataCache(): Int = withContext(Dispatchers.IO) {
        trackDao.clearRemoteMetadataCache()
    }

    suspend fun getRecentSignals(): List<ListeningSignalEntity> = withContext(Dispatchers.IO) {
        signalDao.getRecentSignals()
    }

    suspend fun insertSignal(signal: ListeningSignalEntity) = withContext(Dispatchers.IO) {
        signalDao.insertSignal(signal)
    }

    private fun mergePreservingLocalFields(
        incoming: Track,
        existing: TrackEntity?,
        now: Long
    ): TrackEntity {
        if (existing == null) {
            return TrackEntity.fromDomain(incoming, now = now)
        }
        return TrackEntity(
            id = incoming.id,
            title = incoming.title.ifBlank { existing.title },
            artist = incoming.artist.ifBlank { existing.artist },
            album = incoming.album.ifBlank { existing.album },
            genre = incoming.genre.ifBlank { existing.genre },
            mood = incoming.mood.ifBlank { existing.mood },
            bpm = if (incoming.bpm > 0) incoming.bpm else existing.bpm,
            durationMs = if (incoming.durationMs > 0L) incoming.durationMs else existing.durationMs,
            artworkUrl = incoming.artworkUrl.ifBlank { existing.artworkUrl },
            streamUrl = incoming.streamUrl.ifBlank { existing.streamUrl },
            localUri = incoming.localUri.ifBlank { existing.localUri },
            downloadedFilePath = incoming.downloadedFilePath.ifBlank { existing.downloadedFilePath },
            cachedArtworkPath = incoming.cachedArtworkPath.ifBlank { existing.cachedArtworkPath },
            sourceType = if (
                existing.sourceType == TrackSourceType.LOCAL_SAF.name ||
                existing.sourceType == TrackSourceType.CLOUD_SAF.name
            ) existing.sourceType else incoming.sourceType.name,
            isDownloaded = incoming.isDownloaded || existing.isDownloaded,
            isLiked = incoming.isLiked,
            syncedLrcText = incoming.syncedLrcText.ifBlank { existing.syncedLrcText },
            plainLyrics = incoming.plainLyrics.ifBlank { existing.plainLyrics },
            playCount = maxOf(incoming.playCount, existing.playCount),
            lastPlayedAt = maxOf(incoming.lastPlayedAt, existing.lastPlayedAt),
            addedAt = existing.addedAt,
            cachedAt = now
        )
    }
}
