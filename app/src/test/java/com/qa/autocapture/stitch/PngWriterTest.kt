package com.qa.autocapture.stitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.ImageIO

class PngWriterTest {

    @Test
    fun `인코딩한 PNG 를 디코드하면 RGB 가 같다`() {
        val w = 37
        val h = 23
        val rnd = Random(1)
        // 무작위 + 평평한 영역 + 세로 반복을 섞어 Sub/Up 필터가 모두 쓰이게 한다
        val px = IntArray(w * h) { i ->
            val y = i / w
            when {
                y < 5 -> rnd.nextInt()
                y < 12 -> 0xFF336699.toInt()
                else -> (i % w) * 0x070503
            }
        }
        val bytes = ByteArrayOutputStream()
        PngWriter(bytes, w, h).use { png -> for (y in 0 until h) png.writeRow(px, y * w) }

        val img = ImageIO.read(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(w, img.width)
        assertEquals(h, img.height)
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals("($x,$y)", px[y * w + x] and 0xFFFFFF, img.getRGB(x, y) and 0xFFFFFF)
        }
    }

    @Test
    fun `큰 이미지도 여러 IDAT 청크로 나뉘어 정상 디코드된다`() {
        val w = 300
        val h = 900
        val rnd = Random(2)
        val row = IntArray(w)
        val bytes = ByteArrayOutputStream()
        val expected = IntArray(w * h)
        PngWriter(bytes, w, h, level = 1).use { png ->
            for (y in 0 until h) {
                for (x in 0 until w) row[x] = rnd.nextInt()
                row.copyInto(expected, y * w)
                png.writeRow(row, 0)
            }
        }
        val img = ImageIO.read(ByteArrayInputStream(bytes.toByteArray()))
        val actual = img.getRGB(0, 0, w, h, null, 0, w)
        for (i in expected.indices) assertEquals(expected[i] and 0xFFFFFF, actual[i] and 0xFFFFFF)
    }

    @Test
    fun `행 수가 모자라면 닫을 때 실패한다`() {
        val png = PngWriter(ByteArrayOutputStream(), 4, 4)
        png.writeRow(IntArray(4), 0)
        assertThrows(IllegalStateException::class.java) { png.close() }
    }
}
