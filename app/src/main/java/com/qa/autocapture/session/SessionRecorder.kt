package com.qa.autocapture.session

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.qa.autocapture.capture.policy.CaptureConfig
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
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 세션 1개(시작~정지)를 기록한다. 공개 함수는 메인 스레드에서 부른다.
 *
 * 보고용 결과: 화면 방문마다 "화면 상태" 단위로 이미지 1장. 서비스가 확정한 화면을 받아
 * - [Op.NEW]: 새 상태 시작(이전 상태는 이미지로 저장)
 * - [Op.REPLACE]: 같은 상태의 작은 변화 → 마지막 화면을 최신 것으로 바꿈
 * - [Op.SCROLL]: 스크롤한 화면 → 같은 상태에 덧붙임(나중에 이어붙여 긴 이미지 1장)
 * 프레임은 임시 PNG 로만 두고(FrameStore), 상태가 끝나면(새 상태·화면 전환·정지) GroupSaver 가 이미지로 저장한다.
 * 기록은 SessionThreads.save 에서만 고친다.
 */
class SessionRecorder(
    context: Context,
    private val topInset: Int,
    private val bottomInset: Int,
    /** 확보한 이미지 수(저장 중인 것 포함)가 바뀔 때마다 (세션ID, 수) 로 메인 스레드에서 호출 */
    private val onImageCount: (sessionId: String, images: Int) -> Unit,
) {
    enum class Op { NEW, REPLACE, SCROLL }

    private val sessions = SessionStore(context)
    private val frameStore = FrameStore(context)
    private val evidence = EvidenceStore(context)
    private val session: SessionData
    private val log: TextLog

    val sessionId: String get() = session.id

    /** 현재 화면 방문 번호(메인 스레드). 촬영 결과가 같은 화면의 것인지 확인하는 데 쓴다. */
    var currentSegment = 0
        private set

    /** 처리를 기다리는 화면 수. 많이 쌓이면 서비스가 다음 촬영을 미룬다. */
    private val pending = AtomicInteger()
    val busy: Boolean get() = pending.get() >= CaptureConfig.MAX_PENDING_SAVES

    // 이하 save 스레드 전용
    private var visit: Visit? = null

    /** 상태는 끝났지만 아직 이미지로 저장 중인 수. */
    private var savingGroups = 0

    /** 화면 방문 1번. 첫 화면을 받을 때 [record] 를 만들고 순번을 준다. */
    private class Visit(val number: Int, val packageName: String, val className: String, val enteredAt: Long) {
        var record: ScreenRecord? = null
        var group: Group? = null
        var groupNo = 0

        /** 이 방문에서 저장한 이미지 수(stitch 스레드 전용). 파일 이름 번호에 쓴다. */
        var files = 0
    }

    /** 화면 상태 1개 = 결과 이미지 1장(이어붙이지 못하면 여러 장). */
    private class Group(val dir: File, val startedAt: Long) {
        var count = 0
        var lastHint: Int? = null
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

    /** 새 화면 방문을 연다. 이전 방문의 상태는 이미지로 저장한다. */
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
     * 확정한 화면 1장. Bitmap 소유권을 넘겨받는다(임시 저장 후 recycle).
     * @param scrollDy [Op.SCROLL] 일 때 직전 화면 이후 scrollDeltaY 합(모르면 null). 이어붙일 때 겹침 힌트.
     */
    fun addFrame(bitmap: Bitmap, op: Op, scrollDy: Int?) {
        val at = System.currentTimeMillis()
        pending.incrementAndGet()
        SessionThreads.save.execute {
            try {
                processFrame(bitmap, op, scrollDy, at)
            } catch (e: Exception) {
                Log.e(TAG, "화면 저장 실패", e)
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
                        log.write(at, "세션 종료", "$reason · 이미지 ${saved}장")
                        log.close()
                        SessionThreads.main.post { onDone(saved) }
                    }
                }
            }
        }
    }

    // ── save 스레드 ───────────────────────────────────────────────

    private fun processFrame(bitmap: Bitmap, op: Op, scrollDy: Int?, at: Long) {
        val v = visit ?: return
        val g = v.group
        when {
            op == Op.REPLACE && g != null -> frameStore.writeFrame(g.dir, g.count - 1, g.lastHint, bitmap)
            op == Op.SCROLL && g != null -> {
                frameStore.writeFrame(g.dir, g.count, scrollDy, bitmap)
                g.count++
                g.lastHint = scrollDy
            }
            else -> {
                closeGroup(v)
                val record = v.record ?: ScreenRecord(session.screens.size + 1, v.packageName, v.className, v.enteredAt).also {
                    session.screens += it
                    v.record = it
                    sessions.write(session)
                }
                v.groupNo++
                val group = Group(frameStore.groupDir(session.id, v.number, v.groupNo), at)
                frameStore.writeGroupInfo(group.dir, GroupInfo(record.seq, baseName(record)))
                frameStore.writeFrame(group.dir, 0, null, bitmap)
                group.count = 1
                v.group = group
                notifyCount()
            }
        }
    }

    /** 현재 상태를 끝내고 이미지로 저장한다(stitch 스레드). */
    private fun closeGroup(v: Visit) {
        val g = v.group ?: return
        v.group = null
        val record = v.record ?: return
        val base = baseName(record)
        val path = StoragePaths.sessionPath(session.id)
        savingGroups++
        SessionThreads.stitch.execute {
            val files = try {
                GroupSaver.save(frameStore, evidence, g.dir, path, topInset, bottomInset) {
                    if (v.files++ == 0) "$base.png" else "${base}_${v.files}.png"
                }.also { g.dir.deleteRecursively() }
            } catch (e: Exception) {
                // 임시 프레임은 남겨 두고 다음 서비스 연결 때 다시 시도한다(SessionRecovery)
                Log.e(TAG, "이미지 저장 실패: $base", e)
                emptyList()
            }
            SessionThreads.onSave {
                savingGroups--
                for (f in files) {
                    record.captures += CaptureRecord(f.name, g.startedAt, f.frames)
                    log.write(System.currentTimeMillis(), "저장", if (f.frames > 1) "${f.name} (화면 ${f.frames}장 이어붙임)" else f.name)
                }
                if (files.isNotEmpty()) sessions.write(session)
                notifyCount()
            }
        }
    }

    private fun closeVisit(exitedAt: Long) {
        val v = visit ?: return
        visit = null
        closeGroup(v)
        val record = v.record ?: return // 확보한 화면이 없는 방문
        record.exitedAt = exitedAt
        sessions.write(session)
    }

    /** 저장한 이미지 + 저장 중 + 지금 만드는 상태. 플로팅 버튼 숫자. */
    private fun notifyCount() {
        val id = session.id
        val count = session.imageCount + savingGroups + (if (visit?.group != null) 1 else 0)
        SessionThreads.main.post { onImageCount(id, count) }
    }

    private companion object {
        const val TAG = "SessionRecorder"

        fun baseName(record: ScreenRecord) =
            StoragePaths.baseName(record.seq, record.packageName, record.className, record.enteredAt)
    }
}
