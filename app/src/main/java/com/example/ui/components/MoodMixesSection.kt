package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.ui.theme.PlusJakartaSansFontFamily
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

enum class MoodArtworkKind {
    LATE_NIGHT,
    MORNING_VIBES,
    CHILL_POP,
    WORKOUT_BEATS,
    DEEP_FOCUS,
    SUNDAY_CALM
}

data class MoodMixSpec(
    val id: String,
    val label: String,
    val name: String,
    val searchQuery: String,
    val artwork: MoodArtworkKind
)

val DefaultMoodMixes = listOf(
    MoodMixSpec(
        id = "late_night",
        label = "Late\nNight",
        name = "Late Night",
        searchQuery = "late night chill electronic ambient r&b music",
        artwork = MoodArtworkKind.LATE_NIGHT
    ),
    MoodMixSpec(
        id = "morning_vibes",
        label = "Morning\nVibes",
        name = "Morning Vibes",
        searchQuery = "morning vibes feel good indie pop acoustic music",
        artwork = MoodArtworkKind.MORNING_VIBES
    ),
    MoodMixSpec(
        id = "chill_pop",
        label = "Chill\nPop",
        name = "Chill Pop",
        searchQuery = "chill pop aesthetic sunset hits music",
        artwork = MoodArtworkKind.CHILL_POP
    ),
    MoodMixSpec(
        id = "workout_beats",
        label = "Workout\nBeats",
        name = "Workout Beats",
        searchQuery = "workout beats high energy gym music",
        artwork = MoodArtworkKind.WORKOUT_BEATS
    ),
    MoodMixSpec(
        id = "deep_focus",
        label = "Deep\nFocus",
        name = "Deep Focus",
        searchQuery = "deep focus instrumental ambient study music",
        artwork = MoodArtworkKind.DEEP_FOCUS
    ),
    MoodMixSpec(
        id = "sunday_calm",
        label = "Sunday\nCalm",
        name = "Sunday Calm",
        searchQuery = "sunday calm soft acoustic soul chill music",
        artwork = MoodArtworkKind.SUNDAY_CALM
    )
)

private val AccentViolet = Color(0xFF6C47FF)
private val BannerInkPrimary = Color(0xFF14102B)
private val BannerInkSecondary = Color(0xFF473F63)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AskAiDjBanner(
    onSubmitPrompt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPromptSheet by remember { mutableStateOf(false) }
    var promptText by remember { mutableStateOf("") }
    val suggestions = remember {
        listOf(
            "Arijit Singh midnight drive",
            "90s R&B sunset groove",
            "Rainy café lo-fi beats",
            "High energy synthwave run",
            "Acoustic indie coffeehouse"
        )
    }

    val bannerShape = RoundedCornerShape(34.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                shape = bannerShape,
                ambientColor = Color(0x406C47FF),
                spotColor = Color(0x406C47FF)
            )
            .clip(bannerShape)
            .border(1.dp, Color.White.copy(alpha = 0.72f), bannerShape)
            .clickable(
                role = Role.Button,
                onClickLabel = "Open Ask AI DJ playlist creator"
            ) { showPromptSheet = true }
            .semantics {
                contentDescription = "Ask AI DJ. Create a playlist with an artist, mood or vibe"
            }
            .testTag("ask_ai_dj_banner")
    ) {
        // Pastel lavender gradient + layered organic wave ribbons matching visual reference
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height

            drawRect(
                brush = Brush.horizontalGradient(
                    0.0f to Color(0xFFF5F1FD),
                    0.46f to Color(0xFFEEE8FC),
                    0.78f to Color(0xFFDED2FF),
                    1.0f to Color(0xFFC7B1FF)
                )
            )

            // Back flowing lavender wave sweeping across the right half
            val wave1 = Path().apply {
                moveTo(w * 0.42f, h)
                cubicTo(w * 0.56f, h * 0.25f, w * 0.72f, h * 0.78f, w * 0.88f, 0f)
                lineTo(w, 0f)
                lineTo(w, h)
                close()
            }
            drawPath(
                path = wave1,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFCBB6FF).copy(alpha = 0.42f),
                        Color(0xFFAA8DFF).copy(alpha = 0.48f)
                    ),
                    start = Offset(w * 0.42f, h),
                    end = Offset(w, 0f)
                )
            )

            // Foreground translucent ribbon fold
            val wave2 = Path().apply {
                moveTo(w * 0.55f, h)
                cubicTo(w * 0.67f, h * 0.55f, w * 0.78f, h * 0.10f, w, h * 0.36f)
                lineTo(w, h)
                close()
            }
            drawPath(
                path = wave2,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFFFFFFF).copy(alpha = 0.34f),
                        Color(0xFF9F7DFF).copy(alpha = 0.40f)
                    ),
                    start = Offset(w * 0.55f, h),
                    end = Offset(w, h * 0.36f)
                )
            )

            // Subtle upper-right wave highlight sheen
            val wave3 = Path().apply {
                moveTo(w * 0.64f, 0f)
                cubicTo(w * 0.74f, h * 0.42f, w * 0.86f, h * 0.14f, w, h * 0.18f)
                lineTo(w, 0f)
                close()
            }
            drawPath(
                path = wave3,
                color = Color.White.copy(alpha = 0.28f)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SparklePairGlyph(
                tint = AccentViolet,
                modifier = Modifier.size(30.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Ask AI DJ",
                    fontFamily = PlusJakartaSansFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    lineHeight = 22.sp,
                    color = BannerInkPrimary,
                    maxLines = 1
                )
                Text(
                    text = "Create a playlist with an artist, mood or vibe",
                    fontFamily = PlusJakartaSansFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.5.sp,
                    lineHeight = 16.sp,
                    color = BannerInkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .shadow(
                        elevation = 6.dp,
                        shape = CircleShape,
                        ambientColor = Color(0x596C47FF),
                        spotColor = Color(0x596C47FF)
                    )
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFFA77BFF), Color(0xFF7D48FF))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    if (showPromptSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showPromptSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = AccentViolet
                    )
                    Text(
                        text = "Ask AI DJ",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Describe an artist, mood, or vibe and PulseWave will tune and play a custom YouTube Music mix right away.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    label = { Text("Artist, mood, or vibe") },
                    placeholder = { Text("e.g. Arijit Singh midnight drive") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val clean = promptText.trim()
                            if (clean.isNotEmpty()) {
                                showPromptSheet = false
                                onSubmitPrompt(clean)
                                promptText = ""
                            }
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_dj_prompt_input")
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    suggestions.forEach { idea ->
                        AssistChip(
                            onClick = {
                                showPromptSheet = false
                                onSubmitPrompt(idea)
                            },
                            label = { Text(idea) }
                        )
                    }
                }

                Button(
                    onClick = {
                        val clean = promptText.trim()
                        if (clean.isNotEmpty()) {
                            showPromptSheet = false
                            onSubmitPrompt(clean)
                            promptText = ""
                        }
                    },
                    enabled = promptText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentViolet,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("ai_dj_submit_button")
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Generate & Play Mix", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodMixesCarouselCard(
    mixes: List<MoodMixSpec> = DefaultMoodMixes,
    activeMoodName: String?,
    isPlaying: Boolean,
    onMixClick: (MoodMixSpec) -> Unit,
    modifier: Modifier = Modifier
) {
    val count = mixes.size
    if (count == 0) return

    val initialIndex = min(1, count - 1)
    val posAnim = remember { Animatable(initialIndex.toFloat()) }
    var isDragging by remember { mutableStateOf(false) }
    var showAllSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val outerShape = RoundedCornerShape(26.dp)
    val cardShape = RoundedCornerShape(24.dp)

    // Outer soft pastel lavender backdrop matching the HTML container + top tab accent
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(outerShape)
            .background(
                Brush.verticalGradient(
                    0.0f to Color(0xFFECE6FB),
                    0.45f to Color(0xFFF6F4FC),
                    1.0f to Color(0xFFF6F4FC)
                )
            )
            .padding(start = 8.dp, end = 8.dp, top = 0.dp, bottom = 8.dp)
            .testTag("mood_mixes_section")
    ) {
        // Subtle top pastel violet tab pill peeking behind the card top edge
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 26.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFFD9CFFA), Color(0xFFB9A6F6), Color(0xFFD9CFFA))
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .shadow(
                    elevation = 10.dp,
                    shape = cardShape,
                    ambientColor = Color(0x1A462896),
                    spotColor = Color(0x1A462896)
                )
                .clip(cardShape)
                .background(Color.White)
                .padding(top = 18.dp, bottom = 10.dp)
        ) {
            // Header row: "Mood Mixes" + "See All ->"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Mood Mixes",
                    fontFamily = PlusJakartaSansFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    letterSpacing = (-0.2).sp,
                    color = BannerInkPrimary
                )

                Row(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showAllSheet = true }
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .testTag("mood_mixes_see_all_button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = "See All",
                        fontFamily = PlusJakartaSansFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = AccentViolet
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = "See all mood mixes",
                        tint = AccentViolet,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // 3D Coverflow Stage
            val density = LocalDensity.current
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(232.dp)
                    .padding(top = 6.dp)
                    .pointerInput(count) {
                        val velocityTracker = VelocityTracker()
                        val dragSpacingPx = with(density) { 112.dp.toPx() }
                        detectHorizontalDragGestures(
                            onDragStart = {
                                velocityTracker.resetTracking()
                                isDragging = true
                                scope.launch { posAnim.stop() }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                var raw = posAnim.value - dragAmount / dragSpacingPx
                                val maxPos = (count - 1).toFloat()
                                if (raw < 0f) raw *= 0.35f
                                if (raw > maxPos) raw = maxPos + (raw - maxPos) * 0.35f
                                scope.launch { posAnim.snapTo(raw) }
                            },
                            onDragEnd = {
                                isDragging = false
                                val vxCardsPerSec = -velocityTracker.calculateVelocity().x / dragSpacingPx
                                val projected = posAnim.value + vxCardsPerSec * 0.2f
                                val dest = projected.roundToInt().coerceIn(0, count - 1)
                                scope.launch {
                                    posAnim.animateTo(
                                        targetValue = dest.toFloat(),
                                        animationSpec = spring(
                                            dampingRatio = 0.76f,
                                            stiffness = 175f
                                        )
                                    )
                                }
                            },
                            onDragCancel = {
                                isDragging = false
                                val dest = posAnim.value.roundToInt().coerceIn(0, count - 1)
                                scope.launch {
                                    posAnim.animateTo(
                                        targetValue = dest.toFloat(),
                                        animationSpec = spring(
                                            dampingRatio = 0.76f,
                                            stiffness = 175f
                                        )
                                    )
                                }
                            }
                        )
                    }
                    .testTag("mood_mixes_carousel"),
                contentAlignment = Alignment.Center
            ) {
                val widthFactor = (maxWidth / 358.dp).coerceIn(0.84f, 1.04f)
                val cardW = 172.dp * widthFactor
                val cardH = 196.dp * widthFactor
                val primarySpreadPx = with(density) { (108.dp * widthFactor).toPx() }
                val farSpreadPx = with(density) { (34.dp * widthFactor).toPx() }
                val camDist = 12f * density.density
                val currentPos = posAnim.value

                mixes.forEachIndexed { index, mix ->
                    val d = index - currentPos
                    val ad = abs(d)
                    val sign = if (d < 0f) -1f else 1f
                    val cl = min(ad, 1f)
                    val far = max(ad - 1f, 0f)

                    val tx = sign * (cl * primarySpreadPx + far * farSpreadPx)
                    val rotY = -sign * cl * 40f
                    val scale = (1f - 0.17f * cl - 0.07f * min(far, 1.5f)) *
                        (if (isDragging && ad < 0.5f) 0.975f else 1f)
                    val cardAlpha = (1f - max(ad - 2.3f, 0f) * 1.4f).coerceIn(0f, 1f)
                    val grayAmount = ((ad - 1.15f) / 0.8f).coerceIn(0f, 1f)
                    val zOrder = 100f - (ad * 10f)
                    val playBtnOpacity = (1f - ad * 1.8f).coerceIn(0f, 1f)
                    val shadowElev = (16f - 7f * cl).coerceAtLeast(2f)
                    val isMixPlaying = activeMoodName.equals(mix.name, ignoreCase = true) && isPlaying
                    val isCentered = index == currentPos.roundToInt() && ad < 0.45f

                    if (cardAlpha > 0.01f) {
                        MoodCoverCard(
                            mix = mix,
                            grayAmount = grayAmount,
                            playButtonOpacity = playBtnOpacity,
                            isMixPlaying = isMixPlaying,
                            onClick = {
                                if (isCentered) {
                                    onMixClick(mix)
                                } else {
                                    scope.launch {
                                        posAnim.animateTo(
                                            targetValue = index.toFloat(),
                                            animationSpec = spring(
                                                dampingRatio = 0.76f,
                                                stiffness = 175f
                                            )
                                        )
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(cardW, cardH)
                                .zIndex(zOrder)
                                .graphicsLayer {
                                    translationX = tx
                                    rotationY = rotY
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = cardAlpha
                                    cameraDistance = camDist
                                    shadowElevation = with(density) { shadowElev.dp.toPx() }
                                    shape = cardShape
                                    clip = false
                                }
                                .testTag("mood_mix_card_${mix.id}")
                        )
                    }
                }
            }

            // Pagination indicators matching HTML dot -> 20dp active purple pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val currentPos = posAnim.value
                mixes.forEachIndexed { index, mix ->
                    val wFraction = (1f - abs(index - currentPos)).coerceIn(0f, 1f)
                    val dotWidth = (6f + 14f * wFraction).dp
                    val dotColor = lerp(Color(0xFFCDC6E6), AccentViolet, wFraction)

                    Box(
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClickLabel = "Focus ${mix.name}"
                            ) {
                                scope.launch {
                                    posAnim.animateTo(
                                        targetValue = index.toFloat(),
                                        animationSpec = spring(
                                            dampingRatio = 0.76f,
                                            stiffness = 175f
                                        )
                                    )
                                }
                            }
                            .padding(horizontal = 3.dp, vertical = 12.dp)
                            .testTag("mood_mix_dot_${mix.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(dotWidth)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(dotColor)
                        )
                    }
                }
            }
        }
    }

    if (showAllSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showAllSheet = false },
            sheetState = sheetState
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
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Pick any mix to tune YouTube Music instantly.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    itemsIndexed(mixes, key = { _, m -> m.id }) { index, mix ->
                        val isMixPlaying = activeMoodName.equals(mix.name, ignoreCase = true) && isPlaying
                        MoodCoverCard(
                            mix = mix,
                            grayAmount = 0f,
                            playButtonOpacity = 1f,
                            isMixPlaying = isMixPlaying,
                            onClick = {
                                showAllSheet = false
                                scope.launch {
                                    posAnim.animateTo(
                                        targetValue = index.toFloat(),
                                        animationSpec = spring(dampingRatio = 0.76f, stiffness = 175f)
                                    )
                                }
                                onMixClick(mix)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(176.dp)
                                .testTag("mood_mix_grid_${mix.id}")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoodCoverCard(
    mix: MoodMixSpec,
    grayAmount: Float,
    playButtonOpacity: Float,
    isMixPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF2A1C5E))
            .border(3.dp, Color.White, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "Play ${mix.name} mix",
                onClick = onClick
            )
            .semantics {
                contentDescription = if (isMixPlaying) {
                    "${mix.name} mood mix playing. Tap to pause"
                } else {
                    "${mix.name} mood mix. Tap to play or focus"
                }
            }
    ) {
        // Custom vector scene matching the 6 SVGs from Mood_Mixes_Carousel.html
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawMoodScene(mix.artwork, grayAmount)
        }

        // Bottom dark scrim overlay: rgba(14,8,38,0.62) -> transparent at 56%
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.44f to Color.Transparent,
                        1.0f to Color(0x9E0E0826)
                    )
                )
        )

        // Bottom-left icon + 2-line label
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, bottom = 14.dp, end = 48.dp)
        ) {
            MoodStrokeGlyph(
                kind = mix.artwork,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = mix.label,
                style = TextStyle(
                    fontFamily = PlusJakartaSansFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 17.sp,
                    lineHeight = 19.5.sp,
                    letterSpacing = (-0.1).sp,
                    color = Color.White,
                    shadow = Shadow(
                        color = Color(0x590A051E),
                        offset = Offset(0f, 3f),
                        blurRadius = 10f
                    )
                ),
                maxLines = 2
            )
        }

        // Bottom-right circular Play / Pause indicator visible cleanly on centered card
        if (playButtonOpacity > 0.02f) {
            val scaleFactor = 0.7f + 0.3f * playButtonOpacity
            Surface(
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 11.dp, bottom = 13.dp)
                    .size(32.dp)
                    .graphicsLayer {
                        alpha = playButtonOpacity
                        scaleX = scaleFactor
                        scaleY = scaleFactor
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(14.dp)) {
                        val iconColor = Color(0xFF1B1340)
                        if (isMixPlaying) {
                            val barW = size.width * (5f / 24f)
                            val barH = size.height * (16f / 24f)
                            val yTop = size.height * (4f / 24f)
                            val r = CornerRadius(size.width * (1.5f / 24f))
                            drawRoundRect(
                                color = iconColor,
                                topLeft = Offset(size.width * (5f / 24f), yTop),
                                size = Size(barW, barH),
                                cornerRadius = r
                            )
                            drawRoundRect(
                                color = iconColor,
                                topLeft = Offset(size.width * (14f / 24f), yTop),
                                size = Size(barW, barH),
                                cornerRadius = r
                            )
                        } else {
                            val playPath = Path().apply {
                                moveTo(size.width * (7.5f / 24f), size.height * (4.5f / 24f))
                                lineTo(size.width * (20.5f / 24f), size.height * (12f / 24f))
                                lineTo(size.width * (7.5f / 24f), size.height * (19.5f / 24f))
                                close()
                            }
                            drawPath(playPath, color = iconColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SparklePairGlyph(
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Large 4-point star
        val cx = w * 0.43f
        val cy = h * 0.45f
        val r = w * 0.38f
        val bigStar = Path().apply {
            moveTo(cx, cy - r)
            quadraticTo(cx, cy, cx + r, cy)
            quadraticTo(cx, cy, cx, cy + r)
            quadraticTo(cx, cy, cx - r, cy)
            quadraticTo(cx, cy, cx, cy - r)
            close()
        }
        drawPath(bigStar, color = tint)

        // Small accent 4-point star at bottom-right
        val sx = w * 0.80f
        val sy = h * 0.77f
        val sr = w * 0.16f
        val smallStar = Path().apply {
            moveTo(sx, sy - sr)
            quadraticTo(sx, sy, sx + sr, sy)
            quadraticTo(sx, sy, sx, sy + sr)
            quadraticTo(sx, sy, sx - sr, sy)
            quadraticTo(sx, sy, sx, sy - sr)
            close()
        }
        drawPath(smallStar, color = tint)
    }
}

@Composable
private fun MoodStrokeGlyph(
    kind: MoodArtworkKind,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val u = size.width / 24f
        val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val white = Color.White

        when (kind) {
            MoodArtworkKind.LATE_NIGHT -> {
                val p = Path().apply {
                    moveTo(20f * u, 14.5f * u)
                    cubicTo(18.7f * u, 18.8f * u, 14.3f * u, 21.5f * u, 9.8f * u, 20.5f * u)
                    cubicTo(5.2f * u, 19.5f * u, 2.3f * u, 15.1f * u, 3.2f * u, 10.5f * u)
                    cubicTo(4.0f * u, 6.7f * u, 7.0f * u, 4.2f * u, 9.5f * u, 4.0f * u)
                    cubicTo(7.4f * u, 7.3f * u, 8.2f * u, 11.7f * u, 11.3f * u, 14.0f * u)
                    cubicTo(13.8f * u, 15.9f * u, 17.2f * u, 16.0f * u, 20f * u, 14.5f * u)
                    close()
                }
                drawPath(p, color = white, style = stroke)
            }
            MoodArtworkKind.MORNING_VIBES -> {
                drawCircle(white, radius = 4f * u, center = Offset(12f * u, 12f * u), style = stroke)
                val rays = listOf(
                    Offset(12f, 2f) to Offset(12f, 4f),
                    Offset(12f, 20f) to Offset(12f, 22f),
                    Offset(4.9f, 4.9f) to Offset(6.3f, 6.3f),
                    Offset(17.7f, 17.7f) to Offset(19.1f, 19.1f),
                    Offset(2f, 12f) to Offset(4f, 12f),
                    Offset(20f, 12f) to Offset(22f, 12f),
                    Offset(4.9f, 19.1f) to Offset(6.3f, 17.7f),
                    Offset(17.7f, 6.3f) to Offset(19.1f, 4.9f)
                )
                rays.forEach { (a, b) ->
                    drawLine(
                        color = white,
                        start = Offset(a.x * u, a.y * u),
                        end = Offset(b.x * u, b.y * u),
                        strokeWidth = 1.8f * u,
                        cap = StrokeCap.Round
                    )
                }
            }
            MoodArtworkKind.CHILL_POP -> {
                val glass = Path().apply {
                    moveTo(4f * u, 4f * u)
                    lineTo(20f * u, 4f * u)
                    lineTo(12f * u, 13f * u)
                    close()
                }
                drawPath(glass, color = white, style = stroke)
                drawLine(white, Offset(12f * u, 13f * u), Offset(12f * u, 20f * u), strokeWidth = 1.8f * u, cap = StrokeCap.Round)
                drawLine(white, Offset(8f * u, 20f * u), Offset(16f * u, 20f * u), strokeWidth = 1.8f * u, cap = StrokeCap.Round)
            }
            MoodArtworkKind.WORKOUT_BEATS -> {
                drawArc(
                    color = white,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(4f * u, 4f * u),
                    size = Size(16f * u, 16f * u),
                    style = stroke
                )
                drawLine(white, Offset(4f * u, 12f * u), Offset(4f * u, 16f * u), strokeWidth = 1.8f * u, cap = StrokeCap.Round)
                drawLine(white, Offset(20f * u, 12f * u), Offset(20f * u, 16f * u), strokeWidth = 1.8f * u, cap = StrokeCap.Round)
                drawRoundRect(
                    color = white,
                    topLeft = Offset(4f * u, 14f * u),
                    size = Size(3f * u, 6f * u),
                    cornerRadius = CornerRadius(1.5f * u),
                    style = stroke
                )
                drawRoundRect(
                    color = white,
                    topLeft = Offset(17f * u, 14f * u),
                    size = Size(3f * u, 6f * u),
                    cornerRadius = CornerRadius(1.5f * u),
                    style = stroke
                )
            }
            MoodArtworkKind.DEEP_FOCUS -> {
                drawCircle(white, radius = 9f * u, center = Offset(12f * u, 12f * u), style = stroke)
                drawCircle(white, radius = 4f * u, center = Offset(12f * u, 12f * u), style = stroke)
            }
            MoodArtworkKind.SUNDAY_CALM -> {
                val cup = Path().apply {
                    moveTo(4f * u, 9f * u)
                    lineTo(17f * u, 9f * u)
                    lineTo(17f * u, 14f * u)
                    cubicTo(17f * u, 16.8f * u, 14.8f * u, 19f * u, 12f * u, 19f * u)
                    lineTo(9f * u, 19f * u)
                    cubicTo(6.2f * u, 19f * u, 4f * u, 16.8f * u, 4f * u, 14f * u)
                    close()
                }
                drawPath(cup, color = white, style = stroke)
                drawArc(
                    color = white,
                    startAngle = -90f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(15f * u, 10f * u),
                    size = Size(6f * u, 4f * u),
                    style = stroke
                )
                drawLine(white, Offset(8f * u, 3f * u), Offset(8f * u, 6f * u), strokeWidth = 1.8f * u, cap = StrokeCap.Round)
                drawLine(white, Offset(12f * u, 3f * u), Offset(12f * u, 6f * u), strokeWidth = 1.8f * u, cap = StrokeCap.Round)
            }
        }
    }
}

private fun Color.withMoodTone(grayAmount: Float): Color {
    if (grayAmount <= 0.005f) return this
    val sat = 1f - grayAmount * 0.92f
    val bright = 1f - grayAmount * 0.10f
    val luma = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    val r = ((luma + (red - luma) * sat) * bright).coerceIn(0f, 1f)
    val g = ((luma + (green - luma) * sat) * bright).coerceIn(0f, 1f)
    val b = ((luma + (blue - luma) * sat) * bright).coerceIn(0f, 1f)
    return Color(r, g, b, alpha)
}

private fun DrawScope.drawMoodScene(kind: MoodArtworkKind, grayAmount: Float) {
    val sx = size.width / 172f
    val sy = size.height / 196f
    fun c(hex: Long): Color = Color(hex).withMoodTone(grayAmount)
    fun pt(x: Float, y: Float) = Offset(x * sx, y * sy)

    when (kind) {
        MoodArtworkKind.LATE_NIGHT -> {
            drawRect(
                brush = Brush.linearGradient(
                    0.0f to c(0xFF120E36),
                    0.55f to c(0xFF4A2F9E),
                    1.0f to c(0xFF8A62E0),
                    start = Offset.Zero,
                    end = Offset(size.width * 0.5f, size.height)
                )
            )
            val stars = listOf(
                Triple(30f, 26f, 1.2f to 0.9f),
                Triple(62f, 16f, 0.9f to 0.7f),
                Triple(104f, 30f, 1.3f to 0.9f),
                Triple(136f, 18f, 1.0f to 0.8f),
                Triple(150f, 48f, 0.9f to 0.6f),
                Triple(84f, 52f, 0.8f to 0.6f),
                Triple(20f, 64f, 0.9f to 0.5f)
            )
            stars.forEach { (x, y, rp) ->
                drawCircle(
                    color = c(0xFFFFFFFF).copy(alpha = rp.second),
                    radius = rp.first * sx,
                    center = pt(x, y)
                )
            }
            val bolt = Path().apply {
                moveTo(120f * sx, 0f)
                lineTo(104f * sx, 52f * sy)
                lineTo(118f * sx, 50f * sy)
                lineTo(96f * sx, 108f * sy)
            }
            drawPath(
                path = bolt,
                color = c(0xFFCDB8FF).copy(alpha = 0.5f),
                style = Stroke(width = 1.6f * sx)
            )
            val m1 = Path().apply {
                moveTo(0f, 138f * sy)
                lineTo(34f * sx, 108f * sy)
                lineTo(58f * sx, 126f * sy)
                lineTo(98f * sx, 84f * sy)
                lineTo(134f * sx, 122f * sy)
                lineTo(158f * sx, 106f * sy)
                lineTo(172f * sx, 118f * sy)
                lineTo(172f * sx, 196f * sy)
                lineTo(0f, 196f * sy)
                close()
            }
            drawPath(m1, color = c(0xFF2C1D68).copy(alpha = 0.9f))
            val m2 = Path().apply {
                moveTo(0f, 160f * sy)
                lineTo(40f * sx, 136f * sy)
                lineTo(74f * sx, 154f * sy)
                lineTo(116f * sx, 126f * sy)
                lineTo(172f * sx, 156f * sy)
                lineTo(172f * sx, 196f * sy)
                lineTo(0f, 196f * sy)
                close()
            }
            drawPath(m2, color = c(0xFF150F3A))
        }

        MoodArtworkKind.MORNING_VIBES -> {
            drawRect(
                brush = Brush.verticalGradient(
                    0.0f to c(0xFF2F2872),
                    0.38f to c(0xFFB24A82),
                    0.62f to c(0xFFFF8A5C),
                    0.78f to c(0xFFFFCF8A),
                    1.0f to c(0xFFFF9A6A)
                )
            )
            drawCircle(c(0xFFFFD08A).copy(alpha = 0.30f), radius = 56f * sx, center = pt(86f, 120f))
            drawCircle(c(0xFFFFF1C9), radius = 30f * sx, center = pt(86f, 120f))
            drawOval(
                color = c(0xFFFFB19A).copy(alpha = 0.7f),
                topLeft = pt(6f, 100f),
                size = Size(68f * sx, 8f * sy)
            )
            drawOval(
                color = c(0xFFFF9D86).copy(alpha = 0.6f),
                topLeft = pt(106f, 90.5f),
                size = Size(60f * sx, 7f * sy)
            )
            val m1 = Path().apply {
                moveTo(0f, 140f * sy)
                lineTo(28f * sx, 124f * sy)
                lineTo(52f * sx, 138f * sy)
                lineTo(84f * sx, 118f * sy)
                lineTo(116f * sx, 140f * sy)
                lineTo(148f * sx, 122f * sy)
                lineTo(172f * sx, 136f * sy)
                lineTo(172f * sx, 196f * sy)
                lineTo(0f, 196f * sy)
                close()
            }
            drawPath(m1, color = c(0xFF5A2B63).copy(alpha = 0.85f))
            val m2 = Path().apply {
                moveTo(0f, 166f * sy)
                lineTo(36f * sx, 146f * sy)
                lineTo(70f * sx, 164f * sy)
                lineTo(112f * sx, 140f * sy)
                lineTo(172f * sx, 168f * sy)
                lineTo(172f * sx, 196f * sy)
                lineTo(0f, 196f * sy)
                close()
            }
            drawPath(m2, color = c(0xFF2B1A45))
        }

        MoodArtworkKind.CHILL_POP -> {
            drawRect(
                brush = Brush.verticalGradient(
                    0.0f to c(0xFF2C2A80),
                    0.45f to c(0xFFA23D93),
                    0.75f to c(0xFFFF7B6B),
                    1.0f to c(0xFFFFB07A)
                )
            )
            drawCircle(c(0xFFFFE0B0).copy(alpha = 0.9f), radius = 22f * sx, center = pt(86f, 132f))
            drawRect(
                color = c(0xFF5D2A78).copy(alpha = 0.8f),
                topLeft = pt(0f, 146f),
                size = Size(size.width, 50f * sy)
            )
            val palmInk = c(0xFF170F2E)
            val leftTrunk = Path().apply {
                moveTo(24f * sx, 196f * sy)
                quadraticTo(32f * sx, 140f * sy, 44f * sx, 84f * sy)
            }
            drawPath(leftTrunk, palmInk, style = Stroke(4.5f * sx, cap = StrokeCap.Round))
            val leftFronds = Path().apply {
                moveTo(44f * sx, 84f * sy); quadraticTo(20f * sx, 72f * sy, 6f * sx, 94f * sy)
                moveTo(44f * sx, 84f * sy); quadraticTo(28f * sx, 58f * sy, 8f * sx, 58f * sy)
                moveTo(44f * sx, 84f * sy); quadraticTo(58f * sx, 56f * sy, 82f * sx, 62f * sy)
                moveTo(44f * sx, 84f * sy); quadraticTo(64f * sx, 76f * sy, 80f * sx, 96f * sy)
                moveTo(44f * sx, 84f * sy); quadraticTo(38f * sx, 64f * sy, 44f * sx, 46f * sy)
            }
            drawPath(leftFronds, palmInk, style = Stroke(3f * sx, cap = StrokeCap.Round))

            val rightTrunk = Path().apply {
                moveTo(148f * sx, 196f * sy)
                quadraticTo(142f * sx, 150f * sy, 132f * sx, 110f * sy)
            }
            drawPath(rightTrunk, palmInk, style = Stroke(4f * sx, cap = StrokeCap.Round))
            val rightFronds = Path().apply {
                moveTo(132f * sx, 110f * sy); quadraticTo(154f * sx, 100f * sy, 168f * sx, 118f * sy)
                moveTo(132f * sx, 110f * sy); quadraticTo(146f * sx, 90f * sy, 166f * sx, 90f * sy)
                moveTo(132f * sx, 110f * sy); quadraticTo(116f * sx, 90f * sy, 100f * sx, 94f * sy)
                moveTo(132f * sx, 110f * sy); quadraticTo(114f * sx, 112f * sy, 104f * sx, 126f * sy)
            }
            drawPath(rightFronds, palmInk, style = Stroke(2.8f * sx, cap = StrokeCap.Round))
        }

        MoodArtworkKind.WORKOUT_BEATS -> {
            drawRect(
                brush = Brush.linearGradient(
                    0.0f to c(0xFF190E44),
                    0.55f to c(0xFF5A2FD8),
                    1.0f to c(0xFFB04BFF),
                    start = Offset.Zero,
                    end = Offset(size.width * 0.3f, size.height)
                )
            )
            val beam1 = Path().apply {
                moveTo(20f * sx, 0f); lineTo(44f * sx, 0f)
                lineTo(70f * sx, 196f * sy); lineTo(6f * sx, 196f * sy); close()
            }
            drawPath(beam1, color = c(0xFFFFFFFF).copy(alpha = 0.08f))
            val beam2 = Path().apply {
                moveTo(96f * sx, 0f); lineTo(112f * sx, 0f)
                lineTo(150f * sx, 196f * sy); lineTo(104f * sx, 196f * sy); close()
            }
            drawPath(beam2, color = c(0xFFFFFFFF).copy(alpha = 0.10f))
            val beam3 = Path().apply {
                moveTo(140f * sx, 0f); lineTo(152f * sx, 0f)
                lineTo(172f * sx, 120f * sy); lineTo(172f * sx, 196f * sy)
                lineTo(150f * sx, 196f * sy); close()
            }
            drawPath(beam3, color = c(0xFFFFFFFF).copy(alpha = 0.07f))
            drawCircle(c(0xFFFF5AD9).copy(alpha = 0.22f), radius = 14f * sx, center = pt(38f, 66f))
            drawCircle(c(0xFF7FD0FF).copy(alpha = 0.25f), radius = 10f * sx, center = pt(130f, 48f))
            drawCircle(c(0xFFFF9AF0).copy(alpha = 0.30f), radius = 6f * sx, center = pt(110f, 96f))
            drawOval(
                color = c(0xFFFF5AD9).copy(alpha = 0.35f),
                topLeft = pt(-4f, 164f),
                size = Size(180f * sx, 52f * sy)
            )
        }

        MoodArtworkKind.DEEP_FOCUS -> {
            drawRect(
                brush = Brush.linearGradient(
                    0.0f to c(0xFF0A2240),
                    0.55f to c(0xFF1B6F8A),
                    1.0f to c(0xFF7FD6C8),
                    start = Offset.Zero,
                    end = Offset(size.width * 0.4f, size.height)
                )
            )
            val center = pt(86f, 104f)
            val rings = listOf(22f to 0.30f, 44f to 0.22f, 68f to 0.15f, 94f to 0.10f)
            rings.forEach { (r, a) ->
                drawCircle(
                    color = c(0xFFFFFFFF).copy(alpha = a),
                    radius = r * sx,
                    center = center,
                    style = Stroke(width = 1.2f * sx)
                )
            }
            drawCircle(c(0xFFDFFCF6).copy(alpha = 0.9f), radius = 9f * sx, center = center)
        }

        MoodArtworkKind.SUNDAY_CALM -> {
            drawRect(
                brush = Brush.verticalGradient(
                    0.0f to c(0xFF3A2456),
                    0.50f to c(0xFFD9709A),
                    0.75f to c(0xFFFFC58A),
                    1.0f to c(0xFFFFDCA8)
                )
            )
            drawCircle(c(0xFFFFF0CF).copy(alpha = 0.9f), radius = 20f * sx, center = pt(118f, 92f))
            listOf(128f to 0.35f, 144f to 0.28f, 162f to 0.20f).forEach { (y0, a) ->
                val wave = Path().apply {
                    moveTo(0f, y0 * sy)
                    quadraticTo(22f * sx, (y0 - 8f) * sy, 44f * sx, y0 * sy)
                    quadraticTo(66f * sx, (y0 + 8f) * sy, 88f * sx, y0 * sy)
                    quadraticTo(110f * sx, (y0 - 8f) * sy, 132f * sx, y0 * sy)
                    quadraticTo(154f * sx, (y0 + 8f) * sy, 176f * sx, y0 * sy)
                }
                drawPath(
                    path = wave,
                    color = c(0xFFFFFFFF).copy(alpha = a),
                    style = Stroke(width = 1.3f * sx, cap = StrokeCap.Round)
                )
            }
        }
    }
}
