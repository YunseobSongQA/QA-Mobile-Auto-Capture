package com.qa.autocapture.ui.main

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import com.qa.autocapture.R
import com.qa.autocapture.storage.SessionData
import com.qa.autocapture.storage.SessionStore
import com.qa.autocapture.storage.Times
import com.qa.autocapture.ui.common.Ui
import com.qa.autocapture.ui.common.Ui.ButtonStyle
import com.qa.autocapture.ui.common.Ui.Type
import com.qa.autocapture.ui.session.SessionActivity

/** 메인 화면 3) 저장된 세션 목록: 시작 시각, 이미지·화면 수, 기록 시간(또는 기록 중), [열기] → SessionActivity. */
class SessionListCard(private val activity: Activity, private val ui: Ui) {

    private val store = SessionStore(activity)
    val view: LinearLayout = ui.card(padding = 0)

    fun refresh() {
        view.removeAllViews()
        val sessions = store.loadAll()
        if (sessions.isEmpty()) {
            view.addView(
                ui.text("아직 저장된 세션이 없습니다. 플로팅 버튼으로 기록을 시작하세요.", Type.BODY_MEDIUM, R.color.on_surface_variant).apply {
                    setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
                },
            )
            return
        }
        sessions.forEachIndexed { i, s ->
            if (i > 0) view.addView(ui.divider())
            view.addView(row(s))
        }
    }

    private fun row(s: SessionData): View {
        val open = { activity.startActivity(SessionActivity.intent(activity, s.id)) }
        val row = ui.row().apply {
            setPadding(ui.dp(16), ui.dp(12), ui.dp(8), ui.dp(12))
            background = ui.rowBackground()
            setOnClickListener { open() }
        }
        val texts = ui.column()
        texts.addView(ui.text(Times.format(s.startedAt, "yyyy-MM-dd HH:mm:ss"), Type.BODY_LARGE))
        texts.addView(ui.text(detail(s), Type.BODY_SMALL, R.color.on_surface_variant))
        row.addView(texts, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        row.addView(ui.button("열기", ButtonStyle.TEXT, open))
        return row
    }

    private fun detail(s: SessionData) = buildString {
        append("이미지 ").append(s.imageCount).append("장 · 화면 ").append(s.screens.size).append("개")
        val end = s.endedAt
        if (!s.ended) {
            append(" · 기록 중")
        } else if (end != null) {
            append(" · ").append(maxOf(1L, (end - s.startedAt + 59_999) / 60_000)).append("분")
        }
    }
}
