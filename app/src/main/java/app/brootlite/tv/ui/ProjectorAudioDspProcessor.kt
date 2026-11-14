package app.brootlite.tv.ui

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import app.brootlite.tv.data.AudioDspConfig
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Real-time zero-allocation PCM Audio Boost (50%–300%), 3-Band Biquad Parametric EQ
 * (100 Hz Bass Low-Shelf · 1.8 kHz Voice Presence · 7.5 kHz Treble High-Shelf),
 * 5.1/7.1 Center-Channel Dialogue Lift, Kodi-style Dialogue DRC, and Soft-Knee Limiter.
 *
 * Runs directly inside Media3's `DefaultAudioSink` pipeline across both hardware MediaCodec
 * and Jellyfin FFmpeg decoded streams (`PCM_16BIT` & `PCM_FLOAT`, 1..8 channels) with zero
 * native `.so` bloat and zero heap allocations in `queueInput`.
 */
@UnstableApi
class ProjectorAudioDspProcessor(initialConfig: AudioDspConfig = AudioDspConfig()) : BaseAudioProcessor() {

    companion object {
        private const val MAX_CHANNELS = 8
        private const val NUM_BANDS = 3
    }

    @Volatile
    private var config: AudioDspConfig = initialConfig

    @Volatile
    private var coeffsDirty: Boolean = true

    private var smoothedGain: Float = (initialConfig.boostPercent / 100f).coerceIn(0.5f, 3.0f)
    private var drcEnvelope: Float = 0f

    // Direct-Form II Transposed biquad coefficients [band * 5]: b0, b1, b2, a1, a2
    private val coeffs = FloatArray(NUM_BANDS * 5)
    private val bandActive = BooleanArray(NUM_BANDS)

    // Filter delay states [band * MAX_CHANNELS]
    private val z1 = FloatArray(NUM_BANDS * MAX_CHANNELS)
    private val z2 = FloatArray(NUM_BANDS * MAX_CHANNELS)

    // Scratch per-frame sample buffer (MAX_CHANNELS = 8 -> zero per-frame allocations)
    private val frameSamples = FloatArray(MAX_CHANNELS)
    private var coeffSampleRate: Int = 0

    fun updateConfig(newConfig: AudioDspConfig) {
        val prev = config
        config = newConfig
        if (prev.bassDb != newConfig.bassDb ||
            prev.voiceDb != newConfig.voiceDb ||
            prev.trebleDb != newConfig.trebleDb
        ) {
            coeffsDirty = true
        }
    }

    fun currentConfig(): AudioDspConfig = config

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val validEncoding = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val validChannels = inputAudioFormat.channelCount in 1..MAX_CHANNELS
        val validRate = inputAudioFormat.sampleRate in 8_000..192_000
        if (!validEncoding || !validChannels || !validRate) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        coeffsDirty = true
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val cfg = config
        val targetGain = (cfg.boostPercent / 100f).coerceIn(0.5f, 3.0f)
        val hasEq = cfg.bassDb != 0 || cfg.voiceDb != 0 || cfg.trebleDb != 0
        val useDrc = cfg.dialogueCompress

        // Zero-CPU pass-through fast path when 100% Flat and smoothed gain has settled
        if (!hasEq && !useDrc && targetGain == 1.0f && abs(smoothedGain - 1.0f) < 0.0005f) {
            smoothedGain = 1.0f
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            return
        }

        val sr = inputAudioFormat.sampleRate
        val channels = inputAudioFormat.channelCount
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameSize = channels * bytesPerSample
        val frameCount = remaining / frameSize

        if (coeffsDirty || coeffSampleRate != sr) {
            recomputeBiquadCoefficients(sr, cfg)
            coeffSampleRate = sr
            coeffsDirty = false
        }

        val out = replaceOutputBuffer(frameCount * frameSize)
        val srcPos = inputBuffer.position()
        inputBuffer.limit(srcPos + frameCount * frameSize)

        val b0_0 = coeffs[0]; val b1_0 = coeffs[1]; val b2_0 = coeffs[2]; val a1_0 = coeffs[3]; val a2_0 = coeffs[4]
        val b0_1 = coeffs[5]; val b1_1 = coeffs[6]; val b2_1 = coeffs[7]; val a1_1 = coeffs[8]; val a2_1 = coeffs[9]
        val b0_2 = coeffs[10]; val b1_2 = coeffs[11]; val b2_2 = coeffs[12]; val a1_2 = coeffs[13]; val a2_2 = coeffs[14]
        val act0 = bandActive[0]
        val act1 = bandActive[1]
        val act2 = bandActive[2]

        var gain = smoothedGain
        var env = drcEnvelope
        val isSurround = channels >= 6

        for (f in 0 until frameCount) {
            var framePeak = 0f

            for (ch in 0 until channels) {
                var s = if (isFloat) {
                    inputBuffer.float
                } else {
                    inputBuffer.short.toFloat() * (1f / 32768f)
                }

                // 5.1 / 7.1 surround dialogue center-channel lift (FL=0, FR=1, FC=2, LFE=3)
                if (useDrc && isSurround) {
                    if (ch == 2) {
                        s *= 1.45f // +3.2 dB dedicated center dialogue boost
                    } else if (ch == 3) {
                        s *= 0.72f // Trim LFE sub rumble to preserve projector speaker headroom
                    }
                }

                // Band 0: 100 Hz Low-Shelf (Bass)
                if (act0) {
                    val idx = ch
                    val y = b0_0 * s + z1[idx]
                    z1[idx] = b1_0 * s - a1_0 * y + z2[idx]
                    z2[idx] = b2_0 * s - a2_0 * y
                    s = y
                }
                // Band 1: 1.8 kHz Peaking EQ (Voice / Dialogue Presence)
                if (act1) {
                    val idx = MAX_CHANNELS + ch
                    val y = b0_1 * s + z1[idx]
                    z1[idx] = b1_1 * s - a1_1 * y + z2[idx]
                    z2[idx] = b2_1 * s - a2_1 * y
                    s = y
                }
                // Band 2: 7.5 kHz High-Shelf (Treble / Air)
                if (act2) {
                    val idx = (2 * MAX_CHANNELS) + ch
                    val y = b0_2 * s + z1[idx]
                    z1[idx] = b1_2 * s - a1_2 * y + z2[idx]
                    z2[idx] = b2_2 * s - a2_2 * y
                    s = y
                }

                frameSamples[ch] = s
                val mag = abs(s)
                if (mag > framePeak) framePeak = mag
            }

            // Kodi-style Dialogue / Night Dynamic Range Compressor (envelope follower)
            val drcMul = if (useDrc) {
                val coeff = if (framePeak > env) 0.018f else 0.00035f
                env += coeff * (framePeak - env)
                when {
                    // Noise gate below -54 dBFS so room silence never hisses
                    env < 0.002f -> 1.0f
                    // Smooth upward lift for quiet whispers (-54 dBFS .. -18 dBFS -> up to +5.2 dB)
                    env < 0.125f -> {
                        val gateRamp = ((env - 0.002f) / 0.014f).coerceIn(0f, 1f)
                        val lift = 1.0f + 0.82f * (1.0f - (env / 0.125f))
                        1.0f + (lift - 1.0f) * gateRamp
                    }
                    // Gentle downward compression above -9 dBFS for loud explosions
                    env > 0.36f -> (0.36f / env).pow(0.42f)
                    else -> 1.0f
                }
            } else {
                1.0f
            }

            // Click-free per-frame master gain smoothing
            gain += 0.0018f * (targetGain - gain)
            val totalGain = gain * drcMul

            for (ch in 0 until channels) {
                val amplified = frameSamples[ch] * totalGain
                val limited = softLimit(amplified)
                if (isFloat) {
                    out.putFloat(limited)
                } else {
                    val pcm16 = (limited * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    out.putShort(pcm16)
                }
            }
        }

        smoothedGain = gain
        drcEnvelope = env
        out.flip()
    }

    /**
     * Transparent below 0.84 (-1.5 dBFS); smooth hyperbolic tangent ceiling at 0.995 full-scale
     * so 200%–300% boost never causes harsh digital clipping on projector speakers.
     */
    private fun softLimit(x: Float): Float {
        if (!x.isFinite()) return 0f
        val ax = abs(x)
        if (ax <= 0.84f) return x
        val excess = (ax - 0.84f) / 0.155f
        val compressed = 0.84f + 0.155f * tanh(excess.toDouble()).toFloat()
        return if (x >= 0f) compressed else -compressed
    }

    override fun onFlush() {
        z1.fill(0f)
        z2.fill(0f)
        drcEnvelope = 0f
    }

    override fun onReset() {
        z1.fill(0f)
        z2.fill(0f)
        drcEnvelope = 0f
        coeffSampleRate = 0
        coeffsDirty = true
    }

    private fun recomputeBiquadCoefficients(sampleRate: Int, cfg: AudioDspConfig) {
        val sr = max(8_000, sampleRate).toDouble()
        computeLowShelf(band = 0, sr = sr, freqHz = 100.0, q = 0.707, gainDb = cfg.bassDb)
        computePeakingEq(band = 1, sr = sr, freqHz = 1800.0, q = 1.0, gainDb = cfg.voiceDb)
        computeHighShelf(band = 2, sr = sr, freqHz = 7500.0, q = 0.707, gainDb = cfg.trebleDb)
    }

    private fun computeLowShelf(band: Int, sr: Double, freqHz: Double, q: Double, gainDb: Int) {
        if (gainDb == 0 || freqHz >= sr * 0.48) {
            setIdentityBand(band)
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sr
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / (2.0 * q)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val b0 = a * ((a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha)
        val b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW)
        val b2 = a * ((a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha)
        val a0 = (a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha
        val a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW)
        val a2 = (a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha
        storeNormalizedCoeffs(band, b0, b1, b2, a0, a1, a2)
    }

    private fun computePeakingEq(band: Int, sr: Double, freqHz: Double, q: Double, gainDb: Int) {
        if (gainDb == 0 || freqHz >= sr * 0.48) {
            setIdentityBand(band)
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sr
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / (2.0 * q)

        val b0 = 1.0 + alpha * a
        val b1 = -2.0 * cosW
        val b2 = 1.0 - alpha * a
        val a0 = 1.0 + alpha / a
        val a1 = -2.0 * cosW
        val a2 = 1.0 - alpha / a
        storeNormalizedCoeffs(band, b0, b1, b2, a0, a1, a2)
    }

    private fun computeHighShelf(band: Int, sr: Double, freqHz: Double, q: Double, gainDb: Int) {
        if (gainDb == 0 || freqHz >= sr * 0.48) {
            setIdentityBand(band)
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freqHz / sr
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / (2.0 * q)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val b0 = a * ((a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha)
        val b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW)
        val b2 = a * ((a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha)
        val a0 = (a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha
        val a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW)
        val a2 = (a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha
        storeNormalizedCoeffs(band, b0, b1, b2, a0, a1, a2)
    }

    private fun setIdentityBand(band: Int) {
        val base = band * 5
        coeffs[base] = 1f
        coeffs[base + 1] = 0f
        coeffs[base + 2] = 0f
        coeffs[base + 3] = 0f
        coeffs[base + 4] = 0f
        bandActive[band] = false
    }

    private fun storeNormalizedCoeffs(
        band: Int,
        b0: Double,
        b1: Double,
        b2: Double,
        a0: Double,
        a1: Double,
        a2: Double
    ) {
        val invA0 = 1.0 / a0
        val base = band * 5
        coeffs[base] = (b0 * invA0).toFloat()
        coeffs[base + 1] = (b1 * invA0).toFloat()
        coeffs[base + 2] = (b2 * invA0).toFloat()
        coeffs[base + 3] = (a1 * invA0).toFloat()
        coeffs[base + 4] = (a2 * invA0).toFloat()
        bandActive[band] = true
    }
}
