package org.athena.bitboinc

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import edu.berkeley.boinc.R
import edu.berkeley.boinc.client.ClientStatus
import edu.berkeley.boinc.rpc.AccountIn

/** Sign in / create account for the one project this app serves. Starts the engine, then opens the main screen. */
class WelcomeActivity : AppCompatActivity() {
    private lateinit var pal: Palette
    private val ui = Handler(Looper.getMainLooper())
    private var register = false
    private lateinit var email: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var go: TextView
    private lateinit var toggle: TextView
    private lateinit var msg: TextView
    private lateinit var userRow: View
    private var busy = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        pal = Themes.current(this)
        val prefs = getSharedPreferences("bb", Context.MODE_PRIVATE)
        Hub.ensureService(this)   // start the engine while the user types
        if (prefs.getString("auth", null) != null) { openMain(); return }

        window.statusBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(pal.bg))
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (if (pal.light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0) or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        val root = FrameLayout(this)
        root.addView(SpaceBackground(this, pal), ViewGroup.LayoutParams(-1, -1))
        val col = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(32), dp(24), dp(24)) }
        val sv = ScrollView(this).apply { addView(col, ViewGroup.LayoutParams(-1, -2)); isFillViewport = true }
        root.addView(sv, ViewGroup.LayoutParams(-1, -1))

        col.addView(android.widget.ImageView(this).apply { setImageResource(edu.berkeley.boinc.R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(88), dp(88)).apply { bottomMargin = dp(14) })
        col.addView(label(pal, "BitBOINC", 34f, bold = true).apply { gravity = Gravity.CENTER })
        col.addView(label(pal, getString(R.string.bb_tagline), 14f, muted = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(28)) }, matchWrap())

        val card = card(pal, 18)
        email = field(getString(R.string.bb_email), InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        user = field(getString(R.string.bb_username), InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        pass = field(getString(R.string.bb_password), InputType.TYPE_TEXT_VARIATION_PASSWORD)
        card.addView(email, matchWrap())
        userRow = user.also { it.visibility = View.GONE }
        card.addView(user, matchWrap().apply { topMargin = dp(10) })
        card.addView(pass, matchWrap().apply { topMargin = dp(10) })
        msg = label(pal, "", 12f, color = Color.parseColor("#FF6B6B")).apply { setPadding(0, dp(10), 0, 0) }
        card.addView(msg, matchWrap())
        go = TextView(this).apply {
            textSize = 15f; gravity = Gravity.CENTER; setTextColor(if (pal.light) Color.WHITE else Color.BLACK)
            background = rounded(pal.accent, dp(12).toFloat()); setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { submit() }
        }
        card.addView(go, matchWrap().apply { topMargin = dp(14) })
        col.addView(card, matchWrap())

        toggle = label(pal, "", 14f, color = pal.accent).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)); setOnClickListener { register = !register; refresh() } }
        col.addView(toggle, matchWrap())
        col.addView(label(pal, getString(R.string.bb_terms), 11f, muted = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL + "about.php"))) }
        }, matchWrap())
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val bars = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            sv.setPadding(0, bars.top, 0, bars.bottom); ins
        }
        refresh()
    }

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint; inputType = if (type == InputType.TYPE_TEXT_VARIATION_PASSWORD) InputType.TYPE_CLASS_TEXT or type else InputType.TYPE_CLASS_TEXT or type
        setTextColor(pal.text); setHintTextColor(pal.muted); textSize = 15f; maxLines = 1
        background = rounded(pal.bg, dp(10).toFloat(), pal.line, dp(1)); setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun refresh() {
        user.visibility = if (register) View.VISIBLE else View.GONE
        go.text = getString(if (register) R.string.bb_create_account else R.string.bb_sign_in)
        toggle.text = getString(if (register) R.string.bb_have_account else R.string.bb_no_account)
        msg.text = ""
    }

    private fun fail(res: Int, extra: String? = null) = ui.post {
        busy = false; go.alpha = 1f; refresh()
        msg.text = getString(res) + (if (extra.isNullOrBlank()) "" else "\n$extra")
    }

    private fun submit() {
        if (busy) return
        val e = email.text.toString().trim(); val pw = pass.text.toString(); val un = user.text.toString().trim()
        if (e.isEmpty() || pw.isEmpty() || (register && un.isEmpty())) { msg.text = getString(R.string.bb_fill_all); return }
        busy = true; go.alpha = 0.5f; go.text = getString(R.string.bb_connecting); msg.text = ""
        Thread {
            // wait for the engine (it unpacks and starts the BOINC client on first launch)
            var tries = 0
            while ((Hub.monitor == null || Hub.monitor!!.setupStatus == ClientStatus.SETUP_STATUS_LAUNCHING) && tries++ < 90) Thread.sleep(1000)
            val m = Hub.monitor
            if (m == null || m.setupStatus == ClientStatus.SETUP_STATUS_ERROR) { fail(R.string.bb_engine_failed); return@Thread }
            try {
                val cfg = m.getProjectConfigPolling(PROJECT_URL)
                if (cfg == null || cfg.errorNum != 0) { fail(R.string.bb_server_unreachable); return@Thread }
                val url = cfg.secureUrlIfAvailable ?: cfg.masterUrl
                val acc = AccountIn(url, e, if (register) un else null, pw, null, cfg.usesName)
                val out = if (register) m.createAccountPolling(acc) else m.lookupCredentials(acc)
                if (out == null) { fail(R.string.bb_server_unreachable); return@Thread }
                val key = out.authenticator
                if (out.errorNum != 0 || key.isNullOrEmpty()) {
                    fail(if (register) R.string.bb_register_failed else R.string.bb_login_failed, out.errorMsg); return@Thread
                }
                if (!m.attachProject(url, cfg.name, key)) { fail(R.string.bb_attach_failed); return@Thread }
                getSharedPreferences("bb", Context.MODE_PRIVATE).edit().putString("auth", key).putString("email", e).apply()
                ui.post { openMain() }
            } catch (ex: Exception) { fail(R.string.bb_server_unreachable) }
        }.start()
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
