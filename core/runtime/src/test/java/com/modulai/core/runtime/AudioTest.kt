package com.modulai.core.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioTest {
    private fun sine(hz: Double, rate: Int, seconds: Double, amplitude: Float = 0.5f) =
        Pcm(FloatArray((rate * seconds).toInt()) { (amplitude * sin(2 * PI * hz * it / rate)).toFloat() }, rate)

    /** Zero crossings per second, halved: the frequency of a clean tone. */
    private fun frequency(pcm: Pcm): Double {
        var crossings = 0
        for (i in 1 until pcm.samples.size) if (pcm.samples[i - 1] < 0 != pcm.samples[i] < 0) crossings++
        return crossings / 2.0 / (pcm.samples.size.toDouble() / pcm.sampleRate)
    }

    @Test
    fun wavRoundTripKeepsSamplesAndRate() {
        val pcm = sine(440.0, 22_050, 0.25)
        val back = Wav.decode(Wav.encode(pcm))
        assertEquals(22_050, back.sampleRate)
        assertEquals(pcm.samples.size, back.samples.size)
        for (i in pcm.samples.indices) assertEquals(pcm.samples[i], back.samples[i], 1f / 16_000)
    }

    @Test
    fun wavDecodeMixesStereoAndSkipsUnknownChunks() {
        val buf = ByteBuffer.allocate(12 + 8 + 4 + 8 + 16 + 8 + 8).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(0).put("WAVE".toByteArray())
        buf.put("LIST".toByteArray()).putInt(3).put(byteArrayOf(1, 2, 3, 0)) // odd size is padded
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(8000).putInt(32_000).putShort(4).putShort(16)
        buf.put("data".toByteArray()).putInt(8)
        buf.putShort(16_384).putShort(0).putShort(-16_384).putShort(-16_384)
        val pcm = Wav.decode(buf.array())
        assertEquals(8000, pcm.sampleRate)
        assertArrayEquals(floatArrayOf(0.25f, -0.5f), pcm.samples, 1e-6f)
    }

    @Test
    fun wavDecodeReadsFloatSamples() {
        val buf = ByteBuffer.allocate(12 + 8 + 16 + 8 + 8).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(0).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(3).putShort(1).putInt(16_000).putInt(64_000).putShort(4).putShort(32)
        buf.put("data".toByteArray()).putInt(8).putFloat(0.75f).putFloat(-0.125f)
        assertArrayEquals(floatArrayOf(0.75f, -0.125f), Wav.decode(buf.array()).samples, 0f)
    }

    @Test
    fun wavDecodeRejectsOtherFiles() {
        assertThrows(IllegalArgumentException::class.java) { Wav.decode("ID3 not a wave file at all".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { Wav.decode(ByteArray(3)) }
    }

    @Test
    fun resamplingKeepsPitchLengthAndLevel() {
        val source = sine(440.0, 44_100, 1.0)
        for (rate in intArrayOf(16_000, 24_000, 48_000)) {
            val out = source.resampled(rate)
            assertEquals(rate, out.sampleRate)
            assertEquals(1000.0, out.durationMs.toDouble(), 2.0)
            assertEquals(440.0, frequency(out), 3.0)
            assertEquals(source.rms, out.rms, 0.01f)
        }
    }

    @Test
    fun downsamplingRemovesWhatTheNewRateCannotCarry() {
        // 10 kHz does not fit under the 8 kHz limit of 16 kHz audio; unfiltered it would fold down to 6 kHz.
        val out = sine(10_000.0, 44_100, 0.5).resampled(16_000)
        assertTrue("left ${out.rms}", out.rms < 0.02f)
    }

    @Test
    fun resamplingToTheSameRateIsFree() {
        val pcm = sine(100.0, 16_000, 0.1)
        assertSame(pcm, pcm.resampled(16_000))
    }

    @Test
    fun shortsAreScaledAndMixed() {
        val pcm = Pcm.fromShorts(shortArrayOf(16_384, -16_384, 8192, 8192), 8000, channels = 2)
        assertArrayEquals(floatArrayOf(0f, 0.25f), pcm.samples, 1e-6f)
        assertArrayEquals(shortArrayOf(0, 8192), pcm.toShortArray())
        assertEquals(Short.MAX_VALUE, Pcm(floatArrayOf(3f), 8000).toShortArray()[0])
    }

    @Test
    fun concatInsertsSilenceBetweenParts() {
        val a = Pcm(floatArrayOf(1f, 1f), 1000)
        val joined = Pcm.concat(listOf(a, a), gapMs = 3)
        assertArrayEquals(floatArrayOf(1f, 1f, 0f, 0f, 0f, 1f, 1f), joined.samples, 0f)
        assertThrows(IllegalArgumentException::class.java) { Pcm.concat(listOf(a, Pcm(floatArrayOf(1f), 2000))) }
    }

    @Test
    fun silenceHasNoLevel() {
        assertEquals(0f, Pcm(FloatArray(100), 8000).rms, 0f)
        assertEquals(0f, Pcm(FloatArray(0), 8000).rms, 0f)
        assertTrue(abs(sine(50.0, 8000, 1.0, amplitude = 1f).rms - 0.707f) < 0.01f)
    }

    @Test
    fun textIsSplitAtSentenceEnds() {
        val text = "Первое предложение. Второе, подлиннее! Третье?"
        assertEquals(listOf("Первое предложение.", "Второе, подлиннее!", "Третье?"), splitForSpeech(text, 20))
        assertEquals(listOf(text), splitForSpeech(text, 200))
        assertEquals(listOf("Первое предложение. Второе, подлиннее!", "Третье?"), splitForSpeech(text, 40))
    }

    @Test
    fun longSentencesAreSplitAtWordsAndNothingIsLost() {
        val text = "one two three four five six seven eight nine ten"
        val parts = splitForSpeech(text, 12)
        assertTrue(parts.all { it.length <= 12 })
        assertEquals(text, parts.joinToString(" "))
        assertEquals(listOf("abcde", "fghij", "k"), splitForSpeech("abcdefghijk", 5))
        assertEquals(emptyList<String>(), splitForSpeech("  \n ", 10))
    }
}
