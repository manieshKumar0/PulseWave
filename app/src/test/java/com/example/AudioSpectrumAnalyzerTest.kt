package com.example

import androidx.media3.common.C
import com.example.media.AudioSpectrumAnalyzer
import com.example.media.AudioSpectrumBus
import com.example.media.AudioVisualizerFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class AudioSpectrumAnalyzerTest {

    private fun generatePcm16StereoSine(
        sampleRateHz: Int,
        freqHz: Double,
        amplitude: Double,
        frameCount: Int
    ): ByteBuffer {
        val buf = ByteBuffer.allocate(frameCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            val sample = (sin(2.0 * PI * freqHz * i / sampleRateHz) * amplitude * 32767.0)
                .toInt()
                .coerceIn(-32768, 32767)
                .toShort()
            buf.putShort(sample)
            buf.putShort(sample)
        }
        buf.flip()
        return buf
    }

    private fun generatePcmFloatMonoSine(
        sampleRateHz: Int,
        freqHz: Double,
        amplitude: Float,
        frameCount: Int
    ): ByteBuffer {
        val buf = ByteBuffer.allocate(frameCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            val sample = (sin(2.0 * PI * freqHz * i / sampleRateHz) * amplitude).toFloat()
            buf.putFloat(sample)
        }
        buf.flip()
        return buf
    }

    @Test
    fun silencePcm_producesZeroEnergyAndBands() {
        val analyzer = AudioSpectrumAnalyzer(latencyCompensationMs = 0L)
        analyzer.flush(sampleRateHz = 48_000, channelCount = 2, encoding = C.ENCODING_PCM_16BIT)

        val silence = ByteBuffer.allocate(4096 * 4).order(ByteOrder.LITTLE_ENDIAN)
        analyzer.handleBuffer(silence)

        val frame = analyzer.latestFrame
        assertTrue(frame.isLive)
        assertEquals(0f, frame.bassEnergy, 0.0001f)
        assertEquals(0f, frame.overallEnergy, 0.0001f)
        assertTrue(frame.bands.all { it == 0f })
    }

    @Test
    fun bassSineWave_concentratesInLowFrequencyBands_andPreservesCallerBufferPosition() {
        val analyzer = AudioSpectrumAnalyzer(latencyCompensationMs = 0L)
        analyzer.flush(sampleRateHz = 48_000, channelCount = 2, encoding = C.ENCODING_PCM_16BIT)

        val bassBuffer = generatePcm16StereoSine(
            sampleRateHz = 48_000,
            freqHz = 85.0,
            amplitude = 0.75,
            frameCount = 4096
        )
        val initialPos = bassBuffer.position()
        val initialLimit = bassBuffer.limit()

        analyzer.handleBuffer(bassBuffer)

        assertEquals(initialPos, bassBuffer.position())
        assertEquals(initialLimit, bassBuffer.limit())

        val frame = analyzer.latestFrame
        assertTrue(frame.isLive)
        val bassMax = frame.bands.slice(0..5).maxOrNull() ?: 0f
        val trebleMax = frame.bands.slice(18 until frame.bands.size).maxOrNull() ?: 0f
        assertTrue("Expected strong bass band peak ($bassMax)", bassMax > 0.5f)
        assertTrue("Expected bass ($bassMax) to dominate treble ($trebleMax)", bassMax > trebleMax * 3f)
        assertTrue("Expected bassEnergy > highEnergy", frame.bassEnergy > frame.highEnergy * 3f)
    }

    @Test
    fun treblePcmFloatSineWave_concentratesInHighFrequencyBands() {
        val analyzer = AudioSpectrumAnalyzer(latencyCompensationMs = 0L)
        analyzer.flush(sampleRateHz = 48_000, channelCount = 1, encoding = C.ENCODING_PCM_FLOAT)

        val highBuffer = generatePcmFloatMonoSine(
            sampleRateHz = 48_000,
            freqHz = 5_000.0,
            amplitude = 0.65f,
            frameCount = 4096
        )
        analyzer.handleBuffer(highBuffer)

        val frame = analyzer.latestFrame
        assertTrue(frame.isLive)
        val lowMax = frame.bands.slice(0..6).maxOrNull() ?: 0f
        val highMax = frame.bands.slice(18 until frame.bands.size).maxOrNull() ?: 0f
        assertTrue("Expected strong high band peak ($highMax)", highMax > 0.5f)
        assertTrue("Expected treble ($highMax) to dominate bass ($lowMax)", highMax > lowMax * 3f)
        assertTrue("Expected highEnergy > bassEnergy", frame.highEnergy > frame.bassEnergy * 3f)
    }

    @Test
    fun spectrumBus_clearsImmediatelyWhenPausedOrFlushed() {
        AudioSpectrumBus.reset()
        AudioSpectrumBus.audioBufferSink.flush(48_000, 2, C.ENCODING_PCM_16BIT)

        val bassBuffer = generatePcm16StereoSine(
            sampleRateHz = 48_000,
            freqHz = 90.0,
            amplitude = 0.8,
            frameCount = 4096
        )
        AudioSpectrumBus.setPlaybackActive(true)
        AudioSpectrumBus.audioBufferSink.handleBuffer(bassBuffer)

        // Sampling while playing primes the active live frame
        val live = AudioSpectrumBus.sampleNow(isPlaying = true)
        assertTrue(live.isLive)
        assertTrue(live.bassEnergy > 0.3f)

        // Pausing immediately returns inactive EMPTY frame
        AudioSpectrumBus.setPlaybackActive(false)
        val paused = AudioSpectrumBus.sampleNow(isPlaying = false)
        assertFalse(paused.isLive)
        assertEquals(AudioVisualizerFrame.EMPTY, paused)

        // Flushing resets buffered frames as well
        AudioSpectrumBus.audioBufferSink.flush(48_000, 2, C.ENCODING_PCM_16BIT)
        val postFlush = AudioSpectrumBus.sampleNow(isPlaying = true)
        assertFalse(postFlush.isLive)
        AudioSpectrumBus.reset()
    }
}
