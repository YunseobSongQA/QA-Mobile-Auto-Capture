package com.qa.autocapture.capture

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager

/**
 * 메인 화면·빠른 설정 타일이 보는 캡처 서비스 상태. 같은 프로세스라 메모리로 공유한다.
 * 쓰기는 CaptureService 만 하고, 모두 메인 스레드에서 다룬다.
 */
object CaptureStatus {

    /** 서비스가 시스템에 연결돼 동작 중인가. */
    var connected = false
        internal set

    /** 기록 중이면 사진을 찍은 화면 수, 아니면 null. */
    var recordingScreens: Int? = null
        internal set

    private val listeners = LinkedHashSet<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    internal fun notifyChanged() {
        for (l in listeners.toList()) l()
    }

    /** 시스템 설정에서 이 접근성 서비스가 켜져 있는가. */
    fun isServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { isThisService(context, it) }
    }

    /** 시스템 접근성 설정에서 이 서비스가 어떤 상태인가. */
    enum class Registration {
        /** 켜져 있음. */
        ENABLED,

        /** 접근성 → 설치된 앱 목록에 있음(꺼짐). */
        LISTED,

        /** 앱에는 서비스가 있지만 시스템이 접근성 목록에 올리지 않음(설치된 앱 목록에 안 보임). */
        NOT_LISTED,
    }

    /**
     * 설정 앱의 "설치된 앱" 목록과 같은 AccessibilityManager.getInstalledAccessibilityServiceList 로 확인한다.
     * NOT_LISTED 면 사용자는 접근성 설정에서 이 앱을 찾을 수 없다.
     */
    fun registration(context: Context): Registration {
        if (isServiceEnabled(context)) return Registration.ENABLED
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return Registration.NOT_LISTED
        return if (am.installedAccessibilityServiceList.any { isThisService(context, it) }) Registration.LISTED else Registration.NOT_LISTED
    }

    private fun isThisService(context: Context, info: AccessibilityServiceInfo): Boolean {
        val s = info.resolveInfo.serviceInfo
        return s.packageName == context.packageName && s.name == CaptureService::class.java.name
    }
}
