package com.qa.autocapture.storage

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 세션 기록 읽기·쓰기.
 * - 내부 noBackupFiles/sessions/{세션}/session.json: 메인 화면 목록·비정상 종료 복구용(+ ended 플래그)
 * - 공개 Documents/QAEvidence/{세션}/manifest.json: 사용자에게 주는 기록(같은 내용)
 */
class SessionStore(context: Context) {

    private val app = context.applicationContext
    private val root = StoragePaths.internalRoot(app)
    private val evidence = EvidenceStore(app)

    /** 세션 ID = 시작 시각(yyyyMMdd_HHmmss). 같은 초에 시작하면 _2, _3 을 붙인다. */
    fun newSessionId(startedAt: Long): String {
        val base = Times.format(startedAt, "yyyyMMdd_HHmmss")
        var id = base
        var n = 2
        while (File(root, id).exists()) id = "${base}_${n++}"
        File(root, id).mkdirs()
        return id
    }

    /** 내부 session.json 과 공개 manifest.json 을 함께 갱신한다. */
    fun write(session: SessionData) {
        val dir = File(root, session.id).apply { mkdirs() }
        val internal = toJson(session).put("ended", session.ended).toString(2)
        val tmp = File(dir, "$SESSION_FILE.tmp")
        tmp.writeText(internal)
        tmp.renameTo(File(dir, SESSION_FILE))

        // 이미지가 하나도 없는 세션은 manifest 를 만들지 않는다
        if (session.screens.isNotEmpty()) {
            val bytes = toJson(session).toString(2).toByteArray()
            evidence.overwrite(StoragePaths.sessionPath(session.id), StoragePaths.MANIFEST_FILE, "application/json", bytes)
        }
    }

    /** 최근 세션부터. */
    fun loadAll(): List<SessionData> =
        root.listFiles()?.mapNotNull { read(it) }?.sortedByDescending { it.startedAt } ?: emptyList()

    fun load(sessionId: String): SessionData? = read(File(root, sessionId))

    private fun read(dir: File): SessionData? = try {
        val o = JSONObject(File(dir, SESSION_FILE).readText())
        SessionData(o.getString("session"), Times.parseIso(o.getString("startedAt"))).apply {
            endedAt = optTime(o, "endedAt")
            ended = o.optBoolean("ended")
            val arr = o.optJSONArray("screens") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val enteredAt = Times.parseIso(s.getString("enteredAt"))
                val screen = ScreenRecord(s.getInt("seq"), s.getString("packageName"), s.getString("className"), enteredAt)
                screen.exitedAt = optTime(s, "exitedAt")
                val captures = s.optJSONArray("captures")
                if (captures != null) {
                    for (j in 0 until captures.length()) {
                        val c = captures.getJSONObject(j)
                        screen.captures += CaptureRecord(c.getString("file"), Times.parseIso(c.getString("at")), c.optInt("frames", 1))
                    }
                } else {
                    // 이전 버전 기록(화면마다 스티칭한 PNG 의 files 목록)
                    val files = s.optJSONArray("files") ?: JSONArray()
                    for (j in 0 until files.length()) screen.captures += CaptureRecord(files.getString(j), enteredAt, 1)
                }
                // "full" = 이전 버전의 긴 이미지 기록
                (s.optJSONArray("scroll") ?: s.optJSONArray("full"))?.let { a -> screen.scroll = List(a.length()) { a.getString(it) } }
                screens += screen
            }
            val toastArr = o.optJSONArray("toasts") ?: JSONArray()
            for (i in 0 until toastArr.length()) {
                val t = toastArr.getJSONObject(i)
                toasts += ToastRecord(
                    at = Times.parseIso(t.getString("at")),
                    packageName = t.getString("packageName"),
                    text = t.getString("text"),
                    screen = t.optString("screen").takeIf { it.isNotEmpty() && it != "null" },
                )
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun toJson(session: SessionData): JSONObject {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val screens = JSONArray()
        for (s in session.screens) {
            val captures = JSONArray()
            for (c in s.captures) {
                captures.put(JSONObject().put("file", c.file).put("at", Times.iso(c.at)).put("frames", c.frames))
            }
            val screen = JSONObject()
                .put("seq", s.seq)
                .put("packageName", s.packageName)
                .put("className", s.className)
                .put("enteredAt", Times.iso(s.enteredAt))
                .put("exitedAt", s.exitedAt?.let { Times.iso(it) } ?: JSONObject.NULL)
                .put("captures", captures)
            if (s.scroll.isNotEmpty()) screen.put("scroll", JSONArray(s.scroll)) // 이전 버전 기록
            screens.put(screen)
        }
        val toasts = JSONArray()
        for (t in session.toasts) {
            toasts.put(
                JSONObject()
                    .put("at", Times.iso(t.at))
                    .put("packageName", t.packageName)
                    .put("text", t.text)
                    .put("screen", t.screen ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("session", session.id)
            .put("startedAt", Times.iso(session.startedAt))
            .put("endedAt", session.endedAt?.let { Times.iso(it) } ?: JSONObject.NULL)
            .put(
                "device",
                JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("android", Build.VERSION.RELEASE)
                    .put("sdk", Build.VERSION.SDK_INT),
            )
            .put("app", JSONObject().put("versionName", info.versionName).put("versionCode", info.longVersionCode))
            .put("screens", screens)
            .put("toasts", toasts)
    }

    private fun optTime(o: JSONObject, key: String): Long? =
        o.optString(key).takeIf { it.isNotEmpty() && it != "null" }?.let { Times.parseIso(it) }

    private companion object {
        const val SESSION_FILE = "session.json"
    }
}
