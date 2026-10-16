package com.qa.autocapture.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import java.io.Closeable
import java.io.InputStream

/** 임시 프레임 PNG 를 sRGB ARGB 픽셀로 읽는다(이어붙이기용). 같은 크기면 Bitmap 을 재사용한다. */
class FrameReader : Closeable {
    private var bitmap: Bitmap? = null

    var width = 0
        private set
    var height = 0
        private set

    /** [into] 크기가 맞으면 거기에 채워 돌려주고, 아니면 새 배열을 만든다. 읽기 실패 시 null. */
    fun read(open: () -> InputStream?, into: IntArray?): IntArray? {
        val bmp = decode(open) ?: return null
        width = bmp.width
        height = bmp.height
        val px = if (into != null && into.size == width * height) into else IntArray(width * height)
        bmp.getPixels(px, 0, width, 0, 0, width, height)
        return px
    }

    private fun decode(open: () -> InputStream?): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            inBitmap = bitmap
        }
        val reused = try {
            open()?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: IllegalArgumentException) {
            null // 크기가 달라 재사용 불가
        }
        if (reused != null) return reused.also { bitmap = it }
        bitmap?.recycle()
        bitmap = null
        opts.inBitmap = null
        return open()?.use { BitmapFactory.decodeStream(it, null, opts) }?.also { bitmap = it }
    }

    override fun close() {
        bitmap?.recycle()
        bitmap = null
    }
}
