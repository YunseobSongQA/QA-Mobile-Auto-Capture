package com.qa.autocapture.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 화면 1개의 저장 결과(manifest 의 screens 항목). 시각은 epoch millis. */
class ScreenRecord(
    val seq: Int,
    val files: List<String>,
    val packageName: String,
    val className: String,
    val enteredAt: Long,
    val exitedAt: Long,
    val frames: Int,
    val stitch: String,
)

/** 세션 중 감지한 토스트(manifest 의 toasts 항목). [screen] = 그때 화면의 className. */
class ToastRecord(val at: Long, val packageName: String, val text: String, val screen: String?)

/** 세션 1개의 기록. 내부 session.json 과 공개 manifest.json 의 원본. */
class SessionData(val id: String, val startedAt: Long) {
    var endedAt: Long? = null
    var ended = false
    val screens = mutableListOf<ScreenRecord>()
    val toasts = mutableListOf<ToastRecord>()
}

/** 화면 구간의 임시 정보(구간 폴더의 screen.json). */
class ScreenInfo(val packageName: String, val className: String, val enteredAt: Long)

/**
 * 파일 입출력 담당.
 * - 임시 프레임: noBackupFiles/sessions/{세션}/frames/{구간}/{번호}.png
 * - 결과물: MediaStore → Documents/QAEvidence/{세션}/ (저장소 권한 없이 내 파일만 다룬다)
 */
class EvidenceStore(context: Context) {

    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val root = File(app.noBackupFilesDir, "sessions")
    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    // ── 임시 프레임 ──────────────────────────────────────────────

    fun newSessionId(startedAt: Long): String {
        val base = format(startedAt, "yyyyMMdd_HHmmss")
        var id = base
        var n = 2
        while (File(root, id).exists()) id = "${base}_${n++}"
        File(root, id).mkdirs()
        return id
    }

    fun segmentDir(sessionId: String, segment: Int) =
        File(root, "$sessionId/frames/" + String.format(Locale.ROOT, "%04d", segment))

    fun pendingSegments(sessionId: String): List<File> =
        File(root, "$sessionId/frames").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()

    fun writeScreenInfo(dir: File, info: ScreenInfo) {
        dir.mkdirs()
        val json = JSONObject()
            .put("packageName", info.packageName)
            .put("className", info.className)
            .put("enteredAt", info.enteredAt)
        File(dir, SCREEN_FILE).writeText(json.toString())
    }

    fun readScreenInfo(dir: File): ScreenInfo? = try {
        val o = JSONObject(File(dir, SCREEN_FILE).readText())
        ScreenInfo(o.getString("packageName"), o.getString("className"), o.getLong("enteredAt"))
    } catch (e: Exception) {
        null
    }

    /** 스크린샷 버퍼를 임시 PNG 로 저장한다. 버퍼와 Bitmap 은 여기서 바로 해제한다. */
    fun writeFrame(dir: File, index: Int, buffer: HardwareBuffer, colorSpace: ColorSpace?) {
        var hardware: Bitmap? = null
        var software: Bitmap? = null
        try {
            hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace) ?: return
            software = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: return
            dir.mkdirs()
            val tmp = File(dir, "$index.tmp")
            BufferedOutputStream(FileOutputStream(tmp)).use { software.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // 끝까지 쓴 파일만 .png 로 보이게 해서 반쯤 쓴 프레임을 읽지 않는다
            tmp.renameTo(File(dir, "$index.png"))
        } catch (e: Exception) {
            Log.w(TAG, "프레임 저장 실패", e)
        } finally {
            software?.recycle()
            hardware?.recycle()
            buffer.close()
        }
    }

    fun listFrames(dir: File): List<File> =
        dir.listFiles { f -> f.name.endsWith(".png") }
            ?.sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }
            ?: emptyList()

    /** 임시 프레임 PNG 를 sRGB ARGB 픽셀로 읽는다. 같은 크기면 Bitmap 을 재사용한다. */
    class FrameReader : Closeable {
        private var bitmap: Bitmap? = null
        var width = 0
            private set
        var height = 0
            private set

        /** [into] 크기가 맞으면 거기에 채워 돌려주고, 아니면 새 배열을 만든다. 읽기 실패 시 null. */
        fun read(file: File, into: IntArray?): IntArray? {
            val bmp = decode(file) ?: return null
            width = bmp.width
            height = bmp.height
            val px = if (into != null && into.size == width * height) into else IntArray(width * height)
            bmp.getPixels(px, 0, width, 0, 0, width, height)
            return px
        }

        private fun decode(file: File): Bitmap? {
            val opts = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                inBitmap = bitmap
            }
            val reused = try {
                BitmapFactory.decodeFile(file.path, opts)
            } catch (e: IllegalArgumentException) {
                null // 크기가 달라 재사용 불가
            }
            if (reused != null) return reused.also { bitmap = it }
            bitmap?.recycle()
            bitmap = null
            opts.inBitmap = null
            return BitmapFactory.decodeFile(file.path, opts)?.also { bitmap = it }
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

        // 화면이 하나도 없는 세션은 공개 폴더를 만들지 않는다
        if (session.screens.isNotEmpty()) {
            writeText(sessionPath(session.id), MANIFEST_FILE, "application/json", toJson(session).toString(2))
        }
    }

    fun loadSessions(): List<SessionData> =
        root.listFiles()?.mapNotNull { readSession(it) }?.sortedByDescending { it.startedAt } ?: emptyList()

    private fun readSession(dir: File): SessionData? = try {
        val o = JSONObject(File(dir, SESSION_FILE).readText())
        SessionData(o.getString("session"), parseTime(o.getString("startedAt"))).apply {
            endedAt = o.optString("endedAt").takeIf { it.isNotEmpty() && it != "null" }?.let { parseTime(it) }
            ended = o.optBoolean("ended")
            val arr = o.getJSONArray("screens")
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val files = s.getJSONArray("files")
                screens += ScreenRecord(
                    seq = s.getInt("seq"),
                    files = List(files.length()) { files.getString(it) },
                    packageName = s.getString("packageName"),
                    className = s.getString("className"),
                    enteredAt = parseTime(s.getString("enteredAt")),
                    exitedAt = parseTime(s.getString("exitedAt")),
                    frames = s.getInt("frames"),
                    stitch = s.getString("stitch"),
                )
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
            screens.put(
                JSONObject()
                    .put("seq", s.seq)
                    .put("files", JSONArray(s.files))
                    .put("packageName", s.packageName)
                    .put("className", s.className)
                    .put("enteredAt", isoTime(s.enteredAt))
                    .put("exitedAt", isoTime(s.exitedAt))
                    .put("frames", s.frames)
                    .put("stitch", s.stitch),
            )
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

    /** 계속 덧붙여 쓸 MediaStore 파일을 만든다(디버그 로그). 스트림은 호출자가 닫는다. */
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

    /** 같은 이름이 있으면 덮어쓰고, 없으면 새로 만든다. */
    private fun writeText(relativePath: String, name: String, mime: String, text: String) {
        val bytes = text.toByteArray()
        val existing = find(relativePath, name)
        if (existing == null) {
            saveFile(relativePath, name, mime) { it.write(bytes) }
            return
        }
        val out = resolver.openOutputStream(existing, "wt") ?: throw IOException("파일을 열 수 없습니다: $name")
        out.use { it.write(bytes) }
    }

    private fun find(relativePath: String, name: String): Uri? = resolver.query(
        collection,
        arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
        arrayOf(relativePath, name),
        null,
    )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }

    /**
     * 세션 폴더의 내 파일을 zip 으로 묶어 Documents/QAEvidence/{세션}.zip 에 저장한다(기존 zip 은 교체).
     * 공유 인텐트에 그대로 쓸 수 있는 URI 를 돌려준다.
     */
    fun exportZip(sessionId: String): Uri {
        val items = mutableListOf<Pair<Uri, String>>()
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(sessionPath(sessionId)),
            "${MediaStore.MediaColumns.DISPLAY_NAME} ASC",
        )?.use { c ->
            while (c.moveToNext()) items += ContentUris.withAppendedId(collection, c.getLong(0)) to c.getString(1)
        }
        if (items.isEmpty()) throw IOException("저장된 파일이 없습니다")
        find(DEBUG_PATH, "$sessionId.txt")?.let { items += it to "debug_log.txt" }

        val zipName = "$sessionId.zip"
        find(ROOT_PATH, zipName)?.let { resolver.delete(it, null, null) }
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
        private const val SCREEN_FILE = "screen.json"
        private const val SESSION_FILE = "session.json"
        private const val MANIFEST_FILE = "manifest.json"

        /** 구간 폴더 안에서 "토스트 순간" 프레임을 두는 하위 폴더(스티칭 대상 아님). */
        const val TOAST_DIR = "toast"

        /** 결과물 최상위 폴더(MediaStore RELATIVE_PATH). */
        val ROOT_PATH = "${Environment.DIRECTORY_DOCUMENTS}/QAEvidence/"

        /** 디버그 로그 폴더. 세션별 {세션}.txt */
        val DEBUG_PATH = "${ROOT_PATH}debug/"

        fun sessionPath(sessionId: String) = "$ROOT_PATH$sessionId/"

        /** {순번3자리}_{패키지 끝부분}_{화면클래스 짧은이름}_{HHmmss} */
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

        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)

        fun isoTime(millis: Long): String = ISO.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

        private fun parseTime(s: String): Long = OffsetDateTime.parse(s, ISO).toInstant().toEpochMilli()

        fun format(millis: Long, pattern: String): String =
            DateTimeFormatter.ofPattern(pattern, Locale.ROOT).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    }
}

/** 메인 화면의 스위치 값. 서비스와 같은 프로세스라 SharedPreferences 로 공유한다. */
class Options(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("options", Context.MODE_PRIVATE)

    /** 토스트가 뜬 순간도 별도 PNG(…_toast.png)로 저장한다. */
    var saveToastShot: Boolean
        get() = prefs.getBoolean(KEY_TOAST_SHOT, false)
        set(value) = prefs.edit().putBoolean(KEY_TOAST_SHOT, value).apply()

    /** 기록 중 받은 접근성 이벤트를 디버그 로그로 저장한다. */
    var debugLog: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_LOG, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_LOG, value).apply()

    private companion object {
        const val KEY_TOAST_SHOT = "save_toast_shot"
        const val KEY_DEBUG_LOG = "debug_log"
    }
}

/**
 * 기록 중 받은 접근성 이벤트를 Documents/QAEvidence/debug/{세션}.txt 에 한 줄씩 쓴다(탭 구분).
 * 쓰기는 전용 스레드에서 하고 1초마다 flush 해 비정상 종료 때도 대부분 남긴다.
 */
class DebugLog(context: Context, sessionId: String) : Closeable {

    /** io 스레드에서만 쓴다. */
    private var writer: Writer? = null
    private var lastFlush = 0L

    init {
        val store = EvidenceStore(context)
        io.execute {
            try {
                val out = store.createStream(EvidenceStore.DEBUG_PATH, "$sessionId.txt", "text/plain")
                writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 64 * 1024).apply {
                    write("# QA 증적 캡처 디버그 로그 / 세션 $sessionId\n")
                    write("# 시각\t이벤트 타입\t패키지\t클래스\t텍스트\t기타\n")
                }
            } catch (e: Exception) {
                Log.w(TAG, "디버그 로그를 만들 수 없습니다", e)
            }
        }
    }

    /** 한 줄 기록. 이벤트 객체는 재사용되므로 호출자가 값을 꺼내서 넘긴다. */
    fun log(millis: Long, type: String, packageName: CharSequence?, className: CharSequence?, text: CharSequence?, extra: String = "") {
        io.execute {
            val w = writer ?: return@execute
            try {
                w.write(TIME.format(Instant.ofEpochMilli(millis)))
                for (field in arrayOf(type, packageName, className, text, extra)) {
                    w.write('\t'.code)
                    w.write(clean(field))
                }
                w.write('\n'.code)
                val now = SystemClock.uptimeMillis()
                if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                    w.flush()
                    lastFlush = now
                }
            } catch (e: IOException) {
                Log.w(TAG, "디버그 로그 쓰기 실패", e)
                runCatching { w.close() }
                writer = null
            }
        }
    }

    override fun close() {
        io.execute {
            runCatching { writer?.close() }
            writer = null
        }
    }

    private companion object {
        const val TAG = "DebugLog"
        const val FLUSH_INTERVAL_MS = 1000L
        val io = Executors.newSingleThreadExecutor { Thread(it, "qa-debug") }
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneId.systemDefault())

        fun clean(s: CharSequence?): String = s?.toString()?.replace('\t', ' ')?.replace('\n', ' ')?.replace('\r', ' ') ?: ""
    }
}
