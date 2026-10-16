package com.qa.autocapture

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import com.qa.autocapture.capture.CaptureStatus
import com.qa.autocapture.storage.Options
import com.qa.autocapture.ui.common.Ui
import com.qa.autocapture.ui.common.Ui.Type
import com.qa.autocapture.ui.main.PrivacyCard
import com.qa.autocapture.ui.main.SessionListCard
import com.qa.autocapture.ui.main.SetupGuideCard
import com.qa.autocapture.ui.main.StatusCard
import com.qa.autocapture.ui.settings.SettingsActivity

/**
 * 앱 메인 화면(레이아웃 XML 없이 코드로 구성). 위에서부터 카드 4개를 쌓는다.
 * 1) [StatusCard] 상태 + 마스터 스위치  2) [SetupGuideCard] 설정 안내  3) [SessionListCard] 저장된 세션  4) [PrivacyCard] 개인정보 및 권한
 * 카드는 ui/main/ 에 있다. 앱바 오른쪽 톱니바퀴는 설정 화면(SettingsActivity).
 *
 * 런처·접근성 설정의 "설정" 버튼이 여는 화면이라 클래스 이름(com.qa.autocapture.MainActivity)을 고정한다.
 */
class MainActivity : Activity() {

    private lateinit var options: Options
    private lateinit var status: StatusCard
    private lateinit var setup: SetupGuideCard
    private lateinit var sessions: SessionListCard
    private var wasRecording = false

    /** 서비스 상태(연결·기록·저장 수)가 바뀌면 호출된다. 기록이 끝나면 세션 목록도 새로 읽는다. */
    private val stateListener: () -> Unit = {
        status.refresh()
        val recording = CaptureStatus.recordingScreens != null
        if (wasRecording != recording) sessions.refresh()
        wasRecording = recording
    }

    /** 빠른 설정 타일로 마스터 스위치를 바꾸면 바로 반영한다. */
    private val optionsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Options.KEY_CAPTURE_ENABLED) status.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        options = Options(this)
        status = StatusCard(this, ui, options)
        setup = SetupGuideCard(this, ui)
        sessions = SessionListCard(this, ui)

        val content = ui.column()
        content.addView(status.view)
        content.addView(setup.view)
        content.addView(ui.sectionTitle("저장된 세션"))
        content.addView(sessions.view)
        content.addView(ui.sectionTitle("개인정보 및 권한"))
        content.addView(PrivacyCard(this, ui).view)
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
        CaptureStatus.addListener(stateListener)
        options.listen(optionsListener)
    }

    override fun onResume() {
        super.onResume()
        wasRecording = CaptureStatus.recordingScreens != null
        status.refresh()
        setup.refresh()
        sessions.refresh()
    }

    override fun onStop() {
        CaptureStatus.removeListener(stateListener)
        options.unlisten(optionsListener)
        super.onStop()
    }
}
