package com.qa.autocapture.stitch

import java.io.OutputStream

/**
 * 합성 계획의 part 하나를 PNG 로 쓴다.
 *
 * @param load 프레임 번호의 ARGB 픽셀을 주어진 배열(width*frameHeight)에 채운다.
 *             최근 2장은 캐시해 같은 프레임을 반복해서 읽지 않는다.
 */
fun renderPart(part: Stitcher.Part, out: OutputStream, load: (frame: Int, into: IntArray) -> Unit) {
    val w = part.width
    val cacheFrame = intArrayOf(-1, -1)
    val cache = arrayOfNulls<IntArray>(2)
    var victim = 0
    PngWriter(out, w, part.height).use { png ->
        for (slice in part.slices) {
            if (slice.isGap) {
                for (y in 0 until slice.height) png.writeRow(gapRow(w, y, slice.height), 0)
                continue
            }
            var idx = cacheFrame.indexOf(slice.frame)
            if (idx < 0) {
                idx = victim
                victim = 1 - victim
                val buf = cache[idx] ?: IntArray(w * part.frameHeight).also { cache[idx] = it }
                load(slice.frame, buf)
                cacheFrame[idx] = slice.frame
            } else {
                victim = 1 - idx
            }
            val px = cache[idx]!!
            for (y in slice.top until slice.bottom) png.writeRow(px, y * w)
        }
    }
}

/** 구분선 = 연회색 띠 + 가운데 점선("이 사이는 너무 빨리 넘겨 찍히지 않음"). */
private const val GAP_BACKGROUND = 0xFFE0E0E0.toInt()
private const val GAP_DASH = 0xFF757575.toInt()
private const val GAP_DASH_THICKNESS = 4
private const val GAP_DASH_ON = 24
private const val GAP_DASH_OFF = 16

internal fun gapRow(width: Int, y: Int, height: Int): IntArray {
    val mid = height / 2
    val dash = y >= mid - GAP_DASH_THICKNESS / 2 && y < mid + GAP_DASH_THICKNESS / 2
    return IntArray(width) { x -> if (dash && x % (GAP_DASH_ON + GAP_DASH_OFF) < GAP_DASH_ON) GAP_DASH else GAP_BACKGROUND }
}
