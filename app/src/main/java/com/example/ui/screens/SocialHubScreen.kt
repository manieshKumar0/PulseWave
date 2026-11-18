package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.ConversationDoc
import com.example.data.model.DirectMessageDoc
import com.example.data.model.FeedCommentDoc
import com.example.data.model.FeedPostDoc
import com.example.data.model.FriendRequestDoc
import com.example.data.model.PulseRoomDoc
import com.example.data.model.SonicDnaProfile
import com.example.data.model.Track
import com.example.data.model.UserProfile
import com.example.ui.components.CreatePulseRoomDialog
import com.example.ui.components.GoogleSignInGateCard
import com.example.ui.components.ObsidianGlassCard
import com.example.ui.components.PulseWaveBrandLogo
import com.example.ui.components.SonicDnaRadarChart
import com.example.ui.components.TrackArtworkThumb
import com.example.ui.components.defaultPulseRoomName
import com.example.ui.theme.CoralWave
import com.example.ui.theme.MintElectric
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.UltravioletPulse
import com.example.ui.viewmodel.UiState

@Composable
fun SocialGuestGateScreen(
    isSigningIn: Boolean,
    onSignInWithGoogle: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PulseWaveBrandLogo(size = 38.dp)
                Column {
                    Text("Pulse Social", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        text = "Sign in required · Guest listening active",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        item {
            GoogleSignInGateCard(
                title = "Connect Your Google Account",
                subtitle = "Pulse Social, Friend Feed, Direct Music DMs, Sonic DNA Overlap, and live PulseRooms sync through your Google account.",
                isSigningIn = isSigningIn,
                onSignInClick = onSignInWithGoogle,
                buttonTestTag = "social_sign_in_button"
            )
        }

        item {
            ObsidianGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("What unlocks after sign-in", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "• Live Sonic DNA Overlap & 1-tap Taste Blend playlists with friends\n" +
                            "• Host & join synchronized PulseRooms with custom room names\n" +
                            "• Share tracks, soundscape presets & timestamped lyric notes\n" +
                            "• Cloud-synced bookmarks and collaborative playlists",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialHubScreen(
    currentUserId: String,
    currentUserProfile: UserProfile,
    mySonicDna: SonicDnaProfile,
    activeTrack: Track?,
    activeSoundscapeName: String,
    libraryTracks: List<Track>,
    discoverableUsersState: UiState<List<UserProfile>>,
    friendRequestsState: UiState<List<FriendRequestDoc>>,
    feedPostsState: UiState<List<FeedPostDoc>>,
    activePostComments: List<FeedCommentDoc>,
    conversationsState: UiState<List<ConversationDoc>>,
    activeMessages: List<DirectMessageDoc>,
    activeRoomsState: UiState<List<PulseRoomDoc>>,
    computeTasteCompatibility: (UserProfile) -> Int,
    defaultRoomName: String = defaultPulseRoomName(currentUserProfile.displayName),
    onPlayTrack: (Track) -> Unit,
    onPublishPost: (Track, String, String, String) -> Unit,
    onTogglePostLike: (FeedPostDoc) -> Unit,
    onOpenPostComments: (String) -> Unit,
    onAddPostComment: (FeedPostDoc, String) -> Unit,
    onSendFriendRequest: (UserProfile) -> Unit,
    onAcceptFriendRequest: (String) -> Unit,
    onCreateTasteBlend: (UserProfile) -> Unit,
    onOpenConversation: (String) -> Unit,
    onSendDm: (UserProfile, String, Track?, String, String) -> Unit,
    onCreatePulseRoom: (String, String, Track) -> Unit,
    onJoinPulseRoom: (PulseRoomDoc) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Feed", "Friends", "Messages", "Rooms")

    var commentSheetPost by remember { mutableStateOf<FeedPostDoc?>(null) }
    var selectedDmPartner by remember { mutableStateOf<UserProfile?>(null) }
    var selectedBlendCompareFriend by remember { mutableStateOf<UserProfile?>(null) }
    var showCreateRoomDialog by remember { mutableStateOf(false) }

    val allUsers = (discoverableUsersState as? UiState.Success)?.data.orEmpty()
    val allRequests = (friendRequestsState as? UiState.Success)?.data.orEmpty()
    val acceptedRequests = remember(allRequests) { allRequests.filter { it.status == "ACCEPTED" } }
    val pendingIncoming = remember(allRequests, currentUserId) {
        allRequests.filter { it.status == "PENDING" && it.toUid == currentUserId }
    }
    val connectedFriendUids = remember(acceptedRequests, currentUserId) {
        acceptedRequests.flatMap { it.participantUids }.filter { it != currentUserId }.toSet()
    }
    val pendingOrConnectedUids = remember(allRequests, currentUserId) {
        allRequests.flatMap { it.participantUids }.filter { it != currentUserId }.toSet()
    }
    val connectedFriends = remember(allUsers, connectedFriendUids) {
        allUsers.filter { it.userId in connectedFriendUids }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PulseWaveBrandLogo(size = 38.dp)
            Column {
                Text("Pulse Social", style = MaterialTheme.typography.headlineLarge)
                Text(
                    text = "@${currentUserProfile.handle} · ${connectedFriends.size} friends connected",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
        ) {
            tabTitles.forEachIndexed { idx, title ->
                Tab(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    text = { Text(title, maxLines = 1) },
                    modifier = Modifier.testTag("social_tab_${title.lowercase()}")
                )
            }
        }

        when (selectedTab) {
            0 -> FeedTab(
                currentUserId = currentUserId,
                activeTrack = activeTrack,
                activeSoundscapeName = activeSoundscapeName,
                feedPostsState = feedPostsState,
                onPublishPost = onPublishPost,
                onPlayTrack = onPlayTrack,
                onTogglePostLike = onTogglePostLike,
                onOpenComments = { post ->
                    onOpenPostComments(post.id)
                    commentSheetPost = post
                }
            )
            1 -> FriendsAndDnaTab(
                mySonicDna = mySonicDna,
                currentUserProfile = currentUserProfile,
                pendingIncoming = pendingIncoming,
                connectedFriends = connectedFriends,
                discoverableUsers = allUsers.filter {
                    it.userId != currentUserId && it.userId !in pendingOrConnectedUids
                },
                selectedCompareFriend = selectedBlendCompareFriend,
                computeTasteCompatibility = computeTasteCompatibility,
                onSelectCompareFriend = {
                    selectedBlendCompareFriend = if (selectedBlendCompareFriend?.userId == it.userId) null else it
                },
                onAcceptRequest = onAcceptFriendRequest,
                onSendRequest = onSendFriendRequest,
                onCreateTasteBlend = onCreateTasteBlend,
                onMessageFriend = { friend ->
                    selectedDmPartner = friend
                    val sorted = listOf(currentUserId, friend.userId).sorted()
                    onOpenConversation("dm_${sorted[0]}_${sorted[1]}")
                    selectedTab = 2
                }
            )
            2 -> MessagesTab(
                currentUserId = currentUserId,
                friends = connectedFriends.ifEmpty { allUsers.filter { it.userId != currentUserId } },
                conversationsState = conversationsState,
                activeMessages = activeMessages,
                selectedPartner = selectedDmPartner,
                activeTrack = activeTrack,
                activeSoundscapeName = activeSoundscapeName,
                onSelectPartner = { partner ->
                    selectedDmPartner = partner
                    val sorted = listOf(currentUserId, partner.userId).sorted()
                    onOpenConversation("dm_${sorted[0]}_${sorted[1]}")
                },
                onSelectConversation = { conv ->
                    val otherUid = conv.participantUids.firstOrNull { it != currentUserId }
                    val matchedUser = allUsers.firstOrNull { it.userId == otherUid }
                        ?: otherUid?.let { UserProfile(id = it, userId = it, displayName = conv.participantLabel) }
                    selectedDmPartner = matchedUser
                    onOpenConversation(conv.id)
                },
                onPlayTrack = onPlayTrack,
                onSendDm = onSendDm
            )
            3 -> PulseRoomsTab(
                activeRoomsState = activeRoomsState,
                canCreate = activeTrack != null || libraryTracks.isNotEmpty(),
                onOpenCreateDialog = { showCreateRoomDialog = true },
                onJoinRoom = onJoinPulseRoom
            )
        }
    }

    if (commentSheetPost != null) {
        val post = commentSheetPost!!
        var commentText by remember { mutableStateOf("") }
        ModalBottomSheet(
            onDismissRequest = { commentSheetPost = null },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Comments on ${post.trackTitle}", style = MaterialTheme.typography.titleLarge)
                if (activePostComments.isEmpty()) {
                    Text(
                        "Be the first to drop a note on this track.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    activePostComments.take(12).forEach { comment ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(comment.authorName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text(comment.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = commentText,
                        onValueChange = { commentText = it },
                        placeholder = { Text("Write a comment...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("post_comment_input")
                    )
                    Button(
                        onClick = {
                            if (commentText.isNotBlank()) {
                                onAddPostComment(post, commentText)
                                commentText = ""
                            }
                        },
                        modifier = Modifier.height(50.dp).testTag("send_post_comment_button")
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Post comment")
                    }
                }
            }
        }
    }

    if (showCreateRoomDialog) {
        val seedTrack = activeTrack ?: libraryTracks.firstOrNull()
        CreatePulseRoomDialog(
            defaultRoomName = defaultRoomName,
            seedTrack = seedTrack,
            onDismiss = { showCreateRoomDialog = false },
            onConfirmCreate = { roomName, moodTag ->
                if (seedTrack != null) {
                    onCreatePulseRoom(roomName, moodTag, seedTrack)
                    showCreateRoomDialog = false
                }
            }
        )
    }
}

@Composable
private fun FeedTab(
    currentUserId: String,
    activeTrack: Track?,
    activeSoundscapeName: String,
    feedPostsState: UiState<List<FeedPostDoc>>,
    onPublishPost: (Track, String, String, String) -> Unit,
    onPlayTrack: (Track) -> Unit,
    onTogglePostLike: (FeedPostDoc) -> Unit,
    onOpenComments: (FeedPostDoc) -> Unit
) {
    var caption by remember { mutableStateOf("") }
    var lyricQuote by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (activeTrack != null) {
            item {
                ObsidianGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Post Active Track: ${activeTrack.title}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedTextField(
                            value = caption,
                            onValueChange = { caption = it },
                            placeholder = { Text("Share what you're hearing with friends...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("feed_composer_caption")
                        )
                        OutlinedTextField(
                            value = lyricQuote,
                            onValueChange = { lyricQuote = it },
                            placeholder = { Text("Optional lyric line highlight") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                onPublishPost(
                                    activeTrack,
                                    caption.ifBlank { "Spinning ${activeTrack.title} on $activeSoundscapeName" },
                                    lyricQuote,
                                    activeSoundscapeName
                                )
                                caption = ""
                                lyricQuote = ""
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("publish_feed_post_button")
                        ) {
                            Text("Share to Profile Feed")
                        }
                    }
                }
            }
        }

        when (feedPostsState) {
            is UiState.Loading -> item {
                Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is UiState.Error -> item {
                ObsidianGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(feedPostsState.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                }
            }
            is UiState.Success -> {
                val posts = feedPostsState.data
                if (posts.isEmpty()) {
                    item {
                        ObsidianGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Your music feed is quiet", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "Play any track and post it above to share live vibes, lyric highlights, and soundscape presets.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(posts, key = { it.id }) { post ->
                        val isLiked = currentUserId in post.likeUids
                        ObsidianGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = post.authorName.take(1).uppercase(),
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column {
                                            Text(post.authorName, style = MaterialTheme.typography.titleMedium)
                                            Text("@${post.authorHandle}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    if (post.soundscapeLabel.isNotBlank()) {
                                        Surface(
                                            color = UltravioletPulse.copy(alpha = 0.18f),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text(
                                                text = post.soundscapeLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }

                                if (post.caption.isNotBlank()) {
                                    Text(post.caption, style = MaterialTheme.typography.bodyLarge)
                                }

                                if (post.lyricQuote.isNotBlank()) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "“${post.lyricQuote}”",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                }

                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onPlayTrack(
                                                Track(
                                                    id = post.trackId,
                                                    title = post.trackTitle,
                                                    artist = post.artist,
                                                    artworkUrl = post.artworkUrl,
                                                    streamUrl = post.streamUrl
                                                )
                                            )
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TrackArtworkThumb(
                                            artworkUrl = post.artworkUrl,
                                            title = post.trackTitle,
                                            modifier = Modifier.size(48.dp)
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(post.trackTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(post.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                        }
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Play ${post.trackTitle}", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = { onTogglePostLike(post) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                            contentDescription = "Like post",
                                            tint = if (isLiked) CoralWave else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("${post.likeUids.size}")
                                    }
                                    TextButton(
                                        onClick = { onOpenComments(post) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(Icons.AutoMirrored.Rounded.Chat, contentDescription = "Comments")
                                        Spacer(Modifier.width(4.dp))
                                        Text("${post.commentCount} Comments")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendsAndDnaTab(
    mySonicDna: SonicDnaProfile,
    currentUserProfile: UserProfile,
    pendingIncoming: List<FriendRequestDoc>,
    connectedFriends: List<UserProfile>,
    discoverableUsers: List<UserProfile>,
    selectedCompareFriend: UserProfile?,
    computeTasteCompatibility: (UserProfile) -> Int,
    onSelectCompareFriend: (UserProfile) -> Unit,
    onAcceptRequest: (String) -> Unit,
    onSendRequest: (UserProfile) -> Unit,
    onCreateTasteBlend: (UserProfile) -> Unit,
    onMessageFriend: (UserProfile) -> Unit
) {
    val friendDna = selectedCompareFriend?.let {
        SonicDnaProfile(
            energy = it.sonicEnergy.toInt(),
            valence = it.sonicValence.toInt(),
            tempo = it.sonicTempo.toInt(),
            acoustic = it.sonicAcoustic.toInt(),
            electronic = it.sonicElectronic.toInt(),
            chill = it.sonicChill.toInt(),
            topGenres = it.topGenres
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ObsidianGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (selectedCompareFriend == null) "Your Live Sonic DNA" else "Sonic DNA Overlap",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "${mySonicDna.avgCompletionPct}% avg track completion · Top: ${mySonicDna.topGenres.joinToString(" · ")}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    SonicDnaRadarChart(
                        primaryProfile = mySonicDna,
                        friendProfile = friendDna,
                        primaryLabel = currentUserProfile.displayName.split(" ").first(),
                        friendLabel = selectedCompareFriend?.displayName?.split(" ")?.first() ?: "Friend"
                    )
                }
            }
        }

        if (pendingIncoming.isNotEmpty()) {
            item {
                Text("Friend Requests", style = MaterialTheme.typography.titleLarge)
            }
            items(pendingIncoming, key = { it.id }) { req ->
                ObsidianGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(req.fromName, style = MaterialTheme.typography.titleMedium)
                            Text("@${req.fromHandle}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            onClick = { onAcceptRequest(req.id) },
                            modifier = Modifier.height(48.dp).testTag("accept_friend_${req.id}")
                        ) {
                            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Accept")
                        }
                    }
                }
            }
        }

        item {
            Text("Friends & Taste Blending", style = MaterialTheme.typography.titleLarge)
        }

        if (connectedFriends.isEmpty()) {
            item {
                ObsidianGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Add listeners below to unlock Sonic DNA overlap & 1-tap Taste Blend playlists.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(connectedFriends, key = { it.userId }) { friend ->
                val matchPct = computeTasteCompatibility(friend)
                ObsidianGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 18.dp,
                    accentTint = if (selectedCompareFriend?.userId == friend.userId) MintElectric else UltravioletPulse,
                    onClick = { onSelectCompareFriend(friend) }
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(friend.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = if (friend.nowPlayingTitle.isNotBlank()) {
                                        "Listening to ${friend.nowPlayingTitle}"
                                    } else "@${friend.handle}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    text = "$matchPct% Match",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onCreateTasteBlend(friend) },
                                modifier = Modifier.weight(1f).height(48.dp).testTag("taste_blend_${friend.userId}")
                            ) {
                                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Taste Blend", maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = { onMessageFriend(friend) },
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Message", maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        if (discoverableUsers.isNotEmpty()) {
            item {
                Text("Discover Listeners", style = MaterialTheme.typography.titleLarge)
            }
            items(discoverableUsers, key = { it.userId }) { user ->
                val matchPct = computeTasteCompatibility(user)
                ObsidianGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(user.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "$matchPct% Sonic Match · ${user.topGenres.take(2).joinToString(", ").ifBlank { "Music Explorer" }}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = { onSendRequest(user) },
                            modifier = Modifier.height(48.dp).testTag("add_friend_${user.userId}")
                        ) {
                            Icon(Icons.Rounded.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessagesTab(
    currentUserId: String,
    friends: List<UserProfile>,
    conversationsState: UiState<List<ConversationDoc>>,
    activeMessages: List<DirectMessageDoc>,
    selectedPartner: UserProfile?,
    activeTrack: Track?,
    activeSoundscapeName: String,
    onSelectPartner: (UserProfile) -> Unit,
    onSelectConversation: (ConversationDoc) -> Unit,
    onPlayTrack: (Track) -> Unit,
    onSendDm: (UserProfile, String, Track?, String, String) -> Unit
) {
    var messageInput by remember { mutableStateOf("") }
    var attachTrack by remember { mutableStateOf(false) }
    var attachSoundscape by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (friends.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(friends, key = { it.userId }) { friend ->
                    FilterChip(
                        selected = selectedPartner?.userId == friend.userId,
                        onClick = { onSelectPartner(friend) },
                        label = { Text(friend.displayName) }
                    )
                }
            }
        }

        if (selectedPartner == null) {
            val conversations = (conversationsState as? UiState.Success)?.data.orEmpty()
            if (conversations.isEmpty()) {
                ObsidianGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Direct Music Messages", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Select a listener above to send playable tracks, collaborative playlists, or soundscape presets.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(conversations, key = { it.id }) { conv ->
                        ObsidianGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onSelectConversation(conv) }
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(conv.participantLabel, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = conv.lastMessageText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Chat with ${selectedPartner.displayName}", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { /* switch partner via chips */ }) {
                    Text("@${selectedPartner.handle}")
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(activeMessages, key = { it.id }) { msg ->
                    val isMine = msg.senderUid == currentUserId
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            color = if (isMine) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(0.82f)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (msg.text.isNotBlank()) {
                                    Text(msg.text, style = MaterialTheme.typography.bodyLarge)
                                }
                                when (msg.attachmentType) {
                                    "TRACK" -> {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    onPlayTrack(
                                                        Track(
                                                            id = msg.attachedId,
                                                            title = msg.attachedTitle,
                                                            artist = msg.attachedSubtitle,
                                                            artworkUrl = msg.attachedArtworkUrl,
                                                            streamUrl = msg.attachedStreamUrl
                                                        )
                                                    )
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                TrackArtworkThumb(
                                                    artworkUrl = msg.attachedArtworkUrl,
                                                    title = msg.attachedTitle,
                                                    modifier = Modifier.size(40.dp)
                                                )
                                                Spacer(Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(msg.attachedTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                                    Text(msg.attachedSubtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                                }
                                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play shared track", tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                    "SOUNDSCAPE", "PLAYLIST" -> {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                                Spacer(Modifier.width(8.dp))
                                                Column {
                                                    Text(msg.attachedTitle, style = MaterialTheme.typography.titleMedium)
                                                    Text(msg.attachedSubtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Attachment quick chips + message input
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (activeTrack != null) {
                    FilterChip(
                        selected = attachTrack,
                        onClick = {
                            attachTrack = !attachTrack
                            if (attachTrack) attachSoundscape = false
                        },
                        label = { Text("Attach ${activeTrack.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }
                FilterChip(
                    selected = attachSoundscape,
                    onClick = {
                        attachSoundscape = !attachSoundscape
                        if (attachSoundscape) attachTrack = false
                    },
                    label = { Text("Attach DSP: $activeSoundscapeName", maxLines = 1) },
                    leadingIcon = { Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 110.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = messageInput,
                    onValueChange = { messageInput = it },
                    placeholder = { Text("Send a message or music drop...") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("dm_message_input")
                )
                Button(
                    onClick = {
                        val type = when {
                            attachTrack && activeTrack != null -> "TRACK"
                            attachSoundscape -> "SOUNDSCAPE"
                            else -> "NONE"
                        }
                        if (messageInput.isNotBlank() || type != "NONE") {
                            onSendDm(
                                selectedPartner,
                                messageInput,
                                if (type == "TRACK") activeTrack else null,
                                type,
                                if (type == "SOUNDSCAPE") activeSoundscapeName else ""
                            )
                            messageInput = ""
                            attachTrack = false
                            attachSoundscape = false
                        }
                    },
                    modifier = Modifier.height(50.dp).testTag("send_dm_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send DM")
                }
            }
        }
    }
}

@Composable
private fun PulseRoomsTab(
    activeRoomsState: UiState<List<PulseRoomDoc>>,
    canCreate: Boolean,
    onOpenCreateDialog: () -> Unit,
    onJoinRoom: (PulseRoomDoc) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ObsidianGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, accentTint = MintElectric) {
                Column {
                    Box(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                        Image(
                            painter = painterResource(id = R.drawable.img_pulseroom_cover),
                            contentDescription = "PulseRooms live co-listening cover",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Brush.verticalGradient(listOf(Color.Transparent, ObsidianBg.copy(alpha = 0.88f))))
                        )
                        Column(
                            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp)
                        ) {
                            Text("PULSEROOMS", style = MaterialTheme.typography.labelLarge, color = MintElectric)
                            Text("Real-Time Co-Listening & Shared Queue", style = MaterialTheme.typography.titleLarge, color = Color.White)
                        }
                    }
                    Button(
                        enabled = canCreate,
                        onClick = onOpenCreateDialog,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                            .height(48.dp)
                            .testTag("start_pulseroom_button")
                    ) {
                        Icon(Icons.Rounded.Radio, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start Live PulseRoom")
                    }
                }
            }
        }

        when (activeRoomsState) {
            is UiState.Loading -> item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is UiState.Error -> item {
                Text(activeRoomsState.message, color = MaterialTheme.colorScheme.error)
            }
            is UiState.Success -> {
                val rooms = activeRoomsState.data
                if (rooms.isEmpty()) {
                    item {
                        ObsidianGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("No active rooms right now", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Start a PulseRoom above so friends can listen in sync with live waveform reactions.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(rooms, key = { it.id }) { room ->
                        ObsidianGlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 18.dp,
                            onClick = { onJoinRoom(room) }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TrackArtworkThumb(
                                    artworkUrl = room.artworkUrl,
                                    title = room.trackTitle,
                                    modifier = Modifier.size(56.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(room.roomName, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = "${room.trackTitle} · ${room.artist}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Host: ${room.hostName} · ${room.memberUids.size} listening · ${room.soundscapePreset}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Icon(Icons.Rounded.Groups, contentDescription = "Join ${room.roomName}", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}
