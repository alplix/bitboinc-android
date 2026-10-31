package org.athena.bitboinc

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import edu.berkeley.boinc.client.IMonitor
import edu.berkeley.boinc.client.Monitor
import edu.berkeley.boinc.client.MonitorAsync
import edu.berkeley.boinc.rpc.GlobalPreferences
import edu.berkeley.boinc.rpc.HostInfo
import edu.berkeley.boinc.rpc.Notice
import edu.berkeley.boinc.rpc.Project
import edu.berkeley.boinc.rpc.Result
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

const val PROJECT_URL = "https://bitboinc.athena.org.tr/"

class Sys(
    val cpuPct: Float,          // share of all cores used by BitBOINC's processes
    val cores: Int,
    val boincRss: Long,         // bytes
    val memTotal: Long, val memAvail: Long,
    val gpuPct: Float,          // -1 = not readable on this device
    val battPct: Int, val charging: Boolean, val battTempC: Float, val thermal: Int,
    val rxBps: Long, val txBps: Long,
    val cpuHist: List<Float>, val gpuHist: List<Float>
)

class Snapshot(
    val connected: Boolean,
    val setup: Int, val computing: Int, val suspendReason: Int,
    val title: String, val desc: String,
    val tasks: List<Result>, val projects: List<Project>,
    val host: HostInfo?, val prefs: GlobalPreferences?, val notices: List<Notice>,
    val sys: Sys?
)

/** Binds the BOINC client service and publishes a fresh Snapshot every two seconds while someone listens. */
object Hub {
    @Volatile var monitor: MonitorAsync? = null
    @Volatile var snap: Snapshot? = null
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadScheduledExecutor()
    private var job: ScheduledFuture<*>? = null
    private val listeners = LinkedHashSet<(Snapshot) -> Unit>()
    private var bound = false
    private var gpuApplied = false
    private lateinit var app: Context

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { monitor = MonitorAsync(IMonitor.Stub.asInterface(service)) }
        override fun onServiceDisconnected(name: ComponentName?) { monitor = null }
    }

    fun ensureService(ctx: Context) {
        app = ctx.applicationContext
        val i = Intent(app, Monitor::class.java)
        app.startService(i)   // called while an activity is visible, so this is allowed; the service goes foreground by itself when computing
        if (!bound) bound = app.bindService(i, conn, Context.BIND_AUTO_CREATE)
    }

    fun listen(ctx: Context, l: (Snapshot) -> Unit) {
        ensureService(ctx)
        synchronized(listeners) { listeners.add(l) }
        snap?.let { l(it) }
        if (job == null) job = pool.scheduleWithFixedDelay({ tick() }, 0, 2, TimeUnit.SECONDS)
    }

    fun unlisten(l: (Snapshot) -> Unit) {
        synchronized(listeners) { listeners.remove(l); if (listeners.isEmpty()) { job?.cancel(false); job = null } }
    }

    private fun tick() {
        try {
            val s = build()
            snap = s
            main.post { synchronized(listeners) { listeners.toList() }.forEach { it(s) } }
        } catch (e: Exception) { android.util.Log.e("bb", "snapshot failed", e) }   // keep the last snapshot, try again in two seconds
    }

    /** One failing engine call (or an oversized reply) must not leave every screen empty. */
    private fun <T> safe(what: String, dflt: T, f: () -> T?): T = try { f() ?: dflt } catch (e: Throwable) { android.util.Log.e("bb", "$what failed", e); dflt }

    private fun build(): Snapshot {
        val sys = SysStats.sample(app)
        val m = monitor ?: return Snapshot(false, 0, 0, 0, "", "", emptyList(), emptyList(), null, null, emptyList(), sys)
        if (!gpuApplied) {
            gpuApplied = true
            val use = app.getSharedPreferences("bb", Context.MODE_PRIVATE).getBoolean("use_gpu", true)
            m.setGpuMode(if (use) edu.berkeley.boinc.utils.RUN_MODE_AUTO else edu.berkeley.boinc.utils.RUN_MODE_NEVER)
        }
        val computing = safe("computingStatus", 0) { m.computingStatus }
        return Snapshot(
            true, safe("setupStatus", 0) { m.setupStatus }, computing, safe("suspendReason", 0) { m.computingSuspendReason },
            safe("title", "") { m.currentStatusTitle }, safe("description", "") { m.currentStatusDescription },
            safe("tasks", emptyList<Result>()) {
                // the engine API splits tasks by "has an active task": running (or preempted) ones come with true, all the others with false
                safe("activeTasks", emptyList<Result>()) { m.getTasks(0, 40, true) } + safe("otherTasks", emptyList<Result>()) { m.getTasks(0, 40, false) }
            }, safe("projects", emptyList<Project>()) { m.projects },
            safe<HostInfo?>("hostInfo", null) { m.hostInfo }, safe<GlobalPreferences?>("prefs", null) { m.prefs },
            safe("notices", emptyList<Notice>()) { m.rssNotices }, sys
        )
    }

    /** Run something against the monitor off the main thread. */
    fun io(block: (MonitorAsync) -> Unit) { pool.execute { monitor?.let { try { block(it) } catch (e: Exception) { } } } }
}

/** Seconds of computing per day. Written by the engine service (its own process, so hours count even when the app
 *  is closed) into a small file; the UI process only reads it. Last 14 days kept. */
object Daily {
    private val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    private fun file(c: Context) = File(c.filesDir, "daily.txt")
    fun key(offsetDays: Int = 0) = fmt.format(Date(System.currentTimeMillis() - offsetDays * 86400000L))
    private var acc = 0.0
    private var lastWrite = 0L

    /** Called by the service on every status update while computing; flushes to disk about once a minute. */
    @Synchronized fun tick(c: Context, secs: Double) {
        acc += secs
        val now = System.currentTimeMillis()
        if (now - lastWrite < 60000) return
        lastWrite = now
        val m = read(c).toMutableMap()
        val k = key()
        m[k] = (m[k] ?: 0.0) + acc
        acc = 0.0
        val keep = (0..13).map { key(it) }.toSet()
        try { file(c).writeText(m.filterKeys { it in keep }.entries.joinToString("\n") { it.key + " " + it.value.toLong() }) } catch (e: Exception) { }
    }

    private fun read(c: Context): Map<String, Double> = try {
        file(c).readLines().mapNotNull { l -> l.split(' ').takeIf { it.size == 2 }?.let { it[0] to it[1].toDouble() } }.toMap()
    } catch (e: Exception) { emptyMap() }

    fun seconds(c: Context, offsetDays: Int): Int = (read(c)[key(offsetDays)] ?: 0.0).toInt()
    fun lastWeek(c: Context): List<Float> { val m = read(c); return (6 downTo 0).map { ((m[key(it)] ?: 0.0) / 3600.0).toFloat() } }
}

object SysStats {
    private var lastTicks = -1L
    private var lastAt = 0L
    private var lastRx = -1L
    private var lastTx = 0L
    private val cpuHist = ArrayDeque<Float>()
    private val gpuHist = ArrayDeque<Float>()
    private val gpuFiles = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
        "/sys/class/misc/mali0/device/utilisation",
        "/sys/devices/platform/soc/soc:mali/utilization",
        "/sys/kernel/gpu/gpu_busy"
    )

    /** utime+stime (clock ticks) and resident bytes of every process this app can see (its own uid only). */
    private fun procs(): Pair<Long, Long> {
        var ticks = 0L; var rss = 0L
        val page = 4096L
        File("/proc").listFiles()?.forEach { d ->
            if (d.name.isNotEmpty() && d.name[0].isDigit()) {
                try {
                    val s = File(d, "stat").readText()
                    val f = s.substring(s.lastIndexOf(')') + 2).split(' ')
                    ticks += f[11].toLong() + f[12].toLong()
                    rss += f[21].toLong() * page
                } catch (e: Exception) { }
            }
        }
        return ticks to rss
    }

    private fun gpu(): Float {
        for (p in gpuFiles) {
            try {
                val t = File(p).readText().trim()
                val n = t.split(' ', '%', '\n').firstOrNull { it.isNotEmpty() }?.toFloatOrNull() ?: continue
                return n.coerceIn(0f, 100f)
            } catch (e: Exception) { }
        }
        return -1f
    }

    @Synchronized fun sample(c: Context): Sys {
        val now = System.currentTimeMillis()
        val (ticks, rss) = procs()
        val cores = Runtime.getRuntime().availableProcessors()
        var cpu = 0f
        if (lastTicks >= 0 && now > lastAt) {
            val coresUsed = (ticks - lastTicks) / 100f / ((now - lastAt) / 1000f)
            cpu = (coresUsed / cores * 100f).coerceIn(0f, 100f)
        }
        lastTicks = ticks; lastAt = now
        val g = gpu()
        cpuHist.addLast(cpu); if (cpuHist.size > 60) cpuHist.removeFirst()
        gpuHist.addLast(if (g < 0) 0f else g); if (gpuHist.size > 60) gpuHist.removeFirst()

        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)

        val b = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val st = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        var thermal = 0
        if (Build.VERSION.SDK_INT >= 29) thermal = (c.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus

        val uid = android.os.Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid); val tx = TrafficStats.getUidTxBytes(uid)
        var rxBps = 0L; var txBps = 0L
        if (lastRx >= 0 && rx >= 0) { rxBps = ((rx - lastRx) / 2).coerceAtLeast(0); txBps = ((tx - lastTx) / 2).coerceAtLeast(0) }
        lastRx = rx; lastTx = tx

        return Sys(cpu, cores, rss, mi.totalMem, mi.availMem, g,
            if (lvl >= 0) lvl * 100 / scale else -1, charging, temp, thermal, rxBps, txBps, cpuHist.toList(), gpuHist.toList())
    }
}

/** Calls the website's android_api.php (account key = what the BOINC client already holds). */
object Api {
    fun fetch(auth: String, lang: String, host: Int = 0, version: String = ""): JSONObject? = try {
        val u = java.net.URL(PROJECT_URL + "android_api.php?auth=" + java.net.URLEncoder.encode(auth, "UTF-8") + "&lang=" + lang +
            (if (host > 0) "&host=$host&v=" + java.net.URLEncoder.encode(version, "UTF-8") else ""))
        val c = u.openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()) else null
    } catch (e: Exception) { null }

    fun fetchUpdate(): JSONObject? = try {
        val c = java.net.URL(PROJECT_URL + "android/latest.json").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()) else null
    } catch (e: Exception) { null }
}
