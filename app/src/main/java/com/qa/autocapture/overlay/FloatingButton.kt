package com.qa.autocapture.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import com.qa.autocapture.capture.policy.CaptureConfig
import com.qa.autocapture.capture.policy.FrameDiff
import java.util.EnumSet
import kotlin.math.hypot

/**
 * TYPE_ACCESSIBILITY_OVERLAY 로 띄우는 플로팅 버튼(오버레이 권한 불필요).
 * 탭 = 기록 시작/정지, 길게 누르기 = 앱 열기, 끌기 = 이동. 캡처 일시정지 중에는 서비스가 [remove] 해 숨긴다.
 * 증적에 찍히지 않게 숨기는 때([Hide]): 저장될 화면을 찍는 순간(CAPTURE), 스크롤하는 동안(SCROLL).
 * 숨길 이유가 하나도 없을 때만 보인다. 화면을 확보하면 [pulse] 로 잠깐 체크 표시를 보여 준다.
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

    /** 숨기는 이유. */
    enum class Hide { CAPTURE, SCROLL }

    private var attached = false
    private val hideReasons = EnumSet.noneOf(Hide::class.java)

    /** 숨긴 시각(SystemClock.uptimeMillis). 보이는 중이면 0. 숨긴 뒤 다음 화면 합성(수십 ms)이 지나야 캡처에 안 찍힌다. */
    var hiddenSince = 0L
        private set

    private var recording = false
    private var images = 0
    private var pulsing = false
    private val endPulse = Runnable {
        pulsing = false
        render()
    }

    init {
        render()
        view.setOnTouchListener(DragHandler())
    }

    fun show() {
        if (attached) return
        // 숨긴 상태로 제거됐을 수 있으므로 보이는 상태로 되돌린 뒤 붙인다
        hideReasons.clear()
        hiddenSince = 0
        view.visibility = View.VISIBLE
        params.alpha = 1f
        wm.addView(view, params)
        attached = true
    }

    fun remove() {
        if (!attached) return
        runCatching { wm.removeViewImmediate(view) }
        attached = false
    }

    /** 대기: "● 시작"(남색), 기록 중: "■ 확보한 이미지 수"(초록 = 동작 중) */
    fun setRecording(recording: Boolean, savedImages: Int) {
        this.recording = recording
        images = savedImages
        if (!recording) {
            pulsing = false
            view.removeCallbacks(endPulse)
        }
        render()
    }

    /** 화면을 확보했다는 표시: 잠깐 "✓ 수" 로 바뀌었다가 돌아온다. */
    fun pulse() {
        if (!recording) return
        pulsing = true
        render()
        view.removeCallbacks(endPulse)
        view.postDelayed(endPulse, PULSE_MS)
    }

    private fun render() {
        view.text = when {
            !recording -> "● 시작"
            pulsing -> "✓ $images"
            else -> "■ $images"
        }
        shape.setColor(
            when {
                !recording -> IDLE_COLOR
                pulsing -> PULSE_COLOR
                else -> RECORDING_COLOR
            },
        )
        view.contentDescription = if (recording) "QA 기록 중, 확보한 이미지 ${images}장. 누르면 정지" else "QA 기록 시작"
    }

    /** [reason] 으로 숨긴다(창 알파와 View 를 함께). 이미 숨겨져 있으면 그대로. */
    fun hide(reason: Hide) {
        if (!attached) return
        val wasVisible = hideReasons.isEmpty()
        hideReasons += reason
        if (!wasVisible) return
        hiddenSince = SystemClock.uptimeMillis()
        view.visibility = View.INVISIBLE
        params.alpha = 0f
        wm.updateViewLayout(view, params)
    }

    /** [reason] 을 거둔다. 숨길 이유가 더 없으면 다시 보인다. */
    fun show(reason: Hide) {
        if (!attached || !hideReasons.remove(reason) || hideReasons.isNotEmpty()) return
        hiddenSince = 0
        view.visibility = View.VISIBLE
        params.alpha = 1f
        wm.updateViewLayout(view, params)
    }

    /** 화면에서 버튼이 차지하는 영역(px, 여백 포함). 버튼을 숨기지 않고 찍은 비교 화면에서는 이 영역을 비교하지 않는다. */
    fun screenBox(): FrameDiff.Box? {
        if (!attached || view.width == 0) return null
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val m = CaptureConfig.OVERLAY_MASK_MARGIN_PX
        return FrameDiff.Box(loc[0] - m, loc[1] - m, loc[0] + view.width + m, loc[1] + view.height + m)
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
        // 앱 색과 맞춘다: 남색(primary), 초록(동작 중). 빨강은 "서비스 꺼짐" 전용이라 쓰지 않는다.
        const val IDLE_COLOR = 0xE624457A.toInt()
        const val RECORDING_COLOR = 0xE61E7E34.toInt()
        const val PULSE_COLOR = 0xF243A047.toInt()
        const val PULSE_MS = 600L
    }
}
