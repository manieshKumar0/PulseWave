package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LocalLibraryRepository
import com.example.data.local.PulseDatabase
import com.example.data.model.Track
import com.example.data.model.TrackSourceType
import com.example.domain.SafMusicImporter
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceAudioScannerTest {

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
    fun isEligibleDeviceAudio_requiresStrictlyLongerThanTwoMinutesAndExcludesAlerts() {
        assertFalse(
            SafMusicImporter.isEligibleDeviceAudio(
                durationMs = 90_000L,
                isMusic = 1,
                isNotification = 0,
                isAlarm = 0,
                isRingtone = 0,
                mimeType = "audio/mpeg",
                displayName = "ninety_seconds.mp3"
            )
        )
        assertFalse(
            SafMusicImporter.isEligibleDeviceAudio(
                durationMs = 120_000L,
                isMusic = 1,
                isNotification = 0,
                isAlarm = 0,
                isRingtone = 0,
                mimeType = "audio/mpeg",
                displayName = "exact_two_minutes.mp3"
            )
        )
        assertTrue(
            SafMusicImporter.isEligibleDeviceAudio(
                durationMs = 120_001L,
                isMusic = 1,
                isNotification = 0,
                isAlarm = 0,
                isRingtone = 0,
                mimeType = "audio/mpeg",
                displayName = "just_over_two_minutes.mp3"
            )
        )
        assertTrue(
            SafMusicImporter.isEligibleDeviceAudio(
                durationMs = 210_000L,
                isMusic = 0,
                isNotification = 0,
                isAlarm = 0,
                isRingtone = 0,
                mimeType = "audio/mpeg",
                displayName = "Downloaded - Song.mp3"
            )
        )
        assertFalse(
            SafMusicImporter.isEligibleDeviceAudio(
                durationMs = 180_000L,
                isMusic = 1,
                isNotification = 1,
                isAlarm = 0,
                isRingtone = 0
            )
        )
        assertFalse(
            SafMusicImporter.isEligibleDeviceAudio(
                durationMs = 180_000L,
                isMusic = 1,
                isNotification = 0,
                isAlarm = 1,
                isRingtone = 0
            )
        )
        assertFalse(
            SafMusicImporter.isEligibleDeviceAudio(
                durationMs = 180_000L,
                isMusic = 0,
                isNotification = 0,
                isAlarm = 0,
                isRingtone = 1,
                mimeType = "audio/ogg",
                displayName = "long_ringtone.ogg"
            )
        )
    }

    @Test
    fun syncScannedDeviceTracks_preservesLikesAndLyricsAndPrunesDeletedDeviceFilesOnly() = runTest {
        val manualSaf = Track(
            id = "saf_manual_1",
            title = "Manual Import",
            artist = "Artist A",
            localUri = "content://saf/1",
            sourceType = TrackSourceType.LOCAL_SAF
        )
        val firstScanA = Track(
            id = "device_ms_101",
            title = "Night Drive",
            artist = "Local Device",
            durationMs = 195_000L,
            localUri = "content://media/external/audio/media/101",
            sourceType = TrackSourceType.LOCAL_SAF
        )
        val firstScanB = Track(
            id = "device_ms_102",
            title = "Old Song To Delete",
            artist = "Artist B",
            durationMs = 140_000L,
            localUri = "content://media/external/audio/media/102",
            sourceType = TrackSourceType.LOCAL_SAF
        )
        repo.upsertAll(listOf(manualSaf, firstScanA, firstScanB))
        repo.setLiked(firstScanA, true)
        repo.updateLyrics("device_ms_101", "[00:01.00]Lyrics line", "Lyrics line")
        repo.recordTrackPlayed(firstScanA)

        // Second scan removes 102, keeps 101 (with raw MediaStore metadata), adds 103
        val secondScanA = firstScanA.copy(isLiked = false, syncedLrcText = "", playCount = 0)
        val secondScanC = Track(
            id = "device_ms_103",
            title = "Fresh MP3",
            artist = "Artist C",
            durationMs = 240_000L,
            localUri = "content://media/external/audio/media/103",
            sourceType = TrackSourceType.LOCAL_SAF
        )
        repo.syncScannedDeviceTracks(listOf(secondScanA, secondScanC))

        val preservedA = repo.getTrackById("device_ms_101")
        assertNotNull(preservedA)
        assertTrue(preservedA!!.isLiked)
        assertEquals("[00:01.00]Lyrics line", preservedA.syncedLrcText)
        assertEquals(1, preservedA.playCount)

        assertNull(repo.getTrackById("device_ms_102"))
        assertNotNull(repo.getTrackById("device_ms_103"))
        assertNotNull(repo.getTrackById("saf_manual_1"))
    }

    @Test
    fun scanDeviceAudio_returnsEmptySafelyWithoutPermission() = runTest {
        val importer = SafMusicImporter(context)
        val tracks = importer.scanDeviceAudio()
        assertTrue(tracks.isEmpty())
    }
}
