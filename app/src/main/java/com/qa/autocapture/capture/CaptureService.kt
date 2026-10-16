package com.qa.autocapture.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.qa.autocapture.MainActivity
import com.qa.autocapture.capture.policy.CaptureConfig
import com.qa.autocapture.overlay.FloatingButton
import com.qa.autocapture.session.SessionRecorder
import com.qa.autocapture.session.SessionRecovery
import com.qa.autocapture.storage.Options

/**
 * 접근성 서비스 진입점. 이벤트를 받아 나눠 주고, 기록 세션과 마스터 스위치를 관리한다.
 *
 * - 화면 식별·무시 대상: [ScreenTracker]
 * - 언제 무엇을 찍을지: [CaptureController] (→ 촬영 큐 [ScreenshotTaker])
 * - 찍은 화면 기록: [SessionRecorder]
 * - 메인 화면·타일에 상태 알리기: [CaptureStatus]
 *
 * 클래스 이름을 바꾸면 덮어쓰기 설치 후 접근성 권한이 풀리므로 이름과 패키지를 고정한다.
 */
class CaptureService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var options: Options
    private lateinit var tracker: ScreenTracker
    private var overlay: FloatingButton? = null
    private var controller: CaptureController? = null
    private var recorder: SessionRecorder? = null
    private var debugLog: DebugLog? = null

    /** 마스터 스위치. false 면 이벤트 처리·캡처를 모두 멈춘다. */
    private var enabled = false

    /** 설정 화면·빠른 설정 타일에서 마스터 스위치를 바꾸면 바로 반영한다. */
    private val optionsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Options.KEY_CAPTURE_ENABLED && options.captureEnabled != enabled) applyEnabled(options.captureEnabled)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        options = Options(this)
        options.listen(optionsListener)
        tracker = ScreenTracker(this)
        val button = FloatingButton(this, onTap = ::toggleSession, onLongPress = ::openApp)
        overlay = button
        controller = CaptureController(handler, button, this, options) { tag, message -> debugLog?.note(tag, controller?.current, message) }
        CaptureStatus.connected = true
        applyEnabled(options.captureEnabled)
        val (top, bottom) = button.systemBarInsets()
        SessionRecovery.recoverUnfinished(applicationContext, top, bottom)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!enabled) return
        val c = controller ?: return
        val pkg = event.packageName?.toString()
        debugLog?.let { if (pkg != packageName) it.event(event) }
        if (pkg == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (tracker.isIgnored(pkg)) {
                    if (tracker.isLeave(pkg)) c.cancelPending()
                    return
                }
                val cls = event.className?.toString()
                if (cls.isNullOrEmpty()) return
                val screen = tracker.onWindowState(pkg, cls, event.windowId)
                when {
                    screen == null -> c.onContentChanged() // 토스트 창 등은 화면이 아니다
                    recorder == null -> Unit
                    // 같은 창 안의 화면 바뀜(창 제목·패널 변경)은 터치처럼 확인한다
                    screen != c.current -> c.switchTo(screen)
                    else -> c.onAction()
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (recorder == null || tracker.isIgnored(pkg)) return
                val screen = tracker.screenOf(pkg, event.windowId, c.current) ?: return
                if (isSmallScroller(event)) {
                    c.onContentChanged() // 작은 영역(티커 등)의 스크롤은 이어붙일 대상이 아니다
                } else {
                    c.onScroll(screen, event.scrollDeltaX, event.scrollDeltaY)
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            -> if (pkg == c.current?.packageName) c.onAction()
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (pkg == c.current?.packageName) c.onContentChanged()
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (pkg != packageName && event.className?.contains("Toast") == true) c.onToast(pkg, DebugLog.eventText(event))
            }
        }
    }

    override fun onInterrupt() = Unit

    /** 스크롤한 뷰의 화면 높이가 SCROLL_MIN_HEIGHT_RATIO 보다 작은가. 모르면 false. */
    private fun isSmallScroller(event: AccessibilityEvent): Boolean {
        val node = event.source ?: return false
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        @Suppress("DEPRECATION")
        node.recycle()
        val screenHeight = resources.displayMetrics.heightPixels
        return bounds.height() in 1 until (screenHeight * CaptureConfig.SCROLL_MIN_HEIGHT_RATIO).toInt()
    }

    override fun onDestroy() {
        // 바로가기 등으로 서비스가 꺼지면 진행 중인 세션을 저장하고 끝낸다
        stopSession("서비스 꺼짐")
        if (::options.isInitialized) options.unlisten(optionsListener)
        handler.removeCallbacksAndMessages(null)
        controller?.shutdown()
        controller = null
        overlay?.remove()
        overlay = null
        CaptureStatus.connected = false
        notifyState()
        super.onDestroy()
    }

    // ── 마스터 스위치 ────────────────────────────────────────────

    private fun applyEnabled(on: Boolean) {
        enabled = on
        if (on) {
            tracker.refreshIgnoredPackages()
            overlay?.show()
        } else {
            stopSession("일시정지")
            overlay?.remove()
            tracker.forgetLastSeen() // 멈춘 동안 바뀐 화면은 알 수 없다
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

    // ── 세션 ────────────────────────────────────────────────────

    private fun toggleSession() {
        if (recorder == null) startSession() else stopSession("플로팅 버튼으로 정지")
    }

    private fun startSession() {
        if (!enabled) return
        val button = overlay ?: return
        val c = controller ?: return
        tracker.refreshIgnoredPackages()
        val (top, bottom) = button.systemBarInsets()
        val rec = SessionRecorder(applicationContext, top, bottom) { sessionId, images ->
            if (recorder?.sessionId == sessionId) {
                overlay?.setRecording(true, images)
                CaptureStatus.recordingImages = images
                CaptureStatus.notifyChanged()
            }
        }
        recorder = rec
        CaptureStatus.recordingImages = 0
        button.setRecording(true, 0)
        if (options.debugLog) {
            debugLog = DebugLog(applicationContext, rec.sessionId)
            applyEventTypes()
        }
        c.start(rec, top, bottom)
        notifyState()
        // 이미 앱 화면 위에서 시작했다면 그 화면부터 기록
        tracker.activeScreen()?.let { c.switchTo(it) }
    }

    private fun stopSession(reason: String) {
        val rec = recorder ?: return
        controller?.stop()
        recorder = null
        CaptureStatus.recordingImages = null
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
            CaptureStatus.notifyChanged()
        }
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun notifyState() {
        CaptureStatus.notifyChanged()
        CaptureTileService.refresh(this)
    }

    private companion object {
        /** res/xml/accessibility_service_config.xml 의 accessibilityEventTypes 와 같아야 한다 */
        const val CAPTURE_EVENT_TYPES = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
            AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
    }
}
