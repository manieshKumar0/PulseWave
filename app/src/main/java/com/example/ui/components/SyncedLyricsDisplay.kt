package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.LyricLine
import com.example.data.model.LyricNoteDoc
import com.example.ui.theme.FrauncesFontFamily
import com.example.ui.theme.UltravioletPulse

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
 * Clean song-reactive ambient backdrop behind synced lyrics.
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

        // Soft song-colored ambient glow centered directly behind the active middle lyric line
        val center = Offset(w * 0.5f, h * 0.5f)
        val centerRadius = size.maxDimension * (0.50f + 0.10f * smoothedEnergy)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    animatedPrimary.copy(alpha = 0.28f + 0.10f * smoothedEnergy),
                    animatedSecondary.copy(alpha = 0.10f),
                    Color.Transparent
                ),
                center = center,
                radius = centerRadius
            ),
            radius = centerRadius,
            center = center
        )

        val cornerCenter = Offset(w * 0.85f, h * 0.92f)
        val cornerRadius = size.maxDimension * 0.55f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    animatedPrimary.copy(alpha = 0.14f),
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
 * Focused 3-line synced lyrics stage:
 * - Only the previous, active center, and next lyric lines are displayed
 * - Center line is sharp, large, centered, and glowing in the song's accent palette
 * - Top & bottom lines are softly blurred and dimmed so focus stays locked on the active line
 */
@Composable
fun SyncedLyricsDisplay(
    lines: List<LyricLine>,
    beatState: BeatReactiveLyricState,
    lyricNotes: List<LyricNoteDoc> = emptyList(),
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") listState: LazyListState = rememberLazyListState(),
    emptyContent: @Composable () -> Unit = {}
) {
    val activeIndex by remember(lines, beatState.positionMs) {
        derivedStateOf { SyncedLyricsTimeline.findActiveLineIndex(lines, beatState.positionMs) }
    }

    val animatedAccent by animateColorAsState(
        targetValue = beatState.primaryAccent,
        animationSpec = tween(durationMillis = 500),
        label = "lyric_active_accent"
    )

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
            val centerIndex = activeIndex.coerceIn(0, lines.lastIndex)

            AnimatedContent(
                targetState = centerIndex,
                transitionSpec = {
                    val forward = targetState >= initialState
                    val enterOffset: (Int) -> Int = { fullHeight ->
                        if (forward) fullHeight / 5 else -fullHeight / 5
                    }
                    val exitOffset: (Int) -> Int = { fullHeight ->
                        if (forward) -fullHeight / 5 else fullHeight / 5
                    }
                    (slideInVertically(
                        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                        initialOffsetY = enterOffset
                    ) + fadeIn(tween(durationMillis = 260))) togetherWith
                        (slideOutVertically(
                            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                            targetOffsetY = exitOffset
                        ) + fadeOut(tween(durationMillis = 200)))
                },
                label = "three_line_lyric_stage",
                modifier = Modifier.fillMaxSize()
            ) { focusIdx ->
                val prevLine = lines.getOrNull(focusIdx - 1)
                val centerLine = lines[focusIdx]
                val nextLine = lines.getOrNull(focusIdx + 1)

                val nextTimestamp = nextLine?.timestampMs ?: Long.MAX_VALUE
                val pinnedNotes = remember(lyricNotes, centerLine.timestampMs, nextTimestamp) {
                    lyricNotes.filter { note ->
                        note.timestampMs >= (centerLine.timestampMs - 500L) &&
                            note.timestampMs < nextTimestamp
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Top slot: Previous line (blurred & dimmed)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(bottom = 14.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        if (prevLine != null) {
                            BlurredPeripheralLyricLine(
                                index = focusIdx - 1,
                                line = prevLine,
                                onClick = { onSeekTo(prevLine.timestampMs) }
                            )
                        }
                    }

                    // Center slot: Active line (sharp, big, bold Fraunces editorial font)
                    FocusedCenterLyricLine(
                        index = focusIdx,
                        line = centerLine,
                        beatEnergy = beatState.beatEnergy,
                        isPlaying = beatState.isPlaying,
                        accentColor = animatedAccent,
                        pinnedNotes = pinnedNotes,
                        onClick = { onSeekTo(centerLine.timestampMs) }
                    )

                    // Bottom slot: Next line (blurred & dimmed)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        if (nextLine != null) {
                            BlurredPeripheralLyricLine(
                                index = focusIdx + 1,
                                line = nextLine,
                                onClick = { onSeekTo(nextLine.timestampMs) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusedCenterLyricLine(
    index: Int,
    line: LyricLine,
    beatEnergy: Float,
    isPlaying: Boolean,
    accentColor: Color,
    pinnedNotes: List<LyricNoteDoc>,
    onClick: () -> Unit
) {
    val isDarkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    val targetScale = if (isPlaying) {
        1.0f + (beatEnergy * 0.025f)
    } else {
        1.0f
    }
    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 360f),
        label = "center_lyric_scale"
    )

    val activeColor = if (isDarkSurface) {
        lerp(accentColor, Color(0xFFFFF1DC), 0.34f)
    } else {
        lerp(accentColor, MaterialTheme.colorScheme.onSurface, 0.16f)
    }

    val centerTextStyle = MaterialTheme.typography.headlineMedium.copy(
        fontFamily = FrauncesFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 27.sp,
        lineHeight = 35.sp,
        letterSpacing = (-0.3).sp,
        shadow = Shadow(
            color = accentColor.copy(alpha = if (isDarkSurface) 0.55f else 0.28f),
            offset = Offset.Zero,
            blurRadius = if (isPlaying) 22f + (beatEnergy * 8f) else 16f
        )
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
                transformOrigin = TransformOrigin.Center
            }
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .testTag("synced_lyric_line_$index"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = line.text,
            style = centerTextStyle,
            color = activeColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        if (pinnedNotes.isNotEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                pinnedNotes.take(2).forEach { note ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "✦ ${note.authorName}: ${note.noteText}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlurredPeripheralLyricLine(
    index: Int,
    line: LyricLine,
    onClick: () -> Unit
) {
    val isDarkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val peripheralColor = if (isDarkSurface) {
        Color.White.copy(alpha = 0.28f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)
    }

    val peripheralTextStyle = MaterialTheme.typography.titleMedium.copy(
        fontFamily = FrauncesFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.15).sp
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("synced_lyric_line_$index"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = line.text,
            style = peripheralTextStyle,
            color = peripheralColor,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .blur(radius = 5.5.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
        )
    }
}
