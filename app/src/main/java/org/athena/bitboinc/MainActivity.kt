package org.athena.bitboinc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import edu.berkeley.boinc.R
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var pal: Palette
    private lateinit var bg: SpaceBackground
    private lateinit var holder: FrameLayout
    private lateinit var navItems: List<Pair<ImageView, TextView>>
    private lateinit var screens: List<Screen>
    private lateinit var titleView: TextView
    private lateinit var dot: View
    private lateinit var refreshBtn: ImageView
    private lateinit var bgCard: Background.Card
    private var current = 0
    private var api: JSONObject? = null
    private val handler = Handler(Looper.getMainLooper())
    private val listener: (Snapshot) -> Unit = { s -> screens.forEach { sc -> try { sc.update(s) } catch (e: Throwable) { android.util.Log.e("bb", "screen update failed", e) } }; dot.background = rounded(if (s.connected && s.computing == edu.berkeley.boinc.client.ClientStatus.COMPUTING_STATUS_COMPUTING) pal.accent else pal.muted, dp(8).toFloat()) }
    private val apiTask = object : Runnable {
        override fun run() {
            val auth = getSharedPreferences("bb", MODE_PRIVATE).getString("auth", null)
            if (auth != null) Thread {
                val j = Api.fetch(auth, Locale.getDefault().language, Hub.snap?.projects?.firstOrNull()?.hostId ?: 0, edu.berkeley.boinc.BuildConfig.VERSION_NAME)
                if (j != null) handler.post { api = j; screens.forEach { it.updateApi(j) } }
            }.start()
            handler.postDelayed(this, 120000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        pal = Themes.current(this)
        window.statusBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.navigationBarColor = pal.card
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (pal.light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN else View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        val root = FrameLayout(this)
        bg = SpaceBackground(this, pal)
        root.addView(bg, ViewGroup.LayoutParams(-1, -1))
        val col = vbox()
        root.addView(col, ViewGroup.LayoutParams(-1, -1))

        val top = hbox().apply { setPadding(dp(18), dp(14), dp(18), dp(8)) }
        titleView = label(pal, "", 22f, bold = true)
        top.addView(titleView, weight())
        refreshBtn = ImageView(this).apply {
            setImageResource(R.drawable.bb_ic_refresh); setColorFilter(pal.muted); setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = getString(R.string.bb_refresh)
            setOnClickListener { refresh(true) }
        }
        top.addView(refreshBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        dot = View(this)
        top.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { leftMargin = dp(8) })
        col.addView(top, matchWrap())

        holder = FrameLayout(this)
        col.addView(holder, LinearLayout.LayoutParams(-1, 0, 1f))

        screens = listOf(
            HomeScreen(this, pal), StatsScreen(this, pal), ScienceScreen(this, pal), BadgesScreen(this, pal),
            SettingsScreen(this, pal, { id -> Themes.set(this, id); recreate() }, { logout() })
        )
        screens.forEach { s -> holder.addView(s.root, FrameLayout.LayoutParams(-1, -1)); s.onRefresh = { refresh(false) } }

        bgCard = Background.Card(this, pal)
        (screens[0] as HomeScreen).addTop(bgCard.view)

        val icons = listOf(R.drawable.bb_ic_home, R.drawable.bb_ic_chart, R.drawable.bb_ic_science, R.drawable.bb_ic_award, R.drawable.bb_ic_settings)
        val names = listOf(R.string.bb_tab_home, R.string.bb_tab_stats, R.string.bb_tab_science, R.string.bb_tab_badges, R.string.bb_tab_settings)
        val nav = hbox().apply { setBackgroundColor(pal.card); setPadding(0, dp(6), 0, dp(6)); gravity = Gravity.CENTER }
        navItems = icons.indices.map { i ->
            val cell = vbox().apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(2)); setOnClickListener { select(i) } }
            val iv = ImageView(this).apply { setImageResource(icons[i]) }
            val tv = label(pal, getString(names[i]), 10f).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0); maxLines = 1 }
            cell.addView(iv, LinearLayout.LayoutParams(dp(24), dp(24)))
            cell.addView(tv)
            nav.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            iv to tv
        }
        val line = View(this).apply { setBackgroundColor(pal.line) }
        col.addView(line, LinearLayout.LayoutParams(-1, dp(1)))
        col.addView(nav, matchWrap())
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val bars = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            col.setPadding(0, bars.top, 0, bars.bottom); ins
        }
        select(0)
        val bbPrefs = getSharedPreferences("bb", MODE_PRIVATE)
        if (!bbPrefs.getBoolean("defaults_gpu_in_use", false)) {   // 0.1.8: the GPU may also compute while the phone is in use
            Hub.ensureService(this)
            Hub.io { m ->
                m.prefs?.let { p -> p.runGpuIfUserActive = true; m.setGlobalPreferences(p) }
                bbPrefs.edit().putBoolean("defaults_gpu_in_use", true).apply()
            }
        }
        if (!bbPrefs.getBoolean("defaults_while_in_use", false)) {
            Hub.ensureService(this)
            Hub.io { m ->
                m.setSuspendWhenScreenOn(false)
                m.prefs?.let { p -> p.runIfUserActive = true; m.setGlobalPreferences(p) }
                bbPrefs.edit().putBoolean("defaults_while_in_use", true).apply()
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    /** Manual refresh: ask the engine for a fresh status and the website for fresh numbers. */
    private fun refresh(spin: Boolean) {
        Hub.io {
            it.forceRefresh()
            // ask the project for news and new work right now instead of waiting for BOINC's own timer
            it.projectOp(edu.berkeley.boinc.rpc.RpcClient.PROJECT_UPDATE, PROJECT_URL)
        }
        handler.removeCallbacks(apiTask); handler.post(apiTask)
        if (spin) refreshBtn.animate().rotationBy(360f).setDuration(600).start()
        handler.postDelayed({
            screens.forEach { it.refresher.isRefreshing = false }
            android.widget.Toast.makeText(this, R.string.bb_refreshed, android.widget.Toast.LENGTH_SHORT).show()
        }, 900)
    }

    private fun select(i: Int) {
        current = i
        screens.forEachIndexed { k, s -> s.root.visibility = if (k == i) View.VISIBLE else View.GONE }
        navItems.forEachIndexed { k, (iv, tv) ->
            val c = if (k == i) pal.accent else pal.muted
            iv.setColorFilter(c); tv.setTextColor(c)
        }
        titleView.text = listOf(R.string.bb_title_home, R.string.bb_title_stats, R.string.bb_title_science, R.string.bb_title_badges, R.string.bb_title_settings).let { getString(it[i]) }
    }

    override fun onResume() {
        super.onResume()
        bgCard.refresh()
    }

    override fun onStart() {
        super.onStart()
        Hub.listen(this, listener)
        handler.post(apiTask)
    }

    override fun onStop() {
        Hub.unlisten(listener)
        handler.removeCallbacks(apiTask)
        super.onStop()
    }

    private fun logout() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(R.string.bb_logout_confirm)
            .setPositiveButton(R.string.bb_logout) { _, _ ->
                Hub.io { m -> m.projectOp(edu.berkeley.boinc.rpc.RpcClient.PROJECT_DETACH, PROJECT_URL) }
                getSharedPreferences("bb", Context.MODE_PRIVATE).edit().remove("auth").remove("email").apply()
                startActivity(Intent(this, WelcomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
}
