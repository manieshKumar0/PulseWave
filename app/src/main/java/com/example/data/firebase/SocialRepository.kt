package com.example.data.firebase

import android.content.Context
import com.example.R
import com.example.data.model.CollaborativePlaylistDoc
import com.example.data.model.ConversationDoc
import com.example.data.model.DirectMessageDoc
import com.example.data.model.FeedCommentDoc
import com.example.data.model.FeedPostDoc
import com.example.data.model.FriendRequestDoc
import com.example.data.model.LyricNoteDoc
import com.example.data.model.PlaylistTrackItemMap
import com.example.data.model.PulseRoomDoc
import com.example.data.model.RoomQueueItemDoc
import com.example.data.model.RoomReactionDoc
import com.example.data.model.SonicDnaProfile
import com.example.data.model.SyncedTrackDoc
import com.example.data.model.Track
import com.example.data.model.UserProfile
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot.ServerTimestampBehavior
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class SocialRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("Google Sign-In required")

    private fun sanitizeDocId(raw: String): String =
        raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(120).ifBlank { "doc_default" }

    private fun <T> Flow<T>.wrapFirestoreFlow(op: OperationType, path: String, uid: String): Flow<T> =
        this.catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val toLog = firestoreError ?: (error as? Exception)
            if (toLog != null && auth.currentUser?.uid == uid) {
                handleFirestoreError(toLog, op, path)
            }
            throw error
        }

    // --- 1. User Profiles & Sonic DNA ---

    suspend fun ensureUserProfile(
        displayName: String,
        avatarUrl: String,
        sonicDna: SonicDnaProfile
    ): Result<UserProfile> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val path = "users/$uid"
        return try {
            val docRef = db.collection("users").document(uid)
            val snap = docRef.get().await()
            if (!snap.exists()) {
                val safeName = displayName.trim().take(60).ifBlank { "Pulse Listener" }
                val baseHandle = safeName.lowercase().replace(Regex("[^a-z0-9]"), "_").take(20).ifBlank { "listener" }
                val profile = UserProfile(
                    id = uid,
                    userId = uid,
                    displayName = safeName,
                    handle = "${baseHandle}_${uid.take(4)}",
                    avatarUrl = avatarUrl.take(500),
                    bio = "Exploring soundscapes on PulseWave",
                    discoverable = true,
                    topGenres = sonicDna.topGenres.take(8),
                    sonicEnergy = sonicDna.energy.toLong(),
                    sonicValence = sonicDna.valence.toLong(),
                    sonicTempo = sonicDna.tempo.toLong(),
                    sonicAcoustic = sonicDna.acoustic.toLong(),
                    sonicElectronic = sonicDna.electronic.toLong(),
                    sonicChill = sonicDna.chill.toLong(),
                    listeningMinutes = (sonicDna.totalPlays * 3L),
                    streakDays = 1L
                )
                docRef.set(profile.toCreateMap()).await()
                Result.success(profile)
            } else {
                val existing = snap.toObject(UserProfile::class.java, ServerTimestampBehavior.ESTIMATE)
                    ?: UserProfile(id = uid, userId = uid, displayName = displayName)
                val safeIncoming = displayName.trim().take(60)
                if (safeIncoming.isNotBlank() &&
                    !safeIncoming.equals("Pulse Listener", ignoreCase = true) &&
                    (existing.displayName.isBlank() || existing.displayName.equals("Pulse Listener", ignoreCase = true))
                ) {
                    docRef.update(
                        mapOf(
                            "displayName" to safeIncoming,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                    Result.success(existing.copy(displayName = safeIncoming))
                } else {
                    Result.success(existing)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun updateSonicDnaAndNowPlaying(
        sonicDna: SonicDnaProfile,
        nowPlayingTrack: Track?
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val path = "users/$uid"
        return try {
            val updates = mutableMapOf<String, Any>(
                "topGenres" to sonicDna.topGenres.take(8),
                "sonicEnergy" to sonicDna.energy.toLong().coerceIn(0L, 100L),
                "sonicValence" to sonicDna.valence.toLong().coerceIn(0L, 100L),
                "sonicTempo" to sonicDna.tempo.toLong().coerceIn(0L, 100L),
                "sonicAcoustic" to sonicDna.acoustic.toLong().coerceIn(0L, 100L),
                "sonicElectronic" to sonicDna.electronic.toLong().coerceIn(0L, 100L),
                "sonicChill" to sonicDna.chill.toLong().coerceIn(0L, 100L),
                "listeningMinutes" to (sonicDna.totalPlays * 3L).coerceAtLeast(0L),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (nowPlayingTrack != null) {
                updates["nowPlayingTitle"] = nowPlayingTrack.title.take(120)
                updates["nowPlayingArtist"] = nowPlayingTrack.artist.take(120)
            }
            db.collection("users").document(uid).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    fun observeDiscoverableUsers(): Flow<List<UserProfile>> = flow {
        val uid = requireUserId()
        emitAll(
            db.collection("users")
                .whereEqualTo("discoverable", true)
                .snapshots()
                .map { snap -> snap.toObjects(UserProfile::class.java, ServerTimestampBehavior.ESTIMATE) }
                .wrapFirestoreFlow(OperationType.LIST, "users", uid)
        )
    }

    // --- 2. Private Cloud Library Sync ---

    fun observeSyncedLibrary(): Flow<List<SyncedTrackDoc>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/syncedLibrary"
        emitAll(
            db.collection("users").document(uid).collection("syncedLibrary")
                .snapshots()
                .map { snap -> snap.toObjects(SyncedTrackDoc::class.java, ServerTimestampBehavior.ESTIMATE) }
                .wrapFirestoreFlow(OperationType.LIST, path, uid)
        )
    }

    suspend fun syncTrackToCloud(track: Track, liked: Boolean, playCountDelta: Long = 0L, positionMs: Long = 0L): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val docId = sanitizeDocId(track.id)
        val path = "users/$uid/syncedLibrary/$docId"
        return try {
            val ref = db.collection("users").document(uid).collection("syncedLibrary").document(docId)
            val snap = ref.get().await()
            if (!snap.exists()) {
                val payload = mapOf(
                    "userId" to uid,
                    "trackId" to track.id.take(128),
                    "title" to track.title.take(160).ifBlank { "Untitled" },
                    "artist" to track.artist.take(120).ifBlank { "Artist" },
                    "album" to track.album.take(160),
                    "genre" to track.genre.take(60),
                    "mood" to track.mood.take(60),
                    "bpm" to track.bpm.coerceIn(0, 300).toLong(),
                    "durationMs" to track.durationMs.coerceAtLeast(0L),
                    "artworkUrl" to track.artworkUrl.take(600),
                    "streamUrl" to track.streamUrl.take(600),
                    "sourceType" to track.sourceType.name,
                    "liked" to liked,
                    "playCount" to playCountDelta.coerceAtLeast(0L),
                    "lastPositionMs" to positionMs.coerceAtLeast(0L),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                ref.set(payload).await()
            } else {
                val existingPlays = snap.getLong("playCount") ?: 0L
                ref.update(
                    mapOf(
                        "liked" to liked,
                        "playCount" to (existingPlays + playCountDelta).coerceAtLeast(0L),
                        "lastPositionMs" to positionMs.coerceAtLeast(0L),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    // --- 3. Friends & Requests ---

    fun observeFriendRequests(): Flow<List<FriendRequestDoc>> = flow {
        val uid = requireUserId()
        emitAll(
            db.collection("friendRequests")
                .whereArrayContains("participantUids", uid)
                .snapshots()
                .map { snap -> snap.toObjects(FriendRequestDoc::class.java, ServerTimestampBehavior.ESTIMATE) }
                .wrapFirestoreFlow(OperationType.LIST, "friendRequests", uid)
        )
    }

    suspend fun sendFriendRequest(from: UserProfile, target: UserProfile): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        if (target.userId == uid) return Result.failure(IllegalArgumentException("Cannot add yourself"))
        val sorted = listOf(uid, target.userId).sorted()
        val reqId = sanitizeDocId("fr_${sorted[0]}_${sorted[1]}")
        val path = "friendRequests/$reqId"
        return try {
            val payload = mapOf(
                "fromUid" to uid,
                "fromName" to from.displayName.take(60).ifBlank { "Listener" },
                "fromHandle" to from.handle.take(30).ifBlank { "listener" },
                "toUid" to target.userId,
                "toName" to target.displayName.take(60).ifBlank { "Friend" },
                "toHandle" to target.handle.take(30).ifBlank { "friend" },
                "participantUids" to sorted,
                "status" to "PENDING",
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("friendRequests").document(reqId).set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun acceptFriendRequest(requestId: String): Result<Unit> {
        val path = "friendRequests/$requestId"
        return try {
            requireUserId()
            db.collection("friendRequests").document(requestId).update(
                mapOf(
                    "status" to "ACCEPTED",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    // --- 4. Social Profile Feed Posts & Comments ---

    fun observePublicFeedPosts(): Flow<List<FeedPostDoc>> = flow {
        val uid = requireUserId()
        emitAll(
            db.collection("feedPosts")
                .whereEqualTo("visibility", "PUBLIC")
                .snapshots()
                .map { snap ->
                    snap.toObjects(FeedPostDoc::class.java, ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .wrapFirestoreFlow(OperationType.LIST, "feedPosts", uid)
        )
    }

    suspend fun publishFeedPost(
        author: UserProfile,
        track: Track,
        caption: String,
        postType: String = "TRACK",
        lyricQuote: String = "",
        soundscapeLabel: String = ""
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val docRef = db.collection("feedPosts").document()
        return try {
            val payload = mapOf(
                "authorUid" to uid,
                "authorName" to author.displayName.take(60).ifBlank { "Listener" },
                "authorHandle" to author.handle.take(30).ifBlank { "listener" },
                "visibility" to "PUBLIC",
                "postType" to postType,
                "trackId" to track.id.take(128),
                "trackTitle" to track.title.take(160),
                "artist" to track.artist.take(120),
                "artworkUrl" to track.artworkUrl.take(600),
                "streamUrl" to track.streamUrl.take(600),
                "caption" to caption.trim().take(400),
                "lyricQuote" to lyricQuote.trim().take(300),
                "soundscapeLabel" to soundscapeLabel.trim().take(80),
                "likeUids" to emptyList<String>(),
                "commentCount" to 0L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun toggleFeedPostLike(post: FeedPostDoc): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val path = "feedPosts/${post.id}"
        return try {
            val updatedLikes = if (uid in post.likeUids) {
                post.likeUids - uid
            } else {
                (post.likeUids + uid).distinct().take(200)
            }
            db.collection("feedPosts").document(post.id).update(
                mapOf(
                    "likeUids" to updatedLikes,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    fun observePostComments(postId: String): Flow<List<FeedCommentDoc>> = flow {
        val uid = requireUserId()
        val path = "feedPosts/$postId/comments"
        emitAll(
            db.collection("feedPosts").document(postId).collection("comments")
                .whereEqualTo("postVisibility", "PUBLIC")
                .snapshots()
                .map { snap ->
                    snap.toObjects(FeedCommentDoc::class.java, ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.createdAt?.seconds ?: 0L }
                }
                .wrapFirestoreFlow(OperationType.LIST, path, uid)
        )
    }

    suspend fun addPostComment(post: FeedPostDoc, authorName: String, text: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val trimmed = text.trim().take(300)
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Comment cannot be empty"))
        val commentRef = db.collection("feedPosts").document(post.id).collection("comments").document()
        return try {
            val batch = db.batch()
            batch.set(
                commentRef,
                mapOf(
                    "postId" to post.id,
                    "postVisibility" to post.visibility,
                    "authorUid" to uid,
                    "authorName" to authorName.take(60).ifBlank { "Listener" },
                    "text" to trimmed,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            )
            batch.update(
                db.collection("feedPosts").document(post.id),
                mapOf(
                    "commentCount" to FieldValue.increment(1L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, commentRef.path)
            Result.failure(e)
        }
    }

    // --- 5. Direct Messaging (1:1 Chat + Playable Music Cards) ---

    fun observeConversations(): Flow<List<ConversationDoc>> = flow {
        val uid = requireUserId()
        emitAll(
            db.collection("conversations")
                .whereArrayContains("participantUids", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(ConversationDoc::class.java, ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.updatedAt?.seconds ?: 0L }
                }
                .wrapFirestoreFlow(OperationType.LIST, "conversations", uid)
        )
    }

    fun observeMessages(conversationId: String): Flow<List<DirectMessageDoc>> = flow {
        val uid = requireUserId()
        val path = "conversations/$conversationId/messages"
        emitAll(
            db.collection("conversations").document(conversationId).collection("messages")
                .whereArrayContains("participantUids", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(DirectMessageDoc::class.java, ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.createdAt?.seconds ?: 0L }
                }
                .wrapFirestoreFlow(OperationType.LIST, path, uid)
        )
    }

    suspend fun sendDirectMessage(
        sender: UserProfile,
        recipient: UserProfile,
        text: String,
        attachedTrack: Track? = null,
        attachmentType: String = if (attachedTrack != null) "TRACK" else "NONE",
        attachedPlaylistId: String = "",
        attachedPlaylistTitle: String = "",
        attachedPresetName: String = ""
    ): Result<String> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val sorted = listOf(uid, recipient.userId).sorted()
        val convId = sanitizeDocId("dm_${sorted[0]}_${sorted[1]}")
        val convRef = db.collection("conversations").document(convId)
        val msgRef = convRef.collection("messages").document()
        val preview = when {
            text.isNotBlank() -> text.trim().take(500)
            attachedTrack != null -> "Shared track: ${attachedTrack.title}"
            attachedPlaylistTitle.isNotBlank() -> "Shared playlist: $attachedPlaylistTitle"
            attachedPresetName.isNotBlank() -> "Shared soundscape: $attachedPresetName"
            else -> "Sent a pulse"
        }

        return try {
            val snap = convRef.get().await()
            if (!snap.exists()) {
                convRef.set(
                    mapOf(
                        "participantUids" to sorted,
                        "participantLabel" to "${sender.displayName.take(40)} & ${recipient.displayName.take(40)}",
                        "lastMessageText" to preview,
                        "lastSenderUid" to uid,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                convRef.update(
                    mapOf(
                        "lastMessageText" to preview,
                        "lastSenderUid" to uid,
                        "participantLabel" to "${sender.displayName.take(40)} & ${recipient.displayName.take(40)}",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            val msgPayload = mutableMapOf<String, Any>(
                "senderUid" to uid,
                "senderName" to sender.displayName.take(60).ifBlank { "Listener" },
                "participantUids" to sorted,
                "text" to text.trim().take(500),
                "attachmentType" to attachmentType,
                "createdAt" to FieldValue.serverTimestamp()
            )
            when (attachmentType) {
                "TRACK" -> if (attachedTrack != null) {
                    msgPayload["attachedId"] = attachedTrack.id.take(128)
                    msgPayload["attachedTitle"] = attachedTrack.title.take(160)
                    msgPayload["attachedSubtitle"] = attachedTrack.artist.take(160)
                    msgPayload["attachedArtworkUrl"] = attachedTrack.artworkUrl.take(600)
                    msgPayload["attachedStreamUrl"] = attachedTrack.streamUrl.take(600)
                }
                "PLAYLIST" -> {
                    msgPayload["attachedId"] = attachedPlaylistId.take(128)
                    msgPayload["attachedTitle"] = attachedPlaylistTitle.take(160)
                    msgPayload["attachedSubtitle"] = "Collaborative Playlist"
                }
                "SOUNDSCAPE" -> {
                    msgPayload["attachedTitle"] = attachedPresetName.take(160)
                    msgPayload["attachedSubtitle"] = "DSP Soundscape Preset"
                }
            }
            msgRef.set(msgPayload).await()
            Result.success(convId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, msgRef.path)
            Result.failure(e)
        }
    }

    // --- 6. Real-Time Collaborative Playlists ---

    fun observeCollaborativePlaylists(): Flow<List<CollaborativePlaylistDoc>> = flow {
        val uid = requireUserId()
        emitAll(
            db.collection("playlists")
                .whereArrayContains("collaboratorUids", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(CollaborativePlaylistDoc::class.java, ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.updatedAt?.seconds ?: 0L }
                }
                .wrapFirestoreFlow(OperationType.LIST, "playlists", uid)
        )
    }

    suspend fun createCollaborativePlaylist(
        owner: UserProfile,
        title: String,
        description: String,
        collaboratorUids: List<String>,
        initialTracks: List<Track> = emptyList(),
        discoverable: Boolean = true
    ): Result<String> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val docRef = db.collection("playlists").document()
        val collabs = (listOf(uid) + collaboratorUids).distinct().take(30)
        val items = initialTracks.take(50).map { t ->
            mapOf(
                "trackId" to t.id.take(128),
                "title" to t.title.take(160),
                "artist" to t.artist.take(120),
                "artworkUrl" to t.artworkUrl.take(600),
                "streamUrl" to t.streamUrl.take(600),
                "durationMs" to t.durationMs,
                "addedByUid" to uid,
                "upvotes" to 1L
            )
        }
        return try {
            docRef.set(
                mapOf(
                    "ownerUid" to uid,
                    "ownerName" to owner.displayName.take(60).ifBlank { "Listener" },
                    "collaboratorUids" to collabs,
                    "title" to title.trim().take(80).ifBlank { "Shared Pulse Mix" },
                    "description" to description.trim().take(240),
                    "collaborative" to true,
                    "discoverable" to discoverable,
                    "trackItems" to items,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun addTrackToCollaborativePlaylist(playlist: CollaborativePlaylistDoc, track: Track): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val path = "playlists/${playlist.id}"
        return try {
            val current = playlist.trackItems.toMutableList()
            if (current.none { it["trackId"] == track.id } && current.size < 50) {
                current.add(
                    mapOf(
                        "trackId" to track.id.take(128),
                        "title" to track.title.take(160),
                        "artist" to track.artist.take(120),
                        "artworkUrl" to track.artworkUrl.take(600),
                        "streamUrl" to track.streamUrl.take(600),
                        "durationMs" to track.durationMs,
                        "addedByUid" to uid,
                        "upvotes" to 1L
                    )
                )
                db.collection("playlists").document(playlist.id).update(
                    mapOf(
                        "trackItems" to current,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun upvotePlaylistTrack(playlist: CollaborativePlaylistDoc, trackId: String): Result<Unit> {
        requireUserId()
        val path = "playlists/${playlist.id}"
        return try {
            val updated = playlist.trackItems.map { item ->
                if (item["trackId"] == trackId) {
                    val votes = (item["upvotes"] as? Number)?.toLong() ?: 1L
                    item.toMutableMap().apply { put("upvotes", votes + 1L) }
                } else item
            }
            db.collection("playlists").document(playlist.id).update(
                mapOf(
                    "trackItems" to updated,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun removeTrackFromCollaborativePlaylist(
        playlist: CollaborativePlaylistDoc,
        trackId: String
    ): Result<Unit> {
        requireUserId()
        val path = "playlists/${playlist.id}"
        return try {
            val updated = playlist.trackItems.filterNot { it["trackId"] == trackId }
            if (updated.size != playlist.trackItems.size) {
                db.collection("playlists").document(playlist.id).update(
                    mapOf(
                        "trackItems" to updated,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    // --- 7. Live Co-Listening PulseRooms ---

    fun observeActivePulseRooms(): Flow<List<PulseRoomDoc>> = flow {
        val uid = requireUserId()
        emitAll(
            db.collection("pulseRooms")
                .whereEqualTo("active", true)
                .snapshots()
                .map { snap ->
                    snap.toObjects(PulseRoomDoc::class.java, ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.updatedAt?.seconds ?: 0L }
                }
                .wrapFirestoreFlow(OperationType.LIST, "pulseRooms", uid)
        )
    }

    fun observePulseRoom(roomId: String): Flow<PulseRoomDoc?> = flow {
        val uid = requireUserId()
        val path = "pulseRooms/$roomId"
        emitAll(
            db.collection("pulseRooms").document(roomId)
                .snapshots()
                .map { snap -> snap.toObject(PulseRoomDoc::class.java, ServerTimestampBehavior.ESTIMATE) }
                .wrapFirestoreFlow(OperationType.GET, path, uid)
        )
    }

    fun observeRoomQueue(roomId: String): Flow<List<RoomQueueItemDoc>> = flow {
        val uid = requireUserId()
        val path = "pulseRooms/$roomId/queue"
        emitAll(
            db.collection("pulseRooms").document(roomId).collection("queue")
                .whereEqualTo("roomActive", true)
                .snapshots()
                .map { snap ->
                    snap.toObjects(RoomQueueItemDoc::class.java, ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.createdAt?.seconds ?: 0L }
                }
                .wrapFirestoreFlow(OperationType.LIST, path, uid)
        )
    }

    fun observeRoomReactions(roomId: String): Flow<List<RoomReactionDoc>> = flow {
        val uid = requireUserId()
        val path = "pulseRooms/$roomId/reactions"
        emitAll(
            db.collection("pulseRooms").document(roomId).collection("reactions")
                .whereEqualTo("roomActive", true)
                .snapshots()
                .map { snap ->
                    snap.toObjects(RoomReactionDoc::class.java, ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                        .take(15)
                }
                .wrapFirestoreFlow(OperationType.LIST, path, uid)
        )
    }

    suspend fun createPulseRoom(
        host: UserProfile,
        roomName: String,
        moodTag: String,
        track: Track,
        soundscapePresetName: String
    ): Result<String> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val docRef = db.collection("pulseRooms").document()
        return try {
            docRef.set(
                mapOf(
                    "hostUid" to uid,
                    "hostName" to host.displayName.take(60).ifBlank { "Host" },
                    "roomName" to roomName.trim().take(60).ifBlank { "Live PulseRoom" },
                    "moodTag" to moodTag.trim().take(40).ifBlank { "Chillwave" },
                    "active" to true,
                    "memberUids" to listOf(uid),
                    "currentTrackId" to track.id.take(128),
                    "trackTitle" to track.title.take(160),
                    "artist" to track.artist.take(120),
                    "artworkUrl" to track.artworkUrl.take(600),
                    "streamUrl" to track.streamUrl.take(600),
                    "playing" to true,
                    "positionMs" to 0L,
                    "soundscapePreset" to soundscapePresetName.take(40).ifBlank { "Obsidian Studio" },
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun joinPulseRoom(room: PulseRoomDoc): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        if (uid in room.memberUids) return Result.success(Unit)
        val path = "pulseRooms/${room.id}"
        return try {
            val updatedMembers = (room.memberUids + uid).distinct().take(50)
            db.collection("pulseRooms").document(room.id).update(
                mapOf(
                    "memberUids" to updatedMembers,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun renamePulseRoom(roomId: String, newName: String): Result<Unit> {
        requireUserId()
        val trimmed = newName.trim().take(60)
        if (trimmed.isBlank()) return Result.failure(IllegalArgumentException("Room name cannot be empty"))
        val path = "pulseRooms/$roomId"
        return try {
            db.collection("pulseRooms").document(roomId).update(
                mapOf(
                    "roomName" to trimmed,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun endPulseRoom(roomId: String): Result<Unit> {
        requireUserId()
        val path = "pulseRooms/$roomId"
        return try {
            db.collection("pulseRooms").document(roomId).update(
                mapOf(
                    "active" to false,
                    "playing" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun leavePulseRoom(room: PulseRoomDoc): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        if (room.hostUid == uid) return endPulseRoom(room.id)
        val path = "pulseRooms/${room.id}"
        return try {
            val updatedMembers = room.memberUids.filter { it != uid }.ifEmpty { listOf(room.hostUid) }
            db.collection("pulseRooms").document(room.id).update(
                mapOf(
                    "memberUids" to updatedMembers,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updatePulseRoomPlayback(
        roomId: String,
        track: Track,
        isPlaying: Boolean,
        positionMs: Long,
        soundscapePresetName: String
    ): Result<Unit> {
        requireUserId()
        val path = "pulseRooms/$roomId"
        return try {
            db.collection("pulseRooms").document(roomId).update(
                mapOf(
                    "currentTrackId" to track.id.take(128),
                    "trackTitle" to track.title.take(160),
                    "artist" to track.artist.take(120),
                    "artworkUrl" to track.artworkUrl.take(600),
                    "streamUrl" to track.streamUrl.take(600),
                    "playing" to isPlaying,
                    "positionMs" to positionMs.coerceAtLeast(0L),
                    "soundscapePreset" to soundscapePresetName.take(40).ifBlank { "Obsidian Studio" },
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun enqueueRoomTrack(roomId: String, userName: String, track: Track): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val ref = db.collection("pulseRooms").document(roomId).collection("queue").document()
        return try {
            ref.set(
                mapOf(
                    "roomActive" to true,
                    "trackId" to track.id.take(128),
                    "title" to track.title.take(160),
                    "artist" to track.artist.take(120),
                    "artworkUrl" to track.artworkUrl.take(600),
                    "streamUrl" to track.streamUrl.take(600),
                    "addedByUid" to uid,
                    "addedByName" to userName.take(60).ifBlank { "Friend" },
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path)
            Result.failure(e)
        }
    }

    suspend fun removeRoomQueueItem(roomId: String, queueItemId: String): Result<Unit> {
        requireUserId()
        val ref = db.collection("pulseRooms").document(roomId).collection("queue").document(queueItemId)
        return try {
            ref.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, ref.path)
            Result.failure(e)
        }
    }

    suspend fun sendRoomReaction(roomId: String, userName: String, emoji: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val ref = db.collection("pulseRooms").document(roomId).collection("reactions").document()
        return try {
            ref.set(
                mapOf(
                    "roomActive" to true,
                    "senderUid" to uid,
                    "senderName" to userName.take(60).ifBlank { "Friend" },
                    "emoji" to emoji.take(16),
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path)
            Result.failure(e)
        }
    }

    // --- 8. Beat-Reactive Lyric Canvas Shared Friend Notes ---

    fun observeLyricNotes(trackKey: String): Flow<List<LyricNoteDoc>> = flow {
        val uid = requireUserId()
        emitAll(
            db.collection("lyricNotes")
                .whereEqualTo("trackKey", trackKey.take(128))
                .whereEqualTo("shared", true)
                .snapshots()
                .map { snap ->
                    snap.toObjects(LyricNoteDoc::class.java, ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.timestampMs }
                }
                .wrapFirestoreFlow(OperationType.LIST, "lyricNotes", uid)
        )
    }

    suspend fun addLyricNote(
        trackKey: String,
        authorName: String,
        timestampMs: Long,
        noteText: String
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val trimmed = noteText.trim().take(200)
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Note cannot be empty"))
        val ref = db.collection("lyricNotes").document()
        return try {
            ref.set(
                mapOf(
                    "trackKey" to trackKey.take(128),
                    "authorUid" to uid,
                    "authorName" to authorName.take(60).ifBlank { "Listener" },
                    "timestampMs" to timestampMs.coerceAtLeast(0L),
                    "noteText" to trimmed,
                    "shared" to true,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, ref.path)
            Result.failure(e)
        }
    }
}
