package com.qa.autocapture.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.qa.autocapture.MainActivity
import com.qa.autocapture.overlay.FloatingButton
import com.qa.autocapture.session.SessionRecorder
import com.qa.autocapture.storage.DebugLog
import com.qa.autocapture.storage.Options

/**
 * 접근성 서비스. 화면 전환·스크롤을 감지해 스크린샷을 찍고 SessionRecorder 에 넘긴다.
 *
 * - 화면 식별자 = packageName + className (창 ID 별로 처음 본 className)
 * - 화면 진입·스크롤 후 바로 찍지 않고 화면이 안정되면 1장(Stabilizer: 내용 변화·로딩 표시·토스트 대기)
 * - takeScreenshot 은 350ms 이상 간격으로 하나씩 실행하고 요청은 1건으로 합친다
 * - 캡처 직전 플로팅 버튼을 숨기고 캡처 후 다시 보인다
 * 이벤트 흐름은 DESIGN.md 3장 참고.
 */
class CaptureService : AccessibilityService() {

    private data class Screen(val packageName: String, val className: String)

    private enum class Shot { FRAME, TOAST }

    private val handler = Handler(Looper.getMainLooper())
    private val stabilizer = Stabilizer()
    private lateinit var options: Options
    private var overlay: FloatingButton? = null
    private var recorder: SessionRecorder? = null
    private var debugLog: DebugLog? = null

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
    private var captureWanted = false // 안정화가 끝나 찍을 일반 프레임
    private var toastShotWanted = false // "토스트 순간 별도 저장"
    private var capturing = false
    private var scheduledShot: Shot? = null // 오버레이를 숨기고 촬영을 기다리는 중인 종류
    private var lastShotAt = 0L

    private val stabilityCheck = Runnable { checkStable() }
    private val toastShot = Runnable {
        toastShotWanted = true
        pumpCapture()
    }
    private val shoot = Runnable { takeShot() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        options = Options(this)
        refreshIgnoredPackages()
        val button = FloatingButton(this, onTap = ::toggleSession, onLongPress = ::openApp)
        button.show()
        overlay = button
        val (top, bottom) = button.systemBarInsets()
        SessionRecorder.recoverUnfinished(applicationContext, top, bottom)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        debugLog?.let { if (pkg != packageName) logEvent(it, event) }
        if (pkg == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg in ignoredPackages) {
                    if (pkg in leavePackages) cancelPendingCaptures()
                    return
                }
                onWindowStateChanged(pkg, event.className?.toString(), event.windowId)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (pkg in ignoredPackages) return
                onViewScrolled(pkg, event.windowId, event.scrollDeltaX, event.scrollDeltaY)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 안정화 판단: 지금 화면 앱의 내용이 바뀌면 다시 기다린다
                if (pkg == current?.packageName) stabilizer.onActivity(SystemClock.uptimeMillis())
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (pkg != packageName && event.className?.contains("Toast") == true) onToast(pkg, eventText(event))
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        // 바로가기 등으로 서비스가 꺼지면 진행 중인 세션을 저장하고 끝낸다
        stopSession()
        handler.removeCallbacksAndMessages(null)
        overlay?.remove()
        overlay = null
        super.onDestroy()
    }

    // ── 화면 식별 ────────────────────────────────────────────────

    private fun onWindowStateChanged(pkg: String, cls: String?, windowId: Int) {
        if (cls.isNullOrEmpty()) return
        if (cls.startsWith("android.widget.Toast")) return // 토스트 창은 화면이 아니다
        val screen = windowScreens[windowId]
            ?: Screen(pkg, cls).also { if (windowId >= 0) windowScreens[windowId] = it }
        lastSeen = screen
        if (recorder == null) return
        switchTo(screen)
        // 전환 애니메이션·로딩이 끝나 화면이 안정되면 1장
        requestStableCapture()
    }

    private fun onViewScrolled(pkg: String, windowId: Int, dx: Int, dy: Int) {
        if (recorder == null) return
        if (dy == 0 && dx != 0) return // 가로 스크롤은 범위 밖
        // 다이얼로그가 닫히고 원래 화면을 스크롤하면 그 화면으로 돌아간다
        val screen = windowScreens[windowId] ?: current?.takeIf { it.packageName == pkg } ?: return
        switchTo(screen)
        // 스크롤이 멈추고 화면이 안정되면 1장(이어붙일 중간 프레임도 같은 안정화 대기)
        requestStableCapture()
    }

    private fun switchTo(screen: Screen) {
        if (screen == current) return
        current = screen
        cancelPendingCaptures()
        recorder?.enterScreen(screen.packageName, screen.className)
    }

    /** 기다리던 일반 프레임 촬영을 버린다. 토스트 순간 촬영은 화면이 바뀌어도 유지한다. */
    private fun cancelPendingCaptures() {
        stabilizer.cancel()
        handler.removeCallbacks(stabilityCheck)
        captureWanted = false
        if (scheduledShot == Shot.FRAME) {
            // 숨김 대기 중이던 촬영은 이전 화면 것이므로 취소
            handler.removeCallbacks(shoot)
            finishShot()
            pumpCapture()
        }
    }

    // ── 안정화 ──────────────────────────────────────────────────

    private fun requestStableCapture() {
        if (recorder == null || current == null) return
        stabilizer.request(SystemClock.uptimeMillis())
        checkStable()
    }

    private fun checkStable() {
        handler.removeCallbacks(stabilityCheck)
        when (val d = stabilizer.evaluate(SystemClock.uptimeMillis(), ::progressVisible)) {
            is Stabilizer.Decision.Wait -> {
                if (d.reason != "settle") debug("#WAIT", d.reason)
                handler.postAtTime(stabilityCheck, d.at)
            }
            is Stabilizer.Decision.Capture -> {
                debug("#CAPTURE", "${if (d.forced) "forced" else "stable"} waited=${d.waitedMs}ms")
                captureWanted = true
                pumpCapture()
            }
            Stabilizer.Decision.Idle -> Unit
        }
    }

    /** 활성 창 노드 트리에 보이는 로딩 표시(ProgressBar 클래스 또는 progressbar 역할)가 있는가. */
    private fun progressVisible(): Boolean {
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        if (root.packageName?.toString() == current?.packageName) queue.add(root) else recycle(root)
        var visited = 0
        var found: String? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (found == null && visited++ < MAX_SCAN_NODES && node.isVisibleToUser) {
                if (isProgress(node)) {
                    found = node.className?.toString() ?: "progressbar"
                } else {
                    for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
                }
            }
            recycle(node)
        }
        if (found != null) debug("#PROGRESS", found)
        return found != null
    }

    private fun isProgress(node: AccessibilityNodeInfo): Boolean {
        if (node.className?.contains("ProgressBar") == true) return true
        val extras = node.extras
        // AndroidX 의 roleDescription, 웹뷰(Chromium)의 ARIA role
        return extras.getString(EXTRA_ROLE_DESCRIPTION)?.contains("progress", ignoreCase = true) == true ||
            extras.getString(EXTRA_CHROME_ROLE) == "progressIndicator"
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) = node.recycle()

    // ── 토스트 ──────────────────────────────────────────────────

    private fun onToast(pkg: String, text: String) {
        val rec = recorder ?: return
        stabilizer.onToast(SystemClock.uptimeMillis())
        rec.logToast(System.currentTimeMillis(), pkg, text, current?.className)
        // 이미 찍기로 한 일반 프레임은 토스트가 사라진 뒤로 미룬다
        if (captureWanted || scheduledShot == Shot.FRAME) {
            cancelPendingCaptures()
            requestStableCapture()
        }
        if (options.saveToastShot) {
            handler.removeCallbacks(toastShot)
            handler.postDelayed(toastShot, CaptureTiming.TOAST_SHOT_DELAY_MS)
        }
    }

    private fun eventText(event: AccessibilityEvent): String =
        event.text.joinToString(" ").ifEmpty { event.contentDescription?.toString() ?: "" }

    // ── 캡처 큐 ─────────────────────────────────────────────────

    private fun pumpCapture() {
        if (capturing || recorder == null) return
        val kind = when {
            toastShotWanted -> Shot.TOAST
            captureWanted -> Shot.FRAME
            else -> return
        }
        if (kind == Shot.TOAST) toastShotWanted = false else captureWanted = false
        capturing = true
        scheduledShot = kind
        overlay?.hideForCapture()
        val wait = lastShotAt + CaptureTiming.MIN_SHOT_INTERVAL_MS - SystemClock.uptimeMillis()
        handler.postDelayed(shoot, maxOf(CaptureTiming.HIDE_SETTLE_MS, wait))
    }

    private fun takeShot() {
        val kind = scheduledShot
        scheduledShot = null
        val rec = recorder
        if (rec == null || kind == null) {
            finishShot()
            return
        }
        val segment = rec.currentSegment
        lastShotAt = SystemClock.uptimeMillis()
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    finishShot()
                    val r = recorder?.takeIf { it === rec }
                    when {
                        r == null -> result.hardwareBuffer.close() // 세션이 끝남
                        kind == Shot.TOAST -> r.addToastFrame(result.hardwareBuffer, result.colorSpace) // 지금 화면 구간에 붙인다
                        r.currentSegment == segment -> r.addFrame(result.hardwareBuffer, result.colorSpace)
                        else -> result.hardwareBuffer.close() // 그 사이 화면이 바뀜
                    }
                    pumpCapture()
                }

                override fun onFailure(errorCode: Int) {
                    finishShot()
                    Log.w(TAG, "takeScreenshot 실패: $errorCode")
                    debug("#SHOT_FAIL", "code=$errorCode")
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        if (kind == Shot.TOAST) toastShotWanted = true else captureWanted = true
                    }
                    pumpCapture()
                }
            },
        )
    }

    private fun finishShot() {
        capturing = false
        scheduledShot = null
        overlay?.restoreAfterCapture()
    }

    // ── 디버그 로그 ──────────────────────────────────────────────

    private fun logEvent(log: DebugLog, e: AccessibilityEvent) {
        val extra = buildString {
            append("win=").append(e.windowId)
            when (e.eventType) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                    append(" change=").append(e.contentChangeTypes)
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> append(" dx=").append(e.scrollDeltaX).append(" dy=").append(e.scrollDeltaY)
            }
        }
        val type = AccessibilityEvent.eventTypeToString(e.eventType)
        log.log(System.currentTimeMillis(), type, e.packageName, e.className, eventText(e).take(MAX_LOG_TEXT), extra)
    }

    /** 서비스 내부 판단(캡처 시점, 대기 이유 등)을 디버그 로그에 남긴다. */
    private fun debug(tag: String, message: String) {
        debugLog?.log(System.currentTimeMillis(), tag, current?.packageName, current?.className, message)
    }

    /** 디버그 로그 중에는 모든 이벤트를 받아, 대상 앱의 토스트·로딩이 어떤 이벤트로 오는지 볼 수 있게 한다. */
    private fun listenToAllEvents(all: Boolean) {
        runCatching {
            val info = serviceInfo ?: return
            info.eventTypes = if (all) AccessibilityEvent.TYPES_ALL_MASK else CAPTURE_EVENT_TYPES
            serviceInfo = info
        }
    }

    // ── 세션 ────────────────────────────────────────────────────

    private fun toggleSession() {
        if (recorder == null) startSession() else stopSession()
    }

    private fun startSession() {
        refreshIgnoredPackages()
        val button = overlay ?: return
        val (top, bottom) = button.systemBarInsets()
        val rec = SessionRecorder(applicationContext, top, bottom) { sessionId, saved ->
            if (recorder?.sessionId == sessionId) overlay?.setRecording(true, saved)
        }
        recorder = rec
        current = null
        button.setRecording(true, 0)
        if (options.debugLog) {
            debugLog = DebugLog(applicationContext, rec.sessionId)
            listenToAllEvents(true)
        }
        // 이미 앱 화면 위에서 시작했다면 그 화면부터 기록
        val seen = lastSeen
        if (seen != null && seen.packageName == activeWindowPackage()) {
            switchTo(seen)
            requestStableCapture()
        }
    }

    private fun stopSession() {
        val rec = recorder ?: return
        handler.removeCallbacks(toastShot)
        toastShotWanted = false
        cancelPendingCaptures()
        recorder = null
        current = null
        debugLog?.let {
            it.close()
            debugLog = null
            listenToAllEvents(false)
        }
        overlay?.setRecording(false, 0)
        val app = applicationContext
        rec.stop { saved -> Toast.makeText(app, "QA 기록 저장 완료: 화면 ${saved}개", Toast.LENGTH_LONG).show() }
    }

    private fun activeWindowPackage(): String? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString()
        @Suppress("DEPRECATION")
        root.recycle()
        return pkg
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

    private companion object {
        const val TAG = "CaptureService"
        // 타이밍 상수는 CaptureTiming(Stabilizer.kt) 한 곳에 모은다

        /** res/xml/accessibility_service_config.xml 의 accessibilityEventTypes 와 같아야 한다 */
        const val CAPTURE_EVENT_TYPES = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED

        /** 로딩 표시를 찾을 때 살펴볼 최대 노드 수(웹뷰 등 큰 트리 대비) */
        const val MAX_SCAN_NODES = 2000
        const val MAX_LOG_TEXT = 500
        const val EXTRA_ROLE_DESCRIPTION = "AccessibilityNodeInfo.roleDescription"
        const val EXTRA_CHROME_ROLE = "AccessibilityNodeInfo.chromeRole"
        const val MAX_WINDOWS = 256
        const val SAMSUNG_LAUNCHER = "com.sec.android.app.launcher"
        val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.samsung.android.app.cocktailbarservice", // 엣지 패널
            "com.samsung.android.app.smartcapture", // 스크린샷 툴바
        )
    }
}
