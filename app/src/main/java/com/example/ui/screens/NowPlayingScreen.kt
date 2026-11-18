package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.example.data.model.DefaultSoundscapes
import com.example.data.model.LyricLine
import com.example.data.model.LyricNoteDoc
import com.example.data.model.PulseRoomDoc
import com.example.data.model.SonicDnaProfile
import com.example.data.model.SoundscapePreset
import com.example.data.model.Track
import com.example.data.model.UserProfile
import com.example.media.PlaybackUiState
import com.example.ui.components.BeatReactiveLyricState
import com.example.ui.components.BeatReactiveWaveformCanvas
import com.example.ui.components.CreatePulseRoomDialog
import com.example.ui.components.ObsidianGlassCard
import com.example.ui.components.PulseRoomStatusBadge
import com.example.ui.components.PulseWaveBrandLogo
import com.example.ui.components.SonicDnaRadarChart
import com.example.ui.components.SyncedLyricsDisplay
import com.example.ui.components.TrackArtworkThumb
import com.example.ui.components.dynamicAmbientGlow
import com.example.ui.components.formatMillis
import com.example.ui.theme.CoralWave
import com.example.ui.theme.MintElectric
import com.example.ui.theme.UltravioletPulse
import com.example.ui.viewmodel.UiState
import kotlin.math.roundToInt

@Composable
fun NowPlayingScreen(
    playbackState: PlaybackUiState,
    parsedLyrics: List<LyricLine>,
    isLoadingLyrics: Boolean = false,
    aiTrackStory: UiState<String>?,
    lyricNotes: List<LyricNoteDoc>,
    sonicDna: SonicDnaProfile,
    albumAccent: Color?,
    downloadProgress: Float?,
    activePulseRoom: PulseRoomDoc? = null,
    currentUserId: String = "",
    isSignedIn: Boolean = true,
    knownUsers: List<UserProfile> = emptyList(),
    defaultRoomName: String = "My Listening Room",
    onRequestSignIn: (String) -> Unit = {},
    onOpenPulseRoom: (PulseRoomDoc) -> Unit = {},
    onClose: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleLike: (Track) -> Unit,
    onDownloadClick: (Track) -> Unit,
    onShareClick: (Track) -> Unit,
    onStartPulseRoom: (roomName: String, moodTag: String, track: Track) -> Unit,
    onRetryLyrics: (Track, String?, String?) -> Unit = { _, _, _ -> },
    onGenerateAiStory: (Track) -> Unit,
    onAddLyricNote: (Track, Long, String) -> Unit,
    onApplyPreset: (SoundscapePreset) -> Unit,
    onMorphSoundscape: (SoundscapePreset, SoundscapePreset, Float) -> Unit,
    onUpdateBand: (Int, Int) -> Unit,
    onUpdateBassBoost: (Int) -> Unit,
    onUpdateReverbPreset: (Int) -> Unit
) {
    BackHandler(onBack = onClose)

    val track = playbackState.currentTrack ?: return
    val glowPrimary = albumAccent ?: MaterialTheme.colorScheme.primary
    var selectedStudioTab by remember { mutableIntStateOf(0) }
    var showCreateRoomDialog by remember { mutableStateOf(false) }
    val studioTabs = listOf("Player", "Lyrics", "Soundscape", "DNA")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .dynamicAmbientGlow(glowPrimary, UltravioletPulse, intensity = 0.42f)
            .safeDrawingPadding()
    ) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("collapse_now_playing")
            ) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Collapse player")
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PulseWaveBrandLogo(size = 24.dp, showBackdrop = false)
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = playbackState.activeSoundscape.name.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${track.sourceType.label} · ${track.bpm} BPM",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row {
                IconButton(
                    onClick = {
                        if (!isSignedIn) {
                            onRequestSignIn("PulseRooms co-listening")
                        } else if (activePulseRoom != null && activePulseRoom.active) {
                            onOpenPulseRoom(activePulseRoom)
                        } else {
                            showCreateRoomDialog = true
                        }
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("now_playing_pulseroom_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Radio,
                        contentDescription = if (activePulseRoom != null && activePulseRoom.active) "Open active PulseRoom" else "Create PulseRoom",
                        tint = if (activePulseRoom != null && activePulseRoom.active) MintElectric else MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(
                    onClick = { onShareClick(track) },
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("now_playing_share_button")
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = "Share track")
                }
            }
        }

        AnimatedVisibility(
            visible = activePulseRoom != null && activePulseRoom.active,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            activePulseRoom?.let { room ->
                PulseRoomStatusBadge(
                    room = room,
                    currentUserId = currentUserId,
                    knownUsers = knownUsers,
                    onOpenRoom = { onOpenPulseRoom(room) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        TabRow(
            selectedTabIndex = selectedStudioTab,
            containerColor = Color.Transparent
        ) {
            studioTabs.forEachIndexed { idx, title ->
                Tab(
                    selected = selectedStudioTab == idx,
                    onClick = { selectedStudioTab = idx },
                    text = { Text(title, maxLines = 1) },
                    modifier = Modifier.testTag("studio_tab_${title.lowercase()}")
                )
            }
        }

        when (selectedStudioTab) {
            0 -> PlayerTab(
                track = track,
                playbackState = playbackState,
                glowPrimary = glowPrimary,
                downloadProgress = downloadProgress,
                onTogglePlayPause = onTogglePlayPause,
                onSeekTo = onSeekTo,
                onSkipNext = onSkipNext,
                onSkipPrevious = onSkipPrevious,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
                onToggleLike = { onToggleLike(track) },
                onDownloadClick = { onDownloadClick(track) }
            )
            1 -> LyricCanvasTab(
                track = track,
                playbackState = playbackState,
                parsedLyrics = parsedLyrics,
                isLoadingLyrics = isLoadingLyrics,
                aiTrackStory = aiTrackStory,
                lyricNotes = lyricNotes,
                glowPrimary = glowPrimary,
                isSignedIn = isSignedIn,
                onRequestSignIn = onRequestSignIn,
                onSeekTo = onSeekTo,
                onRetryLyrics = { customTitle, customArtist ->
                    onRetryLyrics(track, customTitle, customArtist)
                },
                onGenerateAiStory = { onGenerateAiStory(track) },
                onAddLyricNote = { note -> onAddLyricNote(track, playbackState.positionMs, note) }
            )
            2 -> SoundscapeLabTab(
                playbackState = playbackState,
                onApplyPreset = onApplyPreset,
                onMorphSoundscape = onMorphSoundscape,
                onUpdateBand = onUpdateBand,
                onUpdateBassBoost = onUpdateBassBoost,
                onUpdateReverbPreset = onUpdateReverbPreset
            )
            3 -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ObsidianGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Acoustic Telemetry", style = MaterialTheme.typography.titleLarge)
                        Text("Genre: ${track.genre} · Mood: ${track.mood}", style = MaterialTheme.typography.bodyLarge)
                        Text("Tempo: ${track.bpm} BPM · Album: ${track.album}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ObsidianGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Your Sonic DNA Profile", style = MaterialTheme.typography.titleLarge)
                        SonicDnaRadarChart(primaryProfile = sonicDna)
                    }
                }
            }
        }
    }

    if (showCreateRoomDialog) {
        CreatePulseRoomDialog(
            defaultRoomName = defaultRoomName,
            seedTrack = track,
            onDismiss = { showCreateRoomDialog = false },
            onConfirmCreate = { roomName, moodTag ->
                showCreateRoomDialog = false
                onStartPulseRoom(roomName, moodTag, track)
            }
        )
    }
}

@Composable
private fun PlayerTab(
    track: Track,
    playbackState: PlaybackUiState,
    glowPrimary: Color,
    downloadProgress: Float?,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleLike: () -> Unit,
    onDownloadClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TrackArtworkThumb(
            artworkUrl = track.displayArtworkUri,
            title = track.title,
            modifier = Modifier
                .fillMaxWidth(0.84f)
                .height(270.dp),
            cornerRadius = 28.dp
        )

        BeatReactiveWaveformCanvas(
            energy = playbackState.beatPulseEnergy,
            isPlaying = playbackState.isPlaying,
            primaryColor = glowPrimary,
            secondaryColor = UltravioletPulse,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row {
                    IconButton(
                        onClick = onToggleLike,
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("now_playing_like_button")
                    ) {
                        Icon(
                            imageVector = if (track.isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "Like track",
                            tint = if (track.isLiked) CoralWave else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (track.streamUrl.isNotBlank()) {
                        IconButton(
                            onClick = onDownloadClick,
                            modifier = Modifier.minimumInteractiveComponentSize().testTag("now_playing_download_button")
                        ) {
                            when {
                                downloadProgress != null -> CircularProgressIndicator(
                                    progress = { downloadProgress },
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp
                                )
                                track.isDownloaded -> Icon(
                                    imageVector = Icons.Rounded.DownloadDone,
                                    contentDescription = "Remove offline download",
                                    tint = MintElectric
                                )
                                else -> Icon(Icons.Rounded.Download, contentDescription = "Download offline")
                            }
                        }
                    }
                }
            }
        }

        if (playbackState.playbackError != null) {
            Text(
                text = playbackState.playbackError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        val duration = playbackState.durationMs.coerceAtLeast(1L)
        var draggingPos by remember(track.id) { mutableStateOf<Float?>(null) }
        val sliderValue = draggingPos ?: playbackState.positionMs.toFloat().coerceIn(0f, duration.toFloat())

        Column(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = sliderValue,
                onValueChange = { draggingPos = it },
                onValueChangeFinished = {
                    draggingPos?.let { onSeekTo(it.toLong()) }
                    draggingPos = null
                },
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier.fillMaxWidth().testTag("player_seek_slider")
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatMillis(sliderValue.toLong()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatMillis(duration),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Transport Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onToggleShuffle,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (playbackState.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onSkipPrevious,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("player_prev_button")
            ) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous track", modifier = Modifier.size(34.dp))
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(66.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onTogglePlayPause)
                    .testTag("player_play_pause_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (playbackState.isBuffering) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
            IconButton(
                onClick = onSkipNext,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("player_next_button")
            ) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next track", modifier = Modifier.size(34.dp))
            }
            IconButton(
                onClick = onCycleRepeat,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                val icon = if (playbackState.repeatMode == Player.REPEAT_MODE_ONE) {
                    Icons.Rounded.RepeatOne
                } else {
                    Icons.Rounded.Repeat
                }
                Icon(
                    imageVector = icon,
                    contentDescription = "Repeat mode",
                    tint = if (playbackState.repeatMode != Player.REPEAT_MODE_OFF) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun LyricCanvasTab(
    track: Track,
    playbackState: PlaybackUiState,
    parsedLyrics: List<LyricLine>,
    isLoadingLyrics: Boolean,
    aiTrackStory: UiState<String>?,
    lyricNotes: List<LyricNoteDoc>,
    glowPrimary: Color,
    isSignedIn: Boolean,
    onRequestSignIn: (String) -> Unit,
    onSeekTo: (Long) -> Unit,
    onRetryLyrics: (String?, String?) -> Unit,
    onGenerateAiStory: () -> Unit,
    onAddLyricNote: (String) -> Unit
) {
    val beatState = remember(
        playbackState.positionMs,
        playbackState.durationMs,
        playbackState.isPlaying,
        track.bpm,
        playbackState.beatPulseEnergy,
        playbackState.beatPhase,
        glowPrimary
    ) {
        BeatReactiveLyricState(
            positionMs = playbackState.positionMs,
            durationMs = playbackState.durationMs,
            isPlaying = playbackState.isPlaying,
            bpm = track.bpm,
            beatEnergy = playbackState.beatPulseEnergy,
            beatPhase = playbackState.beatPhase,
            primaryAccent = glowPrimary,
            secondaryAccent = UltravioletPulse
        )
    }

    var noteDraft by remember { mutableStateOf("") }
    var showManualLyricsSearch by remember(track.id) { mutableStateOf(false) }
    var customTitleQuery by remember(track.id) { mutableStateOf(track.title) }
    var customArtistQuery by remember(track.id) {
        val defaultArtist = if (track.artist.equals("Local Device", true) || track.artist.equals("Cloud Drive", true)) "" else track.artist
        mutableStateOf(defaultArtist)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BeatReactiveWaveformCanvas(
            energy = playbackState.beatPulseEnergy,
            isPlaying = playbackState.isPlaying,
            primaryColor = glowPrimary,
            secondaryColor = UltravioletPulse,
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
        )

        ObsidianGlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            cornerRadius = 22.dp,
            accentTint = glowPrimary
        ) {
            SyncedLyricsDisplay(
                lines = parsedLyrics,
                beatState = beatState,
                lyricNotes = lyricNotes,
                onSeekTo = onSeekTo,
                emptyContent = {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (isLoadingLyrics) {
                            CircularProgressIndicator()
                            Text(
                                text = "Fetching synced lyrics for \"${track.title}\"...",
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                        } else {
                            Text(
                                text = "No synced LRC lyrics found yet for \"${track.title}\"",
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Device files with missing ID3 tags can be matched by entering the song title & artist below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onRetryLyrics(null, null) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("retry_fetch_lyrics_button")
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Fetch Lyrics", maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = { showManualLyricsSearch = !showManualLyricsSearch },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("toggle_manual_lyrics_search")
                                ) {
                                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Match Song", maxLines = 1)
                                }
                            }

                            if (showManualLyricsSearch) {
                                OutlinedTextField(
                                    value = customTitleQuery,
                                    onValueChange = { customTitleQuery = it },
                                    label = { Text("Song Title") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("lyrics_custom_title_input")
                                )
                                OutlinedTextField(
                                    value = customArtistQuery,
                                    onValueChange = { customArtistQuery = it },
                                    label = { Text("Artist (Optional)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().testTag("lyrics_custom_artist_input")
                                )
                                Button(
                                    onClick = {
                                        onRetryLyrics(customTitleQuery, customArtistQuery)
                                    },
                                    enabled = customTitleQuery.isNotBlank(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("search_custom_lyrics_button")
                                ) {
                                    Text("Search Lyrics Online")
                                }
                            }

                            Button(
                                onClick = onGenerateAiStory,
                                modifier = Modifier.height(48.dp).testTag("generate_ai_vibe_story_button")
                            ) {
                                Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Generate AI Vibe Liner Notes")
                            }
                        }

                        when (aiTrackStory) {
                            is UiState.Loading -> CircularProgressIndicator()
                            is UiState.Success -> {
                                Text(
                                    text = aiTrackStory.data,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center
                                )
                            }
                            is UiState.Error -> {
                                Text(aiTrackStory.message, color = MaterialTheme.colorScheme.error)
                            }
                            null -> Unit
                        }
                    }
                }
            )
        }

        if (isSignedIn) {
            // Shared Friend Lyric Notes
            if (lyricNotes.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(lyricNotes, key = { it.id }) { note ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.clickable { onSeekTo(note.timestampMs) }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(
                                    text = "${note.authorName} @ ${formatMillis(note.timestampMs)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(note.noteText, style = MaterialTheme.typography.bodyMedium)
                            }
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
                    value = noteDraft,
                    onValueChange = { noteDraft = it },
                    placeholder = { Text("Pin friend note @ ${formatMillis(playbackState.positionMs)}") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("lyric_note_input")
                )
                Button(
                    onClick = {
                        if (noteDraft.isNotBlank()) {
                            onAddLyricNote(noteDraft)
                            noteDraft = ""
                        }
                    },
                    modifier = Modifier.height(50.dp).testTag("pin_lyric_note_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Pin lyric note")
                }
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Sign in with Google to view & pin friend lyric notes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { onRequestSignIn("Friend lyric notes") },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("lyric_notes_sign_in_button")
                    ) {
                        Text("Sign in")
                    }
                }
            }
        }
    }
}

@Composable
private fun SoundscapeLabTab(
    playbackState: PlaybackUiState,
    onApplyPreset: (SoundscapePreset) -> Unit,
    onMorphSoundscape: (SoundscapePreset, SoundscapePreset, Float) -> Unit,
    onUpdateBand: (Int, Int) -> Unit,
    onUpdateBassBoost: (Int) -> Unit,
    onUpdateReverbPreset: (Int) -> Unit
) {
    val presets = DefaultSoundscapes.presets
    var morphFrom by remember { mutableStateOf(presets.first()) }
    var morphTo by remember { mutableStateOf(presets.getOrElse(1) { presets.first() }) }
    var morphFraction by remember { mutableFloatStateOf(0f) }

    val bandLabels = DefaultSoundscapes.bandLabels
    val reverbLabels = DefaultSoundscapes.reverbLabels

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Soundscape Presets", style = MaterialTheme.typography.titleLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(presets, key = { it.id }) { preset ->
                FilterChip(
                    selected = playbackState.activeSoundscape.id == preset.id,
                    onClick = { onApplyPreset(preset) },
                    label = { Text(preset.name) },
                    modifier = Modifier.testTag("soundscape_preset_${preset.id}")
                )
            }
        }

        ObsidianGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, accentTint = MintElectric) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Live Soundscape Morph", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Cross-morph DSP EQ & bass in real time between ${morphFrom.name} → ${morphTo.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(presets) { p ->
                        FilterChip(
                            selected = morphTo.id == p.id,
                            onClick = {
                                morphFrom = playbackState.activeSoundscape
                                morphTo = p
                                onMorphSoundscape(morphFrom, morphTo, morphFraction)
                            },
                            label = { Text("To: ${p.name}") }
                        )
                    }
                }
                Slider(
                    value = morphFraction,
                    onValueChange = {
                        morphFraction = it
                        onMorphSoundscape(morphFrom, morphTo, it)
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth().testTag("soundscape_morph_slider")
                )
                Text(
                    text = "Morph Blend: ${(morphFraction * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        ObsidianGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("5-Band Parametric Rack", style = MaterialTheme.typography.titleMedium)
                playbackState.bandLevels.forEachIndexed { idx, millibels ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = bandLabels.getOrElse(idx) { "B$idx" },
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(52.dp)
                        )
                        Slider(
                            value = millibels.toFloat(),
                            onValueChange = { onUpdateBand(idx, it.roundToInt()) },
                            valueRange = -1500f..1500f,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${millibels / 100}dB",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(44.dp),
                            textAlign = TextAlign.End
                        )
                    }
                }

                Text("Sub-Bass Boost (${playbackState.bassBoost / 10}%)", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = playbackState.bassBoost.toFloat(),
                    onValueChange = { onUpdateBassBoost(it.roundToInt()) },
                    valueRange = 0f..1000f,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Spatial Reverb", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(reverbLabels) { idx, label ->
                        FilterChip(
                            selected = playbackState.reverbPreset == idx,
                            onClick = { onUpdateReverbPreset(idx) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    }
}
