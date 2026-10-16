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
 * 1) 상태 카드 + 마스터 스위치  2) 처음 설정 안내(다 끝나면 숨김)  3) 저장된 세션 목록  4) 개인정보 및 권한.
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
    private lateinit var setupProgress: TextView
    private val steps = ArrayList<Step>()

    private lateinit var sessionList: LinearLayout
    private var wasRecording = false

    /** 설정 단계 1개. [done] 은 화면에 돌아올 때마다 다시 확인한다. */
    private class Step(val badgeHolder: LinearLayout, val action: View, val number: Int, val done: () -> Boolean)

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
                "접근성 서비스가 꺼져 있어 캡처할 수 없습니다. 아래 처음 설정 1단계부터 진행하세요.",
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

    // ── 2) 처음 설정 안내 ────────────────────────────────────────

    private fun setupCard(): View {
        setupCard = ui.card()
        val head = ui.row()
        head.addView(ui.text("처음 설정", Type.TITLE_MEDIUM), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        setupProgress = ui.text("", Type.BODY_SMALL, R.color.on_surface_variant)
        head.addView(setupProgress)
        setupCard.addView(head)

        val appInfo = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) }
        addStep(
            1,
            "접근성 서비스 켜기",
            "설정 → 접근성 → 설치된 앱 → ${getString(R.string.app_name)} → 켜기. \"제한된 설정\" 안내가 뜨면 확인을 누르고 2단계를 먼저 하세요.",
            "접근성 설정",
            { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        ) { CaptureService.isEnabled(this) }
        addStep(
            2,
            "제한된 설정 허용",
            "1단계에서 막히면: 앱 정보 → 오른쪽 위 ⋮ → 제한된 설정 허용 → 다시 1단계. 서비스가 켜지면 완료로 표시됩니다.",
            "앱 정보",
            appInfo,
        ) { CaptureService.isEnabled(this) }
        addStep(
            3,
            "배터리 사용 \"제한 없음\"",
            "앱 정보 → 배터리 → 제한 없음. 배터리 최적화로 서비스가 중간에 꺼지지 않게 합니다.",
            "앱 정보",
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
        val button = ui.button(actionLabel, ButtonStyle.TONAL, action)
        texts.addView(button, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        row.addView(texts, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { marginStart = ui.dp(12) })
        setupCard.addView(row)
        steps += Step(badgeHolder, button, number, done)
    }

    private fun refreshSteps() {
        var completed = 0
        for (step in steps) {
            val done = step.done()
            if (done) completed++
            step.badgeHolder.removeAllViews()
            step.badgeHolder.addView(ui.stepBadge(step.number, done))
            step.action.visibility = if (done) View.GONE else View.VISIBLE
        }
        setupProgress.text = "${steps.size}단계 중 ${completed}단계 완료"
        setupCard.visibility = if (completed == steps.size) View.GONE else View.VISIBLE
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
