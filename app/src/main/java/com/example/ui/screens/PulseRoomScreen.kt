package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.DefaultSoundscapes
import com.example.data.model.PulseRoomDoc
import com.example.data.model.RoomQueueItemDoc
import com.example.data.model.RoomReactionDoc
import com.example.data.model.SoundscapePreset
import com.example.data.model.Track
import com.example.media.PlaybackUiState
import com.example.ui.components.BeatReactiveWaveformCanvas
import com.example.ui.components.ObsidianGlassCard
import com.example.ui.components.TrackArtworkThumb
import com.example.ui.components.VisualizerStyle
import com.example.ui.theme.MintElectric
import com.example.ui.theme.UltravioletPulse

@Composable
fun PulseRoomScreen(
    roomId: String,
    currentUserId: String,
    room: PulseRoomDoc?,
    queue: List<RoomQueueItemDoc>,
    reactions: List<RoomReactionDoc>,
    playbackState: PlaybackUiState,
    libraryTracks: List<Track>,
    onBack: () -> Unit,
    onEndOrLeaveRoom: () -> Unit = {},
    onRenameRoom: (String) -> Unit = {},
    onSyncListenerPlayback: (Track, Boolean, Long, SoundscapePreset?) -> Unit,
    onHostBroadcastPlayback: (Track, Boolean, Long, String) -> Unit,
    onToggleHostPlayPause: () -> Unit,
    onEnqueueTrack: (Track) -> Unit,
    onSendReaction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isHost = room?.hostUid == currentUserId
    var showRenameDialog by remember { mutableStateOf(false) }

    LaunchedEffect(room?.currentTrackId, room?.playing, room?.positionMs, room?.soundscapePreset, isHost) {
        if (room != null && !isHost && room.currentTrackId.isNotBlank()) {
            val roomTrack = Track(
                id = room.currentTrackId,
                title = room.trackTitle,
                artist = room.artist,
                artworkUrl = room.artworkUrl,
                streamUrl = room.streamUrl
            )
            val preset = DefaultSoundscapes.presets.firstOrNull { it.name == room.soundscapePreset }
            onSyncListenerPlayback(roomTrack, room.playing, room.positionMs, preset)
        }
    }

    val reactionEmojis = listOf("🔥", "🌊", "⚡", "💜", "🎧")

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("pulseroom_back_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to rooms")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = room?.roomName ?: "PulseRoom $roomId",
                            style = MaterialTheme.typography.headlineMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isHost) {
                            IconButton(
                                onClick = { showRenameDialog = true },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("pulseroom_rename_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = "Rename room",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = if (room != null) {
                            "${room.memberUids.size} listening · ${room.moodTag} · ${room.soundscapePreset}"
                        } else "Connecting to live room...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Rounded.Groups, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("${room?.memberUids?.size ?: 1}", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        item {
            ObsidianGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, accentTint = MintElectric) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TrackArtworkThumb(
                            artworkUrl = room?.artworkUrl ?: playbackState.currentTrack?.displayArtworkUri.orEmpty(),
                            title = room?.trackTitle ?: "Track",
                            modifier = Modifier.size(72.dp),
                            cornerRadius = 16.dp
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isHost) "YOU ARE HOSTING" else "SYNCED TO ${room?.hostName?.uppercase() ?: "HOST"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MintElectric
                            )
                            Text(
                                text = room?.trackTitle ?: playbackState.currentTrack?.title ?: "Waiting for track",
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = room?.artist ?: playbackState.currentTrack?.artist ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    BeatReactiveWaveformCanvas(
                        energy = playbackState.beatPulseEnergy,
                        isPlaying = playbackState.isPlaying,
                        primaryColor = MintElectric,
                        secondaryColor = UltravioletPulse,
                        style = VisualizerStyle.CLASSIC_PULSE,
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    )

                    if (isHost && playbackState.currentTrack != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    onToggleHostPlayPause()
                                    onHostBroadcastPlayback(
                                        playbackState.currentTrack,
                                        !playbackState.isPlaying,
                                        playbackState.positionMs,
                                        playbackState.activeSoundscape.name
                                    )
                                },
                                modifier = Modifier.weight(1f).height(48.dp).testTag("pulseroom_host_play_pause")
                            ) {
                                Icon(
                                    imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = null
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (playbackState.isPlaying) "Pause Room" else "Play Room")
                            }
                            OutlinedButton(
                                onClick = {
                                    onHostBroadcastPlayback(
                                        playbackState.currentTrack,
                                        playbackState.isPlaying,
                                        playbackState.positionMs,
                                        playbackState.activeSoundscape.name
                                    )
                                },
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Icon(Icons.Rounded.Sync, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Sync All")
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = onEndOrLeaveRoom,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("pulseroom_end_or_leave_button")
                    ) {
                        Icon(
                            imageVector = if (isHost) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ExitToApp,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (isHost) "End Listening Room" else "Leave Listening Room")
                    }
                }
            }
        }

        // Live Pulse Reactions
        item {
            ObsidianGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Live Pulse Reactions", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        reactionEmojis.forEach { emoji ->
                            OutlinedButton(
                                onClick = { onSendReaction(emoji) },
                                modifier = Modifier.height(48.dp).testTag("reaction_$emoji")
                            ) {
                                Text(emoji, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    if (reactions.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(reactions, key = { it.id }) { reaction ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = "${reaction.emoji} ${reaction.senderName}",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Shared Live Queue
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Shared Live Queue (${queue.size})", style = MaterialTheme.typography.titleLarge)
                val quickTrack = playbackState.currentTrack ?: libraryTracks.firstOrNull()
                if (quickTrack != null) {
                    OutlinedButton(
                        onClick = { onEnqueueTrack(quickTrack) },
                        modifier = Modifier.height(48.dp).testTag("enqueue_current_track_button")
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Queue Track")
                    }
                }
            }
        }

        if (queue.isEmpty()) {
            item {
                ObsidianGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Queue is empty. Anyone in the room can drop tracks into the shared lineup!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(queue, key = { it.id }) { item ->
                ObsidianGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TrackArtworkThumb(
                            artworkUrl = item.artworkUrl,
                            title = item.title,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Added by ${item.addedByName} · ${item.artist}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        if (isHost) {
                            IconButton(
                                onClick = {
                                    onHostBroadcastPlayback(
                                        Track(
                                            id = item.trackId,
                                            title = item.title,
                                            artist = item.artist,
                                            artworkUrl = item.artworkUrl,
                                            streamUrl = item.streamUrl
                                        ),
                                        true,
                                        0L,
                                        playbackState.activeSoundscape.name
                                    )
                                },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play queued track for room", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRenameDialog && room != null) {
        var editedName by remember(room.roomName) { mutableStateOf(room.roomName) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Room") },
            text = {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    label = { Text("Room Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("pulseroom_rename_input")
                )
            },
            confirmButton = {
                Button(
                    enabled = editedName.isNotBlank(),
                    onClick = {
                        onRenameRoom(editedName.trim())
                        showRenameDialog = false
                    },
                    modifier = Modifier.testTag("confirm_rename_pulseroom_button")
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
