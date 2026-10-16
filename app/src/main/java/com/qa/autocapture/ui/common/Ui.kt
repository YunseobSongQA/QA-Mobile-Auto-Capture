package com.qa.autocapture.ui.common

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.qa.autocapture.R

/**
 * 화면 공통 뷰 도우미(레이아웃 XML 없이 코드로 구성, 외부 라이브러리 없음).
 * Material 3 의 색 역할(colors.xml), 글자 크기 단계, 모양(카드 12dp·버튼 20dp·스위치 16dp 둥근 모서리),
 * 4dp 배수 여백(화면 가장자리 16dp, 카드 안 16dp, 카드 사이 12dp)을 따른다.
 */
class Ui(private val activity: Activity) {

    /** Material 3 글자 크기 단계. */
    enum class Type(val sp: Float, val medium: Boolean) {
        TITLE_LARGE(22f, false),
        TITLE_MEDIUM(16f, true),
        BODY_LARGE(16f, false),
        BODY_MEDIUM(14f, false),
        BODY_SMALL(12f, false),
        LABEL_LARGE(14f, true),
    }

    enum class ButtonStyle { FILLED, TONAL, TEXT }

    private val density = activity.resources.displayMetrics.density

    fun dp(v: Int) = (v * density + 0.5f).toInt()

    fun color(id: Int) = activity.getColor(id)

    fun text(s: CharSequence, type: Type = Type.BODY_MEDIUM, colorRes: Int = R.color.on_surface) = TextView(activity).apply {
        text = s
        textSize = type.sp
        typeface = if (type.medium) MEDIUM else Typeface.DEFAULT
        setTextColor(color(colorRes))
        setLineSpacing(0f, 1.25f)
    }

    fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    fun row() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 테두리 카드(surface + outlineVariant 1dp, 12dp 모서리). 아래로 12dp 띄운다. */
    fun card(padding: Int = 16) = column().apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(color(R.color.surface))
            setStroke(dp(1), color(R.color.outline_variant))
        }
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
    }

    /** 카드 위의 섹션 제목. */
    fun sectionTitle(s: String) = text(s, Type.LABEL_LARGE, R.color.primary).apply {
        setPadding(dp(4), dp(12), dp(4), dp(8))
    }

    fun divider() = View(activity).apply {
        setBackgroundColor(color(R.color.outline_variant))
        layoutParams = LinearLayout.LayoutParams(MATCH, maxOf(1, dp(1) / 2))
    }

    fun button(label: String, style: ButtonStyle, onClick: () -> Unit) = TextView(activity).apply {
        text = label
        textSize = 14f
        typeface = MEDIUM
        gravity = Gravity.CENTER
        maxLines = 1
        minHeight = dp(40)
        minimumHeight = dp(40)
        val side = dp(if (style == ButtonStyle.TEXT) 12 else 20)
        setPadding(side, 0, side, 0)
        val (bg, fg) = when (style) {
            ButtonStyle.FILLED -> color(R.color.primary) to color(R.color.on_primary)
            ButtonStyle.TONAL -> color(R.color.secondary_container) to color(R.color.on_secondary_container)
            ButtonStyle.TEXT -> Color.TRANSPARENT to color(R.color.primary)
        }
        setTextColor(fg)
        background = ripple(rounded(bg, 20), fg, 20)
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** 48dp 터치 영역의 아이콘 버튼. */
    fun iconButton(icon: Int, description: String, tintRes: Int = R.color.on_surface_variant, onClick: () -> Unit) =
        ImageButton(activity).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(color(tintRes))
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER
            // 내용·마스크 없는 RippleDrawable = 테두리 없는 물결 효과
            background = RippleDrawable(ColorStateList.valueOf(withAlpha(color(R.color.on_surface), 0.12f)), null, null)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { onClick() }
        }

    fun icon(icon: Int, tintRes: Int, sizeDp: Int = 24) = ImageView(activity).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(color(tintRes))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** 단계 표시: 완료면 체크, 아니면 번호. */
    fun stepBadge(number: Int, done: Boolean): View {
        val size = dp(28)
        val view = if (done) {
            ImageView(activity).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(color(R.color.on_primary))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(4), dp(4), dp(4), dp(4))
                contentDescription = "완료"
            }
        } else {
            text(number.toString(), Type.LABEL_LARGE, R.color.on_secondary_container).apply { gravity = Gravity.CENTER }
        }
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(if (done) R.color.primary else R.color.secondary_container))
        }
        view.layoutParams = LinearLayout.LayoutParams(size, size)
        return view
    }

    /** 상태 점(동작 중/일시정지/서비스 꺼짐 색). */
    fun dot(colorInt: Int, sizeDp: Int = 12) = View(activity).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorInt)
        }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Material 3 모양의 스위치(트랙 52x32dp, 켜짐 = primary). */
    fun switch(checked: Boolean, description: String, onChange: (Boolean) -> Unit) = Switch(activity).apply {
        showText = false
        trackDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), rounded(color(R.color.primary), 16).apply { setSize(dp(52), dp(32)) })
            addState(
                intArrayOf(),
                rounded(color(R.color.secondary_container), 16).apply {
                    setStroke(dp(2), color(R.color.outline))
                    setSize(dp(52), dp(32))
                },
            )
        }
        thumbDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), thumb(20, R.color.on_primary))
            addState(intArrayOf(), thumb(16, R.color.outline))
        }
        switchMinWidth = dp(52)
        isChecked = checked
        contentDescription = description
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    /** 26x32dp 안에 지름 [size] 의 원(스위치 손잡이). */
    private fun thumb(size: Int, colorRes: Int): Drawable {
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(colorRes))
            setSize(dp(size), dp(size))
        }
        val h = dp((26 - size) / 2)
        val v = dp((32 - size) / 2)
        return InsetDrawable(circle, h, v, h, v)
    }

    fun seekBar(max: Int, progress: Int, onChange: (Int) -> Unit) = SeekBar(activity).apply {
        this.max = max
        this.progress = progress
        progressTintList = ColorStateList.valueOf(color(R.color.primary))
        thumbTintList = ColorStateList.valueOf(color(R.color.primary))
        progressBackgroundTintList = ColorStateList.valueOf(color(R.color.outline))
        setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) onChange(value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            },
        )
    }

    /** 상단 앱바(64dp). [onBack] 이 있으면 뒤로 버튼, [logo] 면 앱 아이콘을 앞에 둔다. 오른쪽 동작은 호출자가 addView. */
    fun appBar(title: String, onBack: (() -> Unit)? = null, logo: Boolean = false) = row().apply {
        minimumHeight = dp(64)
        setPadding(dp(if (onBack != null) 4 else 16), 0, dp(4), 0)
        if (onBack != null) addView(iconButton(R.drawable.ic_back, "뒤로", R.color.on_surface, onBack))
        if (logo) {
            addView(
                ImageView(activity).apply {
                    setImageResource(R.mipmap.ic_launcher)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) },
            )
        }
        val titleView = text(title, Type.TITLE_LARGE).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            if (onBack != null) setPadding(dp(4), 0, 0, 0)
        }
        addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    /** 세로 스크롤 본문. 가장자리 16dp. */
    fun scroll(content: LinearLayout) = ScrollView(activity).apply {
        clipToPadding = false
        content.setPadding(dp(16), dp(4), dp(16), dp(24))
        addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
    }

    /** 앱바 + 본문. targetSdk 35 는 edge-to-edge 가 강제되므로 시스템바·카메라 컷아웃만큼 띄운다. */
    fun page(appBar: View, body: View): View {
        val root = column()
        root.addView(appBar, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
        if (body is ViewGroup) body.clipToPadding = false
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            body.setPadding(body.paddingLeft, body.paddingTop, body.paddingRight, bars.bottom)
            insets
        }
        return root
    }

    /** 누를 수 있는 목록 행 배경(물결 효과). */
    fun rowBackground(): Drawable = ripple(null, color(R.color.on_surface), 0)

    fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.38f
    }

    private fun rounded(colorInt: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(colorInt)
    }

    private fun ripple(content: Drawable?, base: Int, radiusDp: Int) =
        RippleDrawable(ColorStateList.valueOf(withAlpha(base, 0.12f)), content, rounded(Color.WHITE, radiusDp))

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val MEDIUM: Typeface = Typeface.create(Typeface.DEFAULT, 500, false)

        fun withAlpha(colorInt: Int, alpha: Float) = (colorInt and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
    }
}
