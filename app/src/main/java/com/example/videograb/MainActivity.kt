package com.example.videograb

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var input: EditText
    private var ready = false
    private var pendingUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        status = TextView(this).apply { textSize = 18f; text = "جاري التجهيز..." }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        input = EditText(this).apply { hint = "الصق رابط الفيديو هنا" }
        val dlBtn = Button(this).apply {
            text = "تنزيل"
            setOnClickListener { startDownload(input.text.toString().trim()) }
        }
        val upBtn = Button(this).apply {
            text = "تحديث المحرك"
            setOnClickListener { updateEngine() }
        }
        root.addView(status)
        root.addView(bar)
        root.addView(input)
        root.addView(dlBtn)
        root.addView(upBtn)
        setContentView(root)

        pendingUrl = extractUrl(intent)

        thread {
            try {
                YoutubeDL.getInstance().init(application)
                FFmpeg.getInstance().init(application)
                ready = true
                runOnUiThread {
                    status.text = "جاهز. شارك رابط فيديو معي."
                    pendingUrl?.let { startDownload(it); pendingUrl = null }
                }
            } catch (e: Exception) {
                show("خطأ بالتجهيز: ${e.message}")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractUrl(intent)?.let {
            if (ready) startDownload(it) else pendingUrl = it
        }
    }

    private fun extractUrl(i: Intent?): String? {
        if (i?.action != Intent.ACTION_SEND) return null
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return Regex("https?://\\S+").find(text)?.value
    }

    private fun show(msg: String) = runOnUiThread { status.text = msg }

    private fun startDownload(url: String) {
        if (url.isEmpty()) { show("ما فيه رابط"); return }
        if (!ready) { pendingUrl = url; show("ننتظر التجهيز..."); return }
        thread {
            try {
                show("نبدي التنزيل...")
                val dir = File(cacheDir, "dl")
                dir.deleteRecursively()
                dir.mkdirs()

                val req = YoutubeDLRequest(url)
                req.addOption("-o", dir.absolutePath + "/%(title).80s.%(ext)s")
                req.addOption("-f", "b[ext=mp4]/bv*+ba/b")
                req.addOption("--merge-output-format", "mp4")
                req.addOption("--no-mtime")

                YoutubeDL.getInstance().execute(req) { progress, _, _ ->
                    runOnUiThread {
                        bar.progress = progress.toInt().coerceIn(0, 100)
                        status.text = "جاري التنزيل ${progress.toInt()}%"
                    }
                }

                var count = 0
                dir.listFiles()?.forEach {
                    save(it)
                    it.delete()
                    count++
                }
                show(if (count > 0) "تم! انحفظ بمجلد Download/VideoGrab" else "ما انحفظ شي")
            } catch (e: Exception) {
                show("فشل: ${e.message}")
            }
        }
    }

    private fun save(f: File) {
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(f.extension.lowercase()) ?: "video/mp4"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/VideoGrab")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(uri)?.use { out ->
            f.inputStream().use { it.copyTo(out) }
        }
    }

    private fun updateEngine() {
        if (!ready) { show("ننتظر التجهيز..."); return }
        thread {
            try {
                show("جاري تحديث المحرك...")
                val r = YoutubeDL.getInstance().updateYoutubeDL(application)
                show("نتيجة التحديث: $r")
            } catch (e: Exception) {
                show("فشل التحديث: ${e.message}")
            }
        }
    }
}
