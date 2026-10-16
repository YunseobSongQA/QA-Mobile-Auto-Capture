package com.qa.autocapture.capture.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 스크롤 멈춤 판단(ScrollTimer). */
class ScrollTimerTest {

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
    fun `스크롤하는 동안 이동량이 쌓일 때마다 중간 프레임 1장`() {
        val t = ScrollTimer()
        val step = 800
        fun eval(now: Long) = t.evaluate(now, scrollSettle, scrollMax, step, CaptureConfig.SCROLL_STEP_MS)
        t.onScroll(0, 300)
        assertEquals(ScrollTimer.Decision.Wait(scrollSettle), eval(0))
        t.onScroll(100, 300)
        assertEquals(ScrollTimer.Decision.Wait(100 + scrollSettle), eval(100))
        t.onScroll(200, -300) // 방향과 상관없이 이동량을 센다
        assertEquals(ScrollTimer.Decision.Step, eval(200))
        assertEquals("찍기 전에는 다시 요청하지 않음", ScrollTimer.Decision.Wait(200 + scrollSettle), eval(200))
        t.onStepTaken(250)
        t.onScroll(300, 300)
        assertEquals(ScrollTimer.Decision.Wait(300 + scrollSettle), eval(300))
        assertEquals(ScrollTimer.Decision.Capture, eval(300 + scrollSettle))
        assertFalse(t.active)
    }

    @Test
    fun `이동량을 모르는 스크롤은 SCROLL_STEP_MS 마다 중간 프레임`() {
        val t = ScrollTimer()
        val stepMs = CaptureConfig.SCROLL_STEP_MS
        val steps = mutableListOf<Long>()
        var now = 0L
        while (now <= 1000) {
            t.onScroll(now, null)
            if (t.evaluate(now, scrollSettle, scrollMax, 800, stepMs) == ScrollTimer.Decision.Step) {
                steps += now
                t.onStepTaken(now)
            }
            now += 50
        }
        assertEquals(listOf(stepMs, 2 * stepMs), steps)
    }

    @Test
    fun `중간 프레임을 못 찍으면 다음 판단 때 다시 요청한다`() {
        val t = ScrollTimer()
        t.onScroll(0, 900)
        assertEquals(ScrollTimer.Decision.Step, t.evaluate(0, scrollSettle, scrollMax, 800, CaptureConfig.SCROLL_STEP_MS))
        t.onStepDropped()
        assertEquals(ScrollTimer.Decision.Step, t.evaluate(10, scrollSettle, scrollMax, 800, CaptureConfig.SCROLL_STEP_MS))
    }

    @Test
    fun `취소하면 프레임을 찍지 않는다`() {
        assertTrue(runScroll(listOf(Scroll(0), Scroll(200), CancelScroll(300))).isEmpty())
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
                ScrollTimer.Decision.Step, ScrollTimer.Decision.Idle -> Unit
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
}
