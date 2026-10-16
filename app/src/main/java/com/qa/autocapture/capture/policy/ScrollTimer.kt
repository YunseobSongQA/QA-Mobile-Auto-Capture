package com.qa.autocapture.capture.policy

/**
 * 스크롤 이어붙이기 모드와 "스크롤이 멈춤" 판단. 시각을 인자로 받는 순수 로직.
 *
 * 세로 스크롤 이벤트가 오면 모드를 시작한다. 스크롤 이벤트와 내용 변경이 settleMs 동안 없거나,
 * 마지막 스크롤 이벤트 후 maxWaitMs 가 지나면 프레임 1장을 찍고 모드를 끝낸다.
 */
class ScrollTimer {

    sealed interface Decision {
        data object Idle : Decision

        data class Wait(val at: Long) : Decision

        /** 스크롤이 멈췄다. 이어붙일 프레임 1장을 찍는다. */
        data object Capture : Decision
    }

    /** 이어붙이기 모드(스크롤 중이거나 멈춘 뒤 프레임을 기다리는 중). */
    var active = false
        private set

    private var lastScroll = 0L
    private var lastActivity = 0L

    fun onScroll(now: Long) {
        active = true
        lastScroll = now
        if (now > lastActivity) lastActivity = now
    }

    /** 스크롤 중 내용 변경(새 항목 그리기, 이미지 로딩)은 멈춤 대기에 포함한다. */
    fun onActivity(now: Long) {
        if (active && now > lastActivity) lastActivity = now
    }

    fun cancel() {
        active = false
    }

    fun evaluate(now: Long, settleMs: Long, maxWaitMs: Long): Decision {
        if (!active) return Decision.Idle
        val at = minOf(lastActivity + settleMs, lastScroll + maxWaitMs)
        if (now < at) return Decision.Wait(at)
        active = false
        return Decision.Capture
    }
}
