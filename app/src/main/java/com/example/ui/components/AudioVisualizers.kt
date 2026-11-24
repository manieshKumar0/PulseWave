package com.example.ui.components

import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.media.AudioSpectrumBus
import com.example.media.AudioVisualizerFrame
import com.example.ui.theme.UltravioletPulse
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

@Immutable
data class VisualizerRenderConfig(
    val height: Dp? = null,
    val contentPadding: PaddingValues = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
) {
    constructor(
        height: Dp? = null,
        horizontalPadding: Dp = 0.dp,
        verticalPadding: Dp = 4.dp
    ) : this(
        height = height,
        contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = verticalPadding)
    )
}

private data class CanvasViewport(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val width: Float,
    val height: Float,
    val centerX: Float,
    val centerY: Float
)

enum class VisualizerStyle(
    val number: Int,
    val title: String,
    val preferredHeightDp: Dp
) {
    CLASSIC_PULSE(1, "Classic Pulse", 52.dp),
    CENTER_SYMMETRIC_BARS(2, "Center Bars", 76.dp),
    NEON_SKYLINE(3, "Neon Skyline", 156.dp);

    companion object {
        fun fromIndex(index: Int): VisualizerStyle {
            val list = entries
            return list.getOrNull(index) ?: NEON_SKYLINE
        }
    }
}

@Composable
fun BeatReactiveWaveformCanvas(
    energy: Float,
    isPlaying: Boolean,
    primaryColor: Color,
    secondaryColor: Color = UltravioletPulse,
    modifier: Modifier = Modifier,
    spectrumBands: List<Float>? = null,
    useLiveAudio: Boolean = true,
    positionMs: Long = 0L,
    bpm: Int = 118,
    dynamicGlowEnabled: Boolean = true,
    style: VisualizerStyle = VisualizerStyle.NEON_SKYLINE,
    config: VisualizerRenderConfig = VisualizerRenderConfig(),
    particleMotionScale: Float = 1f,
    skylineIntensityScale: Float = 1f
) {
    if (style == VisualizerStyle.NEON_SKYLINE && spectrumBands == null) {
        NeonSkylineCanvas(
            energy = energy,
            isPlaying = isPlaying,
            positionMs = positionMs,
            bpm = bpm,
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            dynamicGlowEnabled = dynamicGlowEnabled,
            useLiveAudio = useLiveAudio,
            fullScreenBackground = false,
            config = config,
            particleMotionScale = particleMotionScale,
            skylineIntensityScale = skylineIntensityScale,
            modifier = modifier
        )
        return
    }

    val barCount = AudioVisualizerFrame.BAND_COUNT
    val smoothedBands = remember { FloatArray(barCount) }
    val peakCaps = remember { FloatArray(barCount) }
    var frameTick by remember { mutableLongStateOf(0L) }

    val currentEnergy by rememberUpdatedState(energy)
    val currentPositionMs by rememberUpdatedState(positionMs)
    val currentBpm by rememberUpdatedState(bpm)

    LaunchedEffect(isPlaying, useLiveAudio, spectrumBands, style) {
        var lastFrameNanos = 0L
        while (true) {
            var shouldContinue = false
            withFrameNanos { frameNanos ->
                val dt = if (lastFrameNanos == 0L) {
                    0.016f
                } else {
                    ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.004f, 0.05f)
                }
                lastFrameNanos = frameNanos

                val liveFrame = if (spectrumBands == null && useLiveAudio && isPlaying) {
                    AudioSpectrumBus.sampleNow(isPlaying = true)
                } else {
                    AudioVisualizerFrame.EMPTY
                }

                val attackAlpha = 1f - exp(-dt * 28f)
                val decayAlpha = 1f - exp(-dt * 9.5f)
                val peakDecay = dt * 0.48f

                var anyActive = false
                val beatPeriodMs = (60_000.0 / currentBpm.coerceIn(60, 200)).toFloat()
                val songSec = currentPositionMs / 1000f
                val songBeatPhase = ((currentPositionMs % beatPeriodMs.toLong().coerceAtLeast(1L)) / beatPeriodMs).coerceIn(0f, 1f)
                val songPulse = ((cos(songBeatPhase * 2f * PI.toFloat()) * 0.5f + 0.5f).pow(2)).coerceIn(0f, 1f)

                for (i in 0 until barCount) {
                    val target = when {
                        spectrumBands != null -> (spectrumBands.getOrElse(i) { 0f }).coerceIn(0f, 1f)
                        !isPlaying -> 0f
                        liveFrame.isLive -> {
                            val prev = liveFrame.bands[(i - 1).coerceAtLeast(0)]
                            val curr = liveFrame.bands[i]
                            val next = liveFrame.bands[(i + 1).coerceAtMost(barCount - 1)]
                            (prev * 0.14f + curr * 0.72f + next * 0.14f).coerceIn(0f, 1f)
                        }
                        else -> {
                            val shimmer = 0.5f + 0.5f * sin(i * 0.48f + songSec * 4.2f)
                            val weight = if (i < 7) (0.55f + 0.45f * songPulse) else (0.35f + 0.65f * shimmer)
                            (currentEnergy.coerceIn(0f, 1f) * 0.36f * weight).coerceIn(0f, 0.48f)
                        }
                    }

                    val current = smoothedBands[i]
                    val alpha = if (target >= current) attackAlpha else decayAlpha
                    val updated = current + (target - current) * alpha
                    smoothedBands[i] = if (updated < 0.004f) 0f else updated

                    val nextPeak = maxOf(updated, peakCaps[i] - peakDecay)
                    peakCaps[i] = if (nextPeak < 0.004f) 0f else nextPeak

                    if (smoothedBands[i] > 0.004f || peakCaps[i] > 0.004f) {
                        anyActive = true
                    }
                }

                frameTick++
                shouldContinue = isPlaying || anyActive
            }
            if (!shouldContinue) break
        }
    }

    Canvas(modifier = modifier) {
        val tick = frameTick
        if (tick < 0L || size.width <= 0f || size.height <= 0f) return@Canvas

        val padStart = config.contentPadding.calculateStartPadding(layoutDirection).toPx()
        val padEnd = config.contentPadding.calculateEndPadding(layoutDirection).toPx()
        val padTop = config.contentPadding.calculateTopPadding().toPx()
        val padBottom = config.contentPadding.calculateBottomPadding().toPx()

        val left = padStart.coerceIn(0f, size.width * 0.45f)
        val right = (size.width - padEnd).coerceIn(left + 8f, size.width)
        val top = padTop.coerceIn(0f, size.height * 0.45f)
        val bottom = (size.height - padBottom).coerceIn(top + 8f, size.height)
        val vp = CanvasViewport(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            width = (right - left).coerceAtLeast(8f),
            height = (bottom - top).coerceAtLeast(8f),
            centerX = (left + right) / 2f,
            centerY = (top + bottom) / 2f
        )

        clipRect(left = vp.left, top = vp.top, right = vp.right, bottom = vp.bottom) {
            when (style) {
                VisualizerStyle.CLASSIC_PULSE -> drawClassicPulse(
                    vp = vp,
                    barCount = barCount,
                    smoothedBands = smoothedBands,
                    peakCaps = peakCaps,
                    primaryColor = primaryColor,
                    secondaryColor = secondaryColor
                )

                VisualizerStyle.CENTER_SYMMETRIC_BARS -> drawCenterSymmetricBars(
                    vp = vp,
                    barCount = barCount,
                    smoothedBands = smoothedBands,
                    peakCaps = peakCaps,
                    primaryColor = primaryColor,
                    secondaryColor = secondaryColor
                )

                VisualizerStyle.NEON_SKYLINE -> drawCenterSymmetricBars(
                    vp = vp,
                    barCount = barCount,
                    smoothedBands = smoothedBands,
                    peakCaps = peakCaps,
                    primaryColor = if (dynamicGlowEnabled) primaryColor else Color(0xFFFF73F5),
                    secondaryColor = if (dynamicGlowEnabled) secondaryColor else Color(0xFF5087FF)
                )
            }
        }
    }
}

/**
 * #1 Classic Pulse — exact one-step-back left-to-right pill visualizer + floating peak dots.
 */
private fun DrawScope.drawClassicPulse(
    vp: CanvasViewport,
    barCount: Int,
    smoothedBands: FloatArray,
    peakCaps: FloatArray,
    primaryColor: Color,
    secondaryColor: Color
) {
    val spacing = (vp.width / (barCount * 2.15f)).coerceIn(2.5f, 8f)
    val totalSpacing = spacing * (barCount - 1)
    val barWidth = ((vp.width - totalSpacing) / barCount).coerceAtLeast(2f)
    val maxBarHeight = vp.height * 0.90f
    val minBarHeight = (barWidth * 1.15f).coerceIn(4f, vp.height * 0.16f)

    for (i in 0 until barCount) {
        val t = i.toFloat() / (barCount - 1).coerceAtLeast(1)
        val baseColor = lerp(primaryColor, secondaryColor, t)
        val level = smoothedBands[i].coerceIn(0f, 1f)
        val peak = peakCaps[i].coerceIn(0f, 1f)

        val shapedLevel = level.let { it * 0.85f + it * it * 0.15f }
        val barHeight = (minBarHeight + (maxBarHeight - minBarHeight) * shapedLevel)
            .coerceAtMost(vp.height)
        val barTop = vp.centerY - barHeight / 2f
        val x = vp.left + i * (barWidth + spacing)

        if (level > 0.28f) {
            val glowPadX = barWidth * 0.28f
            val glowPadY = barHeight * 0.08f
            drawRoundRect(
                color = baseColor.copy(alpha = (level - 0.20f).coerceIn(0f, 0.30f)),
                topLeft = Offset((x - glowPadX).coerceAtLeast(vp.left), (barTop - glowPadY).coerceAtLeast(vp.top)),
                size = Size(barWidth + glowPadX * 2f, (barHeight + glowPadY * 2f).coerceAtMost(vp.height)),
                cornerRadius = CornerRadius(barWidth, barWidth)
            )
        }

        val barColor = lerp(baseColor, Color.White, (level * 0.22f).coerceIn(0f, 0.22f))
            .copy(alpha = (0.30f + 0.68f * level).coerceIn(0.30f, 0.98f))

        drawRoundRect(
            color = barColor,
            topLeft = Offset(x, barTop),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
        )

        if (peak > level + 0.07f && peak > 0.18f) {
            val peakSpan = minBarHeight + (maxBarHeight - minBarHeight) * peak
            val peakDotSize = (barWidth * 0.62f).coerceIn(2f, 5f)
            val peakAlpha = ((peak - level) * 3.2f).coerceIn(0.18f, 0.72f)
            val topDotY = (vp.centerY - peakSpan / 2f - peakDotSize * 0.6f).coerceAtLeast(vp.top)
            val bottomDotY = (vp.centerY + peakSpan / 2f - peakDotSize * 0.4f).coerceAtMost(vp.bottom - peakDotSize)
            val dotX = x + (barWidth - peakDotSize) / 2f
            drawRoundRect(
                color = barColor.copy(alpha = peakAlpha),
                topLeft = Offset(dotX, topDotY),
                size = Size(peakDotSize, peakDotSize),
                cornerRadius = CornerRadius(peakDotSize / 2f, peakDotSize / 2f)
            )
            drawRoundRect(
                color = barColor.copy(alpha = peakAlpha),
                topLeft = Offset(dotX, bottomDotY),
                size = Size(peakDotSize, peakDotSize),
                cornerRadius = CornerRadius(peakDotSize / 2f, peakDotSize / 2f)
            )
        }
    }
}

/**
 * #2 Center Bars — symmetric center-out equalizer radiating from (centerX, centerY).
 */
private fun DrawScope.drawCenterSymmetricBars(
    vp: CanvasViewport,
    barCount: Int,
    smoothedBands: FloatArray,
    peakCaps: FloatArray,
    primaryColor: Color,
    secondaryColor: Color
) {
    val halfCount = (barCount / 2).coerceAtLeast(1)
    val spacing = (vp.width / (barCount * 2.2f)).coerceIn(2.5f, 8f)
    val totalSpacing = spacing * (barCount - 1)
    val barWidth = ((vp.width - totalSpacing) / barCount).coerceAtLeast(2f)
    val totalBarsWidth = barWidth * barCount + totalSpacing
    val startX = vp.centerX - totalBarsWidth / 2f
    val maxBarHeight = vp.height * 0.94f
    val minBarHeight = (barWidth * 1.15f).coerceIn(4f, vp.height * 0.14f)
    val centerIndex = (barCount - 1) / 2f

    for (i in 0 until barCount) {
        val step = if (i < halfCount) (halfCount - 1) - i else i - halfCount
        val radialNorm = (step.toFloat() / (halfCount - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
        val bandA = (step * 2).coerceIn(0, barCount - 1)
        val bandB = (step * 2 + 1).coerceIn(0, barCount - 1)
        val raw = if (i < halfCount) {
            smoothedBands[bandA] * 0.72f + smoothedBands[bandB] * 0.28f
        } else {
            smoothedBands[bandB] * 0.72f + smoothedBands[bandA] * 0.28f
        }
        val wingLift = 1f + 0.42f * radialNorm.pow(0.75f)
        val level = if (raw <= 0.004f) 0f else (raw.pow(0.74f) * wingLift).coerceIn(0f, 1f)
        val rawPeak = maxOf(peakCaps[bandA], peakCaps[bandB])
        val peak = if (rawPeak <= 0.004f) 0f else (rawPeak.pow(0.74f) * wingLift).coerceIn(0f, 1f)

        val centerNorm = (abs(i - centerIndex) / centerIndex.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val baseColor = lerp(primaryColor, secondaryColor, centerNorm)
        val barHeight = (minBarHeight + (maxBarHeight - minBarHeight) * level).coerceAtMost(vp.height)
        val barTop = vp.centerY - barHeight / 2f
        val x = startX + i * (barWidth + spacing)

        if (level > 0.22f) {
            val glowPadX = barWidth * 0.34f
            val glowPadY = barHeight * 0.09f
            drawRoundRect(
                color = baseColor.copy(alpha = (level - 0.16f).coerceIn(0f, 0.32f)),
                topLeft = Offset(x - glowPadX, (barTop - glowPadY).coerceAtLeast(vp.top)),
                size = Size(barWidth + glowPadX * 2f, (barHeight + glowPadY * 2f).coerceAtMost(vp.height)),
                cornerRadius = CornerRadius(barWidth, barWidth)
            )
        }

        val barColor = lerp(baseColor, Color.White, (level * 0.25f).coerceIn(0f, 0.25f))
            .copy(alpha = (0.34f + 0.65f * level).coerceIn(0.34f, 0.99f))
        drawRoundRect(
            color = barColor,
            topLeft = Offset(x, barTop),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
        )

        if (peak > level + 0.06f && peak > 0.16f) {
            val peakSpan = minBarHeight + (maxBarHeight - minBarHeight) * peak
            val dotSize = (barWidth * 0.58f).coerceIn(2f, 5f)
            val dotX = x + (barWidth - dotSize) / 2f
            val dotAlpha = ((peak - level) * 3f).coerceIn(0.20f, 0.75f)
            drawRoundRect(
                color = barColor.copy(alpha = dotAlpha),
                topLeft = Offset(dotX, (vp.centerY - peakSpan / 2f - dotSize * 0.5f).coerceAtLeast(vp.top)),
                size = Size(dotSize, dotSize),
                cornerRadius = CornerRadius(dotSize / 2f, dotSize / 2f)
            )
            drawRoundRect(
                color = barColor.copy(alpha = dotAlpha),
                topLeft = Offset(dotX, (vp.centerY + peakSpan / 2f - dotSize * 0.5f).coerceAtMost(vp.bottom - dotSize)),
                size = Size(dotSize, dotSize),
                cornerRadius = CornerRadius(dotSize / 2f, dotSize / 2f)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// #3 Neon Skyline — Bottom glass skyline + full-screen rising background sparks
// ---------------------------------------------------------------------------

private class SkylineBar(
    val startFrac: Float,
    val endFrac: Float,
    val wPx: Float,
    val d: Float,
    val mul: Float,
    val sp: Float,
    val ph: Float
) {
    val xFrac: Float get() = (startFrac + endFrac) * 0.5f
    var h: Float = 0f
    var cap: Float = 0f
    var capV: Float = 0f
}

private class SkylineSpike(
    val d: Float,
    val signedU: Float,
    val boost: Float,
    val sp: Float,
    val ph: Float
) {
    var h: Float = 0f
}

private class SkylineSpark {
    var active = false
    var xNorm = 0.5f
    var yNorm = 0.85f
    var vxNorm = 0f
    var vyNorm = -0.2f
    var sizeDp = 1.6f
    var ph = 0f
    var ts = 2f
    var spriteIdx = 0
    var life = 3f
    var age = 0f
    var square = true
}

private data class SkylineFlare(
    val u: Float,
    val spriteIdx: Int,
    val bandKey: Char, // 'b', 'm', 'h'
    val radiusDp: Float
)

private val SKYLINE_FLARES = listOf(
    SkylineFlare(0.215f, 0, 'h', 18f),
    SkylineFlare(0.335f, 3, 'm', 20f),
    SkylineFlare(0.494f, 2, 'b', 36f),
    SkylineFlare(0.630f, 3, 'm', 18f),
    SkylineFlare(0.675f, 0, 'h', 16f),
    SkylineFlare(0.772f, 0, 'h', 22f)
)

private fun boostNeonColor(color: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[1] = hsv[1].coerceAtLeast(0.68f)
    hsv[2] = hsv[2].coerceAtLeast(0.88f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun shiftHue(color: Color, deltaDegrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[0] = ((hsv[0] + deltaDegrees) % 360f + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun skylineTint(
    d: Float,
    edgeColor: Color,
    midColor: Color,
    centerColor: Color
): Color {
    val t = (1f - d).coerceIn(0f, 1f) // 1 at center, 0 at edges
    return if (t < 0.55f) {
        lerp(edgeColor, midColor, t / 0.55f)
    } else {
        lerp(midColor, centerColor, (t - 0.55f) / 0.45f)
    }
}

private fun makeNeutralGlowSprite(): ImageBitmap {
    val size = 64
    val bmp = ImageBitmap(size, size)
    val canvas = androidx.compose.ui.graphics.Canvas(bmp)
    val paint = Paint()
    val half = size / 2f
    val colors = intArrayOf(
        Color.White.toArgb(),
        Color.White.copy(alpha = 0.85f).toArgb(),
        Color.White.copy(alpha = 0.22f).toArgb(),
        Color.Transparent.toArgb()
    )
    paint.asFrameworkPaint().apply {
        isAntiAlias = true
        shader = RadialGradient(
            half,
            half,
            half,
            colors,
            floatArrayOf(0f, 0.20f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
    return bmp
}

@Composable
fun NeonSkylineBackgroundOverlay(
    energy: Float,
    isPlaying: Boolean,
    positionMs: Long = 0L,
    bpm: Int = 118,
    primaryColor: Color = Color(0xFF965FFF),
    secondaryColor: Color = UltravioletPulse,
    dynamicGlowEnabled: Boolean = true,
    useLiveAudio: Boolean = true,
    modifier: Modifier = Modifier,
    config: VisualizerRenderConfig = VisualizerRenderConfig(),
    particleMotionScale: Float = 1f,
    skylineIntensityScale: Float = 1f
) {
    NeonSkylineCanvas(
        energy = energy,
        isPlaying = isPlaying,
        positionMs = positionMs,
        bpm = bpm,
        primaryColor = primaryColor,
        secondaryColor = secondaryColor,
        dynamicGlowEnabled = dynamicGlowEnabled,
        useLiveAudio = useLiveAudio,
        fullScreenBackground = true,
        config = config,
        particleMotionScale = particleMotionScale,
        skylineIntensityScale = skylineIntensityScale,
        modifier = modifier
    )
}

@Composable
private fun NeonSkylineCanvas(
    energy: Float,
    isPlaying: Boolean,
    positionMs: Long,
    bpm: Int,
    primaryColor: Color,
    secondaryColor: Color,
    dynamicGlowEnabled: Boolean,
    useLiveAudio: Boolean,
    fullScreenBackground: Boolean,
    config: VisualizerRenderConfig,
    particleMotionScale: Float = 1f,
    skylineIntensityScale: Float = 1f,
    modifier: Modifier = Modifier
) {
    val glowSprite = remember { makeNeutralGlowSprite() }
    val spritePool = remember { intArrayOf(0, 1, 2, 3, 3, 0) }
    val bars = remember {
        val rng = Random(4207)
        data class RawBar(val start: Float, val end: Float, val wPx: Float, val mul: Float, val sp: Float, val ph: Float)
        val raw = ArrayList<RawBar>(56)
        var u = 0f
        var lastEnd = 0f
        while (u < 1f) {
            val wStep = 0.011f + rng.nextFloat() * 0.013f
            val gapStep = 0.005f + rng.nextFloat() * 0.008f
            val end = u + wStep
            raw.add(
                RawBar(
                    start = u,
                    end = end,
                    wPx = 4f + rng.nextFloat() * 5.5f,
                    mul = 0.58f + rng.nextFloat() * 0.42f,
                    sp = 0.35f + rng.nextFloat() * 0.75f,
                    ph = rng.nextFloat() * 6.283f
                )
            )
            lastEnd = end
            u = end + gapStep
        }
        val span = lastEnd.coerceAtLeast(1f)
        Array(raw.size) { idx ->
            val rb = raw[idx]
            val sf = (rb.start / span).coerceIn(0f, 1f)
            val ef = if (idx == raw.lastIndex) 1f else (rb.end / span).coerceIn(sf, 1f)
            val centerU = ((sf + ef) * 0.5f) * 2f - 1f
            SkylineBar(
                startFrac = sf,
                endFrac = ef,
                wPx = rb.wPx,
                d = abs(centerU).coerceIn(0f, 1f),
                mul = rb.mul,
                sp = rb.sp,
                ph = rb.ph
            )
        }
    }
    val spikes = remember {
        val rng = Random(9181)
        Array(12) {
            val su = -0.5f + rng.nextFloat()
            SkylineSpike(
                d = abs(su).coerceIn(0f, 0.5f),
                signedU = su,
                boost = 1.1f + rng.nextFloat() * 0.6f,
                sp = 0.4f + rng.nextFloat() * 0.8f,
                ph = rng.nextFloat() * 6.283f
            )
        }
    }
    val sparks = remember { Array(220) { SkylineSpark() } }
    val sparkRng = remember { Random(System.nanoTime()) }
    var prewarmed by remember { mutableFloatStateOf(0f) }

    val bands = remember { FloatArray(AudioVisualizerFrame.BAND_COUNT) }
    var bassE by remember { mutableFloatStateOf(0f) }
    var midE by remember { mutableFloatStateOf(0f) }
    var highE by remember { mutableFloatStateOf(0f) }
    var activity by remember { mutableFloatStateOf(0f) }
    var pulse by remember { mutableFloatStateOf(0f) }
    var lastBeatFlag by remember { mutableFloatStateOf(0f) }
    var animTimeSec by remember { mutableFloatStateOf(0f) }
    var spawnAccum by remember { mutableFloatStateOf(0f) }
    var frameTick by remember { mutableLongStateOf(0L) }

    val currentEnergy by rememberUpdatedState(energy)
    val currentPositionMs by rememberUpdatedState(positionMs)
    val currentBpm by rememberUpdatedState(bpm)
    val currentParticleMotion by rememberUpdatedState(particleMotionScale.coerceIn(0f, 2.5f))
    val currentIntensityScale by rememberUpdatedState(skylineIntensityScale.coerceIn(0.25f, 1.75f))

    LaunchedEffect(isPlaying, useLiveAudio, fullScreenBackground) {
        if (prewarmed == 0f) {
            prewarmed = 1f
            val baseNorm = if (fullScreenBackground) 0.996f else 0.72f
            repeat(55) { idx ->
                val s = sparks[idx]
                s.active = true
                val b = bars[sparkRng.nextInt(bars.size)]
                s.xNorm = if (fullScreenBackground) {
                    (b.xFrac + (sparkRng.nextFloat() - 0.5f) * 0.03f).coerceIn(0.01f, 0.99f)
                } else {
                    0.08f + b.xFrac * 0.84f + (sparkRng.nextFloat() - 0.5f) * 0.04f
                }
                s.yNorm = sparkRng.nextFloat() * baseNorm
                s.vxNorm = (sparkRng.nextFloat() - 0.5f) * 0.028f
                s.vyNorm = -(0.14f + sparkRng.nextFloat() * 0.22f)
                s.sizeDp = 0.9f + sparkRng.nextFloat() * 1.4f
                s.ph = sparkRng.nextFloat() * 6.283f
                s.ts = 1.2f + sparkRng.nextFloat() * 2.6f
                s.spriteIdx = if (b.d < 0.28f && sparkRng.nextFloat() < 0.6f) 2 else spritePool[sparkRng.nextInt(spritePool.size)]
                s.life = 3.2f + sparkRng.nextFloat() * 2.6f
                s.age = sparkRng.nextFloat() * s.life * 0.85f
                s.square = sparkRng.nextFloat() < 0.5f
            }
        }

        var lastNanos = 0L
        while (true) {
            var shouldKeepRunning = true
            withFrameNanos { nanos ->
                val dt = if (lastNanos == 0L) {
                    0.016f
                } else {
                    ((nanos - lastNanos) / 1_000_000_000f).coerceIn(0.004f, 0.05f)
                }
                lastNanos = nanos
                animTimeSec += dt

                val liveFrame = if (useLiveAudio && isPlaying) {
                    AudioSpectrumBus.sampleNow(isPlaying = true)
                } else {
                    AudioVisualizerFrame.EMPTY
                }

                val hasAudio = isPlaying
                activity += ((if (hasAudio) 1f else 0f) - activity) * (1f - exp(-dt * 10f))

                // Position + BPM fallback clock so even during brief codec/seek gaps bars lock cleanly to the song
                val posMs = currentPositionMs
                val safeBpm = currentBpm.coerceIn(60, 200)
                val beatPeriodMs = (60_000.0 / safeBpm).toFloat().coerceAtLeast(250f)
                val songBeatPhase = ((posMs % beatPeriodMs.toLong().coerceAtLeast(1L)) / beatPeriodMs).coerceIn(0f, 1f)
                val songBeatWave = ((cos(songBeatPhase * 2f * PI.toFloat()) * 0.5f + 0.5f).pow(2)).coerceIn(0f, 1f)
                val songSec = posMs / 1000f
                val fallbackEnergy = currentEnergy.coerceIn(0.08f, 1f)

                val tb = if (hasAudio) {
                    if (liveFrame.isLive) (liveFrame.bassEnergy * 1.18f).coerceIn(0f, 1f)
                    else (fallbackEnergy * (0.45f + 0.55f * songBeatWave)).coerceIn(0f, 1f)
                } else 0f
                val tm = if (hasAudio) {
                    if (liveFrame.isLive) (liveFrame.midEnergy * 1.28f).coerceIn(0f, 1f)
                    else (fallbackEnergy * (0.40f + 0.45f * (0.5f + 0.5f * sin(songSec * 3.1f)))).coerceIn(0f, 1f)
                } else 0f
                val th = if (hasAudio) {
                    if (liveFrame.isLive) (liveFrame.highEnergy * 1.42f).coerceIn(0f, 1f)
                    else (fallbackEnergy * (0.35f + 0.50f * (0.5f + 0.5f * sin(songSec * 6.8f)))).coerceIn(0f, 1f)
                } else 0f

                val fastAttack = 1f - exp(-dt * 26f)
                val smoothDecay = 1f - exp(-dt * 8.5f)
                bassE += (tb - bassE) * (if (tb > bassE) fastAttack else smoothDecay)
                midE += (tm - midE) * (if (tm > midE) fastAttack else smoothDecay)
                highE += (th - highE) * (if (th > highE) fastAttack else smoothDecay)

                val rawBeat = if (!hasAudio) 0f else if (liveFrame.isLive) liveFrame.beatImpulse else songBeatWave
                if (rawBeat > 0.50f && lastBeatFlag <= 0.50f) {
                    pulse = 1f
                }
                lastBeatFlag = rawBeat
                pulse *= exp(-dt * 7.5f)

                val bandAttack = 1f - exp(-dt * 30f)
                val bandDecay = 1f - exp(-dt * 9.5f)
                val bandMaxIdx = (bands.size - 1).coerceAtLeast(1)
                for (i in bands.indices) {
                    val target = when {
                        !hasAudio -> 0f
                        liveFrame.isLive -> liveFrame.bands[i]
                        else -> {
                            val tBand = i.toFloat() / bandMaxIdx.toFloat()
                            val wave = 0.5f + 0.5f * sin(songSec * (3.4f + tBand * 5.2f) + i * 0.52f)
                            val beatLift = (1f - tBand * 0.65f) * songBeatWave * 0.48f
                            (fallbackEnergy * (0.28f + 0.42f * wave) + beatLift).coerceIn(0f, 1f)
                        }
                    }
                    val cur = bands[i]
                    val a = if (target >= cur) bandAttack else bandDecay
                    bands[i] = (cur + (target - cur) * a).let { if (it < 0.003f) 0f else it }
                }

                val driveScale = 0.35f + 0.65f * currentIntensityScale
                // Update bar normalized heights (0..1 of maxH)
                val barAttack = 1f - exp(-dt * 32f)
                val barDecay = 1f - exp(-dt * 9.5f)
                val barBandMax = bands.size - 1
                for (b in bars) {
                    val idle = (0.13f + 0.44f * exp(-(b.d / 0.54f).pow(2))) *
                        (0.45f + 0.55f * (0.5f + 0.5f * sin(animTimeSec * b.sp + b.ph)))
                    var tgt = idle * (1f - activity)
                    if (activity > 0.001f) {
                        val pos = b.d.pow(1.35f) * barBandMax
                        val i0 = pos.toInt().coerceIn(0, barBandMax)
                        val i1 = (i0 + 1).coerceAtMost(barBandMax)
                        val frac = pos - i0
                        val binV = ((bands[i0] * (1f - frac) + bands[i1] * frac) * (1f + b.d * 0.95f))
                            .coerceIn(0f, 1.25f)
                            .pow(1.18f)
                        tgt += binV * activity * 1.06f + pulse * 0.08f * exp(-(b.d / 0.45f).pow(2))
                    }
                    tgt = (tgt * b.mul * driveScale).coerceIn(0f, 1f)
                    b.h += (tgt - b.h) * (if (tgt > b.h) barAttack else barDecay)
                    if (b.h >= b.cap) {
                        b.cap = b.h
                        b.capV = 0f
                    } else {
                        b.capV += 1.6f * dt
                        b.cap = maxOf(b.h, b.cap - b.capV * dt)
                    }
                }

                // Update center spikes
                val spikeAttack = 1f - exp(-dt * 28f)
                val spikeDecay = 1f - exp(-dt * 8.5f)
                for (sp in spikes) {
                    val center = exp(-(sp.d / 0.45f).pow(2))
                    var st = (0.10f + 0.45f * (0.5f + 0.5f * sin(animTimeSec * sp.sp + sp.ph))) *
                        center * (1f - activity)
                    if (activity > 0.001f) {
                        st += (bassE * 0.82f + midE * 0.42f + pulse * 0.28f) * center * activity * 0.94f
                    }
                    st = (st.coerceIn(0f, 1f) * sp.boost * driveScale).coerceIn(0f, 1.35f)
                    sp.h += (st - sp.h) * (if (st > sp.h) spikeAttack else spikeDecay)
                }

                // Update particles drifting upward across the entire screen
                val speedMul = (1f + highE * 0.65f + pulse * 0.25f) * currentParticleMotion
                val baseNorm = if (fullScreenBackground) 0.996f else 0.72f
                val maxBarNorm = if (fullScreenBackground) 0.22f else 0.62f

                for (p in sparks) {
                    if (!p.active) continue
                    p.age += dt
                    if (p.age > p.life || p.yNorm < -0.04f) {
                        p.active = false
                        continue
                    }
                    if (currentParticleMotion > 0.02f) {
                        p.xNorm += p.vxNorm * dt * speedMul
                        p.yNorm += p.vyNorm * dt * speedMul
                    }
                }

                // Spawn new sparks from bar & spike tips proportional to beats & highs
                val spawnMotionScale = if (currentParticleMotion <= 0.02f) {
                    0f
                } else {
                    (0.65f + 0.35f * currentParticleMotion).coerceAtLeast(0.30f)
                }
                val spawnRate = (if (fullScreenBackground) {
                    if (hasAudio) 16f + highE * 36f + bassE * 28f + pulse * 48f else 5f
                } else {
                    if (hasAudio) 14f + highE * 28f + bassE * 22f else 4f
                }) * spawnMotionScale
                spawnAccum += dt * spawnRate
                while (spawnAccum >= 1f) {
                    spawnAccum -= 1f
                    val freeSlot = sparks.firstOrNull { !it.active } ?: break
                    var tipXNorm: Float
                    var tipHeightNorm: Float
                    var dVal: Float
                    if (sparkRng.nextFloat() < 0.20f) {
                        val sk = spikes[sparkRng.nextInt(spikes.size)]
                        tipXNorm = if (fullScreenBackground) {
                            0.5f + sk.signedU * 0.5f
                        } else {
                            0.5f + sk.signedU * 0.42f
                        }
                        tipHeightNorm = sk.h * maxBarNorm
                        dVal = sk.d
                    } else {
                        val b1 = bars[sparkRng.nextInt(bars.size)]
                        val b2 = bars[sparkRng.nextInt(bars.size)]
                        val tbBar = if (b1.h > b2.h) b1 else b2
                        tipXNorm = if (fullScreenBackground) {
                            tbBar.xFrac
                        } else {
                            0.08f + tbBar.xFrac * 0.84f
                        }
                        tipHeightNorm = tbBar.h * maxBarNorm
                        dVal = tbBar.d
                    }
                    if (tipHeightNorm > 0.015f) {
                        val up = if (fullScreenBackground) true else sparkRng.nextFloat() < 0.88f
                        freeSlot.active = true
                        freeSlot.xNorm = tipXNorm.coerceIn(0.01f, 0.99f)
                        freeSlot.yNorm = (baseNorm - tipHeightNorm).coerceIn(0.05f, baseNorm)
                        freeSlot.vxNorm = (sparkRng.nextFloat() - 0.5f) * 0.030f
                        // Fast enough & long enough lifetime to travel all the way up to the top of the screen
                        freeSlot.vyNorm = if (up) {
                            -(0.08f * (1f - activity) + (0.16f + sparkRng.nextFloat() * 0.24f) * (0.65f + bassE * 0.65f + pulse * 0.45f))
                        } else {
                            0.02f + sparkRng.nextFloat() * 0.04f
                        }
                        freeSlot.sizeDp = 0.9f + sparkRng.nextFloat() * 1.4f
                        freeSlot.ph = sparkRng.nextFloat() * 6.283f
                        freeSlot.ts = 1.2f + sparkRng.nextFloat() * 2.6f
                        freeSlot.spriteIdx = if (dVal < 0.28f && sparkRng.nextFloat() < 0.60f) {
                            2
                        } else {
                            spritePool[sparkRng.nextInt(spritePool.size)]
                        }
                        freeSlot.life = if (fullScreenBackground) {
                            (3.2f + sparkRng.nextFloat() * 2.4f)
                        } else {
                            (0.9f + sparkRng.nextFloat() * 1.0f)
                        }
                        freeSlot.age = 0f
                        freeSlot.square = sparkRng.nextFloat() < 0.5f
                    }
                }

                frameTick++
                shouldKeepRunning = true
            }
            if (!shouldKeepRunning) break
        }
    }

    Canvas(modifier = modifier) {
        val tick = frameTick
        if (tick < 0L || size.width <= 0f || size.height <= 0f) return@Canvas

        // Resolve color palette: synced with Dynamic Album Glow when enabled, classic Neon Blue/Violet/Pink when off
        val edgeColor: Color
        val midColor: Color
        val centerColor: Color
        val iceCoreColor: Color
        if (dynamicGlowEnabled) {
            val vividPrimary = boostNeonColor(primaryColor)
            val vividSecondary = boostNeonColor(secondaryColor)
            edgeColor = lerp(shiftHue(vividPrimary, -28f), vividSecondary, 0.30f)
            midColor = vividPrimary
            centerColor = lerp(shiftHue(vividPrimary, 16f), Color.White, 0.34f)
            iceCoreColor = lerp(centerColor, Color.White, 0.62f)
        } else {
            edgeColor = Color(80 / 255f, 135 / 255f, 1f, 1f)
            midColor = Color(150 / 255f, 95 / 255f, 1f, 1f)
            centerColor = Color(1f, 115 / 255f, 245 / 255f, 1f)
            iceCoreColor = Color(0xFFC8DCFF)
        }
        val paletteSprites = arrayOf(edgeColor, midColor, centerColor, iceCoreColor)

        val left: Float
        val top: Float
        val right: Float
        val bottom: Float
        if (fullScreenBackground) {
            left = 0f
            top = 0f
            right = size.width
            bottom = size.height
        } else {
            val padStart = config.contentPadding.calculateStartPadding(layoutDirection).toPx()
            val padEnd = config.contentPadding.calculateEndPadding(layoutDirection).toPx()
            val padTop = config.contentPadding.calculateTopPadding().toPx()
            val padBottom = config.contentPadding.calculateBottomPadding().toPx()

            left = padStart.coerceIn(0f, size.width * 0.45f)
            right = (size.width - padEnd).coerceIn(left + 8f, size.width)
            top = padTop.coerceIn(0f, size.height * 0.45f)
            bottom = (size.height - padBottom).coerceIn(top + 8f, size.height)
        }
        val w = (right - left).coerceAtLeast(8f)
        val h = (bottom - top).coerceAtLeast(8f)

        // Restrained intensity multiplier so background visuals never overpower foreground photos/text
        val baseIntensity = if (fullScreenBackground) 0.56f else 0.85f
        val intensity = (baseIntensity * currentIntensityScale).coerceIn(0.10f, 1.45f)
        val baseY = if (fullScreenBackground) bottom - 1.5.dp.toPx() else bottom - h * 0.26f
        val maxH = if (fullScreenBackground) {
            config.height?.toPx()?.coerceIn(h * 0.12f, h * 0.40f) ?: (h * 0.22f)
        } else {
            h * 0.66f
        }
        val bandL = if (fullScreenBackground) left else left + w * 0.08f
        val bandW = if (fullScreenBackground) w else w * 0.84f

        clipRect(left = left, top = top, right = right, bottom = bottom) {
            // Subtle horizon radial aura synced with active palette
            val glowA = (0.28f + bassE * 0.40f + pulse * 0.16f) * intensity
            scale(scaleX = 1f, scaleY = 0.42f, pivot = Offset(left + w / 2f, baseY)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            centerColor.copy(alpha = (0.45f * glowA).coerceIn(0f, 1f)),
                            midColor.copy(alpha = (0.26f * glowA).coerceIn(0f, 1f)),
                            Color.Transparent
                        ),
                        center = Offset(left + w / 2f, baseY),
                        radius = w * 0.62f
                    ),
                    radius = w * 0.62f,
                    center = Offset(left + w / 2f, baseY)
                )
            }

            // Skyline reflection (inline mode) & main glass skyline (above baseY)
            val passStart = if (fullScreenBackground) 1 else 0
            for (pass in passStart..1) {
                val mirror = pass == 0
                val fall = if (mirror) 0.45f else 1f
                val alphaMul = (if (mirror) 0.22f else 0.78f) * intensity

                for (b in bars) {
                    val hh = b.h * maxH * fall
                    if (hh < 1.5f) continue
                    val col = skylineTint(b.d, edgeColor, midColor, centerColor)
                    val x: Float
                    val bw: Float
                    if (fullScreenBackground) {
                        x = bandL + b.startFrac * bandW
                        bw = ((b.endFrac - b.startFrac) * bandW).coerceAtLeast(2f)
                    } else {
                        bw = b.wPx.dp.toPx().coerceAtLeast(2f)
                        x = bandL + b.xFrac * bandW - bw * 0.5f
                    }
                    val rectTop = if (mirror) baseY else baseY - hh

                    drawRect(
                        color = col.copy(alpha = (0.22f * alphaMul).coerceIn(0f, 1f)),
                        topLeft = Offset(x, rectTop),
                        size = Size(bw, hh)
                    )
                    drawRect(
                        color = lerp(col, Color.White, 0.16f).copy(alpha = (0.62f * alphaMul).coerceIn(0f, 1f)),
                        topLeft = Offset(x, rectTop),
                        size = Size(bw, hh),
                        style = Stroke(width = 1.dp.toPx())
                    )

                    if (!mirror) {
                        val capTopY = baseY - hh
                        drawRect(
                            color = lerp(col, Color.White, 0.72f).copy(alpha = 0.65f * intensity),
                            topLeft = Offset(x, capTopY),
                            size = Size(bw, 1.4.dp.toPx())
                        )
                        val capH = b.cap * maxH
                        if (capH > hh + 4.dp.toPx()) {
                            drawRect(
                                color = lerp(col, Color.White, 0.30f).copy(alpha = 0.72f * intensity),
                                topLeft = Offset(x + bw * 0.5f - 1.dp.toPx(), baseY - capH - 2.dp.toPx()),
                                size = Size(2.2.dp.toPx(), 2.2.dp.toPx())
                            )
                        }
                    }
                }

                for (sp in spikes) {
                    val hh = sp.h * maxH * fall
                    if (hh < 5f) continue
                    val cc = skylineTint(sp.d, edgeColor, midColor, centerColor)
                    val sx = left + w * 0.5f + sp.signedU * (bandW * 0.5f)
                    val endY = if (mirror) baseY + hh else baseY - hh
                    drawLine(
                        color = cc.copy(alpha = (0.68f * alphaMul).coerceIn(0f, 1f)),
                        start = Offset(sx, baseY),
                        end = Offset(sx, endY),
                        strokeWidth = 1.3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            // Glowing horizon lines (edge-to-edge in fullScreenBackground mode)
            val horizonStart = if (fullScreenBackground) left else left + w * 0.03f
            val horizonWidth = if (fullScreenBackground) w else w * 0.94f
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        edgeColor.copy(alpha = if (fullScreenBackground) 0.42f * intensity else 0f),
                        midColor.copy(alpha = 0.56f * intensity),
                        lerp(centerColor, Color.White, 0.35f).copy(alpha = 0.80f * intensity),
                        midColor.copy(alpha = 0.56f * intensity),
                        edgeColor.copy(alpha = if (fullScreenBackground) 0.42f * intensity else 0f)
                    ),
                    startX = horizonStart,
                    endX = horizonStart + horizonWidth
                ),
                topLeft = Offset(horizonStart, baseY - 0.8.dp.toPx()),
                size = Size(horizonWidth, 1.6.dp.toPx())
            )
            if (!fullScreenBackground) {
                drawRect(
                    color = iceCoreColor.copy(alpha = 0.22f * intensity),
                    topLeft = Offset(left + w * 0.10f, baseY + 6.dp.toPx()),
                    size = Size(w * 0.80f, 1.dp.toPx())
                )
            }

            // Subtle twinkling lens flares on horizon
            for (idx in SKYLINE_FLARES.indices) {
                val f = SKYLINE_FLARES[idx]
                val bandVal = when (f.bandKey) {
                    'b' -> bassE
                    'm' -> midE
                    else -> highE
                }
                val twinkle = 0.75f + 0.25f * sin(animTimeSec * (1.3f + idx * 0.37f) + idx)
                val rPx = f.radiusDp.dp.toPx() * (0.75f + bandVal * 1.15f + if (f.bandKey == 'b') pulse * 0.4f else 0f)
                val fx = (left + w * f.u).roundToInt()
                val fy = baseY.roundToInt()
                val flareAlpha = ((0.38f + bandVal * 0.45f) * twinkle * intensity).coerceIn(0f, 0.85f)
                val rInt = rPx.roundToInt().coerceAtLeast(4)
                val flareTint = paletteSprites[f.spriteIdx]

                drawImage(
                    image = glowSprite,
                    dstOffset = IntOffset(fx - rInt, fy - rInt),
                    dstSize = IntSize(rInt * 2, rInt * 2),
                    alpha = flareAlpha,
                    colorFilter = ColorFilter.tint(flareTint)
                )
                val streakPx = if (f.bandKey == 'b') w * 0.06f + bassE * w * 0.04f else rPx * 1.8f
                val streakInt = streakPx.roundToInt().coerceAtLeast(4)
                val streakH = (rPx * 0.32f).roundToInt().coerceAtLeast(2)
                drawImage(
                    image = glowSprite,
                    dstOffset = IntOffset(fx - streakInt, fy - streakH / 2),
                    dstSize = IntSize(streakInt * 2, streakH),
                    alpha = (flareAlpha * 0.55f).coerceIn(0f, 0.7f),
                    colorFilter = ColorFilter.tint(lerp(flareTint, Color.White, 0.35f))
                )
            }

            // Full-screen upward drifting background sparks (low glare so foreground text/art stay crystal clear)
            val bright = (0.48f + highE * 0.55f + pulse * 0.20f) * intensity
            for (q in sparks) {
                if (!q.active) continue
                val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(animTimeSec * q.ts + q.ph))
                val fadeOut = (1f - (q.age / q.life)).coerceIn(0f, 1f)
                // Soft fade near the very top edge of screen
                val topFade = ((q.yNorm - 0.02f) / 0.10f).coerceIn(0f, 1f)
                val a = (tw * fadeOut * topFade * bright).coerceIn(0f, 0.72f)
                if (a <= 0.01f) continue

                val px = left + q.xNorm * w
                val py = top + q.yNorm * h
                val sPx = q.sizeDp.dp.toPx()
                val glowR = (sPx * 4.5f).roundToInt().coerceAtLeast(3)
                val sparkTint = paletteSprites[q.spriteIdx]

                drawImage(
                    image = glowSprite,
                    dstOffset = IntOffset(px.roundToInt() - glowR, py.roundToInt() - glowR),
                    dstSize = IntSize(glowR * 2, glowR * 2),
                    alpha = (a * 0.48f).coerceIn(0f, 0.55f),
                    colorFilter = ColorFilter.tint(sparkTint)
                )

                val coreSize = if (q.square) sPx else sPx * 0.8f
                drawRect(
                    color = lerp(sparkTint, Color.White, 0.65f).copy(alpha = (a * 0.85f).coerceIn(0f, 0.75f)),
                    topLeft = Offset(px - coreSize * 0.5f, py - coreSize * 0.5f),
                    size = Size(coreSize, coreSize)
                )
            }
        }
    }
}
