package org.athena.bitboinc

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import androidx.core.app.NotificationManagerCompat
import edu.berkeley.boinc.R

/** Everything that keeps Android from letting a volunteer-computing app run in the background, and the screens that fix it. */
object Background {
    fun batteryOk(c: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)

    fun notificationsOk(c: Context): Boolean = NotificationManagerCompat.from(c).areNotificationsEnabled()

    /** Phones from these makers kill background apps on their own; the user has to allow auto-start in the maker's settings app. */
    private val vendorScreens: List<ComponentName> = listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
        ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"),
        ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
        ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
    )

    private fun vendorIntent(c: Context): Intent? = vendorScreens.map { Intent().setComponent(it) }
        .firstOrNull { c.packageManager.resolveActivity(it, 0) != null }

    /** True for makers whose phones are known for killing background apps. */
    fun aggressiveVendor(): Boolean = Build.MANUFACTURER.lowercase() in
        setOf("xiaomi", "redmi", "poco", "huawei", "honor", "oppo", "realme", "vivo", "iqoo", "oneplus", "samsung", "asus", "meizu", "tecno", "infinix", "itel", "letv")

    fun requestBattery(a: Activity) {
        val pkg = Uri.parse("package:" + a.packageName)
        try { a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)) }
        catch (e: Exception) {
            try { a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { appDetails(a) }
        }
    }

    fun openNotifications(a: Activity) {
        try {
            a.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName))
        } catch (e: Exception) { appDetails(a) }
    }

    fun openVendor(a: Activity) {
        val i = vendorIntent(a)
        try { if (i != null) a.startActivity(i) else appDetails(a) } catch (e: Exception) { appDetails(a) }
    }

    fun appDetails(a: Activity) {
        a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.packageName)))
    }

    private fun prefs(c: Context) = c.getSharedPreferences("bb", Context.MODE_PRIVATE)
    fun vendorDone(c: Context) = !aggressiveVendor() || prefs(c).getBoolean("vendor_done", false)
    fun setVendorDone(c: Context) = prefs(c).edit().putBoolean("vendor_done", true).apply()

    fun allOk(c: Context) = batteryOk(c) && notificationsOk(c) && vendorDone(c)

    /** The checklist card; call refresh() whenever the app comes back to the foreground. */
    class Card(private val a: Activity, private val p: Palette) {
        val view: LinearLayout = a.card(p)
        private val rows = a.vbox()
        init {
            view.addView(a.label(p, a.getString(R.string.bb_bg_title), 15f, bold = true))
            view.addView(a.label(p, a.getString(R.string.bb_bg_intro), 12f, muted = true).also { it.setPadding(0, a.dp(4), 0, a.dp(8)) })
            view.addView(rows)
            refresh()
        }

        private fun row(done: Boolean, text: Int, action: Int, onClick: () -> Unit): View = a.hbox().apply {
            setPadding(0, a.dp(6), 0, a.dp(6))
            addView(a.label(p, if (done) "✓" else "•", 16f, color = if (done) p.accent else p.muted, bold = true).also { it.setPadding(0, 0, a.dp(10), 0) })
            addView(a.label(p, a.getString(text), 13f, muted = done), weight())
            if (!done) addView(android.widget.TextView(a).apply {
                this.text = a.getString(action); textSize = 12f
                setTextColor(if (p.light) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
                background = rounded(p.accent, a.dp(10).toFloat()); setPadding(a.dp(12), a.dp(7), a.dp(12), a.dp(7))
                setOnClickListener { onClick() }
            })
        }

        fun refresh() {
            rows.removeAllViews()
            rows.addView(row(batteryOk(a), R.string.bb_bg_battery, R.string.bb_bg_allow) { requestBattery(a) })
            rows.addView(row(notificationsOk(a), R.string.bb_bg_notif, R.string.bb_bg_allow) { openNotifications(a) })
            if (aggressiveVendor()) {
                val done = vendorDone(a)
                rows.addView(row(done, R.string.bb_bg_vendor, R.string.bb_bg_open) { openVendor(a); setVendorDone(a) })
            }
            view.visibility = if (allOk(a)) View.GONE else View.VISIBLE
        }
    }
}
