package com.qa.autocapture.session

import android.content.Context
import android.util.Log
import com.qa.autocapture.storage.CaptureRecord
import com.qa.autocapture.storage.EvidenceStore
import com.qa.autocapture.storage.FrameStore
import com.qa.autocapture.storage.SessionStore
import com.qa.autocapture.storage.StoragePaths

/**
 * 서비스가 비정상 종료돼 끝나지 않은 세션을 정리한다.
 * 남은 임시 프레임은 이미지(…_recovered.png)로 저장하고, 세션은 마지막 이미지 시각으로 닫는다.
 */
object SessionRecovery {
    private const val TAG = "SessionRecovery"

    /** 서비스가 연결될 때 한 번 부른다. 앞서 예약된 저장이 모두 끝난 뒤에 남은 것만 처리한다. */
    fun recoverUnfinished(context: Context, topInset: Int, bottomInset: Int) {
        val sessions = SessionStore(context)
        val frameStore = FrameStore(context)
        val evidence = EvidenceStore(context)
        SessionThreads.stitch.execute {
            SessionThreads.onSave {
                for (s in sessions.loadAll()) {
                    if (s.id == SessionThreads.activeSessionId) continue
                    var changed = false
                    for (dir in frameStore.pendingGroupDirs(s.id)) {
                        val info = frameStore.readGroupInfo(dir)
                        val record = s.screens.firstOrNull { it.seq == info?.seq }
                        val at = dir.lastModified()
                        val files = if (info == null || record == null) {
                            emptyList()
                        } else {
                            var n = 0
                            try {
                                GroupSaver.save(frameStore, evidence, dir, StoragePaths.sessionPath(s.id), topInset, bottomInset) {
                                    if (n++ == 0) "${info.base}_recovered.png" else "${info.base}_recovered$n.png"
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "이미지 복구 실패: ${dir.path}", e)
                                emptyList()
                            }
                        }
                        dir.deleteRecursively() // 복구는 한 번만 시도한다
                        if (record != null && files.isNotEmpty()) {
                            for (f in files) record.captures += CaptureRecord(f.name, at, f.frames)
                            changed = true
                        }
                    }
                    if (!s.ended) {
                        for (screen in s.screens) {
                            if (screen.exitedAt == null) screen.exitedAt = screen.captures.maxOfOrNull { it.at } ?: screen.enteredAt
                        }
                        s.ended = true
                        s.endedAt = s.endedAt ?: (s.screens.flatMap { it.captures }.maxOfOrNull { it.at } ?: s.startedAt)
                        changed = true
                    }
                    if (changed) sessions.write(s)
                }
            }
        }
    }
}
