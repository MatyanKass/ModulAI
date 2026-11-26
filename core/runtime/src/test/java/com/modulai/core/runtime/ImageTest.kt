package com.modulai.core.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageTest {
    @Test
    fun argbRoundTrip() {
        val pixels = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF102030.toInt())
        val image = ImageData.fromArgb(2, 2, pixels)
        assertArrayEquals(
            byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, 0x10, 0x20, 0x30),
            image.rgb,
        )
        assertArrayEquals(pixels, image.toArgb())
    }

    @Test
    fun transparencyBecomesWhite() {
        val image = ImageData.fromArgb(2, 1, intArrayOf(0x00123456, 0x80000000.toInt()))
        assertArrayEquals(byteArrayOf(-1, -1, -1, 127, 127, 127), image.rgb)
    }

    @Test
    fun bufferMustMatchSize() {
        assertThrows(IllegalArgumentException::class.java) { ImageData(2, 2, ByteArray(11)) }
        assertThrows(IllegalArgumentException::class.java) { ImageData(0, 2, ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { ImageData.fromArgb(2, 2, IntArray(3)) }
    }

    @Test
    fun requestValidation() {
        assertNull(ImageRequest("cat").problem())
        assertNull(ImageRequest("cat", width = 768, height = 512, steps = 1, cfgScale = 0f).problem())
        assertNotNull(ImageRequest("cat", width = 500).problem())
        assertNotNull(ImageRequest("cat", height = 64).problem())
        assertNotNull(ImageRequest("cat", width = 4096).problem())
        assertNotNull(ImageRequest("cat", steps = 0).problem())
        assertNotNull(ImageRequest("cat", cfgScale = Float.NaN).problem())
        assertNotNull(ImageRequest("cat", strength = 1.5f).problem())
    }

    @Test
    fun samplerNamesAreUnique() {
        val names = ImageSampler.entries.map { it.wire }
        assertEquals(names.size, names.toSet().size)
        assertEquals("", ImageSampler.AUTO.wire)
    }
}
