package com.qa.autocapture

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.qa.autocapture.capture.CaptureService
import com.qa.autocapture.storage.EvidenceStore
import com.qa.autocapture.storage.Options
import com.qa.autocapture.storage.SessionData

/**
 * 앱 메인 화면(레이아웃 XML 없이 코드로 구성).
 * 서비스 상태, 접근성 설정/앱 정보 이동, "제한된 설정 허용" 안내, 옵션 스위치, 세션 목록과 공유 버튼.
 */
class MainActivity : Activity() {

    private lateinit var store: EvidenceStore
    private lateinit var status: TextView
    private lateinit var statusHelp: TextView
    private lateinit var openAccessibility: Button
    private lateinit var sessionList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = EvidenceStore(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        content.addView(text(getString(R.string.app_name), 24f, bold = true))

        // 1) 서비스 상태
        content.addView(heading("접근성 서비스"))
        status = text("", 18f, bold = true)
        statusHelp = text("", 14f)
        openAccessibility = button("접근성 설정 열기") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        content.addView(status)
        content.addView(statusHelp)
        content.addView(openAccessibility)

        // 2) 제한된 설정 허용 안내
        content.addView(heading("켜지지 않을 때 (제한된 설정)"))
        content.addView(
            text(
                "Play 스토어 밖에서 설치한 앱은 접근성 서비스를 바로 켤 수 없고 \"제한된 설정\" 안내가 뜹니다.\n" +
                    "1. 접근성 설정에서 이 앱을 한 번 눌러 안내를 확인합니다.\n" +
                    "2. 아래 [앱 정보 열기] → 오른쪽 위 ⋮ → \"제한된 설정 허용\"을 누릅니다.\n" +
                    "3. 다시 접근성 설정에서 서비스를 켭니다.\n\n" +
                    "같은 앱 정보 화면의 배터리를 \"제한 없음\"으로 두면 서비스가 중간에 꺼지지 않습니다.",
                14f,
            ),
        )
        content.addView(
            button("앱 정보 열기") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            },
        )

        // 3) 옵션
        val options = Options(this)
        content.addView(heading("옵션"))
        content.addView(
            option(
                "토스트 뜬 순간도 별도 저장",
                "토스트가 뜨면 그 순간 화면을 1장 더 저장합니다(파일명 끝 _toast). " +
                    "기본 캡처는 토스트가 사라진 뒤에 찍고, 토스트 문구는 manifest.json 에 기록합니다.",
                options.saveToastShot,
            ) { options.saveToastShot = it },
        )
        content.addView(
            option(
                "디버그 로그",
                "기록 중 받은 접근성 이벤트(시각, 종류, 패키지, 클래스, 텍스트)를 " +
                    "내장 저장공간/${EvidenceStore.DEBUG_PATH}{세션}.txt 로 저장합니다. 다음 기록 시작부터 적용됩니다.",
                options.debugLog,
            ) { options.debugLog = it },
        )

        // 4) 세션 목록
        content.addView(heading("저장된 세션"))
        content.addView(text("저장 위치: 내장 저장공간/${EvidenceStore.ROOT_PATH}", 13f))
        sessionList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(sessionList)

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(content)
        }
        // targetSdk 35 는 edge-to-edge 가 강제되므로 시스템바·카메라 컷아웃만큼 띄운다
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshSessions()
    }

    private fun refreshStatus() {
        val on = isServiceEnabled()
        status.text = if (on) "켜짐" else "꺼짐"
        status.setTextColor(if (on) ON_COLOR else OFF_COLOR)
        statusHelp.text = if (on) {
            "화면의 플로팅 버튼 [● 시작]을 누르면 기록을 시작합니다. 평소처럼 앱을 쓰고 스크롤하세요. " +
                "다시 누르면 정지하고 저장합니다. 버튼을 길게 누르면 이 화면이 열립니다."
        } else {
            "설정 → 접근성 → 설치된 앱 → ${getString(R.string.app_name)} 에서 켜세요. " +
                "같은 화면에서 바로가기(음량 위/아래 버튼 길게 누르기, 접근성 버튼)를 켜 두면 서비스를 빠르게 켜고 끌 수 있습니다."
        }
        openAccessibility.visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun isServiceEnabled(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java) ?: return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val info = it.resolveInfo.serviceInfo
            info.packageName == packageName && info.name == CaptureService::class.java.name
        }
    }

    private fun refreshSessions() {
        sessionList.removeAllViews()
        val sessions = store.loadSessions()
        if (sessions.isEmpty()) {
            sessionList.addView(text("아직 저장된 세션이 없습니다.", 14f).apply { setPadding(0, dp(12), 0, 0) })
            return
        }
        for (s in sessions) sessionList.addView(sessionRow(s))
    }

    private fun sessionRow(s: SessionData): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val detail = buildString {
            append("화면 ").append(s.screens.size).append("개")
            val end = s.endedAt
            if (!s.ended) {
                append(" · 진행 중")
            } else if (end != null) {
                append(" · ").append(maxOf(1L, (end - s.startedAt + 59_999) / 60_000)).append("분")
            }
        }
        val label = text(EvidenceStore.format(s.startedAt, "yyyy-MM-dd HH:mm:ss") + "\n" + detail, 15f)
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val share = button("공유") {}
        share.setOnClickListener { share(s.id, share) }
        share.isEnabled = s.screens.isNotEmpty()
        row.addView(share)
        return row
    }

    /** 세션 폴더를 zip 으로 묶어(백그라운드) 공유 시트를 연다. */
    private fun share(sessionId: String, button: Button) {
        button.isEnabled = false
        Toast.makeText(this, "압축하는 중…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { store.exportZip(sessionId) }
            runOnUiThread {
                button.isEnabled = true
                if (isDestroyed) return@runOnUiThread
                result
                    .onSuccess { uri ->
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("application/zip")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_SUBJECT, "QA 증적 $sessionId")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(Intent.createChooser(send, "QA 증적 공유"))
                    }
                    .onFailure { Toast.makeText(this, "공유 실패: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    // ── 뷰 도우미 ────────────────────────────────────────────────

    private fun heading(s: String) = text(s, 17f, bold = true).apply { setPadding(0, dp(24), 0, dp(6)) }

    private fun text(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    private fun option(title: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(8))
        addView(
            Switch(this@MainActivity).apply {
                text = title
                textSize = 16f
                isChecked = checked
                setOnCheckedChangeListener { _, on -> onChange(on) }
            },
        )
        addView(text(help, 13f))
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val ON_COLOR = 0xFF43A047.toInt()
        const val OFF_COLOR = 0xFFE53935.toInt()
    }
}
