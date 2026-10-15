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
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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

/** 세션 1개의 기록. 내부 session.json 과 공개 manifest.json 의 원본. */
class SessionData(val id: String, val startedAt: Long) {
    var endedAt: Long? = null
    var ended = false
    val screens = mutableListOf<ScreenRecord>()
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

        /** 결과물 최상위 폴더(MediaStore RELATIVE_PATH). */
        val ROOT_PATH = "${Environment.DIRECTORY_DOCUMENTS}/QAEvidence/"

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
