package com.qa.autocapture

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.qa.autocapture.capture.CaptureService
import com.qa.autocapture.capture.CaptureTileService
import com.qa.autocapture.storage.EvidenceStore
import com.qa.autocapture.storage.Options
import com.qa.autocapture.storage.SessionData
import com.qa.autocapture.ui.SessionActivity
import com.qa.autocapture.ui.SettingsActivity
import com.qa.autocapture.ui.Ui
import com.qa.autocapture.ui.Ui.ButtonStyle
import com.qa.autocapture.ui.Ui.Type

/**
 * 앱 메인 화면(레이아웃 XML 없이 코드로 구성). 위에서부터
 * 1) 상태 카드 + 마스터 스위치  2) 설정 안내(4단계, 다 끝나면 접힘)  3) 저장된 세션 목록  4) 개인정보 및 권한.
 * 타이밍·디버그 로그 설정은 오른쪽 위 설정 화면(SettingsActivity)에 있다.
 */
class MainActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var options: Options
    private lateinit var store: EvidenceStore

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusBody: TextView
    private lateinit var masterSwitch: Switch
    private var bindingSwitch = false

    private lateinit var setupCard: LinearLayout
    private lateinit var setupBody: LinearLayout
    private lateinit var setupProgress: TextView
    private lateinit var setupChevron: View
    private val steps = ArrayList<Step>()
    private var setupExpanded = true

    /** 마지막으로 반영한 "모두 완료" 여부. 바뀔 때만 펼침/접힘을 기본값으로 되돌린다(사용자가 직접 연 상태 유지). */
    private var setupAllDone: Boolean? = null

    private lateinit var sessionList: LinearLayout
    private var wasRecording = false

    /** 설정 단계 1개. [done] 은 화면에 돌아올 때마다 다시 확인한다. */
    private class Step(val badgeHolder: LinearLayout, val number: Int, val done: () -> Boolean)

    /** 서비스 상태(연결·기록·저장 수)가 바뀌면 호출된다. 기록이 끝나면 세션 목록도 새로 읽는다. */
    private val stateListener: () -> Unit = {
        refreshStatus()
        val recording = CaptureService.recordingImages != null
        if (wasRecording != recording) refreshSessions()
        wasRecording = recording
    }

    /** 빠른 설정 타일로 마스터 스위치를 바꾸면 바로 반영한다. */
    private val optionsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Options.KEY_CAPTURE_ENABLED) refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        options = Options(this)
        store = EvidenceStore(this)

        val content = ui.column()
        content.addView(statusCard())
        content.addView(setupCard())
        content.addView(ui.sectionTitle("저장된 세션"))
        sessionList = ui.card(padding = 0)
        content.addView(sessionList)
        content.addView(ui.sectionTitle("개인정보 및 권한"))
        content.addView(privacyCard())
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        content.addView(
            ui.text("버전 $version", Type.BODY_SMALL, R.color.on_surface_variant).apply {
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(8), 0, 0)
            },
            LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP),
        )

        val bar = ui.appBar(getString(R.string.app_name), logo = true)
        bar.addView(ui.iconButton(R.drawable.ic_settings, "설정") { startActivity(Intent(this, SettingsActivity::class.java)) })
        setContentView(ui.page(bar, ui.scroll(content)))
    }

    override fun onStart() {
        super.onStart()
        CaptureService.addStateListener(stateListener)
        options.listen(optionsListener)
    }

    override fun onResume() {
        super.onResume()
        wasRecording = CaptureService.recordingImages != null
        refreshStatus()
        refreshSteps()
        refreshSessions()
    }

    override fun onStop() {
        CaptureService.removeStateListener(stateListener)
        options.unlisten(optionsListener)
        super.onStop()
    }

    // ── 1) 상태 카드 ─────────────────────────────────────────────

    private fun statusCard(): View {
        val card = ui.card()
        val top = ui.row()
        statusDot = ui.dot(ui.color(R.color.status_paused))
        top.addView(statusDot)
        statusTitle = ui.text("", Type.TITLE_MEDIUM)
        top.addView(statusTitle, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { marginStart = ui.dp(12) })
        top.addView(ui.text("캡처", Type.LABEL_LARGE, R.color.on_surface_variant).apply { setPadding(0, 0, ui.dp(8), 0) })
        masterSwitch = ui.switch(options.captureEnabled, "캡처 켜기·일시정지") { on ->
            if (bindingSwitch) return@switch
            options.captureEnabled = on
            CaptureTileService.refresh(this)
            refreshStatus()
        }
        top.addView(masterSwitch)
        card.addView(top)
        statusBody = ui.text("", Type.BODY_MEDIUM, R.color.on_surface_variant)
        card.addView(statusBody, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8) })
        return card
    }

    private fun refreshStatus() {
        val serviceOn = CaptureService.isEnabled(this)
        val captureOn = options.captureEnabled
        val recorded = CaptureService.recordingImages
        val (colorRes, title, body) = when {
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
                "이미지 ${recorded}장 저장됨. 플로팅 버튼을 누르면 기록을 정지하고 저장합니다.",
            )
            else -> Triple(
                R.color.status_running,
                "동작 중",
                "화면의 플로팅 버튼 [● 시작]을 누르면 기록을 시작합니다. 화면이 바뀔 때마다 캡처하고, " +
                    "화면이 멈추면 최종 화면(_final)을 1장 더 저장합니다. 버튼을 길게 누르면 이 화면이 열립니다.",
            )
        }
        statusDot.background.setTint(ui.color(colorRes))
        statusTitle.text = title
        statusTitle.setTextColor(ui.color(colorRes))
        statusBody.text = body
        if (masterSwitch.isChecked != captureOn) {
            bindingSwitch = true
            masterSwitch.isChecked = captureOn
            bindingSwitch = false
        }
    }

    // ── 2) 설정 안내 ─────────────────────────────────────────────

    private fun setupCard(): View {
        setupCard = ui.card()
        val head = ui.row().apply {
            background = ui.rowBackground()
            setOnClickListener { setSetupExpanded(!setupExpanded) }
        }
        val titles = ui.column()
        titles.addView(ui.text("설정 안내", Type.TITLE_MEDIUM))
        setupProgress = ui.text("", Type.BODY_SMALL, R.color.on_surface_variant)
        titles.addView(setupProgress)
        head.addView(titles, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        setupChevron = ui.icon(R.drawable.ic_chevron, R.color.on_surface_variant)
        head.addView(setupChevron)
        setupCard.addView(head)
        setupCard.addView(
            ui.text("앱을 업데이트하면 이 과정을 다시 해야 합니다.", Type.LABEL_LARGE, R.color.primary),
            LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8) },
        )

        setupBody = ui.column()
        setupCard.addView(setupBody)
        val accessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        val appInfo = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) }
        val app = getString(R.string.app_name)
        val serviceOn = { CaptureService.isEnabled(this) }
        addStep(
            1,
            "접근성 설정에서 앱을 한 번 누르기",
            "[접근성 설정 열기] → 설치된 앱 → \"$app\"를 한 번 누릅니다. \"제한된 설정\" 팝업이 뜨면 [확인]을 누릅니다. " +
                "이걸 먼저 해야 2단계의 \"제한된 설정 허용\" 메뉴가 나타납니다.",
            "접근성 설정 열기",
            accessibility,
            serviceOn,
        )
        addStep(
            2,
            "제한된 설정 허용",
            "[앱 정보 열기] → 오른쪽 위 ⋮ → \"제한된 설정 허용\" → PIN(또는 지문)을 입력합니다. ⋮ 메뉴에 항목이 없으면 1단계를 먼저 하세요.",
            "앱 정보 열기",
            appInfo,
            serviceOn,
        )
        addStep(
            3,
            "접근성 서비스 켜기",
            "[접근성 설정 열기] → 설치된 앱 → $app → 스위치를 켜고 [허용]을 누릅니다. 켜지면 1~3단계가 자동으로 완료 표시됩니다.",
            "접근성 설정 열기",
            accessibility,
            serviceOn,
        )
        addStep(
            4,
            "배터리 \"제한 없음\", 사용하지 않는 앱 관리 끄기",
            "[앱 정보 열기] → 배터리 → \"제한 없음\"을 고릅니다. 앱 정보로 돌아와 \"사용하지 않는 앱 관리\"를 끕니다. " +
                "서비스가 중간에 꺼지지 않게 합니다.",
            "앱 정보 열기",
            appInfo,
        ) { getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true }
        return setupCard
    }

    private fun addStep(number: Int, title: String, help: String, actionLabel: String, action: () -> Unit, done: () -> Boolean) {
        val row = ui.row().apply {
            gravity = Gravity.TOP
            setPadding(0, ui.dp(16), 0, 0)
        }
        val badgeHolder = LinearLayout(this)
        row.addView(badgeHolder)
        val texts = ui.column()
        texts.addView(ui.text(title, Type.LABEL_LARGE))
        texts.addView(ui.text(help, Type.BODY_SMALL, R.color.on_surface_variant).apply { setPadding(0, ui.dp(2), 0, ui.dp(8)) })
        texts.addView(ui.button(actionLabel, ButtonStyle.TONAL, action), LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        row.addView(texts, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { marginStart = ui.dp(12) })
        setupBody.addView(row)
        steps += Step(badgeHolder, number, done)
    }

    private fun refreshSteps() {
        var completed = 0
        for (step in steps) {
            val done = step.done()
            if (done) completed++
            step.badgeHolder.removeAllViews()
            step.badgeHolder.addView(ui.stepBadge(step.number, done))
        }
        val allDone = completed == steps.size
        setupProgress.text = if (allDone) "${steps.size}단계 모두 완료" else "${steps.size}단계 중 ${completed}단계 완료"
        // 서비스가 꺼져 있으면(미완료) 펼치고, 다 끝나면 접는다
        if (allDone != setupAllDone) {
            setupAllDone = allDone
            setSetupExpanded(!allDone)
        }
    }

    private fun setSetupExpanded(expanded: Boolean) {
        setupExpanded = expanded
        setupBody.visibility = if (expanded) View.VISIBLE else View.GONE
        setupChevron.rotation = if (expanded) 270f else 90f
        setupChevron.contentDescription = if (expanded) "설정 안내 접기" else "설정 안내 펼치기"
    }

    // ── 3) 저장된 세션 ───────────────────────────────────────────

    private fun refreshSessions() {
        sessionList.removeAllViews()
        val sessions = store.loadSessions()
        if (sessions.isEmpty()) {
            sessionList.addView(
                ui.text("아직 저장된 세션이 없습니다. 플로팅 버튼으로 기록을 시작하세요.", Type.BODY_MEDIUM, R.color.on_surface_variant).apply {
                    setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
                },
            )
            return
        }
        sessions.forEachIndexed { i, s ->
            if (i > 0) sessionList.addView(ui.divider())
            sessionList.addView(sessionRow(s))
        }
    }

    private fun sessionRow(s: SessionData): View {
        val open = { startActivity(SessionActivity.intent(this, s.id)) }
        val row = ui.row().apply {
            setPadding(ui.dp(16), ui.dp(12), ui.dp(8), ui.dp(12))
            background = ui.rowBackground()
            setOnClickListener { open() }
        }
        val texts = ui.column()
        texts.addView(ui.text(EvidenceStore.format(s.startedAt, "yyyy-MM-dd HH:mm:ss"), Type.BODY_LARGE))
        texts.addView(ui.text(sessionDetail(s), Type.BODY_SMALL, R.color.on_surface_variant))
        row.addView(texts, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        row.addView(ui.button("열기", ButtonStyle.TEXT, open))
        return row
    }

    private fun sessionDetail(s: SessionData) = buildString {
        append("이미지 ").append(s.imageCount).append("장 · 화면 ").append(s.screens.size).append("개")
        val end = s.endedAt
        if (!s.ended) {
            append(" · 기록 중")
        } else if (end != null) {
            append(" · ").append(maxOf(1L, (end - s.startedAt + 59_999) / 60_000)).append("분")
        }
    }

    // ── 4) 개인정보 및 권한 ──────────────────────────────────────

    private fun privacyCard(): View {
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
                ui.text("• " + getString(res), Type.BODY_SMALL, R.color.on_surface_variant),
                LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8) },
            )
        }
        card.addView(
            ui.text("사용자가 켜는 권한은 접근성 서비스 1개뿐입니다(저장소·알림·오버레이 권한 없음).", Type.BODY_SMALL, R.color.on_surface_variant),
            LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8) },
        )
        return card
    }
}
