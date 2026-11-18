package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.Track

fun defaultPulseRoomName(displayName: String): String {
    val cleaned = displayName.trim()
    if (cleaned.isBlank() || cleaned.equals("Pulse Listener", ignoreCase = true)) {
        return "My Listening Room"
    }
    val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
    val shortName = words.take(2).joinToString(" ").ifBlank { cleaned }
    return "$shortName's Room"
}

@Composable
fun CreatePulseRoomDialog(
    defaultRoomName: String,
    seedTrack: Track?,
    initialMoodTag: String = seedTrack?.mood?.ifBlank { "Chillwave" } ?: "Chillwave",
    onDismiss: () -> Unit,
    onConfirmCreate: (roomName: String, moodTag: String) -> Unit
) {
    var roomName by remember(defaultRoomName) { mutableStateOf(defaultRoomName) }
    var moodTag by remember(seedTrack?.id) { mutableStateOf(initialMoodTag) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Radio,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Create Listening Room?",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Confirm to start a live PulseRoom session. Friends can join and listen in sync with you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = roomName,
                    onValueChange = { roomName = it },
                    label = { Text("Room Name") },
                    placeholder = { Text(defaultRoomName) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pulseroom_name_input")
                )
                OutlinedTextField(
                    value = moodTag,
                    onValueChange = { moodTag = it },
                    label = { Text("Soundscape / Mood Tag") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pulseroom_mood_input")
                )
                if (seedTrack != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Starting Track",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "${seedTrack.title} · ${seedTrack.artist}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = seedTrack != null && roomName.isNotBlank(),
                onClick = {
                    val resolvedName = roomName.trim().ifBlank { defaultRoomName }
                    val resolvedMood = moodTag.trim().ifBlank { "Chillwave" }
                    onConfirmCreate(resolvedName, resolvedMood)
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_create_pulseroom_button")
            ) {
                Text("Create Room")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("cancel_create_pulseroom_button")
            ) {
                Text("Cancel")
            }
        }
    )
}
