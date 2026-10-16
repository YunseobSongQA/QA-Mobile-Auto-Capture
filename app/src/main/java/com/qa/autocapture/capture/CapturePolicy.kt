package com.qa.autocapture.capture

/**
 * 캡처 관련 상수. 값을 바꿀 때는 여기만 고친다.
 * SETTLE_MS·MAX_WAIT_MS 는 기본값이고, 설정 화면에서 바꾼 값(Options)이 우선한다.
 */
object CaptureConfig {
    /** 화면 변화가 이만큼 없으면 최종 화면(_final)을 1장 더 찍는다. */
    const val SETTLE_MS = 1500L

    /** 변화가 계속돼도 변화가 시작된 뒤 이만큼 지나면 그 시점 화면을 _final 로 찍는다. */
    const val MAX_WAIT_MS = 8000L

    /** 설정 화면에서 고를 수 있는 범위와 간격. 두 범위가 겹치지 않아 항상 SETTLE ≤ MAX_WAIT 이다. */
    const val SETTLE_MIN_MS = 500L
    const val SETTLE_MAX_MS = 3000L
    const val SETTLE_STEP_MS = 100L
    const val MAX_WAIT_MIN_MS = 3000L
    const val MAX_WAIT_MAX_MS = 30_000L
    const val MAX_WAIT_STEP_MS = 500L

    /** takeScreenshot 최소 간격(시스템 제한 333ms + 여유). 변화가 이어지면 이 간격으로 계속 찍는다. */
    const val MIN_SHOT_INTERVAL_MS = 350L

    /** 오버레이를 숨긴 뒤 화면에 반영될 때까지 기다리는 시간. */
    const val HIDE_SETTLE_MS = 80L

    /** 저장을 기다리는 캡처가 이만큼 쌓이면 다음 캡처를 잠시 미룬다(메모리 보호). */
    const val MAX_PENDING_SAVES = 3

    /** 저장이 밀려 캡처를 미뤘을 때 다시 확인하는 간격. */
    const val BACKLOG_RETRY_MS = 100L

    /** 비교용 축소 이미지의 가로 칸 수(폭 1080px 이면 칸 1개 = 15x15px). */
    const val THUMB_COLUMNS = 72

    /** 칸 평균색이 채널(R/G/B) 중 하나라도 이 값보다 크게 다르면 "바뀐 칸"으로 센다. */
    const val CELL_DIFF = 24

    /** 바뀐 칸이 전체의 이 비율 이하이면 직전 저장 이미지와 "거의 같음" → 저장 생략. */
    const val SIMILAR_MAX_CHANGED_RATIO = 0.01
}

/**
 * 최종 화면(_final) 캡처 시점 판단. 시각을 인자로 받는 순수 로직이라 JVM 단위 테스트로 검증한다.
 *
 * - 마지막 변화 후 settleMs 동안 변화가 없으면 → 최종 캡처
 * - 변화가 이어져 첫 변화 후 maxWaitMs 가 지나면 → 그 시점에 최종 캡처. 이후는 그때부터 다시 잰다.
 */
class FinalTimer {

    sealed interface Decision {
        /** 기다리는 최종 캡처가 없다. */
        data object Idle : Decision

        /** [at] 에 다시 [evaluate] 한다. */
        data class Wait(val at: Long) : Decision

        /** 지금 최종 화면을 찍는다. [forced] = 변화가 계속되는 중에 maxWaitMs 가 지남. */
        data class Final(val forced: Boolean) : Decision
    }

    private var burstStart = NONE
    private var lastChange = NONE

    /** 화면 변화가 있었다. */
    fun onChange(now: Long) {
        if (burstStart == NONE) burstStart = now
        if (now > lastChange) lastChange = now
    }

    /** 기다리던 최종 캡처를 버린다(화면 전환·화면 이탈·세션 정지). */
    fun cancel() {
        burstStart = NONE
    }

    /** [Decision.Final] 을 돌려주면 그 판단은 소진된다. */
    fun evaluate(now: Long, settleMs: Long, maxWaitMs: Long): Decision {
        if (burstStart == NONE) return Decision.Idle
        val settleAt = lastChange + settleMs
        val forceAt = burstStart + maxWaitMs
        return when {
            now >= settleAt -> {
                burstStart = NONE
                Decision.Final(forced = false)
            }
            now >= forceAt -> {
                burstStart = now
                Decision.Final(forced = true)
            }
            else -> Decision.Wait(minOf(settleAt, forceAt))
        }
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}

/**
 * "직전 저장 이미지와 거의 같은가" 판단용 축소 이미지 비교. Android 의존성 없는 순수 로직.
 *
 * 화면을 정사각형 칸으로 나눠 칸마다 평균색을 구하고, 평균색이 [CaptureConfig.CELL_DIFF] 보다 크게 다른 칸 수를 센다.
 */
object FrameDiff {

    /** 칸별 평균색(0xRRGGBB)을 행 우선으로 담은 축소 이미지. */
    class Thumb(val columns: Int, val rows: Int, val cells: IntArray)

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
        return Thumb(columns, rows, cells)
    }

    /** 바뀐 칸 수. 크기가 다르면(회전 등) 전체 칸 수보다 큰 값을 돌려준다. */
    fun changedCells(a: Thumb, b: Thumb): Int {
        if (a.columns != b.columns || a.rows != b.rows) return Int.MAX_VALUE
        var changed = 0
        for (i in a.cells.indices) {
            val p = a.cells[i]
            val q = b.cells[i]
            if (p == q) continue
            val dr = kotlin.math.abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF))
            val dg = kotlin.math.abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF))
            val db = kotlin.math.abs((p and 0xFF) - (q and 0xFF))
            if (dr > CaptureConfig.CELL_DIFF || dg > CaptureConfig.CELL_DIFF || db > CaptureConfig.CELL_DIFF) changed++
        }
        return changed
    }

    /** 거의 같음: 바뀐 칸이 [CaptureConfig.SIMILAR_MAX_CHANGED_RATIO] 이하. 변화 캡처의 저장 생략에 쓴다. */
    fun similar(a: Thumb, b: Thumb): Boolean =
        changedCells(a, b) <= a.cells.size * CaptureConfig.SIMILAR_MAX_CHANGED_RATIO

    /** 같음: 바뀐 칸이 하나도 없음. 최종 캡처는 이때만 새로 저장하지 않는다(작은 체크박스 변화도 남기기 위해). */
    fun same(a: Thumb, b: Thumb): Boolean = changedCells(a, b) == 0
}
