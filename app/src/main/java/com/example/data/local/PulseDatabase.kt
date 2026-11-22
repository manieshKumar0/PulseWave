package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.Track
import com.example.data.model.TrackSourceType
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["sourceType"]),
        Index(value = ["isDownloaded"])
    ]
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val mood: String,
    val bpm: Int,
    val durationMs: Long,
    val artworkUrl: String,
    val streamUrl: String,
    val localUri: String,
    val downloadedFilePath: String,
    val cachedArtworkPath: String = "",
    val sourceType: String,
    val isDownloaded: Boolean,
    val isLiked: Boolean,
    val syncedLrcText: String,
    val plainLyrics: String,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0L,
    val addedAt: Long = System.currentTimeMillis(),
    val cachedAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): Track = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        mood = mood,
        bpm = bpm,
        durationMs = durationMs,
        artworkUrl = artworkUrl,
        streamUrl = streamUrl,
        localUri = localUri,
        downloadedFilePath = downloadedFilePath,
        cachedArtworkPath = cachedArtworkPath,
        sourceType = TrackSourceType.entries.firstOrNull { it.name == sourceType } ?: TrackSourceType.STREAM,
        isDownloaded = isDownloaded,
        isLiked = isLiked,
        syncedLrcText = syncedLrcText,
        plainLyrics = plainLyrics,
        playCount = playCount,
        lastPlayedAt = lastPlayedAt
    )

    companion object {
        fun fromDomain(
            track: Track,
            now: Long = System.currentTimeMillis()
        ): TrackEntity = TrackEntity(
            id = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            genre = track.genre,
            mood = track.mood,
            bpm = track.bpm,
            durationMs = track.durationMs,
            artworkUrl = track.artworkUrl,
            streamUrl = track.streamUrl,
            localUri = track.localUri,
            downloadedFilePath = track.downloadedFilePath,
            cachedArtworkPath = track.cachedArtworkPath,
            sourceType = track.sourceType.name,
            isDownloaded = track.isDownloaded,
            isLiked = track.isLiked,
            syncedLrcText = track.syncedLrcText,
            plainLyrics = track.plainLyrics,
            playCount = track.playCount,
            lastPlayedAt = track.lastPlayedAt,
            addedAt = now,
            cachedAt = now
        )
    }
}

@Entity(
    tableName = "downloaded_tracks",
    indices = [Index(value = ["downloadedAt"])]
)
data class DownloadedTrackEntity(
    @PrimaryKey val trackId: String,
    val localAudioPath: String,
    val localArtworkPath: String = "",
    val fileSizeBytes: Long = 0L,
    val mimeType: String = "audio/mpeg",
    val downloadedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "listening_signals")
data class ListeningSignalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val genre: String,
    val mood: String,
    val bpm: Int,
    val completionRatio: Float,
    val earlySkip: Boolean,
    val replayCount: Int,
    val seekBackCount: Int,
    val dwellMs: Long,
    val liked: Boolean,
    val savedOffline: Boolean,
    val sharedInDm: Boolean,
    val timeBucket: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY lastPlayedAt DESC, addedAt DESC")
    fun observeAllTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks")
    suspend fun getAllTracksSnapshot(): List<TrackEntity>

    @Query(
        "SELECT * FROM tracks " +
            "WHERE isDownloaded = 1 OR sourceType = 'LOCAL_SAF' OR sourceType = 'CLOUD_SAF' " +
            "ORDER BY lastPlayedAt DESC, addedAt DESC"
    )
    fun observeOfflinePlayableTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isDownloaded = 1 ORDER BY addedAt DESC")
    fun observeDownloadedTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isDownloaded = 1")
    suspend fun getDownloadedTracksSnapshot(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getTrackById(id: String): TrackEntity?

    @Query(
        "SELECT * FROM tracks " +
            "WHERE title LIKE '%' || :query || '%' " +
            "OR artist LIKE '%' || :query || '%' " +
            "OR album LIKE '%' || :query || '%' " +
            "OR genre LIKE '%' || :query || '%' " +
            "ORDER BY isDownloaded DESC, lastPlayedAt DESC, addedAt DESC " +
            "LIMIT 50"
    )
    suspend fun searchCachedTracks(query: String): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("UPDATE tracks SET isLiked = :liked WHERE id = :trackId")
    suspend fun setLiked(trackId: String, liked: Boolean)

    @Query("UPDATE tracks SET isDownloaded = :downloaded, downloadedFilePath = :filePath WHERE id = :trackId")
    suspend fun setDownloadState(trackId: String, downloaded: Boolean, filePath: String)

    @Query("UPDATE tracks SET syncedLrcText = :syncedLrc, plainLyrics = :plain WHERE id = :trackId")
    suspend fun updateLyrics(trackId: String, syncedLrc: String, plain: String)

    @Query("UPDATE tracks SET artworkUrl = :artworkUrl WHERE id = :trackId")
    suspend fun updateArtwork(trackId: String, artworkUrl: String)

    @Query("UPDATE tracks SET cachedArtworkPath = :path WHERE id = :trackId")
    suspend fun updateCachedArtwork(trackId: String, path: String)

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :playedAt WHERE id = :trackId")
    suspend fun markTrackPlayed(trackId: String, playedAt: Long)

    @Query(
        "DELETE FROM tracks " +
            "WHERE isDownloaded = 0 " +
            "AND sourceType NOT IN ('LOCAL_SAF', 'CLOUD_SAF')"
    )
    suspend fun clearRemoteMetadataCache(): Int

    @Query(
        "DELETE FROM tracks " +
            "WHERE isDownloaded = 0 " +
            "AND sourceType NOT IN ('LOCAL_SAF', 'CLOUD_SAF') " +
            "AND (id LIKE 'audius_%' OR LOWER(streamUrl) LIKE '%audius.co%')"
    )
    suspend fun purgeLegacyAudiusCache(): Int

    @Query("DELETE FROM tracks WHERE id = :trackId")
    suspend fun deleteTrack(trackId: String)

    @Query("DELETE FROM tracks WHERE id IN (:trackIds)")
    suspend fun deleteTracksByIds(trackIds: List<String>)
}

@Dao
interface DownloadedTrackDao {
    @Query("SELECT * FROM downloaded_tracks ORDER BY downloadedAt DESC")
    fun observeAll(): Flow<List<DownloadedTrackEntity>>

    @Query("SELECT * FROM downloaded_tracks")
    suspend fun getAllSnapshot(): List<DownloadedTrackEntity>

    @Query("SELECT COALESCE(SUM(fileSizeBytes), 0) FROM downloaded_tracks")
    fun observeTotalBytes(): Flow<Long>

    @Query("SELECT * FROM downloaded_tracks WHERE trackId = :trackId LIMIT 1")
    suspend fun getById(trackId: String): DownloadedTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DownloadedTrackEntity)

    @Query("DELETE FROM downloaded_tracks WHERE trackId = :trackId")
    suspend fun deleteById(trackId: String)

    @Query("DELETE FROM downloaded_tracks")
    suspend fun deleteAll()
}

@Dao
interface SignalDao {
    @Query("SELECT * FROM listening_signals ORDER BY timestamp DESC LIMIT 400")
    fun observeRecentSignals(): Flow<List<ListeningSignalEntity>>

    @Query("SELECT * FROM listening_signals ORDER BY timestamp DESC LIMIT 400")
    suspend fun getRecentSignals(): List<ListeningSignalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSignal(signal: ListeningSignalEntity)
}

@Database(
    entities = [
        TrackEntity::class,
        DownloadedTrackEntity::class,
        ListeningSignalEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class PulseDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun downloadedTrackDao(): DownloadedTrackDao
    abstract fun signalDao(): SignalDao

    companion object {
        @Volatile
        private var INSTANCE: PulseDatabase? = null

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN cachedArtworkPath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tracks ADD COLUMN playCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN lastPlayedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN cachedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_sourceType ON tracks(sourceType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_isDownloaded ON tracks(isDownloaded)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS downloaded_tracks (
                        trackId TEXT NOT NULL PRIMARY KEY,
                        localAudioPath TEXT NOT NULL,
                        localArtworkPath TEXT NOT NULL DEFAULT '',
                        fileSizeBytes INTEGER NOT NULL DEFAULT 0,
                        mimeType TEXT NOT NULL DEFAULT 'audio/mpeg',
                        downloadedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_downloaded_tracks_downloadedAt ON downloaded_tracks(downloadedAt)"
                )
            }
        }

        fun getInstance(context: Context): PulseDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PulseDatabase::class.java,
                    "pulsewave_local.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration(false)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
