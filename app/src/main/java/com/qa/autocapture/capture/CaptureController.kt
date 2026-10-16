package com.qa.autocapture.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.SystemClock
import com.qa.autocapture.capture.ScreenshotTaker.Kind
import com.qa.autocapture.overlay.FloatingButton
import com.qa.autocapture.session.SessionRecorder
import com.qa.autocapture.storage.Options
import kotlin.math.abs

/**
 * 찍을 때 판단(첫 버전 방식). 기록 중인 화면의 이벤트를 받아 찍고, 찍은 화면을 SessionRecorder 에 넘긴다.
 * 메인 스레드 전용.
 *
 * - 화면 진입: 창 이벤트가 ENTRY_SETTLE_MS(0.5초) 동안 잠잠해지면 1장.
 *   토스트가 떠 있거나 로딩 표시가 돌면 사라질 때까지 미룬다(깔끔한 사진, 최대 ENTRY_MAX_WAIT_MS).
 * - 스크롤: 멈춘 지 SCROLL_DEBOUNCE_MS(0.3초) 뒤 1장. 멈추지 않고 길게 스크롤하면 화면 높이 절반마다 1장(겹침 유지).
 *   스크롤하는 동안 플로팅 버튼은 숨겨 둔다(깜빡임 없음).
 * - 같은 화면 방문에서 찍은 사진은 화면을 떠날 때 겹치는 부분끼리 이어붙여 1장이 된다(SessionRecorder → GroupSaver).
 */
class CaptureController(
    private val handler: Handler,
    private val overlay: FloatingButton,
    service: AccessibilityService,
    private val options: Options,
    /** 앞 화면에 돌아가는 로딩 표시가 있는가(ScreenTracker.hasLoadingIndicator). */
    private val isLoading: () -> Boolean,
    private val debug: (tag: String, message: String) -> Unit,
) : ScreenshotTaker.Callback {

    private val taker = ScreenshotTaker(service, handler, overlay, this)
    private val scrollStepPx = (service.resources.displayMetrics.heightPixels * CaptureConfig.SCROLL_STEP_RATIO).toInt()

    private var recorder: SessionRecorder? = null

    /** 기록 중인 화면. */
    var current: Screen? = null
        private set

    // 화면 진입 사진
    private var entryPending = false
    private var entryDeadline = 0L

    // 스크롤
    private var scrolling = false
    private var scrollAccum = 0
    private var lastStepAt = 0L

    /** 마지막으로 찍은 사진 이후 세로 스크롤 이동량(scrollDeltaY 합). 이동량을 모르는 이벤트가 섞이면 모르는 것으로 본다. */
    private var scrollDy = 0
    private var scrollDyKnown = true

    // 토스트
    private var toastShownAt = 0L
    private var toastUntil = 0L

    private val entryCapture = Runnable { tryEntryCapture() }
    private val scrollStopped = Runnable {
        scrolling = false
        scrollAccum = 0
        debug("#SCROLL", "stop")
        request(Kind.SCROLL_STOP)
    }

    // ── 세션 ────────────────────────────────────────────────────

    fun start(recorder: SessionRecorder) {
        this.recorder = recorder
        current = null
    }

    fun stop() {
        cancelPending()
        recorder = null
        current = null
    }

    fun shutdown() {
        stop()
        taker.shutdown()
    }

    // ── 이벤트 ──────────────────────────────────────────────────

    /** 창 전환(화면 진입이거나 같은 화면의 창 변경). 창 이벤트가 잠잠해지면 1장. */
    fun onWindowState(screen: Screen) {
        if (recorder == null) return
        switchTo(screen)
        scheduleEntry()
    }

    /**
     * 스크롤 이벤트. [screen] = 스크롤한 창의 화면, [dx]·[dy] = scrollDeltaX/Y(모르면 -1).
     * 가로로만 스크롤(배너·탭 넘기기)은 이어붙일 수 없으므로 무시한다.
     */
    fun onScroll(screen: Screen, dx: Int, dy: Int) {
        if (recorder == null) return
        val unknown = dy == UNDEFINED_DELTA
        if (!unknown && dy == 0 && dx != 0) return
        // 다이얼로그가 닫히고 원래 화면을 스크롤하면 그 화면으로 돌아간다
        switchTo(screen)
        val now = SystemClock.uptimeMillis()
        if (!scrolling) {
            scrolling = true
            lastStepAt = now
            overlay.hide(FloatingButton.Hide.SCROLL)
            debug("#SCROLL", "start")
        }
        if (unknown) {
            scrollDyKnown = false
            if (now - lastStepAt >= CaptureConfig.SCROLL_STEP_MS) step(now)
        } else {
            scrollDy += dy
            scrollAccum += abs(dy)
            if (scrollAccum >= scrollStepPx) step(now)
        }
        handler.removeCallbacks(scrollStopped)
        handler.postDelayed(scrollStopped, CaptureConfig.SCROLL_DEBOUNCE_MS)
    }

    /** 토스트: 문구는 기록하고, 떠 있는 동안(TOAST_VISIBLE_MS)은 화면 진입 사진을 미룬다. */
    fun onToast(pkg: String, text: String) {
        val rec = recorder ?: return
        rec.logToast(System.currentTimeMillis(), pkg, text, current?.className)
        val now = SystemClock.uptimeMillis()
        toastShownAt = now
        toastUntil = now + CaptureConfig.TOAST_VISIBLE_MS
    }

    /** 사용자가 홈·이 앱으로 떠났거나 화면이 바뀌었다. 기다리던 촬영을 모두 버린다. */
    fun cancelPending() {
        handler.removeCallbacks(entryCapture)
        handler.removeCallbacks(scrollStopped)
        entryPending = false
        if (scrolling) {
            scrolling = false
            overlay.show(FloatingButton.Hide.SCROLL)
        }
        scrollAccum = 0
        taker.cancel()
    }

    // ── 언제 찍을지 ──────────────────────────────────────────────

    private fun switchTo(screen: Screen) {
        if (screen == current) return
        current = screen
        cancelPending()
        scrollDy = 0
        scrollDyKnown = true
        recorder?.enterScreen(screen.packageName, screen.className)
    }

    /** 창 이벤트가 잠잠해지면 진입 사진. 창 이벤트가 계속 와도 ENTRY_MAX_WAIT_MS 를 넘기지 않는다. */
    private fun scheduleEntry() {
        val now = SystemClock.uptimeMillis()
        if (!entryPending) {
            entryPending = true
            entryDeadline = now + CaptureConfig.ENTRY_MAX_WAIT_MS
        }
        handler.removeCallbacks(entryCapture)
        handler.postAtTime(entryCapture, minOf(now + options.settleMs, entryDeadline))
    }

    /** 진입 사진: 토스트가 떠 있거나 로딩 표시가 돌면 사라질 때까지 미룬다(최대 entryDeadline). */
    private fun tryEntryCapture() {
        val now = SystemClock.uptimeMillis()
        if (now < entryDeadline) {
            if (now < toastUntil) {
                debug("#WAIT", "toast")
                handler.postAtTime(entryCapture, minOf(toastUntil, entryDeadline))
                return
            }
            if (isLoading()) {
                debug("#WAIT", "loading")
                handler.postAtTime(entryCapture, minOf(now + CaptureConfig.LOADING_RECHECK_MS, entryDeadline))
                return
            }
        }
        entryPending = false
        request(Kind.ENTRY)
    }

    /** 길게 스크롤하는 중 1장(프레임끼리 겹치게). */
    private fun step(now: Long) {
        scrollAccum = 0
        lastStepAt = now
        debug("#SCROLL", "step")
        request(Kind.SCROLL_STEP)
    }

    private fun request(kind: Kind) {
        if (recorder == null || current == null) return
        taker.want(ScreenshotTaker.Request(kind))
    }

    /** [at] 에 찍은 화면에 토스트가 들어 있을 수 있는가. */
    private fun toastVisibleAt(at: Long) =
        toastShownAt != 0L && at >= toastShownAt - CaptureConfig.TOAST_EVENT_SLACK_MS && at < toastUntil

    // ── 촬영 결과 (ScreenshotTaker.Callback) ─────────────────────

    override fun isBusy(): Boolean = recorder?.busy == true

    override fun onBeforeShot(request: ScreenshotTaker.Request): Boolean {
        val rec = recorder ?: return false
        request.recorder = rec
        request.segment = rec.currentSegment
        request.takenAt = SystemClock.uptimeMillis()
        request.scrollDy = if (scrollDyKnown) scrollDy else null
        scrollDy = 0
        scrollDyKnown = true
        debug("#SHOT", request.kind.name.lowercase())
        return true
    }

    override fun onShotFailed(request: ScreenshotTaker.Request, retrying: Boolean) {
        // 못 찍은 사진의 이동량은 다음 사진으로 넘긴다
        val dy = request.scrollDy
        if (dy == null) scrollDyKnown = false else scrollDy += dy
        debug("#SHOT_FAIL", if (retrying) "retry" else request.kind.name.lowercase())
        if (!retrying && request.kind == Kind.SCROLL_STOP && !scrolling) overlay.show(FloatingButton.Hide.SCROLL)
    }

    override fun onCaptured(request: ScreenshotTaker.Request, bitmap: Bitmap?) {
        // 스크롤이 멈춘 사진을 찍었으면 버튼을 다시 보인다(그새 다시 스크롤하면 그대로 숨김)
        if (request.kind == Kind.SCROLL_STOP && !scrolling) overlay.show(FloatingButton.Hide.SCROLL)
        val rec = recorder
        if (bitmap == null || rec == null || request.recorder !== rec || request.segment != rec.currentSegment) {
            bitmap?.recycle() // 그 사이 화면이 바뀜
            return
        }
        if (request.kind == Kind.ENTRY && toastVisibleAt(request.takenAt)) {
            // 찍는 순간 토스트가 떠 있었다: 버리고 토스트가 사라진 뒤 다시 찍는다
            bitmap.recycle()
            debug("#DROP", "toast")
            entryPending = true
            entryDeadline = maxOf(entryDeadline, toastUntil)
            handler.removeCallbacks(entryCapture)
            handler.postAtTime(entryCapture, toastUntil)
            return
        }
        rec.addFrame(bitmap, request.scrollDy)
        overlay.pulse()
        debug("#FRAME", "${request.kind.name.lowercase()} dy=${request.scrollDy ?: "?"}")
    }

    private companion object {
        /** AccessibilityEvent.getScrollDeltaY 가 이동량을 모를 때 돌려주는 값. */
        const val UNDEFINED_DELTA = -1
    }
}
