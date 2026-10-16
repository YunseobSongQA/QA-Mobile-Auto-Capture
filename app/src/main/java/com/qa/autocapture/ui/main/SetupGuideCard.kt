package com.qa.autocapture.ui.main

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.qa.autocapture.R
import com.qa.autocapture.capture.CaptureStatus
import com.qa.autocapture.ui.common.Ui
import com.qa.autocapture.ui.common.Ui.ButtonStyle
import com.qa.autocapture.ui.common.Ui.Type

/**
 * 메인 화면 2) 설정 안내 카드: ① 제한된 설정 허용 → ② 접근성 서비스 켜기 → ③ 배터리 제한 없음.
 * 단계마다 구체적인 메뉴 경로와 해당 설정 화면으로 바로 가는 버튼이 있다.
 * ①②는 접근성 서비스가 켜지면 완료(제한된 설정을 직접 확인하는 공개 API 가 없음), ③은 배터리 최적화 제외 여부로 판단한다.
 * 미완료면 펼치고 모두 완료면 접는다(제목 줄을 눌러 직접 바꿀 수 있음).
 */
class SetupGuideCard(private val activity: Activity, private val ui: Ui) {

    /** 설정 단계 1개. [done] 은 화면에 돌아올 때마다 다시 확인한다. */
    private class Step(val badgeHolder: LinearLayout, val number: Int, val done: () -> Boolean)

    val view: View
    private val body: LinearLayout
    private val progress: TextView
    private val chevron: View
    private val steps = ArrayList<Step>()
    private var expanded = true

    /** 마지막으로 반영한 "모두 완료" 여부. 바뀔 때만 펼침/접힘을 기본값으로 되돌린다(사용자가 직접 연 상태 유지). */
    private var allDone: Boolean? = null

    init {
        val card = ui.card()
        val head = ui.row().apply {
            background = ui.rowBackground()
            setOnClickListener { setExpanded(!expanded) }
        }
        val titles = ui.column()
        titles.addView(ui.text("설정 안내", Type.TITLE_MEDIUM))
        progress = ui.text("", Type.BODY_SMALL, R.color.on_surface_variant)
        titles.addView(progress)
        head.addView(titles, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        chevron = ui.icon(R.drawable.ic_chevron, R.color.on_surface_variant)
        head.addView(chevron)
        card.addView(head)
        card.addView(
            ui.text("앱을 업데이트하면 이 과정을 다시 해야 합니다.", Type.LABEL_LARGE, R.color.primary),
            LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8) },
        )
        body = ui.column()
        card.addView(body)
        view = card

        val accessibility = { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        val appInfo = {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)))
        }
        val app = activity.getString(R.string.app_name)
        val serviceOn = { CaptureStatus.isServiceEnabled(activity) }
        addStep(
            1,
            "제한된 설정 허용 (가장 먼저)",
            "홈 화면에서 \"$app\" 아이콘을 길게 누르고 ⓘ(앱 정보)를 누릅니다. 또는 설정 → 애플리케이션 → $app 으로 들어갑니다. " +
                "오른쪽 위 ⋮(점 세 개) → \"제한된 설정 허용\" → PIN(또는 지문)을 입력합니다. 아래 [앱 정보 열기]로 바로 갈 수 있습니다.\n" +
                "⋮ 에 \"제한된 설정 허용\"이 없으면: [접근성 설정 열기] → 설치된 앱 → $app 을 한 번 눌러 \"제한된 설정\" 안내를 띄우고 " +
                "[확인]을 누른 뒤 다시 ⋮ 메뉴로 갑니다.",
            listOf("앱 정보 열기" to appInfo, "메뉴가 없을 때: 접근성 설정 열기" to accessibility),
            serviceOn,
        )
        addStep(
            2,
            "접근성 서비스 켜기",
            "[접근성 설정 열기] → 설치된 앱 → $app → 스위치를 켜고 [허용]을 누릅니다. " +
                "켜지면 1·2단계가 자동으로 완료 표시되고 화면 오른쪽에 [● 시작] 버튼이 나타납니다.",
            listOf("접근성 설정 열기" to accessibility),
            serviceOn,
        )
        addStep(
            3,
            "배터리 \"제한 없음\", 사용하지 않는 앱 관리 끄기",
            "[앱 정보 열기] → 배터리 → \"제한 없음\"을 고릅니다. 앱 정보로 돌아와 \"사용하지 않는 앱 관리\"를 끕니다. " +
                "서비스가 중간에 꺼지지 않게 합니다.",
            listOf("앱 정보 열기" to appInfo),
        ) { activity.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(activity.packageName) == true }
    }

    fun refresh() {
        var completed = 0
        for (step in steps) {
            val done = step.done()
            if (done) completed++
            step.badgeHolder.removeAllViews()
            step.badgeHolder.addView(ui.stepBadge(step.number, done))
        }
        val all = completed == steps.size
        progress.text = if (all) "${steps.size}단계 모두 완료" else "${steps.size}단계 중 ${completed}단계 완료"
        // 서비스가 꺼져 있으면(미완료) 펼치고, 다 끝나면 접는다
        if (all != allDone) {
            allDone = all
            setExpanded(!all)
        }
    }

    /** 단계 1개: 번호(완료면 체크), 제목, 구체적인 메뉴 경로, 해당 설정 화면으로 바로 가는 버튼(첫 버튼이 주 동작). */
    private fun addStep(number: Int, title: String, help: String, actions: List<Pair<String, () -> Unit>>, done: () -> Boolean) {
        val row = ui.row().apply {
            gravity = Gravity.TOP
            setPadding(0, ui.dp(16), 0, 0)
        }
        val badgeHolder = LinearLayout(activity)
        row.addView(badgeHolder)
        val texts = ui.column()
        texts.addView(ui.text(title, Type.LABEL_LARGE))
        texts.addView(ui.text(help, Type.BODY_SMALL, R.color.on_surface_variant).apply { setPadding(0, ui.dp(2), 0, ui.dp(8)) })
        actions.forEachIndexed { i, (label, action) ->
            val style = if (i == 0) ButtonStyle.TONAL else ButtonStyle.TEXT
            texts.addView(ui.button(label, style, action), LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { if (i > 0) topMargin = ui.dp(4) })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { marginStart = ui.dp(12) })
        body.addView(row)
        steps += Step(badgeHolder, number, done)
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        body.visibility = if (value) View.VISIBLE else View.GONE
        chevron.rotation = if (value) 270f else 90f
        chevron.contentDescription = if (value) "설정 안내 접기" else "설정 안내 펼치기"
    }
}
