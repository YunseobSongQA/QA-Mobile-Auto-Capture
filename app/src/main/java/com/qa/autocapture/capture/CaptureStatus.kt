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

    /** 기록 중이면 확보한 이미지 수, 아니면 null. */
    var recordingImages: Int? = null
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
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val info = it.resolveInfo.serviceInfo
            info.packageName == context.packageName && info.name == CaptureService::class.java.name
        }
    }
}
