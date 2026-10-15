package com.qa.autocapture.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.hypot

/**
 * TYPE_ACCESSIBILITY_OVERLAY 로 띄우는 플로팅 버튼(오버레이 권한 불필요).
 * 탭 = 기록 시작/정지, 길게 누르기 = 앱 열기, 끌기 = 이동.
 * 캡처 직전에 숨기고 캡처 후 다시 보여 증적에 찍히지 않게 한다.
 */
class FloatingButton(
    private val service: AccessibilityService,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val shape = GradientDrawable().apply { cornerRadius = dp(24).toFloat() }

    private val view = TextView(service).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        minWidth = dp(64)
        minHeight = dp(48)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = shape
        setOnClickListener { onTap() }
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        val metrics = service.resources.displayMetrics
        x = metrics.widthPixels - dp(88)
        y = (metrics.heightPixels * 0.35f).toInt()
    }

    private var attached = false

    init {
        setRecording(false, 0)
        view.setOnTouchListener(DragHandler())
    }

    fun show() {
        if (attached) return
        wm.addView(view, params)
        attached = true
    }

    fun remove() {
        if (!attached) return
        runCatching { wm.removeViewImmediate(view) }
        attached = false
    }

    /** 대기: "● 시작"(회색), 기록 중: "■ 저장된 화면 수"(빨강) */
    fun setRecording(recording: Boolean, savedScreens: Int) {
        view.text = if (recording) "■ $savedScreens" else "● 시작"
        shape.setColor(if (recording) RECORDING_COLOR else IDLE_COLOR)
        view.contentDescription =
            if (recording) "QA 기록 중, 저장된 화면 ${savedScreens}개. 누르면 정지" else "QA 기록 시작"
    }

    /** 창 알파와 View 를 함께 숨긴다. 다음 화면 합성(수십 ms) 뒤에 캡처해야 한다. */
    fun hideForCapture() {
        if (!attached) return
        view.visibility = View.INVISIBLE
        params.alpha = 0f
        wm.updateViewLayout(view, params)
    }

    fun restoreAfterCapture() {
        if (!attached) return
        view.visibility = View.VISIBLE
        params.alpha = 1f
        wm.updateViewLayout(view, params)
    }

    /** (상태바 높이, 내비게이션바 높이). 스티칭의 고정 영역 최소값으로 쓴다. 크게 잡혀도 무해하다. */
    fun systemBarInsets(): Pair<Int, Int> {
        val insets = runCatching {
            wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.systemBars())
        }.getOrNull()
        val top = maxOf(insets?.top ?: 0, systemDimen("status_bar_height"))
        val bottom = maxOf(insets?.bottom ?: 0, systemDimen("navigation_bar_height"))
        return top to bottom
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun systemDimen(name: String): Int {
        val res = service.resources
        val id = res.getIdentifier(name, "dimen", "android")
        return if (id != 0) res.getDimensionPixelSize(id) else 0
    }

    private fun dp(v: Int) = (v * density + 0.5f).toInt()

    private inner class DragHandler : View.OnTouchListener {
        private val slop = ViewConfiguration.get(service).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val longPress = Runnable {
            longPressed = true
            onLongPress()
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    longPressed = false
                    v.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        v.removeCallbacks(longPress)
                    }
                    if (dragging && attached) {
                        val metrics = service.resources.displayMetrics
                        params.x = (startX + dx.toInt()).coerceIn(0, maxOf(0, metrics.widthPixels - v.width))
                        params.y = (startY + dy.toInt()).coerceIn(0, maxOf(0, metrics.heightPixels - v.height))
                        wm.updateViewLayout(view, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (!dragging && !longPressed) v.performClick()
                }
                MotionEvent.ACTION_CANCEL -> v.removeCallbacks(longPress)
            }
            return true
        }
    }

    private companion object {
        const val IDLE_COLOR = 0xCC424242.toInt()
        const val RECORDING_COLOR = 0xE6D32F2F.toInt()
    }
}
