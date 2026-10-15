package com.qa.autocapture.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 이벤트 시퀀스 → 캡처 시점 검증.
 * CaptureService 처럼 "요청 시 바로 판단, Wait 이면 그 시각에 다시 판단" 하는 시뮬레이터로 돌린다.
 */
class StabilizerTest {

    private val settle = CaptureTiming.SETTLE_MS
    private val maxWait = CaptureTiming.MAX_WAIT_MS
    private val toastWait = CaptureTiming.TOAST_WAIT_MS
    private val poll = CaptureTiming.PROGRESS_POLL_MS

    @Test
    fun `화면 진입 후 변화가 없으면 SETTLE_MS 뒤에 찍는다`() {
        val shots = run(listOf(Request(0)))
        assertEquals(listOf(Shot(settle, forced = false)), shots)
    }

    @Test
    fun `내용 변화가 이어지면 마지막 변화부터 SETTLE_MS 를 다시 기다린다`() {
        val shots = run(listOf(Request(0), Activity(300), Activity(800), Activity(1500)))
        assertEquals(listOf(Shot(1500 + settle, forced = false)), shots)
    }

    @Test
    fun `계속 변하는 화면은 MAX_WAIT_MS 에 강제로 찍는다`() {
        // 자동 롤링 배너: 200ms 마다 내용 변경
        val banner = (0L..20_000L step 200).map { Activity(it) }
        val shots = run(listOf(Request(0)) + banner, until = 20_000)
        assertEquals(listOf(Shot(maxWait, forced = true)), shots)
    }

    @Test
    fun `로딩 표시가 사라질 때까지 기다린다`() {
        val calls = intArrayOf(0)
        val shots = run(listOf(Request(0))) { t -> calls[0]++; t < 2600 }
        val shot = shots.single()
        assertEquals(false, shot.forced)
        assertTrue("로딩이 끝난 뒤 한 번의 확인 간격 안에 찍어야 함: ${shot.at}", shot.at in 2600..2600 + poll)
        // 시간 조건을 통과한 뒤에만 노드 트리를 확인한다
        assertTrue(calls[0] <= (shot.at - settle) / poll + 1)
    }

    @Test
    fun `로딩 표시가 계속 보이면 MAX_WAIT_MS 에 강제로 찍는다`() {
        val shots = run(listOf(Request(0))) { true }
        assertEquals(listOf(Shot(maxWait, forced = true)), shots)
    }

    @Test
    fun `내용 변화 중에는 로딩 표시를 확인하지 않는다`() {
        val calls = intArrayOf(0)
        val busy = (0L..3000L step 100).map { Activity(it) }
        run(listOf(Request(0)) + busy) { calls[0]++; false }
        assertEquals(1, calls[0])
    }

    @Test
    fun `토스트가 뜨면 TOAST_WAIT_MS 동안 찍지 않는다`() {
        val shots = run(listOf(Request(0), Toast(500)))
        assertEquals(listOf(Shot(500 + toastWait, forced = false)), shots)
    }

    @Test
    fun `토스트 보류는 내용 안정보다 길면 그만큼 기다린다`() {
        val shots = run(listOf(Toast(0), Request(100), Activity(900)))
        assertEquals(listOf(Shot(toastWait, forced = false)), shots)
    }

    @Test
    fun `최대 대기 시간 직전에 뜬 토스트도 피해서 찍는다`() {
        val busy = (0L..4800L step 200).map { Activity(it) }
        val shots = run(listOf(Request(0)) + busy + Toast(4800))
        assertEquals(listOf(Shot(4800 + toastWait, forced = true)), shots)
    }

    @Test
    fun `토스트가 계속 떠도 MAX_WAIT_MS + TOAST_WAIT_MS 를 넘기지 않는다`() {
        val spam = (0L..20_000L step 1000).map { Toast(it) }
        val shots = run(listOf(Request(0)) + spam, until = 20_000)
        assertEquals(listOf(Shot(maxWait + toastWait, forced = true)), shots)
    }

    @Test
    fun `스크롤이 멈출 때마다 안정화 후 1장씩 찍는다`() {
        val scroll1 = (0L..600L step 50).map { Request(it) }
        val scroll2 = (3000L..3400L step 50).map { Request(it) }
        val shots = run(scroll1 + scroll2)
        assertEquals(listOf(Shot(600 + settle, false), Shot(3400 + settle, false)), shots)
    }

    @Test
    fun `쉬지 않고 스크롤하면 처음 요청부터 MAX_WAIT_MS 마다 강제로 찍는다`() {
        val scroll = (0L..7000L step 100).map { Request(it) }
        val shots = run(scroll)
        // 5000 에 강제 1장, 그 직후 요청부터 다시 기다리다 스크롤이 멈춘 뒤(7000 + settle) 1장
        assertEquals(listOf(Shot(maxWait, true), Shot(7000 + settle, false)), shots)
    }

    @Test
    fun `화면이 바뀌면 기다리던 요청은 버린다`() {
        val shots = run(listOf(Request(0), Cancel(500), Request(600)))
        assertEquals(listOf(Shot(600 + settle, forced = false)), shots)
    }

    @Test
    fun `요청이 없으면 찍지 않는다`() {
        val shots = run(listOf(Activity(0), Toast(100), Activity(2000)))
        assertTrue(shots.isEmpty())
    }

    // ── 시뮬레이터 ───────────────────────────────────────────────

    private sealed interface Ev {
        val t: Long
    }

    private data class Request(override val t: Long) : Ev

    private data class Activity(override val t: Long) : Ev

    private data class Toast(override val t: Long) : Ev

    private data class Cancel(override val t: Long) : Ev

    private data class Shot(val at: Long, val forced: Boolean)

    /** 이벤트를 시간순으로 넣고, 예약된 재판단 시각이 오면 그때 판단한다. */
    private fun run(events: List<Ev>, until: Long = 60_000, progress: (Long) -> Boolean = { false }): List<Shot> {
        val s = Stabilizer()
        val shots = mutableListOf<Shot>()
        val queue = events.sortedBy { it.t }
        var check: Long? = null
        var i = 0

        fun evaluate(now: Long) {
            check = when (val d = s.evaluate(now) { progress(now) }) {
                is Stabilizer.Decision.Wait -> d.at.also { assertTrue("재판단 시각은 미래여야 함", it > now) }
                is Stabilizer.Decision.Capture -> {
                    shots += Shot(now, d.forced)
                    null
                }
                Stabilizer.Decision.Idle -> null
            }
        }

        while (true) {
            val nextEvent = queue.getOrNull(i)?.t ?: Long.MAX_VALUE
            val c = check
            if (c != null && c <= nextEvent && c <= until) {
                evaluate(c)
                continue
            }
            if (nextEvent > until) break
            when (val e = queue[i++]) {
                is Request -> {
                    s.request(e.t)
                    evaluate(e.t)
                }
                is Activity -> s.onActivity(e.t)
                is Toast -> s.onToast(e.t)
                is Cancel -> {
                    s.cancel()
                    check = null
                }
            }
        }
        return shots
    }
}
