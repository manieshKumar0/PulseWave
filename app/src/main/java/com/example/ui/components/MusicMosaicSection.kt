package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.model.Track
import kotlin.math.roundToInt

private val MosaicPalmInk = Color(0xE61B1030)

private val MosaicTileRippleAlpha = RippleAlpha(
    draggedAlpha = 0.16f,
    focusedAlpha = 0.16f,
    hoveredAlpha = 0.10f,
    pressedAlpha = 0.26f
)

@Composable
fun MusicMosaicDarkSection(
    tracks: List<Track>,
    activeTrackId: String?,
    isPlaying: Boolean,
    activeMoodMix: String?,
    onPlayTrack: (Track) -> Unit,
    onPlayMoodMix: (MoodMixSpec) -> Unit,
    modifier: Modifier = Modifier,
    footerContent: (@Composable (scaleDp: Float, scalePx: Float, isDarkTheme: Boolean) -> Unit)? = null
) {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .testTag("music_mosaic_dark_section")
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("music_mosaic_canvas")
        ) {
            val density = LocalDensity.current
            val scaleDp = (maxWidth.value / MOSAIC_REF_PANEL_WIDTH).coerceAtLeast(0.4f)
            val panelWidthPx = with(density) { maxWidth.toPx() }
            val scalePx = (panelWidthPx / MOSAIC_REF_PANEL_WIDTH).coerceAtLeast(0.4f)
            val mosaicHeightDp = (MOSAIC_REF_HEIGHT * scaleDp).dp

            Layout(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(mosaicHeightDp),
                content = {
                    MosaicTileSpecs.forEachIndexed { slotIdx, spec ->
                        val track = if (tracks.isNotEmpty()) {
                            tracks[slotIdx % tracks.size]
                        } else {
                            null
                        }
                        MosaicSongTile(
                            spec = spec,
                            slotIndex = slotIdx,
                            track = track,
                            scalePx = scalePx,
                            onClick = { if (track != null) onPlayTrack(track) }
                        )
                    }
                }
            ) { measurables, _ ->
                val placeables = measurables.mapIndexed { index, measurable ->
                    val spec = MosaicTileSpecs[index]
                    val wPx = (spec.refWidth * scalePx).roundToInt().coerceAtLeast(1)
                    val hPx = (spec.refHeight * scalePx).roundToInt().coerceAtLeast(1)
                    measurable.measure(Constraints.fixed(wPx, hPx)) to spec
                }
                val totalW = panelWidthPx.roundToInt().coerceAtLeast(1)
                val totalH = (MOSAIC_REF_HEIGHT * scalePx).roundToInt().coerceAtLeast(1)
                layout(totalW, totalH) {
                    placeables.forEach { (placeable, spec) ->
                        placeable.placeRelative(
                            x = (spec.refX * scalePx).roundToInt(),
                            y = (spec.refY * scalePx).roundToInt()
                        )
                    }
                }
            }
        }

        if (footerContent != null) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val scaleDp = (maxWidth.value / MOSAIC_REF_PANEL_WIDTH).coerceAtLeast(0.4f)
                val scalePx = (with(density) { maxWidth.toPx() } / MOSAIC_REF_PANEL_WIDTH).coerceAtLeast(0.4f)
                footerContent(scaleDp, scalePx, isDarkTheme)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MosaicSongTile(
    spec: MosaicTileSpec,
    slotIndex: Int,
    track: Track?,
    scalePx: Float,
    onClick: () -> Unit
) {
    val shape = remember(spec.id) {
        OrganicSvgShape(spec.pathData, spec.refWidth, spec.refHeight)
    }
    val gradientStops = remember(spec.id) {
        if (spec.gradientColors.size >= 2) spec.gradientColors else listOf(Color(0xFF6E5198), Color(0xFF2B2045))
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.968f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 620f),
        label = "mosaic_tile_press_scale"
    )
    val rippleConfig = remember(spec.id) {
        RippleConfiguration(
            color = spec.glowColor,
            rippleAlpha = MosaicTileRippleAlpha
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = gradientStops,
                    start = Offset.Zero,
                    end = Offset(0.35f * spec.refWidth * scalePx, spec.refHeight * scalePx)
                )
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = track != null,
                role = Role.Button,
                onClickLabel = track?.let { "Play ${it.title} by ${it.artist}" },
                onClick = onClick
            )
            .semantics {
                contentDescription = if (track != null) {
                    "${track.title} by ${track.artist}. Tap to play"
                } else {
                    "Loading track recommendation"
                }
            }
            .testTag("mosaic_track_tile_$slotIndex")
    ) {
        val artUrl = track?.displayArtworkUri.orEmpty()
        if (artUrl.isNotBlank()) {
            AsyncImage(
                model = artUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        CompositionLocalProvider(LocalRippleConfiguration provides rippleConfig) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .indication(interactionSource, ripple(bounded = true))
            )
        }
    }
}

internal fun DrawScope.drawMosaicPalm(
    baseX: Float,
    baseY: Float,
    topX: Float,
    topY: Float,
    scale: Float,
    strokeScale: Float
) {
    val dx = topX - baseX
    val trunk = Path().apply {
        moveTo(baseX, baseY)
        quadraticTo(baseX + dx * 0.25f, (baseY + topY) * 0.52f, topX, topY)
    }
    drawPath(trunk, MosaicPalmInk, style = Stroke(width = 3.0f * scale * strokeScale, cap = StrokeCap.Round))

    val span = 34f * scale * strokeScale
    val fronds = Path().apply {
        moveTo(topX, topY); quadraticTo(topX - span * 0.55f, topY - span * 0.18f, topX - span * 1.25f, topY + span * 0.36f)
        moveTo(topX, topY); quadraticTo(topX - span * 0.30f, topY - span * 0.42f, topX - span * 0.90f, topY - span * 0.08f)
        moveTo(topX, topY); quadraticTo(topX + span * 0.04f, topY - span * 0.50f, topX + span * 0.12f, topY - span * 0.22f)
        moveTo(topX, topY); quadraticTo(topX + span * 0.35f, topY - span * 0.40f, topX + span * 0.96f, topY - span * 0.04f)
        moveTo(topX, topY); quadraticTo(topX + span * 0.58f, topY - span * 0.14f, topX + span * 1.28f, topY + span * 0.44f)
        moveTo(topX, topY); quadraticTo(topX - span * 0.18f, topY - span * 0.25f, topX - span * 0.68f, topY + span * 0.58f)
        moveTo(topX, topY); quadraticTo(topX + span * 0.22f, topY - span * 0.22f, topX + span * 0.74f, topY + span * 0.54f)
    }
    drawPath(fronds, MosaicPalmInk, style = Stroke(width = 2.3f * scale * strokeScale, cap = StrokeCap.Round))
}
