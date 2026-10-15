package com.qa.autocapture.stitch

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs

/**
 * 행 단위로 기록하는 PNG 인코더(RGB 8bit, sRGB).
 *
 * 전체 이미지를 메모리에 올리지 않으므로 아주 긴 결과도 프레임 1~2장분 메모리로 만들 수 있다.
 * [out] 은 닫지 않는다.
 */
class PngWriter(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    level: Int = 6,
) : Closeable {

    private val deflater = Deflater(level)
    private val idat = DeflaterOutputStream(IdatStream(), deflater, BUFFER_SIZE)
    private var prev = ByteArray(width * 3)
    private var cur = ByteArray(width * 3)
    private val sub = ByteArray(width * 3)
    private val up = ByteArray(width * 3)
    private var written = 0

    init {
        require(width > 0 && height > 0) { "잘못된 크기: ${width}x$height" }
        out.write(SIGNATURE)
        val ihdr = ByteArrayOutputStream(13)
        DataOutputStream(ihdr).apply {
            writeInt(width)
            writeInt(height)
            writeByte(8) // bit depth
            writeByte(2) // color type: RGB
            writeByte(0) // compression
            writeByte(0) // filter
            writeByte(0) // interlace 없음
        }
        writeChunk("IHDR", ihdr.toByteArray(), 13)
        writeChunk("sRGB", byteArrayOf(0), 1)
    }

    /** [argb] 의 [offset] 부터 width 개 픽셀을 한 행으로 기록한다(알파는 버림). */
    fun writeRow(argb: IntArray, offset: Int) {
        check(written < height) { "행 수 초과" }
        var j = 0
        for (i in offset until offset + width) {
            val p = argb[i]
            cur[j++] = (p shr 16).toByte()
            cur[j++] = (p shr 8).toByte()
            cur[j++] = p.toByte()
        }
        // 필터: Sub(1)와 Up(2) 중 절댓값 합이 작은 쪽
        var sumSub = 0L
        var sumUp = 0L
        for (i in cur.indices) {
            val left = if (i >= 3) cur[i - 3].toInt() else 0
            val s = (cur[i] - left).toByte()
            val u = (cur[i] - prev[i]).toByte()
            sub[i] = s
            up[i] = u
            sumSub += abs(s.toInt())
            sumUp += abs(u.toInt())
        }
        if (sumSub <= sumUp) {
            idat.write(1)
            idat.write(sub)
        } else {
            idat.write(2)
            idat.write(up)
        }
        val t = prev
        prev = cur
        cur = t
        written++
    }

    override fun close() {
        try {
            check(written == height) { "행 수 부족: $written/$height" }
            idat.close() // 남은 압축 데이터를 IDAT 로 내보낸다
            writeChunk("IEND", ByteArray(0), 0)
            out.flush()
        } finally {
            deflater.end()
        }
    }

    private fun writeChunk(type: String, data: ByteArray, length: Int) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data, 0, length)
        val header = DataOutputStream(out)
        header.writeInt(length)
        out.write(typeBytes)
        out.write(data, 0, length)
        header.writeInt(crc.value.toInt())
    }

    /** 압축된 바이트를 모아 IDAT 청크로 내보낸다. */
    private inner class IdatStream : OutputStream() {
        private val buf = ByteArray(BUFFER_SIZE)
        private var n = 0

        override fun write(b: Int) {
            if (n == buf.size) flushChunk()
            buf[n++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var l = len
            while (l > 0) {
                if (n == buf.size) flushChunk()
                val c = minOf(l, buf.size - n)
                System.arraycopy(b, o, buf, n, c)
                n += c
                o += c
                l -= c
            }
        }

        override fun close() = flushChunk()

        private fun flushChunk() {
            if (n > 0) writeChunk("IDAT", buf, n)
            n = 0
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    }
}

/**
 * 합성 계획의 part 하나를 PNG 로 쓴다.
 *
 * @param load 프레임 번호의 ARGB 픽셀을 주어진 배열(width*frameHeight)에 채운다.
 *             최근 2장은 캐시해 같은 프레임을 반복해서 읽지 않는다.
 */
fun renderPart(part: Stitcher.Part, out: OutputStream, load: (frame: Int, into: IntArray) -> Unit) {
    val w = part.width
    val cacheFrame = intArrayOf(-1, -1)
    val cache = arrayOfNulls<IntArray>(2)
    var victim = 0
    PngWriter(out, w, part.height).use { png ->
        for (slice in part.slices) {
            var idx = cacheFrame.indexOf(slice.frame)
            if (idx < 0) {
                idx = victim
                victim = 1 - victim
                val buf = cache[idx] ?: IntArray(w * part.frameHeight).also { cache[idx] = it }
                load(slice.frame, buf)
                cacheFrame[idx] = slice.frame
            } else {
                victim = 1 - idx
            }
            val px = cache[idx]!!
            for (y in slice.top until slice.bottom) png.writeRow(px, y * w)
        }
    }
}
