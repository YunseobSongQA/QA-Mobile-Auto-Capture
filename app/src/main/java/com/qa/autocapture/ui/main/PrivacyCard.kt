package com.qa.autocapture.ui.main

import android.app.Activity
import android.os.Build
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
            ui.text(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "사용자가 켜는 권한은 접근성 서비스 1개뿐입니다(저장소·알림·오버레이 권한 없음)." else LEGACY_PERMISSIONS, Type.BODY_SMALL, R.color.on_surface_variant),
            LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8) },
        )
        view = card
    }

    private companion object {
        /** API 26~29: 화면 녹화로 찍으므로 기록 중 알림, Android 9 이하는 저장공간 권한이 더 필요하다. */
        const val LEGACY_PERMISSIONS = "사용자가 켜는 권한은 접근성 서비스입니다. Android 10 이하에서는 기록할 때마다 화면 녹화를 허용하고 기록하는 동안 알림이 보이며, " +
            "Android 9 이하에서는 저장공간 권한도 씁니다(오버레이 권한 없음)."
    }
}
