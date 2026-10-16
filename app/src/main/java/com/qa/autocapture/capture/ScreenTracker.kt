package com.qa.autocapture.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import com.qa.autocapture.capture.policy.CaptureConfig

/** 화면 식별자 = 앱 패키지 + 화면 클래스. */
data class Screen(val packageName: String, val className: String)

/**
 * 화면 식별: 이벤트가 어느 화면의 것인지, 무시할 패키지인지 판단한다. 메인 스레드 전용.
 *
 * - 무시: 이 앱, 홈 런처, 시스템 UI, 키보드(IME), 삼성 엣지 패널·스크린샷 툴바
 * - 떠남: 이 앱·런처가 앞으로 나오면 사용자가 테스트 화면을 떠난 것
 * - 창 ID → 화면: 같은 창에서 온 다른 className(Drawer, DecorView 등)은 같은 화면으로 본다
 * - 로딩 표시: 앞에 있는 창에 돌아가는 ProgressBar 가 보이는지
 */
class ScreenTracker(private val service: AccessibilityService) {

    private val ignoredPackages = HashSet<String>()
    private val leavePackages = HashSet<String>()

    private val windowScreens = object : LinkedHashMap<Int, Screen>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Screen>?) = size > MAX_WINDOWS
    }

    /** 세션 밖에서도 기억하는 마지막 화면(기록 시작 직후 사용). */
    private var lastSeen: Screen? = null

    fun isIgnored(pkg: String) = pkg in ignoredPackages

    /** 이 패키지가 앞으로 나오면 사용자가 화면을 떠난 것(홈·이 앱). */
    fun isLeave(pkg: String) = pkg in leavePackages

    /** 창 전환 이벤트의 화면. 토스트 창이면 null. */
    fun onWindowState(pkg: String, cls: String, windowId: Int): Screen? {
        if (cls.startsWith("android.widget.Toast")) return null
        val screen = windowScreens[windowId]
            ?: Screen(pkg, cls).also { if (windowId >= 0) windowScreens[windowId] = it }
        lastSeen = screen
        return screen
    }

    /** 스크롤 이벤트가 속한 화면. 모르는 창이면 [current] 와 패키지가 같을 때만 [current]. */
    fun screenOf(pkg: String, windowId: Int, current: Screen?): Screen? =
        windowScreens[windowId] ?: current?.takeIf { it.packageName == pkg }

    /** 일시정지했다 다시 켜면 그동안 바뀐 화면을 알 수 없다. */
    fun forgetLastSeen() {
        lastSeen = null
    }

    /** 지금 앞에 있는 앱 화면. 창 ID 로 아는 화면이거나, 마지막으로 본 화면과 패키지가 같을 때만. */
    fun activeScreen(): Screen? {
        val root = service.rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString()
        val windowId = root.windowId
        @Suppress("DEPRECATION")
        root.recycle()
        if (pkg == null || pkg in ignoredPackages) return null
        return windowScreens[windowId]?.takeIf { it.packageName == pkg } ?: lastSeen?.takeIf { it.packageName == pkg }
    }

    /**
     * 앞에 있는 창에 돌아가는 로딩 표시가 보이는가: 화면에 보이는 진행 정도 없는(indeterminate) ProgressBar.
     * 진행률이 있는 막대(다운로드 %)와 SeekBar 는 로딩으로 보지 않는다. Compose 의 로딩 표시도 같은 이름으로 보인다.
     */
    fun hasLoadingIndicator(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        if (root.packageName?.toString()?.let { isIgnored(it) } == true) {
            @Suppress("DEPRECATION")
            root.recycle()
            return false
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue += root
        var visited = 0
        var found = false
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!found && visited < CaptureConfig.LOADING_SCAN_MAX_NODES) {
                visited++
                if (node.className?.toString() == PROGRESS_BAR && node.rangeInfo == null && node.isVisibleToUser) {
                    found = true
                } else {
                    for (i in 0 until node.childCount) node.getChild(i)?.let { queue += it }
                }
            }
            @Suppress("DEPRECATION")
            node.recycle()
        }
        return found
    }

    /** 무시 목록을 다시 만든다(런처·키보드는 사용자가 바꿀 수 있으므로 켤 때와 기록 시작 때). */
    fun refreshIgnoredPackages() {
        leavePackages.clear()
        leavePackages += service.packageName
        leavePackages += SAMSUNG_LAUNCHER
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        // queryIntentActivities 는 설정 앱의 FallbackHome 까지 돌려주므로 기본 런처 하나만 쓴다
        @Suppress("DEPRECATION")
        service.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            ?.takeIf { it != "android" && it != "com.android.settings" }
            ?.let { leavePackages += it }

        ignoredPackages.clear()
        ignoredPackages += leavePackages
        ignoredPackages += SYSTEM_PACKAGES
        service.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { ignoredPackages += it.packageName }
    }

    private companion object {
        const val MAX_WINDOWS = 256
        const val PROGRESS_BAR = "android.widget.ProgressBar"
        const val SAMSUNG_LAUNCHER = "com.sec.android.app.launcher"
        val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.samsung.android.app.cocktailbarservice", // 엣지 패널
            "com.samsung.android.app.smartcapture", // 스크린샷 툴바
        )
    }
}
