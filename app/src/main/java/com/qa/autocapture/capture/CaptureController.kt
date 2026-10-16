package com.qa.autocapture.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.SystemClock
import com.qa.autocapture.capture.ScreenshotTaker.Purpose
import com.qa.autocapture.capture.policy.CaptureConfig
import com.qa.autocapture.capture.policy.FrameDiff
import com.qa.autocapture.capture.policy.ScrollTimer
import com.qa.autocapture.capture.policy.SettleCycle
import com.qa.autocapture.overlay.FloatingButton
import com.qa.autocapture.session.SessionRecorder
import com.qa.autocapture.storage.Options

/**
 * 찍을 때 판단. 기록 중인 화면의 이벤트를 받아 언제 무엇을 찍을지 정하고, 확정한 화면을 SessionRecorder 에 넘긴다.
 * 메인 스레드 전용.
 *
 * - 찍는 때(중요한 때만): 화면 진입, 터치·길게 누르기·입력, 스크롤 멈춤.
 *   앱이 스스로 바뀐 것(배너, 시계, 애니메이션)은 화면 대부분이 바뀌었을 때만.
 * - 찍는 화면: 정적으로 완성된 화면(SettleCycle). 스크롤은 멈출 때마다 1장(ScrollTimer)을 같은 상태에 덧붙인다.
 * - 화면 상태: 작은 변화는 그 상태 이미지를 최신 화면으로 바꾸고, 뚜렷이 바뀌면 새 이미지([deliver]).
 * 판단 규칙은 DESIGN.md 3장.
 */
class CaptureController(
    private val handler: Handler,
    overlay: FloatingButton,
    service: AccessibilityService,
    private val options: Options,
    private val debug: (tag: String, message: String) -> Unit,
) : ScreenshotTaker.Callback {

    private val taker = ScreenshotTaker(service, handler, overlay, this)
    private val cycle = SettleCycle()
    private val scrollTimer = ScrollTimer()

    private var recorder: SessionRecorder? = null

    /** 기록 중인 화면. */
    var current: Screen? = null
        private set

    /** 이 화면 방문에서 마지막으로 확보한 화면(비교 기준)과, 그것이 진입 직후 미리 확보한 임시 화면인지. */
    private var lastThumb: FrameDiff.Thumb? = null
    private var lastIsPreview = false

    /** 마지막으로 확보한 화면 이후 세로 스크롤 이동량(scrollDeltaY 합). 0 이 아닌 값이 없으면 모르는 것으로 본다. */
    private var scrollDy = 0
    private var scrollDyKnown = false

    private var cycleCheckPending = false
    private var scrollCheckPending = false
    private var changeCooldownUntil = 0L

    private val cycleCheck = Runnable {
        cycleCheckPending = false
        checkCycle()
    }
    private val scrollCheck = Runnable {
        scrollCheckPending = false
        checkScroll()
    }

    // ── 세션 ────────────────────────────────────────────────────

    fun start(recorder: SessionRecorder, topInset: Int, bottomInset: Int) {
        this.recorder = recorder
        current = null
        taker.setInsets(topInset, bottomInset)
    }

    fun stop() {
        cancelPending()
        recorder = null
        current = null
        lastThumb = null
        lastIsPreview = false
    }

    fun shutdown() {
        stop()
        taker.shutdown()
    }

    // ── 이벤트 ──────────────────────────────────────────────────

    /** 화면 진입(또는 다른 화면에서 돌아옴). 새 방문을 열고 완성되면 1장. */
    fun switchTo(screen: Screen) {
        val rec = recorder ?: return
        current = screen
        stopCycle()
        resetScroll()
        lastThumb = null
        lastIsPreview = false
        rec.enterScreen(screen.packageName, screen.className)
        startCycle(SettleCycle.Kind.ACTION, preview = true)
    }

    /** 터치·입력: 화면이 바뀌었으면 완성된 뒤 찍는다. */
    fun onAction() {
        if (recorder == null || current == null) return
        if (scrollTimer.active) {
            scrollTimer.onActivity(SystemClock.uptimeMillis())
            return
        }
        startCycle(SettleCycle.Kind.ACTION)
    }

    /** 내용 변경: 확인 중이면 아직 바뀌는 중이라는 뜻, 아니면 앱이 스스로 바뀐 것(큰 변화일 때만 찍음). */
    fun onContentChanged() {
        if (recorder == null || current == null) return
        val now = SystemClock.uptimeMillis()
        when {
            scrollTimer.active -> scrollTimer.onActivity(now)
            cycle.active -> {
                cycle.onActivity(now)
                // 대기 중인 판단이 있으면 그때 마지막 변화 시각으로 다시 잰다(이벤트마다 타이머를 다시 걸지 않음)
                if (!cycleCheckPending) checkCycle()
            }
            now >= changeCooldownUntil -> startCycle(SettleCycle.Kind.CHANGE)
        }
    }

    /** 스크롤 이벤트. [screen] = 스크롤한 창의 화면. */
    fun onScroll(screen: Screen, dx: Int, dy: Int) {
        if (recorder == null) return
        // 다이얼로그가 닫히고 원래 화면을 스크롤하면 그 화면으로 돌아간다
        if (screen != current) switchTo(screen)
        if (dy == 0 && dx != 0) {
            onContentChanged() // 가로로만 스크롤(배너·탭 넘기기)은 이어붙일 수 없으므로 화면 변화로 본다
            return
        }
        if (dy != 0) {
            scrollDy += dy
            scrollDyKnown = true
        }
        if (!scrollTimer.active) {
            // 스크롤 중에는 정지 확인을 멈추고 멈출 때까지 기다린다
            debug("#SCROLL", "start")
            stopCycle()
        }
        scrollTimer.onScroll(SystemClock.uptimeMillis())
        if (!scrollCheckPending) checkScroll()
    }

    fun onToast(pkg: String, text: String) {
        val rec = recorder ?: return
        rec.logToast(System.currentTimeMillis(), pkg, text, current?.className)
        onContentChanged()
    }

    /** 사용자가 홈·이 앱으로 떠났다. 기다리던 촬영을 모두 버린다(떠난 뒤 화면이 찍히지 않게). */
    fun cancelPending() {
        stopCycle()
        resetScroll()
        taker.cancel()
    }

    // ── 언제 찍을지 ──────────────────────────────────────────────

    private fun startCycle(kind: SettleCycle.Kind, preview: Boolean = false) {
        cycle.start(kind, SystemClock.uptimeMillis(), preview)
        checkCycle()
    }

    private fun stopCycle() {
        cycle.cancel()
        handler.removeCallbacks(cycleCheck)
        cycleCheckPending = false
        if (taker.wanted?.purpose != Purpose.SCROLL) taker.drop()
    }

    private fun resetScroll() {
        scrollTimer.cancel()
        handler.removeCallbacks(scrollCheck)
        scrollCheckPending = false
        taker.drop(Purpose.SCROLL)
        scrollDy = 0
        scrollDyKnown = false
    }

    private fun checkCycle() {
        handler.removeCallbacks(cycleCheck)
        cycleCheckPending = false
        if (recorder == null) return
        val now = SystemClock.uptimeMillis()
        when (val d = cycle.evaluate(now, options.settleMs, CaptureConfig.CONFIRM_MS, options.maxWaitMs)) {
            is SettleCycle.Decision.Wait -> {
                cycleCheckPending = true
                handler.postAtTime(cycleCheck, d.at)
            }
            is SettleCycle.Decision.Take -> {
                val purpose = when (d.shot) {
                    SettleCycle.Shot.PREVIEW -> Purpose.PREVIEW
                    SettleCycle.Shot.PROBE -> Purpose.PROBE
                    SettleCycle.Shot.FINAL -> Purpose.FINAL
                }
                want(ScreenshotTaker.Request(purpose, d.generation))
            }
            SettleCycle.Decision.Idle -> Unit
        }
    }

    /** 스크롤이 멈췄는지 판단한다. 멈췄으면 이어붙일 프레임 1장을 요청한다. */
    private fun checkScroll() {
        if (recorder == null) return
        when (val d = scrollTimer.evaluate(SystemClock.uptimeMillis(), CaptureConfig.SCROLL_SETTLE_MS, CaptureConfig.SCROLL_MAX_WAIT_MS)) {
            is ScrollTimer.Decision.Wait -> {
                scrollCheckPending = true
                handler.postAtTime(scrollCheck, d.at)
            }
            ScrollTimer.Decision.Capture -> {
                debug("#SCROLL", "stop dy=${if (scrollDyKnown) scrollDy else "?"}")
                want(ScreenshotTaker.Request(Purpose.SCROLL, 0))
            }
            ScrollTimer.Decision.Idle -> Unit
        }
    }

    private fun want(request: ScreenshotTaker.Request) {
        if (taker.wanted?.purpose == Purpose.SCROLL && request.purpose != Purpose.SCROLL) {
            // 스크롤 프레임이 먼저다. 정지 확인은 조금 뒤 다시 판단한다
            cycle.onShotFailed(request.generation, SystemClock.uptimeMillis(), CaptureConfig.CONFIRM_MS)
            checkCycle()
            return
        }
        taker.want(request)
    }

    // ── 촬영 결과 (ScreenshotTaker.Callback) ─────────────────────

    override fun isBusy(): Boolean = recorder?.busy == true

    override fun onBeforeShot(request: ScreenshotTaker.Request): Boolean {
        val rec = recorder ?: return false
        // 스크롤이 다시 시작됐으면 멈출 때 다시 요청된다
        if (request.purpose == Purpose.SCROLL && scrollTimer.active) return false
        request.recorder = rec
        request.segment = rec.currentSegment
        if (request.purpose == Purpose.SCROLL) {
            request.scrollDy = if (scrollDyKnown) scrollDy else null
            scrollDy = 0
            scrollDyKnown = false
        }
        debug("#SHOT", request.purpose.name.lowercase())
        return true
    }

    override fun onShotFailed(request: ScreenshotTaker.Request, retrying: Boolean) {
        request.scrollDy?.let {
            scrollDy += it
            scrollDyKnown = true
        }
        debug("#SHOT_FAIL", if (retrying) "retry" else request.purpose.name.lowercase())
        if (!retrying && request.recorder === recorder && request.purpose != Purpose.SCROLL) {
            cycle.onShotFailed(request.generation, SystemClock.uptimeMillis(), CaptureConfig.CONFIRM_MS)
            checkCycle()
        }
    }

    override fun onAnalyzed(request: ScreenshotTaker.Request, thumb: FrameDiff.Thumb?, bitmap: Bitmap?, mask: FrameDiff.Box?) {
        val rec = recorder
        if (rec == null || request.recorder !== rec || request.segment != rec.currentSegment || thumb == null) {
            bitmap?.recycle()
            if (rec != null && thumb == null && request.purpose != Purpose.SCROLL) {
                cycle.onShotFailed(request.generation, SystemClock.uptimeMillis(), CaptureConfig.CONFIRM_MS)
                checkCycle()
            }
            return
        }
        val now = SystemClock.uptimeMillis()
        if (request.purpose == Purpose.SCROLL) {
            rec.addFrame(bitmap ?: return, SessionRecorder.Op.SCROLL, request.scrollDy)
            lastThumb = thumb
            lastIsPreview = false
            debug("#FRAME", "scroll dy=${request.scrollDy ?: "?"}")
            // 남은 로딩(이미지 등)이 끝나면 방금 프레임을 완성된 화면으로 바꾼다
            startCycle(SettleCycle.Kind.COMPLETION)
            return
        }
        val shot = when (request.purpose) {
            Purpose.PREVIEW -> SettleCycle.Shot.PREVIEW
            Purpose.PROBE -> SettleCycle.Shot.PROBE
            else -> SettleCycle.Shot.FINAL
        }
        when (val o = cycle.onShot(request.generation, shot, thumb, mask, lastThumb, now, CaptureConfig.CONFIRM_MS)) {
            SettleCycle.Outcome.Preview -> {
                if (bitmap != null && lastThumb == null) {
                    rec.addFrame(bitmap, SessionRecorder.Op.NEW, null)
                    lastThumb = thumb
                    lastIsPreview = true
                    debug("#FRAME", "preview")
                } else {
                    bitmap?.recycle()
                }
            }
            is SettleCycle.Outcome.Accept -> deliver(rec, thumb, bitmap, o.kind, o.forced)
            is SettleCycle.Outcome.NoChange -> {
                bitmap?.recycle()
                if (o.kind == SettleCycle.Kind.CHANGE) changeCooldownUntil = now + CaptureConfig.CHANGE_COOLDOWN_MS
                debug("#NOCHANGE", o.kind.name.lowercase())
            }
            SettleCycle.Outcome.Continue -> bitmap?.recycle()
        }
        checkCycle()
    }

    /** 확정한 화면을 화면 상태에 넣는다: 작은 변화면 마지막 화면을 바꾸고, 뚜렷이 바뀌면 새 이미지. */
    private fun deliver(rec: SessionRecorder, thumb: FrameDiff.Thumb, bitmap: Bitmap?, kind: SettleCycle.Kind, forced: Boolean) {
        if (bitmap == null) return
        val last = lastThumb
        val op = when {
            last == null -> SessionRecorder.Op.NEW
            lastIsPreview -> SessionRecorder.Op.REPLACE // 진입 직후 임시 화면은 완성된 화면으로 바꾼다
            kind == SettleCycle.Kind.CHANGE -> SessionRecorder.Op.NEW
            kind == SettleCycle.Kind.COMPLETION ->
                if (FrameDiff.changeRatio(last, thumb) < CaptureConfig.PAGE_CHANGE_RATIO) SessionRecorder.Op.REPLACE else SessionRecorder.Op.NEW
            FrameDiff.similar(last, thumb) -> SessionRecorder.Op.REPLACE
            else -> SessionRecorder.Op.NEW
        }
        if (op == SessionRecorder.Op.REPLACE && last != null && FrameDiff.same(last, thumb)) {
            bitmap.recycle() // 똑같으면 바꿀 필요 없음
            lastIsPreview = false
            return
        }
        debug("#FRAME", "${op.name.lowercase()} ${kind.name.lowercase()}${if (forced) " forced" else ""}")
        rec.addFrame(bitmap, op, null)
        lastThumb = thumb
        lastIsPreview = false
        if (op == SessionRecorder.Op.NEW) {
            scrollDy = 0
            scrollDyKnown = false
        }
    }
}
