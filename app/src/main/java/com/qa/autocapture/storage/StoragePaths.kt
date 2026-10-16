package com.qa.autocapture.storage

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.Locale

/**
 * 저장 위치와 파일 이름 규칙. 경로·이름을 바꿀 때는 여기만 고친다.
 *
 * ```
 * 결과물 (MediaStore, 사용자에게 보임)          내부 기록 (앱 전용, 백업 제외)
 * Documents/QAEvidence/                        noBackupFiles/sessions/
 * ├── {세션}/                                  └── {세션}/
 * │   ├── {순번}_{앱}_{화면}_{시각}[_n].png        ├── session.json            세션 목록·복구 (SessionStore)
 * │   ├── manifest.json                            └── groups/{방문}_{상태}/   임시 프레임 (FrameStore)
 * │   └── session_log.txt
 * ├── {세션}.zip
 * └── debug/{세션}.txt
 * ```
 */
object StoragePaths {

    /** 결과물 최상위 폴더(MediaStore RELATIVE_PATH). */
    val ROOT_PATH = "${Environment.DIRECTORY_DOCUMENTS}/QAEvidence/"

    /** 디버그 로그 폴더. 세션별 {세션}.txt */
    val DEBUG_PATH = "${ROOT_PATH}debug/"

    const val MANIFEST_FILE = "manifest.json"

    /** 세션 폴더의 텍스트 로그(토스트 문구, 화면 진입, 저장한 파일을 시각과 함께). */
    const val SESSION_LOG_FILE = "session_log.txt"

    fun sessionPath(sessionId: String) = "$ROOT_PATH$sessionId/"

    fun debugLogFile(sessionId: String) = "$sessionId.txt"

    fun zipFile(sessionId: String) = "$sessionId.zip"

    /** 내부 기록 최상위 폴더. */
    fun internalRoot(context: Context) = File(context.applicationContext.noBackupFilesDir, "sessions")

    /** 결과 이미지 이름 앞부분: {화면순번3자리}_{패키지 끝부분}_{화면클래스 짧은이름}_{진입 HHmmss} */
    fun baseName(seq: Int, packageName: String, className: String, enteredAt: Long): String =
        String.format(
            Locale.ROOT,
            "%03d_%s_%s_%s",
            seq,
            clean(packageName.substringAfterLast('.')),
            clean(className.substringAfterLast('.')),
            Times.format(enteredAt, "HHmmss"),
        )

    private fun clean(s: String) = s.replace(Regex("[^A-Za-z0-9]"), "_").take(40).ifEmpty { "unknown" }
}
