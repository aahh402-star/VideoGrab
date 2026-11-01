package com.example.videograb

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val BLUE = Color.parseColor("#2563EB")
    private val PURPLE = Color.parseColor("#7C3AED")
    private val DARK = Color.parseColor("#0F172A")
    private val GRAY = Color.parseColor("#64748B")
    private val LIGHT = Color.parseColor("#E2E8F0")

    private lateinit var input: EditText
    private lateinit var analyzeMsg: TextView
    private lateinit var infoCard: LinearLayout
    private lateinit var thumb: ImageView
    private lateinit var titleView: TextView
    private lateinit var chipBox: LinearLayout
    private lateinit var statusCard: LinearLayout
    private lateinit var bar: ProgressBar
    private lateinit var status: TextView
    private lateinit var cancelBtn: Button
    private lateinit var pageMain: LinearLayout
    private lateinit var pageHist: LinearLayout
    private lateinit var histList: LinearLayout

    @Volatile private var ready = false
    @Volatile private var gen = 0
    @Volatile private var lastUrl = ""
    private var currentUrl = ""
    private var currentTitle = ""
    private var currentPlaylist = false
    private var selectedQ = 0
    private val chips = ArrayList<Pair<Button, Int>>()
    private var pendingUrl: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRun: Runnable? = null
    private val urlRegex = Regex("https?://\\S+")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = bg(Color.WHITE, 16)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        elevation = dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
    }

    private fun btn(label: String, hex: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        isAllCaps = false
        textSize = 16f
        background = bg(Color.parseColor(hex), 12)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun rowBtn(label: String, hex: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        isAllCaps = false
        textSize = 15f
        background = bg(Color.parseColor(hex), 12)
        layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
            setMargins(dp(4), dp(8), dp(4), 0)
        }
        setOnClickListener { onClick() }
    }

    private fun chip(label: String, q: Int): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 14f
        val lp = LinearLayout.LayoutParams(0, dp(46), 1f)
        lp.setMargins(dp(4), dp(4), dp(4), dp(4))
        b.layoutParams = lp
        b.setOnClickListener {
            selectedQ = q
            refreshChips()
        }
        chips.add(Pair(b, q))
        return b
    }

    private fun refreshChips() {
        for ((b, q) in chips) {
            val on = q == selectedQ
            val col = if (q < 0) PURPLE else BLUE
            b.background = bg(if (on) col else LIGHT, 12)
            b.setTextColor(if (on) Color.WHITE else DARK)
        }
    }

    private fun showMsg(t: String) {
        runOnUiThread {
            analyzeMsg.text = t
            analyzeMsg.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun showHistory(on: Boolean) {
        pageMain.visibility = if (on) View.GONE else View.VISIBLE
        pageHist.visibility = if (on) View.VISIBLE else View.GONE
        if (on) refreshHistory()
    }

    override fun onBackPressed() {
        if (pageHist.visibility == View.VISIBLE) showHistory(false) else super.onBackPressed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#F1F5F9"))
        scroll.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(24), dp(16), dp(24))

        // الهيدر (بالمنتصف)
        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.gravity = Gravity.CENTER_HORIZONTAL
        val hbg = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(BLUE, PURPLE)
        )
        hbg.cornerRadius = dp(20).toFloat()
        header.background = hbg
        header.setPadding(dp(20), dp(24), dp(20), dp(24))
        val hlp = LinearLayout.LayoutParams(MATCH, WRAP)
        hlp.bottomMargin = dp(12)
        header.layoutParams = hlp
        val t1 = TextView(this)
        t1.text = "العرداوي"
        t1.textSize = 30f
        t1.gravity = Gravity.CENTER
        t1.setTypeface(null, Typeface.BOLD)
        t1.setTextColor(Color.WHITE)
        t1.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        val t2 = TextView(this)
        t2.text = "نزّل فيديوهاتك بأي جودة، بدون إعلانات"
        t2.textSize = 14f
        t2.gravity = Gravity.CENTER
        t2.setTextColor(Color.parseColor("#E0E7FF"))
        t2.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        header.addView(t1)
        header.addView(t2)
        root.addView(header)

        // الصفحة الرئيسية
        pageMain = LinearLayout(this)
        pageMain.orientation = LinearLayout.VERTICAL

        val linkCard = card()
        input = EditText(this)
        input.hint = "الصق رابط الفيديو هنا"
        input.setSingleLine(true)
        input.textSize = 15f
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pendingRun?.let { handler.removeCallbacks(it) }
                val r = Runnable { checkInput(false) }
                pendingRun = r
                handler.postDelayed(r, 350)
            }
        })
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        row.addView(rowBtn("لصق من الحافظة", "#2563EB") { pasteClip() })
        row.addView(rowBtn("السجل", "#64748B") { showHistory(true) })
        analyzeMsg = TextView(this)
        analyzeMsg.textSize = 14f
        analyzeMsg.setTextColor(GRAY)
        analyzeMsg.setTextIsSelectable(true)
        analyzeMsg.setPadding(0, dp(10), 0, 0)
        analyzeMsg.visibility = View.GONE
        linkCard.addView(input)
        linkCard.addView(row)
        linkCard.addView(analyzeMsg)
        pageMain.addView(linkCard)

        infoCard = card()
        thumb = ImageView(this)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.background = bg(LIGHT, 12)
        thumb.clipToOutline = true
        val thlp = LinearLayout.LayoutParams(MATCH, dp(180))
        thlp.bottomMargin = dp(8)
        thumb.layoutParams = thlp
        titleView = TextView(this)
        titleView.textSize = 15f
        titleView.setTypeface(null, Typeface.BOLD)
        titleView.setTextColor(DARK)
        titleView.setPadding(0, 0, 0, dp(8))
        val label = TextView(this)
        label.text = "اختر الجودة أو الصيغة"
        label.textSize = 13f
        label.setTextColor(GRAY)
        chipBox = LinearLayout(this)
        chipBox.orientation = LinearLayout.VERTICAL
        infoCard.addView(thumb)
        infoCard.addView(titleView)
        infoCard.addView(label)
        infoCard.addView(chipBox)
        infoCard.addView(btn("⬇  ابدأ التنزيل", "#16A34A") { startDownload() })
        infoCard.visibility = View.GONE
        pageMain.addView(infoCard)

        statusCard = card()
        status = TextView(this)
        status.textSize = 15f
        status.setTextIsSelectable(true)
        status.setTextColor(Color.parseColor("#334155"))
        status.setPadding(0, 0, 0, dp(8))
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        cancelBtn = btn("إلغاء التنزيل", "#DC2626") {
            startService(Intent(this, DownloadService::class.java).putExtra("cmd", "cancel"))
        }
        cancelBtn.visibility = View.GONE
        statusCard.addView(status)
        statusCard.addView(bar)
        statusCard.addView(cancelBtn)
        statusCard.visibility = View.GONE
        pageMain.addView(statusCard)

        root.addView(pageMain)

        // صفحة السجل
        pageHist = LinearLayout(this)
        pageHist.orientation = LinearLayout.VERTICAL
        pageHist.addView(btn("رجوع", "#2563EB") { showHistory(false) })
        pageHist.addView(btn("مسح السجل", "#64748B") {
            Store.clear(this)
            refreshHistory()
        })
        histList = LinearLayout(this)
        histList.orientation = LinearLayout.VERTICAL
        histList.setPadding(0, dp(12), 0, 0)
        pageHist.addView(histList)
        pageHist.visibility = View.GONE
        root.addView(pageHist)

        scroll.addView(root)
        setContentView(scroll)

        val shared = extractUrl(intent)
        if (shared != null) {
            lastUrl = shared
            pendingUrl = shared
            input.setText(shared)
            showMsg("جاري تجهيز المحرك...")
        }

        thread {
            try {
                Engine.ensure(application)
                ready = true
                runOnUiThread {
                    val u = pendingUrl
                    if (u != null) {
                        pendingUrl = null
                        analyze(u)
                    }
                }
            } catch (e: Exception) {
                showMsg("خطأ بالتجهيز: ${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Hub.listener = { runOnUiThread { renderProgress() } }
        renderProgress()
    }

    override fun onPause() {
        super.onPause()
        Hub.listener = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val u = extractUrl(intent)
        if (u != null) {
            showHistory(false)
            lastUrl = ""
            input.setText(u)
            checkInput(true)
        }
    }

    private fun extractUrl(i: Intent?): String? {
        if (i?.action != Intent.ACTION_SEND) return null
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return urlRegex.find(text)?.value
    }

    private fun renderProgress() {
        val has = Hub.active || Hub.text.isNotEmpty()
        statusCard.visibility = if (has) View.VISIBLE else View.GONE
        bar.visibility = if (Hub.active) View.VISIBLE else View.GONE
        bar.isIndeterminate = Hub.active && Hub.indeterminate
        bar.progress = Hub.progress
        if (Hub.text.isNotEmpty()) {
            val extra = if (Hub.waiting > 0) "\nبالانتظار: ${Hub.waiting}" else ""
            status.text = Hub.text + extra
        }
        cancelBtn.visibility = if (Hub.active) View.VISIBLE else View.GONE
        if (!Hub.active && pageHist.visibility == View.VISIBLE) refreshHistory()
    }

    private fun pasteClip() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        val u = urlRegex.find(t)?.value
        if (u == null) {
            showMsg("ما لقيت رابط بالحافظة")
            return
        }
        lastUrl = ""
        input.setText(u)
        checkInput(true)
    }

    private fun checkInput(force: Boolean) {
        val txt = input.text.toString()
        val u = urlRegex.find(txt)?.value
        if (u == null || !u.contains(".")) {
            if (txt.isBlank()) {
                gen += 1
                lastUrl = ""
                infoCard.visibility = View.GONE
                analyzeMsg.visibility = View.GONE
            }
            return
        }
        if (!force && u == lastUrl) return
        lastUrl = u
        analyze(u)
    }

    private fun isPlaylist(u: String): Boolean {
        val s = u.lowercase()
        return s.contains("/playlist") || s.contains("/sets/") ||
            (s.contains("list=") && !s.contains("v=") && !s.contains("youtu.be/"))
    }

    private fun infoRequest(url: String): YoutubeDLRequest {
        val r = YoutubeDLRequest(url)
        r.addOption("--no-playlist")
        r.addOption("--socket-timeout", "10")
        r.addOption("--extractor-retries", "1")
        return r
    }

    private fun analyze(url: String) {
        if (!ready) {
            pendingUrl = url
            showMsg("جاري تجهيز المحرك...")
            return
        }
        gen += 1
        val my = gen
        currentUrl = url
        currentPlaylist = isPlaylist(url)
        infoCard.visibility = View.GONE
        if (!Hub.active && Hub.text.isNotEmpty()) {
            Hub.text = ""
            renderProgress()
        }

        if (currentPlaylist) {
            showOptions("قائمة تشغيل كاملة", listOf(1080, 720, 480, 360), null)
            showMsg("")
            return
        }

        showMsg("جاري التعرف على الفيديو...")
        thread {
            try {
                val yt = YoutubeDL.getInstance()
                val info = try {
                    yt.getInfo(infoRequest(url))
                } catch (e: Exception) {
                    if (my != gen) return@thread
                    showMsg("نحدّث المحرك ونعيد المحاولة...")
                    yt.updateYoutubeDL(application)
                    yt.getInfo(infoRequest(url))
                }
                if (my != gen) return@thread
                val title: String = info.title ?: "فيديو"
                var heights = (info.formats ?: emptyList()).mapNotNull { f ->
                    val h = f.height.toString().toIntOrNull() ?: 0
                    val v = f.vcodec.toString()
                    if (h >= 144 && v != "none" && v != "null") h else null
                }.distinct().sortedDescending()
                if (heights.isEmpty()) heights = listOf(1080, 720, 480, 360)
                val thumbUrl: String? = info.thumbnail
                runOnUiThread {
                    if (my == gen) {
                        showOptions(title, heights, thumbUrl)
                        showMsg("")
                    }
                }
            } catch (e: Exception) {
                if (my == gen) {
                    lastUrl = ""
                    showMsg("فشل التعرف على الرابط ❌\n" + (e.message ?: "").take(400))
                }
            }
        }
    }

    private fun showOptions(title: String, heights: List<Int>, thumbUrl: String?) {
        currentTitle = title
        titleView.text = title
        chipBox.removeAllViews()
        chips.clear()

        val items = ArrayList<Pair<String, Int>>()
        items.add(Pair("أفضل جودة", 0))
        for (h in heights) items.add(Pair("${h}p", h))
        items.add(Pair("🎵 MP3 صوت", -1))
        items.add(Pair("🎵 M4A صوت", -2))

        selectedQ = 0
        var i = 0
        while (i < items.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            var j = i
            while (j < minOf(i + 2, items.size)) {
                row.addView(chip(items[j].first, items[j].second))
                j++
            }
            chipBox.addView(row)
            i += 2
        }
        refreshChips()

        val tu = thumbUrl ?: ""
        if (tu.isEmpty()) {
            thumb.visibility = View.GONE
        } else {
            thumb.visibility = View.VISIBLE
            thumb.setImageDrawable(null)
            thread {
                try {
                    val bmp = URL(tu).openStream().use { BitmapFactory.decodeStream(it) }
                    runOnUiThread { if (bmp != null) thumb.setImageBitmap(bmp) }
                } catch (e: Exception) { }
            }
        }
        infoCard.visibility = View.VISIBLE
    }

    private fun startDownload() {
        if (currentUrl.isEmpty()) return
        val i = Intent(this, DownloadService::class.java)
            .putExtra("url", currentUrl)
            .putExtra("q", selectedQ)
            .putExtra("pl", currentPlaylist)
            .putExtra("title", currentTitle)
        startForegroundService(i)
        Toast.makeText(this, "انضاف للتنزيل ✅ تكدر تغلق التطبيق", Toast.LENGTH_SHORT).show()
        gen += 1
        lastUrl = ""
        input.setText("")
        infoCard.visibility = View.GONE
        analyzeMsg.visibility = View.GONE
    }

    private fun refreshHistory() {
        histList.removeAllViews()
        val list = Store.all(this)
        if (list.isEmpty()) {
            val empty = TextView(this)
            empty.text = "ماكو تنزيلات بعد"
            empty.setTextColor(GRAY)
            empty.setPadding(dp(8), dp(16), dp(8), dp(16))
            histList.addView(empty)
            return
        }
        val fmt = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.getDefault())
        for (o in list) {
            val name = o.optString("n")
            val uri = o.optString("u")
            val mime = o.optString("m")
            val whenText = fmt.format(Date(o.optLong("t")))
            val icon = if (mime.startsWith("audio")) "🎵" else "🎬"

            val c = card()
            val tv1 = TextView(this)
            tv1.text = "$icon  $name"
            tv1.textSize = 15f
            tv1.setTypeface(null, Typeface.BOLD)
            tv1.setTextColor(DARK)
            tv1.maxLines = 2
            val tv2 = TextView(this)
            tv2.text = whenText
            tv2.textSize = 12f
            tv2.setTextColor(GRAY)
            c.addView(tv1)
            c.addView(tv2)
            c.setOnClickListener { openFile(uri, mime) }
            histList.addView(c)
        }
    }

    private fun openFile(u: String, m: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse(u), m)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "ما كدرت أفتح الملف، يمكن انحذف", Toast.LENGTH_LONG).show()
        }
    }
}
