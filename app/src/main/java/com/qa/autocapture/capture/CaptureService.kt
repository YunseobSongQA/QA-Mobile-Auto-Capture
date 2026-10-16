package com.qa.autocapture.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
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

/**
 * 접근성 서비스. 화면 변화를 감지해 스크린샷을 찍고 SessionRecorder 에 넘긴다. 판단 규칙 없이 아래만 한다.
 *
 * - 화면 식별자 = packageName + className (창 ID 별로 처음 본 className)
 * - 화면 변화(창 전환·스크롤·내용 변경·토스트)마다 캡처. 변화가 이어지면 takeScreenshot 최소 간격으로 계속 찍는다.
 * - 변화가 SETTLE_MS 동안 없거나, 계속돼도 MAX_WAIT_MS 가 지나면 최종 화면(_final) 1장 (FinalTimer)
 * - 마스터 스위치(Options.captureEnabled)를 끄면 플로팅 버튼을 숨기고 이벤트를 받지 않는다
 * - 캡처 직전 플로팅 버튼을 숨기고 캡처 후 다시 보인다
 * 이벤트 흐름은 DESIGN.md 3장 참고.
 */
class CaptureService : AccessibilityService() {

    private data class Screen(val packageName: String, val className: String)

    private val handler = Handler(Looper.getMainLooper())
    private val finalTimer = FinalTimer()
    private lateinit var options: Options
    private var overlay: FloatingButton? = null
    private var recorder: SessionRecorder? = null
    private var debugLog: TextLog? = null

    /** 마스터 스위치. false 면 이벤트 처리·캡처를 모두 멈춘다. */
    private var enabled = false

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

    // 캡처 큐 상태
    private var changeWanted = false // 화면 변화 캡처
    private var finalWanted = false // 최종 캡처
    private var finalSegment = 0 // 최종 판단을 내린 화면 구간
    private var capturing = false // 촬영 예약 ~ 결과 수신
    private var inFlight = false // takeScreenshot 호출 ~ 결과 수신
    private var lastShotAt = 0L
    private var finalCheckPending = false

    private val finalCheck = Runnable {
        finalCheckPending = false
        checkFinal()
    }
    private val hideAndShoot = Runnable {
        overlay?.hideForCapture()
        handler.postDelayed(shoot, CaptureConfig.HIDE_SETTLE_MS)
    }
    private val shoot = Runnable { takeShot() }
    private val pump = Runnable { pumpCapture() }

    /** 설정 화면·빠른 설정 타일에서 마스터 스위치를 바꾸면 바로 반영한다. */
    private val optionsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Options.KEY_CAPTURE_ENABLED && options.captureEnabled != enabled) applyEnabled(options.captureEnabled)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        options = Options(this)
        options.listen(optionsListener)
        overlay = FloatingButton(this, onTap = ::toggleSession, onLongPress = ::openApp)
        connected = true
        applyEnabled(options.captureEnabled)
        SessionRecorder.recoverUnfinished(applicationContext)
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
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pkg == current?.packageName) onChange()
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
            onChange() // 토스트 창은 화면이 아니지만 화면 변화다
            return
        }
        val screen = windowScreens[windowId]
            ?: Screen(pkg, cls).also { if (windowId >= 0) windowScreens[windowId] = it }
        lastSeen = screen
        if (recorder == null) return
        switchTo(screen)
        onChange()
    }

    private fun onViewScrolled(pkg: String, windowId: Int, dx: Int, dy: Int) {
        val rec = recorder ?: return
        // 다이얼로그가 닫히고 원래 화면을 스크롤하면 그 화면으로 돌아간다
        val screen = windowScreens[windowId] ?: current?.takeIf { it.packageName == pkg } ?: return
        switchTo(screen)
        if (dy != 0 || dx == 0) rec.onScrolled() // 가로로만 스크롤한 경우는 긴 이미지 대상이 아님
        onChange()
    }

    private fun switchTo(screen: Screen) {
        if (screen == current) return
        current = screen
        // 최종 캡처는 화면마다 따로 잰다
        finalTimer.cancel()
        finalWanted = false
        recorder?.enterScreen(screen.packageName, screen.className)
    }

    // ── 화면 변화 → 캡처 ─────────────────────────────────────────

    private fun onChange() {
        if (recorder == null || current == null) return
        finalTimer.onChange(SystemClock.uptimeMillis())
        changeWanted = true
        pumpCapture()
        // 대기 중인 판단이 있으면 그때 마지막 변화 시각으로 다시 잰다(이벤트마다 타이머를 다시 걸지 않음)
        if (!finalCheckPending) checkFinal()
    }

    private fun checkFinal() {
        val rec = recorder ?: return
        val now = SystemClock.uptimeMillis()
        when (val d = finalTimer.evaluate(now, options.settleMs, options.maxWaitMs)) {
            is FinalTimer.Decision.Wait -> {
                finalCheckPending = true
                handler.postAtTime(finalCheck, d.at)
            }
            is FinalTimer.Decision.Final -> {
                debug("#FINAL", if (d.forced) "forced" else "settled")
                finalWanted = true
                finalSegment = rec.currentSegment
                pumpCapture()
                if (d.forced) checkFinal() // 변화가 멈출 때의 최종을 위해 다시 잰다
            }
            FinalTimer.Decision.Idle -> Unit
        }
    }

    /** 사용자가 홈·이 앱으로 떠났다. 기다리던 캡처를 모두 버린다(떠난 뒤 화면이 찍히지 않게). */
    private fun cancelPending() {
        finalTimer.cancel()
        handler.removeCallbacks(finalCheck)
        finalCheckPending = false
        changeWanted = false
        finalWanted = false
        handler.removeCallbacks(pump)
        if (capturing && !inFlight) {
            handler.removeCallbacks(hideAndShoot)
            handler.removeCallbacks(shoot)
            finishShot()
        }
    }

    // ── 캡처 큐 ─────────────────────────────────────────────────

    /** 찍을 것이 있으면 최소 간격에 맞춰 1장 예약한다. 예약 뒤 들어온 변화는 그 1장에 함께 담긴다. */
    private fun pumpCapture() {
        val rec = recorder ?: return
        if (capturing || (!changeWanted && !finalWanted)) return
        if (rec.busy) {
            // 저장이 밀리면 버퍼가 쌓이지 않게 잠시 미룬다
            handler.removeCallbacks(pump)
            handler.postDelayed(pump, CaptureConfig.BACKLOG_RETRY_MS)
            return
        }
        capturing = true
        // 버튼은 촬영 직전에만 숨긴다
        val hideAt = maxOf(
            SystemClock.uptimeMillis(),
            lastShotAt + CaptureConfig.MIN_SHOT_INTERVAL_MS - CaptureConfig.HIDE_SETTLE_MS,
        )
        handler.postAtTime(hideAndShoot, hideAt)
    }

    private fun takeShot() {
        val rec = recorder
        if (rec == null || (!changeWanted && !finalWanted)) {
            finishShot()
            return
        }
        // 최종 판단 뒤 화면이 바뀌었으면 그 화면의 변화 캡처로 저장한다
        val final = finalWanted && finalSegment == rec.currentSegment
        changeWanted = false
        finalWanted = false
        inFlight = true
        lastShotAt = SystemClock.uptimeMillis()
        debug("#SHOT", if (final) "final" else "change")
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    finishShot()
                    if (recorder === rec) rec.addCapture(result.hardwareBuffer, result.colorSpace, final) else result.hardwareBuffer.close()
                    pumpCapture()
                }

                override fun onFailure(errorCode: Int) {
                    finishShot()
                    Log.w(TAG, "takeScreenshot 실패: $errorCode")
                    debug("#SHOT_FAIL", "code=$errorCode")
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && recorder === rec) {
                        if (final) finalWanted = true else changeWanted = true
                    }
                    pumpCapture()
                }
            },
        )
    }

    private fun finishShot() {
        capturing = false
        inFlight = false
        overlay?.restoreAfterCapture()
    }

    // ── 토스트 ──────────────────────────────────────────────────

    private fun onToast(pkg: String, text: String) {
        val rec = recorder ?: return
        rec.logToast(System.currentTimeMillis(), pkg, text, current?.className)
        onChange()
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

    /** 서비스 내부 판단(촬영, 최종 캡처, 실패)을 디버그 로그에 남긴다. */
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
        val rec = SessionRecorder(applicationContext, top, bottom) { sessionId, saved ->
            if (recorder?.sessionId == sessionId) {
                overlay?.setRecording(true, saved)
                recordingImages = saved
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
        activeScreen()?.let {
            switchTo(it)
            onChange()
        }
    }

    private fun stopSession(reason: String) {
        val rec = recorder ?: return
        cancelPending()
        recorder = null
        current = null
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
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED

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

        /** 기록 중이면 저장한 이미지 수, 아니면 null. */
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
