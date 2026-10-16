package com.qa.autocapture.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.qa.autocapture.MainActivity
import com.qa.autocapture.overlay.FloatingButton
import com.qa.autocapture.session.SessionRecorder
import com.qa.autocapture.storage.EvidenceStore
import com.qa.autocapture.storage.Options
import com.qa.autocapture.storage.TextLog
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 접근성 서비스. 보고하기 좋은 화면만 골라 찍어 SessionRecorder 에 넘긴다.
 *
 * - 찍는 때(중요한 때만): 화면 진입, 터치·길게 누르기·입력, 스크롤 멈춤.
 *   앱이 스스로 바뀐 것(배너, 시계, 애니메이션)은 화면 대부분이 바뀌었을 때만.
 * - 찍는 화면: 이벤트가 SETTLE_MS 동안 없고, CONFIRM_MS 간격의 두 화면이 같은 "정적으로 완성된" 화면 (SettleCycle).
 *   비교용 화면은 플로팅 버튼을 숨기지 않고 찍고(깜빡임 없음), 저장할 1장만 버튼을 숨기고 찍는다.
 * - 스크롤: 스크롤 중에는 찍지 않고 멈출 때마다 1장 → 같은 화면 상태에 덧붙여 나중에 긴 이미지 1장 (ScrollTimer).
 * - 화면 상태: 작은 변화는 그 상태 이미지를 최신 화면으로 바꾸고, 뚜렷이 바뀌면 새 이미지.
 * - 마스터 스위치(Options.captureEnabled)를 끄면 플로팅 버튼을 숨기고 이벤트를 받지 않는다.
 * 이벤트 흐름은 DESIGN.md 3장 참고.
 */
class CaptureService : AccessibilityService() {

    private data class Screen(val packageName: String, val className: String)

    private enum class Purpose { PREVIEW, PROBE, FINAL, SCROLL }

    /** 찍을 것. [generation] = SettleCycle 의 판단 번호(스크롤은 0). */
    private class Shot(val purpose: Purpose, val generation: Int)

    private val handler = Handler(Looper.getMainLooper())
    private val analyzer = Executors.newSingleThreadExecutor { Thread(it, "qa-analyze") }
    private val cycle = SettleCycle()
    private val scrollTimer = ScrollTimer()
    private lateinit var options: Options
    private var overlay: FloatingButton? = null
    private var recorder: SessionRecorder? = null
    private var debugLog: TextLog? = null

    /** 마스터 스위치. false 면 이벤트 처리·캡처를 모두 멈춘다. */
    private var enabled = false

    /** 비교에서 뺄 상태바·내비게이션바 높이(세션 시작 때). */
    private var insetTop = 0
    private var insetBottom = 0

    /** 이벤트를 무시할 패키지: 이 앱, 런처, 시스템 UI, 키보드 */
    private val ignoredPackages = HashSet<String>()

    /** 이 중 앞으로 나오면 "사용자가 화면을 떠난 것"으로 보고 대기 중인 캡처를 취소할 패키지: 이 앱, 런처 */
    private val leavePackages = HashSet<String>()

    /** windowId → 그 창의 화면. 같은 창에서 온 다른 className(Drawer, DecorView 등)은 같은 화면으로 본다. */
    private val windowScreens = object : LinkedHashMap<Int, Screen>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Screen>?) = size > MAX_WINDOWS
    }

    /** 세션 밖에서도 기억하는 마지막 화면(세션 시작 직후 사용) */
    private var lastSeen: Screen? = null
    private var current: Screen? = null

    /** 이 화면 방문에서 마지막으로 확보한 화면(비교 기준)과, 그것이 진입 직후 미리 확보한 임시 화면인지. */
    private var lastThumb: FrameDiff.Thumb? = null
    private var lastIsPreview = false

    // 촬영 큐
    private var wanted: Shot? = null
    private var shooting = false // 촬영 예약 ~ 결과 수신
    private var inFlight = false // takeScreenshot 호출 ~ 결과 수신
    private var lastShotAt = 0L
    private var cycleCheckPending = false
    private var scrollCheckPending = false
    private var changeCooldownUntil = 0L

    /** 마지막으로 확보한 화면 이후 세로 스크롤 이동량(scrollDeltaY 합). 0 이 아닌 값이 없으면 모르는 것으로 본다. */
    private var scrollDy = 0
    private var scrollDyKnown = false

    private val cycleCheck = Runnable {
        cycleCheckPending = false
        checkCycle()
    }
    private val scrollCheck = Runnable {
        scrollCheckPending = false
        checkScroll()
    }
    private val prepare = Runnable { prepareShot() }
    private val shoot = Runnable { takeShot() }
    private val pump = Runnable { pumpShot() }

    /** 설정 화면·빠른 설정 타일에서 마스터 스위치를 바꾸면 바로 반영한다. */
    private val optionsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Options.KEY_CAPTURE_ENABLED && options.captureEnabled != enabled) applyEnabled(options.captureEnabled)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        options = Options(this)
        options.listen(optionsListener)
        val button = FloatingButton(this, onTap = ::toggleSession, onLongPress = ::openApp)
        overlay = button
        connected = true
        applyEnabled(options.captureEnabled)
        val (top, bottom) = button.systemBarInsets()
        SessionRecorder.recoverUnfinished(applicationContext, top, bottom)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!enabled) return
        val pkg = event.packageName?.toString()
        debugLog?.let { if (pkg != packageName) logEvent(it, event) }
        if (pkg == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg in ignoredPackages) {
                    if (pkg in leavePackages) cancelPending()
                    return
                }
                onWindowStateChanged(pkg, event.className?.toString(), event.windowId)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (pkg in ignoredPackages) return
                onViewScrolled(pkg, event.windowId, event.scrollDeltaX, event.scrollDeltaY)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            -> if (pkg == current?.packageName) onAction()
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pkg == current?.packageName) onContentChanged()
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (pkg != packageName && event.className?.contains("Toast") == true) onToast(pkg, eventText(event))
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        // 바로가기 등으로 서비스가 꺼지면 진행 중인 세션을 저장하고 끝낸다
        stopSession("서비스 꺼짐")
        if (::options.isInitialized) options.unlisten(optionsListener)
        handler.removeCallbacksAndMessages(null)
        analyzer.shutdown()
        overlay?.remove()
        overlay = null
        connected = false
        notifyState()
        super.onDestroy()
    }

    // ── 마스터 스위치 ────────────────────────────────────────────

    private fun applyEnabled(on: Boolean) {
        enabled = on
        if (on) {
            refreshIgnoredPackages()
            overlay?.show()
        } else {
            stopSession("일시정지")
            overlay?.remove()
            // 멈춘 동안 바뀐 화면은 알 수 없다
            lastSeen = null
        }
        applyEventTypes()
        notifyState()
    }

    /** 일시정지 중에는 이벤트를 아예 받지 않는다. 디버그 로그 중에는 모든 이벤트를 받는다. */
    private fun applyEventTypes() {
        runCatching {
            val info = serviceInfo ?: return
            info.eventTypes = when {
                !enabled -> 0
                debugLog != null -> AccessibilityEvent.TYPES_ALL_MASK
                else -> CAPTURE_EVENT_TYPES
            }
            serviceInfo = info
        }
    }

    // ── 화면 식별 ────────────────────────────────────────────────

    private fun onWindowStateChanged(pkg: String, cls: String?, windowId: Int) {
        if (cls.isNullOrEmpty()) return
        if (cls.startsWith("android.widget.Toast")) {
            onContentChanged() // 토스트 창은 화면이 아니다
            return
        }
        val screen = windowScreens[windowId]
            ?: Screen(pkg, cls).also { if (windowId >= 0) windowScreens[windowId] = it }
        lastSeen = screen
        if (recorder == null) return
        // 같은 창 안의 화면 바뀜(창 제목·패널 변경)은 터치처럼 확인한다
        if (screen != current) switchTo(screen) else onAction()
    }

    private fun onViewScrolled(pkg: String, windowId: Int, dx: Int, dy: Int) {
        if (recorder == null) return
        // 다이얼로그가 닫히고 원래 화면을 스크롤하면 그 화면으로 돌아간다
        val screen = windowScreens[windowId] ?: current?.takeIf { it.packageName == pkg } ?: return
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

    private fun switchTo(screen: Screen) {
        current = screen
        stopCycle()
        resetScroll()
        lastThumb = null
        lastIsPreview = false
        recorder?.enterScreen(screen.packageName, screen.className)
        startCycle(SettleCycle.Kind.ACTION, preview = true)
    }

    // ── 언제 찍을지 ──────────────────────────────────────────────

    /** 터치·입력: 화면이 바뀌었으면 완성된 뒤 찍는다. */
    private fun onAction() {
        if (recorder == null || current == null) return
        if (scrollTimer.active) {
            scrollTimer.onActivity(SystemClock.uptimeMillis())
            return
        }
        startCycle(SettleCycle.Kind.ACTION)
    }

    /** 내용 변경: 확인 중이면 아직 바뀌는 중이라는 뜻, 아니면 앱이 스스로 바뀐 것(큰 변화일 때만 찍음). */
    private fun onContentChanged() {
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

    private fun onToast(pkg: String, text: String) {
        val rec = recorder ?: return
        rec.logToast(System.currentTimeMillis(), pkg, text, current?.className)
        onContentChanged()
    }

    private fun startCycle(kind: SettleCycle.Kind, preview: Boolean = false) {
        cycle.start(kind, SystemClock.uptimeMillis(), preview)
        checkCycle()
    }

    private fun stopCycle() {
        cycle.cancel()
        handler.removeCallbacks(cycleCheck)
        cycleCheckPending = false
        if (wanted?.purpose != Purpose.SCROLL) wanted = null
    }

    private fun resetScroll() {
        scrollTimer.cancel()
        handler.removeCallbacks(scrollCheck)
        scrollCheckPending = false
        if (wanted?.purpose == Purpose.SCROLL) wanted = null
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
                want(Shot(purpose, d.generation))
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
                want(Shot(Purpose.SCROLL, 0))
            }
            ScrollTimer.Decision.Idle -> Unit
        }
    }

    /** 사용자가 홈·이 앱으로 떠났다. 기다리던 촬영을 모두 버린다(떠난 뒤 화면이 찍히지 않게). */
    private fun cancelPending() {
        stopCycle()
        resetScroll()
        wanted = null
        handler.removeCallbacks(pump)
        if (shooting && !inFlight) {
            handler.removeCallbacks(prepare)
            handler.removeCallbacks(shoot)
            finishShot()
        }
    }

    // ── 촬영 ────────────────────────────────────────────────────

    private fun want(shot: Shot) {
        if (wanted?.purpose == Purpose.SCROLL && shot.purpose != Purpose.SCROLL) {
            // 스크롤 프레임이 먼저다. 정지 확인은 조금 뒤 다시 판단한다
            cycle.onShotFailed(shot.generation, SystemClock.uptimeMillis(), CaptureConfig.CONFIRM_MS)
            checkCycle()
            return
        }
        wanted = shot
        pumpShot()
    }

    /** 찍을 것이 있으면 최소 간격에 맞춰 1장 예약한다. 버튼은 저장용 화면을 찍기 직전에만 숨긴다. */
    private fun pumpShot() {
        val rec = recorder ?: return
        val s = wanted ?: return
        if (shooting) return
        if (rec.busy) {
            // 저장이 밀리면 화면이 쌓이지 않게 잠시 미룬다
            handler.removeCallbacks(pump)
            handler.postDelayed(pump, CaptureConfig.BACKLOG_RETRY_MS)
            return
        }
        shooting = true
        val hide = if (s.purpose == Purpose.PROBE) 0 else CaptureConfig.HIDE_SETTLE_MS
        handler.postAtTime(prepare, maxOf(SystemClock.uptimeMillis(), lastShotAt + CaptureConfig.MIN_SHOT_INTERVAL_MS - hide))
    }

    private fun prepareShot() {
        val s = wanted
        if (s == null || recorder == null) {
            finishShot()
            return
        }
        if (s.purpose == Purpose.PROBE) {
            takeShot()
        } else {
            overlay?.hideForCapture()
            handler.postDelayed(shoot, CaptureConfig.HIDE_SETTLE_MS)
        }
    }

    private fun takeShot() {
        val s = wanted
        wanted = null
        val rec = recorder
        // 스크롤이 다시 시작됐으면 멈출 때 다시 요청된다
        if (s == null || rec == null || (s.purpose == Purpose.SCROLL && scrollTimer.active)) {
            finishShot()
            pumpShot()
            return
        }
        val box = if (s.purpose == Purpose.PROBE) overlay?.screenBox() else null
        val segment = rec.currentSegment
        val dy = if (s.purpose == Purpose.SCROLL && scrollDyKnown) scrollDy else null
        if (s.purpose == Purpose.SCROLL) {
            scrollDy = 0
            scrollDyKnown = false
        }
        inFlight = true
        lastShotAt = SystemClock.uptimeMillis()
        debug("#SHOT", s.purpose.name.lowercase())
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    finishShot()
                    analyze(s, result, box, segment, dy)
                    pumpShot()
                }

                override fun onFailure(errorCode: Int) {
                    finishShot()
                    Log.w(TAG, "takeScreenshot 실패: $errorCode")
                    debug("#SHOT_FAIL", "code=$errorCode")
                    if (dy != null) {
                        scrollDy += dy
                        scrollDyKnown = true
                    }
                    if (recorder === rec) {
                        if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                            if (wanted == null) wanted = s
                        } else if (s.purpose != Purpose.SCROLL) {
                            cycle.onShotFailed(s.generation, SystemClock.uptimeMillis(), CaptureConfig.CONFIRM_MS)
                            checkCycle()
                        }
                    }
                    pumpShot()
                }
            },
        )
    }

    private fun finishShot() {
        shooting = false
        inFlight = false
        overlay?.restoreAfterCapture()
    }

    /** 스크린샷을 별도 스레드에서 Bitmap·축소 이미지로 바꿔 메인 스레드로 돌려준다. 비교용 화면은 축소 이미지만. */
    private fun analyze(s: Shot, result: ScreenshotResult, box: FrameDiff.Box?, segment: Int, dy: Int?) {
        val buffer = result.hardwareBuffer
        val colorSpace = result.colorSpace
        val keep = s.purpose != Purpose.PROBE
        val top = insetTop
        val bottom = insetBottom
        try {
            analyzer.execute {
                var bitmap: Bitmap? = null
                val thumb = try {
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                    bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    hardware?.recycle()
                    bitmap?.let { thumbnail(it, top, bottom) }
                } catch (e: Exception) {
                    Log.w(TAG, "화면 변환 실패", e)
                    null
                } finally {
                    buffer.close()
                }
                if (!keep || thumb == null) {
                    bitmap?.recycle()
                    bitmap = null
                }
                val frame = bitmap
                handler.post { onAnalyzed(s, thumb, frame, box, segment, dy) }
            }
        } catch (e: RejectedExecutionException) {
            buffer.close() // 서비스 종료 중
        }
    }

    private fun onAnalyzed(s: Shot, thumb: FrameDiff.Thumb?, bitmap: Bitmap?, box: FrameDiff.Box?, segment: Int, dy: Int?) {
        val rec = recorder
        if (rec == null || segment != rec.currentSegment || thumb == null) {
            bitmap?.recycle()
            if (rec != null && thumb == null && s.purpose != Purpose.SCROLL) {
                cycle.onShotFailed(s.generation, SystemClock.uptimeMillis(), CaptureConfig.CONFIRM_MS)
                checkCycle()
            }
            return
        }
        val now = SystemClock.uptimeMillis()
        if (s.purpose == Purpose.SCROLL) {
            rec.addFrame(bitmap ?: return, SessionRecorder.Op.SCROLL, dy)
            lastThumb = thumb
            lastIsPreview = false
            debug("#FRAME", "scroll dy=${dy ?: "?"}")
            // 남은 로딩(이미지 등)이 끝나면 방금 프레임을 완성된 화면으로 바꾼다
            startCycle(SettleCycle.Kind.COMPLETION)
            return
        }
        val shot = when (s.purpose) {
            Purpose.PREVIEW -> SettleCycle.Shot.PREVIEW
            Purpose.PROBE -> SettleCycle.Shot.PROBE
            else -> SettleCycle.Shot.FINAL
        }
        when (val o = cycle.onShot(s.generation, shot, thumb, box, lastThumb, now, CaptureConfig.CONFIRM_MS)) {
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

    private fun thumbnail(bitmap: Bitmap, topInset: Int, bottomInset: Int): FrameDiff.Thumb {
        val w = bitmap.width
        val h = bitmap.height
        val top = topInset.coerceIn(0, h / 3)
        val bottom = h - bottomInset.coerceIn(0, h / 3)
        return FrameDiff.thumbnail(w, top, bottom) { y, count, into -> bitmap.getPixels(into, 0, w, 0, y, w, count) }
    }

    private fun eventText(event: AccessibilityEvent): String =
        event.text.joinToString(" ").ifEmpty { event.contentDescription?.toString() ?: "" }

    // ── 디버그 로그 ──────────────────────────────────────────────

    private fun logEvent(log: TextLog, e: AccessibilityEvent) {
        val extra = buildString {
            append("win=").append(e.windowId)
            when (e.eventType) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                    append(" change=").append(e.contentChangeTypes)
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> append(" dx=").append(e.scrollDeltaX).append(" dy=").append(e.scrollDeltaY)
            }
        }
        val type = AccessibilityEvent.eventTypeToString(e.eventType)
        log.write(System.currentTimeMillis(), type, e.packageName, e.className, eventText(e).take(MAX_LOG_TEXT), extra)
    }

    /** 서비스 내부 판단(촬영, 확보, 스크롤 시작·멈춤, 실패)을 디버그 로그에 남긴다. */
    private fun debug(tag: String, message: String) {
        debugLog?.write(System.currentTimeMillis(), tag, current?.packageName, current?.className, message)
    }

    // ── 세션 ────────────────────────────────────────────────────

    private fun toggleSession() {
        if (recorder == null) startSession() else stopSession("플로팅 버튼으로 정지")
    }

    private fun startSession() {
        if (!enabled) return
        refreshIgnoredPackages()
        val button = overlay ?: return
        val (top, bottom) = button.systemBarInsets()
        insetTop = top
        insetBottom = bottom
        val rec = SessionRecorder(applicationContext, top, bottom) { sessionId, images ->
            if (recorder?.sessionId == sessionId) {
                overlay?.setRecording(true, images)
                recordingImages = images
                notifyListeners()
            }
        }
        recorder = rec
        current = null
        recordingImages = 0
        button.setRecording(true, 0)
        if (options.debugLog) {
            debugLog = TextLog(
                applicationContext,
                EvidenceStore.DEBUG_PATH,
                "${rec.sessionId}.txt",
                listOf("# QA 증적 캡처 디버그 로그 / 세션 ${rec.sessionId}", "# 시각\t이벤트 타입\t패키지\t클래스\t텍스트\t기타"),
            )
            applyEventTypes()
        }
        notifyState()
        // 이미 앱 화면 위에서 시작했다면 그 화면부터 기록
        activeScreen()?.let { switchTo(it) }
    }

    private fun stopSession(reason: String) {
        val rec = recorder ?: return
        cancelPending()
        recorder = null
        current = null
        lastThumb = null
        lastIsPreview = false
        recordingImages = null
        debugLog?.let {
            it.close()
            debugLog = null
            applyEventTypes()
        }
        overlay?.setRecording(false, 0)
        notifyState()
        val app = applicationContext
        rec.stop(reason) { saved ->
            Toast.makeText(app, "QA 기록 저장 완료: 이미지 ${saved}장", Toast.LENGTH_LONG).show()
            notifyListeners()
        }
    }

    /** 지금 앞에 있는 앱 화면. 창 ID 로 아는 화면이거나, 마지막으로 본 화면과 패키지가 같을 때만. */
    private fun activeScreen(): Screen? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString()
        val windowId = root.windowId
        @Suppress("DEPRECATION")
        root.recycle()
        if (pkg == null || pkg in ignoredPackages) return null
        return windowScreens[windowId]?.takeIf { it.packageName == pkg } ?: lastSeen?.takeIf { it.packageName == pkg }
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun refreshIgnoredPackages() {
        leavePackages.clear()
        leavePackages += packageName
        leavePackages += SAMSUNG_LAUNCHER
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        // queryIntentActivities 는 설정 앱의 FallbackHome 까지 돌려주므로 기본 런처 하나만 쓴다
        @Suppress("DEPRECATION")
        packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            ?.takeIf { it != "android" && it != "com.android.settings" }
            ?.let { leavePackages += it }

        ignoredPackages.clear()
        ignoredPackages += leavePackages
        ignoredPackages += SYSTEM_PACKAGES
        getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { ignoredPackages += it.packageName }
    }

    // ── 화면·타일에 상태 알리기 ──────────────────────────────────

    private fun notifyState() {
        notifyListeners()
        CaptureTileService.refresh(this)
    }

    companion object {
        private const val TAG = "CaptureService"
        // 캡처 상수는 CaptureConfig(CapturePolicy.kt) 한 곳에 모은다

        /** res/xml/accessibility_service_config.xml 의 accessibilityEventTypes 와 같아야 한다 */
        private const val CAPTURE_EVENT_TYPES = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
            AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED

        private const val MAX_LOG_TEXT = 500
        private const val MAX_WINDOWS = 256
        private const val SAMSUNG_LAUNCHER = "com.sec.android.app.launcher"
        private val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.samsung.android.app.cocktailbarservice", // 엣지 패널
            "com.samsung.android.app.smartcapture", // 스크린샷 툴바
        )

        // 아래 상태는 같은 프로세스의 메인 화면·빠른 설정 타일이 읽는다. 메인 스레드에서만 쓴다.

        /** 서비스가 시스템에 연결돼 동작 중인가. */
        var connected = false
            private set

        /** 기록 중이면 확보한 이미지 수, 아니면 null. */
        var recordingImages: Int? = null
            private set

        private val listeners = LinkedHashSet<() -> Unit>()

        fun addStateListener(listener: () -> Unit) {
            listeners += listener
        }

        fun removeStateListener(listener: () -> Unit) {
            listeners -= listener
        }

        private fun notifyListeners() {
            for (l in listeners.toList()) l()
        }

        /** 시스템 설정에서 이 접근성 서비스가 켜져 있는가. */
        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val info = it.resolveInfo.serviceInfo
                info.packageName == context.packageName && info.name == CaptureService::class.java.name
            }
        }
    }
}
