package com.qa.autocapture.ui.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 옆으로 넘기는 페이지 뷰. 프레임워크 HorizontalScrollView 에 "한 장 단위로 맞추기"를 더했다(AndroidX ViewPager 대신).
 * 손가락을 따라 움직이고, 놓으면 가까운 장(빠르게 넘기면 다음 장)으로 맞춘다.
 * 페이지 안의 세로 스크롤(긴 사진)은 그대로 동작한다.
 */
@SuppressLint("ViewConstructor")
class PagerView(context: Context, private val onPageSelected: (index: Int) -> Unit) : HorizontalScrollView(context) {

    private val strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val pages = ArrayList<FrameLayout>()
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var tracker: VelocityTracker? = null
    private var downPage = 0
    private var pageWidth = 0

    /** 지금 보이는 장. */
    var current = 0
        private set

    val pageCount: Int get() = pages.size

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = true
        addView(strip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    /** 빈 페이지 [count] 장을 만든다. 내용은 호출자가 [page] 에 넣는다. */
    fun setPageCount(count: Int) {
        strip.removeAllViews()
        pages.clear()
        repeat(count) {
            val page = FrameLayout(context)
            pages += page
            strip.addView(page, LinearLayout.LayoutParams(pageWidth, LinearLayout.LayoutParams.MATCH_PARENT))
        }
    }

    fun page(index: Int): FrameLayout = pages[index]

    /** [index] 장으로 간다. 크기가 정해지기 전이면 정해진 뒤에 간다. */
    fun showPage(index: Int, smooth: Boolean = false) {
        val target = index.coerceIn(0, maxOf(0, pages.size - 1))
        if (pageWidth == 0) {
            current = target
            return
        }
        if (smooth) smoothScrollTo(target * pageWidth, 0) else scrollTo(target * pageWidth, 0)
        select(target)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == pageWidth) return
        pageWidth = w
        for (p in pages) p.layoutParams = (p.layoutParams as LinearLayout.LayoutParams).apply { width = w }
        strip.requestLayout()
        val keep = current
        post { showPage(keep) }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                downPage = current
            }
        }
        tracker?.addMovement(ev)
        val handled = super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                snap()
                tracker?.recycle()
                tracker = null
            }
        }
        return handled
    }

    /** 놓은 뒤 가까운 장으로 맞춘다. 빠르게 넘겼으면 그 방향의 다음 장. */
    private fun snap() {
        if (pageWidth == 0 || pages.isEmpty()) return
        val t = tracker ?: return
        t.computeCurrentVelocity(1000)
        val vx = t.xVelocity
        val nearest = (scrollX.toFloat() / pageWidth).roundToInt()
        // 긴 사진을 위아래로 넘기다 손가락이 조금 옆으로 흘러도 장이 바뀌지 않게, 실제로 옆으로 끌었을 때만 넘긴다
        val dragged = abs(scrollX - downPage * pageWidth) > touchSlop
        val sideways = dragged && abs(vx) > minFlingVelocity && abs(vx) > abs(t.yVelocity)
        val target = when {
            sideways && vx < 0 -> downPage + 1
            sideways && vx > 0 -> downPage - 1
            else -> nearest
        }.coerceIn(0, pages.size - 1)
        smoothScrollTo(target * pageWidth, 0)
        select(target)
    }

    /** 자유롭게 미끄러지지 않고 장 단위로만 움직인다([snap]). */
    override fun fling(velocityX: Int) = Unit

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (pageWidth > 0) select((l.toFloat() / pageWidth).roundToInt().coerceIn(0, maxOf(0, pages.size - 1)))
    }

    /** 보이는 장이 바뀌면 알린다. */
    private fun select(index: Int) {
        if (index == current) return
        current = index
        onPageSelected(index)
    }
}
