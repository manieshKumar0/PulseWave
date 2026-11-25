package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.data.model.CollaborativePlaylistDoc
import com.example.data.model.SyncedTrackDoc
import com.example.data.model.Track
import com.example.data.model.TrackSourceType
import com.example.domain.SafMusicImporter
import com.example.ui.components.GoogleSignInGateCard
import com.example.ui.components.ObsidianGlassCard
import com.example.ui.components.PulseScreenHeader
import com.example.ui.components.TrackRowItem
import com.example.ui.theme.UltravioletPulse
import com.example.ui.viewmodel.UiState

private enum class LibraryFilter(val label: String, val widthWeight: Float) {
    ALL("All", 0.72f),
    DOWNLOADED("Downloaded", 1.48f),
    DEVICE("Device", 0.90f),
    SHARED("Shared", 0.90f)
}

@Composable
fun LibraryScreen(
    tracks: List<Track>,
    searchResults: List<Track>,
    isSearching: Boolean,
    activeTrack: Track?,
    downloadProgress: Map<String, Float>,
    offlineMode: Boolean,
    playlistsState: UiState<List<CollaborativePlaylistDoc>>,
    syncedLibraryState: UiState<List<SyncedTrackDoc>>,
    searchError: String? = null,
    isScanningDeviceMusic: Boolean = false,
    isSignedIn: Boolean = true,
    isSigningIn: Boolean = false,
    onSignInWithGoogle: () -> Unit = {},
    onScanDeviceAudio: (silentIfNoPermission: Boolean) -> Unit = {},
    onSearchQuery: (String) -> Unit,
    onPlayTrack: (Track, List<Track>) -> Unit,
    onToggleLike: (Track) -> Unit,
    onDownloadClick: (Track) -> Unit,
    onShareClick: (Track) -> Unit,
    onCreatePlaylist: (String, String, List<Track>) -> Unit,
    onAddActiveTrackToPlaylist: (CollaborativePlaylistDoc, Track) -> Unit,
    onUpvotePlaylistTrack: (CollaborativePlaylistDoc, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val requiredAudioPermission = remember { SafMusicImporter.requiredDeviceAudioPermission() }
    var hasAudioPermission by remember {
        mutableStateOf(SafMusicImporter.hasDeviceAudioPermission(context))
    }
    var permissionRequestedOnce by remember { mutableStateOf(false) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionRequestedOnce = true
        val permitted = granted || SafMusicImporter.hasDeviceAudioPermission(context)
        hasAudioPermission = permitted
        if (permitted) {
            onScanDeviceAudio(false)
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    val filters = LibraryFilter.entries
    var selectedFilter by remember { mutableStateOf(LibraryFilter.ALL) }
    val listStates = remember { LibraryFilter.entries.associateWith { LazyListState() } }
    val haptics = LocalHapticFeedback.current
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    val currentSelectedFilter by rememberUpdatedState(selectedFilter)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val hadPermission = hasAudioPermission
                val permitted = SafMusicImporter.hasDeviceAudioPermission(context)
                hasAudioPermission = permitted
                if (!hadPermission && permitted && currentSelectedFilter == LibraryFilter.DEVICE) {
                    onScanDeviceAudio(true)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val tracksByFilter = remember(tracks, searchResults, searchQuery) {
        val base = if (searchQuery.isNotBlank()) searchResults else tracks
        mapOf(
            LibraryFilter.ALL to base.filter {
                it.sourceType != TrackSourceType.LOCAL_SAF &&
                    it.sourceType != TrackSourceType.CLOUD_SAF
            },
            LibraryFilter.DOWNLOADED to base.filter { it.isDownloaded },
            LibraryFilter.DEVICE to base.filter {
                it.sourceType == TrackSourceType.LOCAL_SAF &&
                    (!it.id.startsWith(SafMusicImporter.DEVICE_TRACK_ID_PREFIX) ||
                        it.durationMs > SafMusicImporter.DEFAULT_MIN_DURATION_MS)
            },
            LibraryFilter.SHARED to emptyList()
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        PulseScreenHeader(
            title = "Library & Vault",
            titleTestTag = "library_header_title"
        )
        Spacer(Modifier.height(12.dp))

        // Search input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = {
                searchQuery = it
                onSearchQuery(it)
            },
            placeholder = {
                Text(
                    text = "Search any song, artist, or YouTube link",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (isSearching) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else if (searchQuery.isNotBlank()) {
                    IconButton(onClick = {
                        searchQuery = ""
                        onSearchQuery("")
                    }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("library_search_input")
        )

        Spacer(Modifier.height(12.dp))

        // Segmented Sliding Pill Filter Bar (All, Downloaded, Device, Shared)
        LibrarySegmentedTabBar(
            filters = filters,
            selectedFilter = selectedFilter,
            onSelectFilter = { filter ->
                if (selectedFilter != filter) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    selectedFilter = filter
                }
                if (filter == LibraryFilter.DEVICE) {
                    val permitted = SafMusicImporter.hasDeviceAudioPermission(context)
                    hasAudioPermission = permitted
                    if (!permitted) {
                        audioPermissionLauncher.launch(requiredAudioPermission)
                    }
                }
            }
        )

        Spacer(Modifier.height(8.dp))

        AnimatedContent(
            targetState = selectedFilter,
            transitionSpec = {
                val forward = targetState.ordinal >= initialState.ordinal
                val enterOffset: (Int) -> Int = { width ->
                    val delta = (width * 0.045f).toInt().coerceAtLeast(1)
                    if (forward) delta else -delta
                }
                val exitOffset: (Int) -> Int = { width ->
                    val delta = (width * 0.03f).toInt().coerceAtLeast(1)
                    if (forward) -delta else delta
                }
                (slideInHorizontally(
                    animationSpec = tween(durationMillis = 85, easing = FastOutSlowInEasing),
                    initialOffsetX = enterOffset
                ) + fadeIn(animationSpec = tween(durationMillis = 80))) togetherWith
                    (slideOutHorizontally(
                        animationSpec = tween(durationMillis = 65, easing = FastOutSlowInEasing),
                        targetOffsetX = exitOffset
                    ) + fadeOut(animationSpec = tween(durationMillis = 55)))
            },
            label = "library_tab_content",
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
                .testTag("library_filter_pager")
        ) { pageFilter ->
            val filteredTracks = tracksByFilter[pageFilter].orEmpty()
            val listState = listStates.getValue(pageFilter)

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 6.dp, bottom = 140.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (pageFilter == LibraryFilter.SHARED) {
            if (!isSignedIn) {
                item {
                    GoogleSignInGateCard(
                        title = "Sign in for Collaborative Playlists",
                        subtitle = "Create shared mixes, invite friends, and upvote tracks in real time once signed in with Google.",
                        isSigningIn = isSigningIn,
                        onSignInClick = onSignInWithGoogle,
                        buttonTestTag = "shared_playlists_sign_in_button"
                    )
                }
            } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Collaborative Playlists", style = MaterialTheme.typography.titleLarge)
                    Button(
                        onClick = { showCreatePlaylistDialog = true },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("new_collaborative_playlist_button")
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Create")
                    }
                }
            }

            when (playlistsState) {
                is UiState.Loading -> {
                    item {
                        Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                is UiState.Error -> {
                    item {
                        ObsidianGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = playlistsState.message,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
                is UiState.Success -> {
                    val playlists = playlistsState.data
                    if (playlists.isEmpty()) {
                        item {
                            ObsidianGlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(38.dp)
                                    )
                                    Text("No shared playlists yet", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Create a collaborative playlist or generate a Sonic Taste Blend with a friend.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        items(playlists, key = { it.id }) { playlist ->
                            CollaborativePlaylistCard(
                                playlist = playlist,
                                activeTrack = activeTrack,
                                onPlayPlaylistTrack = { item ->
                                    val domainTracks = playlist.parsedTracks().map { p ->
                                        Track(
                                            id = p.trackId,
                                            title = p.title,
                                            artist = p.artist,
                                            artworkUrl = p.artworkUrl,
                                            streamUrl = p.streamUrl,
                                            durationMs = p.durationMs
                                        )
                                    }
                                    val target = domainTracks.firstOrNull { it.id == item.trackId } ?: return@CollaborativePlaylistCard
                                    onPlayTrack(target, domainTracks)
                                },
                                onAddActiveTrack = {
                                    if (activeTrack != null) onAddActiveTrackToPlaylist(playlist, activeTrack)
                                },
                                onUpvoteTrack = { tid -> onUpvotePlaylistTrack(playlist, tid) }
                            )
                        }
                    }
                }
            }
            }
        } else {
            if (pageFilter == LibraryFilter.DEVICE && (hasAudioPermission || filteredTracks.isNotEmpty())) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isScanningDeviceMusic) {
                                    stringResource(R.string.device_songs_scanning)
                                } else {
                                    stringResource(R.string.device_songs_summary, filteredTracks.size)
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Surface(
                            onClick = {
                                if (SafMusicImporter.hasDeviceAudioPermission(context)) {
                                    onScanDeviceAudio(false)
                                } else {
                                    audioPermissionLauncher.launch(requiredAudioPermission)
                                }
                            },
                            enabled = !isScanningDeviceMusic,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("rescan_device_audio_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (isScanningDeviceMusic) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = stringResource(R.string.device_songs_rescan),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (searchQuery.isNotBlank() &&
                !isSearching &&
                !searchError.isNullOrBlank() &&
                pageFilter == LibraryFilter.ALL
            ) {
                item(key = "yt_search_error_banner") {
                    val isOfflineSearchErr = searchError.contains("offline", ignoreCase = true) ||
                        searchError.contains("Network error", ignoreCase = true) ||
                        searchError.contains("timed out", ignoreCase = true) ||
                        searchError.contains("connection", ignoreCase = true)
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.80f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("youtube_search_error_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (isOfflineSearchErr) Icons.Rounded.WifiOff else Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = searchError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { onSearchQuery(searchQuery) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("retry_youtube_search_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Retry")
                            }
                        }
                    }
                }
            }

            if (filteredTracks.isEmpty()) {
                if (pageFilter == LibraryFilter.DEVICE && isScanningDeviceMusic) {
                    item(key = "device_scanning") {
                        ObsidianGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = stringResource(R.string.device_songs_scanning),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }
                } else if (pageFilter == LibraryFilter.DEVICE && !hasAudioPermission) {
                    item(key = "device_permission") {
                        val activity = context as? Activity
                        val showSettingsHint = permissionRequestedOnce &&
                            activity != null &&
                            !ActivityCompat.shouldShowRequestPermissionRationale(activity, requiredAudioPermission)
                        ObsidianGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LibraryMusic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(40.dp)
                                )
                                Text(
                                    text = stringResource(R.string.device_permission_title),
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = stringResource(R.string.device_permission_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = {
                                        if (showSettingsHint) {
                                            val settingsIntent = Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.fromParts("package", context.packageName, null)
                                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(settingsIntent)
                                        } else {
                                            audioPermissionLauncher.launch(requiredAudioPermission)
                                        }
                                    },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("grant_device_audio_permission_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.FolderOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (showSettingsHint) "Open App Permissions"
                                        else stringResource(R.string.device_permission_cta)
                                    )
                                }
                            }
                        }
                    }
                } else if (searchQuery.isNotBlank() && isSearching) {
                    item(key = "search_loading_header") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                                .testTag("library_searching_skeleton")
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "Searching for \"${searchQuery.trim()}\"...",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    items(5, key = { "search_skeleton_$it" }) { skeletonIdx ->
                        SearchTrackSkeletonCard(index = skeletonIdx)
                    }
                } else if (searchQuery.isNotBlank()) {
                    item(key = "search_no_results") {
                        SearchNoResultsCard(
                            query = searchQuery.trim(),
                            filter = pageFilter,
                            onSwitchToAll = { selectedFilter = LibraryFilter.ALL },
                            onClearSearch = {
                                searchQuery = ""
                                onSearchQuery("")
                            }
                        )
                    }
                } else if (pageFilter == LibraryFilter.DEVICE) {
                    item(key = "device_empty") {
                        ObsidianGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.img_empty_library),
                                    contentDescription = "Empty library artwork",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(150.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                )
                                Text(
                                    text = stringResource(R.string.device_empty_title),
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = stringResource(R.string.device_empty_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    item(key = "library_empty_card") {
                        ObsidianGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.img_empty_library),
                                    contentDescription = "Empty library artwork",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(150.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                )
                                Text(
                                    text = when (pageFilter) {
                                        LibraryFilter.DOWNLOADED -> "No downloaded songs yet"
                                        LibraryFilter.ALL -> "No online tracks yet"
                                        else -> "No tracks match this view"
                                    },
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = when (pageFilter) {
                                        LibraryFilter.DOWNLOADED -> "Tap the download button on any online song to save it here for offline playback."
                                        LibraryFilter.ALL -> "Search for any song above or play tracks from For You to populate your online library."
                                        else -> "Switch to Device to browse local songs."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else {
                if (searchQuery.isNotBlank() && pageFilter == LibraryFilter.ALL) {
                    item(key = "search_live_updating") {
                        AnimatedVisibility(visible = isSearching) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Fetching online results...",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                items(
                    items = filteredTracks,
                    key = { it.id },
                    contentType = { "track_row" }
                ) { track ->
                    TrackRowItem(
                        track = track,
                        isActive = activeTrack?.id == track.id,
                        downloadProgress = downloadProgress[track.id],
                        onPlay = { onPlayTrack(track, filteredTracks) },
                        onToggleLike = { onToggleLike(track) },
                        onDownloadClick = { onDownloadClick(track) },
                        onShareClick = { onShareClick(track) }
                    )
                }
            }
        }
            }
        }
    }

    if (showCreatePlaylistDialog) {
        var title by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text("New Collaborative Playlist") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Playlist title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("playlist_title_input")
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Vibe description") },
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val seeds = listOfNotNull(activeTrack).ifEmpty { tracks.take(3) }
                        onCreatePlaylist(title.ifBlank { "Shared Pulse Mix" }, description, seeds)
                        showCreatePlaylistDialog = false
                    },
                    modifier = Modifier.testTag("confirm_create_playlist_button")
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun CollaborativePlaylistCard(
    playlist: CollaborativePlaylistDoc,
    activeTrack: Track?,
    onPlayPlaylistTrack: (com.example.data.model.PlaylistTrackItemMap) -> Unit,
    onAddActiveTrack: () -> Unit,
    onUpvoteTrack: (String) -> Unit
) {
    val parsed = remember(playlist.trackItems) {
        playlist.parsedTracks().sortedByDescending { it.upvotes }
    }
    ObsidianGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("collab_playlist_${playlist.id}"),
        cornerRadius = 20.dp,
        accentTint = UltravioletPulse
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
                    Text(playlist.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "${playlist.collaboratorUids.size} collaborators · ${parsed.size} tracks",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (playlist.description.isNotBlank()) {
                        Text(
                            text = playlist.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                }
                if (activeTrack != null) {
                    OutlinedButton(
                        onClick = onAddActiveTrack,
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Current")
                    }
                }
            }

            parsed.take(8).forEach { item ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { onPlayPlaylistTrack(item) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Play ${item.title}",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(
                            onClick = { onUpvoteTrack(item.trackId) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ThumbUp,
                                contentDescription = "Upvote ${item.title}",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("${item.upvotes}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibrarySegmentedTabBar(
    filters: List<LibraryFilter>,
    selectedFilter: LibraryFilter,
    onSelectFilter: (LibraryFilter) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            val totalWeight = remember(filters) { filters.sumOf { it.widthWeight.toDouble() }.toFloat() }
            val selectedIndex = filters.indexOf(selectedFilter).coerceAtLeast(0)
            val weightBeforeSelected = remember(filters, selectedIndex) {
                filters.take(selectedIndex).sumOf { it.widthWeight.toDouble() }.toFloat()
            }
            val targetOffset = maxWidth * (weightBeforeSelected / totalWeight)
            val targetWidth = maxWidth * (filters[selectedIndex].widthWeight / totalWeight)

            val indicatorOffset by animateDpAsState(
                targetValue = targetOffset,
                animationSpec = tween(durationMillis = 85, easing = FastOutSlowInEasing),
                label = "library_tab_indicator_offset"
            )
            val indicatorWidth by animateDpAsState(
                targetValue = targetWidth,
                animationSpec = tween(durationMillis = 85, easing = FastOutSlowInEasing),
                label = "library_tab_indicator_width"
            )

            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(indicatorWidth)
                    .height(44.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.40f),
                        shape = RoundedCornerShape(18.dp)
                    )
            )

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                filters.forEach { filter ->
                    val isSelected = selectedFilter == filter
                    val labelColor by animateColorAsState(
                        targetValue = if (isSelected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(durationMillis = 70),
                        label = "library_tab_label_${filter.name}"
                    )

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(filter.widthWeight)
                            .height(56.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSelectFilter(filter) }
                            .testTag("library_filter_${filter.name.lowercase()}")
                    ) {
                        Text(
                            text = filter.label,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 13.5.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                            ),
                            color = labelColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchTrackSkeletonCard(index: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "search_skeleton_shimmer")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1250, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "search_shimmer_progress"
    )
    val baseColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val sweepStart = (progress * 900f) - 300f - (index * 45f)
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = Offset(sweepStart, 0f),
        end = Offset(sweepStart + 280f, 180f)
    )
    val titleFractions = listOf(0.76f, 0.62f, 0.84f, 0.68f, 0.72f)
    val artistFractions = listOf(0.46f, 0.54f, 0.40f, 0.50f, 0.44f)

    ObsidianGlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(shimmerBrush)
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(titleFractions[index % titleFractions.size])
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(shimmerBrush)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(artistFractions[index % artistFractions.size])
                        .height(11.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(shimmerBrush)
                )
                Box(
                    modifier = Modifier
                        .width(52.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(shimmerBrush)
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(shimmerBrush)
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(shimmerBrush)
            )
        }
    }
}

@Composable
private fun SearchNoResultsCard(
    query: String,
    filter: LibraryFilter,
    onSwitchToAll: () -> Unit,
    onClearSearch: () -> Unit
) {
    ObsidianGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("library_search_no_results")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f))
            ) {
                Icon(
                    imageVector = Icons.Rounded.SearchOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(26.dp)
                )
            }
            Text(
                text = "No matches for \"$query\"",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Text(
                text = when (filter) {
                    LibraryFilter.ALL -> "Try another song title, artist name, or paste a YouTube link."
                    LibraryFilter.DOWNLOADED -> "None of your downloaded tracks match this search."
                    LibraryFilter.DEVICE -> "No local audio files on your device match this search."
                    LibraryFilter.SHARED -> "Switch to All to search the catalog."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (filter != LibraryFilter.ALL) {
                    Button(
                        onClick = onSwitchToAll,
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Search in All")
                    }
                }
                OutlinedButton(
                    onClick = onClearSearch,
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Clear Search")
                }
            }
        }
    }
}

