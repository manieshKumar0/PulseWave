package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

private enum class LibraryFilter(val label: String) {
    ALL("All"),
    DOWNLOADED("Downloaded"),
    DEVICE("Device"),
    CLOUD_DRIVE("Cloud Drive"),
    SHARED_PLAYLISTS("Shared Playlists")
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
    var selectedFilter by remember { mutableStateOf(LibraryFilter.ALL) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, selectedFilter) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val permitted = SafMusicImporter.hasDeviceAudioPermission(context)
                hasAudioPermission = permitted
                if (permitted && selectedFilter == LibraryFilter.DEVICE) {
                    onScanDeviceAudio(true)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val filteredTracks = remember(tracks, searchResults, searchQuery, selectedFilter) {
        val base = if (searchQuery.isNotBlank()) searchResults else tracks
        when (selectedFilter) {
            LibraryFilter.ALL -> base.filter {
                it.sourceType != TrackSourceType.LOCAL_SAF &&
                    it.sourceType != TrackSourceType.CLOUD_SAF
            }
            LibraryFilter.DOWNLOADED -> base.filter { it.isDownloaded }
            LibraryFilter.DEVICE -> base.filter {
                it.sourceType == TrackSourceType.LOCAL_SAF &&
                    (!it.id.startsWith(SafMusicImporter.DEVICE_TRACK_ID_PREFIX) ||
                        it.durationMs > SafMusicImporter.DEFAULT_MIN_DURATION_MS)
            }
            LibraryFilter.CLOUD_DRIVE -> base.filter { it.sourceType == TrackSourceType.CLOUD_SAF }
            LibraryFilter.SHARED_PLAYLISTS -> emptyList()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            PulseScreenHeader(
                title = "Library & Vault",
                titleTestTag = "library_header_title"
            )
        }

        // Search input
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    onSearchQuery(it)
                },
                placeholder = { Text("Search any song, artist, or YouTube link") },
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
        }

        // Filter Chips
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LibraryFilter.entries) { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = {
                            selectedFilter = filter
                            if (filter == LibraryFilter.DEVICE) {
                                val permitted = SafMusicImporter.hasDeviceAudioPermission(context)
                                hasAudioPermission = permitted
                                if (permitted) {
                                    onScanDeviceAudio(true)
                                } else {
                                    audioPermissionLauncher.launch(requiredAudioPermission)
                                }
                            }
                        },
                        label = { Text(filter.label) },
                        modifier = Modifier.testTag("library_filter_${filter.name.lowercase()}")
                    )
                }
            }
        }

        if (selectedFilter == LibraryFilter.SHARED_PLAYLISTS) {
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
            if (selectedFilter == LibraryFilter.DEVICE && (hasAudioPermission || filteredTracks.isNotEmpty())) {
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
                        OutlinedButton(
                            onClick = {
                                if (SafMusicImporter.hasDeviceAudioPermission(context)) {
                                    onScanDeviceAudio(false)
                                } else {
                                    audioPermissionLauncher.launch(requiredAudioPermission)
                                }
                            },
                            enabled = !isScanningDeviceMusic,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("rescan_device_audio_button")
                        ) {
                            if (isScanningDeviceMusic) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.device_songs_rescan))
                        }
                    }
                }
            }

            if (searchQuery.isNotBlank() &&
                !isSearching &&
                !searchError.isNullOrBlank() &&
                selectedFilter == LibraryFilter.ALL
            ) {
                item(key = "yt_search_error_banner") {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("youtube_search_error_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = searchError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
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
                item {
                    if (selectedFilter == LibraryFilter.DEVICE && isScanningDeviceMusic) {
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
                    } else if (selectedFilter == LibraryFilter.DEVICE && !hasAudioPermission) {
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
                    } else if (selectedFilter == LibraryFilter.DEVICE) {
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
                    } else {
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
                                    text = when (selectedFilter) {
                                        LibraryFilter.DOWNLOADED -> "No downloaded songs yet"
                                        LibraryFilter.ALL -> "No online tracks match this view"
                                        else -> "No tracks match this view"
                                    },
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = when (selectedFilter) {
                                        LibraryFilter.DOWNLOADED -> "Tap the download button on any online song to save it here for offline playback."
                                        LibraryFilter.ALL -> "Search for any song above or play tracks from For You to populate your online library."
                                        else -> "Import audio from your Cloud Drive in Settings, or switch to Device to browse local songs."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else {
                items(filteredTracks, key = { it.id }) { track ->
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
