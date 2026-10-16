package com.qa.autocapture.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.qa.autocapture.capture.CaptureConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 저장한 이미지 1장(manifest 의 captures 항목). [at] = 첫 화면을 찍은 시각(epoch millis), [frames] = 이어붙인 화면 수. */
class CaptureRecord(val file: String, val at: Long, val frames: Int)

/** 화면 방문 1번(manifest 의 screens 항목). 그 방문에서 첫 캡처를 저장할 때 만든다. */
class ScreenRecord(val seq: Int, val packageName: String, val className: String, val enteredAt: Long) {
    var exitedAt: Long? = null
    val captures = mutableListOf<CaptureRecord>()

    /** 이전 버전의 스크롤 이미지 파일(…_scroll.png, …_full.png). 지금은 captures 에 함께 담는다. */
    var scroll: List<String> = emptyList()
}

/** 세션 중 감지한 토스트(manifest 의 toasts 항목). [screen] = 그때 화면의 className. */
class ToastRecord(val at: Long, val packageName: String, val text: String, val screen: String?)

/** 세션 1개의 기록. 내부 session.json 과 공개 manifest.json 의 원본. */
class SessionData(val id: String, val startedAt: Long) {
    var endedAt: Long? = null
    var ended = false
    val screens = mutableListOf<ScreenRecord>()
    val toasts = mutableListOf<ToastRecord>()

    /** 저장한 이미지 수(캡처 + 스크롤 이미지). */
    val imageCount: Int get() = screens.sumOf { it.captures.size + it.scroll.size }
}

/** MediaStore 에 저장된 내 파일 1개. */
class StoredFile(val uri: Uri, val name: String, val mime: String?)

/** 화면 상태 1개(결과 이미지 1장)의 임시 폴더 정보: 그 화면의 순번과 결과 파일 이름 앞부분. */
class GroupInfo(val seq: Int, val base: String)

/** 임시 프레임 1장. [hint] = 직전 프레임부터의 scrollDeltaY 합(모르면 null). */
class FrameFile(val file: File, val hint: Int?)

/**
 * 파일 입출력 담당.
 * - 결과물: MediaStore → Documents/QAEvidence/{세션}/ (저장소 권한 없이 내 파일만 다룬다)
 * - 세션 목록·복구용 기록: noBackupFiles/sessions/{세션}/session.json
 * - 임시 프레임: noBackupFiles/sessions/{세션}/groups/{방문}_{상태}/{번호}_{이동량}.png
 *   (화면 상태 1개 = 결과 이미지 1장. 스크롤하면 프레임이 여러 장 쌓였다가 이어붙여진다)
 */
class EvidenceStore(context: Context) {

    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val root = File(app.noBackupFilesDir, "sessions")
    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** 세션별 manifest.json 항목. 저장할 때마다 다시 조회하지 않게 기억한다. */
    private val manifestUris = HashMap<String, Uri>()

    fun newSessionId(startedAt: Long): String {
        val base = format(startedAt, "yyyyMMdd_HHmmss")
        var id = base
        var n = 2
        while (File(root, id).exists()) id = "${base}_${n++}"
        File(root, id).mkdirs()
        return id
    }

    // ── 이미지 ──────────────────────────────────────────────────

    fun savePng(relativePath: String, name: String, bitmap: Bitmap): Uri =
        saveFile(relativePath, name, "image/png") { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

    /** 내 파일의 이름을 바꾼다. 성공하면 true. */
    fun rename(uri: Uri, name: String): Boolean = try {
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null) > 0
    } catch (e: Exception) {
        Log.w(TAG, "이름 변경 실패: $name", e)
        false
    }

    // ── 임시 프레임 ─────────────────────────────────────────────

    fun groupDir(sessionId: String, visit: Int, group: Int) =
        File(root, "$sessionId/groups/" + String.format(Locale.ROOT, "%04d_%02d", visit, group))

    /** 저장되지 않고 남은(비정상 종료) 임시 폴더. 이전 버전의 scroll/ 폴더도 포함한다. */
    fun pendingGroupDirs(sessionId: String): List<File> =
        listOf("groups", "scroll").flatMap { sub ->
            File(root, "$sessionId/$sub").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        }

    fun writeGroupInfo(dir: File, info: GroupInfo) {
        dir.mkdirs()
        File(dir, GROUP_INFO_FILE).writeText("${info.seq}\n${info.base}")
    }

    fun readGroupInfo(dir: File): GroupInfo? = try {
        val lines = File(dir, GROUP_INFO_FILE).readLines()
        GroupInfo(lines[0].trim().toInt(), lines[1].trim())
    } catch (e: Exception) {
        null
    }

    /** 프레임을 임시 PNG 로 쓴다(같은 번호면 덮어씀). 끝까지 쓴 파일만 .png 로 보이게 해서 반쯤 쓴 프레임을 읽지 않는다. */
    fun writeFrame(dir: File, index: Int, hint: Int?, bitmap: Bitmap) {
        dir.mkdirs()
        val name = String.format(Locale.ROOT, "%04d_%s.png", index, hint?.toString() ?: "n")
        val tmp = File(dir, "$name.tmp")
        BufferedOutputStream(FileOutputStream(tmp)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val target = File(dir, name)
        target.delete()
        if (!tmp.renameTo(target)) throw IOException("임시 프레임 저장 실패: $name")
    }

    /** 번호순. 이름 = {번호}_{이동량|n}[_…](이전 버전 이름도 읽는다). */
    fun listFrames(dir: File): List<FrameFile> =
        dir.listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name }?.mapNotNull { f ->
            val parts = f.nameWithoutExtension.split('_')
            if (parts.size < 2) null else FrameFile(f, parts[1].toIntOrNull())
        } ?: emptyList()

    /** PNG 를 sRGB ARGB 픽셀로 읽는다(스티칭용). 같은 크기면 Bitmap 을 재사용한다. */
    class FrameReader : Closeable {
        private var bitmap: Bitmap? = null
        var width = 0
            private set
        var height = 0
            private set

        /** [into] 크기가 맞으면 거기에 채워 돌려주고, 아니면 새 배열을 만든다. 읽기 실패 시 null. */
        fun read(open: () -> InputStream?, into: IntArray?): IntArray? {
            val bmp = decode(open) ?: return null
            width = bmp.width
            height = bmp.height
            val px = if (into != null && into.size == width * height) into else IntArray(width * height)
            bmp.getPixels(px, 0, width, 0, 0, width, height)
            return px
        }

        private fun decode(open: () -> InputStream?): Bitmap? {
            val opts = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                inBitmap = bitmap
            }
            val reused = try {
                open()?.use { BitmapFactory.decodeStream(it, null, opts) }
            } catch (e: IllegalArgumentException) {
                null // 크기가 달라 재사용 불가
            }
            if (reused != null) return reused.also { bitmap = it }
            bitmap?.recycle()
            bitmap = null
            opts.inBitmap = null
            return open()?.use { BitmapFactory.decodeStream(it, null, opts) }?.also { bitmap = it }
        }

        override fun close() {
            bitmap?.recycle()
            bitmap = null
        }
    }

    // ── 세션 기록(session.json / manifest.json) ────────────────────

    /** 내부 session.json 과 공개 manifest.json 을 함께 갱신한다. */
    fun writeSession(session: SessionData) {
        val dir = File(root, session.id).apply { mkdirs() }
        val internal = toJson(session).put("ended", session.ended).toString(2)
        val tmp = File(dir, "$SESSION_FILE.tmp")
        tmp.writeText(internal)
        tmp.renameTo(File(dir, SESSION_FILE))

        // 이미지가 하나도 없는 세션은 manifest 를 만들지 않는다
        if (session.screens.isNotEmpty()) writeManifest(session.id, toJson(session).toString(2))
    }

    fun loadSessions(): List<SessionData> =
        root.listFiles()?.mapNotNull { readSession(it) }?.sortedByDescending { it.startedAt } ?: emptyList()

    fun loadSession(sessionId: String): SessionData? = readSession(File(root, sessionId))

    private fun readSession(dir: File): SessionData? = try {
        val o = JSONObject(File(dir, SESSION_FILE).readText())
        SessionData(o.getString("session"), parseTime(o.getString("startedAt"))).apply {
            endedAt = optTime(o, "endedAt")
            ended = o.optBoolean("ended")
            val arr = o.optJSONArray("screens") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val enteredAt = parseTime(s.getString("enteredAt"))
                val screen = ScreenRecord(s.getInt("seq"), s.getString("packageName"), s.getString("className"), enteredAt)
                screen.exitedAt = optTime(s, "exitedAt")
                val captures = s.optJSONArray("captures")
                if (captures != null) {
                    for (j in 0 until captures.length()) {
                        val c = captures.getJSONObject(j)
                        screen.captures += CaptureRecord(c.getString("file"), parseTime(c.getString("at")), c.optInt("frames", 1))
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
                    at = parseTime(t.getString("at")),
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
                captures.put(JSONObject().put("file", c.file).put("at", isoTime(c.at)).put("frames", c.frames))
            }
            val screen = JSONObject()
                .put("seq", s.seq)
                .put("packageName", s.packageName)
                .put("className", s.className)
                .put("enteredAt", isoTime(s.enteredAt))
                .put("exitedAt", s.exitedAt?.let { isoTime(it) } ?: JSONObject.NULL)
                .put("captures", captures)
            if (s.scroll.isNotEmpty()) screen.put("scroll", JSONArray(s.scroll)) // 이전 버전 기록
            screens.put(screen)
        }
        val toasts = JSONArray()
        for (t in session.toasts) {
            toasts.put(
                JSONObject()
                    .put("at", isoTime(t.at))
                    .put("packageName", t.packageName)
                    .put("text", t.text)
                    .put("screen", t.screen ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("session", session.id)
            .put("startedAt", isoTime(session.startedAt))
            .put("endedAt", session.endedAt?.let { isoTime(it) } ?: JSONObject.NULL)
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

    // ── MediaStore ────────────────────────────────────────────────

    /** MediaStore 에 새 파일을 만들고 [write] 로 내용을 쓴다. 실패하면 만든 항목을 지운다. */
    fun saveFile(relativePath: String, name: String, mime: String, write: (OutputStream) -> Unit): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore 항목을 만들 수 없습니다: $relativePath$name")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("파일을 열 수 없습니다: $name")
            BufferedOutputStream(out, 256 * 1024).use(write)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** 계속 덧붙여 쓸 MediaStore 파일을 만든다(세션 로그, 디버그 로그). 스트림은 호출자가 닫는다. */
    fun createStream(relativePath: String, name: String, mime: String): OutputStream {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore 항목을 만들 수 없습니다: $relativePath$name")
        return resolver.openOutputStream(uri) ?: run {
            resolver.delete(uri, null, null)
            throw IOException("파일을 열 수 없습니다: $name")
        }
    }

    /** manifest.json 을 덮어쓴다. 없거나 지워졌으면 새로 만든다. */
    private fun writeManifest(sessionId: String, text: String) {
        val bytes = text.toByteArray()
        val path = sessionPath(sessionId)
        val existing = manifestUris[sessionId] ?: findFile(path, MANIFEST_FILE)
        if (existing != null) {
            try {
                val out = resolver.openOutputStream(existing, "wt")
                if (out != null) {
                    out.use { it.write(bytes) }
                    manifestUris[sessionId] = existing
                    return
                }
            } catch (e: IOException) {
                Log.w(TAG, "manifest 덮어쓰기 실패, 새로 만듭니다", e)
            }
        }
        manifestUris[sessionId] = saveFile(path, MANIFEST_FILE, "application/json") { it.write(bytes) }
    }

    fun findFile(relativePath: String, name: String): Uri? = resolver.query(
        collection,
        arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
        arrayOf(relativePath, name),
        null,
    )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }

    /** 세션 폴더의 내 파일(이미지, manifest.json, session_log.txt)을 이름순으로. */
    fun listFiles(sessionId: String): List<StoredFile> {
        val out = ArrayList<StoredFile>()
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(sessionPath(sessionId)),
            "${MediaStore.MediaColumns.DISPLAY_NAME} ASC",
        )?.use { c ->
            while (c.moveToNext()) out += StoredFile(ContentUris.withAppendedId(collection, c.getLong(0)), c.getString(1), c.getString(2))
        }
        return out
    }

    /** 텍스트 파일을 읽는다. [maxBytes] 보다 길면 뒷부분만. */
    fun readText(uri: Uri, maxBytes: Int): String {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""
        val from = maxOf(0, bytes.size - maxBytes)
        return String(bytes, from, bytes.size - from, Charsets.UTF_8)
    }

    /**
     * 세션 폴더의 내 파일을 zip 으로 묶어 Documents/QAEvidence/{세션}.zip 에 저장한다(기존 zip 은 교체).
     * 공유 인텐트에 그대로 쓸 수 있는 URI 를 돌려준다.
     */
    fun exportZip(sessionId: String): Uri {
        val items = listFiles(sessionId).map { it.uri to it.name }.toMutableList()
        if (items.isEmpty()) throw IOException("저장된 파일이 없습니다")
        findFile(DEBUG_PATH, "$sessionId.txt")?.let { items += it to "debug_log.txt" }

        val zipName = "$sessionId.zip"
        findFile(ROOT_PATH, zipName)?.let { resolver.delete(it, null, null) }
        return saveFile(ROOT_PATH, zipName, "application/zip") { out ->
            val zip = ZipOutputStream(out)
            zip.setLevel(Deflater.BEST_SPEED) // PNG 는 이미 압축돼 있다
            for ((uri, name) in items) {
                zip.putNextEntry(ZipEntry("$sessionId/$name"))
                resolver.openInputStream(uri)?.use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.finish()
        }
    }

    companion object {
        private const val TAG = "EvidenceStore"
        private const val SESSION_FILE = "session.json"
        private const val MANIFEST_FILE = "manifest.json"
        private const val GROUP_INFO_FILE = "info.txt"

        /** 세션 폴더의 텍스트 로그(토스트 문구, 화면 진입, 저장한 파일을 시각과 함께). */
        const val SESSION_LOG_FILE = "session_log.txt"

        /** 결과물 최상위 폴더(MediaStore RELATIVE_PATH). */
        val ROOT_PATH = "${Environment.DIRECTORY_DOCUMENTS}/QAEvidence/"

        /** 디버그 로그 폴더. 세션별 {세션}.txt */
        val DEBUG_PATH = "${ROOT_PATH}debug/"

        fun sessionPath(sessionId: String) = "$ROOT_PATH$sessionId/"

        /** {화면순번3자리}_{패키지 끝부분}_{화면클래스 짧은이름}_{진입 HHmmss} */
        fun baseName(seq: Int, packageName: String, className: String, enteredAt: Long): String =
            String.format(
                Locale.ROOT,
                "%03d_%s_%s_%s",
                seq,
                clean(packageName.substringAfterLast('.')),
                clean(className.substringAfterLast('.')),
                format(enteredAt, "HHmmss"),
            )

        private fun clean(s: String) = s.replace(Regex("[^A-Za-z0-9]"), "_").take(40).ifEmpty { "unknown" }

        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)

        fun isoTime(millis: Long): String = ISO.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

        /** 밀리초가 없는 이전 버전 기록도 읽는다. */
        private fun parseTime(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()

        private fun optTime(o: JSONObject, key: String): Long? =
            o.optString(key).takeIf { it.isNotEmpty() && it != "null" }?.let { parseTime(it) }

        fun format(millis: Long, pattern: String): String =
            DateTimeFormatter.ofPattern(pattern, Locale.ROOT).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    }
}

/** 설정 값. 서비스·화면·빠른 설정 타일이 같은 프로세스라 SharedPreferences 로 공유한다(재부팅 후에도 유지). */
class Options(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("options", Context.MODE_PRIVATE)

    /** 마스터 스위치. 끄면 접근성 서비스는 켜 둔 채 플로팅 버튼을 숨기고 이벤트 처리·캡처를 모두 멈춘다. */
    var captureEnabled: Boolean
        get() = prefs.getBoolean(KEY_CAPTURE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CAPTURE_ENABLED, value).apply()

    /** 이 시간 동안 화면 변화가 없으면 최종 화면(_final)을 찍는다. */
    var settleMs: Long
        get() = prefs.getLong(KEY_SETTLE_MS, CaptureConfig.SETTLE_MS).coerceIn(CaptureConfig.SETTLE_MIN_MS, CaptureConfig.SETTLE_MAX_MS)
        set(value) = prefs.edit().putLong(KEY_SETTLE_MS, value).apply()

    /** 변화가 계속돼도 이 시간이 지나면 그 시점 화면을 _final 로 찍는다. */
    var maxWaitMs: Long
        get() = prefs.getLong(KEY_MAX_WAIT_MS, CaptureConfig.MAX_WAIT_MS).coerceIn(CaptureConfig.MAX_WAIT_MIN_MS, CaptureConfig.MAX_WAIT_MAX_MS)
        set(value) = prefs.edit().putLong(KEY_MAX_WAIT_MS, value).apply()

    /** 기록 중 받은 접근성 이벤트를 디버그 로그로 저장한다. */
    var debugLog: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_LOG, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_LOG, value).apply()

    fun resetTiming() {
        prefs.edit().remove(KEY_SETTLE_MS).remove(KEY_MAX_WAIT_MS).apply()
    }

    /** 리스너는 약한 참조로 등록되므로 호출자가 필드로 들고 있어야 한다. 콜백은 메인 스레드에서 온다. */
    fun listen(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unlisten(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val KEY_CAPTURE_ENABLED = "capture_enabled"
        private const val KEY_SETTLE_MS = "settle_ms"
        private const val KEY_MAX_WAIT_MS = "max_wait_ms"
        private const val KEY_DEBUG_LOG = "debug_log"
    }
}

/**
 * MediaStore 텍스트 파일에 한 줄씩 덧붙여 쓴다. 한 줄 = 시각(HH:mm:ss.SSS) + 탭으로 구분한 필드.
 * 세션 로그(세션 폴더의 session_log.txt)와 디버그 로그(debug/{세션}.txt)에 쓴다.
 * 쓰기는 전용 스레드에서 하고, 마지막 쓰기 1초 뒤 flush 해 비정상 종료 때도 대부분 남긴다.
 */
class TextLog(context: Context, relativePath: String, name: String, header: List<String>) : Closeable {

    /** io 스레드에서만 쓴다. */
    private var writer: Writer? = null
    private var flushScheduled = false

    init {
        val store = EvidenceStore(context)
        io.execute {
            try {
                val out = store.createStream(relativePath, name, "text/plain")
                writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 64 * 1024).apply {
                    for (line in header) write("$line\n")
                }
            } catch (e: Exception) {
                Log.w(TAG, "로그 파일을 만들 수 없습니다: $relativePath$name", e)
            }
        }
    }

    /** 한 줄 기록. 접근성 이벤트 객체는 재사용되므로 호출자가 값을 꺼내서 넘긴다. */
    fun write(millis: Long, vararg fields: CharSequence?) {
        io.execute {
            val w = writer ?: return@execute
            try {
                w.write(TIME.format(Instant.ofEpochMilli(millis)))
                for (field in fields) {
                    w.write('\t'.code)
                    w.write(clean(field))
                }
                w.write('\n'.code)
                if (!flushScheduled) {
                    flushScheduled = true
                    io.schedule({ flush() }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
                }
            } catch (e: IOException) {
                Log.w(TAG, "로그 쓰기 실패", e)
                runCatching { w.close() }
                writer = null
            }
        }
    }

    private fun flush() {
        flushScheduled = false
        runCatching { writer?.flush() }
    }

    override fun close() {
        io.execute {
            runCatching { writer?.close() }
            writer = null
        }
    }

    private companion object {
        const val TAG = "TextLog"
        const val FLUSH_DELAY_MS = 1000L
        val io = Executors.newSingleThreadScheduledExecutor { Thread(it, "qa-log") }
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneId.systemDefault())

        fun clean(s: CharSequence?): String = s?.toString()?.replace('\t', ' ')?.replace('\n', ' ')?.replace('\r', ' ') ?: ""
    }
}
