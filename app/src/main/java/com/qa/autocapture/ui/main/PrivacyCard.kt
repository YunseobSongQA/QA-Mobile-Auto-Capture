package com.qa.autocapture.ui.main

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import com.qa.autocapture.R
import com.qa.autocapture.ui.common.Ui
import com.qa.autocapture.ui.common.Ui.Type

/** 메인 화면 4) 개인정보 및 권한: 접근성으로 읽는 정보, 저장 위치, 외부 전송 없음. 문구는 strings.xml(서비스 설명과 공유). */
class PrivacyCard(activity: Activity, ui: Ui) {

    val view: View

    init {
        val card = ui.card()
        val head = ui.row()
        head.addView(ui.icon(R.drawable.ic_shield, R.color.primary, 20))
        head.addView(
            ui.text("증적은 이 기기 안에만 저장됩니다", Type.LABEL_LARGE),
            LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { marginStart = ui.dp(8) },
        )
        card.addView(head)
        for (res in intArrayOf(R.string.privacy_reads, R.string.privacy_storage, R.string.privacy_network)) {
            card.addView(
                ui.text("• " + activity.getString(res), Type.BODY_SMALL, R.color.on_surface_variant),
                LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8) },
            )
        }
        card.addView(
            ui.text("사용자가 켜는 권한은 접근성 서비스 1개뿐입니다(저장소·알림·오버레이 권한 없음).", Type.BODY_SMALL, R.color.on_surface_variant),
            LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8) },
        )
        view = card
    }
}
