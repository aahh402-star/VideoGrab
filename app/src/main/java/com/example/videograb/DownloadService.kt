package com.example.videograb

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

object Hub {
    @Volatile var active = false
    @Volatile var progress = 0
    @Volatile var indeterminate = false
    @Volatile var text = ""
    @Volatile var waiting = 0
    @Volatile var listener: (() -> Unit)? = null
    fun post() { listener?.invoke() }
}

object Engine {
    private var ok = false
    @Synchronized
    fun ensure(app: Application) {
        if (ok) return
        YoutubeDL.getInstance().init(app)
        FFmpeg.getInstance().init(app)
        ok = true
    }
}

object Store {
    private fun sp(c: Context) = c.getSharedPreferences("hist", Context.MODE_PRIVATE)

    @Synchronized
    fun add(c: Context, name: String, uri: String, mime: String) {
        val old = JSONArray(sp(c).getString("list", "[]") ?: "[]")
        val out = JSONArray()
        out.put(
            JSONObject()
                .put("n", name)
                .put("u", uri)
                .put("m", mime)
                .put("t", System.currentTimeMillis())
        )
        for (i in 0 until minOf(old.length(), 99)) out.put(old.get(i))
        sp(c).edit().putString("list", out.toString()).apply()
    }

    @Synchronized
    fun all(c: Context): List<JSONObject> {
        val a = JSONArray(sp(c).getString("list", "[]") ?: "[]")
        val r = ArrayList<JSONObject>()
        for (i in 0 until a.length()) r.add(a.getJSONObject(i))
        return r
    }

    fun clear(c: Context) {
        sp(c).edit().remove("list").apply()
    }
}

class DownloadService : Service() {

    class Job(val url: String, val q: Int, val playlist: Boolean, val title: String)

    companion object {
        const val CH = "dl"
        const val CH_DONE = "done"
        const val ID = 1
        const val PID = "dl"
        private val ITEM = Regex("(?:item|video) (\\d+) of (\\d+)")
    }

    private val queue = LinkedBlockingQueue<Job>()
    private val lock = Any()
    private var worker: Thread? = null
    @Volatile private var cancelled = false
    @Volatile private var lastId = 0
    @Volatile private var lastPct = -1
    private var doneId = 100

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastId = startId

        if (intent?.getStringExtra("cmd") == "cancel") {
            cancelled = true
            try { YoutubeDL.getInstance().destroyProcessById(PID) } catch (e: Exception) { }
            if (worker == null) stopSelf(startId)
            return START_NOT_STICKY
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "التنزيلات", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "اكتمال التنزيل", NotificationManager.IMPORTANCE_DEFAULT)
        )
        startForeground(
            ID,
            notif("جاري التجهيز...", 0, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        if (intent != null) {
            val url = intent.getStringExtra("url")
            if (url != null) {
                queue.add(
                    Job(
                        url,
                        intent.getIntExtra("q", 0),
                        intent.getBooleanExtra("pl", false),
                        intent.getStringExtra("title") ?: ""
                    )
                )
                Hub.waiting = queue.size
                Hub.post()
            }
        }
        startWorker()
        return START_NOT_STICKY
    }

    private fun startWorker() {
        synchronized(lock) {
            if (worker != null) return
            worker = thread {
                while (true) {
                    var job: Job? = null
                    synchronized(lock) {
                        job = queue.poll()
                        if (job == null) worker = null
                    }
                    val j = job ?: break
                    Hub.waiting = queue.size
                    Hub.post()
                    process(j)
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(lastId)
            }
        }
    }

    private fun hasFiles(dir: File): Boolean =
        dir.listFiles()?.any { !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") } == true

    private fun process(job: Job) {
        cancelled = false
        lastPct = -1
        val name = if (job.title.isNotEmpty()) job.title else "فيديو"
        setState(true, 0, true, "جاري التجهيز: $name")

        val dir = File(cacheDir, "dl")
        dir.deleteRecursively()
        dir.mkdirs()

        try {
            Engine.ensure(application)
            try {
                doDownload(job, dir, name)
            } catch (e: Exception) {
                if (cancelled) throw e
                if (!(job.playlist && hasFiles(dir))) {
                    setState(true, 0, true, "فشل، نحدّث المحرك ونعيد المحاولة...")
                    try { YoutubeDL.getInstance().updateYoutubeDL(application) } catch (e2: Exception) { }
                    dir.deleteRecursively()
                    dir.mkdirs()
                    try {
                        doDownload(job, dir, name)
                    } catch (e3: Exception) {
                        if (cancelled || !(job.playlist && hasFiles(dir))) throw e3
                    }
                }
            }

            setState(true, 100, true, "جاري الحفظ...")
            var count = 0
            var lastName = ""
            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                if (f.name.endsWith(".part") || f.name.endsWith(".ytdl")) return@forEach
                if (save(f)) {
                    count++
                    lastName = f.nameWithoutExtension
                }
            }
            if (count > 0) {
                val shortMsg = if (count > 1) "تم تنزيل $count ملفات ✅" else "تم ✅ $lastName"
                finish(shortMsg, "$shortMsg\nانحفظ بمجلد Download/VideoGrab")
            } else {
                finish("ما انحفظ شي ❌", "ما انحفظ شي ❌ جرب جودة ثانية")
            }
        } catch (e: Exception) {
            if (cancelled) {
                finish("تم الإلغاء", "تم الإلغاء")
            } else {
                val msg = (e.message ?: "").take(500)
                finish("فشل التنزيل ❌", "فشل التنزيل ❌\n$msg")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun doDownload(job: Job, dir: File, name: String) {
        val req = YoutubeDLRequest(job.url)
        val tpl = if (job.playlist) "%(playlist_index)s - %(title).70s.%(ext)s"
        else "%(title).80s.%(ext)s"
        req.addOption("-o", dir.absolutePath + "/" + tpl)

        when (job.q) {
            -1 -> {
                req.addOption("-f", "ba/b")
                req.addOption("-x")
                req.addOption("--audio-format", "mp3")
            }
            -2 -> {
                req.addOption("-f", "ba/b")
                req.addOption("-x")
                req.addOption("--audio-format", "m4a")
            }
            else -> {
                val sel = if (job.q == 0) "bv*+ba/b"
                else "bv*[height<=${job.q}]+ba/b[height<=${job.q}]/b"
                req.addOption("-f", sel)
                req.addOption("-S", "res,vcodec:h264,acodec:m4a")
                req.addOption("--merge-output-format", "mp4")
            }
        }
        req.addOption("--no-mtime")
        req.addOption("-N", "4")
        req.addOption("--retries", "3")
        req.addOption("--fragment-retries", "3")
        if (job.playlist) {
            req.addOption("--yes-playlist")
            req.addOption("--ignore-errors")
        } else {
            req.addOption("--no-playlist")
        }

        var item = ""
        YoutubeDL.getInstance().execute(req, PID) { progress, _, line ->
            val m = ITEM.find(line)
            if (m != null) item = "عنصر ${m.groupValues[1]} من ${m.groupValues[2]} • "
            val p = progress.toInt().coerceIn(0, 100)
            if (p != lastPct) {
                lastPct = p
                setState(true, p, false, "$item$name\n$p%")
            }
        }
    }

    private fun save(f: File): Boolean {
        val ext = f.extension.lowercase()
        var mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/mp4"
        if (ext == "mp3") mime = "audio/mpeg"
        if (ext == "m4a") mime = "audio/mp4"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/VideoGrab")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return false
        contentResolver.openOutputStream(uri)?.use { out ->
            f.inputStream().use { it.copyTo(out) }
