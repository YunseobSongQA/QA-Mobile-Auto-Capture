package com.qa.autocapture.ui.viewer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.qa.autocapture.R
import com.qa.autocapture.storage.EvidenceStore
import com.qa.autocapture.storage.StoredFile
import com.qa.autocapture.ui.common.Ui
import com.qa.autocapture.ui.common.Ui.ButtonStyle
import com.qa.autocapture.ui.common.Ui.Type
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * 세션 사진 보기: 경로 순서(파일 이름 순)대로 옆으로 넘기며 본다.
 * 이어붙인 긴 사진은 위아래로 스크롤해 끝까지 본다. 확대가 필요하면 [갤러리로 열기].
 *
 * 메모리를 아끼려고 지금 장과 양옆 장만 읽어 두고, 긴 사진은 GPU 가 그릴 수 있는 높이(TILE_HEIGHT)로 잘라 읽는다.
 */
class ImageViewerActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var sessionId: String
    private lateinit var pager: PagerView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private var images: List<StoredFile> = emptyList()

    /** 읽어 둔 장 → 그 장의 Bitmap 들(해제용). */
    private val loaded = HashMap<Int, List<Bitmap>>()

    /** 읽는 중인 장과 그 요청 번호(그사이 다시 요청하거나 버리면 결과를 버린다). */
    private val loading = HashMap<Int, Int>()
    private var requestNo = 0

    private val main = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor { Thread(it, "qa-viewer") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        sessionId = intent.getStringExtra(EXTRA_SESSION) ?: return finish()
        val startName = savedInstanceState?.getString(STATE_NAME) ?: intent.getStringExtra(EXTRA_NAME)

        pager = PagerView(this, ::onPageSelected).apply { setBackgroundColor(Color.BLACK) }
        setContentView(ui.page(topBar(), pager))

        decoder.execute {
            val files = runCatching { EvidenceStore(this).listFiles(sessionId) }.getOrDefault(emptyList())
                .filter { it.name.endsWith(".png") }
            main.post {
                if (isDestroyed) return@post
                images = files
                pager.setPageCount(files.size)
                val start = files.indexOfFirst { it.name == startName }.coerceAtLeast(0)
                pager.showPage(start)
                onPageSelected(start)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        images.getOrNull(pager.current)?.let { outState.putString(STATE_NAME, it.name) }
    }

    override fun onDestroy() {
        decoder.shutdownNow()
        for (bitmaps in loaded.values) bitmaps.forEach { it.recycle() }
        loaded.clear()
        super.onDestroy()
    }

    /** 앱바: 뒤로 + "3 / 12" + 파일 이름 + [갤러리로 열기]. */
    private fun topBar(): LinearLayout {
        val bar = ui.row().apply {
            minimumHeight = ui.dp(64)
            setPadding(ui.dp(4), 0, ui.dp(4), 0)
        }
        bar.addView(ui.iconButton(R.drawable.ic_back, "뒤로", R.color.on_surface, ::finish))
        val texts = ui.column().apply { setPadding(ui.dp(4), 0, 0, 0) }
        title = ui.text("", Type.TITLE_MEDIUM)
        subtitle = ui.text("", Type.BODY_SMALL, R.color.on_surface_variant).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        texts.addView(title)
        texts.addView(subtitle)
        bar.addView(texts, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        bar.addView(ui.button("갤러리로 열기", ButtonStyle.TEXT) { openExternal() })
        return bar
    }

    /** 장이 바뀌면 제목을 고치고, 지금 장과 양옆만 읽어 두고 나머지는 해제한다. */
    private fun onPageSelected(index: Int) {
        val file = images.getOrNull(index)
        title.text = if (images.isEmpty()) "사진 없음" else "${index + 1} / ${images.size}"
        subtitle.text = file?.name ?: ""
        val keep = (index - 1)..(index + 1)
        for (i in loaded.keys.toList()) if (i !in keep) unload(i)
        for (i in loading.keys.toList()) if (i !in keep) loading.remove(i)
        load(index)
        load(index + 1)
        load(index - 1)
    }

    private fun load(index: Int) {
        val file = images.getOrNull(index) ?: return
        if (index in loaded || index in loading) return
        val no = ++requestNo
        loading[index] = no
        val page = pager.page(index)
        page.removeAllViews()
        page.addView(ProgressBar(this), FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
        val width = pager.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        decoder.execute {
            val tiles = decodeTiles(file.uri, width)
            main.post {
                if (isDestroyed || loading[index] != no) {
                    tiles?.forEach { it.recycle() }
                    return@post
                }
                loading.remove(index)
                show(index, tiles, width)
            }
        }
    }

    /** 장 하나 = 세로 스크롤 안에 타일을 폭에 맞춰 쌓은 것. */
    private fun show(index: Int, tiles: List<Bitmap>?, width: Int) {
        val page = pager.page(index)
        page.removeAllViews()
        if (tiles.isNullOrEmpty()) {
            page.addView(
                ui.text("사진을 열 수 없습니다", Type.BODY_MEDIUM, R.color.on_surface_variant).apply { setTextColor(Color.LTGRAY) },
                FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER),
            )
            return
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (bmp in tiles) {
            val h = (bmp.height.toLong() * width / bmp.width).toInt()
            column.addView(
                ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_XY
                    setImageBitmap(bmp)
                },
                LinearLayout.LayoutParams(Ui.MATCH, h),
            )
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        }
        page.addView(scroll, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        loaded[index] = tiles
    }

    private fun unload(index: Int) {
        val bitmaps = loaded.remove(index) ?: return
        pager.page(index).removeAllViews()
        bitmaps.forEach { it.recycle() }
    }

    /**
     * 사진을 화면 폭 [width] 에 맞게 TILE_HEIGHT 높이 조각들로 읽는다.
     * 큰 사진은 메모리(MAX_PAGE_BYTES)를 넘지 않게 줄여 읽는다(RGB_565).
     */
    private fun decodeTiles(uri: Uri, width: Int): List<Bitmap>? = try {
        val region = contentResolver.openInputStream(uri)?.use { newRegionDecoder(it) } ?: return null
        try {
            val w = region.width
            val h = region.height
            var sample = 1
            while (w / (sample * 2) >= width) sample *= 2
            while ((w / sample).toLong() * (h / sample) * 2 > MAX_PAGE_BYTES) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val tiles = ArrayList<Bitmap>()
            val step = TILE_HEIGHT * sample
            var y = 0
            while (y < h) {
                region.decodeRegion(Rect(0, y, w, minOf(h, y + step)), opts)?.let { tiles += it }
                y += step
            }
            tiles
        } finally {
            region.recycle()
        }
    } catch (e: Exception) {
        Log.w(TAG, "사진 읽기 실패: $uri", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "메모리 부족: $uri", e)
        null
    }

    private fun newRegionDecoder(input: InputStream): BitmapRegionDecoder? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(input)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(input, false)
        }

    /** 지금 사진을 기본 갤러리 앱으로 연다(확대해서 볼 때). */
    private fun openExternal() {
        val file = images.getOrNull(pager.current) ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(imageUri(file), "image/png")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "이미지를 열 앱이 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "ImageViewerActivity"
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_NAME = "name"
        private const val STATE_NAME = "name"

        /** 타일 1장 높이(px). GPU 가 한 번에 그릴 수 있는 크기보다 충분히 작게. */
        private const val TILE_HEIGHT = 2048

        /** 한 장(긴 사진)을 읽을 때 쓰는 최대 메모리. 넘으면 절반 크기로 읽는다. */
        private const val MAX_PAGE_BYTES = 24L * 1024 * 1024

        /** MediaStore 이미지 URI. 이미지 컬렉션 URI 라야 갤러리 앱이 받는다. */
        fun imageUri(file: StoredFile): Uri =
            ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.VOLUME_EXTERNAL_PRIMARY else "external"), ContentUris.parseId(file.uri))

        /** [name] 사진부터 연다. */
        fun intent(context: Context, sessionId: String, name: String): Intent =
            Intent(context, ImageViewerActivity::class.java).putExtra(EXTRA_SESSION, sessionId).putExtra(EXTRA_NAME, name)
    }
}
