package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LocalLibraryRepository
import com.example.data.local.PulseDatabase
import com.example.data.model.Track
import com.example.data.model.TrackSourceType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalLibraryRoomTest {

    private lateinit var context: Context
    private lateinit var db: PulseDatabase
    private lateinit var repo: LocalLibraryRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = LocalLibraryRepository(db)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun cacheRemoteCatalog_preservesOfflineDownloadAndLyricsState() = runTest {
        val audioFile = File(context.cacheDir, "offline_song.mp3").apply {
            writeText("fake-audio-bytes")
        }
        val artFile = File(context.cacheDir, "offline_art.jpg").apply {
            writeText("fake-art-bytes")
        }

        val initial = Track(
            id = "track-1",
            title = "Midnight Wave",
            artist = "Arijit",
            album = "Afterglow",
            genre = "Synthwave",
            streamUrl = "https://example.com/stream.mp3",
            artworkUrl = "https://example.com/art.jpg",
            syncedLrcText = "[00:01.00]Tum hi ho",
            isLiked = true
        )

        repo.markTrackDownloaded(
            track = initial,
            audioFilePath = audioFile.absolutePath,
            artworkFilePath = artFile.absolutePath,
            fileSizeBytes = audioFile.length() + artFile.length()
        )
        repo.recordTrackPlayed(initial)

        // Incoming remote catalog refresh lacks downloaded path, liked flag, and lyrics
        val remoteRefresh = Track(
            id = "track-1",
            title = "Midnight Wave (Remastered)",
            artist = "Arijit",
            album = "Afterglow",
            genre = "Electronic",
            streamUrl = "https://example.com/stream-v2.mp3",
            artworkUrl = "https://example.com/art-v2.jpg",
            isDownloaded = false,
            isLiked = false,
            syncedLrcText = ""
        )
        val extraRemote = Track(
            id = "track-2",
            title = "Neon Rain",
            artist = "Pulse",
            genre = "Lo-Fi",
            streamUrl = "https://example.com/rain.mp3"
        )

        repo.cacheRemoteCatalog(listOf(remoteRefresh, extraRemote))

        val stored = repo.getTrackById("track-1")
        assertNotNull(stored)
        assertEquals("Midnight Wave (Remastered)", stored!!.title)
        assertEquals("Electronic", stored.genre)
        assertTrue(stored.isDownloaded)
        assertEquals(audioFile.absolutePath, stored.downloadedFilePath)
        assertEquals(artFile.absolutePath, stored.cachedArtworkPath)
        assertEquals("file://${artFile.absolutePath}", stored.displayArtworkUri)
        assertEquals("file://${audioFile.absolutePath}", stored.playableUri)
        assertTrue(stored.isLiked)
        assertEquals("[00:01.00]Tum hi ho", stored.syncedLrcText)
        assertEquals(1, stored.playCount)

        val summary = repo.cacheSummary.first()
        assertEquals(2, summary.cachedTrackCount)
        assertEquals(1, summary.downloadedCount)
        assertTrue(summary.totalBytesUsed > 0L)

        audioFile.delete()
        artFile.delete()
    }

    @Test
    fun searchAndClearRemoteMetadataCache_keepsOfflineAndLocalTracks() = runTest {
        val audioFile = File(context.cacheDir, "kept_offline.mp3").apply {
            writeText("audio-content")
        }

        val downloadedTrack = Track(
            id = "dl-1",
            title = "Kesariya",
            artist = "Pritam",
            genre = "Bollywood",
            streamUrl = "https://example.com/k.mp3"
        )
        repo.markTrackDownloaded(
            track = downloadedTrack,
            audioFilePath = audioFile.absolutePath,
            artworkFilePath = "",
            fileSizeBytes = audioFile.length()
        )

        val localSafTrack = Track(
            id = "saf-1",
            title = "Voice Memo Remix",
            artist = "Local Device",
            localUri = "content://media/audio/1",
            sourceType = TrackSourceType.LOCAL_SAF
        )
        val remoteOnlyTrack = Track(
            id = "rem-1",
            title = "Kesariya Live Acoustic",
            artist = "Arijit",
            genre = "Acoustic",
            streamUrl = "https://example.com/live.mp3",
            sourceType = TrackSourceType.STREAM
        )
        repo.upsertAll(listOf(localSafTrack, remoteOnlyTrack))

        val searchResults = repo.searchCachedTracks("Kesariya")
        assertEquals(2, searchResults.size)
        // Downloaded tracks should rank ahead of stream-only cached metadata
        assertEquals("dl-1", searchResults.first().id)

        val removed = repo.clearRemoteMetadataCache()
        assertEquals(1, removed)

        val remainingIds = repo.allTracks.first().map { it.id }.toSet()
        assertTrue("dl-1" in remainingIds)
        assertTrue("saf-1" in remainingIds)
        assertFalse("rem-1" in remainingIds)

        audioFile.delete()
    }

    @Test
    fun verifyOfflineCacheIntegrity_clearsStaleDeletedFileFlags() = runTest {
        val missingAudioPath = File(context.cacheDir, "deleted_file_12345.mp3").absolutePath
        val missingArtPath = File(context.cacheDir, "deleted_art_12345.jpg").absolutePath

        repo.markTrackDownloaded(
            track = Track(id = "stale-1", title = "Ghost", artist = "Unknown"),
            audioFilePath = missingAudioPath,
            artworkFilePath = missingArtPath,
            fileSizeBytes = 1024L
        )

        repo.verifyOfflineCacheIntegrity()

        val cleaned = repo.getTrackById("stale-1")
        assertNotNull(cleaned)
        assertFalse(cleaned!!.isDownloaded)
        assertEquals("", cleaned.downloadedFilePath)
        assertEquals("", cleaned.cachedArtworkPath)
        assertEquals(0L, repo.cacheSummary.first().totalBytesUsed)
    }
}
