package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val handle: String = "",
    val avatarUrl: String = "",
    val bio: String = "",
    val discoverable: Boolean = true,
    val topGenres: List<String> = emptyList(),
    val sonicEnergy: Long = 70L,
    val sonicValence: Long = 62L,
    val sonicTempo: Long = 68L,
    val sonicAcoustic: Long = 35L,
    val sonicElectronic: Long = 80L,
    val sonicChill: Long = 55L,
    val listeningMinutes: Long = 0L,
    val streakDays: Long = 1L,
    val nowPlayingTitle: String = "",
    val nowPlayingArtist: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> = mapOf(
        "userId" to userId,
        "displayName" to displayName.take(60).ifBlank { "Listener" },
        "handle" to handle.take(30).ifBlank { "pulse_listener" },
        "avatarUrl" to avatarUrl.take(500),
        "bio" to bio.take(240),
        "discoverable" to discoverable,
        "topGenres" to topGenres.take(8),
        "sonicEnergy" to sonicEnergy.coerceIn(0L, 100L),
        "sonicValence" to sonicValence.coerceIn(0L, 100L),
        "sonicTempo" to sonicTempo.coerceIn(0L, 100L),
        "sonicAcoustic" to sonicAcoustic.coerceIn(0L, 100L),
        "sonicElectronic" to sonicElectronic.coerceIn(0L, 100L),
        "sonicChill" to sonicChill.coerceIn(0L, 100L),
        "listeningMinutes" to listeningMinutes.coerceAtLeast(0L),
        "streakDays" to streakDays.coerceAtLeast(0L),
        "nowPlayingTitle" to nowPlayingTitle.take(120),
        "nowPlayingArtist" to nowPlayingArtist.take(120),
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@Keep
@IgnoreExtraProperties
data class SyncedTrackDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val trackId: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
    val mood: String = "",
    val bpm: Long = 118L,
    val durationMs: Long = 0L,
    val artworkUrl: String = "",
    val streamUrl: String = "",
    val sourceType: String = "STREAM",
    val liked: Boolean = false,
    val playCount: Long = 0L,
    val lastPositionMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FriendRequestDoc(
    @DocumentId val id: String = "",
    val fromUid: String = "",
    val fromName: String = "",
    val fromHandle: String = "",
    val toUid: String = "",
    val toName: String = "",
    val toHandle: String = "",
    val participantUids: List<String> = emptyList(),
    val status: String = "PENDING",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FeedPostDoc(
    @DocumentId val id: String = "",
    val authorUid: String = "",
    val authorName: String = "",
    val authorHandle: String = "",
    val visibility: String = "PUBLIC",
    val postType: String = "TRACK",
    val trackId: String = "",
    val trackTitle: String = "",
    val artist: String = "",
    val artworkUrl: String = "",
    val streamUrl: String = "",
    val caption: String = "",
    val lyricQuote: String = "",
    val soundscapeLabel: String = "",
    val likeUids: List<String> = emptyList(),
    val commentCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FeedCommentDoc(
    @DocumentId val id: String = "",
    val postId: String = "",
    val postVisibility: String = "PUBLIC",
    val authorUid: String = "",
    val authorName: String = "",
    val text: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ConversationDoc(
    @DocumentId val id: String = "",
    val participantUids: List<String> = emptyList(),
    val participantLabel: String = "",
    val lastMessageText: String = "",
    val lastSenderUid: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class DirectMessageDoc(
    @DocumentId val id: String = "",
    val senderUid: String = "",
    val senderName: String = "",
    val participantUids: List<String> = emptyList(),
    val text: String = "",
    val attachmentType: String = "NONE",
    val attachedId: String = "",
    val attachedTitle: String = "",
    val attachedSubtitle: String = "",
    val attachedArtworkUrl: String = "",
    val attachedStreamUrl: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PlaylistTrackItemMap(
    val trackId: String = "",
    val title: String = "",
    val artist: String = "",
    val artworkUrl: String = "",
    val streamUrl: String = "",
    val durationMs: Long = 180_000L,
    val addedByUid: String = "",
    val upvotes: Long = 1L
)

@Keep
@IgnoreExtraProperties
data class CollaborativePlaylistDoc(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val ownerName: String = "",
    val collaboratorUids: List<String> = emptyList(),
    val title: String = "",
    val description: String = "",
    val collaborative: Boolean = true,
    val discoverable: Boolean = false,
    val trackItems: List<Map<String, Any>> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun parsedTracks(): List<PlaylistTrackItemMap> = trackItems.mapNotNull { item ->
        val tid = item["trackId"] as? String ?: return@mapNotNull null
        PlaylistTrackItemMap(
            trackId = tid,
            title = item["title"] as? String ?: "Untitled",
            artist = item["artist"] as? String ?: "Unknown",
            artworkUrl = item["artworkUrl"] as? String ?: "",
            streamUrl = item["streamUrl"] as? String ?: "",
            durationMs = (item["durationMs"] as? Number)?.toLong() ?: 180_000L,
            addedByUid = item["addedByUid"] as? String ?: "",
            upvotes = (item["upvotes"] as? Number)?.toLong() ?: 1L
        )
    }
}

@Keep
@IgnoreExtraProperties
data class PulseRoomDoc(
    @DocumentId val id: String = "",
    val hostUid: String = "",
    val hostName: String = "",
    val roomName: String = "",
    val moodTag: String = "Chillwave",
    val active: Boolean = true,
    val memberUids: List<String> = emptyList(),
    val currentTrackId: String = "",
    val trackTitle: String = "",
    val artist: String = "",
    val artworkUrl: String = "",
    val streamUrl: String = "",
    val playing: Boolean = true,
    val positionMs: Long = 0L,
    val soundscapePreset: String = "Obsidian Studio",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class RoomQueueItemDoc(
    @DocumentId val id: String = "",
    val roomActive: Boolean = true,
    val trackId: String = "",
    val title: String = "",
    val artist: String = "",
    val artworkUrl: String = "",
    val streamUrl: String = "",
    val addedByUid: String = "",
    val addedByName: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class RoomReactionDoc(
    @DocumentId val id: String = "",
    val roomActive: Boolean = true,
    val senderUid: String = "",
    val senderName: String = "",
    val emoji: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class LyricNoteDoc(
    @DocumentId val id: String = "",
    val trackKey: String = "",
    val authorUid: String = "",
    val authorName: String = "",
    val timestampMs: Long = 0L,
    val noteText: String = "",
    val shared: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
