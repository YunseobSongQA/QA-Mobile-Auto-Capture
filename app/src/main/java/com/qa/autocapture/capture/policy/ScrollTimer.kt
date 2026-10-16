package com.qa.autocapture.capture.policy

import kotlin.math.abs

/**
 * 스크롤 이어붙이기 모드 판단. 시각을 인자로 받는 순수 로직.
 *
 * 세로 스크롤 이벤트가 오면 모드를 시작한다.
 * - 스크롤하는 동안: 이동량이 stepPx 를 넘을 때마다(이동량을 모르면 stepMs 마다) 중간 프레임 1장([Decision.Step]).
 *   한 번에 화면 높이보다 많이 넘겨도 프레임끼리 겹쳐서 한 장으로 이어붙일 수 있다.
 * - 스크롤 이벤트와 내용 변경이 settleMs 동안 없거나, 마지막 스크롤 이벤트 후 maxWaitMs 가 지나면
 *   멈춘 화면 1장([Decision.Capture])을 찍고 모드를 끝낸다.
 */
class ScrollTimer {

    sealed interface Decision {
        data object Idle : Decision

        data class Wait(val at: Long) : Decision

        /** 스크롤하는 중이다. 이어붙일 중간 프레임 1장을 찍는다(찍으면 [onStepTaken]). */
        data object Step : Decision

        /** 스크롤이 멈췄다. 이어붙일 프레임 1장을 찍는다. */
        data object Capture : Decision
    }

    /** 이어붙이기 모드(스크롤 중이거나 멈춘 뒤 프레임을 기다리는 중). */
    var active = false
        private set

    private var lastScroll = 0L
    private var lastActivity = 0L

    /** 마지막 프레임(또는 스크롤 시작) 이후 이동량 합과, 이동량을 모르는 스크롤이 있었는지. */
    private var movedPx = 0
    private var unknownMove = false
    private var frameAt = 0L
    private var stepPending = false

    /** [dy] = 이 이벤트의 세로 이동량(px). 모르면 null. */
    fun onScroll(now: Long, dy: Int? = null) {
        if (!active) {
            active = true
            movedPx = 0
            unknownMove = false
            frameAt = now
            stepPending = false
        }
        lastScroll = now
        if (now > lastActivity) lastActivity = now
        if (dy == null) unknownMove = true else movedPx += abs(dy)
    }

    /** 스크롤 중 내용 변경(새 항목 그리기, 이미지 로딩)은 멈춤 대기에 포함한다. */
    fun onActivity(now: Long) {
        if (active && now > lastActivity) lastActivity = now
    }

    /** 중간 프레임을 찍었다(찍은 시각). 이동량을 다시 센다. */
    fun onStepTaken(now: Long) {
        stepPending = false
        movedPx = 0
        unknownMove = false
        frameAt = now
    }

    /** 중간 프레임을 찍지 못했다. 다음 판단 때 다시 요청한다. */
    fun onStepDropped() {
        stepPending = false
    }

    fun cancel() {
        active = false
        stepPending = false
    }

    fun evaluate(now: Long, settleMs: Long, maxWaitMs: Long, stepPx: Int = Int.MAX_VALUE, stepMs: Long = Long.MAX_VALUE / 2): Decision {
        if (!active) return Decision.Idle
        val stopAt = minOf(lastActivity + settleMs, lastScroll + maxWaitMs)
        if (now >= stopAt) {
            active = false
            stepPending = false
            return Decision.Capture
        }
        if (!stepPending) {
            if (movedPx >= stepPx || (unknownMove && now >= frameAt + stepMs)) {
                stepPending = true
                return Decision.Step
            }
            if (unknownMove) return Decision.Wait(minOf(stopAt, frameAt + stepMs))
        }
        return Decision.Wait(stopAt)
    }
}
