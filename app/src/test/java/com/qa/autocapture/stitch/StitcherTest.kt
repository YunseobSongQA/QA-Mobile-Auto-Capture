package com.qa.autocapture.stitch

import com.qa.autocapture.capture.CaptureConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.ImageIO

/**
 * 합성 이미지(고정 헤더/푸터 + 긴 콘텐츠)를 겹치게 잘라 만든 프레임으로
 * 스티칭 결과가 원본과 픽셀 단위로 같은지 검증한다.
 */
class StitcherTest {

    private val w = 160
    private val h = 420
    private val statusH = 14
    private val toolbarH = 30
    private val tabH = 26
    private val navH = 12
    private val headerH = statusH + toolbarH
    private val footerH = tabH + navH
    private val viewH = h - headerH - footerH

    private val content = syntheticContent(w, 3000, seed = 7)
    private val header = header(clock = 0)
    private val footer = solidBlock(w, footerH, seed = 3, base = 0xFFF2F2F2.toInt())
    private val periodic = periodicContent(1200, period = 37, seed = 5)

    // ── 기본 시나리오 ──────────────────────────────────────────────

    @Test
    fun `아래로 끝까지 스크롤하면 원본과 같다`() {
        val offsets = listOf(0, 120, 250, 251, 400, 690, 900, 1100, 1350, 1500)
        val result = stitch(offsets.map { frame(it) })
        assertEquals("ok", result.plan.result)
        assertEquals(1, result.images.size)
        assertImage(expected(0, offsets.last()), result.images[0])
    }

    @Test
    fun `같은 위치 프레임은 버린다`() {
        val offsets = listOf(0, 0, 200, 200, 200, 380)
        val result = stitch(offsets.map { frame(it) })
        assertEquals(3, result.plan.usedFrames)
        assertImage(expected(0, 380), result.images.single())
    }

    @Test
    fun `아래 위 아래로 왕복해도 원본과 같다`() {
        val offsets = listOf(0, 150, 300, 200, 60, 350, 520, 410, 700)
        val result = stitch(offsets.map { frame(it) })
        assertImage(expected(0, 700), result.images.single())
    }

    @Test
    fun `중간에서 진입해 위로 스크롤하면 위쪽에 붙는다`() {
        val offsets = listOf(600, 450, 300, 120, 0)
        val result = stitch(offsets.map { frame(it) })
        assertEquals("ok", result.plan.result)
        assertImage(expected(0, 600), result.images.single())
    }

    @Test
    fun `프레임 1장이면 single`() {
        val f = frame(500)
        val result = stitch(listOf(f))
        assertEquals("single", result.plan.result)
        assertImage(Image(w, h, f), result.images.single())
    }

    @Test
    fun `겹침이 없으면 part 로 분리된다`() {
        val offsets = listOf(0, 200, 380, 1800, 2000)
        val result = stitch(offsets.map { frame(it) })
        assertEquals("split", result.plan.result)
        assertEquals(2, result.images.size)
        assertImage(expected(0, 380), result.images[0])
        assertImage(expected(1800, 2000), result.images[1])
    }

    @Test
    fun `크기가 바뀌면 분리된다`() {
        val other = IntArray(w * (h - 20)) { 0xFF336699.toInt() }
        val frames = listOf(Image(w, h, frame(0)), Image(w, h - 20, other))
        val plan = Stitcher.plan(frames.map { it.toFrame() })
        assertEquals("split", plan.result)
    }

    // ── 현실적인 방해 요소 ─────────────────────────────────────────

    @Test
    fun `상태바 시계가 바뀌어도 첫 프레임 상태바로 복원된다`() {
        val offsets = listOf(0, 180, 360, 540, 720)
        val frames = offsets.mapIndexed { i, o -> frame(o, clock = if (i >= 2) 1 else 0) }
        val result = stitch(frames, topInset = statusH, bottomInset = navH)
        assertImage(expected(0, 720), result.images.single())
    }

    @Test
    fun `상태바 높이 힌트가 없어도 시계 변화는 복원에 영향이 없다`() {
        val offsets = listOf(0, 180, 360, 540, 720)
        val frames = offsets.mapIndexed { i, o -> frame(o, clock = if (i >= 3) 1 else 0) }
        val result = stitch(frames)
        assertImage(expected(0, 720), result.images.single())
    }

    @Test
    fun `같은 위치에서 시계만 바뀐 프레임은 버린다`() {
        val result = stitch(listOf(frame(300), frame(300, clock = 1)))
        assertEquals("single", result.plan.result)
    }

    @Test
    fun `접히는 툴바는 첫 프레임의 큰 제목만 남는다`() {
        val bigTitleH = 90
        val bigHeader = solidBlock(w, bigTitleH, seed = 11, base = 0xFF1E88E5.toInt())
        val small = header
        // 첫 프레임: 상태바 + 큰 제목 + 콘텐츠[0..]. 이후 프레임: 작은 헤더 + 콘텐츠[o..]
        val first = compose(listOf(small.copyRows(0, statusH), bigHeader, content.copyRows(0, h - statusH - bigTitleH - footerH), footer))
        val offsets = listOf(160, 330, 500, 650)
        val rest = offsets.map { frame(it) }
        val result = stitch(listOf(first) + rest, topInset = statusH, bottomInset = navH)

        val exp = compose(
            listOf(
                small.copyRows(0, statusH),
                bigHeader,
                content.copyRows(0, offsets.last() + viewH),
                footer,
            ),
        )
        assertImage(Image(w, exp.size / w, exp), result.images.single())
    }

    @Test
    fun `높이 제한을 넘으면 프레임 경계에서 나뉜다`() {
        val longContent = syntheticContent(w, 40_000, seed = 21)
        val offsets = (0..38_000 step 250).toList()
        val frames = offsets.map { o -> compose(listOf(header, longContent.copyRows(o, o + viewH), footer)) }
        val plan = Stitcher.plan(frames.map { Image(w, h, it).toFrame() })
        assertEquals("split", plan.result)
        assertTrue(plan.parts.all { it.height <= CaptureConfig.MAX_STITCH_HEIGHT })
    }

    // ── scrollDeltaY 힌트 ────────────────────────────────────────

    @Test
    fun `scrollDeltaY 힌트가 맞으면 그대로 써서 원본과 같다`() {
        val offsets = listOf(0, 120, 250, 400, 690, 900)
        val result = stitch(offsets.map { frame(it) }, hints = deltas(offsets))
        assertEquals("ok", result.plan.result)
        assertImage(expected(0, offsets.last()), result.images.single())
    }

    @Test
    fun `힌트가 틀리면 행 비교로 겹침을 찾는다`() {
        val offsets = listOf(0, 120, 250, 400, 690)
        val wrong = deltas(offsets).map { it?.plus(37) }
        val result = stitch(offsets.map { frame(it) }, hints = wrong)
        assertImage(expected(0, offsets.last()), result.images.single())
    }

    @Test
    fun `반복 무늬라 행 비교로 못 찾아도 힌트로 이어붙인다`() {
        val offsets = listOf(0, 100, 200, 300)
        val frames = offsets.map { frameOf(periodic, it) }
        // 행이 화면 안에서 여러 번 나와 투표할 행이 없다 → 힌트 없이는 나뉜다
        assertNotEquals("ok", stitch(frames).plan.result)
        val result = stitch(frames, hints = deltas(offsets))
        assertEquals("ok", result.plan.result)
        assertImage(expectedOf(periodic, 0, offsets.last()), result.images.single())
    }

    @Test
    fun `같은 위치로 버린 프레임이 있어도 힌트를 이어서 쓴다`() {
        val offsets = listOf(0, 100, 100, 200)
        // 세 번째는 같은 위치에서 시계만 바뀐 프레임(버림)
        val frames = offsets.mapIndexed { i, o -> frameOf(periodic, o, clock = if (i == 2) 1 else 0) }
        val result = stitch(frames, hints = deltas(offsets))
        assertEquals(3, result.plan.usedFrames)
        assertImage(expectedOf(periodic, 0, 200), result.images.single())
    }

    @Test
    fun `힌트가 있어도 겹침이 없으면 억지로 붙이지 않고 나눈다`() {
        val offsets = listOf(0, 200, 380, 1800, 2000)
        val result = stitch(offsets.map { frame(it) }, hints = deltas(offsets))
        assertEquals("split", result.plan.result)
        assertEquals(2, result.images.size)
        assertImage(expected(0, 380), result.images[0])
        assertImage(expected(1800, 2000), result.images[1])
    }

    // ── 고정 영역 판별 ───────────────────────────────────────────

    @Test
    fun `고정 영역 판별 - 상단 헤더와 하단 탭바 높이를 찾는다`() {
        val rows = listOf(0, 150, 300).map { Image(w, h, frameOf(periodic, it)).toFrame().rows }
        assertEquals(headerH to footerH, Stitcher.fixedArea(rows))
    }

    @Test
    fun `고정 영역 판별 - 상태바 높이가 더 크면 그 값을 쓴다`() {
        val rows = listOf(0, 150).map { Image(w, h, frameOf(periodic, it)).toFrame().rows }
        assertEquals(60 to footerH, Stitcher.fixedArea(rows, topInset = 60, bottomInset = navH))
    }

    @Test
    fun `고정 영역 판별 - 움직임이 없으면 상태바와 내비게이션바 높이로 되돌린다`() {
        val f = Image(w, h, frame(300)).toFrame().rows
        assertEquals(statusH to navH, Stitcher.fixedArea(listOf(f, f.copyOf()), statusH, navH))
    }

    @Test
    fun `고정 영역은 결과 맨 위와 맨 아래에 한 번씩만 나온다`() {
        val offsets = listOf(0, 100, 200, 300)
        val result = stitch(offsets.map { frameOf(periodic, it) }, hints = deltas(offsets))
        val img = result.images.single()
        assertEquals(headerH + (300 + viewH) + footerH, img.height)
        assertImage(Image(w, headerH, header), Image(w, headerH, img.pixels.copyOfRange(0, headerH * w)))
        assertImage(Image(w, footerH, footer), Image(w, footerH, img.pixels.copyOfRange((img.height - footerH) * w, img.height * w)))
    }

    // ── 도우미 ───────────────────────────────────────────────────

    private class Image(val width: Int, val height: Int, val pixels: IntArray) {
        fun toFrame() = Stitcher.Frame(width, height, Stitcher.rowHashes(pixels, width, height))
    }

    private class Result(val plan: Stitcher.Plan, val images: List<Image>)

    /** plan → PNG 렌더링 → ImageIO 디코드까지 전체 경로를 거친다. */
    private fun stitch(frames: List<IntArray>, topInset: Int = 0, bottomInset: Int = 0, hints: List<Int?> = emptyList()): Result {
        val plan = Stitcher.plan(frames.map { Image(w, h, it).toFrame() }, topInset, bottomInset, hints)
        val images = plan.parts.map { part ->
            val bytes = ByteArrayOutputStream()
            renderPart(part, bytes) { i, into -> frames[i].copyInto(into) }
            val img = ImageIO.read(ByteArrayInputStream(bytes.toByteArray()))
            Image(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
        }
        return Result(plan, images)
    }

    /** 콘텐츠 [first .. last+viewH) 를 헤더/푸터로 감싼 기대 이미지. */
    private fun expected(first: Int, last: Int): Image {
        val px = compose(listOf(header, content.copyRows(first, last + viewH), footer))
        return Image(w, px.size / w, px)
    }

    private fun frame(offset: Int, clock: Int = 0): IntArray = frameOf(content, offset, clock)

    private fun frameOf(source: IntArray, offset: Int, clock: Int = 0): IntArray =
        compose(listOf(if (clock == 0) header else header(clock), source.copyRows(offset, offset + viewH), footer))

    private fun expectedOf(source: IntArray, first: Int, last: Int): Image {
        val px = compose(listOf(header, source.copyRows(first, last + viewH), footer))
        return Image(w, px.size / w, px)
    }

    /** 오프셋 목록 → 프레임 사이 scrollDeltaY 힌트(첫 프레임은 null). */
    private fun deltas(offsets: List<Int>): List<Int?> = offsets.indices.map { i -> if (i == 0) null else offsets[i] - offsets[i - 1] }

    /** 서로 다른 37개 행이 계속 반복되는 콘텐츠(화면 안에서 같은 행이 여러 번 나와 행 비교만으로는 위치를 못 찾음). */
    private fun periodicContent(height: Int, period: Int, seed: Long): IntArray {
        val rnd = Random(seed)
        val block = IntArray(w * period) { 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF) }
        return IntArray(w * height) { i -> block[i % block.size] }
    }

    private fun assertImage(expected: Image, actual: Image) {
        assertEquals("폭", expected.width, actual.width)
        assertEquals("높이", expected.height, actual.height)
        for (i in expected.pixels.indices) {
            val e = expected.pixels[i] or OPAQUE
            val a = actual.pixels[i] or OPAQUE
            if (e != a) {
                throw AssertionError("픽셀 불일치 (x=${i % expected.width}, y=${i / expected.width})")
            }
        }
    }

    private fun IntArray.copyRows(from: Int, to: Int) = copyOfRange(from * w, to * w)

    private fun compose(blocks: List<IntArray>): IntArray {
        val out = IntArray(blocks.sumOf { it.size })
        var p = 0
        for (b in blocks) {
            b.copyInto(out, p)
            p += b.size
        }
        return out
    }

    /** 상태바(시계 무늬 포함) + 툴바. clock 값에 따라 시계 부분만 달라진다. */
    private fun header(clock: Int): IntArray {
        val px = compose(
            listOf(
                solidBlock(w, statusH, seed = 1, base = 0xFF202020.toInt()),
                solidBlock(w, toolbarH, seed = 2, base = 0xFF1565C0.toInt()),
            ),
        )
        val rnd = Random(100L + clock)
        for (y in 3 until statusH - 3) for (x in 4 until 26) {
            if (rnd.nextInt(3) == 0) px[y * w + x] = 0xFFFFFFFF.toInt()
        }
        return px
    }

    /** 바탕색 위에 고정된 무늬(아이콘·글자 흉내)가 있는 블록. */
    private fun solidBlock(width: Int, height: Int, seed: Long, base: Int): IntArray {
        val rnd = Random(seed)
        val px = IntArray(width * height) { base }
        for (y in 2 until height - 2) for (x in 30 until width - 30) {
            if (rnd.nextInt(5) == 0) px[y * width + x] = 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF)
        }
        return px
    }

    /**
     * 목록 화면 흉내: 아이템마다 위/아래 여백(빈 행), 모든 아이템에 같은 아이콘 무늬,
     * 랜덤 텍스트 행, 반복되는 구분선. 빈 행·구분선·아이콘 행은 서로 같아 유일하지 않다.
     */
    private fun syntheticContent(width: Int, height: Int, seed: Long): IntArray {
        val rnd = Random(seed)
        val white = 0xFFFFFFFF.toInt()
        val px = IntArray(width * height) { white }
        val icon = IntArray(24 * 24) { if (rnd.nextBoolean()) 0xFF4CAF50.toInt() else white }
        var y = 0
        while (y < height) {
            y += 8
            val lines = 20 + rnd.nextInt(30)
            for (r in 0 until lines) {
                if (y >= height) break
                if (r < 24) for (x in 0 until 24) px[y * width + 6 + x] = icon[r * 24 + x]
                if (r in 2 until lines - 2) {
                    for (x in 40 until width - 12) {
                        if (rnd.nextInt(4) == 0) px[y * width + x] = 0xFF000000.toInt() or (rnd.nextInt(90) * 0x010101)
                    }
                }
                y++
            }
            y += 8
            if (y < height) {
                for (x in 0 until width) px[y * width + x] = 0xFFDDDDDD.toInt()
                y++
            }
        }
        return px
    }

    private companion object {
        const val OPAQUE = 0xFF000000.toInt()
    }
}
