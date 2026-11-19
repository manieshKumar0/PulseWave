package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.CollaborativePlaylistDoc
import com.example.data.model.Track
import com.example.data.model.TrackSourceType
import com.example.data.model.UserProfile
import com.example.ui.theme.CoralWave
import com.example.ui.theme.MintElectric
import com.example.ui.theme.UltravioletPulse
import kotlin.math.sin

@Composable
fun PulseWaveBrandLogo(
    size: Dp = 36.dp,
    showBackdrop: Boolean = true,
    modifier: Modifier = Modifier
) {
    val corner = (size * 0.28f).coerceAtLeast(8.dp)
    if (showBackdrop) {
        Surface(
            modifier = modifier
                .size(size)
                .testTag("pulsewave_brand_logo"),
            shape = RoundedCornerShape(corner),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            border = BorderStroke(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)
                    )
                )
            )
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_pulsewave_logo),
                contentDescription = "PulseWave logo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding((size * 0.14f).coerceAtLeast(3.dp))
            )
        }
    } else {
        Image(
            painter = painterResource(id = R.drawable.ic_pulsewave_logo),
            contentDescription = "PulseWave logo",
            contentScale = ContentScale.Fit,
            modifier = modifier
                .size(size)
                .testTag("pulsewave_brand_logo")
        )
    }
}

@Composable
fun ObsidianGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    accentTint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val baseModifier = if (onClick != null) {
        modifier.clip(shape).clickable(onClick = onClick)
    } else {
        modifier.clip(shape)
    }

    Surface(
        modifier = baseModifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    accentTint.copy(alpha = 0.36f),
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.48f),
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.28f)
                )
            )
        ),
        tonalElevation = 4.dp
    ) {
        Column(content = content)
    }
}

fun Modifier.dynamicAmbientGlow(
    primaryGlow: Color,
    secondaryGlow: Color = UltravioletPulse,
    intensity: Float = 0.28f
): Modifier = this.drawBehind {
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                primaryGlow.copy(alpha = intensity),
                Color.Transparent
            ),
            center = Offset(size.width * 0.2f, size.height * 0.14f),
            radius = size.maxDimension * 0.72f
        )
    )
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                secondaryGlow.copy(alpha = intensity * 0.7f),
                Color.Transparent
            ),
            center = Offset(size.width * 0.85f, size.height * 0.78f),
            radius = size.maxDimension * 0.65f
        )
    )
}

@Composable
fun TrackArtworkThumb(
    artworkUrl: String,
    title: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.secondaryContainer
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        if (artworkUrl.isNotBlank()) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = "$title artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun BeatReactiveWaveformCanvas(
    energy: Float,
    isPlaying: Boolean,
    primaryColor: Color,
    secondaryColor: Color = UltravioletPulse,
    modifier: Modifier = Modifier
) {
    val animatedEnergy by animateFloatAsState(
        targetValue = if (isPlaying) energy else 0.18f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 420f),
        label = "beat_energy"
    )
    Canvas(modifier = modifier) {
        val barCount = 28
        val spacing = size.width / (barCount * 1.45f)
        val barWidth = (size.width - spacing * (barCount - 1)) / barCount
        for (i in 0 until barCount) {
            val phase = sin((i * 0.55f) + (animatedEnergy * 6.5f))
            val envelope = (0.35f + 0.65f * kotlin.math.abs(phase)) * animatedEnergy
            val barHeight = (size.height * envelope).coerceAtLeast(6f)
            val top = (size.height - barHeight) / 2f
            val x = i * (barWidth + spacing)
            val color = if (i % 3 == 0) secondaryColor else primaryColor
            drawRoundRect(
                color = color.copy(alpha = 0.85f),
                topLeft = Offset(x, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
fun MiniPlayerBar(
    track: Track,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTogglePlay: () -> Unit,
    onSkipNext: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    ObsidianGlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("mini_player_bar"),
        cornerRadius = 20.dp,
        accentTint = MaterialTheme.colorScheme.primary,
        onClick = onOpenNowPlaying
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrackArtworkThumb(
                    artworkUrl = track.displayArtworkUri,
                    title = track.title,
                    modifier = Modifier.size(46.dp),
                    cornerRadius = 12.dp
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "${track.artist} · ${track.bpm} BPM",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("mini_player_play_pause")
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = onSkipNext,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("mini_player_next")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Next track"
                    )
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@Composable
fun TrackRowItem(
    track: Track,
    isActive: Boolean,
    downloadProgress: Float?,
    onPlay: () -> Unit,
    onToggleLike: () -> Unit,
    onDownloadClick: () -> Unit,
    onShareClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ObsidianGlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("track_row_${track.id}"),
        cornerRadius = 18.dp,
        accentTint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        onClick = onPlay
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrackArtworkThumb(
                artworkUrl = track.displayArtworkUri,
                title = track.title,
                modifier = Modifier.size(54.dp),
                cornerRadius = 14.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${track.genre} · ${track.bpm} BPM",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            maxLines = 1
                        )
                    }
                    if (track.isDownloaded) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = "Downloaded offline",
                            tint = MintElectric,
                            modifier = Modifier.size(14.dp)
                        )
                    } else if (track.sourceType == TrackSourceType.CLOUD_SAF) {
                        Icon(
                            imageVector = Icons.Rounded.CloudDone,
                            contentDescription = "Cloud storage track",
                            tint = UltravioletPulse,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            val haptics = LocalHapticFeedback.current
            val likeScale by animateFloatAsState(
                targetValue = if (track.isLiked) 1.15f else 1.0f,
                animationSpec = spring(dampingRatio = 0.45f, stiffness = 500f),
                label = "row_like_scale"
            )

            IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onToggleLike()
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("track_like_${track.id}")
            ) {
                Icon(
                    imageVector = if (track.isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (track.isLiked) "Unlike track" else "Like track",
                    tint = if (track.isLiked) CoralWave else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer {
                        scaleX = likeScale
                        scaleY = likeScale
                    }
                )
            }

            if (track.canDownloadOffline || track.isDownloaded) {
                IconButton(
                    onClick = {
                        if (downloadProgress == null) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDownloadClick()
                        }
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("track_download_${track.id}")
                ) {
                    when {
                        downloadProgress != null && downloadProgress < 0.08f -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        downloadProgress != null -> {
                            val animatedProgress by animateFloatAsState(
                                targetValue = downloadProgress.coerceIn(0f, 1f),
                                animationSpec = spring(stiffness = 300f),
                                label = "row_download_progress"
                            )
                            CircularProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.2.dp,
                                color = MintElectric
                            )
                        }
                        track.isDownloaded -> Icon(
                            imageVector = Icons.Rounded.DownloadDone,
                            contentDescription = "Remove offline download",
                            tint = MintElectric
                        )
                        else -> Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "Download offline"
                        )
                    }
                }
            }

            IconButton(
                onClick = onShareClick,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = "Share track"
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareMusicSheet(
    track: Track,
    activeSoundscapeName: String,
    friends: List<UserProfile>,
    playlists: List<CollaborativePlaylistDoc>,
    activeRoomId: String?,
    onShareToFeed: (caption: String, lyricQuote: String) -> Unit,
    onSendToFriendDm: (UserProfile, String) -> Unit,
    onAddToPlaylist: (CollaborativePlaylistDoc) -> Unit,
    onQueueInPulseRoom: () -> Unit,
    onDismiss: () -> Unit
) {
    var caption by remember { mutableStateOf("On heavy repeat right now with $activeSoundscapeName") }
    var lyricQuote by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Share \"${track.title}\"",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${track.artist} · ${track.genre}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                label = { Text("Feed Caption / Note") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2
            )
            OutlinedTextField(
                value = lyricQuote,
                onValueChange = { lyricQuote = it },
                label = { Text("Optional Lyric Highlight") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1
            )

            Button(
                onClick = {
                    onShareToFeed(caption, lyricQuote)
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("share_to_feed_button")
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Post to Profile Feed")
            }

            if (activeRoomId != null) {
                OutlinedButton(
                    onClick = {
                        onQueueInPulseRoom()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Add to Active PulseRoom Queue")
                }
            }

            if (friends.isNotEmpty()) {
                Text(
                    text = "Send Directly in DM",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                friends.take(4).forEach { friend ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                onSendToFriendDm(friend, caption)
                                onDismiss()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(friend.displayName, style = MaterialTheme.typography.titleMedium)
                            Text("@${friend.handle}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send DM", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (playlists.isNotEmpty()) {
                Text(
                    text = "Add to Collaborative Playlist",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
                playlists.take(4).forEach { pl ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                onAddToPlaylist(pl)
                                onDismiss()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(pl.title, style = MaterialTheme.typography.titleMedium)
                            Text("${pl.trackItems.size} tracks · ${pl.collaboratorUids.size} collaborators", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = "Add to playlist", tint = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
}

@Composable
fun GoogleGLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.20f
        val inset = stroke / 2f
        val arcSize = Size(w - stroke, h - stroke)
        val topLeft = Offset(inset, inset)

        // Blue top-right & horizontal bar
        drawArc(
            color = Color(0xFF4285F4),
            startAngle = -15f,
            sweepAngle = 105f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        // Green bottom-left
        drawArc(
            color = Color(0xFF34A853),
            startAngle = 90f,
            sweepAngle = 95f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        // Yellow left
        drawArc(
            color = Color(0xFFFBBC05),
            startAngle = 185f,
            sweepAngle = 60f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        // Red top
        drawArc(
            color = Color(0xFFEA4335),
            startAngle = 245f,
            sweepAngle = 75f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Butt)
        )
        // Center-right horizontal crossbar of the G
        drawLine(
            color = Color(0xFF4285F4),
            start = Offset(w * 0.50f, h * 0.50f),
            end = Offset(w - inset * 0.35f, h * 0.50f),
            strokeWidth = stroke,
            cap = StrokeCap.Butt
        )
    }
}

@Composable
fun GoogleSignInGateCard(
    title: String,
    subtitle: String,
    isSigningIn: Boolean = false,
    onSignInClick: () -> Unit,
    buttonTestTag: String = "gate_sign_in_button",
    modifier: Modifier = Modifier
) {
    ObsidianGlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        accentTint = UltravioletPulse
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                modifier = Modifier.size(54.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onSignInClick,
                enabled = !isSigningIn,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag(buttonTestTag)
            ) {
                if (isSigningIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Signing in with Google...")
                } else {
                    Surface(
                        shape = CircleShape,
                        color = Color.White,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            GoogleGLogo(modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("Sign in with Google", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun GoogleSignInRequiredDialog(
    featureTitle: String,
    isSigningIn: Boolean,
    onDismiss: () -> Unit,
    onSignInWithGoogle: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(
                shape = CircleShape,
                color = Color.White,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    GoogleGLogo(modifier = Modifier.size(24.dp))
                }
            }
        },
        title = {
            Text(
                text = "Sign in to unlock $featureTitle",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Text(
                text = "You're currently listening in Guest Mode. Sign in with your Google account to use Cloud Drive sync, Social Feed, PulseRooms, collaborative playlists, and shared lyric notes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = onSignInWithGoogle,
                enabled = !isSigningIn,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("guest_prompt_sign_in_button")
            ) {
                if (isSigningIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Signing in...")
                } else {
                    Text("Sign in with Google")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("guest_prompt_cancel_button")
            ) {
                Text("Keep Listening")
            }
        }
    )
}

fun formatMillis(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%d:%02d".format(min, sec)
}

