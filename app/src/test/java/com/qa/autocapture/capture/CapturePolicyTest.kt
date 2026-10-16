package com.qa.autocapture.capture

import com.qa.autocapture.capture.SettleCycle.Kind
import com.qa.autocapture.capture.SettleCycle.Shot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 정지 확인(SettleCycle), 스크롤 멈춤(ScrollTimer), 축소 이미지 비교(FrameDiff) 검증. */
class CapturePolicyTest {

    private val settle = CaptureConfig.SETTLE_MS
    private val confirm = CaptureConfig.CONFIRM_MS
    private val maxWait = CaptureConfig.MAX_WAIT_MS
    private val preview = CaptureConfig.ENTRY_PREVIEW_MS

    // ── SettleCycle ──────────────────────────────────────────────

    @Test
    fun `정적인 화면은 미리 1장 확보 뒤 바뀐 게 없으면 더 찍지 않는다`() {
        val r = cycle(Kind.ACTION, preview = true) { loaded }
        assertEquals(listOf("preview@$preview", "nochange@$settle"), r.outcomes)
        assertEquals("버튼을 숨긴 촬영은 미리 확보 1번뿐", 1, r.hiddenShots)
    }

    @Test
    fun `로딩 표시가 돌아가는 동안은 정지로 보지 않고 다 뜬 뒤에 찍는다`() {
        // 이벤트 없이 화면만 바뀜(돌아가는 로딩 표시) → 3초에 내용이 뜸
        val r = cycle(Kind.ACTION, preview = true) { t -> if (t < 3000) spinner(t) else loaded }
        val accept = r.accepted.single()
        assertTrue("다 뜬 뒤 확인 간격 안에 확정: ${accept.at}", accept.at in 3000..3000 + 3 * confirm)
        assertTrue("확정 화면은 다 뜬 화면", accept.cells.contentEquals(loaded))
        assertFalse(accept.forced)
        assertTrue("버튼 숨김 촬영은 미리 확보 + 확정 + 실패 1번 이하: ${r.hiddenShots}", r.hiddenShots <= 3)
    }

    @Test
    fun `이벤트가 이어지는 동안은 찍지 않는다`() {
        val busy = (0L..5000L step 200).toList()
        val r = cycle(Kind.ACTION, preview = true, events = busy) { loaded }
        assertTrue(r.shots.all { it.first > 5000 })
        assertEquals("preview@${5000 + preview}", r.outcomes.first())
    }

    @Test
    fun `끝내 정지하지 않는 화면은 MAX_WAIT_MS 에 그 시점 화면을 찍는다`() {
        val r = cycle(Kind.ACTION) { t -> spinner(t) }
        assertEquals(listOf(maxWait), r.accepted.map { it.at })
        assertTrue(r.accepted.single().forced)
    }

    @Test
    fun `터치했는데 화면이 그대로면 버튼을 숨기지 않고 끝낸다`() {
        val r = cycle(Kind.ACTION, last = loaded) { loaded }
        assertEquals(listOf("nochange@$settle"), r.outcomes)
        assertEquals(0, r.hiddenShots)
    }

    @Test
    fun `터치 뒤 바뀐 화면은 정지를 확인하고 1장`() {
        val r = cycle(Kind.ACTION, last = loaded) { errorShown }
        assertEquals(listOf("accept@${settle + confirm}"), r.outcomes)
        assertEquals(1, r.hiddenShots)
    }

    @Test
    fun `비교 화면에 찍힌 플로팅 버튼은 정지 판단에서 뺀다`() {
        // 버튼 영역을 빼지 않으면 PROBE(버튼 있음)와 FINAL(버튼 없음)이 달라 정지로 보지 않는다
        val r = cycle(Kind.ACTION) { loaded }
        assertEquals(listOf("accept@${settle + confirm}"), r.outcomes)
    }

    @Test
    fun `앱이 스스로 조금 바뀐 것(배너)은 찍지 않는다`() {
        val r = cycle(Kind.CHANGE, last = loaded) { banner }
        assertEquals(listOf("nochange@$settle"), r.outcomes)
    }

    @Test
    fun `앱이 스스로 화면 대부분을 바꾸면 새 화면으로 보고 찍는다`() {
        val r = cycle(Kind.CHANGE, last = loaded) { otherPage }
        assertEquals(listOf("accept@${settle + confirm}"), r.outcomes)
        assertEquals(Kind.CHANGE, r.accepted.single().kind)
    }

    @Test
    fun `앱이 스스로 계속 바뀌면 찍지 않고 끝낸다`() {
        val r = cycle(Kind.CHANGE, last = loaded) { t -> if ((t / 100) % 2 == 0L) otherPage else loaded }
        assertTrue(r.accepted.isEmpty())
    }

    @Test
    fun `촬영 중 이벤트가 오면 그 결과는 버리고 다시 조용해지길 기다린다`() {
        val c = SettleCycle()
        c.start(Kind.ACTION, 0)
        val take = c.evaluate(settle, settle, confirm, maxWait) as SettleCycle.Decision.Take
        c.onActivity(settle + 10)
        val outcome = c.onShot(take.generation, take.shot, thumb(errorShown), null, null, settle + 20, confirm)
        assertEquals(SettleCycle.Outcome.Continue, outcome)
        assertEquals(SettleCycle.Decision.Wait(settle + 10 + settle), c.evaluate(settle + 30, settle, confirm, maxWait))
    }

    // ── ScrollTimer ──────────────────────────────────────────────

    private val scrollSettle = CaptureConfig.SCROLL_SETTLE_MS
    private val scrollMax = CaptureConfig.SCROLL_MAX_WAIT_MS

    @Test
    fun `스크롤이 멈추면 SCROLL_SETTLE_MS 뒤 프레임 1장`() {
        val drag = (0L..600L step 20).map { Scroll(it) }
        assertEquals(listOf(600 + scrollSettle), runScroll(drag))
    }

    @Test
    fun `스크롤이 멈출 때마다 1장씩`() {
        val first = (0L..300L step 20).map { Scroll(it) }
        val second = (2000L..2400L step 20).map { Scroll(it) }
        assertEquals(listOf(300 + scrollSettle, 2400 + scrollSettle), runScroll(first + second))
    }

    @Test
    fun `멈춘 뒤 내용 변경이 잠깐 있으면 그만큼 기다린다`() {
        val events = listOf(Scroll(0), Scroll(300), Activity(500), Activity(700))
        assertEquals(listOf(700 + scrollSettle), runScroll(events))
    }

    @Test
    fun `멈춘 뒤 내용 변경이 계속돼도 SCROLL_MAX_WAIT_MS 를 넘기지 않는다`() {
        val banner = (400L..10_000L step 100).map { Activity(it) }
        assertEquals(listOf(300 + scrollMax), runScroll(listOf(Scroll(0), Scroll(300)) + banner))
    }

    @Test
    fun `스크롤 없이 내용만 바뀌면 이어붙이기 모드가 아니다`() {
        assertTrue(runScroll(listOf(Activity(0), Activity(500))).isEmpty())
    }

    @Test
    fun `취소하면 프레임을 찍지 않는다`() {
        assertTrue(runScroll(listOf(Scroll(0), Scroll(200), CancelScroll(300))).isEmpty())
    }

    // ── FrameDiff ────────────────────────────────────────────────

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

    // ── SettleCycle 시뮬레이터 ───────────────────────────────────

    /** 10x10 칸 화면. 버튼은 오른쪽 위 2x2 칸. */
    private val cols = 10
    private val buttonBox = FrameDiff.Box(80, 0, 100, 20)
    private val loaded = IntArray(cols * cols) { 0x101010 * (it % 7) }
    private val errorShown = loaded.copyOf().also { for (i in 50 until 60) it[i] = 0xC62828 }
    private val banner = loaded.copyOf().also { for (i in 0 until 20) it[20 + i] = 0x00FF00 } // 20% 바뀜
    private val otherPage = IntArray(cols * cols) { 0xFFFFFF - 0x111111 * (it % 5) }

    /** 로딩 표시: 가운데 칸 하나가 100ms 마다 7가지 모양을 돈다(정지 기준 0.2% 를 넘음, 0.5초 간격 두 화면은 늘 다름). */
    private fun spinner(t: Long) = loaded.copyOf().also { it[44] = ((t / 100) % 7).toInt() * 0x242424 }

    private fun thumb(cells: IntArray) = FrameDiff.Thumb(cols, cols, cells, 10, 0)

    /** PROBE 는 버튼이 찍혀 있다. */
    private fun withButton(cells: IntArray) = cells.copyOf().also { for (i in intArrayOf(8, 9, 18, 19)) it[i] = 0x24457A }

    private class Accepted(val at: Long, val cells: IntArray, val forced: Boolean, val kind: Kind)

    private class CycleResult(val outcomes: List<String>, val accepted: List<Accepted>, val shots: List<Pair<Long, Shot>>) {
        val hiddenShots get() = shots.count { it.second != Shot.PROBE }
    }

    /** 서비스처럼 대기 시각마다 다시 판단하고, 찍으라고 하면 그 시각 화면으로 바로 결과를 돌려준다. */
    private fun cycle(
        kind: Kind,
        preview: Boolean = false,
        last: IntArray? = null,
        events: List<Long> = emptyList(),
        until: Long = 20_000,
        screenAt: (Long) -> IntArray,
    ): CycleResult {
        val c = SettleCycle()
        val outcomes = mutableListOf<String>()
        val accepted = mutableListOf<Accepted>()
        val shots = mutableListOf<Pair<Long, Shot>>()
        var lastThumb = last?.let { thumb(it) }
        c.start(kind, 0, preview)
        var now = 0L
        var i = 0
        while (now <= until && c.active) {
            while (i < events.size && events[i] <= now) c.onActivity(events[i++])
            when (val d = c.evaluate(now, settle, confirm, maxWait)) {
                is SettleCycle.Decision.Take -> {
                    shots += now to d.shot
                    val cells = screenAt(now).let { if (d.shot == Shot.PROBE) withButton(it) else it }
                    val mask = if (d.shot == Shot.PROBE) buttonBox else null
                    when (val o = c.onShot(d.generation, d.shot, thumb(cells), mask, lastThumb, now, confirm)) {
                        SettleCycle.Outcome.Preview -> {
                            outcomes += "preview@$now"
                            lastThumb = thumb(cells)
                        }
                        is SettleCycle.Outcome.NoChange -> outcomes += "nochange@$now"
                        is SettleCycle.Outcome.Accept -> {
                            outcomes += "accept@$now"
                            accepted += Accepted(now, cells, o.forced, o.kind)
                        }
                        SettleCycle.Outcome.Continue -> Unit
                    }
                }
                is SettleCycle.Decision.Wait -> {
                    assertTrue("재판단 시각은 미래여야 함", d.at > now)
                    now = minOf(d.at, events.getOrNull(i) ?: Long.MAX_VALUE)
                }
                SettleCycle.Decision.Idle -> now = events.getOrNull(i) ?: (until + 1)
            }
        }
        return CycleResult(outcomes, accepted, shots)
    }

    // ── ScrollTimer 시뮬레이터 ───────────────────────────────────

    private sealed interface Ev {
        val t: Long
    }

    private data class Scroll(override val t: Long) : Ev

    private data class Activity(override val t: Long) : Ev

    private data class CancelScroll(override val t: Long) : Ev

    /** 스크롤 시 바로 판단하고, Wait 이면 그 시각에 다시 판단한다. 프레임을 찍은 시각 목록. */
    private fun runScroll(events: List<Ev>, until: Long = 60_000): List<Long> {
        val timer = ScrollTimer()
        val shots = mutableListOf<Long>()
        var check: Long? = null
        var i = 0

        fun evaluate(now: Long) {
            check = null
            when (val d = timer.evaluate(now, scrollSettle, scrollMax)) {
                is ScrollTimer.Decision.Wait -> check = d.at.also { assertTrue("재판단 시각은 미래여야 함", it > now) }
                ScrollTimer.Decision.Capture -> shots += now
                ScrollTimer.Decision.Idle -> Unit
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
                is Scroll -> {
                    timer.onScroll(e.t)
                    if (check == null) evaluate(e.t)
                }
                is Activity -> timer.onActivity(e.t)
                is CancelScroll -> {
                    timer.cancel()
                    check = null
                }
            }
        }
        return shots
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
