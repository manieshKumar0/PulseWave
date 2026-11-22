package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MintElectric
import com.example.ui.theme.PlusJakartaSansFontFamily
import kotlin.math.roundToInt

private val MoodHeadingInkDark = Color(0xFFF1ECF8)
private val MoodHeadingInkLight = Color(0xFF14102B)
private val MoodLinkDark = Color(0xFFB79CF0)
private val MoodLinkLight = Color(0xFF6C47FF)
private val MoodDotInactiveDark = Color(0xFF5A5270)
private val MoodDotInactiveLight = Color(0xFFCDC6E6)
private val MoodTrackDarkStops = listOf(Color(0xFF34303D), Color(0xFF2B2833))
private val MoodTrackLightStops = listOf(Color(0xFFE3DEE7), Color(0xFFDCD6E2), Color(0xFFCBC6D0), Color(0xFFBCB8C1))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DarkMoodMixesStrip(
    scaleDp: Float,
    scalePx: Float,
    isDarkTheme: Boolean,
    mixes: List<MoodMixSpec> = DefaultMoodMixes,
    activeMoodName: String?,
    isPlaying: Boolean,
    onMixClick: (MoodMixSpec) -> Unit,
    modifier: Modifier = Modifier
) {
    if (mixes.isEmpty()) return

    var pageOffset by remember { mutableIntStateOf(0) }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    var showAllSheet by remember { mutableStateOf(false) }

    val pageCount = (mixes.size - 3).coerceAtLeast(1)
    val clampedOffset = pageOffset.coerceIn(0, pageCount - 1)
    val visibleMixes = List(MoodCardSlotSpecs.size) { idx ->
        mixes[(clampedOffset + idx) % mixes.size]
    }

    val headingColor = if (isDarkTheme) MoodHeadingInkDark else MoodHeadingInkLight
    val linkColor = if (isDarkTheme) MoodLinkDark else MoodLinkLight
    val dotInactiveColor = if (isDarkTheme) MoodDotInactiveDark else MoodDotInactiveLight
    val trackStops = if (isDarkTheme) MoodTrackDarkStops else MoodTrackLightStops

    val density = LocalDensity.current
    val stripHeightDp = (MOOD_STRIP_REF_HEIGHT * scaleDp).dp
    val headerPadStartDp = (15f * scaleDp).dp
    val headerPadEndDp = (18f * scaleDp).dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("mood_mixes_section")
    ) {
        // Header row: "Mood Mixes" + "See All ->"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = headerPadStartDp, end = headerPadEndDp, top = (6f * scaleDp).dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Mood Mixes",
                style = TextStyle(
                    fontFamily = PlusJakartaSansFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = (18f * scaleDp).coerceIn(15f, 20f).sp,
                    color = headingColor
                )
            )

            Row(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = "See all mood mixes") {
                        showAllSheet = true
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("mood_mixes_see_all_button"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "See All",
                    style = TextStyle(
                        fontFamily = PlusJakartaSansFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = (13f * scaleDp).coerceIn(12f, 15f).sp,
                        color = linkColor
                    )
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = "See all mood mixes",
                    tint = linkColor,
                    modifier = Modifier.size((15f * scaleDp).coerceIn(13f, 17f).dp)
                )
            }
        }

        // Organic tilted 4-card stage over the horizontal pill track backdrop
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height((stripHeightDp - 24.dp).coerceAtLeast(100.dp))
                .pointerInput(pageCount) {
                    val thresholdPx = with(density) { 42.dp.toPx() }
                    detectHorizontalDragGestures(
                        onDragStart = { dragAccum = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragAccum += dragAmount
                        },
                        onDragEnd = {
                            if (dragAccum < -thresholdPx && pageOffset < pageCount - 1) {
                                pageOffset++
                            } else if (dragAccum > thresholdPx && pageOffset > 0) {
                                pageOffset--
                            }
                            dragAccum = 0f
                        },
                        onDragCancel = { dragAccum = 0f }
                    )
                }
                .testTag("mood_mixes_carousel")
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val u = size.width / MOSAIC_REF_PANEL_WIDTH
                drawRoundRect(
                    brush = Brush.horizontalGradient(trackStops),
                    topLeft = Offset(15f * u, 28f * u),
                    size = Size(416f * u, 70f * u),
                    cornerRadius = CornerRadius(14f * u)
                )
            }

            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    MoodCardSlotSpecs.forEachIndexed { idx, slot ->
                        val mix = visibleMixes[idx]
                        val isActive = activeMoodName.equals(mix.name, ignoreCase = true) && isPlaying
                        DarkOrganicMoodCard(
                            slot = slot,
                            mix = mix,
                            scaleDp = scaleDp,
                            scalePx = scalePx,
                            isDarkTheme = isDarkTheme,
                            isActive = isActive,
                            onClick = { onMixClick(mix) }
                        )
                    }
                }
            ) { measurables, constraints ->
                val placeables = measurables.mapIndexed { idx, measurable ->
                    val slot = MoodCardSlotSpecs[idx]
                    val wPx = (slot.refWidth * scalePx).roundToInt().coerceAtLeast(1)
                    val hPx = (slot.refHeight * scalePx).roundToInt().coerceAtLeast(1)
                    measurable.measure(Constraints.fixed(wPx, hPx)) to slot
                }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeables.forEach { (placeable, slot) ->
                        placeable.placeRelative(
                            x = (slot.refX * scalePx).roundToInt(),
                            y = ((slot.refY - 20f) * scalePx).roundToInt().coerceAtLeast(0)
                        )
                    }
                }
            }
        }

        // Pagination pill + dots
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = (4f * scaleDp).dp, bottom = (10f * scaleDp).dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val totalDots = maxOf(pageCount, 3)
            for (dotIdx in 0 until totalDots) {
                val active = dotIdx == clampedOffset
                Box(
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClickLabel = "Show mood mix set ${dotIdx + 1}"
                        ) {
                            pageOffset = dotIdx.coerceIn(0, pageCount - 1)
                        }
                        .padding(horizontal = 4.dp, vertical = 6.dp)
                        .testTag("mood_mix_dot_$dotIdx"),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(if (active) 14.dp else 5.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (active) linkColor else dotInactiveColor)
                    )
                }
            }
        }
    }

    if (showAllSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val sheetBg = if (isDarkTheme) Color(0xFF1E1D22) else MaterialTheme.colorScheme.surface
        val sheetInk = if (isDarkTheme) MoodHeadingInkDark else MaterialTheme.colorScheme.onSurface
        ModalBottomSheet(
            onDismissRequest = { showAllSheet = false },
            sheetState = sheetState,
            containerColor = sheetBg,
            contentColor = sheetInk
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "All Mood Mixes",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = sheetInk
                )
                Text(
                    text = "Pick any mix to tune YouTube Music instantly.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = sheetInk.copy(alpha = 0.72f)
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(440.dp)
                ) {
                    itemsIndexed(mixes, key = { _, m -> m.id }) { _, mix ->
                        val isMixPlaying = activeMoodName.equals(mix.name, ignoreCase = true) && isPlaying
                        val cardShape = RoundedCornerShape(20.dp)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(168.dp)
                                .clip(cardShape)
                                .border(
                                    width = if (isMixPlaying) 2.5.dp else 1.5.dp,
                                    color = if (isMixPlaying) MintElectric else Color.White.copy(alpha = 0.85f),
                                    shape = cardShape
                                )
                                .clickable(role = Role.Button, onClickLabel = "Play ${mix.name}") {
                                    showAllSheet = false
                                    onMixClick(mix)
                                }
                                .testTag("mood_mix_grid_${mix.id}")
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawMoodScene(mix.artwork, grayAmount = 0f)
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            0.0f to Color.Transparent,
                                            0.45f to Color(0x0D140C24),
                                            1.0f to Color(0xBD140C24)
                                        )
                                    )
                            )
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                MoodStrokeGlyph(kind = mix.artwork, modifier = Modifier.size(20.dp))
                                Text(
                                    text = mix.name,
                                    style = TextStyle(
                                        fontFamily = PlusJakartaSansFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = Color.White
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DarkOrganicMoodCard(
    slot: MoodCardSlotSpec,
    mix: MoodMixSpec,
    scaleDp: Float,
    scalePx: Float,
    isDarkTheme: Boolean,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val shape = remember(slot.id) {
        OrganicSvgShape(slot.pathData, slot.refWidth, slot.refHeight)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "Play ${mix.name}",
                onClick = onClick
            )
            .semantics {
                contentDescription = if (isActive) {
                    "${mix.name} mood mix playing. Tap to pause"
                } else {
                    "${mix.name} mood mix. Tap to play"
                }
            }
            .testTag("mood_mix_card_${mix.id}")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val borderPath = buildScaledSvgPath(
                slot.pathData,
                slot.refWidth,
                slot.refHeight,
                size.width,
                size.height
            )
            drawPath(
                path = borderPath,
                color = if (isDarkTheme) Color(0x66000000) else Color(0x281B1238),
                style = Stroke(width = 7f * scalePx, join = StrokeJoin.Round)
            )
            drawPath(
                path = borderPath,
                color = if (isActive) MintElectric else Color.White,
                style = Stroke(width = 4.2f * scalePx, join = StrokeJoin.Round)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val u = size.width / slot.refWidth
                when (mix.artwork) {
                    MoodArtworkKind.LATE_NIGHT -> {
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color(0xFF3B3470), Color(0xFF6B4A9A), Color(0xFF241A42)),
                                start = Offset.Zero,
                                end = Offset(size.width * 0.35f, size.height)
                            )
                        )
                        drawCircle(Color(0x2ECDB8FF), radius = 23f * u, center = Offset(size.width * 0.68f, size.height * 0.27f))
                    }
                    MoodArtworkKind.MORNING_VIBES -> {
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color(0xFFF2A867), Color(0xFFE56F8C), Color(0xFF4A3A7C)),
                                start = Offset.Zero,
                                end = Offset(size.width * 0.35f, size.height)
                            )
                        )
                        drawCircle(Color(0x80FFE2A8), radius = 25.2f * u, center = Offset(size.width * 0.49f, size.height * 0.61f))
                        drawMosaicPalm(size.width * 0.81f, size.height - 2f * u, size.width * 0.855f, size.height * 0.49f, scale = u * 0.72f, strokeScale = 0.9f)
                    }
                    MoodArtworkKind.CHILL_POP -> {
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color(0xFF4C3B7E), Color(0xFFDD678C), Color(0xFFF39A55)),
                                start = Offset.Zero,
                                end = Offset(size.width * 0.35f, size.height)
                            )
                        )
                        drawCircle(Color(0x59FFD9A0), radius = 17f * u, center = Offset(size.width * 0.49f, size.height * 0.61f))
                        drawMosaicPalm(size.width * 0.29f, size.height - 2f * u, size.width * 0.375f, size.height * 0.295f, scale = u * 0.68f, strokeScale = 0.85f)
                        drawMosaicPalm(size.width * 0.76f, size.height - 2f * u, size.width * 0.825f, size.height * 0.44f, scale = u * 0.62f, strokeScale = 0.75f)
                    }
                    MoodArtworkKind.WORKOUT_BEATS -> {
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color(0xFF7B57C4), Color(0xFF2A1D4A)),
                                start = Offset.Zero,
                                end = Offset(size.width * 0.35f, size.height)
                            )
                        )
                    }
                    MoodArtworkKind.DEEP_FOCUS,
                    MoodArtworkKind.SUNDAY_CALM -> {
                        drawMoodScene(mix.artwork, grayAmount = 0f)
                    }
                }

                drawRect(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.45f to Color(0x0D140C24),
                        1.0f to Color(0xAD140C24)
                    )
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = (10f * scaleDp).dp,
                        end = (8f * scaleDp).dp,
                        bottom = (10f * scaleDp).dp
                    )
                    .offset(y = (-1f * scaleDp).dp)
                    .rotate(slot.contentRotationDeg),
                verticalArrangement = Arrangement.spacedBy((3f * scaleDp).dp)
            ) {
                MoodStrokeGlyph(
                    kind = mix.artwork,
                    modifier = Modifier.size((16f * scaleDp).coerceIn(13f, 18f).dp)
                )
                Text(
                    text = mix.label,
                    style = TextStyle(
                        fontFamily = PlusJakartaSansFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = (12.5f * scaleDp).coerceIn(10f, 14f).sp,
                        lineHeight = (13.8f * scaleDp).coerceIn(11f, 15.5f).sp,
                        color = Color.White,
                        shadow = Shadow(color = Color(0x88000000), offset = Offset(0f, 1.5f), blurRadius = 5f)
                    ),
                    maxLines = 2
                )
            }
        }
    }
}
