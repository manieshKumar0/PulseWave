package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.Track
import com.example.ui.theme.MintElectric
import com.example.ui.theme.PlusJakartaSansFontFamily
import kotlin.math.roundToInt

private val MosaicShadeMid = Color(0x0D140C24)
private val MosaicShadeBottom = Color(0xBD140C24)
private val MosaicPalmInk = Color(0xE61B1030)

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
    val trackSlotByTileId = remember {
        var nextIdx = 0
        MosaicTileSpecs
            .filter { it.kind == MosaicTileKind.TRACK }
            .associate { it.id to nextIdx++ }
    }

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
                        MosaicTileSpecs.forEach { spec ->
                            if (spec.kind == MosaicTileKind.TRACK) {
                                val slotIdx = trackSlotByTileId[spec.id] ?: 0
                                val track = if (tracks.isNotEmpty()) {
                                    tracks[slotIdx % tracks.size]
                                } else {
                                    null
                                }
                                MosaicSongTile(
                                    spec = spec,
                                    slotIndex = slotIdx,
                                    track = track,
                                    scaleDp = scaleDp,
                                    scalePx = scalePx,
                                    isActive = track != null && track.id == activeTrackId,
                                    isPlaying = track != null && track.id == activeTrackId && isPlaying,
                                    onClick = { if (track != null) onPlayTrack(track) }
                                )
                            } else {
                                val isMixActive = activeMoodMix.equals(spec.editorialTitle, ignoreCase = true) && isPlaying
                                val artworkKind = when (spec.kind) {
                                    MosaicTileKind.SUNSET_PALMS_HEADPHONES -> MoodArtworkKind.LATE_NIGHT
                                    MosaicTileKind.SUNSET_PALMS_SLEEP -> MoodArtworkKind.SUNDAY_CALM
                                    MosaicTileKind.GOLDEN_HOUR_MIX -> MoodArtworkKind.CHILL_POP
                                    else -> MoodArtworkKind.DEEP_FOCUS
                                }
                                MosaicEditorialTile(
                                    spec = spec,
                                    scaleDp = scaleDp,
                                    scalePx = scalePx,
                                    isActive = isMixActive,
                                    onClick = {
                                        onPlayMoodMix(
                                            MoodMixSpec(
                                                id = "mosaic_${spec.id}",
                                                label = spec.editorialTitle,
                                                name = spec.editorialTitle,
                                                searchQuery = spec.mixQuery,
                                                artwork = artworkKind
                                            )
                                        )
                                    }
                                )
                            }
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

@Composable
private fun MosaicSongTile(
    spec: MosaicTileSpec,
    slotIndex: Int,
    track: Track?,
    scaleDp: Float,
    scalePx: Float,
    isActive: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val shape = remember(spec.id) {
        OrganicSvgShape(spec.pathData, spec.refWidth, spec.refHeight)
    }
    val gradientStops = remember(spec.id) {
        if (spec.gradientColors.size >= 2) spec.gradientColors else listOf(Color(0xFF6E5198), Color(0xFF2B2045))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = gradientStops,
                    start = Offset.Zero,
                    end = Offset(0.35f * spec.refWidth * scalePx, spec.refHeight * scalePx)
                )
            )
            .clickable(
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
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(spec.glowColor.copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(size.width * 0.61f, size.height * 0.29f),
                    radius = maxOf(size.width, size.height) * 0.56f
                ),
                center = Offset(size.width * 0.61f, size.height * 0.29f),
                radius = maxOf(size.width, size.height) * 0.56f
            )
        }

        val artUrl = track?.displayArtworkUri.orEmpty()
        if (artUrl.isNotBlank()) {
            AsyncImage(
                model = artUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.42f to MosaicShadeMid,
                        0.74f to Color(0x6E140C24),
                        1.0f to MosaicShadeBottom
                    )
                )
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val u = size.width / spec.refWidth
            when (spec.accent) {
                MosaicTrackAccent.WAVEFORM_BOTTOM -> {
                    val heights = listOf(4f, 9f, 6f, 12f, 5f, 10f, 8f, 13f, 6f, 9f, 5f, 11f, 7f, 4f, 9f, 6f, 11f, 5f, 10f)
                    val baseY = size.height - 15f * u
                    heights.forEachIndexed { idx, h ->
                        val bx = (18f + idx * 3.2f) * u
                        if (bx < size.width - 14f * u) {
                            drawRoundRect(
                                color = if (isPlaying) MintElectric else Color.White.copy(alpha = 0.72f),
                                topLeft = Offset(bx, baseY - (h * u) * 0.5f),
                                size = Size(1.4f * u, h * u),
                                cornerRadius = CornerRadius(0.8f * u)
                            )
                        }
                    }
                }
                MosaicTrackAccent.PROGRESS_BOTTOM -> {
                    val py = size.height - 15f * u
                    val trackW = minOf(size.width * 0.50f, 117f * u)
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.35f),
                        topLeft = Offset(15f * u, py),
                        size = Size(trackW, 1.8f * u),
                        cornerRadius = CornerRadius(0.9f * u)
                    )
                    drawRoundRect(
                        color = if (isPlaying) MintElectric else Color.White.copy(alpha = 0.92f),
                        topLeft = Offset(15f * u, py),
                        size = Size(trackW * 0.36f, 1.8f * u),
                        cornerRadius = CornerRadius(0.9f * u)
                    )
                }
                MosaicTrackAccent.EQ_TOP_RIGHT -> {
                    drawEqualizerBadge(
                        topRight = Offset(size.width - 12f * scalePx, 12f * scalePx),
                        scale = scalePx,
                        color = if (isPlaying) MintElectric else Color.White.copy(alpha = 0.85f)
                    )
                }
                MosaicTrackAccent.DOTS_BOTTOM_RIGHT -> {
                    val dy = size.height - 20f * u
                    listOf(96f, 101f, 106f).forEach { dx ->
                        drawCircle(
                            color = Color.White.copy(alpha = 0.85f),
                            radius = 1.5f * u,
                            center = Offset(dx * u, dy)
                        )
                    }
                }
                MosaicTrackAccent.NONE -> {
                    if (isPlaying) {
                        drawEqualizerBadge(
                            topRight = Offset(size.width - 12f * scalePx, 12f * scalePx),
                            scale = scalePx,
                            color = MintElectric
                        )
                    }
                }
            }

            if (isActive) {
                val borderPath = buildScaledSvgPath(spec.pathData, spec.refWidth, spec.refHeight, size.width, size.height)
                drawPath(
                    path = borderPath,
                    color = MintElectric,
                    style = Stroke(width = 3.0f * scalePx, join = StrokeJoin.Round)
                )
            }
        }

        if (track != null) {
            val padStart = (spec.textInsetDp * scaleDp).dp
            val bottomRef = if (spec.accent == MosaicTrackAccent.WAVEFORM_BOTTOM || spec.accent == MosaicTrackAccent.PROGRESS_BOTTOM) {
                23f
            } else {
                11f
            }
            val padBottom = (bottomRef * scaleDp).dp
            val padEnd = (10f * scaleDp).dp
            val scaledTitleSp = (spec.titleSizeSp * scaleDp).coerceIn(10.5f, 17f).sp
            val scaledSubSp = (spec.subtitleSizeSp * scaleDp).coerceIn(8.5f, 12f).sp
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = padStart, end = padEnd, bottom = padBottom),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = track.title,
                    style = TextStyle(
                        fontFamily = PlusJakartaSansFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = scaledTitleSp,
                        lineHeight = scaledTitleSp * 1.12f,
                        color = Color.White,
                        shadow = Shadow(color = Color(0x99000000), offset = Offset(0f, 1.5f), blurRadius = 5f)
                    ),
                    maxLines = if (spec.refHeight >= 150f) 2 else 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artist,
                    style = TextStyle(
                        fontFamily = PlusJakartaSansFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = scaledSubSp,
                        lineHeight = scaledSubSp * 1.15f,
                        color = Color.White.copy(alpha = 0.85f),
                        shadow = Shadow(color = Color(0x80000000), offset = Offset(0f, 1f), blurRadius = 3f)
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MosaicEditorialTile(
    spec: MosaicTileSpec,
    scaleDp: Float,
    scalePx: Float,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val shape = remember(spec.id) {
        OrganicSvgShape(spec.pathData, spec.refWidth, spec.refHeight)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = spec.gradientColors,
                    start = Offset.Zero,
                    end = Offset(0.35f * spec.refWidth * scalePx, spec.refHeight * scalePx)
                )
            )
            .clickable(
                role = Role.Button,
                onClickLabel = "Play ${spec.editorialTitle}",
                onClick = onClick
            )
            .semantics { contentDescription = "${spec.editorialTitle}. Tap to play mix" }
            .testTag("mosaic_mix_${spec.id}")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val u = size.width / spec.refWidth
            when (spec.kind) {
                MosaicTileKind.VINYL_MIX -> {
                    val cx = size.width * 0.493f
                    val cy = size.height * 0.414f
                    val outerR = minOf(size.width, size.height) * 0.325f
                    drawCircle(color = Color(0xFF120F1C), radius = outerR, center = Offset(cx, cy))
                    listOf(0.85f, 0.70f, 0.55f).forEach { factor ->
                        drawCircle(
                            color = Color.White.copy(alpha = 0.08f),
                            radius = outerR * factor,
                            center = Offset(cx, cy),
                            style = Stroke(width = 1f * u)
                        )
                    }
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFFC58DF5), Color(0xFF6D3FC4)),
                            center = Offset(cx, cy),
                            radius = outerR * 0.38f
                        ),
                        radius = outerR * 0.38f,
                        center = Offset(cx, cy)
                    )
                    drawCircle(color = Color(0xFF120F1C), radius = maxOf(2f * u, outerR * 0.055f), center = Offset(cx, cy))
                }

                MosaicTileKind.SUNSET_PALMS_HEADPHONES -> {
                    drawCircle(color = Color(0x59FFD9A0), radius = 23f * u, center = Offset(size.width * 0.493f, size.height * 0.608f))
                    drawMosaicPalm(baseX = 30.8f * u, baseY = size.height - 2f * u, topX = 38.6f * u, topY = 39.9f * u, scale = u, strokeScale = 1.0f)
                    drawMosaicPalm(baseX = 112.0f * u, baseY = size.height - 2f * u, topX = 118.3f * u, topY = 52.5f * u, scale = u, strokeScale = 0.88f)
                }

                MosaicTileKind.SUNSET_PALMS_SLEEP -> {
                    drawCircle(color = Color(0x59FFD9A0), radius = 27f * u, center = Offset(size.width * 0.492f, size.height * 0.613f))
                    drawMosaicPalm(baseX = 27.1f * u, baseY = size.height - 2f * u, topX = 39.2f * u, topY = 61.9f * u, scale = u, strokeScale = 0.90f)
                    drawMosaicPalm(baseX = 98.4f * u, baseY = size.height - 2f * u, topX = 108.2f * u, topY = 81.5f * u, scale = u, strokeScale = 0.80f)
                }

                MosaicTileKind.GOLDEN_HOUR_MIX -> {
                    drawCircle(color = Color(0x66FFDCA8), radius = 44f * u, center = Offset(size.width * 0.496f, size.height * 0.613f))
                    drawMosaicPalm(baseX = 62f * u, baseY = size.height - 2f * u, topX = 76f * u, topY = 66f * u, scale = u, strokeScale = 1.2f)
                    drawMosaicPalm(baseX = 148f * u, baseY = size.height - 2f * u, topX = 160f * u, topY = 82f * u, scale = u, strokeScale = 1.0f)
                    drawMosaicPalm(baseX = 232f * u, baseY = size.height - 2f * u, topX = 242f * u, topY = 74f * u, scale = u, strokeScale = 1.1f)
                }

                MosaicTileKind.TRACK -> Unit
            }

            drawRect(
                brush = Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.45f to MosaicShadeMid,
                    1.0f to MosaicShadeBottom
                )
            )

            when (spec.kind) {
                MosaicTileKind.SUNSET_PALMS_HEADPHONES -> {
                    drawArc(
                        color = Color.White,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(20f * u, 22f * u),
                        size = Size(12f * u, 12f * u),
                        style = Stroke(width = 1.6f * u, cap = StrokeCap.Round)
                    )
                    drawRoundRect(Color.White, Offset(19f * u, 31f * u), Size(3f * u, 5.5f * u), CornerRadius(1.2f * u))
                    drawRoundRect(Color.White, Offset(30f * u, 31f * u), Size(3f * u, 5.5f * u), CornerRadius(1.2f * u))

                    val cx = size.width - 24f * u
                    val cy = size.height - 25f * u
                    drawCircle(color = Color(0xFFDED4F2), radius = 11f * u, center = Offset(cx, cy))
                    val arrow = Path().apply {
                        moveTo(cx - 5f * u, cy)
                        lineTo(cx + 4f * u, cy)
                        moveTo(cx + 0.5f * u, cy - 3.5f * u)
                        lineTo(cx + 4f * u, cy)
                        lineTo(cx + 0.5f * u, cy + 3.5f * u)
                    }
                    drawPath(
                        arrow,
                        color = Color(0xFF3B2D5C),
                        style = Stroke(width = 1.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
                MosaicTileKind.GOLDEN_HOUR_MIX -> {
                    drawEqualizerBadge(
                        topRight = Offset(size.width - (spec.refWidth - 36f) * u, 14f * u),
                        scale = u,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
                else -> Unit
            }

            if (isActive) {
                val borderPath = buildScaledSvgPath(spec.pathData, spec.refWidth, spec.refHeight, size.width, size.height)
                drawPath(
                    path = borderPath,
                    color = Color(0xFFB79CF0),
                    style = Stroke(width = 3.0f * scalePx, join = StrokeJoin.Round)
                )
            }
        }

        val padStart = (spec.textInsetDp * scaleDp).dp
        val padBottom = (11f * scaleDp).dp
        val padEnd = (if (spec.kind == MosaicTileKind.SUNSET_PALMS_HEADPHONES) 34f * scaleDp else 10f * scaleDp).dp
        val scaledTitleSp = (spec.titleSizeSp * scaleDp).coerceIn(10f, 16f).sp
        val scaledSubSp = (spec.subtitleSizeSp * scaleDp).coerceIn(8.5f, 11f).sp
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = padStart, end = padEnd, bottom = padBottom),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = spec.editorialTitle,
                style = TextStyle(
                    fontFamily = PlusJakartaSansFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = scaledTitleSp,
                    lineHeight = scaledTitleSp * 1.12f,
                    color = Color.White
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (spec.editorialSubtitle.isNotEmpty()) {
                Text(
                    text = spec.editorialSubtitle,
                    style = TextStyle(
                        fontFamily = PlusJakartaSansFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = scaledSubSp,
                        lineHeight = scaledSubSp * 1.15f,
                        color = Color.White.copy(alpha = 0.78f)
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

internal fun DrawScope.drawEqualizerBadge(
    topRight: Offset,
    scale: Float,
    color: Color
) {
    val heights = listOf(6f, 12f, 16f, 9f, 13f)
    val startX = topRight.x - 15f * scale
    val centerY = topRight.y + 8f * scale
    heights.forEachIndexed { idx, h ->
        val bx = startX + idx * 3.4f * scale
        val bh = h * scale
        drawRoundRect(
            color = color,
            topLeft = Offset(bx, centerY - bh * 0.5f),
            size = Size(1.6f * scale, bh),
            cornerRadius = CornerRadius(0.8f * scale)
        )
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
