package com.qa.autocapture.capture

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import com.qa.autocapture.storage.StoragePaths
import com.qa.autocapture.storage.TextLog

/**
 * 디버그 로그(설정 화면에서 켬): 기록 중 받은 접근성 이벤트와 서비스의 판단을 한 줄씩 남긴다.
 * 저장 위치 Documents/QAEvidence/debug/{세션}.txt. 판단 태그는 #SHOT, #FRAME, #NOCHANGE, #SCROLL, #SHOT_FAIL.
 */
class DebugLog(context: Context, sessionId: String) {

    private val log = TextLog(
        context,
        StoragePaths.DEBUG_PATH,
        StoragePaths.debugLogFile(sessionId),
        listOf("# QA 증적 캡처 디버그 로그 / 세션 $sessionId", "# 시각\t이벤트 타입\t패키지\t클래스\t텍스트\t기타"),
    )

    /** 접근성 이벤트 1개. 이벤트 객체는 재사용되므로 값을 바로 꺼내 쓴다. */
    fun event(e: AccessibilityEvent) {
        val extra = buildString {
            append("win=").append(e.windowId)
            when (e.eventType) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                    append(" change=").append(e.contentChangeTypes)
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> append(" dx=").append(e.scrollDeltaX).append(" dy=").append(e.scrollDeltaY)
            }
        }
        val type = AccessibilityEvent.eventTypeToString(e.eventType)
        log.write(System.currentTimeMillis(), type, e.packageName, e.className, eventText(e).take(MAX_TEXT), extra)
    }

    /** 서비스 내부 판단(촬영, 확보, 스크롤 시작·멈춤, 실패). */
    fun note(tag: String, screen: Screen?, message: String) {
        log.write(System.currentTimeMillis(), tag, screen?.packageName, screen?.className, message)
    }

    fun close() = log.close()

    companion object {
        private const val MAX_TEXT = 500

        fun eventText(event: AccessibilityEvent): String =
            event.text.joinToString(" ").ifEmpty { event.contentDescription?.toString() ?: "" }
    }
}
