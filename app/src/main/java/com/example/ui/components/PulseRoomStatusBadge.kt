package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.PulseRoomDoc
import com.example.data.model.UserProfile
import com.example.ui.theme.MintElectric
import com.example.ui.theme.UltravioletPulse

@Composable
fun PulseRoomStatusBadge(
    room: PulseRoomDoc,
    currentUserId: String,
    knownUsers: List<UserProfile>,
    onOpenRoom: () -> Unit,
    modifier: Modifier = Modifier
) {
    val otherMemberUids = remember(room.memberUids, currentUserId) {
        room.memberUids.filter { it != currentUserId && it.isNotBlank() }.distinct()
    }
    val hasFriendsListening = otherMemberUids.isNotEmpty()
    val totalListeners = room.memberUids.distinct().size.coerceAtLeast(1)

    val usersById = remember(knownUsers) {
        knownUsers.associateBy { it.userId }
    }

    val friendProfiles = remember(otherMemberUids, usersById, room) {
        otherMemberUids.map { memberId ->
            usersById[memberId]?.displayName?.takeIf { it.isNotBlank() }
                ?: if (memberId == room.hostUid && room.hostName.isNotBlank()) room.hostName else "Friend"
        }
    }

    val headlineText = remember(hasFriendsListening, friendProfiles, room.roomName) {
        if (hasFriendsListening) {
            when (friendProfiles.size) {
                1 -> "PulseRoom · Listening with ${friendProfiles.first().split(" ").first()}"
                2 -> "PulseRoom · ${friendProfiles[0].split(" ").first()} & ${friendProfiles[1].split(" ").first()}"
                else -> "PulseRoom · ${friendProfiles.first().split(" ").first()} +${friendProfiles.size - 1} friends"
            }
        } else {
            "PulseRoom Live · ${room.roomName.ifBlank { "Shared Session" }}"
        }
    }

    val subtitleText = if (hasFriendsListening) {
        "${room.roomName} · $totalListeners listening · ${room.moodTag}"
    } else {
        "Live room active · Tap to invite or open queue"
    }

    val badgeAccent = if (hasFriendsListening) MintElectric else UltravioletPulse

    val infiniteTransition = rememberInfiniteTransition(label = "pulseroom_badge_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_dot_scale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_dot_alpha"
    )

    val avatarInitials = remember(friendProfiles, hasFriendsListening, room.hostName) {
        if (hasFriendsListening) {
            friendProfiles.take(3).map { it.take(1).uppercase() }
        } else {
            listOf(room.hostName.take(1).uppercase().ifBlank { "P" })
        }
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        border = BorderStroke(
            width = 1.2.dp,
            brush = Brush.horizontalGradient(
                listOf(
                    badgeAccent.copy(alpha = 0.72f),
                    UltravioletPulse.copy(alpha = 0.5f)
                )
            )
        ),
        tonalElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpenRoom)
            .semantics {
                contentDescription = if (hasFriendsListening) {
                    "Active PulseRoom ${room.roomName} with ${otherMemberUids.size} friends listening. Tap to open room."
                } else {
                    "Active PulseRoom ${room.roomName}. Tap to open room."
                }
            }
            .testTag("pulseroom_status_badge")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Animated live pulse indicator + social audio badge icon
            Box(
                modifier = Modifier.size(34.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(badgeAccent.copy(alpha = pulseAlpha))
                )
                Surface(
                    shape = CircleShape,
                    color = badgeAccent.copy(alpha = 0.2f),
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Groups,
                            contentDescription = null,
                            tint = badgeAccent,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(badgeAccent)
                        .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headlineText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasFriendsListening) badgeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            // Stacked mini participant initials
            Box(
                modifier = Modifier.width((22 + (avatarInitials.size - 1) * 14).dp),
                contentAlignment = Alignment.CenterStart
            ) {
                avatarInitials.forEachIndexed { idx, initial ->
                    Surface(
                        shape = CircleShape,
                        color = if (idx % 2 == 0) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .offset(x = (idx * 14).dp)
                            .size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = initial,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(4.dp))

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "Open PulseRoom",
                tint = badgeAccent,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
