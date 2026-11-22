package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.model.DefaultSoundscapes
import com.example.data.model.SoundscapePreset
import com.example.ui.theme.MintElectric
import com.example.ui.theme.UltravioletPulse
import kotlin.math.roundToInt

@Composable
fun EqualizerControlPanel(
    eqEnabled: Boolean,
    activePreset: SoundscapePreset,
    bandLevels: List<Int>,
    bassBoost: Int,
    reverbPreset: Int,
    onToggleEqEnabled: (Boolean) -> Unit,
    onSelectPreset: (SoundscapePreset) -> Unit,
    onUpdateBand: (Int, Int) -> Unit,
    onUpdateBassBoost: (Int) -> Unit,
    onUpdateReverbPreset: (Int) -> Unit,
    onResetEqualizer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val presets = DefaultSoundscapes.presets
    val bandLabels = DefaultSoundscapes.bandLabels
    val reverbLabels = DefaultSoundscapes.reverbLabels

    ObsidianGlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        accentTint = if (eqEnabled) UltravioletPulse else MaterialTheme.colorScheme.outline
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (eqEnabled) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Equalizer,
                            contentDescription = null,
                            tint = if (eqEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Column {
                        Text("5-Band Equalizer", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = if (eqEnabled) {
                                "${activePreset.name} · Media3 AudioFX"
                            } else {
                                "Bypassed · Flat hardware output"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = eqEnabled,
                    onCheckedChange = onToggleEqEnabled,
                    modifier = Modifier.testTag("eq_enabled_switch")
                )
            }

            // Live EQ Frequency Curve Preview
            EqualizerCurvePreview(
                bandLevels = bandLevels,
                enabled = eqEnabled,
                primaryColor = MaterialTheme.colorScheme.primary,
                secondaryColor = MintElectric,
                gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
            )

            // Preset Selector Chips (Flat Studio, Rock, Jazz, Pop, Classical, Bass Boost, Lo-Fi Reverb, etc.)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("EQ Presets", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = if (activePreset.id == "custom") "Custom Curve" else activePreset.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (activePreset.id == "custom") {
                        item(key = "custom") {
                            FilterChip(
                                selected = true,
                                onClick = {},
                                enabled = eqEnabled,
                                label = { Text("Custom") },
                                modifier = Modifier.testTag("settings_eq_preset_custom")
                            )
                        }
                    }
                    items(presets, key = { it.id }) { preset ->
                        FilterChip(
                            selected = activePreset.id == preset.id,
                            onClick = { onSelectPreset(preset) },
                            enabled = eqEnabled,
                            label = { Text(preset.name) },
                            modifier = Modifier.testTag("settings_eq_preset_${preset.id}")
                        )
                    }
                }
            }

            AnimatedVisibility(visible = eqEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    // 5-Band Parametric Sliders
                    Text("Frequency Bands (-15 dB to +15 dB)", style = MaterialTheme.typography.labelLarge)
                    bandLevels.forEachIndexed { idx, millibels ->
                        val label = bandLabels.getOrElse(idx) { "B${idx + 1}" }
                        val dbValue = millibels / 100f
                        val dbFormatted = if (dbValue > 0.05f) {
                            "+%.1f dB".format(dbValue)
                        } else {
                            "%.1f dB".format(dbValue)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (eqEnabled) 1f else 0.5f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.width(52.dp)
                            )
                            Slider(
                                value = millibels.toFloat(),
                                onValueChange = { onUpdateBand(idx, it.roundToInt()) },
                                valueRange = -1500f..1500f,
                                enabled = eqEnabled,
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics {
                                        contentDescription = "$label equalizer band, $dbFormatted"
                                    }
                                    .testTag("settings_eq_band_$idx")
                            )
                            Text(
                                text = dbFormatted,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = if (millibels != 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(56.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

                    // Sub-Bass Boost Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Bass Boost", style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = "${(bassBoost / 10)}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = bassBoost.toFloat(),
                        onValueChange = { onUpdateBassBoost(it.roundToInt()) },
                        valueRange = 0f..1000f,
                        enabled = eqEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Bass boost strength ${bassBoost / 10} percent"
                            }
                            .testTag("settings_eq_bass_slider")
                    )

                    // Spatial Reverb Presets
                    Text("Spatial Reverb", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(reverbLabels) { idx, label ->
                            FilterChip(
                                selected = reverbPreset == idx,
                                onClick = { onUpdateReverbPreset(idx) },
                                enabled = eqEnabled,
                                label = { Text(label) },
                                modifier = Modifier.testTag("settings_eq_reverb_$idx")
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onResetEqualizer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("settings_eq_reset_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Reset to Flat Studio")
                    }
                }
            }
        }
    }
}

@Composable
private fun EqualizerCurvePreview(
    bandLevels: List<Int>,
    enabled: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    gridColor: Color
) {
    val animatedBands = (0 until 5).map { idx ->
        val target = if (enabled) {
            (bandLevels.getOrNull(idx) ?: 0).coerceIn(-1500, 1500) / 1500f
        } else 0f
        animateFloatAsState(
            targetValue = target,
            animationSpec = tween(durationMillis = 220),
            label = "eq_band_$idx"
        ).value
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .height(74.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(74.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            val w = size.width
            val h = size.height
            val centerY = h / 2f

            // Reference 0dB dashed line
            drawLine(
                color = gridColor,
                start = Offset(0f, centerY),
                end = Offset(w, centerY),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
            )

            val points = animatedBands.mapIndexed { i, norm ->
                val x = if (animatedBands.size <= 1) 0f else (i.toFloat() / (animatedBands.size - 1)) * w
                val y = (centerY - norm * (h * 0.44f)).coerceIn(4f, h - 4f)
                Offset(x, y)
            }

            if (points.size >= 2) {
                val curvePath = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    for (i in 0 until points.lastIndex) {
                        val p0 = points[i]
                        val p1 = points[i + 1]
                        val midX = (p0.x + p1.x) / 2f
                        cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                    }
                }

                val fillPath = Path().apply {
                    addPath(curvePath)
                    lineTo(points.last().x, h)
                    lineTo(points.first().x, h)
                    close()
                }

                val activeAlpha = if (enabled) 1f else 0.35f
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.28f * activeAlpha),
                            secondaryColor.copy(alpha = 0.04f * activeAlpha)
                        )
                    )
                )

                drawPath(
                    path = curvePath,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = activeAlpha),
                            secondaryColor.copy(alpha = activeAlpha)
                        )
                    ),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                points.forEach { pt ->
                    drawCircle(
                        color = secondaryColor.copy(alpha = activeAlpha),
                        radius = 4.dp.toPx(),
                        center = pt
                    )
                }
            }
        }
    }
}
