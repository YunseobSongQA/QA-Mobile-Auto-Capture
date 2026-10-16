package com.qa.autocapture.session

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 세션 기록용 스레드.
 * - save: 기록(세션 상태·manifest·세션 로그·임시 프레임)은 여기서만 고친다. 순서대로 처리된다.
 * - stitch: 시간이 걸리는 이어붙이기·이미지 저장. 촬영과 기록을 막지 않는다.
 */
internal object SessionThreads {
    private const val TAG = "SessionThreads"

    val save: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "qa-save") }
    val stitch: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "qa-stitch") }
    val main = Handler(Looper.getMainLooper())

    /** 진행 중인 세션. 복구 작업이 건드리지 않게 한다. */
    @Volatile
    var activeSessionId: String? = null

    /** save 스레드에서 실행한다. 예외는 로그만 남긴다. */
    fun onSave(task: () -> Unit) {
        save.execute {
            try {
                task()
            } catch (e: Exception) {
                Log.e(TAG, "저장 작업 실패", e)
            }
        }
    }
}
