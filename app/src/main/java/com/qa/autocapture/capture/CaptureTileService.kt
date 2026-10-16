package com.qa.autocapture.capture

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.qa.autocapture.MainActivity
import com.qa.autocapture.storage.Options

/**
 * 알림창 빠른 설정 타일: 캡처 마스터 스위치(일시정지/재개).
 * 값은 Options 에 저장하고, 접근성 서비스가 그 변경을 받아 바로 반영한다.
 * 서비스가 꺼져 있으면 누를 때 앱 메인 화면을 연다.
 */
class CaptureTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        if (!CaptureStatus.isServiceEnabled(this)) {
            openApp()
            return
        }
        val options = Options(this)
        options.captureEnabled = !options.captureEnabled
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val serviceOn = CaptureStatus.isServiceEnabled(this)
        val on = Options(this).captureEnabled
        tile.state = if (serviceOn && on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            !serviceOn -> "서비스 꺼짐"
            !on -> "일시정지"
            CaptureStatus.recordingImages != null -> "기록 중"
            else -> "동작 중"
        }
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            // API 33 이하 전용 경로(34+ 에서는 위 PendingIntent 판을 쓴다)
            @Suppress("DEPRECATION")
            @SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** 타일이 보이는 중이면 상태를 다시 그리게 한다. */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, CaptureTileService::class.java)) }
        }
    }
}
