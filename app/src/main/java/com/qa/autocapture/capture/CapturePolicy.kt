package com.qa.autocapture.capture

import kotlin.math.abs

/**
 * 캡처 관련 상수. 값을 바꿀 때는 여기만 고친다.
 * SETTLE_MS·MAX_WAIT_MS 는 기본값이고, 설정 화면에서 바꾼 값(Options)이 우선한다.
 */
object CaptureConfig {
    /** 화면 이벤트가 이만큼 없으면 "조용해짐"으로 보고 정지 확인을 시작한다. */
    const val SETTLE_MS = 1000L

    /** 정지 확인: 이 간격을 두고 찍은 두 화면이 같아야 "정적으로 완성됨"으로 본다. */
    const val CONFIRM_MS = 500L

    /** 터치·화면 진입 뒤 계속 움직여(동영상, 애니메이션) 정지하지 않아도 이만큼 지나면 그 시점 화면을 찍는다. */
    const val MAX_WAIT_MS = 8000L

    /** 설정 화면에서 고를 수 있는 범위와 간격. 두 범위가 겹치지 않아 항상 SETTLE ≤ MAX_WAIT 이다. */
    const val SETTLE_MIN_MS = 500L
    const val SETTLE_MAX_MS = 3000L
    const val SETTLE_STEP_MS = 100L
    const val MAX_WAIT_MIN_MS = 3000L
    const val MAX_WAIT_MAX_MS = 30_000L
    const val MAX_WAIT_STEP_MS = 500L

    /**
     * 화면에 들어온 뒤 이만큼 조용하면 미리 1장을 확보한다(곧바로 스크롤해도 화면 맨 위가 빠지지 않게).
     * 정지 확인으로 완성된 화면을 찍으면 이 1장을 대신한다.
     */
    const val ENTRY_PREVIEW_MS = 400L

    /** 앱이 스스로 바뀐 경우(터치 없음) 이 비율 이상 바뀌어야 새 화면으로 보고 찍는다(배너·시계 무시). */
    const val PAGE_CHANGE_RATIO = 0.3

    /** 앱이 스스로 바뀐 것을 확인해 보고 찍을 게 없었으면 이만큼은 다시 확인하지 않는다. */
    const val CHANGE_COOLDOWN_MS = 3000L

    /** takeScreenshot 최소 간격(시스템 제한 333ms + 여유). */
    const val MIN_SHOT_INTERVAL_MS = 350L

    /** 오버레이를 숨긴 뒤 화면에 반영될 때까지 기다리는 시간. */
    const val HIDE_SETTLE_MS = 80L

    /** 저장을 기다리는 화면이 이만큼 쌓이면 다음 촬영을 잠시 미룬다(메모리 보호). */
    const val MAX_PENDING_SAVES = 3

    /** 저장이 밀려 촬영을 미뤘을 때 다시 확인하는 간격. */
    const val BACKLOG_RETRY_MS = 100L

    // ── 화면 비교(축소 이미지) ────────────────────────────────────

    /** 비교용 축소 이미지의 가로 칸 수(폭 1080px 이면 칸 1개 = 15x15px). */
    const val THUMB_COLUMNS = 72

    /** 칸 평균색이 채널(R/G/B) 중 하나라도 이 값보다 크게 다르면 "바뀐 칸"으로 센다. */
    const val CELL_DIFF = 24

    /** 바뀐 칸이 이 비율 이하이면 "정지"(깜빡이는 커서 정도는 허용, 돌아가는 로딩 표시는 정지 아님). */
    const val STATIC_MAX_CHANGED_RATIO = 0.002

    /** 바뀐 칸이 이 비율 이하이면 "작은 변화": 새 파일을 만들지 않고 그 화면 파일을 최신 상태로 바꾼다. */
    const val SIMILAR_MAX_CHANGED_RATIO = 0.01

    /** 버튼이 보이는 채로 찍은 비교 화면에서 버튼 둘레로 더 빼는 여백(px, 그림자·경계). */
    const val OVERLAY_MASK_MARGIN_PX = 12

    // ── 스크롤 이어붙이기 ─────────────────────────────────────────

    /** 스크롤 이벤트와 내용 변경이 이만큼 없으면 "스크롤이 멈춤"으로 보고 이어붙일 프레임 1장을 찍는다. */
    const val SCROLL_SETTLE_MS = 400L

    /** 스크롤이 멈춘 뒤 내용 변경이 계속돼도(이미지 로딩, 배너) 마지막 스크롤 이벤트 후 이만큼 지나면 찍는다. */
    const val SCROLL_MAX_WAIT_MS = 1500L

    /** 이어붙인 이미지 1장의 최대 높이(px). 넘으면 프레임 경계에서 다음 장으로 나눈다. */
    const val MAX_STITCH_HEIGHT = 20_000

    /** 행 비교에서 뺄 오른쪽 스크롤바 자리(폭 대비 비율). */
    const val STITCH_EDGE_MARGIN_RATIO = 0.03

    /** 행 해시를 만들 때 가로 샘플링 간격(px). */
    const val STITCH_SAMPLE_STEP = 2

    /** 행 비교로 찾은 겹침을 인정하기 위한 최소 득표 수(검증에 쓰는 최소 행 수도 같음). */
    const val STITCH_MIN_VOTES = 10

    /** 행 비교로 찾은 겹침 검증: 겹치는 구간에서 움직인 행 중 맞아야 하는 최소 비율. */
    const val STITCH_MIN_MATCH_RATIO = 0.5

    /** scrollDeltaY 로 받은 겹침 검증: 겹치는 구간에서 움직인 행 중 맞아야 하는 최소 비율(독립 근거라 더 엄격하게). */
    const val STITCH_HINT_MIN_MATCH_RATIO = 0.8

    /** 겹침을 못 찾았어도 바뀐 행이 이 비율 이하이면 같은 위치(시계·커서 변화)로 보고 버린다. */
    const val STITCH_SAME_MAX_CHANGED_RATIO = 0.15
}

/**
 * "정적으로 완성된 화면"을 1장 찍기 위한 판단. 시각과 비교 결과를 인자로 받는 순수 로직(JVM 단위 테스트).
 *
 * 1) 시작(화면 진입·터치·입력·스크롤 멈춤 뒤 확인·앱의 큰 변화) 후 이벤트가 settleMs 동안 없으면
 * 2) 버튼을 숨기지 않고 비교용 화면(PROBE)을 찍어 이 화면의 마지막 확보 화면과 비교한다. 바뀐 게 없으면 끝.
 * 3) 바뀌었으면 confirmMs 뒤 버튼을 숨기고 저장용 화면(FINAL)을 찍어 직전 화면과 비교한다. 같으면 확정.
 *    다르면(아직 움직임) confirmMs 마다 PROBE 를 찍어 직전 것과 같아지면 다시 FINAL.
 * 4) 터치·진입(ACTION)은 시작 후 maxWaitMs 가 지나면 FINAL 을 찍어 그대로 확정한다. 다른 종류는 그냥 끝낸다.
 * 이벤트가 오면 1) 로 돌아간다. 화면 진입이면 조용해진 지 previewMs 뒤 미리 1장(PREVIEW)을 확보한다.
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
            waiting = false
            nextAt = 0
            previewWanted = preview
        } else if (kind.ordinal < current.ordinal) {
            this.kind = kind
        }
        onActivity(now)
    }

    /** 화면이 아직 바뀌는 중(이벤트). 비교를 처음부터 다시 한다. */
    fun onActivity(now: Long) {
        if (kind == null) return
        if (now > lastActivity) lastActivity = now
        generation++
        waiting = false
        ref = null
        refMask = null
        refStable = false
    }

    fun cancel() {
        kind = null
        waiting = false
        generation++
    }

    fun evaluate(now: Long, settleMs: Long, confirmMs: Long, maxWaitMs: Long, previewMs: Long = CaptureConfig.ENTRY_PREVIEW_MS): Decision {
        val k = kind ?: return Decision.Idle
        if (waiting) return Decision.Idle
        val forceAt = startedAt + maxWaitMs
        if (now >= forceAt) {
            if (k != Kind.ACTION) {
                kind = null // 끝내 정지하지 않음: 터치·진입이 아니면 찍지 않는다
                return Decision.Idle
            }
            return take(Shot.FINAL, forced = true)
        }
        if (previewWanted) {
            val previewAt = lastActivity + previewMs
            if (now >= previewAt) {
                previewWanted = false
                return take(Shot.PREVIEW, forced = false)
            }
            return Decision.Wait(minOf(previewAt, forceAt))
        }
        val readyAt = maxOf(lastActivity + settleMs, nextAt)
        if (now < readyAt) return Decision.Wait(minOf(readyAt, forceAt))
        return take(if (ref != null && refStable) Shot.FINAL else Shot.PROBE, forced = false)
    }

    private fun take(shot: Shot, forced: Boolean): Decision {
        waiting = true
        forcedShot = forced
        return Decision.Take(shot, generation)
    }

    /** 촬영이 실패했다. 조금 뒤 다시 판단한다. */
    fun onShotFailed(generation: Int, now: Long, confirmMs: Long) {
        if (generation != this.generation || kind == null) return
        waiting = false
        nextAt = now + confirmMs
    }

    /**
     * 촬영 결과. [mask] = 버튼이 찍힌 영역(PROBE), [last] = 이 화면에서 마지막으로 확보한 화면(없으면 null).
     * [generation] 이 다르면(그 사이 이벤트·취소) 무시한다.
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
        val k = kind
        if (generation != this.generation || k == null) return Outcome.Continue
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

/**
 * 화면 비교용 축소 이미지. Android 의존성 없는 순수 로직.
 *
 * 화면을 정사각형 칸으로 나눠 칸마다 평균색을 구하고, 평균색이 [CaptureConfig.CELL_DIFF] 보다 크게 다른 칸 수를 센다.
 */
object FrameDiff {

    /** 칸별 평균색(0xRRGGBB)을 행 우선으로 담은 축소 이미지. [top] 행부터 [cell] px 칸. */
    class Thumb(val columns: Int, val rows: Int, val cells: IntArray, val cell: Int, val top: Int)

    /** 화면 좌표(px) 사각형 [left, right) x [top, bottom). 비교에서 뺄 영역(플로팅 버튼). */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        companion object {
            fun union(a: Box?, b: Box?): Box? = when {
                a == null -> b
                b == null -> a
                else -> Box(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
            }
        }
    }

    /**
     * 행 [top, bottom) 범위를 축소한다. 상태바·내비게이션바는 범위에서 빼서 시계 같은 변화는 보지 않는다.
     *
     * @param readRows (y, count, into) — y 부터 count 행의 ARGB 픽셀을 into 에 채운다(행 폭 = [width]).
     */
    fun thumbnail(width: Int, top: Int, bottom: Int, readRows: (y: Int, count: Int, into: IntArray) -> Unit): Thumb {
        val cell = maxOf(1, width / CaptureConfig.THUMB_COLUMNS)
        val columns = (width + cell - 1) / cell
        val rows = maxOf(0, (bottom - top + cell - 1) / cell)
        val cells = IntArray(columns * rows)
        val strip = IntArray(width * cell)
        for (r in 0 until rows) {
            val y0 = top + r * cell
            val n = minOf(cell, bottom - y0)
            readRows(y0, n, strip)
            for (c in 0 until columns) {
                val x0 = c * cell
                val x1 = minOf(width, x0 + cell)
                var sr = 0L
                var sg = 0L
                var sb = 0L
                for (dy in 0 until n) {
                    val off = dy * width
                    for (x in x0 until x1) {
                        val p = strip[off + x]
                        sr += (p shr 16) and 0xFF
                        sg += (p shr 8) and 0xFF
                        sb += p and 0xFF
                    }
                }
                val count = n * (x1 - x0)
                cells[r * columns + c] = ((sr / count).toInt() shl 16) or ((sg / count).toInt() shl 8) or (sb / count).toInt()
            }
        }
        return Thumb(columns, rows, cells, cell, top)
    }

    /** 바뀐 칸 비율(0~1). [ignore] 와 겹치는 칸은 세지 않는다. 크기가 다르면(회전 등) 1. */
    fun changeRatio(a: Thumb, b: Thumb, ignore: Box? = null): Double {
        if (a.columns != b.columns || a.rows != b.rows || a.cells.isEmpty()) return 1.0
        var c0 = Int.MAX_VALUE
        var c1 = -1
        var r0 = Int.MAX_VALUE
        var r1 = -1
        if (ignore != null) {
            c0 = maxOf(0, ignore.left / a.cell)
            c1 = minOf(a.columns - 1, (ignore.right - 1) / a.cell)
            r0 = maxOf(0, (ignore.top - a.top) / a.cell)
            r1 = minOf(a.rows - 1, (ignore.bottom - 1 - a.top) / a.cell)
        }
        var changed = 0
        for (i in a.cells.indices) {
            val p = a.cells[i]
            val q = b.cells[i]
            if (p == q) continue
            val r = i / a.columns
            val c = i % a.columns
            if (r in r0..r1 && c in c0..c1) continue
            val dr = abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF))
            val dg = abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF))
            val db = abs((p and 0xFF) - (q and 0xFF))
            if (dr > CaptureConfig.CELL_DIFF || dg > CaptureConfig.CELL_DIFF || db > CaptureConfig.CELL_DIFF) changed++
        }
        return changed.toDouble() / a.cells.size
    }

    /** 정지: 바뀐 칸 ≤ [CaptureConfig.STATIC_MAX_CHANGED_RATIO]. */
    fun static(a: Thumb, b: Thumb, ignore: Box? = null): Boolean =
        changeRatio(a, b, ignore) <= CaptureConfig.STATIC_MAX_CHANGED_RATIO

    /** 작은 변화: 바뀐 칸 ≤ [CaptureConfig.SIMILAR_MAX_CHANGED_RATIO]. */
    fun similar(a: Thumb, b: Thumb): Boolean = changeRatio(a, b) <= CaptureConfig.SIMILAR_MAX_CHANGED_RATIO

    /** 같음: 바뀐 칸 0. */
    fun same(a: Thumb, b: Thumb): Boolean = changeRatio(a, b) == 0.0
}
