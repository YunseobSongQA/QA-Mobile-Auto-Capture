package com.qa.autocapture.storage

import android.content.Context
import android.content.SharedPreferences
import com.qa.autocapture.capture.policy.CaptureConfig

/** 설정 값. 서비스·화면·빠른 설정 타일이 같은 프로세스라 SharedPreferences 로 공유한다(재부팅 후에도 유지). */
class Options(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("options", Context.MODE_PRIVATE)

    /** 마스터 스위치. 끄면 접근성 서비스는 켜 둔 채 플로팅 버튼을 숨기고 이벤트 처리·캡처를 모두 멈춘다. */
    var captureEnabled: Boolean
        get() = prefs.getBoolean(KEY_CAPTURE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CAPTURE_ENABLED, value).apply()

    /** 이 시간 동안 화면 이벤트가 없으면 정지 확인을 거쳐 완성된 화면을 찍는다. */
    var settleMs: Long
        get() = prefs.getLong(KEY_SETTLE_MS, CaptureConfig.SETTLE_MS).coerceIn(CaptureConfig.SETTLE_MIN_MS, CaptureConfig.SETTLE_MAX_MS)
        set(value) = prefs.edit().putLong(KEY_SETTLE_MS, value).apply()

    /** 화면 진입·터치 뒤 계속 움직여도 이 시간이 지나면 그 시점 화면을 찍는다. */
    var maxWaitMs: Long
        get() = prefs.getLong(KEY_MAX_WAIT_MS, CaptureConfig.MAX_WAIT_MS).coerceIn(CaptureConfig.MAX_WAIT_MIN_MS, CaptureConfig.MAX_WAIT_MAX_MS)
        set(value) = prefs.edit().putLong(KEY_MAX_WAIT_MS, value).apply()

    /** 기록 중 받은 접근성 이벤트를 디버그 로그로 저장한다. */
    var debugLog: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_LOG, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_LOG, value).apply()

    fun resetTiming() {
        prefs.edit().remove(KEY_SETTLE_MS).remove(KEY_MAX_WAIT_MS).apply()
    }

    /** 리스너는 약한 참조로 등록되므로 호출자가 필드로 들고 있어야 한다. 콜백은 메인 스레드에서 온다. */
    fun listen(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unlisten(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val KEY_CAPTURE_ENABLED = "capture_enabled"
        private const val KEY_SETTLE_MS = "settle_ms"
        private const val KEY_MAX_WAIT_MS = "max_wait_ms"
        private const val KEY_DEBUG_LOG = "debug_log"
    }
}
