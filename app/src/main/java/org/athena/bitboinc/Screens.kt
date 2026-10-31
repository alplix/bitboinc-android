package org.athena.bitboinc

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.format.DateUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import edu.berkeley.boinc.R
import edu.berkeley.boinc.rpc.Result
import edu.berkeley.boinc.utils.RUN_MODE_AUTO
import edu.berkeley.boinc.utils.RUN_MODE_NEVER
import org.json.JSONObject
import java.net.URL
import java.util.Locale

abstract class Screen(val ctx: Context, val p: Palette) {
    protected val content = ctx.vbox().apply { setPadding(ctx.dp(14), ctx.dp(4), ctx.dp(14), ctx.dp(16)) }
    private val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(content, ViewGroup.LayoutParams(-1, -2)) }
    var onRefresh: (() -> Unit)? = null
    val refresher = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(ctx).apply {
        addView(scroll, ViewGroup.LayoutParams(-1, -1))
        setColorSchemeColors(p.accent)
        setProgressBackgroundColorSchemeColor(p.card)
        setOnRefreshListener { onRefresh?.invoke() }
    }
    val root: View get() = refresher
    abstract fun update(s: Snapshot)
    open fun updateApi(j: JSONObject?) {}
    protected fun s(id: Int) = ctx.getString(id)
    protected fun s(id: Int, vararg a: Any) = ctx.getString(id, *a)
    protected fun title(t: String) = ctx.label(p, t, 12f, muted = true).also { it.setPadding(ctx.dp(4), ctx.dp(8), 0, ctx.dp(6)) }
    protected fun clear() { content.removeAllViews() }
    protected fun rowKV(k: String, v: String): View = ctx.hbox().apply {
        addView(ctx.label(p, k, 13f, muted = true), weight())
        addView(ctx.label(p, v, 13f))
        setPadding(0, ctx.dp(3), 0, ctx.dp(3))
    }
    protected fun button(text: String, accent: Boolean = true, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(if (accent) (if (p.light) Color.WHITE else Color.BLACK) else p.text)
        background = if (accent) rounded(p.accent, ctx.dp(12).toFloat()) else rounded(0, ctx.dp(12).toFloat(), p.line, ctx.dp(1))
        setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
        setOnClickListener { onClick() }
    }
    protected fun switch(on: Boolean, onChange: (Boolean) -> Unit) = Switch(ctx).apply {
        isChecked = on
        val cs = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.accent, p.muted))
        thumbTintList = cs
        trackTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf((p.accent and 0xFFFFFF) or 0x66000000, p.line))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
}

fun fmtDur(c: Context, secs: Double): String {
    val t = secs.toLong().coerceAtLeast(0)
    val h = t / 3600; val m = (t % 3600) / 60
    return if (h > 0) c.getString(R.string.bb_hm, h, m) else c.getString(R.string.bb_m, maxOf(m, if (t > 0) 1 else 0))
}

fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", b / 1073741824.0)
    b >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", b / 1048576.0)
    else -> String.format(Locale.US, "%.0f KB", b / 1024.0)
}

fun fmtNum(v: Double): String = String.format(Locale.getDefault(), "%,.0f", v)

/** Badge label in the app's language when we have it (bb_metric_<key>), else the server's English text. */
fun metricLabel(c: Context, key: String, fallback: String): String {
    val id = c.resources.getIdentifier("bb_metric_$key", "string", c.packageName)
    return if (id != 0) c.getString(id) else fallback
}


/** 0 running, 1 paused, 2 waiting to run, 3 downloading, 4 uploading, 5 ready to report, 6 failed. */
fun taskRank(t: Result): Int = when {
    t.state == edu.berkeley.boinc.utils.RESULT_COMPUTE_ERROR || t.state == edu.berkeley.boinc.utils.RESULT_UPLOAD_FAILED || t.state == edu.berkeley.boinc.utils.RESULT_ABORTED -> 6
    t.state == edu.berkeley.boinc.utils.RESULT_FILES_UPLOADING -> 4
    t.state == edu.berkeley.boinc.utils.RESULT_FILES_UPLOADED -> 5
    t.state < edu.berkeley.boinc.utils.RESULT_FILES_DOWNLOADED -> 3
    t.isActiveTask && t.activeTaskState == edu.berkeley.boinc.utils.PROCESS_EXECUTING -> 0
    t.isSuspendedViaGUI || t.isProjectSuspendedViaGUI || t.activeTaskState == edu.berkeley.boinc.utils.PROCESS_SUSPENDED -> 1
    else -> 2
}

fun taskLabel(rank: Int): Int = when (rank) {
    0 -> R.string.bb_task_running; 1 -> R.string.bb_task_paused; 2 -> R.string.bb_task_waiting; 3 -> R.string.bb_task_downloading
    4 -> R.string.bb_task_uploading; 5 -> R.string.bb_task_ready; else -> R.string.bb_task_failed
}

val appOrder = listOf("phygas", "nostos", "kampylos", "keraunos", "potamos", "sphinx")

fun appName(task: Result): String {
    val n = task.app?.userFriendlyName?.takeIf { it.isNotBlank() } ?: task.app?.name ?: task.name
    return n.replaceFirstChar { it.uppercase() }
}

// ----------------------------------------------------------------------------------------- Home

class HomeScreen(ctx: Context, p: Palette) : Screen(ctx, p) {
    private val ring = RingView(ctx, p)
    private val status = ctx.label(p, "", 14f)
    private val desc = ctx.label(p, "", 12f, muted = true)
    private val today = ctx.label(p, "", 13f, muted = true)
    private val credit = ctx.label(p, "-", 20f, bold = true)
    private val rac = ctx.label(p, "-", 20f, bold = true)
    private val taskBox = ctx.vbox()
    private val newsBox = ctx.vbox()
    private val nextBadge = ctx.vbox()
    private var sw: Switch? = null
    private var suppress = false
    private var lastTaskKey = ""

    init {
        val hero = ctx.card(p).apply { gravity = Gravity.CENTER_HORIZONTAL }
        hero.addView(ring, android.widget.LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        hero.addView(status.apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(8), 0, ctx.dp(2)) }, matchWrap())
        hero.addView(desc.apply { gravity = Gravity.CENTER }, matchWrap())
        hero.addView(today.apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(8), 0, 0) }, matchWrap())
        content.addView(hero)

        val row = ctx.hbox()
        val c1 = ctx.card(p).apply { addView(ctx.label(p, s(R.string.bb_credit), 12f, muted = true)); addView(credit) }
        val c2 = ctx.card(p).apply { addView(ctx.label(p, s(R.string.bb_rac), 12f, muted = true)); addView(rac) }
        row.addView(c1, weightFill().apply { rightMargin = ctx.dp(5) }); row.addView(c2, weightFill().apply { leftMargin = ctx.dp(5) })
        content.addView(row, matchWrap())

        val comp = ctx.card(p)
        val line = ctx.hbox()
        line.addView(ctx.label(p, s(R.string.bb_compute), 15f, bold = true), weight())
        sw = switch(true) { on ->
            if (!suppress) Hub.io { m -> m.setRunMode(if (on) RUN_MODE_AUTO else RUN_MODE_NEVER); m.setNetworkMode(if (on) RUN_MODE_AUTO else RUN_MODE_NEVER) }
        }
        line.addView(sw)
        comp.addView(line, matchWrap())
        content.addView(comp)

        content.addView(title(s(R.string.bb_next_badge)))
        content.addView(ctx.card(p).apply { addView(nextBadge) })
        content.addView(title(s(R.string.bb_tasks_all)))
        content.addView(ctx.card(p).apply { addView(taskBox) })
        content.addView(title(s(R.string.bb_news)))
        content.addView(ctx.card(p).apply { addView(newsBox) })
    }

    fun addTop(v: View) { content.addView(v, 0) }

    override fun update(s: Snapshot) {
        val running = s.tasks.filter { it.isActiveTask && it.activeTaskState == edu.berkeley.boinc.utils.PROCESS_EXECUTING }
        ring.fraction = if (running.isEmpty()) 0f else running.map { it.fractionDone }.average().toFloat()
        ring.big = running.size.toString()
        ring.small = s(R.string.bb_running)
        status.text = if (s.connected) s.title else s(R.string.bb_starting)
        desc.text = s.desc
        val secs = Daily.seconds(ctx, 0)
        today.text = s(R.string.bb_today, fmtDur(ctx, secs.toDouble()))
        val prj = s.projects.firstOrNull()
        credit.text = prj?.let { fmtNum(it.userTotalCredit) } ?: "-"
        rac.text = prj?.let { fmtNum(it.userExpAvgCredit) } ?: "-"
        suppress = true; sw?.isChecked = s.computing != edu.berkeley.boinc.client.ClientStatus.COMPUTING_STATUS_NEVER; suppress = false

        val ordered = s.tasks.sortedWith(compareBy<Result>({ taskRank(it) }, { it.reportDeadline })).take(40)
        val key = ordered.joinToString { it.name + taskRank(it) + (it.fractionDone * 100).toInt() }
        if (key != lastTaskKey) {
            lastTaskKey = key
            taskBox.removeAllViews()
            if (ordered.isEmpty()) taskBox.addView(ctx.label(p, s(R.string.bb_no_tasks), 13f, muted = true))
            ordered.forEach { t ->
                val rank = taskRank(t)
                val r = ctx.vbox().apply { setPadding(0, ctx.dp(5), 0, ctx.dp(7)) }
                val l = ctx.hbox()
                l.addView(AppIconView(ctx, p, scienceId(appName(t))), android.widget.LinearLayout.LayoutParams(ctx.dp(32), ctx.dp(32)).apply { rightMargin = ctx.dp(10) })
                l.addView(ctx.label(p, appName(t), 14f, bold = true), weight())
                l.addView(ctx.label(p, s(taskLabel(rank)), 11f, color = if (rank == 0) p.accent else p.muted, bold = rank == 0))
                r.addView(l, matchWrap())
                if (rank <= 1) {   // running or paused: progress
                    r.addView(BarView(ctx, p).also { it.value = t.fractionDone }, matchWrap().apply { topMargin = ctx.dp(6) })
                    r.addView(ctx.label(p, "${(t.fractionDone * 100).toInt()}% \u00b7 " + fmtDur(ctx, t.estimatedCPUTimeRemaining), 11f, muted = true).also { it.setPadding(0, ctx.dp(3), 0, 0) })
                } else if (rank == 2 && t.reportDeadline > 0) {   // waiting: when it is due
                    r.addView(ctx.label(p, s(R.string.bb_due, DateUtils.getRelativeTimeSpanString(t.reportDeadline.toLong() * 1000).toString()), 11f, muted = true).also { it.setPadding(ctx.dp(42), ctx.dp(2), 0, 0) })
                }
                taskBox.addView(r, matchWrap())
            }
        }
        newsBox.removeAllViews()
        val notices = s.notices.sortedByDescending { it.createTime }.take(3)
        if (notices.isEmpty()) newsBox.addView(ctx.label(p, s(R.string.bb_no_news), 13f, muted = true))
        notices.forEach { n ->
            val v = ctx.vbox().apply { setPadding(0, ctx.dp(4), 0, ctx.dp(6)) }
            v.addView(ctx.label(p, n.title.ifBlank { n.projectName }, 14f, bold = true))
            v.addView(ctx.label(p, DateUtils.getRelativeTimeSpanString((n.createTime * 1000).toLong()).toString(), 11f, muted = true).also { it.setPadding(0, ctx.dp(2), 0, 0) })
            if (n.link.isNotBlank()) v.setOnClickListener { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            newsBox.addView(v, matchWrap())
        }
    }

    override fun updateApi(j: JSONObject?) {
        nextBadge.removeAllViews()
        val arr = j?.optJSONArray("badges")
        var best: JSONObject? = null; var bestFrac = -1.0
        if (arr != null) for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            val next = b.optDouble("next", 0.0)
            if (next <= 0) continue
            val prev = b.optDouble("prev", 0.0)
            val f = (b.optDouble("count") - prev) / (next - prev)
            if (f < 1 && f > bestFrac) { bestFrac = f; best = b }
        }
        if (best == null) { nextBadge.addView(ctx.label(p, s(R.string.bb_offline), 13f, muted = true)); return }
        val l = ctx.hbox()
        l.addView(ctx.label(p, metricLabel(ctx, best.optString("metric"), best.optString("label")), 14f, bold = true), weight())
        l.addView(ctx.label(p, "${fmtNum(best.optDouble("count"))} / ${fmtNum(best.optDouble("next"))}", 12f, muted = true))
        nextBadge.addView(l, matchWrap())
        nextBadge.addView(BarView(ctx, p).also { it.value = bestFrac.toFloat() }, matchWrap().apply { topMargin = ctx.dp(6) })
    }
}

// ------------------------------------------------------------------------------------- Statistics

class StatsScreen(ctx: Context, p: Palette) : Screen(ctx, p) {
    private val cpuV = ctx.label(p, "-", 22f, bold = true); private val cpuSub = ctx.label(p, "", 12f, muted = true)
    private val cpuSpark = SparkView(ctx, p.accent)
    private val gpuV = ctx.label(p, "-", 22f, bold = true); private val gpuSub = ctx.label(p, "", 12f, muted = true)
    private val gpuSpark = SparkView(ctx, p.accent2)
    private val memV = ctx.label(p, "-", 18f, bold = true); private val memBar = BarView(ctx, p); private val memSub = ctx.label(p, "", 12f, muted = true)
    private val tempV = ctx.label(p, "-", 18f, bold = true); private val tempSub = ctx.label(p, "", 12f, muted = true)
    private val battV = ctx.label(p, "-", 18f, bold = true); private val battSub = ctx.label(p, "", 12f, muted = true)
    private val netV = ctx.label(p, "-", 18f, bold = true); private val netSub = ctx.label(p, "", 12f, muted = true)
    private val weekBars = BarsView(ctx, p)
    private val hostBox = ctx.vbox()

    init {
        content.addView(ctx.card(p).apply {
            val h = ctx.hbox(); h.addView(ctx.label(p, s(R.string.bb_cpu), 13f, muted = true), weight()); h.addView(cpuV); addView(h, matchWrap())
            addView(cpuSpark, matchWrap().apply { topMargin = ctx.dp(6) }); addView(cpuSub.apply { setPadding(0, ctx.dp(6), 0, 0) })
        })
        content.addView(ctx.card(p).apply {
            val h = ctx.hbox(); h.addView(ctx.label(p, s(R.string.bb_gpu), 13f, muted = true), weight()); h.addView(gpuV); addView(h, matchWrap())
            addView(gpuSpark, matchWrap().apply { topMargin = ctx.dp(6) }); addView(gpuSub.apply { setPadding(0, ctx.dp(4), 0, 0) })
        })
        val r1 = ctx.hbox()
        r1.addView(ctx.card(p).apply { addView(ctx.label(p, s(R.string.bb_memory), 12f, muted = true)); addView(memV); addView(memBar, matchWrap().apply { topMargin = ctx.dp(6); bottomMargin = ctx.dp(4) }); addView(memSub) }, weightFill().apply { rightMargin = ctx.dp(5) })
        r1.addView(ctx.card(p).apply { addView(ctx.label(p, s(R.string.bb_temp), 12f, muted = true)); addView(tempV); addView(tempSub.apply { setPadding(0, ctx.dp(6), 0, 0) }) }, weightFill().apply { leftMargin = ctx.dp(5) })
        content.addView(r1, matchWrap())
        val r2 = ctx.hbox()
        r2.addView(ctx.card(p).apply { addView(ctx.label(p, s(R.string.bb_battery), 12f, muted = true)); addView(battV); addView(battSub.apply { setPadding(0, ctx.dp(6), 0, 0) }) }, weightFill().apply { rightMargin = ctx.dp(5) })
        r2.addView(ctx.card(p).apply { addView(ctx.label(p, s(R.string.bb_network), 12f, muted = true)); addView(netV); addView(netSub.apply { setPadding(0, ctx.dp(6), 0, 0) }) }, weightFill().apply { leftMargin = ctx.dp(5) })
        content.addView(r2, matchWrap())
        content.addView(ctx.card(p).apply { addView(ctx.label(p, s(R.string.bb_week), 12f, muted = true)); addView(weekBars, matchWrap().apply { topMargin = ctx.dp(8) }) })
        content.addView(title(s(R.string.bb_device)))
        content.addView(ctx.card(p).apply { addView(hostBox) })
    }

    override fun update(s: Snapshot) {
        val y = s.sys ?: return
        cpuV.text = String.format(Locale.getDefault(), "%.0f%%", y.cpuPct)
        cpuSpark.data = y.cpuHist
        cpuSub.text = s(R.string.bb_cpu_sub, y.cores)
        if (y.gpuPct >= 0) {
            gpuV.text = String.format(Locale.getDefault(), "%.0f%%", y.gpuPct); gpuSpark.data = y.gpuHist; gpuSpark.visibility = View.VISIBLE
            gpuSub.text = ""
        } else { gpuV.text = "-"; gpuSpark.visibility = View.GONE; gpuSub.text = s(R.string.bb_gpu_na) }
        val used = y.memTotal - y.memAvail
        memV.text = fmtBytes(y.boincRss)
        memBar.value = used.toFloat() / y.memTotal
        memSub.text = s(R.string.bb_mem_sub, fmtBytes(used), fmtBytes(y.memTotal))
        tempV.text = String.format(Locale.getDefault(), "%.1f °C", y.battTempC)
        tempSub.text = when (y.thermal) { 0 -> s(R.string.bb_thermal_ok); 1 -> s(R.string.bb_thermal_light); else -> s(R.string.bb_thermal_hot) }
        battV.text = if (y.battPct >= 0) "${y.battPct}%" else "-"
        battSub.text = if (y.charging) s(R.string.bb_charging) else s(R.string.bb_on_battery)
        netV.text = fmtBytes(y.rxBps) + "/s"
        netSub.text = s(R.string.bb_net_sub, fmtBytes(y.txBps))
        weekBars.data = Daily.lastWeek(ctx)
        hostBox.removeAllViews()
        s.host?.let { h ->
            hostBox.addView(rowKV(s(R.string.bb_model), "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}"))
            hostBox.addView(rowKV(s(R.string.bb_os), "Android ${android.os.Build.VERSION.RELEASE}"))
            hostBox.addView(rowKV(s(R.string.bb_cores), h.noOfCPUs.toString()))
            hostBox.addView(rowKV(s(R.string.bb_ram), fmtBytes(h.memoryInBytes.toLong())))
            hostBox.addView(rowKV(s(R.string.bb_disk_free), fmtBytes(h.freeDiskSpace.toLong())))
        }
    }
}

// ----------------------------------------------------------------------------------------- Science

class ScienceScreen(ctx: Context, p: Palette) : Screen(ctx, p) {
    private val names = mapOf("phygas" to R.string.bb_app_phygas, "nostos" to R.string.bb_app_nostos, "kampylos" to R.string.bb_app_kampylos,
        "keraunos" to R.string.bb_app_keraunos, "potamos" to R.string.bb_app_potamos, "sphinx" to R.string.bb_app_sphinx)
    private val blurbs = mapOf("phygas" to R.string.bb_blurb_phygas, "nostos" to R.string.bb_blurb_nostos, "kampylos" to R.string.bb_blurb_kampylos,
        "keraunos" to R.string.bb_blurb_keraunos, "potamos" to R.string.bb_blurb_potamos, "sphinx" to R.string.bb_blurb_sphinx)
    private var api: JSONObject? = null
    private var snap: Snapshot? = null

    override fun update(s: Snapshot) { if (snap == null) { snap = s; render() } else snap = s }
    override fun updateApi(j: JSONObject?) { api = j; render() }

    private fun render() {
        clear()
        content.addView(ctx.label(p, s(R.string.bb_science_intro), 13f, muted = true).also { it.setPadding(ctx.dp(4), ctx.dp(6), ctx.dp(4), ctx.dp(10)) })
        val byId = HashMap<String, JSONObject>()
        api?.optJSONArray("apps")?.let { for (i in 0 until it.length()) byId[it.getJSONObject(i).optString("id")] = it.getJSONObject(i) }
        for (id in appOrder) {
            val a = byId[id]
            val c = ctx.card(p)
            val head = ctx.hbox()
            head.addView(AppIconView(ctx, p, id), android.widget.LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)).apply { rightMargin = ctx.dp(12) })
            val tv = ctx.vbox()
            tv.addView(ctx.label(p, s(names[id]!!), 16f, bold = true))
            tv.addView(ctx.label(p, s(blurbs[id]!!), 12f, muted = true).also { it.setPadding(0, ctx.dp(2), 0, 0) })
            head.addView(tv, weight())
            c.addView(head, matchWrap())
            if (a != null) {
                val prog = a.optDouble("progress", -1.0)
                if (prog >= 0) {
                    c.addView(BarView(ctx, p, p.accent2).also { it.value = prog.toFloat() }, matchWrap().apply { topMargin = ctx.dp(10) })
                    c.addView(ctx.label(p, s(R.string.bb_project_progress, String.format(Locale.getDefault(), "%.1f", prog * 100)), 11f, muted = true).also { it.setPadding(0, ctx.dp(4), 0, 0) })
                }
                c.addView(ctx.label(p, s(R.string.bb_my_results, a.optLong("mine")), 12f).also { it.setPadding(0, ctx.dp(6), 0, 0) })
                if (!a.optBoolean("android", true)) c.addView(ctx.label(p, s(R.string.bb_android_soon), 11f, color = p.accent).also { it.setPadding(0, ctx.dp(4), 0, 0) })
                val url = a.optString("url")
                if (url.isNotBlank()) c.setOnClickListener { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            content.addView(c)
        }
    }
}

// ----------------------------------------------------------------------------------------- Badges

object Pics {
    private val cache = LruCache<String, Bitmap>(40)
    fun load(url: String, into: ImageView) {
        cache.get(url)?.let { into.setImageBitmap(it); return }
        into.tag = url
        Thread {
            try {
                val bm = BitmapFactory.decodeStream(URL(url).openStream())
                if (bm != null) { cache.put(url, bm); into.post { if (into.tag == url) into.setImageBitmap(bm) } }
            } catch (e: Exception) { }
        }.start()
    }
}

class BadgesScreen(ctx: Context, p: Palette) : Screen(ctx, p) {
    private var lastKey = ""
    init { content.addView(ctx.label(p, s(R.string.bb_offline), 13f, muted = true)) }
    override fun update(s: Snapshot) {}
    override fun updateApi(j: JSONObject?) {
        val arr = j?.optJSONArray("badges") ?: return
        val key = arr.toString()
        if (key == lastKey) return
        lastKey = key
        clear()
        val earned = (0 until arr.length()).sumOf { arr.getJSONObject(it).optInt("tier") }
        content.addView(ctx.label(p, s(R.string.bb_badges_count, earned, arr.length() * 10), 13f, muted = true).also { it.setPadding(ctx.dp(4), ctx.dp(6), ctx.dp(4), ctx.dp(10)) })
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            val tier = b.optInt("tier")
            val c = ctx.card(p)
            val h = ctx.hbox()
            val img = ImageView(ctx).apply { alpha = if (tier > 0) 1f else 0.28f }
            h.addView(img, android.widget.LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56)).apply { rightMargin = ctx.dp(12) })
            b.optString("img").takeIf { it.isNotBlank() }?.let { Pics.load(it, img) }
            val v = ctx.vbox()
            v.addView(ctx.label(p, metricLabel(ctx, b.optString("metric"), b.optString("label")), 14f, bold = true))
            v.addView(ctx.label(p, if (tier > 0) b.optString("tier_name") else s(R.string.bb_not_yet), 12f, color = if (tier > 0) p.accent else p.muted))
            val next = b.optDouble("next", 0.0)
            if (next > 0) {
                val prev = b.optDouble("prev", 0.0)
                v.addView(BarView(ctx, p).also { it.value = ((b.optDouble("count") - prev) / (next - prev)).toFloat() }, matchWrap().apply { topMargin = ctx.dp(8) })
                v.addView(ctx.label(p, "${fmtNum(b.optDouble("count"))} / ${fmtNum(next)}", 11f, muted = true).also { it.setPadding(0, ctx.dp(3), 0, 0) })
            } else v.addView(ctx.label(p, s(R.string.bb_max_tier), 11f, muted = true).also { it.setPadding(0, ctx.dp(6), 0, 0) })
            h.addView(v, weight())
            c.addView(h, matchWrap())
            content.addView(c)
        }
    }
}

// ----------------------------------------------------------------------------------------- Settings

class SettingsScreen(ctx: Context, p: Palette, private val onTheme: (String) -> Unit, private val onLogout: () -> Unit) : Screen(ctx, p) {
    private var built = false
    private var update: JSONObject? = null

    override fun update(s: Snapshot) {
        if (built || s.prefs == null) return
        built = true
        build(s)
    }

    private fun setPref(change: (edu.berkeley.boinc.rpc.GlobalPreferences) -> Unit) {
        val cur = Hub.snap?.prefs ?: return
        val copy = cur.copy(); change(copy)
        Hub.io { it.setGlobalPreferences(copy) }
    }

    private fun slider(label: String, value: Int, max: Int, unit: String, onSet: (Int) -> Unit): View {
        val box = ctx.vbox().apply { setPadding(0, ctx.dp(6), 0, ctx.dp(2)) }
        val head = ctx.hbox()
        val vv = ctx.label(p, "$value$unit", 13f, color = p.accent)
        head.addView(ctx.label(p, label, 13f), weight()); head.addView(vv)
        box.addView(head, matchWrap())
        val sb = SeekBar(ctx).apply {
            this.max = max; progress = value
            progressTintList = android.content.res.ColorStateList.valueOf(p.accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(p.accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) { vv.text = "$v$unit" }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { onSet(progress) }
            })
        }
        box.addView(sb, matchWrap())
        return box
    }

    private fun toggle(label: String, on: Boolean, onSet: (Boolean) -> Unit): View = ctx.hbox().apply {
        setPadding(0, ctx.dp(6), 0, ctx.dp(6))
        addView(ctx.label(p, label, 14f), weight()); addView(switch(on, onSet))
    }

    private fun build(s: Snapshot) {
        clear()
        val pr = s.prefs!!
        content.addView(title(s(R.string.bb_theme)))
        val tc = ctx.card(p)
        val row = ctx.hbox()
        for (t in Themes.all) {
            val sel = t.id == p.id
            val cell = ctx.vbox().apply { gravity = Gravity.CENTER; setPadding(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2)) }
            val dot = View(ctx).apply { background = rounded(t.bg, ctx.dp(18).toFloat(), if (sel) p.accent else t.accent, ctx.dp(if (sel) 3 else 2)) }
            val inner = View(ctx).apply { background = rounded(t.accent, ctx.dp(5).toFloat()) }
            val fl = android.widget.FrameLayout(ctx)
            fl.addView(dot, android.widget.FrameLayout.LayoutParams(ctx.dp(36), ctx.dp(36)))
            fl.addView(inner, android.widget.FrameLayout.LayoutParams(ctx.dp(10), ctx.dp(10), Gravity.CENTER))
            cell.addView(fl, android.widget.LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)))
            cell.addView(ctx.label(p, s(t.nameRes), 10f, muted = !sel, color = if (sel) p.accent else p.muted).also { it.setPadding(0, ctx.dp(4), 0, 0); it.gravity = Gravity.CENTER })
            cell.setOnClickListener { onTheme(t.id) }
            row.addView(cell, weight())
        }
        tc.addView(row, matchWrap())
        content.addView(tc)

        content.addView(title(s(R.string.bb_rules)))
        val rc = ctx.card(p)
        rc.addView(toggle(s(R.string.bb_only_charging), !pr.runOnBatteryPower) { v -> setPref { it.runOnBatteryPower = !v } })
        rc.addView(toggle(s(R.string.bb_wifi_only), pr.networkWiFiOnly) { v -> setPref { it.networkWiFiOnly = v } })
        val whileInUse = pr.runIfUserActive
        rc.addView(toggle(s(R.string.bb_when_in_use), whileInUse) { v ->
            setPref { it.runIfUserActive = v; it.runGpuIfUserActive = v }
            Hub.io { m -> m.setSuspendWhenScreenOn(!v) }
        })
        rc.addView(toggle(s(R.string.bb_use_gpu), ctx.getSharedPreferences("bb", Context.MODE_PRIVATE).getBoolean("use_gpu", true)) { v ->
            ctx.getSharedPreferences("bb", Context.MODE_PRIVATE).edit().putBoolean("use_gpu", v).apply()
            Hub.io { m -> m.setGpuMode(if (v) RUN_MODE_AUTO else RUN_MODE_NEVER) }
        })
        rc.addView(ctx.label(p, s(R.string.bb_gpu_hint), 11f, muted = true).also { it.setPadding(0, 0, 0, ctx.dp(4)) })
        rc.addView(slider(s(R.string.bb_cpu_cores), pr.maxNoOfCPUsPct.toInt().coerceIn(1, 100), 100, "%") { v -> setPref { it.maxNoOfCPUsPct = v.coerceAtLeast(1).toDouble() } })
        rc.addView(slider(s(R.string.bb_cpu_limit), pr.cpuUsageLimit.toInt().coerceIn(1, 100), 100, "%") { v -> setPref { it.cpuUsageLimit = v.coerceAtLeast(1).toDouble() } })
        rc.addView(slider(s(R.string.bb_min_battery), pr.batteryChargeMinPct.toInt().coerceIn(0, 100), 100, "%") { v -> setPref { it.batteryChargeMinPct = v.toDouble() } })
        rc.addView(slider(s(R.string.bb_max_temp), pr.batteryMaxTemperature.toInt().coerceIn(30, 60), 60, " °C") { v -> setPref { it.batteryMaxTemperature = v.coerceAtLeast(30).toDouble() } })
        content.addView(rc)

        content.addView(title(s(R.string.bb_account)))
        val ac = ctx.card(p)
        val prj = s.projects.firstOrNull()
        ac.addView(rowKV(s(R.string.bb_user), prj?.userName ?: ctx.getSharedPreferences("bb", Context.MODE_PRIVATE).getString("email", "-") ?: "-"))
        ac.addView(button(s(R.string.bb_open_site), false) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL + "home.php")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, matchWrap().apply { topMargin = ctx.dp(8) })
        ac.addView(button(s(R.string.bb_logout), false) { onLogout() }, matchWrap().apply { topMargin = ctx.dp(8) })
        content.addView(ac)

        content.addView(title(s(R.string.bb_about)))
        val ab = ctx.card(p)
        ab.addView(rowKV(s(R.string.bb_version), edu.berkeley.boinc.BuildConfig.VERSION_NAME).also { row ->
            // long press: share the engine's own log (GPU detection, scheduler replies) for support
            row.setOnLongClickListener {
                Hub.io { m ->
                    val sb = StringBuilder("BitBOINC " + edu.berkeley.boinc.BuildConfig.VERSION_NAME + " / Android " + android.os.Build.VERSION.RELEASE +
                        " / " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\n")
                    try { Hub.snap?.host?.let { h -> sb.append("host: " + h.domainName + " cpus=" + h.noOfCPUs + " model=" + h.cpuModel + "\n") } } catch (e: Exception) { }
                    try {
                        for (msg in m.getEventLogMessages(-1, 400)) sb.append(msg.timestamp).append(' ').append(msg.project).append(": ").append(msg.body).append('\n')
                    } catch (e: Exception) { sb.append("log unavailable: " + e + "\n") }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, sb.toString().takeLast(60000))
                        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                true
            }
        })
        ab.addView(ctx.label(p, s(R.string.bb_about_text), 12f, muted = true).also { it.setPadding(0, ctx.dp(8), 0, 0) })
        content.addView(ab)
        Thread {
            val j = Api.fetchUpdate()
            if (j != null && j.optInt("versionCode") > edu.berkeley.boinc.BuildConfig.VERSION_CODE) content.post {
                ab.addView(button(s(R.string.bb_update_available, j.optString("versionName")), true) {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(j.optString("url"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, matchWrap().apply { topMargin = ctx.dp(10) })
            }
        }.start()
    }
}
