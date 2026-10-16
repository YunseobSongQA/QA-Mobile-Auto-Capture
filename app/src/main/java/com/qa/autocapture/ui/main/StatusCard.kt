package com.qa.autocapture.ui.main

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.qa.autocapture.R
import com.qa.autocapture.capture.CaptureStatus
import com.qa.autocapture.capture.CaptureTileService
import com.qa.autocapture.storage.Options
import com.qa.autocapture.ui.common.Ui
import com.qa.autocapture.ui.common.Ui.Type

/** 메인 화면 1) 상태 카드: 서비스 상태(동작 중 / 기록 중 / 일시정지 / 서비스 꺼짐)와 캡처 마스터 스위치. */
class StatusCard(private val activity: Activity, private val ui: Ui, private val options: Options) {

    val view: View
    private val dot: View
    private val title: TextView
    private val body: TextView
    private val masterSwitch: Switch
    private var binding = false

    init {
        val card = ui.card()
        val top = ui.row()
        dot = ui.dot(ui.color(R.color.status_paused))
        top.addView(dot)
        title = ui.text("", Type.TITLE_MEDIUM)
        top.addView(title, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { marginStart = ui.dp(12) })
        top.addView(ui.text("캡처", Type.LABEL_LARGE, R.color.on_surface_variant).apply { setPadding(0, 0, ui.dp(8), 0) })
        masterSwitch = ui.switch(options.captureEnabled, "캡처 켜기·일시정지") { on ->
            if (binding) return@switch
            options.captureEnabled = on
            CaptureTileService.refresh(activity)
            refresh()
        }
        top.addView(masterSwitch)
        card.addView(top)
        body = ui.text("", Type.BODY_MEDIUM, R.color.on_surface_variant)
        card.addView(body, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8) })
        view = card
    }

    fun refresh() {
        val serviceOn = CaptureStatus.isServiceEnabled(activity)
        val captureOn = options.captureEnabled
        val recorded = CaptureStatus.recordingScreens
        val (colorRes, titleText, bodyText) = when {
            !serviceOn -> Triple(
                R.color.status_off,
                "서비스 꺼짐",
                "접근성 서비스가 꺼져 있어 캡처할 수 없습니다. 아래 설정 안내를 1단계부터 따라 하세요. " +
                    "앱을 업데이트한 뒤에도 다시 꺼질 수 있습니다.",
            )
            !captureOn -> Triple(
                R.color.status_paused,
                "일시정지",
                "접근성 서비스는 켜 둔 채 캡처만 멈췄습니다. 플로팅 버튼을 숨기고 화면 감지와 캡처를 하지 않습니다. " +
                    "스위치나 알림창 빠른 설정의 \"QA 캡처\" 타일로 다시 켤 수 있습니다.",
            )
            recorded != null -> Triple(
                R.color.status_running,
                "동작 중 · 기록 중",
                "화면 ${recorded}개 기록. 화면을 떠나면 그 화면 사진(스크롤한 부분은 이어붙여 1장)이 저장됩니다. 플로팅 버튼을 누르면 기록을 정지하고 저장합니다.",
            )
            else -> Triple(
                R.color.status_running,
                "동작 중",
                "화면의 플로팅 버튼 [● 시작]을 누르면 기록을 시작합니다. 화면에 들어가면 1장, 스크롤을 멈출 때마다 1장 찍고 " +
                    "그 화면을 떠날 때 겹치는 부분을 이어붙여 화면당 1장으로 저장합니다. 버튼을 길게 누르면 이 화면이 열립니다.",
            )
        }
        dot.background.setTint(ui.color(colorRes))
        title.text = titleText
        title.setTextColor(ui.color(colorRes))
        body.text = bodyText
        if (masterSwitch.isChecked != captureOn) {
            binding = true
            masterSwitch.isChecked = captureOn
            binding = false
        }
    }
}
