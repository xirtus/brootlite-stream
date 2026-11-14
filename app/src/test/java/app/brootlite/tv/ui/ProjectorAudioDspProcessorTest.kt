package app.brootlite.tv.ui

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import app.brootlite.tv.data.AudioDspConfig
import app.brootlite.tv.data.AudioEqPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class ProjectorAudioDspProcessorTest {

    @Test
    fun boost200DoublesQuietPcmWithoutDistortion() {
        val dsp = ProjectorAudioDspProcessor(AudioDspConfig(boostPercent = 200))
        dsp.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        dsp.flush()

        val frames = 256
        val inBuf = ByteBuffer.allocate(frames * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * 2) {
            inBuf.putShort(2000.toShort())
        }
        inBuf.flip()

        dsp.queueInput(inBuf)
        val out = dsp.output.order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(frames * 2 * 2, out.remaining())

        // Last sample should be smoothly at 2.0x (~4000)
        var lastSample = 0
        while (out.hasRemaining()) {
            lastSample = out.short.toInt()
        }
        assertTrue("Expected ~4000 at 200% boost, got $lastSample", lastSample in 3980..4020)
    }

    @Test
    fun boost300SoftLimitsFullScalePeaksWithoutWrapOrClipping() {
        val dsp = ProjectorAudioDspProcessor(
            AudioDspConfig(
                boostPercent = 300,
                presetId = AudioEqPreset.PROJECTOR.id,
                bassDb = 4,
                voiceDb = 6,
                trebleDb = 3,
                dialogueCompress = true
            )
        )
        dsp.configure(AudioProcessor.AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT))
        dsp.flush()

        val frames = 512
        val inBuf = ByteBuffer.allocate(frames * 6 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) {
            val sign = if ((f / 12) % 2 == 0) 1 else -1
            for (ch in 0 until 6) {
                inBuf.putShort((sign * 28_000).toShort())
            }
        }
        inBuf.flip()

        dsp.queueInput(inBuf)
        val out = dsp.output.order(ByteOrder.LITTLE_ENDIAN)
        var peakAbs = 0
        while (out.hasRemaining()) {
            val s = out.short.toInt()
            peakAbs = maxOf(peakAbs, abs(s))
        }
        assertTrue("Expected soft-limited strong output without overflow, got peak=$peakAbs", peakAbs in 27_500..32_767)
    }

    @Test
    fun default100FlatIsBitExactPassThrough() {
        val dsp = ProjectorAudioDspProcessor(AudioDspConfig())
        dsp.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        dsp.flush()

        val inBuf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        val expected = shortArrayOf(0, -1234, 5678, -30000, 30000, 42, -42, 1024, -1024, 7, -7, 999, -999, 15000, -15000, 1)
        expected.forEach { inBuf.putShort(it) }
        inBuf.flip()

        dsp.queueInput(inBuf)
        val out = dsp.output.order(ByteOrder.LITTLE_ENDIAN)
        for (exp in expected) {
            assertEquals(exp, out.short)
        }
    }
}
