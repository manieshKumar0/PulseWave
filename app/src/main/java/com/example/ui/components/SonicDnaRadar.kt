package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.SonicDnaProfile
import com.example.ui.theme.CoralWave
import com.example.ui.theme.MintElectric
import com.example.ui.theme.UltravioletPulse
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@Immutable
private data class SonicAxisMeta(
    val key: String,
    val label: String,
    val description: String
)

private val sonicAxesMeta = listOf(
    SonicAxisMeta(
        key = "energy",
        label = "Energy",
        description = "Dynamic drive & intensity derived from high-momentum tracks you finish and replay."
    ),
    SonicAxisMeta(
        key = "valence",
        label = "Valence",
        description = "Emotional brightness balancing upbeat tracks against moody, introspective listens."
    ),
    SonicAxisMeta(
        key = "tempo",
        label = "Tempo",
        description = "Preferred BPM pace weighted by completion ratio, likes, and replay frequency."
    ),
    SonicAxisMeta(
        key = "acoustic",
        label = "Acoustic",
        description = "Affinity for organic instruments, folk, indie, jazz, and warm live textures."
    ),
    SonicAxisMeta(
        key = "electronic",
        label = "Electronic",
        description = "Preference for synth production, club rhythms, house, and bass-heavy sound design."
    ),
    SonicAxisMeta(
        key = "chill",
        label = "Chill",
        description = "Weight of atmospheric, dreamy, and downtempo tracks (<105 BPM) in your queue."
    )
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SonicDnaRadarChart(
    primaryProfile: SonicDnaProfile,
    friendProfile: SonicDnaProfile? = null,
    primaryLabel: String = "You",
    friendLabel: String = "Friend",
    modifier: Modifier = Modifier
) {
    var selectedAxisIndex by remember { mutableIntStateOf(0) }

    val rawPrimaryValues = remember(primaryProfile) {
        listOf(
            primaryProfile.energy,
            primaryProfile.valence,
            primaryProfile.tempo,
            primaryProfile.acoustic,
            primaryProfile.electronic,
            primaryProfile.chill
        )
    }
    val rawFriendValues = remember(friendProfile) {
        friendProfile?.let {
            listOf(it.energy, it.valence, it.tempo, it.acoustic, it.electronic, it.chill)
        }
    }

    val animatedPrimary = rawPrimaryValues.mapIndexed { index, target ->
        val animated by animateFloatAsState(
            targetValue = target.coerceIn(0, 100).toFloat(),
            animationSpec = spring(dampingRatio = 0.72f, stiffness = 260f),
            label = "sonic_primary_$index"
        )
        animated
    }

    val animatedFriend = rawFriendValues?.mapIndexed { index, target ->
        val animated by animateFloatAsState(
            targetValue = target.coerceIn(0, 100).toFloat(),
            animationSpec = spring(dampingRatio = 0.72f, stiffness = 260f),
            label = "sonic_friend_$index"
        )
        animated
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val accentSecondary = UltravioletPulse
    val friendColor = CoralWave
    val outlineColor = MaterialTheme.colorScheme.outline
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

    val labelStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    val scaleTickStyle = MaterialTheme.typography.labelSmall
    val textMeasurer = rememberTextMeasurer()

    val chartAccessibilitySummary = remember(primaryProfile, rawPrimaryValues) {
        sonicAxesMeta.mapIndexed { idx, meta ->
            "${meta.label} ${rawPrimaryValues[idx]} out of 100"
        }.joinToString(", ")
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("sonic_dna_radar"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .semantics { contentDescription = "Sonic DNA Radar Chart: $chartAccessibilitySummary" }
                .pointerInput(Unit) {
                    detectTapGestures { tapOffset ->
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val maxR = (minOf(size.width, size.height) / 2f) * 0.68f
                        var closestIdx = 0
                        var bestDistance = Float.MAX_VALUE
                        for (i in 0 until 6) {
                            val angle = (-PI / 2.0) + (i * 2.0 * PI / 6.0)
                            val vx = cx + (maxR * 0.85f * cos(angle)).toFloat()
                            val vy = cy + (maxR * 0.85f * sin(angle)).toFloat()
                            val dist = hypot(tapOffset.x - vx, tapOffset.y - vy)
                            if (dist < bestDistance) {
                                bestDistance = dist
                                closestIdx = i
                            }
                        }
                        if (bestDistance <= maxR * 0.72f) {
                            selectedAxisIndex = closestIdx
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val maxR = (size.minDimension / 2f) * 0.66f

                // Subtle ambient glow behind the radar core
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.16f),
                            accentSecondary.copy(alpha = 0.06f),
                            Color.Transparent
                        ),
                        center = Offset(cx, cy),
                        radius = maxR * 1.15f
                    ),
                    radius = maxR * 1.15f,
                    center = Offset(cx, cy)
                )

                // Concentric hexagonal grid rings (25, 50, 75, 100)
                val ringSteps = listOf(0.25f, 0.50f, 0.75f, 1.0f)
                ringSteps.forEachIndexed { ringIndex, fraction ->
                    val r = maxR * fraction
                    val ringPath = Path()
                    for (i in 0 until 6) {
                        val angle = (-PI / 2.0) + (i * 2.0 * PI / 6.0)
                        val x = cx + (r * cos(angle)).toFloat()
                        val y = cy + (r * sin(angle)).toFloat()
                        if (i == 0) ringPath.moveTo(x, y) else ringPath.lineTo(x, y)
                    }
                    ringPath.close()

                    if (ringIndex % 2 == 1) {
                        drawPath(
                            path = ringPath,
                            color = surfaceVariantColor.copy(alpha = 0.22f)
                        )
                    }
                    drawPath(
                        path = ringPath,
                        color = outlineColor.copy(alpha = if (ringIndex == ringSteps.lastIndex) 0.55f else 0.35f),
                        style = Stroke(width = if (ringIndex == ringSteps.lastIndex) 2.2f else 1.4f)
                    )

                    // Scale tick labels along top spoke (25, 50, 75)
                    if (ringIndex < ringSteps.lastIndex) {
                        val tickLabel = "${(fraction * 100).toInt()}"
                        val measuredTick = textMeasurer.measure(
                            text = tickLabel,
                            style = scaleTickStyle.copy(color = onSurfaceVariantColor.copy(alpha = 0.75f))
                        )
                        drawText(
                            textLayoutResult = measuredTick,
                            topLeft = Offset(
                                x = cx + 6f,
                                y = cy - r - measuredTick.size.height / 2f
                            )
                        )
                    }
                }

                // Radial spokes + perimeter axis labels
                for (i in 0 until 6) {
                    val angle = (-PI / 2.0) + (i * 2.0 * PI / 6.0)
                    val cosA = cos(angle).toFloat()
                    val sinA = sin(angle).toFloat()
                    val endX = cx + maxR * cosA
                    val endY = cy + maxR * sinA
                    val isSelected = i == selectedAxisIndex

                    drawLine(
                        color = if (isSelected) primaryColor.copy(alpha = 0.78f) else outlineColor.copy(alpha = 0.36f),
                        start = Offset(cx, cy),
                        end = Offset(endX, endY),
                        strokeWidth = if (isSelected) 2.8f else 1.3f,
                        cap = StrokeCap.Round
                    )

                    // Perimeter axis label + live value
                    val meta = sonicAxesMeta[i]
                    val valueInt = rawPrimaryValues[i]
                    val labelColor = if (isSelected) primaryColor else onSurfaceColor.copy(alpha = 0.88f)
                    val measuredLabel = textMeasurer.measure(
                        text = "${meta.label} $valueInt",
                        style = labelStyle.copy(color = labelColor)
                    )
                    val labelRadius = maxR + 18.dp.toPx()
                    val anchorX = cx + labelRadius * cosA
                    val anchorY = cy + labelRadius * sinA

                    val adjustedX = when {
                        cosA > 0.25f -> anchorX - measuredLabel.size.width * 0.25f
                        cosA < -0.25f -> anchorX - measuredLabel.size.width * 0.75f
                        else -> anchorX - measuredLabel.size.width / 2f
                    }.coerceIn(2f, (size.width - measuredLabel.size.width - 2f).coerceAtLeast(2f))

                    val adjustedY = when {
                        sinA > 0.35f -> anchorY - measuredLabel.size.height * 0.2f
                        sinA < -0.35f -> anchorY - measuredLabel.size.height * 0.8f
                        else -> anchorY - measuredLabel.size.height / 2f
                    }.coerceIn(2f, (size.height - measuredLabel.size.height - 2f).coerceAtLeast(2f))

                    drawText(
                        textLayoutResult = measuredLabel,
                        topLeft = Offset(adjustedX, adjustedY)
                    )
                }

                fun drawRadarPolygon(
                    values: List<Float>,
                    strokeBrush: Brush,
                    fillBrush: Brush,
                    dotColor: Color,
                    strokeWidthPx: Float,
                    highlightSelectedVertex: Boolean
                ) {
                    val poly = Path()
                    val vertices = ArrayList<Offset>(6)
                    values.forEachIndexed { i, v ->
                        val norm = (v.coerceIn(8f, 100f) / 100f) * maxR
                        val angle = (-PI / 2.0) + (i * 2.0 * PI / 6.0)
                        val pt = Offset(
                            x = cx + (norm * cos(angle)).toFloat(),
                            y = cy + (norm * sin(angle)).toFloat()
                        )
                        vertices.add(pt)
                        if (i == 0) poly.moveTo(pt.x, pt.y) else poly.lineTo(pt.x, pt.y)
                    }
                    poly.close()

                    drawPath(path = poly, brush = fillBrush)
                    drawPath(
                        path = poly,
                        brush = strokeBrush,
                        style = Stroke(width = strokeWidthPx, join = StrokeJoin.Round)
                    )

                    vertices.forEachIndexed { i, pt ->
                        val isSelected = highlightSelectedVertex && i == selectedAxisIndex
                        if (isSelected) {
                            drawCircle(
                                color = dotColor.copy(alpha = 0.28f),
                                radius = 12.dp.toPx(),
                                center = pt
                            )
                        }
                        drawCircle(
                            color = dotColor,
                            radius = if (isSelected) 5.5.dp.toPx() else 3.8.dp.toPx(),
                            center = pt
                        )
                        drawCircle(
                            color = onSurfaceColor.copy(alpha = 0.9f),
                            radius = if (isSelected) 2.2.dp.toPx() else 1.5.dp.toPx(),
                            center = pt
                        )
                    }
                }

                if (animatedFriend != null) {
                    drawRadarPolygon(
                        values = animatedFriend,
                        strokeBrush = Brush.linearGradient(listOf(friendColor, friendColor.copy(alpha = 0.82f))),
                        fillBrush = Brush.radialGradient(
                            colors = listOf(friendColor.copy(alpha = 0.26f), friendColor.copy(alpha = 0.12f)),
                            center = Offset(cx, cy),
                            radius = maxR
                        ),
                        dotColor = friendColor,
                        strokeWidthPx = 2.2.dp.toPx(),
                        highlightSelectedVertex = false
                    )
                }

                drawRadarPolygon(
                    values = animatedPrimary,
                    strokeBrush = Brush.linearGradient(listOf(primaryColor, accentSecondary)),
                    fillBrush = Brush.radialGradient(
                        colors = listOf(primaryColor.copy(alpha = 0.34f), accentSecondary.copy(alpha = 0.16f)),
                        center = Offset(cx, cy),
                        radius = maxR
                    ),
                    dotColor = primaryColor,
                    strokeWidthPx = 2.8.dp.toPx(),
                    highlightSelectedVertex = true
                )
            }
        }

        if (friendProfile != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendPill(label = primaryLabel, color = primaryColor)
                LegendPill(label = friendLabel, color = friendColor)
            }
        }

        // Interactive 6-axis selector pills (48dp touch targets)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sonicAxesMeta.forEachIndexed { index, axis ->
                val selected = selectedAxisIndex == index
                val score = rawPrimaryValues[index]
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (selected) primaryColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { selectedAxisIndex = index }
                        .testTag("sonic_axis_${axis.key}")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = axis.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Text(
                            text = "$score",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) primaryColor else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Selected axis breakdown card
        AnimatedContent(
            targetState = selectedAxisIndex,
            label = "sonic_axis_detail"
        ) { axisIdx ->
            val meta = sonicAxesMeta[axisIdx]
            val score = rawPrimaryValues[axisIdx]
            val friendScore = rawFriendValues?.getOrNull(axisIdx)

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.25f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sonic_axis_insight_card")
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Insights,
                                contentDescription = null,
                                tint = primaryColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${meta.label} Index",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (friendScore != null) {
                                val delta = score - friendScore
                                val deltaStr = if (delta >= 0) "+$delta vs $friendLabel" else "$delta vs $friendLabel"
                                Text(
                                    text = deltaStr,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (delta >= 0) MintElectric else friendColor
                                )
                            }
                            Text(
                                text = "$score / 100",
                                style = MaterialTheme.typography.labelLarge,
                                color = primaryColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    LinearProgressIndicator(
                        progress = { (score / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = primaryColor,
                        trackColor = MaterialTheme.colorScheme.surface
                    )

                    Text(
                        text = meta.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Playback history signal telemetry strip
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = MintElectric,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (primaryProfile.totalPlays > 0) {
                                "Based on ${primaryProfile.totalPlays} recent playback signal${if (primaryProfile.totalPlays == 1) "" else "s"}"
                            } else {
                                "Play tracks to calibrate your live Sonic DNA"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "${primaryProfile.avgCompletionPct}% avg play",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                val tags = (primaryProfile.topGenres.take(3) + primaryProfile.topMoods.take(2)).distinct()
                if (tags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        tags.forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
private fun LegendPill(
    label: String,
    color: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}
