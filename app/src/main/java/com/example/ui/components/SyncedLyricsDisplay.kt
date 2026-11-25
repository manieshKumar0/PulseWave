package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
 * Snapshot of playback telemetry passed into the synced lyrics ambient backdrop.
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

private const val LYRIC_LOOKAHEAD_MS = 160L

private fun findActiveLyricLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    val target = positionMs + LYRIC_LOOKAHEAD_MS
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

/**
 * Clean song-colored ambient glow behind the 3-line lyric stage.
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

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // Soft song-colored ambient glow centered directly behind the active middle lyric line
        val center = Offset(w * 0.5f, h * 0.5f)
        val centerRadius = size.maxDimension * 0.52f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    animatedPrimary.copy(alpha = 0.24f),
                    animatedSecondary.copy(alpha = 0.08f),
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
 * - Shows only 3 lines at a time: previous (top, blurred), active (center, big & sharp), next (bottom, blurred)
 * - Uses bold Fraunces display typography for the focused center lyric line
 * - Tap any visible line to seek directly to its timestamp
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
        derivedStateOf { findActiveLyricLineIndex(lines, beatState.positionMs) }
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

                    // Center slot: Active line (sharp, big, bold Fraunces font)
                    FocusedCenterLyricLine(
                        index = focusIdx,
                        line = centerLine,
                        isIntroWaiting = activeIndex < 0,
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
    isIntroWaiting: Boolean,
    accentColor: Color,
    pinnedNotes: List<LyricNoteDoc>,
    onClick: () -> Unit
) {
    val isDarkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    val activeTextColor = if (isDarkSurface) {
        lerp(accentColor, Color(0xFFFFF5E4), 0.46f)
    } else {
        lerp(accentColor, MaterialTheme.colorScheme.onSurface, 0.20f)
    }
    val introTextColor = if (isDarkSurface) {
        lerp(accentColor, Color.White, 0.25f).copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
    }

    val centerTextStyle = MaterialTheme.typography.headlineMedium.copy(
        fontFamily = FrauncesFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 27.sp,
        lineHeight = 35.sp,
        letterSpacing = (-0.3).sp
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
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
            color = if (isIntroWaiting) introTextColor else activeTextColor,
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
