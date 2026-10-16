package com.qa.autocapture.stitch

import com.qa.autocapture.capture.CaptureConfig
import kotlin.math.abs

/**
 * 스크롤하며 찍은 프레임들(화면 상태 1개)을 세로로 이어붙이는 "합성 계획"을 만든다.
 *
 * 픽셀 대신 행 해시만 보고 판단하므로 프레임 원본을 메모리에 들고 있을 필요가 없다.
 * Android 의존성이 없어 JVM 단위 테스트로 검증한다. 알고리즘 설명은 DESIGN.md 4장.
 * 상수(겹침 판정 기준, 최대 높이)는 CaptureConfig 에 있다.
 */
object Stitcher {

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L

    private const val CONTENT: Byte = 0
    private const val UNKNOWN: Byte = 1
    private const val FIXED: Byte = 2

    /** 행 해시만 담은 프레임. */
    class Frame(val width: Int, val height: Int, val rows: LongArray)

    /** 프레임 [frame] 의 행 [top, bottom) 구간. */
    data class Slice(val frame: Int, val top: Int, val bottom: Int) {
        val height get() = bottom - top
    }

    /** 결과 이미지 1장 = slices 를 위에서부터 이어붙인 것. */
    class Part(val width: Int, val frameHeight: Int, val slices: List<Slice>) {
        val height: Int = slices.sumOf { it.height }

        /** 이 장에 이어붙인 프레임 수. */
        val frameCount: Int get() = slices.map { it.frame }.distinct().size
    }

    class Plan(val parts: List<Part>, val usedFrames: Int) {
        /** 결과 요약: 여러 장으로 나뉨 / 프레임 1장만 사용 / 정상 합성 */
        val result: String
            get() = when {
                parts.size > 1 -> "split"
                usedFrames <= 1 -> "single"
                else -> "ok"
            }
    }

    sealed interface Match {
        /** 같은 위치(오프셋 0) — 버린다. */
        data object Same : Match

        /** 이어지는 위치를 못 찾음 — 새 part 로 나눈다. */
        data object NoMatch : Match

        /** B 의 행 y 는 A 의 행 y + dy 와 같은 내용이다(dy > 0 이면 아래로 스크롤). */
        data class Offset(val dy: Int) : Match
    }

    private class Placed(val frame: Int, val pos: Int)

    /** ARGB 픽셀 배열에서 행마다 64bit 해시를 만든다(오른쪽 여백 제외, 가로 샘플링). */
    fun rowHashes(pixels: IntArray, width: Int, height: Int): LongArray {
        val xEnd = width - (width * CaptureConfig.STITCH_EDGE_MARGIN_RATIO).toInt()
        val out = LongArray(height)
        for (y in 0 until height) {
            var h = FNV_OFFSET
            val start = y * width
            var i = start
            val end = start + xEnd
            while (i < end) {
                h = (h xor (pixels[i].toLong() and 0xFFFFFFFFL)) * FNV_PRIME
                i += CaptureConfig.STITCH_SAMPLE_STEP
            }
            out[y] = h
        }
        return out
    }

    /**
     * 직전에 채택된 프레임 [a] 와 새 프레임 [b] 의 관계를 찾는다.
     *
     * 1) [expected](scrollDeltaY 로 아는 이동량)가 있으면 그 위치에서 행이 맞는지 검증해 맞으면 그대로 쓴다.
     * 2) 없거나 안 맞으면 행 비교: 정적이 아니고(같은 위치 행과 다름) 프레임 안에서 유일한 B 의 행이,
     *    A 에서 유일하게 나타나는 위치와의 차이(dy)에 투표한다.
     */
    fun match(a: LongArray, b: LongArray, expected: Int? = null): Match {
        val h = a.size
        if (a.contentEquals(b)) return Match.Same

        if (expected != null && expected != 0 && abs(expected) < h) {
            val (ok, total) = overlapMatch(a, b, expected)
            if (total >= CaptureConfig.STITCH_MIN_VOTES && ok >= total * CaptureConfig.STITCH_HINT_MIN_MATCH_RATIO) {
                return Match.Offset(expected)
            }
        }

        val uniqueA = HashMap<Long, Int>(h * 2)
        for (y in 0 until h) uniqueA[a[y]] = if (uniqueA.containsKey(a[y])) -1 else y
        val countB = HashMap<Long, Int>(h * 2)
        for (v in b) countB[v] = (countB[v] ?: 0) + 1

        val votes = IntArray(2 * h - 1)
        for (y in 0 until h) {
            val v = b[y]
            if (v == a[y] || countB[v] != 1) continue
            val ya = uniqueA[v] ?: continue
            if (ya < 0) continue
            votes[ya - y + h - 1]++
        }

        var best = 0
        for (i in votes.indices) if (votes[i] > votes[best]) best = i
        var second = 0
        for (i in votes.indices) if (i != best && votes[i] > second) second = votes[i]

        if (votes[best] >= CaptureConfig.STITCH_MIN_VOTES && votes[best] >= 2 * second) {
            val dy = best - (h - 1)
            val (ok, total) = overlapMatch(a, b, dy)
            if (total == 0 || ok >= total * CaptureConfig.STITCH_MIN_MATCH_RATIO) return Match.Offset(dy)
        }

        var changed = 0
        for (y in 0 until h) if (a[y] != b[y]) changed++
        return if (changed <= h * CaptureConfig.STITCH_SAME_MAX_CHANGED_RATIO) Match.Same else Match.NoMatch
    }

    /** 겹치는 구간에서 양쪽 모두 정적이 아닌 행 중 dy 만큼 옮겼을 때 일치하는 행 수와 비교한 행 수. */
    private fun overlapMatch(a: LongArray, b: LongArray, dy: Int): Pair<Int, Int> {
        val h = a.size
        var total = 0
        var ok = 0
        for (y in maxOf(0, -dy) until minOf(h, h - dy)) {
            // 헤더·푸터처럼 제자리에 있는 행끼리 비교하면 의미가 없으므로 뺀다
            if (a[y] == b[y] || a[y + dy] == b[y + dy]) continue
            total++
            if (b[y] == a[y + dy]) ok++
        }
        return ok to total
    }

    /**
     * 프레임 목록(촬영 순서)으로 합성 계획을 만든다. **겹치는 프레임끼리만** 한 장으로 합친다.
     *
     * - 같은 위치 프레임은 버린다.
     * - 프레임 1장만 어긋났으면(오버스크롤로 늘어난 순간 등) 그 프레임만 버리고 다음 프레임으로 잇는다.
     * - 겹침을 못 찾은 곳, 크기가 바뀐 곳(회전), [maxHeight] 를 넘는 곳에서는 다음 장(part)으로 나눈다.
     *
     * @param topInset 상태바 높이. 상단 고정 영역의 최소값으로 쓴다.
     * @param bottomInset 내비게이션바 높이. 하단 고정 영역의 최소값으로 쓴다.
     * @param hints hints[i] = 프레임 i-1 → i 사이 scrollDeltaY 합(아래로 스크롤이면 +). 모르면 null. hints[0] 은 쓰지 않는다.
     * @param maxHeight 결과 1장의 최대 높이. 넘으면 프레임 경계에서 다음 part 로 나눈다.
     */
    fun plan(
        frames: List<Frame>,
        topInset: Int = 0,
        bottomInset: Int = 0,
        hints: List<Int?> = emptyList(),
        maxHeight: Int = CaptureConfig.MAX_STITCH_HEIGHT,
    ): Plan {
        require(frames.isNotEmpty()) { "프레임이 없습니다" }

        val groups = mutableListOf<List<Placed>>()
        var group = mutableListOf(Placed(0, 0))
        var last = 0
        var lastPos = 0
        // 직전 채택 프레임부터의 이동량. 버린 프레임의 이동량도 더한다. 하나라도 모르면 null.
        var expected: Int? = 0
        var i = 1
        while (i < frames.size) {
            val a = frames[last]
            val b = frames[i]
            expected = add(expected, hints.getOrNull(i))
            val m = if (!sameSize(a, b)) Match.NoMatch else match(a.rows, b.rows, expected)
            when (m) {
                Match.Same -> {
                    i++
                    continue
                }
                Match.NoMatch -> {
                    val next = frames.getOrNull(i + 1)
                    if (sameSize(a, b) && next != null && sameSize(a, next) &&
                        match(a.rows, next.rows, add(expected, hints.getOrNull(i + 1))) is Match.Offset
                    ) {
                        i++ // 이 프레임만 어긋났다: 버리고(이동량은 이어서 센다) 다음 프레임을 직전 프레임에 잇는다
                        continue
                    }
                    groups += group
                    group = mutableListOf(Placed(i, 0))
                    lastPos = 0
                }
                is Match.Offset -> {
                    val pos = lastPos + m.dy
                    val top = minOf(group.minOf { it.pos }, pos)
                    val bottom = maxOf(group.maxOf { it.pos }, pos) + b.height
                    if (bottom - top > maxHeight) {
                        groups += group
                        group = mutableListOf(Placed(i, 0))
                        lastPos = 0
                    } else {
                        group += Placed(i, pos)
                        lastPos = pos
                    }
                }
            }
            last = i
            expected = 0
            i++
        }
        groups += group

        val parts = groups.map { compose(frames, it, topInset, bottomInset) }
        return Plan(parts, groups.sumOf { it.size })
    }

    private fun add(a: Int?, b: Int?): Int? = if (a != null && b != null) a + b else null

    private fun sameSize(a: Frame, b: Frame) = a.width == b.width && a.height == b.height

    /** 위치가 정해진 프레임 묶음 하나를 행 단위로 합성한다. */
    private fun compose(frames: List<Frame>, group: List<Placed>, topInset: Int, bottomInset: Int): Part {
        val first = frames[group[0].frame]
        val w = first.width
        val h = first.height
        if (group.size == 1) return Part(w, h, listOf(Slice(group[0].frame, 0, h)))

        val rows = group.map { frames[it.frame].rows }

        // (1) 고정 영역(헤더·탭바): 결과에는 맨 위·맨 아래에 한 번씩만 넣고 나머지 프레임에서는 잘라낸다
        val (fixedTop, fixedBottom) = fixedArea(rows, topInset, bottomInset)

        // (2) 행 분류: 이웃 프레임과 비교
        val cls = Array(group.size) { k ->
            ByteArray(h) { y -> classify(rows, group, k, y, h) }
        }

        // (3) 맨 위 프레임의 상단 고정 영역 + 행마다 출처를 고른 스트립 + 맨 아래 프레임의 하단 고정 영역
        val topPos = group.minOf { it.pos }
        val bottomPos = group.maxOf { it.pos }
        val topK = group.indexOfFirst { it.pos == topPos }
        val bottomK = group.indexOfLast { it.pos == bottomPos }

        val out = SliceBuilder()
        out.add(group[topK].frame, 0, fixedTop)
        for (s in topPos + fixedTop until bottomPos + h - fixedBottom) {
            var bestK = -1
            var bestScore = Int.MAX_VALUE
            for (k in group.indices) {
                val y = s - group[k].pos
                if (y < 0 || y >= h) continue
                val outsideZone = if (y >= fixedTop && y < h - fixedBottom) 0 else 1
                val score = outsideZone * 4 + cls[k][y]
                if (score < bestScore) {
                    bestScore = score
                    bestK = k
                }
            }
            out.add(group[bestK].frame, s - group[bestK].pos, s - group[bestK].pos + 1)
        }
        out.add(group[bottomK].frame, h - fixedBottom, h)
        return Part(w, h, out.slices)
    }

    /**
     * 상단·하단 고정 영역 높이(헤더·탭바처럼 프레임마다 안 바뀌는 행).
     * 연속 프레임 쌍마다 위/아래에서부터 같은 위치 행이 동일한 길이를 재서 그 최소값을 쓴다.
     * 상태바·내비게이션바 높이([topInset], [bottomInset])보다 작으면 그 값을 쓰고,
     * 위아래 합이 화면을 다 덮으면(움직임 없음) 그 두 값으로 되돌린다.
     */
    fun fixedArea(rows: List<LongArray>, topInset: Int = 0, bottomInset: Int = 0): Pair<Int, Int> {
        val h = rows.first().size
        var minTop = h
        var minBottom = h
        for (k in 1 until rows.size) {
            val a = rows[k - 1]
            val b = rows[k]
            var t = 0
            while (t < h && a[t] == b[t]) t++
            var bt = 0
            while (bt < h && a[h - 1 - bt] == b[h - 1 - bt]) bt++
            minTop = minOf(minTop, t)
            minBottom = minOf(minBottom, bt)
        }
        val top = maxOf(topInset, minTop)
        val bottom = maxOf(bottomInset, minBottom)
        if (top + bottom >= h) return topInset.coerceIn(0, h / 3) to bottomInset.coerceIn(0, h / 3)
        return top to bottom
    }

    /**
     * CONTENT: 스크롤 위치를 맞춘 이웃 행과 같음.
     * FIXED: 그렇지 않으면서 이웃의 같은 화면 위치 행과 같음(헤더·탭바처럼 안 움직임).
     * UNKNOWN: 둘 다 아님(새로 나타난 행이나 떠 있는 요소).
     */
    private fun classify(rows: List<LongArray>, group: List<Placed>, k: Int, y: Int, h: Int): Byte {
        val v = rows[k][y]
        var c = UNKNOWN
        for (j in k - 1..k + 1 step 2) {
            if (j < 0 || j >= group.size) continue
            val yj = group[k].pos + y - group[j].pos
            if (yj in 0 until h && rows[j][yj] == v) return CONTENT
            if (rows[j][y] == v) c = FIXED
        }
        return c
    }

    /** 같은 프레임의 연속된 행을 하나의 Slice 로 합친다. */
    private class SliceBuilder {
        val slices = mutableListOf<Slice>()
        private var frame = -1
        private var top = 0
        private var bottom = 0

        fun add(f: Int, from: Int, to: Int) {
            if (to <= from) return
            if (f == frame && from == bottom) {
                bottom = to
                slices[slices.lastIndex] = Slice(frame, top, bottom)
                return
            }
            frame = f
            top = from
            bottom = to
            slices += Slice(f, from, to)
        }
    }
}
