package com.example.media

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class AudioVisualizerFrame(
    val bands: FloatArray,
    val bassEnergy: Float,
    val midEnergy: Float,
    val highEnergy: Float,
    val overallEnergy: Float,
    val beatImpulse: Float,
    val beatPhase: Float,
    val isLive: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioVisualizerFrame) return false
        return bands.contentEquals(other.bands) &&
            bassEnergy == other.bassEnergy &&
            midEnergy == other.midEnergy &&
            highEnergy == other.highEnergy &&
            overallEnergy == other.overallEnergy &&
            beatImpulse == other.beatImpulse &&
            beatPhase == other.beatPhase &&
            isLive == other.isLive
    }

    override fun hashCode(): Int {
        var result = bands.contentHashCode()
        result = 31 * result + bassEnergy.hashCode()
        result = 31 * result + midEnergy.hashCode()
        result = 31 * result + highEnergy.hashCode()
        result = 31 * result + overallEnergy.hashCode()
        result = 31 * result + beatImpulse.hashCode()
        result = 31 * result + beatPhase.hashCode()
        result = 31 * result + isLive.hashCode()
        return result
    }

    companion object {
        const val BAND_COUNT = 28

        val EMPTY = AudioVisualizerFrame(
            bands = FloatArray(BAND_COUNT),
            bassEnergy = 0f,
            midEnergy = 0f,
            highEnergy = 0f,
            overallEnergy = 0f,
            beatImpulse = 0f,
            beatPhase = 0f,
            isLive = false
        )
    }
}

/**
 * Real-time Radix-2 FFT audio spectrum & beat analyzer that attaches directly to ExoPlayer's
 * decoded PCM pipeline via [TeeAudioProcessor.AudioBufferSink] without requiring RECORD_AUDIO.
 */
@OptIn(UnstableApi::class)
class AudioSpectrumAnalyzer(
    private val bandCount: Int = AudioVisualizerFrame.BAND_COUNT,
    private val fftSize: Int = 2048,
    private val hopSize: Int = 1024,
    private val latencyCompensationMs: Long = 85L,
    private val elapsedTimeProvider: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onFrameProduced: ((ScheduledFrame) -> Unit)? = null
) : TeeAudioProcessor.AudioBufferSink {

    data class ScheduledFrame(
        val deliverAtMs: Long,
        val frame: AudioVisualizerFrame
    )

    private val lock = Any()

    private var sampleRateHz: Int = 48_000
    private var channelCount: Int = 2
    private var encoding: Int = C.ENCODING_PCM_16BIT

    // Sliding PCM ring buffer (mono, normalized -1f..1f)
    private val ringSamples = FloatArray(fftSize)
    private var ringWriteIdx = 0
    private var samplesCollectedTotal = 0
    private var samplesSinceLastHop = 0

    // Reusable FFT buffers (zero allocation inside handleBuffer)
    private val fftReal = FloatArray(fftSize)
    private val fftImag = FloatArray(fftSize)
    private val magnitudes = FloatArray(fftSize / 2 + 1)
    private val window = FloatArray(fftSize)
    private val bitReversed = IntArray(fftSize)
    private val cosTable = FloatArray(fftSize / 2)
    private val sinTable = FloatArray(fftSize / 2)

    // Logarithmic band boundaries in FFT bin indices (floating point for accurate low-end interpolation)
    private val bandLowBin = FloatArray(bandCount)
    private val bandHighBin = FloatArray(bandCount)
    private val bandGainTilt = FloatArray(bandCount)
    private val workBands = FloatArray(bandCount)

    // Adaptive gain & beat tracking state
    private var adaptivePeak = 0.08f
    private var bassBaseline = 0.08f
    private var beatImpulse = 0f
    private var beatPhase = 0f
    private var lastScheduledDeliverMs = 0.0

    @Volatile
    var latestFrame: AudioVisualizerFrame = AudioVisualizerFrame.EMPTY
        private set

    init {
        require(fftSize > 0 && (fftSize and (fftSize - 1)) == 0) { "fftSize must be a power of 2" }
        require(hopSize in 1..fftSize) { "hopSize must be in 1..fftSize" }

        for (i in 0 until fftSize) {
            window[i] = (0.5 * (1.0 - cos(2.0 * PI * i / (fftSize - 1)))).toFloat()
        }

        val bits = Integer.numberOfTrailingZeros(fftSize)
        for (i in 0 until fftSize) {
            bitReversed[i] = Integer.reverse(i) ushr (32 - bits)
        }
        for (i in 0 until fftSize / 2) {
            val angle = -2.0 * PI * i / fftSize
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }

        recomputeFrequencyBands(sampleRateHz)
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        synchronized(lock) {
            this.sampleRateHz = sampleRateHz.coerceIn(8_000, 192_000)
            this.channelCount = channelCount.coerceIn(1, 8)
            this.encoding = encoding
            ringSamples.fill(0f)
            ringWriteIdx = 0
            samplesCollectedTotal = 0
            samplesSinceLastHop = 0
            adaptivePeak = 0.08f
            bassBaseline = 0.08f
            beatImpulse = 0f
            lastScheduledDeliverMs = 0.0
            recomputeFrequencyBands(this.sampleRateHz)
            latestFrame = AudioVisualizerFrame.EMPTY
        }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val remaining = buffer.remaining()
        if (remaining <= 0) return

        val readOnly = buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        synchronized(lock) {
            val bytesPerSample = when (encoding) {
                C.ENCODING_PCM_8BIT -> 1
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
                else -> return
            }
            val frameStride = bytesPerSample * channelCount
            if (frameStride <= 0) return

            val frameCount = remaining / frameStride
            if (frameCount <= 0) return

            val hopDurationMs = ((hopSize * 1000.0) / sampleRateHz).coerceAtLeast(8.0)
            val nowMs = elapsedTimeProvider()

            for (f in 0 until frameCount) {
                var monoSum = 0f
                for (ch in 0 until channelCount) {
                    monoSum += readNormalizedSample(readOnly, encoding)
                }
                val mono = (monoSum / channelCount.toFloat()).coerceIn(-1f, 1f)
                ringSamples[ringWriteIdx] = mono
                ringWriteIdx = (ringWriteIdx + 1) and (fftSize - 1)
                if (samplesCollectedTotal < fftSize) {
                    samplesCollectedTotal++
                }
                samplesSinceLastHop++

                if (samplesCollectedTotal >= fftSize && samplesSinceLastHop >= hopSize) {
                    samplesSinceLastHop -= hopSize
                    val frame = analyzeWindow(nowMs)
                    latestFrame = frame

                    val baseTargetMs = (nowMs + latencyCompensationMs).toDouble()
                    val scheduledMs = if (lastScheduledDeliverMs in (nowMs - 45.0)..(nowMs + 1400.0)) {
                        maxOf(baseTargetMs, lastScheduledDeliverMs + hopDurationMs)
                    } else {
                        baseTargetMs
                    }.coerceAtMost(nowMs + 1400.0)
                    lastScheduledDeliverMs = scheduledMs
                    onFrameProduced?.invoke(ScheduledFrame(deliverAtMs = scheduledMs.toLong(), frame = frame))
                }
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            ringSamples.fill(0f)
            ringWriteIdx = 0
            samplesCollectedTotal = 0
            samplesSinceLastHop = 0
            adaptivePeak = 0.08f
            bassBaseline = 0.08f
            beatImpulse = 0f
            beatPhase = 0f
            lastScheduledDeliverMs = 0.0
            latestFrame = AudioVisualizerFrame.EMPTY
        }
    }

    private fun readNormalizedSample(buf: ByteBuffer, enc: Int): Float {
        return when (enc) {
            C.ENCODING_PCM_16BIT -> buf.short.toFloat() / 32768f
            C.ENCODING_PCM_FLOAT -> buf.float
            C.ENCODING_PCM_8BIT -> ((buf.get().toInt() and 0xFF) - 128) / 128f
            C.ENCODING_PCM_24BIT -> {
                val b0 = buf.get().toInt() and 0xFF
                val b1 = buf.get().toInt() and 0xFF
                val b2 = buf.get().toInt() // signed high byte
                val sample24 = (b2 shl 16) or (b1 shl 8) or b0
                sample24.toFloat() / 8_388_608f
            }
            C.ENCODING_PCM_32BIT -> buf.int.toFloat() / 2_147_483_648f
            else -> 0f
        }
    }

    private fun recomputeFrequencyBands(rateHz: Int) {
        val nyquist = (rateHz / 2f).coerceAtLeast(2_000f)
        val minFreq = 42f
        val maxFreq = minOf(14_500f, nyquist * 0.92f).coerceAtLeast(minFreq * 4f)
        val binHz = rateHz.toFloat() / fftSize.toFloat()
        val maxBin = (fftSize / 2 - 1).toFloat()

        for (b in 0 until bandCount) {
            val t0 = b.toFloat() / bandCount.toFloat()
            val t1 = (b + 1).toFloat() / bandCount.toFloat()
            val fLow = minFreq * (maxFreq / minFreq).pow(t0)
            val fHigh = minFreq * (maxFreq / minFreq).pow(t1)
            bandLowBin[b] = (fLow / binHz).coerceIn(1f, maxBin)
            bandHighBin[b] = (fHigh / binHz).coerceIn(bandLowBin[b], maxBin)
            // Perceptual pink-noise + upper-harmonic tilt so vocals & hi-hats animate clearly
            // without drowning out kick drums.
            bandGainTilt[b] = 1.0f + 2.35f * t0.pow(0.85f)
        }
    }

    private fun analyzeWindow(@Suppress("UNUSED_PARAMETER") nowMs: Long): AudioVisualizerFrame {
        // Reorder ring buffer chronologically with Hann window applied
        var rmsAccum = 0f
        for (i in 0 until fftSize) {
            val srcIdx = (ringWriteIdx + i) and (fftSize - 1)
            val sample = ringSamples[srcIdx]
            rmsAccum += sample * sample
            val windowed = sample * window[i]
            val rev = bitReversed[i]
            fftReal[rev] = windowed
            fftImag[rev] = 0f
        }

        val rms = sqrt(rmsAccum / fftSize.toFloat())
        // Hard silence / ultra-low noise gate (< -58 dBFS)
        if (rms < 0.0012f) {
            beatImpulse *= 0.72f
            adaptivePeak = maxOf(0.045f, adaptivePeak * 0.992f)
            return AudioVisualizerFrame(
                bands = FloatArray(bandCount),
                bassEnergy = 0f,
                midEnergy = 0f,
                highEnergy = 0f,
                overallEnergy = 0f,
                beatImpulse = beatImpulse.coerceIn(0f, 1f),
                beatPhase = beatPhase,
                isLive = true
            )
        }

        // In-place Cooley-Tukey Radix-2 FFT
        var size = 2
        while (size <= fftSize) {
            val halfSize = size ushr 1
            val tableStep = fftSize / size
            var i = 0
            while (i < fftSize) {
                var k = 0
                var j = 0
                while (j < halfSize) {
                    val cosW = cosTable[k]
                    val sinW = sinTable[k]
                    val evenIdx = i + j
                    val oddIdx = evenIdx + halfSize
                    val oddR = fftReal[oddIdx]
                    val oddI = fftImag[oddIdx]
                    val tReal = oddR * cosW - oddI * sinW
                    val tImag = oddR * sinW + oddI * cosW
                    val evenR = fftReal[evenIdx]
                    val evenI = fftImag[evenIdx]
                    fftReal[oddIdx] = evenR - tReal
                    fftImag[oddIdx] = evenI - tImag
                    fftReal[evenIdx] = evenR + tReal
                    fftImag[evenIdx] = evenI + tImag
                    k += tableStep
                    j++
                }
                i += size
            }
            size = size shl 1
        }

        // Magnitude spectrum (positive frequencies normalized by window coherent gain * N/2)
        val normScale = 1f / (fftSize * 0.25f)
        val nyquistBin = fftSize / 2
        for (bin in 1..nyquistBin) {
            val re = fftReal[bin]
            val im = fftImag[bin]
            magnitudes[bin] = sqrt(re * re + im * im) * normScale
        }

        var frameMax = 0f
        for (b in 0 until bandCount) {
            val lo = bandLowBin[b]
            val hi = bandHighBin[b]
            val loIdx = lo.toInt().coerceIn(1, nyquistBin)
            val hiIdx = hi.toInt().coerceIn(loIdx, nyquistBin)

            val rawMag = if (loIdx == hiIdx) {
                // Interpolate smoothly between adjacent bins in sub-bass region
                val frac = (lo - loIdx.toFloat()).coerceIn(0f, 1f)
                val nextIdx = (loIdx + 1).coerceAtMost(nyquistBin)
                magnitudes[loIdx] * (1f - frac) + magnitudes[nextIdx] * frac
            } else {
                // RMS + peak blend inside the band preserves punchy harmonics
                var sumSq = 0f
                var peak = 0f
                var count = 0
                for (bin in loIdx..hiIdx) {
                    val m = magnitudes[bin]
                    sumSq += m * m
                    if (m > peak) peak = m
                    count++
                }
                val bandRms = sqrt(sumSq / count.coerceAtLeast(1))
                bandRms * 0.55f + peak * 0.45f
            }

            val tilted = rawMag * bandGainTilt[b]
            workBands[b] = tilted
            if (tilted > frameMax) frameMax = tilted
        }

        // Adaptive Gain Control: fast rise for sudden drops, gentle ~2.5s decay for quiet bridges
        adaptivePeak = if (frameMax > adaptivePeak) {
            adaptivePeak * 0.65f + frameMax * 0.35f
        } else {
            maxOf(0.045f, adaptivePeak * 0.994f)
        }
        val gainDenominator = maxOf(adaptivePeak, 0.045f)

        val outBands = FloatArray(bandCount)
        val logDenom = ln(1f + 10f)
        for (b in 0 until bandCount) {
            val normalized = (workBands[b] / gainDenominator).coerceIn(0f, 1.35f)
            // Logarithmic perceptual curve + slight power shaping keeps noise low & beats expressive
            val compressed = (ln(1f + 10f * normalized) / logDenom).coerceIn(0f, 1f)
            outBands[b] = if (compressed < 0.03f) 0f else compressed.pow(1.08f)
        }

        var bassSum = 0f
        var bassPeak = 0f
        for (b in 0 until 6) {
            bassSum += outBands[b]
            if (outBands[b] > bassPeak) bassPeak = outBands[b]
        }
        val bassEnergy = ((bassSum / 6f) * 0.55f + bassPeak * 0.45f).coerceIn(0f, 1f)

        var midSum = 0f
        for (b in 6 until 18) midSum += outBands[b]
        val midEnergy = (midSum / 12f).coerceIn(0f, 1f)

        var highSum = 0f
        for (b in 18 until bandCount) highSum += outBands[b]
        val highEnergy = (highSum / (bandCount - 18).coerceAtLeast(1)).coerceIn(0f, 1f)

        val overallEnergy = (bassEnergy * 0.45f + midEnergy * 0.38f + highEnergy * 0.17f).coerceIn(0f, 1f)

        // Bass kick onset detector tuned so every beat drop & punch registers cleanly
        val isKickOnset = bassEnergy > 0.20f && bassEnergy > (bassBaseline * 1.16f + 0.035f)
        bassBaseline = bassBaseline * 0.89f + bassEnergy * 0.11f
        beatImpulse = if (isKickOnset) {
            maxOf(beatImpulse, ((bassEnergy - bassBaseline) * 2.4f).coerceIn(0.55f, 1f))
        } else {
            (beatImpulse * 0.84f).coerceIn(0f, 1f)
        }

        // Advance continuous musical phase synced to beat energy & transients
        val phaseStep = 0.014f + 0.038f * overallEnergy + 0.042f * beatImpulse
        beatPhase = (beatPhase + phaseStep) % 1f

        return AudioVisualizerFrame(
            bands = outBands,
            bassEnergy = bassEnergy,
            midEnergy = midEnergy,
            highEnergy = highEnergy,
            overallEnergy = overallEnergy,
            beatImpulse = beatImpulse,
            beatPhase = beatPhase,
            isLive = true
        )
    }
}

/**
 * Process-wide bus connecting Media3's [TeeAudioProcessor] in [PulseMediaService]
 * with Compose visualizers and [PlaybackController] with latency alignment and fast/clean pause reset.
 */
@OptIn(UnstableApi::class)
object AudioSpectrumBus {
    private const val MAX_QUEUE_SIZE = 96
    private const val STALE_AFTER_MS = 950L

    private val queueLock = Any()
    private val scheduledQueue = ArrayDeque<AudioSpectrumAnalyzer.ScheduledFrame>(MAX_QUEUE_SIZE)

    @Volatile
    private var lastDeliveredFrame: AudioVisualizerFrame = AudioVisualizerFrame.EMPTY

    @Volatile
    private var lastDeliveredAtMs: Long = 0L

    @Volatile
    private var playbackActive: Boolean = false

    val analyzer: AudioSpectrumAnalyzer = AudioSpectrumAnalyzer(
        latencyCompensationMs = 85L,
        onFrameProduced = { scheduled ->
            synchronized(queueLock) {
                while (scheduledQueue.size >= MAX_QUEUE_SIZE) {
                    scheduledQueue.removeFirst()
                }
                scheduledQueue.addLast(scheduled)
            }
        }
    )

    val audioBufferSink: TeeAudioProcessor.AudioBufferSink = object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            clearPendingQueue()
            analyzer.flush(sampleRateHz, channelCount, encoding)
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            analyzer.handleBuffer(buffer)
        }
    }

    fun setPlaybackActive(active: Boolean) {
        playbackActive = active
        if (!active) {
            clearPendingQueue()
        }
    }

    fun clearPendingQueue() {
        synchronized(queueLock) {
            scheduledQueue.clear()
            lastDeliveredFrame = AudioVisualizerFrame.EMPTY
            lastDeliveredAtMs = 0L
        }
    }

    fun reset() {
        playbackActive = false
        clearPendingQueue()
        analyzer.reset()
    }

    fun sampleNow(isPlaying: Boolean, nowMs: Long = System.nanoTime() / 1_000_000L): AudioVisualizerFrame {
        if (!isPlaying) {
            return AudioVisualizerFrame.EMPTY
        }
        synchronized(queueLock) {
            var candidate: AudioSpectrumAnalyzer.ScheduledFrame? = null
            while (scheduledQueue.isNotEmpty()) {
                val head = scheduledQueue.first()
                if (head.deliverAtMs <= nowMs) {
                    candidate = scheduledQueue.removeFirst()
                } else {
                    break
                }
            }

            if (candidate != null) {
                lastDeliveredFrame = candidate.frame
                lastDeliveredAtMs = nowMs
                return candidate.frame
            }

            // If a frame just arrived within the latency window and we don't yet have one delivered,
            // prime immediately on track start instead of staying blank.
            if (lastDeliveredFrame === AudioVisualizerFrame.EMPTY && scheduledQueue.isNotEmpty()) {
                val first = scheduledQueue.first()
                if (first.deliverAtMs - nowMs <= 160L) {
                    lastDeliveredFrame = first.frame
                    lastDeliveredAtMs = nowMs
                    return first.frame
                }
            }

            return if (lastDeliveredFrame.isLive && (nowMs - lastDeliveredAtMs) <= STALE_AFTER_MS) {
                lastDeliveredFrame
            } else {
                AudioVisualizerFrame.EMPTY
            }
        }
    }
}
