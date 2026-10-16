package com.qa.autocapture.capture.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 비교용 축소 이미지(FrameDiff). */
class FrameDiffTest {

    private val w = 1080
    private val h = 2400
    private val statusBar = 80
    private val navBar = 130

    @Test
    fun `같은 화면은 same`() {
        val a = screen()
        assertTrue(FrameDiff.same(thumbOf(a), thumbOf(a.copyOf())))
    }

    @Test
    fun `상태바 시계 변화는 비교 범위 밖이라 same`() {
        val b = screen().also { fill(it, 900, 20, 120, 40, 0xFF000000.toInt()) }
        assertTrue(FrameDiff.same(thumbOf(screen()), thumbOf(b)))
    }

    @Test
    fun `깜빡이는 커서는 정지로 본다`() {
        val b = screen().also { fill(it, 300, 1000, 4, 50, 0xFF000000.toInt()) }
        assertTrue(FrameDiff.static(thumbOf(screen()), thumbOf(b)))
    }

    @Test
    fun `돌아가는 로딩 표시는 정지가 아니지만 작은 변화다`() {
        val b = screen().also { fill(it, 480, 1100, 120, 120, 0xFF24457A.toInt()) }
        val a = thumbOf(screen())
        val t = thumbOf(b)
        assertFalse(FrameDiff.static(a, t))
        assertTrue(FrameDiff.similar(a, t))
    }

    @Test
    fun `오류 문구 한 줄은 작은 변화가 아니다`() {
        val b = screen().also { fill(it, 48, 1500, 900, 48, 0xFFC62828.toInt()) }
        assertFalse(FrameDiff.similar(thumbOf(screen()), thumbOf(b)))
    }

    @Test
    fun `빼라고 한 영역(플로팅 버튼)의 변화는 세지 않는다`() {
        val withButton = screen().also { fill(it, 900, 800, 150, 120, 0xFF24457A.toInt()) }
        val box = FrameDiff.Box(900, 800, 1050, 920)
        assertFalse(FrameDiff.static(thumbOf(screen()), thumbOf(withButton)))
        assertTrue(FrameDiff.static(thumbOf(screen()), thumbOf(withButton), box))
    }

    @Test
    fun `크기가 다르면 전부 바뀐 것으로 본다`() {
        val other = FrameDiff.thumbnail(h, 0, w) { _, _, into -> into.fill(WHITE) }
        assertEquals(1.0, FrameDiff.changeRatio(thumbOf(screen()), other), 0.0)
    }

    @Test
    fun `칸 평균색은 칸 안 픽셀의 평균이다`() {
        // 15px 칸의 절반을 검정으로 → 평균 약 0x88
        val px = IntArray(w * 15) { WHITE }
        for (y in 0 until 15) for (x in 0 until 7) px[y * w + x] = 0xFF000000.toInt()
        val t = FrameDiff.thumbnail(w, 0, 15) { y, n, into -> px.copyInto(into, 0, y * w, (y + n) * w) }
        assertEquals(72, t.columns)
        assertEquals(1, t.rows)
        val expected = 255 * 8 / 15
        assertEquals((expected shl 16) or (expected shl 8) or expected, t.cells[0])
        assertEquals(0xFFFFFF, t.cells[1])
    }

    // ── FrameDiff 도우미 ─────────────────────────────────────────

    /** 흰 바탕 + 상태바(어두운 띠) + 목록 무늬. */
    private fun screen(): IntArray {
        val px = IntArray(w * h) { WHITE }
        fill(px, 0, 0, w, statusBar, 0xFF202020.toInt())
        for (row in 0 until 20) fill(px, 48, 200 + row * 100, 600, 24, 0xFF555555.toInt())
        return px
    }

    private fun fill(px: IntArray, x: Int, y: Int, fw: Int, fh: Int, color: Int) {
        for (yy in y until y + fh) for (xx in x until x + fw) px[yy * w + xx] = color
    }

    private fun thumbOf(px: IntArray) =
        FrameDiff.thumbnail(w, statusBar, h - navBar) { y, n, into -> px.copyInto(into, 0, y * w, (y + n) * w) }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
