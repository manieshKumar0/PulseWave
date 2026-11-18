package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.firebase.SocialRepository
import com.example.data.model.CollaborativePlaylistDoc
import com.example.data.model.ConversationDoc
import com.example.data.model.DirectMessageDoc
import com.example.data.model.FeedCommentDoc
import com.example.data.model.FeedPostDoc
import com.example.data.model.FriendRequestDoc
import com.example.data.model.LyricNoteDoc
import com.example.data.model.PulseRoomDoc
import com.example.data.model.RoomQueueItemDoc
import com.example.data.model.RoomReactionDoc
import com.example.data.model.SonicDnaProfile
import com.example.data.model.SyncedTrackDoc
import com.example.data.model.Track
import com.example.data.model.UserProfile
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class SocialViewModel(
    private val socialRepo: SocialRepository,
    val currentUserId: String
) : ViewModel() {

    private fun <T> kotlinx.coroutines.flow.Flow<T>.toUiStateFlow(
        initial: UiState<T> = UiState.Loading
    ): StateFlow<UiState<T>> =
        this.map<T, UiState<T>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val root = firestoreError ?: error
                emit(UiState.Error(root.localizedMessage ?: "Failed to sync cloud data"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = initial
            )

    val discoverableUsers: StateFlow<UiState<List<UserProfile>>> =
        socialRepo.observeDiscoverableUsers().toUiStateFlow()

    val friendRequests: StateFlow<UiState<List<FriendRequestDoc>>> =
        socialRepo.observeFriendRequests().toUiStateFlow()

    val feedPosts: StateFlow<UiState<List<FeedPostDoc>>> =
        socialRepo.observePublicFeedPosts().toUiStateFlow()

    val conversations: StateFlow<UiState<List<ConversationDoc>>> =
        socialRepo.observeConversations().toUiStateFlow()

    val collaborativePlaylists: StateFlow<UiState<List<CollaborativePlaylistDoc>>> =
        socialRepo.observeCollaborativePlaylists().toUiStateFlow()

    val activePulseRooms: StateFlow<UiState<List<PulseRoomDoc>>> =
        socialRepo.observeActivePulseRooms().toUiStateFlow()

    val syncedLibrary: StateFlow<UiState<List<SyncedTrackDoc>>> =
        socialRepo.observeSyncedLibrary().toUiStateFlow()

    private val _currentUserProfile = MutableStateFlow(
        UserProfile(
            id = currentUserId,
            userId = currentUserId,
            displayName = Firebase.auth.currentUser?.displayName ?: "Pulse Listener",
            handle = "pulse_${currentUserId.take(4)}",
            avatarUrl = Firebase.auth.currentUser?.photoUrl?.toString().orEmpty()
        )
    )
    val currentUserProfile: StateFlow<UserProfile> = _currentUserProfile.asStateFlow()

    private val _activeMessages = MutableStateFlow<List<DirectMessageDoc>>(emptyList())
    val activeMessages: StateFlow<List<DirectMessageDoc>> = _activeMessages.asStateFlow()

    private val _activePostComments = MutableStateFlow<List<FeedCommentDoc>>(emptyList())
    val activePostComments: StateFlow<List<FeedCommentDoc>> = _activePostComments.asStateFlow()

    private val _activeLyricNotes = MutableStateFlow<List<LyricNoteDoc>>(emptyList())
    val activeLyricNotes: StateFlow<List<LyricNoteDoc>> = _activeLyricNotes.asStateFlow()

    private val _activeRoom = MutableStateFlow<PulseRoomDoc?>(null)
    val activeRoom: StateFlow<PulseRoomDoc?> = _activeRoom.asStateFlow()

    private val _activeRoomQueue = MutableStateFlow<List<RoomQueueItemDoc>>(emptyList())
    val activeRoomQueue: StateFlow<List<RoomQueueItemDoc>> = _activeRoomQueue.asStateFlow()

    private val _activeRoomReactions = MutableStateFlow<List<RoomReactionDoc>>(emptyList())
    val activeRoomReactions: StateFlow<List<RoomReactionDoc>> = _activeRoomReactions.asStateFlow()

    private val _socialNotice = MutableStateFlow<String?>(null)
    val socialNotice: StateFlow<String?> = _socialNotice.asStateFlow()

    private var messageJob: Job? = null
    private var commentJob: Job? = null
    private var lyricNoteJob: Job? = null
    private var roomJob: Job? = null

    init {
        ensureProfileInitialized(SonicDnaProfile())
    }

    fun ensureProfileInitialized(sonicDna: SonicDnaProfile) {
        viewModelScope.launch {
            val authUser = Firebase.auth.currentUser
            val res = socialRepo.ensureUserProfile(
                displayName = authUser?.displayName ?: "Pulse Listener",
                avatarUrl = authUser?.photoUrl?.toString().orEmpty(),
                sonicDna = sonicDna
            )
            res.onSuccess { _currentUserProfile.value = it }
        }
    }

    fun syncSonicDna(sonicDna: SonicDnaProfile, nowPlayingTrack: Track?) {
        viewModelScope.launch {
            socialRepo.updateSonicDnaAndNowPlaying(sonicDna, nowPlayingTrack)
        }
    }

    fun syncTrackBookmark(track: Track, liked: Boolean) {
        viewModelScope.launch {
            socialRepo.syncTrackToCloud(track, liked = liked, playCountDelta = 1L)
        }
    }

    fun sendFriendRequest(target: UserProfile) {
        viewModelScope.launch {
            val res = socialRepo.sendFriendRequest(_currentUserProfile.value, target)
            _socialNotice.value = res.fold(
                onSuccess = { "Friend request sent to ${target.displayName}" },
                onFailure = { it.localizedMessage ?: "Failed to send request" }
            )
        }
    }

    fun acceptFriendRequest(requestId: String) {
        viewModelScope.launch {
            val res = socialRepo.acceptFriendRequest(requestId)
            _socialNotice.value = res.fold(
                onSuccess = { "Friend connected!" },
                onFailure = { it.localizedMessage ?: "Failed to accept request" }
            )
        }
    }

    fun computeTasteCompatibility(friend: UserProfile): Int {
        val me = _currentUserProfile.value
        val diffs = listOf(
            abs(me.sonicEnergy - friend.sonicEnergy),
            abs(me.sonicValence - friend.sonicValence),
            abs(me.sonicTempo - friend.sonicTempo),
            abs(me.sonicAcoustic - friend.sonicAcoustic),
            abs(me.sonicElectronic - friend.sonicElectronic),
            abs(me.sonicChill - friend.sonicChill)
        )
        val avgDiff = diffs.average()
        return (100.0 - avgDiff).roundToInt().coerceIn(42, 99)
    }

    fun createTasteBlendPlaylist(friend: UserProfile, candidateTracks: List<Track>) {
        viewModelScope.launch {
            val matchPct = computeTasteCompatibility(friend)
            val blendedTracks = candidateTracks
                .filter { track ->
                    friend.topGenres.any { g -> track.genre.contains(g, true) } ||
                        _currentUserProfile.value.topGenres.any { g -> track.genre.contains(g, true) }
                }
                .ifEmpty { candidateTracks }
                .take(10)

            val res = socialRepo.createCollaborativePlaylist(
                owner = _currentUserProfile.value,
                title = "${_currentUserProfile.value.displayName.split(" ").first()} × ${friend.displayName.split(" ").first()} Blend",
                description = "$matchPct% Sonic DNA Match · Shared auto-blended playlist",
                collaboratorUids = listOf(currentUserId, friend.userId),
                initialTracks = blendedTracks,
                discoverable = false
            )
            _socialNotice.value = res.fold(
                onSuccess = { "Created $matchPct% Sonic Blend playlist with ${friend.displayName}!" },
                onFailure = { it.localizedMessage ?: "Could not create Taste Blend" }
            )
        }
    }

    fun publishPost(
        track: Track,
        caption: String,
        postType: String = "TRACK",
        lyricQuote: String = "",
        soundscapeLabel: String = ""
    ) {
        viewModelScope.launch {
            val res = socialRepo.publishFeedPost(
                author = _currentUserProfile.value,
                track = track,
                caption = caption,
                postType = postType,
                lyricQuote = lyricQuote,
                soundscapeLabel = soundscapeLabel
            )
            _socialNotice.value = res.fold(
                onSuccess = { "Shared ${track.title} to your feed" },
                onFailure = { it.localizedMessage ?: "Failed to post" }
            )
        }
    }

    fun togglePostLike(post: FeedPostDoc) {
        viewModelScope.launch { socialRepo.toggleFeedPostLike(post) }
    }

    fun openPostComments(postId: String) {
        commentJob?.cancel()
        commentJob = viewModelScope.launch {
            socialRepo.observePostComments(postId)
                .catch { _activePostComments.value = emptyList() }
                .collect { _activePostComments.value = it }
        }
    }

    fun addPostComment(post: FeedPostDoc, text: String) {
        viewModelScope.launch {
            socialRepo.addPostComment(post, _currentUserProfile.value.displayName, text)
        }
    }

    fun openConversation(conversationId: String) {
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            socialRepo.observeMessages(conversationId)
                .catch { _activeMessages.value = emptyList() }
                .collect { _activeMessages.value = it }
        }
    }

    fun sendDm(
        recipient: UserProfile,
        text: String,
        attachedTrack: Track? = null,
        attachmentType: String = if (attachedTrack != null) "TRACK" else "NONE",
        playlistId: String = "",
        playlistTitle: String = "",
        presetName: String = ""
    ) {
        viewModelScope.launch {
            val res = socialRepo.sendDirectMessage(
                sender = _currentUserProfile.value,
                recipient = recipient,
                text = text,
                attachedTrack = attachedTrack,
                attachmentType = attachmentType,
                attachedPlaylistId = playlistId,
                attachedPlaylistTitle = playlistTitle,
                attachedPresetName = presetName
            )
            res.onSuccess { convId ->
                openConversation(convId)
                _socialNotice.value = "Sent to ${recipient.displayName}"
            }.onFailure {
                _socialNotice.value = it.localizedMessage ?: "Message failed"
            }
        }
    }

    fun createPlaylist(title: String, description: String, collaboratorUids: List<String>, seedTracks: List<Track>) {
        viewModelScope.launch {
            val res = socialRepo.createCollaborativePlaylist(
                owner = _currentUserProfile.value,
                title = title,
                description = description,
                collaboratorUids = collaboratorUids,
                initialTracks = seedTracks
            )
            _socialNotice.value = res.fold(
                onSuccess = { "Collaborative playlist '$title' created" },
                onFailure = { it.localizedMessage ?: "Playlist creation failed" }
            )
        }
    }

    fun addTrackToPlaylist(playlist: CollaborativePlaylistDoc, track: Track) {
        viewModelScope.launch {
            val res = socialRepo.addTrackToCollaborativePlaylist(playlist, track)
            _socialNotice.value = res.fold(
                onSuccess = { "Added ${track.title} to ${playlist.title}" },
                onFailure = { it.localizedMessage ?: "Could not add track" }
            )
        }
    }

    fun upvotePlaylistTrack(playlist: CollaborativePlaylistDoc, trackId: String) {
        viewModelScope.launch { socialRepo.upvotePlaylistTrack(playlist, trackId) }
    }

    fun createAndJoinPulseRoom(roomName: String, moodTag: String, track: Track, soundscapePreset: String, onRoomCreated: (String) -> Unit) {
        viewModelScope.launch {
            val res = socialRepo.createPulseRoom(
                host = _currentUserProfile.value,
                roomName = roomName,
                moodTag = moodTag,
                track = track,
                soundscapePresetName = soundscapePreset
            )
            res.onSuccess { roomId ->
                observePulseRoomSession(roomId)
                onRoomCreated(roomId)
            }.onFailure {
                _socialNotice.value = it.localizedMessage ?: "Could not start PulseRoom"
            }
        }
    }

    fun joinAndObservePulseRoom(room: PulseRoomDoc, onJoined: () -> Unit) {
        viewModelScope.launch {
            socialRepo.joinPulseRoom(room)
            observePulseRoomSession(room.id)
            onJoined()
        }
    }

    fun renamePulseRoom(roomId: String, newName: String) {
        viewModelScope.launch {
            val res = socialRepo.renamePulseRoom(roomId, newName)
            _socialNotice.value = res.fold(
                onSuccess = { "Updated room name to '${newName.trim()}'" },
                onFailure = { it.localizedMessage ?: "Could not rename room" }
            )
        }
    }

    fun endOrLeavePulseRoom(room: PulseRoomDoc?, onFinished: () -> Unit) {
        viewModelScope.launch {
            if (room != null) {
                if (room.hostUid == currentUserId) {
                    socialRepo.endPulseRoom(room.id)
                    _socialNotice.value = "Ended '${room.roomName}'"
                } else {
                    socialRepo.leavePulseRoom(room)
                    _socialNotice.value = "Left '${room.roomName}'"
                }
            }
            roomJob?.cancel()
            _activeRoom.value = null
            _activeRoomQueue.value = emptyList()
            _activeRoomReactions.value = emptyList()
            onFinished()
        }
    }

    fun observePulseRoomSession(roomId: String) {
        roomJob?.cancel()
        roomJob = viewModelScope.launch {
            launch {
                socialRepo.observePulseRoom(roomId)
                    .catch { _activeRoom.value = null }
                    .collect { _activeRoom.value = it }
            }
            launch {
                socialRepo.observeRoomQueue(roomId)
                    .catch { _activeRoomQueue.value = emptyList() }
                    .collect { _activeRoomQueue.value = it }
            }
            launch {
                socialRepo.observeRoomReactions(roomId)
                    .catch { _activeRoomReactions.value = emptyList() }
                    .collect { _activeRoomReactions.value = it }
            }
        }
    }

    fun syncHostRoomState(roomId: String, track: Track, isPlaying: Boolean, positionMs: Long, presetName: String) {
        viewModelScope.launch {
            socialRepo.updatePulseRoomPlayback(roomId, track, isPlaying, positionMs, presetName)
        }
    }

    fun enqueueRoomTrack(roomId: String, track: Track) {
        viewModelScope.launch {
            socialRepo.enqueueRoomTrack(roomId, _currentUserProfile.value.displayName, track)
            _socialNotice.value = "Queued ${track.title} in PulseRoom"
        }
    }

    fun sendRoomReaction(roomId: String, emoji: String) {
        viewModelScope.launch {
            socialRepo.sendRoomReaction(roomId, _currentUserProfile.value.displayName, emoji)
        }
    }

    fun observeLyricNotesForTrack(trackKey: String) {
        lyricNoteJob?.cancel()
        lyricNoteJob = viewModelScope.launch {
            socialRepo.observeLyricNotes(trackKey)
                .catch { _activeLyricNotes.value = emptyList() }
                .collect { _activeLyricNotes.value = it }
        }
    }

    fun addLyricNote(trackKey: String, timestampMs: Long, text: String) {
        viewModelScope.launch {
            val res = socialRepo.addLyricNote(trackKey, _currentUserProfile.value.displayName, timestampMs, text)
            _socialNotice.value = res.fold(
                onSuccess = { "Pinned lyric note for friends!" },
                onFailure = { it.localizedMessage ?: "Could not pin lyric note" }
            )
        }
    }

    fun clearNotice() {
        _socialNotice.value = null
    }
}
