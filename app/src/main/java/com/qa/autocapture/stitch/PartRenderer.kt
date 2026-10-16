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
