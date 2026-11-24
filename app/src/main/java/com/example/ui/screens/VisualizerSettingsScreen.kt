package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.UserAppSettings
import com.example.media.PlaybackUiState
import com.example.ui.components.BeatReactiveWaveformCanvas
import com.example.ui.components.NeonSkylineBackgroundOverlay
import com.example.ui.components.ObsidianGlassCard
import com.example.ui.components.VisualizerRenderConfig
import com.example.ui.components.VisualizerStyle
import com.example.ui.components.dynamicAmbientGlow
import com.example.ui.theme.MintElectric
import com.example.ui.theme.PlayfairDisplayFontFamily
import com.example.ui.theme.UltravioletPulse
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun VisualizerSettingsScreen(
    settings: UserAppSettings,
    playbackState: PlaybackUiState,
    albumAccent: Color?,
    onSelectVisualizerStyle: (Int) -> Unit,
    onUpdateVisualizerLayout: (heightDp: Int, verticalPaddingDp: Int, horizontalPaddingDp: Int) -> Unit,
    onUpdateVisualizerTuning: (particleMotionScale: Float, intensityScale: Float) -> Unit,
    onResetVisualizerSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val selectedStyle = VisualizerStyle.fromIndex(settings.visualizerStyleIndex)
    val glowPrimary = albumAccent ?: MaterialTheme.colorScheme.primary
    val hasActiveAudio = playbackState.isPlaying && playbackState.currentTrack != null
    var simulatePreviewBeat by remember { mutableStateOf(true) }
    var simPositionMs by remember { mutableLongStateOf(0L) }
    var simEnergy by remember { mutableFloatStateOf(0.72f) }

    LaunchedEffect(hasActiveAudio, simulatePreviewBeat) {
        if (!hasActiveAudio && simulatePreviewBeat) {
            while (true) {
                simPositionMs += 32L
                val phase = (simPositionMs % 1000L) / 1000f
                simEnergy = (0.58f + 0.34f * sin(phase * 2f * PI.toFloat())).coerceIn(0.28f, 0.96f)
                delay(32L)
            }
        }
    }

    val previewPlaying = hasActiveAudio || simulatePreviewBeat
    val previewEnergy = if (hasActiveAudio) playbackState.beatPulseEnergy else simEnergy
    val previewPositionMs = if (hasActiveAudio) playbackState.positionMs else simPositionMs
    val previewBpm = playbackState.currentTrack?.bpm ?: 122

    val visualizerConfig = remember(
        selectedStyle,
        settings.visualizerHeightDp,
        settings.visualizerVerticalPaddingDp,
        settings.visualizerHorizontalPaddingDp
    ) {
        VisualizerRenderConfig(
            height = if (settings.visualizerHeightDp > 0) {
                settings.visualizerHeightDp.dp
            } else {
                selectedStyle.preferredHeightDp
            },
            horizontalPadding = settings.visualizerHorizontalPaddingDp.dp,
            verticalPadding = settings.visualizerVerticalPaddingDp.dp
        )
    }

    val isAtDefaults = settings.visualizerStyleIndex == 2 &&
        settings.visualizerHeightDp == 0 &&
        settings.visualizerVerticalPaddingDp == 4 &&
        settings.visualizerHorizontalPaddingDp == 0 &&
        kotlin.math.abs(settings.visualizerParticleMotionScale - 1.0f) < 0.02f &&
        kotlin.math.abs(settings.visualizerIntensityScale - 1.0f) < 0.02f

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        // Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("visualizer_settings_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to Settings"
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        text = "Visualizer Studio",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = PlayfairDisplayFontFamily,
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Default: #3 Neon Skyline",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            TextButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onResetVisualizerSettings()
                },
                enabled = !isAtDefaults,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("visualizer_settings_reset_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.RestartAlt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Reset")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Live Interactive Preview Stage
            item {
                ObsidianGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 24.dp,
                    accentTint = glowPrimary
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.GraphicEq,
                                    contentDescription = null,
                                    tint = glowPrimary
                                )
                                Column {
                                    Text(
                                        text = "Live Preview",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${selectedStyle.number}/3 · ${selectedStyle.title}" +
                                            if (selectedStyle == VisualizerStyle.NEON_SKYLINE) " (Default)" else "",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (!hasActiveAudio) {
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = if (simulatePreviewBeat) {
                                        glowPrimary.copy(alpha = 0.20f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (simulatePreviewBeat) glowPrimary.copy(alpha = 0.45f)
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .clickable { simulatePreviewBeat = !simulatePreviewBeat }
                                        .minimumInteractiveComponentSize()
                                        .testTag("visualizer_preview_toggle_button")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (simulatePreviewBeat) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                            contentDescription = if (simulatePreviewBeat) "Pause preview beat" else "Animate preview beat",
                                            modifier = Modifier.size(16.dp),
                                            tint = if (simulatePreviewBeat) glowPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = if (simulatePreviewBeat) "Demo Beat" else "Paused",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = MintElectric.copy(alpha = 0.16f)
                                ) {
                                    Text(
                                        text = "Live Audio",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MintElectric,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }

                        // Dark stage rendering selected visualizer faithfully
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color(0xFF0A0914),
                                            Color(0xFF141126),
                                            Color(0xFF090812)
                                        )
                                    )
                                )
                                .then(
                                    if (settings.dynamicAlbumGlow) {
                                        Modifier.dynamicAmbientGlow(glowPrimary, UltravioletPulse, intensity = 0.36f)
                                    } else {
                                        Modifier
                                    }
                                )
                                .testTag("visualizer_preview_stage")
                        ) {
                            if (selectedStyle == VisualizerStyle.NEON_SKYLINE) {
                                NeonSkylineBackgroundOverlay(
                                    energy = previewEnergy,
                                    isPlaying = previewPlaying,
                                    positionMs = previewPositionMs,
                                    bpm = previewBpm,
                                    primaryColor = glowPrimary,
                                    secondaryColor = UltravioletPulse,
                                    dynamicGlowEnabled = settings.dynamicAlbumGlow,
                                    useLiveAudio = hasActiveAudio,
                                    config = visualizerConfig,
                                    particleMotionScale = settings.visualizerParticleMotionScale,
                                    skylineIntensityScale = settings.visualizerIntensityScale,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                val cardCanvasHeight = (visualizerConfig.height ?: selectedStyle.preferredHeightDp)
                                    .coerceIn(48.dp, 180.dp)
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    BeatReactiveWaveformCanvas(
                                        energy = previewEnergy,
                                        isPlaying = previewPlaying,
                                        primaryColor = glowPrimary,
                                        secondaryColor = UltravioletPulse,
                                        useLiveAudio = hasActiveAudio,
                                        positionMs = previewPositionMs,
                                        bpm = previewBpm,
                                        dynamicGlowEnabled = settings.dynamicAlbumGlow,
                                        style = selectedStyle,
                                        config = visualizerConfig,
                                        particleMotionScale = settings.visualizerParticleMotionScale,
                                        skylineIntensityScale = settings.visualizerIntensityScale,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(cardCanvasHeight)
                                    )
                                }
                            }

                            // Subtle track badge inside top-left of stage
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = Color.Black.copy(alpha = 0.44f),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp)
                            ) {
                                Text(
                                    text = playbackState.currentTrack?.title ?: "PulseWave Studio Stage",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.86f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 2. Visualizer Style Selection (3 Styles)
            item {
                ObsidianGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 22.dp,
                    accentTint = UltravioletPulse
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Choose Visualizer Style",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                        Text(
                            text = "Select how audio energy animates in Now Playing. Built-in default is #3 Neon Skyline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        VisualizerStyle.entries.forEach { style ->
                            val isSelected = selectedStyle == style
                            val borderColor by animateColorAsState(
                                targetValue = if (isSelected) glowPrimary.copy(alpha = 0.72f)
                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.20f),
                                label = "style_option_border"
                            )
                            val containerColor by animateColorAsState(
                                targetValue = if (isSelected) glowPrimary.copy(alpha = 0.14f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.26f),
                                label = "style_option_bg"
                            )
                            val description = when (style) {
                                VisualizerStyle.CLASSIC_PULSE -> "Compact equalizer bars with crisp falling peak caps"
                                VisualizerStyle.CENTER_SYMMETRIC_BARS -> "Mirrored acoustic spectrum expanding from center"
                                VisualizerStyle.NEON_SKYLINE -> "Full-screen glowing horizon with rising particles"
                            }

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = containerColor,
                                border = BorderStroke(1.dp, borderColor),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSelectVisualizerStyle(style.ordinal)
                                    }
                                    .minimumInteractiveComponentSize()
                                    .testTag("visualizer_style_option_${style.number}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                        contentDescription = if (isSelected) "Selected" else "Not selected",
                                        tint = if (isSelected) glowPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "${style.number}. ${style.title}",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            )
                                            if (style == VisualizerStyle.NEON_SKYLINE) {
                                                Surface(
                                                    shape = RoundedCornerShape(999.dp),
                                                    color = MintElectric.copy(alpha = 0.18f)
                                                ) {
                                                    Text(
                                                        text = "Default",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MintElectric,
                                                        fontWeight = FontWeight.SemiBold,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Neon Skyline (#3) Particle Motion & Volume Controls
            item {
                ObsidianGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 22.dp,
                    accentTint = glowPrimary
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
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = null,
                                    tint = glowPrimary
                                )
                                Text(
                                    text = "Neon Skyline Controls",
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = if (selectedStyle == VisualizerStyle.NEON_SKYLINE) {
                                    glowPrimary.copy(alpha = 0.18f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ) {
                                Text(
                                    text = if (selectedStyle == VisualizerStyle.NEON_SKYLINE) "Active" else "#3 Only",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selectedStyle == VisualizerStyle.NEON_SKYLINE) {
                                        glowPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        // Upward Particle Motion Speed
                        val motionLabel = when {
                            settings.visualizerParticleMotionScale <= 0.02f -> "Off (No rising particles)"
                            kotlin.math.abs(settings.visualizerParticleMotionScale - 1.0f) < 0.05f -> "1.0x (Default)"
                            else -> "${"%.2f".format(settings.visualizerParticleMotionScale)}x"
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Upward Particle Motion",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                }
                                Text(
                                    text = motionLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = glowPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(
                                text = "Adjusts how fast glowing sparks drift upward from #3 Neon Skyline bars",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = settings.visualizerParticleMotionScale,
                                onValueChange = { next ->
                                    val snapped = ((next * 20f).roundToInt() / 20f).coerceIn(0f, 2.5f)
                                    onUpdateVisualizerTuning(snapped, settings.visualizerIntensityScale)
                                },
                                valueRange = 0f..2.5f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics {
                                        contentDescription = "Upward particle motion speed, $motionLabel"
                                    }
                                    .testTag("visualizer_particle_motion_slider")
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))

                        // Neon Skyline Volume / Intensity
                        val volumePct = (settings.visualizerIntensityScale * 100f).roundToInt()
                        val volumeLabel = if (kotlin.math.abs(settings.visualizerIntensityScale - 1.0f) < 0.03f) {
                            "100% (Default)"
                        } else {
                            "$volumePct%"
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Neon Skyline Volume & Glow",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                }
                                Text(
                                    text = volumeLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = glowPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(
                                text = "Controls visual bar reactivity, horizon punch, and neon intensity for #3",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = settings.visualizerIntensityScale,
                                onValueChange = { next ->
                                    val snapped = ((next * 20f).roundToInt() / 20f).coerceIn(0.25f, 1.75f)
                                    onUpdateVisualizerTuning(settings.visualizerParticleMotionScale, snapped)
                                },
                                valueRange = 0.25f..1.75f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics {
                                        contentDescription = "Neon Skyline volume and intensity, $volumeLabel"
                                    }
                                    .testTag("visualizer_skyline_volume_slider")
                            )
                        }
                    }
                }
            }

            // 4. Size & Padding Controls
            item {
                ObsidianGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 22.dp,
                    accentTint = MintElectric
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AspectRatio,
                                    contentDescription = null,
                                    tint = MintElectric
                                )
                                Text(
                                    text = "Size & Padding",
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }
                            TextButton(
                                onClick = { onUpdateVisualizerLayout(0, 4, 0) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("visualizer_layout_auto_button")
                            ) {
                                Text("Auto")
                            }
                        }

                        val maxHeightVal = if (selectedStyle == VisualizerStyle.NEON_SKYLINE) 220f else 150f
                        val activeHeightVal = if (settings.visualizerHeightDp > 0) {
                            settings.visualizerHeightDp.coerceIn(48, maxHeightVal.toInt())
                        } else {
                            selectedStyle.preferredHeightDp.value.roundToInt()
                        }
                        Text(
                            text = "Height: ${activeHeightVal}dp" + if (settings.visualizerHeightDp == 0) " (Auto)" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = activeHeightVal.toFloat(),
                            onValueChange = { next ->
                                onUpdateVisualizerLayout(
                                    next.roundToInt().coerceIn(48, maxHeightVal.toInt()),
                                    settings.visualizerVerticalPaddingDp,
                                    settings.visualizerHorizontalPaddingDp
                                )
                            },
                            valueRange = 48f..maxHeightVal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("visualizer_height_slider")
                        )

                        AnimatedVisibility(visible = selectedStyle != VisualizerStyle.NEON_SKYLINE) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Top / Bottom Padding: ${settings.visualizerVerticalPaddingDp}dp",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Slider(
                                    value = settings.visualizerVerticalPaddingDp.toFloat(),
                                    onValueChange = { next ->
                                        onUpdateVisualizerLayout(
                                            settings.visualizerHeightDp,
                                            next.roundToInt().coerceIn(0, 28),
                                            settings.visualizerHorizontalPaddingDp
                                        )
                                    },
                                    valueRange = 0f..28f,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("visualizer_vpad_slider")
                                )

                                Text(
                                    text = "Left / Right Padding: ${settings.visualizerHorizontalPaddingDp}dp",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Slider(
                                    value = settings.visualizerHorizontalPaddingDp.toFloat(),
                                    onValueChange = { next ->
                                        onUpdateVisualizerLayout(
                                            settings.visualizerHeightDp,
                                            settings.visualizerVerticalPaddingDp,
                                            next.roundToInt().coerceIn(0, 36)
                                        )
                                    },
                                    valueRange = 0f..36f,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("visualizer_hpad_slider")
                                )
                            }
                        }

                        if (selectedStyle == VisualizerStyle.NEON_SKYLINE) {
                            Text(
                                text = "Neon Skyline spans full screen width from the bottom edge automatically.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 5. Explicit Reset Card Button
            item {
                OutlinedButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onResetVisualizerSettings()
                    },
                    enabled = !isAtDefaults,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = glowPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("visualizer_reset_default_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Reset to Default (#3 Neon Skyline)")
                }
            }
        }
    }
}
