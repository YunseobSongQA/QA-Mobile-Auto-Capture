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
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.qa.autocapture.MainActivity
import com.qa.autocapture.overlay.FloatingButton
import com.qa.autocapture.session.SessionRecorder
import kotlin.math.abs

/**
 * 접근성 서비스. 화면 전환·스크롤을 감지해 스크린샷을 찍고 SessionRecorder 에 넘긴다.
 *
 * - 화면 식별자 = packageName + className (창 ID 별로 처음 본 className)
 * - 진입 후 500ms, 스크롤이 300ms 멈출 때마다 1장. 길게 스크롤하면 화면 높이 50% 마다 1장 추가
 * - takeScreenshot 은 350ms 이상 간격으로 하나씩 실행하고 요청은 1건으로 합친다
 * - 캡처 직전 플로팅 버튼을 숨기고 캡처 후 다시 보인다
 * 이벤트 흐름은 DESIGN.md 3장 참고.
 */
class CaptureService : AccessibilityService() {

    private data class Screen(val packageName: String, val className: String)

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: FloatingButton? = null
    private var recorder: SessionRecorder? = null

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
    private var scrollAccum = 0

    // 캡처 큐 상태
    private var captureWanted = false
    private var capturing = false
    private var shotScheduled = false
    private var lastShotAt = 0L

    private val entryCapture = Runnable { requestCapture() }
    private val scrollStopped = Runnable {
        scrollAccum = 0
        requestCapture()
    }
    private val shoot = Runnable { takeShot() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        refreshIgnoredPackages()
        val button = FloatingButton(this, onTap = ::toggleSession, onLongPress = ::openApp)
        button.show()
        overlay = button
        val (top, bottom) = button.systemBarInsets()
        SessionRecorder.recoverUnfinished(applicationContext, top, bottom)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
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
        val screen = windowScreens[windowId]
            ?: Screen(pkg, cls).also { if (windowId >= 0) windowScreens[windowId] = it }
        lastSeen = screen
        if (recorder == null) return
        switchTo(screen)
        // 전환 애니메이션이 끝나도록 창 이벤트가 잠잠해진 뒤 1장
        handler.removeCallbacks(entryCapture)
        handler.postDelayed(entryCapture, ENTRY_SETTLE_MS)
    }

    private fun onViewScrolled(pkg: String, windowId: Int, dx: Int, dy: Int) {
        if (recorder == null) return
        if (dy == 0 && dx != 0) return // 가로 스크롤은 범위 밖
        // 다이얼로그가 닫히고 원래 화면을 스크롤하면 그 화면으로 돌아간다
        val screen = windowScreens[windowId] ?: current?.takeIf { it.packageName == pkg } ?: return
        switchTo(screen)

        if (dy != UNDEFINED_DELTA) scrollAccum += abs(dy)
        if (scrollAccum >= resources.displayMetrics.heightPixels * OVERLAP_GUARD_RATIO) {
            // 멈추지 않고 길게 스크롤해도 프레임끼리 겹치도록 중간에 1장
            scrollAccum = 0
            requestCapture()
        }
        handler.removeCallbacks(scrollStopped)
        handler.postDelayed(scrollStopped, SCROLL_DEBOUNCE_MS)
    }

    private fun switchTo(screen: Screen) {
        if (screen == current) return
        current = screen
        cancelPendingCaptures()
        recorder?.enterScreen(screen.packageName, screen.className)
    }

    private fun cancelPendingCaptures() {
        handler.removeCallbacks(entryCapture)
        handler.removeCallbacks(scrollStopped)
        captureWanted = false
        scrollAccum = 0
        if (shotScheduled) {
            // 숨김 대기 중이던 촬영은 이전 화면 것이므로 취소
            handler.removeCallbacks(shoot)
            finishShot()
        }
    }

    // ── 캡처 큐 ─────────────────────────────────────────────────

    private fun requestCapture() {
        if (recorder == null || current == null) return
        captureWanted = true
        pumpCapture()
    }

    private fun pumpCapture() {
        if (!captureWanted || capturing || recorder == null) return
        captureWanted = false
        capturing = true
        shotScheduled = true
        overlay?.hideForCapture()
        val wait = lastShotAt + MIN_SHOT_INTERVAL_MS - SystemClock.uptimeMillis()
        handler.postDelayed(shoot, maxOf(HIDE_SETTLE_MS, wait))
    }

    private fun takeShot() {
        shotScheduled = false
        val rec = recorder
        if (rec == null) {
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
                    val r = recorder
                    if (r === rec && r.currentSegment == segment) {
                        r.addFrame(result.hardwareBuffer, result.colorSpace)
                    } else {
                        result.hardwareBuffer.close() // 그 사이 화면이 바뀜
                    }
                    pumpCapture()
                }

                override fun onFailure(errorCode: Int) {
                    finishShot()
                    Log.w(TAG, "takeScreenshot 실패: $errorCode")
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) captureWanted = true
                    pumpCapture()
                }
            },
        )
    }

    private fun finishShot() {
        capturing = false
        shotScheduled = false
        overlay?.restoreAfterCapture()
    }

    // ── 세션 ────────────────────────────────────────────────────

    private fun toggleSession() {
        if (recorder == null) startSession() else stopSession()
    }

    private fun startSession() {
        refreshIgnoredPackages()
        val button = overlay ?: return
        val (top, bottom) = button.systemBarInsets()
        recorder = SessionRecorder(applicationContext, top, bottom) { sessionId, saved ->
            if (recorder?.sessionId == sessionId) overlay?.setRecording(true, saved)
        }
        current = null
        button.setRecording(true, 0)
        // 이미 앱 화면 위에서 시작했다면 그 화면부터 기록
        val seen = lastSeen
        if (seen != null && seen.packageName == activeWindowPackage()) {
            switchTo(seen)
            handler.postDelayed(entryCapture, ENTRY_SETTLE_MS)
        }
    }

    private fun stopSession() {
        val rec = recorder ?: return
        cancelPendingCaptures()
        recorder = null
        current = null
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
        const val ENTRY_SETTLE_MS = 500L
        const val SCROLL_DEBOUNCE_MS = 300L

        /** 오버레이를 숨긴 뒤 화면에 반영될 때까지 기다리는 시간 */
        const val HIDE_SETTLE_MS = 80L

        /** 시스템 제한(333ms) + 여유 */
        const val MIN_SHOT_INTERVAL_MS = 350L
        const val OVERLAP_GUARD_RATIO = 0.5
        const val UNDEFINED_DELTA = -1
        const val MAX_WINDOWS = 256
        const val SAMSUNG_LAUNCHER = "com.sec.android.app.launcher"
        val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.samsung.android.app.cocktailbarservice", // 엣지 패널
            "com.samsung.android.app.smartcapture", // 스크린샷 툴바
        )
    }
}
