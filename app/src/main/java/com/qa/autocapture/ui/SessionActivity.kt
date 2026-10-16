package com.qa.autocapture.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.qa.autocapture.R
import com.qa.autocapture.storage.CaptureRecord
import com.qa.autocapture.storage.EvidenceStore
import com.qa.autocapture.storage.SessionData
import com.qa.autocapture.storage.StoredFile
import com.qa.autocapture.ui.Ui.ButtonStyle
import com.qa.autocapture.ui.Ui.Type
import java.util.concurrent.Executors

/** 세션 1개 열기: 요약, zip 공유, 세션 로그 보기, 저장한 이미지 목록(누르면 이미지 보기). */
class SessionActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var store: EvidenceStore
    private lateinit var sessionId: String
    private var session: SessionData? = null
    private var images: List<StoredFile> = emptyList()
    private var logFile: StoredFile? = null
    private var captures: Map<String, CaptureRecord> = emptyMap()

    private lateinit var summary: TextView
    private lateinit var shareButton: View
    private lateinit var logButton: View
    private val adapter = ImageAdapter()

    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newFixedThreadPool(2) { Thread(it, "qa-thumb") }
    private val thumbs = object : LruCache<String, Bitmap>(THUMB_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        store = EvidenceStore(this)
        sessionId = intent.getStringExtra(EXTRA_SESSION) ?: return finish()
        val s = store.loadSession(sessionId)
        session = s
        captures = s?.screens?.flatMap { it.captures }?.associateBy { it.file } ?: emptyMap()

        val list = ListView(this).apply {
            divider = null
            selector = ui.rowBackground()
            addHeaderView(header(), null, false)
            adapter = this@SessionActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                images.getOrNull(position - headerViewsCount)?.let { view(it) }
            }
        }
        val title = s?.let { EvidenceStore.format(it.startedAt, "yyyy-MM-dd HH:mm:ss") } ?: sessionId
        setContentView(ui.page(ui.appBar(title, onBack = ::finish), list))
        loadFiles()
    }

    override fun onDestroy() {
        loader.shutdownNow()
        thumbs.evictAll()
        super.onDestroy()
    }

    private fun header(): View {
        val box = ui.column().apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), 0) }
        val card = ui.card()
        summary = ui.text("", Type.BODY_MEDIUM, R.color.on_surface_variant)
        card.addView(summary)
        val buttons = ui.row().apply { setPadding(0, ui.dp(12), 0, 0) }
        shareButton = ui.button("zip으로 공유", ButtonStyle.FILLED) { share() }
        logButton = ui.button("세션 로그", ButtonStyle.TONAL) { showLog() }
        buttons.addView(shareButton)
        buttons.addView(logButton, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginStart = ui.dp(8) })
        card.addView(buttons)
        box.addView(card)
        box.addView(ui.sectionTitle("이미지"))
        ui.setEnabled(shareButton, false)
        ui.setEnabled(logButton, false)
        updateSummary()
        return box
    }

    private fun updateSummary() {
        val s = session
        summary.text = buildString {
            if (s != null) {
                append("이미지 ").append(s.imageCount).append("장 · 화면 ").append(s.screens.size).append("개")
                if (s.toasts.isNotEmpty()) append(" · 토스트 ").append(s.toasts.size).append("건")
                append('\n')
                val end = s.endedAt
                append(EvidenceStore.format(s.startedAt, "HH:mm:ss")).append(" ~ ")
                append(if (s.ended && end != null) EvidenceStore.format(end, "HH:mm:ss") else "기록 중")
                append('\n')
            }
            append("저장 위치: 내장 저장공간/").append(EvidenceStore.sessionPath(sessionId))
        }
    }

    private fun loadFiles() {
        loader.execute {
            val files = runCatching { store.listFiles(sessionId) }.getOrDefault(emptyList())
            main.post {
                if (isDestroyed) return@post
                images = files.filter { it.name.endsWith(".png") }
                logFile = files.firstOrNull { it.name == EvidenceStore.SESSION_LOG_FILE }
                ui.setEnabled(shareButton, files.isNotEmpty())
                ui.setEnabled(logButton, logFile != null)
                adapter.notifyDataSetChanged()
            }
        }
    }

    /** 세션 폴더를 zip 으로 묶어(백그라운드) 공유 시트를 연다. */
    private fun share() {
        ui.setEnabled(shareButton, false)
        Toast.makeText(this, "압축하는 중…", Toast.LENGTH_SHORT).show()
        loader.execute {
            val result = runCatching { store.exportZip(sessionId) }
            main.post {
                if (isDestroyed) return@post
                ui.setEnabled(shareButton, true)
                result
                    .onSuccess { uri ->
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("application/zip")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .putExtra(Intent.EXTRA_SUBJECT, "QA 증적 $sessionId")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(Intent.createChooser(send, "QA 증적 공유"))
                    }
                    .onFailure { Toast.makeText(this, "공유 실패: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showLog() {
        val file = logFile ?: return
        loader.execute {
            val text = runCatching { store.readText(file.uri, MAX_LOG_BYTES) }.getOrElse { "읽을 수 없습니다: ${it.message}" }
            main.post {
                if (isDestroyed) return@post
                val body = ui.text(text, Type.BODY_SMALL).apply {
                    typeface = Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(8))
                }
                AlertDialog.Builder(this)
                    .setTitle(EvidenceStore.SESSION_LOG_FILE)
                    .setView(ScrollView(this).apply { addView(body) })
                    .setPositiveButton("닫기", null)
                    .show()
            }
        }
    }

    /** 기본 이미지 보기 앱으로 연다. 이미지 컬렉션 URI 로 넘겨야 갤러리 앱이 받는다. */
    private fun view(file: StoredFile) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(imageUri(file), "image/png")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "이미지를 열 앱이 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun imageUri(file: StoredFile): Uri =
        ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentUris.parseId(file.uri))

    private fun describe(name: String): String {
        val c = captures[name]
        val time = c?.let { EvidenceStore.format(it.at, "HH:mm:ss") }
        val kind = when {
            c != null && c.frames > 1 -> "스크롤 이어붙임 · 화면 ${c.frames}장"
            // 이전 버전 파일 이름
            name.contains("_scroll") || name.contains("_full") -> "스크롤 이어붙인 이미지"
            name.contains("_final") -> "최종 화면"
            else -> "화면"
        }
        return listOfNotNull(time, kind).joinToString(" · ")
    }

    // ── 목록 ────────────────────────────────────────────────────

    private inner class ImageAdapter : BaseAdapter() {
        override fun getCount() = images.size

        override fun getItem(position: Int) = images[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder = (convertView?.tag as? Holder) ?: Holder(this@SessionActivity)
            val file = images[position]
            holder.name.text = file.name
            holder.meta.text = describe(file.name)
            bindThumb(holder.thumb, file)
            return holder.root
        }
    }

    private inner class Holder(context: Context) {
        val thumb = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply {
                cornerRadius = ui.dp(8).toFloat()
                setColor(ui.color(R.color.secondary_container))
            }
        }
        val name = ui.text("", Type.BODY_MEDIUM).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val meta = ui.text("", Type.BODY_SMALL, R.color.on_surface_variant)
        val root: LinearLayout = ui.row().apply {
            setPadding(ui.dp(16), ui.dp(8), ui.dp(8), ui.dp(8))
            addView(thumb, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
            val texts = ui.column()
            texts.addView(name)
            texts.addView(meta)
            addView(texts, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { marginStart = ui.dp(16) })
            addView(ui.icon(R.drawable.ic_chevron, R.color.on_surface_variant))
            tag = this@Holder
        }
    }

    private fun bindThumb(view: ImageView, file: StoredFile) {
        val key = file.uri.toString()
        view.tag = key
        val cached = thumbs.get(key)
        view.setImageBitmap(cached)
        if (cached != null) return
        val px = ui.dp(56)
        loader.execute {
            val bmp = loadThumb(file, px) ?: return@execute
            main.post {
                thumbs.put(key, bmp)
                if (view.tag == key) view.setImageBitmap(bmp)
            }
        }
    }

    /** MediaProvider 썸네일을 쓰고, 안 되면 직접 줄여 읽는다. */
    private fun loadThumb(file: StoredFile, px: Int): Bitmap? = try {
        contentResolver.loadThumbnail(imageUri(file), Size(px * 2, px * 2), null)
    } catch (e: Exception) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(file.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= px * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(file.uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }

    companion object {
        private const val EXTRA_SESSION = "session"
        private const val THUMB_CACHE_BYTES = 8 * 1024 * 1024
        private const val MAX_LOG_BYTES = 256 * 1024

        fun intent(context: Context, sessionId: String): Intent =
            Intent(context, SessionActivity::class.java).putExtra(EXTRA_SESSION, sessionId)
    }
}
