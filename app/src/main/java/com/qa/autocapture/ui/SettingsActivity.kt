package com.qa.autocapture.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.qa.autocapture.R
import com.qa.autocapture.capture.CaptureConfig
import com.qa.autocapture.storage.EvidenceStore
import com.qa.autocapture.storage.Options
import com.qa.autocapture.ui.Ui.ButtonStyle
import com.qa.autocapture.ui.Ui.Type
import java.util.Locale

/** 설정 화면: 최종 캡처 타이밍(SETTLE_MS, MAX_WAIT_MS)과 디버그 로그. 값은 Options 에 바로 저장된다. */
class SettingsActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var options: Options
    private val refreshers = ArrayList<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        options = Options(this)

        val content = ui.column()
        content.addView(ui.sectionTitle("최종 캡처 타이밍"))
        val timing = ui.card()
        timing.addView(
            slider(
                "안정화 대기 (SETTLE_MS)",
                "화면 변화가 이 시간 동안 없으면 최종 화면을 1장 더 저장합니다(파일명 끝 _final).",
                CaptureConfig.SETTLE_MIN_MS,
                CaptureConfig.SETTLE_MAX_MS,
                CaptureConfig.SETTLE_STEP_MS,
                { options.settleMs },
                { options.settleMs = it },
            ),
        )
        timing.addView(
            ui.divider().apply {
                val lp = layoutParams as LinearLayout.LayoutParams
                lp.topMargin = ui.dp(16)
                lp.bottomMargin = ui.dp(16)
            },
        )
        timing.addView(
            slider(
                "최대 대기 (MAX_WAIT_MS)",
                "변화가 계속돼도(자동 넘김 배너, 로딩 애니메이션 등) 이 시간이 지나면 그 시점 화면을 _final 로 저장합니다.",
                CaptureConfig.MAX_WAIT_MIN_MS,
                CaptureConfig.MAX_WAIT_MAX_MS,
                CaptureConfig.MAX_WAIT_STEP_MS,
                { options.maxWaitMs },
                { options.maxWaitMs = it },
            ),
        )
        val reset = ui.button("기본값으로 되돌리기", ButtonStyle.TEXT) {
            options.resetTiming()
            refreshers.forEach { it() }
        }
        timing.addView(reset, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply {
            gravity = Gravity.END
            topMargin = ui.dp(8)
        })
        content.addView(timing)
        content.addView(
            ui.text(
                "기본값: 안정화 대기 ${seconds(CaptureConfig.SETTLE_MS)}, 최대 대기 ${seconds(CaptureConfig.MAX_WAIT_MS)}. 바꾼 값은 바로 적용됩니다.",
                Type.BODY_SMALL,
                R.color.on_surface_variant,
            ).apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) },
        )

        content.addView(ui.sectionTitle("기록"))
        val record = ui.card()
        val row = ui.row()
        val texts = ui.column()
        texts.addView(ui.text("디버그 로그", Type.BODY_LARGE))
        texts.addView(
            ui.text(
                "기록 중 받은 모든 접근성 이벤트(시각, 종류, 패키지, 클래스, 텍스트)와 캡처 시점을 " +
                    "내장 저장공간/${EvidenceStore.DEBUG_PATH}{세션}.txt 로 저장합니다. " +
                    "다음 기록 시작부터 적용되고, 켜 두면 배터리를 더 씁니다.",
                Type.BODY_SMALL,
                R.color.on_surface_variant,
            ).apply { setPadding(0, ui.dp(2), 0, 0) },
        )
        row.addView(texts, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { marginEnd = ui.dp(16) })
        row.addView(ui.switch(options.debugLog, "디버그 로그") { options.debugLog = it })
        record.addView(row)
        content.addView(record)

        setContentView(ui.page(ui.appBar("설정", onBack = ::finish), ui.scroll(content)))
    }

    /** 제목 + 현재 값 + 슬라이더 + 설명. 값은 [step] 단위로 [min]~[max]. */
    private fun slider(
        title: String,
        help: String,
        min: Long,
        max: Long,
        step: Long,
        get: () -> Long,
        set: (Long) -> Unit,
    ): LinearLayout {
        val box = ui.column()
        val head = ui.row()
        head.addView(ui.text(title, Type.BODY_LARGE), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val value: TextView = ui.text("", Type.LABEL_LARGE, R.color.primary)
        head.addView(value)
        box.addView(head)
        val bar: SeekBar = ui.seekBar(((max - min) / step).toInt(), 0) { p ->
            val v = min + p * step
            set(v)
            value.text = label(v)
        }
        box.addView(bar, LinearLayout.LayoutParams(Ui.MATCH, ui.dp(40)))
        box.addView(ui.text(help, Type.BODY_SMALL, R.color.on_surface_variant))
        val refresh = {
            val v = get()
            bar.progress = ((v - min) / step).toInt()
            value.text = label(v)
        }
        refresh()
        refreshers += refresh
        return box
    }

    private fun label(ms: Long) = "${seconds(ms)} (${ms}ms)"

    private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.1f초", ms / 1000.0)
}
