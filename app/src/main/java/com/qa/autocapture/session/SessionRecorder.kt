package com.qa.autocapture.session

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.qa.autocapture.capture.CaptureConfig
import com.qa.autocapture.storage.CaptureRecord
import com.qa.autocapture.storage.EvidenceStore
import com.qa.autocapture.storage.FrameStore
import com.qa.autocapture.storage.GroupInfo
import com.qa.autocapture.storage.ScreenRecord
import com.qa.autocapture.storage.SessionData
import com.qa.autocapture.storage.SessionStore
import com.qa.autocapture.storage.StoragePaths
import com.qa.autocapture.storage.TextLog
import com.qa.autocapture.storage.ToastRecord
import java.util.concurrent.atomic.AtomicInteger

/**
 * 세션 1개(시작~정지)를 기록한다. 공개 함수는 메인 스레드에서 부른다.
 *
 * 화면 방문 1번 = 찍은 사진 묶음 1개. 사진은 임시 PNG 로만 두고(FrameStore), 화면을 떠나면(다른 화면·정지)
 * GroupSaver 가 겹치는 부분끼리 이어붙여 그 화면의 이미지로 저장한다(겹침이 없으면 _2, _3 으로 나눔).
 * 기록은 SessionThreads.save 에서만 고친다.
 */
class SessionRecorder(
    context: Context,
    private val topInset: Int,
    private val bottomInset: Int,
    /** 사진을 찍은 화면 수가 바뀔 때마다 (세션ID, 수) 로 메인 스레드에서 호출 */
    private val onScreenCount: (sessionId: String, screens: Int) -> Unit,
) {
    private val sessions = SessionStore(context)
    private val frameStore = FrameStore(context)
    private val evidence = EvidenceStore(context)
    private val session: SessionData
    private val log: TextLog

    val sessionId: String get() = session.id

    /** 현재 화면 방문 번호(메인 스레드). 촬영 결과가 같은 화면의 것인지 확인하는 데 쓴다. */
    var currentSegment = 0
        private set

    /** 처리를 기다리는 사진 수. 많이 쌓이면 서비스가 다음 촬영을 미룬다. */
    private val pending = AtomicInteger()
    val busy: Boolean get() = pending.get() >= CaptureConfig.MAX_PENDING_SAVES

    /** 지금 화면 방문(save 스레드 전용). */
    private var visit: Visit? = null

    /** 화면 방문 1번. 첫 사진을 받을 때 [record] 를 만들고 화면 순번을 준다. */
    private inner class Visit(val number: Int, val packageName: String, val className: String, val enteredAt: Long) {
        val dir = frameStore.groupDir(session.id, number, 1)
        var record: ScreenRecord? = null
        var frames = 0
        var firstAt = 0L
    }

    init {
        val now = System.currentTimeMillis()
        session = SessionData(sessions.newSessionId(now), now)
        SessionThreads.activeSessionId = session.id
        log = TextLog(
            context,
            StoragePaths.sessionPath(session.id),
            StoragePaths.SESSION_LOG_FILE,
            listOf("# QA 증적 캡처 세션 로그 / 세션 ${session.id}", "# 시각\t종류\t내용"),
        )
        log.write(now, "세션 시작", "")
        val s = session
        SessionThreads.save.execute { sessions.write(s) }
    }

    /** 새 화면 방문을 연다. 이전 방문의 사진은 이어붙여 저장한다. */
    fun enterScreen(packageName: String, className: String) {
        currentSegment++
        val number = currentSegment
        val at = System.currentTimeMillis()
        SessionThreads.onSave {
            closeVisit(at)
            visit = Visit(number, packageName, className, at)
            log.write(at, "화면", "$packageName / $className")
        }
    }

    /**
     * 지금 화면의 사진 1장. Bitmap 소유권을 넘겨받는다(임시 저장 후 recycle).
     * @param scrollDy 직전 사진 이후 scrollDeltaY 합(모르면 null). 이어붙일 때 겹침 힌트.
     */
    fun addFrame(bitmap: Bitmap, scrollDy: Int?) {
        val at = System.currentTimeMillis()
        pending.incrementAndGet()
        SessionThreads.save.execute {
            try {
                val v = visit ?: return@execute
                if (v.record == null) {
                    // 이 방문의 첫 사진: 화면 순번을 준다
                    val record = ScreenRecord(session.screens.size + 1, v.packageName, v.className, v.enteredAt)
                    session.screens += record
                    v.record = record
                    v.firstAt = at
                    frameStore.writeGroupInfo(v.dir, GroupInfo(record.seq, baseName(record)))
                    sessions.write(session)
                    notifyCount()
                }
                frameStore.writeFrame(v.dir, v.frames, scrollDy, bitmap)
                v.frames++
            } catch (e: Exception) {
                Log.e(TAG, "사진 저장 실패", e)
            } finally {
                bitmap.recycle()
                pending.decrementAndGet()
            }
        }
    }

    /** 토스트 문구를 manifest 의 toasts 와 세션 로그에 남긴다. */
    fun logToast(at: Long, packageName: String, text: String, screen: String?) {
        SessionThreads.onSave {
            session.toasts += ToastRecord(at, packageName, text, screen)
            sessions.write(session)
            log.write(at, "토스트", "[$packageName] $text")
        }
    }

    /** 세션을 끝낸다. 남은 저장이 모두 끝나면 [onDone] 을 (저장한 이미지 수와 함께) 메인 스레드에서 부른다. */
    fun stop(reason: String, onDone: (savedImages: Int) -> Unit) {
        val at = System.currentTimeMillis()
        SessionThreads.onSave {
            closeVisit(at)
            // 예약된 이미지 저장이 끝나고 그 결과가 반영된 뒤에 마무리한다
            SessionThreads.stitch.execute {
                SessionThreads.onSave {
                    try {
                        session.endedAt = at
                        session.ended = true
                        sessions.write(session)
                    } finally {
                        if (SessionThreads.activeSessionId == session.id) SessionThreads.activeSessionId = null
                        val saved = session.imageCount
                        log.write(at, "세션 종료", "$reason · 화면 ${session.screens.size}개 · 이미지 ${saved}장")
                        log.close()
                        SessionThreads.main.post { onDone(saved) }
                    }
                }
            }
        }
    }

    // ── save 스레드 ───────────────────────────────────────────────

    /** 지금 방문을 닫고, 찍은 사진을 이어붙여 저장한다(stitch 스레드). */
    private fun closeVisit(exitedAt: Long) {
        val v = visit ?: return
        visit = null
        val record = v.record ?: return // 사진을 찍지 못한 방문(그냥 지나간 화면)
        record.exitedAt = exitedAt
        sessions.write(session)
        val base = baseName(record)
        val path = StoragePaths.sessionPath(session.id)
        SessionThreads.stitch.execute {
            var n = 0
            val files = try {
                GroupSaver.save(frameStore, evidence, v.dir, path, topInset, bottomInset) {
                    if (n++ == 0) "$base.png" else "${base}_$n.png"
                }.also { v.dir.deleteRecursively() }
            } catch (e: Exception) {
                // 임시 사진은 남겨 두고 다음 서비스 연결 때 다시 시도한다(SessionRecovery)
                Log.e(TAG, "이미지 저장 실패: $base", e)
                emptyList()
            }
            SessionThreads.onSave {
                for (f in files) {
                    record.captures += CaptureRecord(f.name, v.firstAt, f.frames)
                    log.write(System.currentTimeMillis(), "저장", if (f.frames > 1) "${f.name} (사진 ${f.frames}장 이어붙임)" else f.name)
                }
                if (files.isNotEmpty()) sessions.write(session)
            }
        }
    }

    /** 사진을 찍은 화면 수. 플로팅 버튼 숫자. */
    private fun notifyCount() {
        val id = session.id
        val count = session.screens.size
        SessionThreads.main.post { onScreenCount(id, count) }
    }

    private companion object {
        const val TAG = "SessionRecorder"

        fun baseName(record: ScreenRecord) =
            StoragePaths.baseName(record.seq, record.packageName, record.className, record.enteredAt)
    }
}
