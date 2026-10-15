package com.qa.autocapture.capture

/** 캡처 타이밍 상수. 값을 바꿀 때는 여기만 고친다. */
object CaptureTiming {
    /** 화면 변화(내용 변경·스크롤·창 전환)가 이만큼 없으면 "안정"으로 본다. */
    const val SETTLE_MS = 1000L

    /** 캡처 요청 후 이만큼 지나면 안정 여부와 상관없이 찍는다(자동 롤링 배너 등). */
    const val MAX_WAIT_MS = 5000L

    /** 토스트 감지 후 캡처를 보류하는 시간(Toast.LENGTH_LONG = 3.5초). 꺼짐 이벤트가 없어 시간으로 판단한다. */
    const val TOAST_WAIT_MS = 3500L

    /** 로딩 표시(ProgressBar)가 보이는 동안 다시 확인하는 간격. */
    const val PROGRESS_POLL_MS = 300L

    /** "토스트 순간 별도 저장" 시 토스트가 나타나는 애니메이션을 기다리는 시간. */
    const val TOAST_SHOT_DELAY_MS = 400L

    /** 오버레이를 숨긴 뒤 화면에 반영될 때까지 기다리는 시간. */
    const val HIDE_SETTLE_MS = 80L

    /** takeScreenshot 최소 간격(시스템 제한 333ms + 여유). */
    const val MIN_SHOT_INTERVAL_MS = 350L
}

/**
 * "화면 안정화 후 캡처" 판단. 시각을 인자로 받는 순수 로직이라 JVM 단위 테스트로 검증한다.
 *
 * 캡처 요청(화면 진입·스크롤)이 들어오면 다음을 모두 만족할 때 찍는다.
 * 1. 토스트 보류 시간이 지났다
 * 2. 마지막 화면 변화 후 [settleMs] 가 지났다
 * 3. 로딩 표시가 보이지 않는다
 * 요청 후 [maxWaitMs] 가 지나면 2·3 과 상관없이 찍는다. 토스트는 그 뒤에도 피하되
 * 계속 떠 있어도 [maxWaitMs] + [toastWaitMs] 를 넘기지는 않는다.
 */
class Stabilizer(
    private val settleMs: Long = CaptureTiming.SETTLE_MS,
    private val maxWaitMs: Long = CaptureTiming.MAX_WAIT_MS,
    private val toastWaitMs: Long = CaptureTiming.TOAST_WAIT_MS,
    private val progressPollMs: Long = CaptureTiming.PROGRESS_POLL_MS,
) {
    sealed interface Decision {
        /** 기다리는 요청이 없다. */
        data object Idle : Decision

        /** [at] 에 다시 [evaluate] 한다. */
        data class Wait(val at: Long, val reason: String) : Decision

        /** 지금 찍는다. [forced] = 안정되지 않았지만 최대 대기 시간이 지남. */
        data class Capture(val forced: Boolean, val waitedMs: Long) : Decision
    }

    private var requestedAt = NONE
    private var lastActivity = Long.MIN_VALUE / 2
    private var toastUntil = Long.MIN_VALUE / 2

    val pending: Boolean get() = requestedAt != NONE

    /** 캡처가 필요하다(화면 진입·스크롤). 이미 기다리는 중이면 처음 요청 시각을 유지한다. 요청 자체도 화면 변화로 본다. */
    fun request(now: Long) {
        if (requestedAt == NONE) requestedAt = now
        onActivity(now)
    }

    /** 화면 내용이 바뀌었다(TYPE_WINDOW_CONTENT_CHANGED 등). */
    fun onActivity(now: Long) {
        if (now > lastActivity) lastActivity = now
    }

    /** 토스트가 떴다. 화면이 바뀌어도 토스트는 남아 있으므로 [cancel] 해도 유지한다. */
    fun onToast(now: Long) {
        if (now + toastWaitMs > toastUntil) toastUntil = now + toastWaitMs
    }

    /** 기다리던 요청을 버린다(화면 전환·세션 정지). */
    fun cancel() {
        requestedAt = NONE
    }

    /**
     * 지금 찍을지 판단한다. [progressVisible] 은 시간 조건을 모두 통과했을 때만 부른다(노드 트리 탐색 비용).
     * [Decision.Capture] 를 돌려주면 요청은 소진된다.
     */
    fun evaluate(now: Long, progressVisible: () -> Boolean): Decision {
        if (requestedAt == NONE) return Decision.Idle
        val deadline = requestedAt + maxWaitMs
        val hardDeadline = deadline + toastWaitMs
        return when {
            now >= hardDeadline -> capture(now, forced = true)
            now < toastUntil -> Decision.Wait(minOf(toastUntil, hardDeadline), "toast")
            now >= deadline -> capture(now, forced = true)
            now < lastActivity + settleMs -> Decision.Wait(minOf(lastActivity + settleMs, deadline), "settle")
            progressVisible() -> Decision.Wait(minOf(now + progressPollMs, deadline), "progress")
            else -> capture(now, forced = false)
        }
    }

    private fun capture(now: Long, forced: Boolean): Decision {
        val waited = now - requestedAt
        requestedAt = NONE
        return Decision.Capture(forced, waited)
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
