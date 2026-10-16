package com.qa.autocapture.capture.policy

import kotlin.math.abs

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
