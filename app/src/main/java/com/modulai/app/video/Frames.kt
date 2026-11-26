package com.modulai.app.video

import android.graphics.Bitmap
import android.media.Image

internal object Frames {
    fun scale(source: Bitmap, w: Int, h: Int): Bitmap {
        if (source.width == w && source.height == h) return source
        return Bitmap.createScaledBitmap(source, w, h, true).also { if (it !== source) source.recycle() }
    }

    /** The average of two frames, used as the in-between frame when doubling the frame rate. */
    fun blend(a: Bitmap, b: Bitmap): Bitmap {
        val w = a.width
        val h = a.height
        val pa = IntArray(w * h).also { a.getPixels(it, 0, w, 0, 0, w, h) }
        val pb = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        for (i in pa.indices) {
            val x = pa[i]
            val y = pb[i]
            // Per-channel average without unpacking: drop each channel's low bit, then add.
            pa[i] = (0xFF shl 24) or ((((x and 0xFEFEFE) ushr 1) + ((y and 0xFEFEFE) ushr 1)) and 0xFFFFFF)
        }
        return Bitmap.createBitmap(pa, w, h, Bitmap.Config.ARGB_8888)
    }
}

/** ARGB to the encoder's YUV 4:2:0 layout, honouring whatever plane strides it reports. */
internal object Yuv {
    fun write(bitmap: Bitmap, image: Image) {
        val w = image.width
        val h = image.height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        val (yPlane, uPlane, vPlane) = image.planes
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        for (row in 0 until h) {
            for (col in 0 until w) {
                val c = argb[row * w + col]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuf.put(row * yPlane.rowStride + col * yPlane.pixelStride, y.coerceIn(0, 255).toByte())
                if (row % 2 == 0 && col % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val cr = row / 2
                    val cc = col / 2
                    uBuf.put(cr * uPlane.rowStride + cc * uPlane.pixelStride, u.coerceIn(0, 255).toByte())
                    vBuf.put(cr * vPlane.rowStride + cc * vPlane.pixelStride, v.coerceIn(0, 255).toByte())
                }
            }
        }
    }
}
