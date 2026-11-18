package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.LyricLine
import com.example.data.model.LyricNoteDoc
import com.example.ui.theme.UltravioletPulse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Snapshot of Media3 playback telemetry + BPM phase passed into beat-reactive lyric visuals.
 */
@Immutable
data class BeatReactiveLyricState(
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val bpm: Int,
    val beatEnergy: Float,
    val beatPhase: Float,
    val primaryAccent: Color,
    val secondaryAccent: Color = UltravioletPulse
)

object SyncedLyricsTimeline {
    private const val LOOKAHEAD_MS = 160L

    fun findActiveLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        val target = positionMs + LOOKAHEAD_MS
        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timestampMs <= target) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    fun computeLineProgress(
        lines: List<LyricLine>,
        activeIndex: Int,
        positionMs: Long,
        totalDurationMs: Long
    ): Float {
        if (activeIndex !in lines.indices) return 0f
        val start = lines[activeIndex].timestampMs
        val end = lines.getOrNull(activeIndex + 1)?.timestampMs
            ?: totalDurationMs.coerceAtLeast(start + 3_500L)
        val span = (end - start).coerceAtLeast(300L)
        return ((positionMs - start).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    }
}

/**
 * Clean song-reactive ambient backdrop behind synced lyrics (without circular rings or bottom wave strokes).
 */
@Composable
fun BeatReactiveLyricBackdrop(
    beatState: BeatReactiveLyricState,
    modifier: Modifier = Modifier
) {
    val animatedPrimary by animateColorAsState(
        targetValue = beatState.primaryAccent,
        animationSpec = tween(durationMillis = 550),
        label = "lyric_bg_primary"
    )
    val animatedSecondary by animateColorAsState(
        targetValue = beatState.secondaryAccent,
        animationSpec = tween(durationMillis = 550),
        label = "lyric_bg_secondary"
    )
    val smoothedEnergy by animateFloatAsState(
        targetValue = if (beatState.isPlaying) beatState.beatEnergy else 0.18f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 360f),
        label = "lyric_backdrop_energy"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // Soft song-colored ambient glow centered behind the active middle lyric band
        val center = Offset(w * 0.5f, h * 0.5f)
        val centerRadius = size.maxDimension * (0.48f + 0.10f * smoothedEnergy)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    animatedPrimary.copy(alpha = 0.20f + 0.10f * smoothedEnergy),
                    animatedSecondary.copy(alpha = 0.08f),
                    Color.Transparent
                ),
                center = center,
                radius = centerRadius
            ),
            radius = centerRadius,
            center = center
        )

        // Subtle atmospheric edge wash along the lower-right corner matching the active song palette
        val cornerCenter = Offset(w * 0.85f, h * 0.92f)
        val cornerRadius = size.maxDimension * 0.55f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    animatedPrimary.copy(alpha = 0.16f),
                    Color.Transparent
                ),
                center = cornerCenter,
                radius = cornerRadius
            ),
            radius = cornerRadius,
            center = cornerCenter
        )
    }
}

/**
 * Reusable synced lyrics display synced in real time to Media3's current playback position:
 * - Active line stays auto-centered vertically in the middle of the viewport
 * - Active line glows dynamically using the playing song's ambient accent color
 * - Clean typography without per-line timestamps, timeline progress bars, or background wave strokes
 */
@Composable
fun SyncedLyricsDisplay(
    lines: List<LyricLine>,
    beatState: BeatReactiveLyricState,
    lyricNotes: List<LyricNoteDoc> = emptyList(),
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    emptyContent: @Composable () -> Unit = {}
) {
    val scope = rememberCoroutineScope()

    val activeIndex by remember(lines, beatState.positionMs) {
        derivedStateOf { SyncedLyricsTimeline.findActiveLineIndex(lines, beatState.positionMs) }
    }

    val animatedAccent by animateColorAsState(
        targetValue = beatState.primaryAccent,
        animationSpec = tween(durationMillis = 500),
        label = "lyric_active_accent"
    )

    var userDetachedScroll by remember(lines) { mutableStateOf(false) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            userDetachedScroll = true
        }
    }

    // Auto-resync back to centered live lyrics shortly after manual scrolling ends
    LaunchedEffect(userDetachedScroll, listState.isScrollInProgress) {
        if (userDetachedScroll && !listState.isScrollInProgress) {
            delay(3_200L)
            userDetachedScroll = false
        }
    }

    LaunchedEffect(lines) {
        userDetachedScroll = false
        if (lines.isNotEmpty()) {
            val targetIdx = activeIndex.coerceIn(0, lines.lastIndex)
            listState.scrollToItem(targetIdx, scrollOffset = 0)
        }
    }

    LaunchedEffect(activeIndex, userDetachedScroll, lines.size) {
        if (!userDetachedScroll && lines.isNotEmpty()) {
            val targetIdx = if (activeIndex in lines.indices) activeIndex else 0
            listState.animateScrollToItem(index = targetIdx, scrollOffset = 0)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("synced_lyrics_display")
    ) {
        BeatReactiveLyricBackdrop(
            beatState = beatState,
            modifier = Modifier.fillMaxSize()
        )

        if (lines.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                emptyContent()
            }
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // Pad top & bottom by ~half viewport so even the first & last lines center cleanly
                val verticalCenterPadding = ((maxHeight / 2f) - 28.dp).coerceAtLeast(36.dp)

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 18.dp,
                        end = 18.dp,
                        top = verticalCenterPadding,
                        bottom = verticalCenterPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        items = lines,
                        key = { idx, line -> "${idx}_${line.timestampMs}" }
                    ) { idx, line ->
                        val isActive = idx == activeIndex
                        val distance = if (activeIndex >= 0) abs(idx - activeIndex) else idx
                        val nextTimestamp = lines.getOrNull(idx + 1)?.timestampMs ?: Long.MAX_VALUE
                        val pinnedNotes = remember(lyricNotes, line.timestampMs, nextTimestamp) {
                            lyricNotes.filter { note ->
                                note.timestampMs >= (line.timestampMs - 500L) &&
                                    note.timestampMs < nextTimestamp
                            }
                        }

                        SyncedLyricLineRow(
                            index = idx,
                            line = line,
                            isActive = isActive,
                            distanceFromActive = distance,
                            beatPhase = beatState.beatPhase,
                            beatEnergy = beatState.beatEnergy,
                            isPlaying = beatState.isPlaying,
                            accentColor = animatedAccent,
                            pinnedNotes = pinnedNotes,
                            onClick = {
                                userDetachedScroll = false
                                onSeekTo(line.timestampMs)
                            }
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = userDetachedScroll && activeIndex in lines.indices,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        userDetachedScroll = false
                        scope.launch {
                            if (activeIndex in lines.indices) {
                                listState.animateScrollToItem(activeIndex, scrollOffset = 0)
                            }
                        }
                    },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("resync_lyrics_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MyLocation,
                        contentDescription = "Jump to active lyric line",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Live Sync")
                }
            }
        }
    }
}

@Composable
private fun SyncedLyricLineRow(
    index: Int,
    line: LyricLine,
    isActive: Boolean,
    distanceFromActive: Int,
    beatPhase: Float,
    beatEnergy: Float,
    isPlaying: Boolean,
    accentColor: Color,
    pinnedNotes: List<LyricNoteDoc>,
    onClick: () -> Unit
) {
    val isDarkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    val targetScale = when {
        isActive && isPlaying -> 1.03f + (beatEnergy * 0.02f)
        isActive -> 1.02f
        else -> 0.96f
    }
    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "lyric_line_scale"
    )

    val targetAlpha = when {
        isActive -> 1f
        distanceFromActive == 1 -> 0.60f
        distanceFromActive == 2 -> 0.44f
        else -> 0.30f
    }
    val animatedAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 240),
        label = "lyric_line_alpha"
    )

    val activeTextColor = if (isDarkSurface) Color.White else MaterialTheme.colorScheme.onSurface
    val inactiveTextColor = MaterialTheme.colorScheme.onSurface

    val textColor by animateColorAsState(
        targetValue = if (isActive) activeTextColor else inactiveTextColor,
        animationSpec = tween(durationMillis = 220),
        label = "lyric_line_color"
    )

    val activeTextStyle = MaterialTheme.typography.titleLarge.copy(
        fontSize = 21.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
        shadow = if (isActive) {
            Shadow(
                color = accentColor.copy(alpha = if (isDarkSurface) 0.92f else 0.55f),
                offset = Offset.Zero,
                blurRadius = if (isPlaying) 24f + (beatEnergy * 8f) else 20f
            )
        } else {
            null
        }
    )

    val inactiveTextStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
                alpha = animatedAlpha
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag("synced_lyric_line_$index"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isActive) {
                ActiveLyricWaveBars(
                    accentColor = accentColor,
                    beatPhase = beatPhase,
                    beatEnergy = beatEnergy,
                    isPlaying = isPlaying,
                    modifier = Modifier.size(width = 16.dp, height = 18.dp)
                )
            }
            Text(
                text = line.text,
                style = if (isActive) activeTextStyle else inactiveTextStyle,
                color = textColor,
                modifier = Modifier.weight(1f)
            )
        }

        if (pinnedNotes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                pinnedNotes.take(2).forEach { note ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "✦ ${note.authorName}: ${note.noteText}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveLyricWaveBars(
    accentColor: Color,
    beatPhase: Float,
    beatEnergy: Float,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val barCount = 3
        val gap = size.width * 0.22f
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        val baseRatios = floatArrayOf(0.55f, 0.95f, 0.72f)
        val phaseAngle = beatPhase * 2f * PI.toFloat()

        for (i in 0 until barCount) {
            val waveOffset = if (isPlaying) {
                0.24f * sin(phaseAngle + i * 1.4f) * beatEnergy.coerceIn(0.25f, 1f)
            } else 0f
            val heightRatio = (baseRatios[i] + waveOffset).coerceIn(0.28f, 1f)
            val barHeight = size.height * heightRatio
            val x = i * (barWidth + gap)
            val y = (size.height - barHeight) / 2f

            // Subtle glow behind each bar matching the song's dynamic color
            drawRoundRect(
                color = accentColor.copy(alpha = 0.35f),
                topLeft = Offset((x - 1f).coerceAtLeast(0f), (y - 1f).coerceAtLeast(0f)),
                size = Size(barWidth + 2f, barHeight + 2f),
                cornerRadius = CornerRadius(barWidth, barWidth)
            )
            drawRoundRect(
                color = accentColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
