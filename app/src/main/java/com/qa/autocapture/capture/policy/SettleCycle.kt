package com.qa.autocapture.capture.policy

/**
 * "정적으로 완성된 화면"을 1장 찍기 위한 판단. 시각과 비교 결과를 인자로 받는 순수 로직(JVM 단위 테스트).
 *
 * 1) 시작(화면 진입·터치·입력·스크롤 멈춤 뒤 확인·앱의 큰 변화) 후 이벤트가 settleMs 동안 없으면
 *    — 이벤트가 계속 와도 시작 후 settleMs + QUIET_WAIT_EXTRA_MS 가 지나면 더 기다리지 않는다(바쁜 앱)
 * 2) 버튼을 숨기지 않고 비교용 화면(PROBE)을 찍어 이 화면의 마지막 확보 화면과 비교한다. 바뀐 게 없으면 끝.
 * 3) 바뀌었으면 confirmMs 뒤 버튼을 숨기고 저장용 화면(FINAL)을 찍어 직전 화면과 비교한다. 같으면 확정.
 *    다르면(아직 움직임) confirmMs 마다 PROBE 를 찍어 직전 것과 같아지면 다시 FINAL.
 * 4) 터치·진입(ACTION)은 시작 후 maxWaitMs 가 지나면 FINAL 을 찍어 그대로 확정한다. 다른 종류는 그냥 끝낸다.
 * 1) 의 기다리는 동안 이벤트가 오면 비교를 처음부터 다시 한다. 그 뒤의 이벤트는 화면 비교 결과에 맡긴다.
 * 화면 진입이면 조용해진 지 previewMs 뒤(늦어도 ENTRY_PREVIEW_MAX_MS) 미리 1장(PREVIEW)을 확보한다.
 */
class SettleCycle {

    /** 시작 이유. 앞일수록 우선(진행 중에 더 앞의 이유가 오면 바꾼다). */
    enum class Kind {
        /** 화면 진입, 터치, 입력: 바뀌었으면 찍는다. 끝내 정지하지 않으면 maxWait 에 찍는다. */
        ACTION,

        /** 스크롤 멈춤 뒤 남은 로딩 확인: 바뀌었으면 방금 프레임을 완성된 화면으로 바꾼다. */
        COMPLETION,

        /** 앱이 스스로 바뀜: 화면 대부분(PAGE_CHANGE_RATIO)이 바뀌었을 때만 찍는다. */
        CHANGE,
    }

    enum class Shot {
        /** 버튼을 숨기고 찍는 미리 확보용 화면(화면 진입 직후). */
        PREVIEW,

        /** 버튼을 숨기지 않고 찍는 비교용 화면(저장 안 함). */
        PROBE,

        /** 버튼을 숨기고 찍는 저장용 화면. */
        FINAL,
    }

    sealed interface Decision {
        data object Idle : Decision

        data class Wait(val at: Long) : Decision

        /** [shot] 을 찍어 [onShot] 에 [generation] 과 함께 돌려준다. */
        data class Take(val shot: Shot, val generation: Int) : Decision
    }

    sealed interface Outcome {
        /** 계속 확인한다. */
        data object Continue : Outcome

        /** 미리 확보: 이 화면을 임시로 저장한다(정지 확인 뒤 바뀔 수 있음). */
        data object Preview : Outcome

        /** 찍을 것이 없다(마지막 확보 화면과 같음, 또는 앱의 작은 변화). */
        data class NoChange(val kind: Kind) : Outcome

        /** 이 FINAL 화면을 확정한다. */
        data class Accept(val kind: Kind, val forced: Boolean) : Outcome
    }

    /** 진행 중인 이유. null 이면 진행 중 아님. */
    var kind: Kind? = null
        private set

    val active: Boolean get() = kind != null

    private var generation = 0
    private var startedAt = 0L
    private var lastActivity = 0L

    /** 이 시각까지는 이벤트를 "아직 바뀌는 중"으로 보고 기다린다. 이후는 화면 비교만으로 판단한다. */
    private var quietDeadline = 0L
    private var nextAt = 0L
    private var waiting = false
    private var previewWanted = false
    private var forcedShot = false
    private var ref: FrameDiff.Thumb? = null
    private var refMask: FrameDiff.Box? = null
    private var refStable = false

    /** [preview] = 화면 진입: 조용해지면 미리 1장을 확보한다. */
    fun start(kind: Kind, now: Long, preview: Boolean = false) {
        val current = this.kind
        if (current == null) {
            this.kind = kind
            startedAt = now
            quietDeadline = now + CaptureConfig.SETTLE_MS + CaptureConfig.QUIET_WAIT_EXTRA_MS
            waiting = false
            nextAt = 0
            previewWanted = preview
            generation++
            clearRef()
        } else if (kind.ordinal < current.ordinal) {
            this.kind = kind
        }
        onActivity(now)
    }

    /** 화면이 아직 바뀌는 중(이벤트). 기다리는 동안이면 비교를 처음부터 다시 한다. */
    fun onActivity(now: Long) {
        if (kind == null) return
        if (now > lastActivity) lastActivity = now
        if (now >= quietDeadline) return // 바쁜 앱: 이벤트 대신 화면 비교로 판단한다
        generation++
        waiting = false
        clearRef()
    }

    fun cancel() {
        kind = null
        waiting = false
        generation++
    }

    fun evaluate(now: Long, settleMs: Long, confirmMs: Long, maxWaitMs: Long, previewMs: Long = CaptureConfig.ENTRY_PREVIEW_MS): Decision {
        val k = kind ?: return Decision.Idle
        if (waiting) return Decision.Idle
        // 강제로 찍을 시각을 넘기지 않는다(그 뒤의 이벤트가 강제 촬영 결과를 버리지 않게)
        quietDeadline = startedAt + minOf(settleMs + CaptureConfig.QUIET_WAIT_EXTRA_MS, maxWaitMs)
        val forceAt = startedAt + maxWaitMs
        if (now >= forceAt) {
            if (k != Kind.ACTION) {
                kind = null // 끝내 정지하지 않음: 터치·진입이 아니면 찍지 않는다
                return Decision.Idle
            }
            return take(Shot.FINAL, forced = true)
        }
        if (previewWanted) {
            val previewAt = minOf(lastActivity + previewMs, startedAt + CaptureConfig.ENTRY_PREVIEW_MAX_MS)
            if (now >= previewAt) {
                previewWanted = false
                return take(Shot.PREVIEW, forced = false)
            }
            return Decision.Wait(minOf(previewAt, forceAt))
        }
        val readyAt = maxOf(minOf(lastActivity + settleMs, quietDeadline), nextAt)
        if (now < readyAt) return Decision.Wait(minOf(readyAt, forceAt))
        return take(if (ref != null && refStable) Shot.FINAL else Shot.PROBE, forced = false)
    }

    private fun take(shot: Shot, forced: Boolean): Decision {
        waiting = true
        forcedShot = forced
        return Decision.Take(shot, generation)
    }

    private fun clearRef() {
        ref = null
        refMask = null
        refStable = false
    }

    /** 촬영이 실패했다. 조금 뒤 다시 판단한다. */
    fun onShotFailed(generation: Int, now: Long, confirmMs: Long) {
        if (generation != this.generation || kind == null) return
        waiting = false
        nextAt = now + confirmMs
    }

    /**
     * 촬영 결과. [mask] = 버튼이 찍힌 영역(PROBE), [last] = 이 화면에서 마지막으로 확보한 화면(없으면 null).
     * [generation] 이 다르면(그 사이 이벤트·취소) 무시한다. 미리 확보(PREVIEW)는 그 사이 이벤트가 있어도 쓴다.
     */
    fun onShot(
        generation: Int,
        shot: Shot,
        thumb: FrameDiff.Thumb,
        mask: FrameDiff.Box?,
        last: FrameDiff.Thumb?,
        now: Long,
        confirmMs: Long,
    ): Outcome {
        val k = kind ?: return Outcome.Continue
        if (shot == Shot.PREVIEW) {
            if (generation == this.generation) waiting = false
            return Outcome.Preview
        }
        if (generation != this.generation) return Outcome.Continue
        waiting = false
        when (shot) {
            Shot.PREVIEW -> return Outcome.Preview
            Shot.PROBE -> {
                val r = ref
                if (r == null) {
                    // 조용해진 뒤 첫 비교 화면: 마지막 확보 화면과 비교해 찍을 필요가 있는지 본다
                    if (last != null) {
                        val ratio = FrameDiff.changeRatio(last, thumb, mask)
                        val nothing = if (k == Kind.CHANGE) ratio < CaptureConfig.PAGE_CHANGE_RATIO else ratio <= CaptureConfig.STATIC_MAX_CHANGED_RATIO
                        if (nothing) {
                            kind = null
                            return Outcome.NoChange(k)
                        }
                    }
                    refStable = true // 다음은 바로 FINAL 로 정지를 확인한다
                } else {
                    refStable = FrameDiff.static(r, thumb, FrameDiff.Box.union(refMask, mask))
                }
                ref = thumb
                refMask = mask
                nextAt = now + confirmMs
                return Outcome.Continue
            }
            Shot.FINAL -> {
                val r = ref
                if (forcedShot || (r != null && FrameDiff.static(r, thumb, refMask))) {
                    kind = null
                    return Outcome.Accept(k, forcedShot)
                }
                ref = thumb
                refMask = null
                refStable = false
                nextAt = now + confirmMs
                return Outcome.Continue
            }
        }
    }
}
