package org.athena.bitboinc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import edu.berkeley.boinc.R
import edu.berkeley.boinc.rpc.RpcClient
import edu.berkeley.boinc.rpc.Result
import edu.berkeley.boinc.utils.PROCESS_EXECUTING
import edu.berkeley.boinc.utils.RUN_MODE_AUTO
import edu.berkeley.boinc.utils.RUN_MODE_NEVER
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** One remote BOINC client the volunteer added (classic GUI RPC: address, port 31416, password from gui_rpc_auth.cfg). */
class Device(val name: String, val host: String, val port: Int, val pw: String)

class DeviceState(val ok: Boolean, val msg: String, val running: Int, val total: Int, val avg: Float, val mode: Int, val tasks: List<Triple<String, String, Float>>)

object Devices {
    private fun prefs(c: Context) = c.getSharedPreferences("bb", Context.MODE_PRIVATE)
    fun load(c: Context): List<Device> = try {
        val a = JSONArray(prefs(c).getString("devices", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Device(o.optString("n"), o.optString("h"), o.optInt("p", 31416), o.optString("w")) }
    } catch (e: Exception) { emptyList() }
    fun save(c: Context, l: List<Device>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("n", it.name).put("h", it.host).put("p", it.port).put("w", it.pw)) }
        prefs(c).edit().putString("devices", a.toString()).apply()
    }

    /** Connect, ask, disconnect (a few KB; no long-lived sockets). Runs off the main thread. */
    fun probe(d: Device): DeviceState {
        val rpc = RpcClient()
        try {
            if (!rpc.open(d.host, d.port)) return DeviceState(false, "offline", 0, 0, 0f, -1, emptyList())
            if (!rpc.authorize(d.pw)) return DeviceState(false, "password", 0, 0, 0f, -1, emptyList())
            val st = rpc.ccStatus
            val res: List<Result> = rpc.results ?: emptyList()
            val prj = (rpc.projectStatus ?: emptyList()).associate { it.masterURL to it.projectName }
            val run = res.filter { it.isActiveTask && it.activeTaskState == PROCESS_EXECUTING }
            val shown = run.take(8).map { Triple(prj[it.projectURL] ?: "", it.name.substringBefore('_'), it.fractionDone) }
            return DeviceState(true, "", run.size, res.size, if (run.isEmpty()) 0f else run.map { it.fractionDone }.average().toFloat(), st?.taskMode ?: -1, shown)
        } catch (e: Throwable) { return DeviceState(false, "offline", 0, 0, 0f, -1, emptyList()) }
        finally { try { rpc.close() } catch (e: Throwable) { } }
    }

    /** One command on a remote device. */
    fun command(d: Device, f: (RpcClient) -> Unit) {
        val rpc = RpcClient()
        try { if (rpc.open(d.host, d.port) && rpc.authorize(d.pw)) f(rpc) } catch (e: Throwable) { } finally { try { rpc.close() } catch (e: Throwable) { } }
    }
}

class DevicesScreen(ctx: Context, p: Palette) : Screen(ctx, p) {
    private val io = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())
    private var devices = Devices.load(ctx)
    private val states = HashMap<String, DeviceState>()
    private val open = HashSet<String>()
    private var last = 0L
    private var key = ""

    private fun id(d: Device) = d.host + ":" + d.port

    override fun update(s: Snapshot) {
        val now = System.currentTimeMillis()
        if (root.visibility != View.VISIBLE) { if (key.isEmpty()) draw(); return }
        if (now - last < 6000) return
        last = now
        devices.forEach { d -> io.execute { val st = Devices.probe(d); ui.post { states[id(d)] = st; draw() } } }
        if (key.isEmpty()) draw()
    }

    private fun refreshSoon() { last = 0; Hub.snap?.let { update(it) } }

    private fun draw() {
        key = "x"
        clear()
        content.addView(ctx.label(p, ctx.getString(R.string.bb_dev_intro), 12f, muted = true).also { it.setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(8)) })
        devices.forEach { d ->
            val st = states[id(d)]
            val card = ctx.card(p)
            val head = ctx.hbox()
            val col = ctx.vbox()
            col.addView(ctx.label(p, d.name.ifBlank { d.host }, 15f, bold = true))
            col.addView(ctx.label(p, if (st == null) ctx.getString(R.string.bb_dev_checking) else when {
                st.ok -> ctx.getString(R.string.bb_dev_running, st.running, (st.avg * 100).toInt())
                st.msg == "password" -> ctx.getString(R.string.bb_dev_wrong_pw)
                else -> ctx.getString(R.string.bb_dev_offline)
            }, 12f, color = if (st?.ok == true) p.accent else p.muted))
            head.addView(col, weight())
            head.addView(ctx.label(p, if (id(d) in open) "▲" else "▼", 14f, muted = true))
            card.addView(head, matchWrap())
            card.setOnClickListener { if (!open.remove(id(d))) open.add(id(d)); draw() }
            if (id(d) in open) {
                st?.tasks?.forEach { (proj, name, f) ->
                    val r = ctx.vbox().apply { setPadding(0, ctx.dp(6), 0, 0) }
                    val l = ctx.hbox()
                    l.addView(ctx.label(p, (proj.ifBlank { "?" }) + " · " + name, 12f), weight())
                    l.addView(ctx.label(p, "${(f * 100).toInt()}%", 12f, muted = true))
                    r.addView(l, matchWrap())
                    r.addView(BarView(ctx, p).also { it.value = f }, matchWrap().apply { topMargin = ctx.dp(3) })
                    card.addView(r, matchWrap())
                }
                if (st != null && st.ok) {
                    val paused = st.mode == RUN_MODE_NEVER
                    val row = ctx.hbox().apply { setPadding(0, ctx.dp(10), 0, 0) }
                    row.addView(button(ctx.getString(if (paused) R.string.bb_dev_resume else R.string.bb_dev_pause), false) {
                        io.execute { Devices.command(d) { it.setRunMode(if (paused) RUN_MODE_AUTO else RUN_MODE_NEVER, 0.0) }; ui.post { refreshSoon() } }
                    }, weight().apply { rightMargin = ctx.dp(6) })
                    row.addView(button(ctx.getString(R.string.bb_dev_update), false) {
                        io.execute { Devices.command(d) { c -> (c.projectStatus ?: emptyList()).forEach { pr -> c.projectOp(RpcClient.PROJECT_UPDATE, pr.masterURL) } }; ui.post { refreshSoon() } }
                    }, weight().apply { leftMargin = ctx.dp(6) })
                    card.addView(row, matchWrap())
                }
                card.addView(ctx.label(p, ctx.getString(R.string.bb_dev_remove), 12f, color = p.accent, bold = true).also {
                    it.setPadding(0, ctx.dp(12), 0, 0)
                    it.setOnClickListener {
                        val act = ctx as? android.app.Activity ?: return@setOnClickListener
                        androidx.appcompat.app.AlertDialog.Builder(act).setMessage(ctx.getString(R.string.bb_dev_remove_q, d.name.ifBlank { d.host }))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.bb_dev_remove) { _, _ -> devices = devices.filter { x -> id(x) != id(d) }; Devices.save(ctx, devices); draw() }.show()
                    }
                })
            }
            content.addView(card, matchWrap().apply { bottomMargin = ctx.dp(10) })
        }
        content.addView(button(ctx.getString(R.string.bb_dev_add), true) { addDialog() }, matchWrap().apply { topMargin = ctx.dp(4) })
        content.addView(ctx.label(p, ctx.getString(R.string.bb_dev_howto), 11f, muted = true).also { it.setPadding(ctx.dp(4), ctx.dp(12), ctx.dp(4), 0) })
    }

    private fun addDialog() {
        val act = ctx as? android.app.Activity ?: return
        fun field(hint: String, type: Int, text: String = "") = EditText(ctx).apply {
            this.hint = hint; setText(text); inputType = android.text.InputType.TYPE_CLASS_TEXT or type; maxLines = 1; setTextColor(p.text); setHintTextColor(p.muted)
        }
        val name = field(ctx.getString(R.string.bb_dev_name), 0)
        val host = field(ctx.getString(R.string.bb_dev_host), android.text.InputType.TYPE_TEXT_VARIATION_URI)
        val port = field(ctx.getString(R.string.bb_dev_port), 0, "31416").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val pw = field(ctx.getString(R.string.bb_dev_pw), android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val box = ctx.vbox().apply { setPadding(ctx.dp(20), ctx.dp(10), ctx.dp(20), 0) }
        listOf<View>(name, host, port, pw).forEach { box.addView(it, matchWrap()) }
        val d = androidx.appcompat.app.AlertDialog.Builder(act).setTitle(R.string.bb_dev_add).setView(box)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.bb_dev_add, null).create()
        d.setOnShowListener {
            d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val h = host.text.toString().trim(); val pt = port.text.toString().trim().toIntOrNull() ?: 31416
                if (h.isEmpty() || pt !in 1..65535) { Toast.makeText(ctx, R.string.bb_fill_all, Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                val dev = Device(name.text.toString().trim(), h, pt, pw.text.toString())
                devices = devices.filter { id(it) != id(dev) } + dev
                Devices.save(ctx, devices); d.dismiss(); draw(); refreshSoon()
            }
        }
        d.show()
    }
}
