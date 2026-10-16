package com.qa.autocapture.capture.policy

import com.qa.autocapture.capture.policy.SettleCycle.Kind
import com.qa.autocapture.capture.policy.SettleCycle.Shot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 정적 완성 확인(SettleCycle): 화면을 시각의 함수로 두고 서비스처럼 대기 시각마다 다시 판단하는 시뮬레이터로 검증한다. */
class SettleCycleTest {

    private val settle = CaptureConfig.SETTLE_MS
    private val confirm = CaptureConfig.CONFIRM_MS
    private val maxWait = CaptureConfig.MAX_WAIT_MS
    private val preview = CaptureConfig.ENTRY_PREVIEW_MS
    private val previewMax = CaptureConfig.ENTRY_PREVIEW_MAX_MS
    private val quietMax = settle + CaptureConfig.QUIET_WAIT_EXTRA_MS
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
    fun `이벤트가 잠깐 이어지면 조용해질 때까지 기다린다`() {
        val events = (0L..800L step 200).toList()
        val r = cycle(Kind.ACTION, preview = true, events = events) { loaded }
        assertEquals(listOf("preview@${minOf(800 + preview, previewMax)}", "nochange@${minOf(800 + settle, quietMax)}"), r.outcomes)
    }

    @Test
    fun `이벤트가 계속 오는 바쁜 앱도 SETTLE_MS + QUIET_WAIT_EXTRA_MS 뒤에는 화면 비교로 판단한다`() {
        // 배너·타이머 때문에 화면이 멈춰 있어도 이벤트가 쉬지 않고 온다
        val busy = (0L..20_000L step 100).toList()
        val r = cycle(Kind.ACTION, preview = true, events = busy) { loaded }
        assertEquals(listOf("preview@$previewMax", "nochange@$quietMax"), r.outcomes)
    }

    @Test
    fun `바쁜 앱에서 로딩이 끝나면 다 뜬 화면을 확정한다`() {
        val busy = (0L..20_000L step 100).toList()
        val r = cycle(Kind.ACTION, preview = true, events = busy) { t -> if (t < 3000) spinner(t) else loaded }
        val accept = r.accepted.single()
        assertTrue("다 뜬 뒤 확인 간격 안에 확정: ${accept.at}", accept.at in 3000..3000 + 3 * confirm)
        assertTrue(accept.cells.contentEquals(loaded))
        assertFalse(accept.forced)
    }

    @Test
    fun `바쁜 앱에서 끝내 정지하지 않아도 MAX_WAIT_MS 에 찍은 화면을 버리지 않는다`() {
        val busy = (0L..20_000L step 50).toList()
        val r = cycle(Kind.ACTION, events = busy) { t -> spinner(t) }
        assertEquals(listOf(maxWait), r.accepted.map { it.at })
        assertTrue(r.accepted.single().forced)
        assertEquals("강제 촬영은 1번만", 1, r.shots.count { it.first >= maxWait })
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
        assertEquals(SettleCycle.Decision.Wait(minOf(settle + 10 + settle, quietMax)), c.evaluate(settle + 30, settle, confirm, maxWait))
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
}
