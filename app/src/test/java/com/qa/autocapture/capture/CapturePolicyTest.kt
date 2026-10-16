package com.qa.autocapture.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 최종 캡처 시점(FinalTimer)과 축소 이미지 비교(FrameDiff) 검증. */
class CapturePolicyTest {

    private val settle = CaptureConfig.SETTLE_MS
    private val maxWait = CaptureConfig.MAX_WAIT_MS

    // ── FinalTimer ───────────────────────────────────────────────

    @Test
    fun `변화 후 SETTLE_MS 동안 변화가 없으면 최종 1장`() {
        val finals = run(listOf(Change(0)))
        assertEquals(listOf(Final(settle, forced = false)), finals)
    }

    @Test
    fun `변화가 이어지면 마지막 변화부터 SETTLE_MS 뒤에 최종`() {
        val finals = run(listOf(Change(0), Change(400), Change(1200), Change(2000)))
        assertEquals(listOf(Final(2000 + settle, forced = false)), finals)
    }

    @Test
    fun `변화가 계속되면 MAX_WAIT_MS 마다 최종, 멈추면 SETTLE_MS 뒤 최종`() {
        // 자동 롤링 배너: 200ms 마다 변화, 20초에 멈춤
        val busy = (0L..20_000L step 200).map { Change(it) }
        val finals = run(busy)
        assertEquals(
            listOf(
                Final(maxWait, forced = true),
                Final(2 * maxWait, forced = true),
                Final(20_000 + settle, forced = false),
            ),
            finals,
        )
    }

    @Test
    fun `변화가 없으면 최종도 없다`() {
        assertTrue(run(emptyList()).isEmpty())
    }

    @Test
    fun `취소하면 기다리던 최종을 버린다`() {
        val finals = run(listOf(Change(0), Cancel(1000)))
        assertTrue(finals.isEmpty())
    }

    @Test
    fun `취소 후 새 변화는 처음부터 다시 잰다`() {
        val finals = run(listOf(Change(0), Cancel(1000), Change(1200)))
        assertEquals(listOf(Final(1200 + settle, forced = false)), finals)
    }

    @Test
    fun `설정값을 넘기면 그 값으로 판단한다`() {
        val finals = run((0L..5000L step 100).map { Change(it) }, settleMs = 500, maxWaitMs = 3000)
        assertEquals(listOf(Final(3000, forced = true), Final(5000 + 500, forced = false)), finals)
    }

    // ── FrameDiff ────────────────────────────────────────────────

    private val w = 1080
    private val h = 2400
    private val statusBar = 80
    private val navBar = 130

    @Test
    fun `같은 화면은 same`() {
        val a = screen()
        assertTrue(FrameDiff.same(thumb(a), thumb(a.copyOf())))
    }

    @Test
    fun `상태바 시계 변화는 비교 범위 밖이라 same`() {
        val b = screen().also { fill(it, 900, 20, 120, 40, 0xFF000000.toInt()) }
        assertTrue(FrameDiff.same(thumb(screen()), thumb(b)))
    }

    @Test
    fun `스피너 크기 변화는 similar 지만 same 은 아니다`() {
        val b = screen().also { fill(it, 480, 1100, 120, 120, 0xFF24457A.toInt()) }
        val a = thumb(screen())
        val t = thumb(b)
        assertTrue(FrameDiff.similar(a, t))
        assertFalse(FrameDiff.same(a, t))
    }

    @Test
    fun `토스트 크기 변화는 similar 가 아니다`() {
        val b = screen().also { fill(it, 240, 1950, 600, 140, 0xFF303030.toInt()) }
        assertFalse(FrameDiff.similar(thumb(screen()), thumb(b)))
    }

    @Test
    fun `크기가 다르면 similar 가 아니다`() {
        val other = FrameDiff.thumbnail(h, 0, w) { _, _, into -> into.fill(WHITE) }
        assertFalse(FrameDiff.similar(thumb(screen()), other))
    }

    @Test
    fun `칸 평균색은 칸 안 픽셀의 평균이다`() {
        // 15px 칸의 절반을 검정으로 → 평균 약 0x7F
        val px = IntArray(w * 15) { WHITE }
        for (y in 0 until 15) for (x in 0 until 7) px[y * w + x] = 0xFF000000.toInt()
        val t = FrameDiff.thumbnail(w, 0, 15) { y, n, into -> px.copyInto(into, 0, y * w, (y + n) * w) }
        assertEquals(72, t.columns)
        assertEquals(1, t.rows)
        val expected = 255 * 8 / 15
        assertEquals((expected shl 16) or (expected shl 8) or expected, t.cells[0])
        assertEquals(0xFFFFFF, t.cells[1])
    }

    // ── 도우미 ───────────────────────────────────────────────────

    private sealed interface Ev {
        val t: Long
    }

    private data class Change(override val t: Long) : Ev

    private data class Cancel(override val t: Long) : Ev

    private data class Final(val at: Long, val forced: Boolean)

    /** CaptureService 처럼 변화 시 바로 판단하고, Wait 이면 그 시각에 다시 판단한다. */
    private fun run(events: List<Ev>, settleMs: Long = settle, maxWaitMs: Long = maxWait, until: Long = 60_000): List<Final> {
        val timer = FinalTimer()
        val finals = mutableListOf<Final>()
        var check: Long? = null
        var i = 0

        fun evaluate(now: Long) {
            check = null
            when (val d = timer.evaluate(now, settleMs, maxWaitMs)) {
                is FinalTimer.Decision.Wait -> check = d.at.also { assertTrue("재판단 시각은 미래여야 함", it > now) }
                is FinalTimer.Decision.Final -> {
                    finals += Final(now, d.forced)
                    // 강제 최종 뒤에는 변화가 멈출 때의 최종을 위해 바로 다시 판단한다
                    if (d.forced) evaluate(now)
                }
                FinalTimer.Decision.Idle -> Unit
            }
        }

        val queue = events.sortedBy { it.t }
        while (true) {
            val next = queue.getOrNull(i)?.t ?: Long.MAX_VALUE
            val c = check
            if (c != null && c <= next && c <= until) {
                evaluate(c)
                continue
            }
            if (next > until) break
            when (val e = queue[i++]) {
                is Change -> {
                    timer.onChange(e.t)
                    if (check == null) evaluate(e.t)
                }
                is Cancel -> {
                    timer.cancel()
                    check = null
                }
            }
        }
        return finals
    }

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

    private fun thumb(px: IntArray) =
        FrameDiff.thumbnail(w, statusBar, h - navBar) { y, n, into -> px.copyInto(into, 0, y * w, (y + n) * w) }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
