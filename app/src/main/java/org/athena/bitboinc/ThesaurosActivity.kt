package org.athena.bitboinc

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/** Thesauros (object catalogue) API client: one POST per action, account key in the body (never in the URL). */
object ThApi {
    private const val URL_API = "https://bitboinc.athena.org.tr/thesauros_api.php"

    fun call(ctx: Context, act: String, fields: Map<String, String> = emptyMap(), files: Map<String, ByteArray> = emptyMap()): Pair<Int, JSONObject?> {
        val auth = ctx.getSharedPreferences("bb", Context.MODE_PRIVATE).getString("auth", null) ?: return Pair(0, null)
        val boundary = "----bb" + UUID.randomUUID().toString().replace("-", "")
        return try {
            val c = URL(URL_API).openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 90000
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.setRequestProperty("User-Agent", "BitBOINC-Android")
            DataOutputStream(c.outputStream).use { o ->
                fun field(k: String, v: String) {
                    o.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n"); o.write(v.toByteArray(Charsets.UTF_8)); o.writeBytes("\r\n")
                }
                field("auth", auth); field("act", act); field("lang", Locale.getDefault().language)
                fields.forEach { (k, v) -> field(k, v) }
                files.forEach { (k, b) ->
                    o.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
                    o.write(b); o.writeBytes("\r\n")
                }
                o.writeBytes("--$boundary--\r\n")
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.readText() ?: ""
            Pair(code, try { JSONObject(text) } catch (e: Exception) { null })
        } catch (e: Throwable) { Pair(0, null) }
    }
}

class ThesaurosActivity : AppCompatActivity() {
    private lateinit var p: Palette
    private lateinit var body: LinearLayout
    private lateinit var tabs: List<TextView>
    private val io = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())
    private var meta: JSONObject? = null
    private val thumbs = object : LruCache<String, Bitmap>(24) {}
    private var tab = 0
    private val tr = Locale.getDefault().language == "tr"
    private fun t(en: String, trs: String) = if (tr) trs else en

    // wizard state (new object, or a new measurement for itemId)
    private class Form {
        var itemId = 0
        var step = 1
        var name = ""; var brand = ""; var model = ""; var category = ""
        var quantity = "dose"; var device = ""; var deviceOther = ""; var bg = ""; var value = ""; var dur = "60"; var dist = "0"; var note = ""
        var photoObj: ByteArray? = null; var photoDisp: ByteArray? = null
        var prevObj: Bitmap? = null; var prevDisp: Bitmap? = null
        var licence = false
    }
    private var form = Form()
    private var pendingTarget = 0
    private var camUri: Uri? = null
    private var onPhoto: (() -> Unit)? = null

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) camUri?.let { handlePhoto(it) } }
    private val pickPicture = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) handlePhoto(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Themes.current(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.bg) }
        val top = hbox().apply { setPadding(dp(8), dp(10), dp(12), dp(6)) }
        val back = label(p, "‹", 28f).apply { setPadding(dp(12), 0, dp(14), 0); setOnClickListener { finish() } }
        top.addView(back)
        top.addView(label(p, "Thesauros", 20f, bold = true), weight())
        root.addView(top, matchWrap())
        val tabRow = hbox().apply { setPadding(dp(10), dp(4), dp(10), dp(8)) }
        val names = listOf(t("Catalogue", "Katalog"), t("Add", "Ekle"), t("Mine", "Benimkiler"))
        tabs = names.mapIndexed { i, n ->
            label(p, n, 14f, bold = true).apply {
                gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(10)); setOnClickListener { select(i) }
            }.also { tabRow.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }) }
        }
        root.addView(tabRow, matchWrap())
        body = vbox().apply { setPadding(dp(14), dp(4), dp(14), dp(24)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body, ViewGroup.LayoutParams(-1, -2)) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val bars = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(0, bars.top, 0, bars.bottom); ins
        }
        loadMeta { select(0) }
    }

    // ---------- helpers ----------
    private fun run(work: () -> Unit) = io.execute { try { work() } catch (e: Throwable) { } }
    private fun post(f: () -> Unit) = ui.post { if (!isFinishing) f() }

    private fun loadMeta(then: () -> Unit) {
        showMessage(t("Loading…", "Yükleniyor…"))
        run {
            val (code, j) = ThApi.call(this, "meta")
            post {
                if (code == 200 && j != null) { meta = j; then() }
                else showError(if (code == 0) t("No connection.", "Bağlantı yok.") else t("The server did not answer.", "Sunucu cevap vermedi."), true)
            }
        }
    }
    private fun showMessage(m: String) { body.removeAllViews(); body.addView(label(p, m, 14f, muted = true).apply { setPadding(dp(4), dp(20), dp(4), 0) }) }
    private fun showError(m: String, retry: Boolean = false) {
        body.removeAllViews(); body.addView(label(p, m, 14f).apply { setPadding(dp(4), dp(20), dp(4), dp(12)) })
        if (retry) body.addView(btn(t("Try again", "Tekrar dene"), true) { loadMeta { select(tab) } })
    }
    private fun btn(text: String, accent: Boolean = true, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(if (accent) (if (p.light) Color.WHITE else Color.BLACK) else p.text)
        background = if (accent) rounded(p.accent, dp(12).toFloat()) else rounded(0, dp(12).toFloat(), p.line, dp(1))
        setPadding(dp(16), dp(12), dp(16), dp(12)); setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }
    private fun field(hint: String, value: String, numeric: Boolean = false, multi: Boolean = false, onChange: (String) -> Unit): EditText = EditText(this).apply {
        setText(value); this.hint = hint; setTextColor(p.text); setHintTextColor(p.muted); textSize = 15f
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else if (multi) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        background = rounded(p.card, dp(10).toFloat(), p.line, dp(1)); setPadding(dp(12), dp(10), dp(12), dp(10))
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(8) }
    }
    private fun lab(text: String) = label(p, text, 12f, muted = true).apply { setPadding(dp(2), dp(6), 0, 0) }
    private fun spinner(items: List<String>, selected: Int, onSel: (Int) -> Unit): Spinner = Spinner(this).apply {
        adapter = object : ArrayAdapter<String>(this@ThesaurosActivity, android.R.layout.simple_spinner_dropdown_item, items) {
            override fun getView(pos: Int, v: View?, parent: ViewGroup): View = (super.getView(pos, v, parent) as TextView).apply { setTextColor(p.text) }
            override fun getDropDownView(pos: Int, v: View?, parent: ViewGroup): View = (super.getDropDownView(pos, v, parent) as TextView).apply { setTextColor(Color.BLACK) }
        }
        setSelection(selected)
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(a: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) { onSel(pos) }
            override fun onNothingSelected(a: android.widget.AdapterView<*>?) {}
        }
        background = rounded(p.card, dp(10).toFloat(), p.line, dp(1)); setPadding(dp(8), dp(6), dp(8), dp(6))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(8) }
    }
    private fun leftText(sec: Int): String { val m = (sec + 59) / 60; return if (m >= 60) t("${m / 60} h ${m % 60} min left", "kalan ${m / 60} sa ${m % 60} dk") else t("$m min left", "kalan $m dk") }
    private fun thumb(iv: ImageView, url: String?) {
        if (url == null) return
        thumbs.get(url)?.let { iv.setImageBitmap(it); return }
        run { try { val b = BitmapFactory.decodeStream(URL(url).openStream()); if (b != null) { thumbs.put(url, b); post { iv.setImageBitmap(b) } } } catch (e: Throwable) { } }
    }
    private fun arr(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }

    private fun select(i: Int) {
        tab = i
        tabs.forEachIndexed { n, v -> v.setTextColor(if (n == i) (if (p.light) Color.WHITE else Color.BLACK) else p.text); v.background = if (n == i) rounded(p.accent, dp(12).toFloat()) else rounded(0, dp(12).toFloat(), p.line, dp(1)) }
        when (i) { 0 -> showCatalogue(""); 1 -> { form = Form(); showForm() }; else -> showMine() }
    }

    // ---------- catalogue ----------
    private fun showCatalogue(q: String) {
        body.removeAllViews()
        val row = hbox(); var query = q
        row.addView(field(t("Search name, brand, model", "Ad, marka, model ara"), q) { query = it }, weight())
        row.addView(btn(t("Search", "Ara"), true) { showCatalogue(query) }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) } })
        body.addView(row, matchWrap())
        val holder = vbox(); body.addView(holder, matchWrap())
        holder.addView(label(p, t("Loading…", "Yükleniyor…"), 13f, muted = true))
        run {
            val (code, j) = ThApi.call(this, "list", mapOf("q" to q))
            post {
                holder.removeAllViews()
                if (code != 200 || j == null) { holder.addView(label(p, t("Could not load the catalogue.", "Katalog yüklenemedi."), 14f)); return@post }
                val items = arr(j.optJSONArray("items"))
                if (items.isEmpty()) holder.addView(label(p, t("Nothing here yet. Be the first to add an object.", "Henüz bir şey yok. İlk nesneyi siz ekleyin."), 14f, muted = true).apply { setPadding(0, dp(16), 0, 0) })
                items.forEach { holder.addView(itemCard(it)) }
                if (j.optInt("total") > items.size) holder.addView(label(p, t("Showing the newest ${items.size} of ${j.optInt("total")}. Use the search to find others.", "En yeni ${items.size} / ${j.optInt("total")} gösteriliyor. Diğerleri için arama yapın."), 12f, muted = true).apply { setPadding(0, dp(8), 0, 0) })
            }
        }
    }
    private fun itemCard(o: JSONObject): View {
        val c = card(p, 10).apply { setOnClickListener { showItem(o.optInt("id")) } }
        val h = hbox()
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = rounded(p.line, dp(10).toFloat()); clipToOutline = true }
        h.addView(iv, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(12) })
        thumb(iv, o.optString("thumb").ifEmpty { null })
        val v = vbox()
        v.addView(label(p, o.optString("name"), 15f, bold = true))
        v.addView(label(p, (o.optString("brand") + " " + o.optString("model")).trim(), 13f, muted = true))
        v.addView(label(p, t("${o.optInt("measures")} measurements · ", "${o.optInt("measures")} ölçüm · ") + o.optString("user"), 12f, muted = true).apply { setPadding(0, dp(2), 0, 0) })
        h.addView(v, weight()); c.addView(h, matchWrap()); return c
    }

    // ---------- one object ----------
    private fun showItem(id: Int) {
        showMessage(t("Loading…", "Yükleniyor…"))
        run {
            val (code, j) = ThApi.call(this, "item", mapOf("id" to id.toString()))
            post {
                if (code != 200 || j == null) { showError(t("Could not load this object.", "Bu nesne yüklenemedi.")); return@post }
                body.removeAllViews()
                body.addView(btn(t("‹ Back", "‹ Geri"), false) { select(0) })
                val ph = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = rounded(p.line, dp(14).toFloat()); clipToOutline = true }
                body.addView(ph, LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(10) })
                thumb(ph, j.optString("photo").ifEmpty { null })
                body.addView(label(p, j.optString("name"), 20f, bold = true).apply { setPadding(0, dp(10), 0, 0) })
                body.addView(label(p, (j.optString("brand") + " " + j.optString("model")).trim(), 14f, muted = true))
                val extra = listOf(j.optString("year"), j.optString("material")).filter { it.isNotEmpty() }.joinToString(" · ")
                if (extra.isNotEmpty()) body.addView(label(p, extra, 13f, muted = true))
                if (j.optString("note").isNotEmpty()) body.addView(label(p, j.optString("note"), 14f).apply { setPadding(0, dp(8), 0, 0) })
                body.addView(label(p, t("Measurements", "Ölçümler"), 13f, muted = true).apply { setPadding(0, dp(14), 0, dp(4)) })
                val ms = arr(j.optJSONArray("measurements"))
                if (ms.isEmpty()) body.addView(label(p, t("No measurements yet.", "Henüz ölçüm yok."), 13f, muted = true))
                ms.forEach { m ->
                    val c = card(p, 10)
                    c.addView(label(p, "${m.optDouble("net")} ${m.optString("unit")} " + t("net", "net"), 16f, bold = true))
                    c.addView(label(p, t("reading ", "ölçüm ") + "${m.optDouble("value")} · " + t("background ", "arka plan ") + "${m.optDouble("background")}", 12f, muted = true))
                    c.addView(label(p, m.optString("device") + " · " + m.optString("user"), 12f, muted = true))
                    val left = m.optInt("delete_left")
                    if (left > 0) c.addView(btn(t("Delete", "Sil") + " (" + leftText(left) + ")", false) { confirmDelete("measure", m.optInt("id")) { showItem(id) } })
                    body.addView(c)
                }
                body.addView(btn(t("Add my measurement", "Kendi ölçümümü ekle"), true) { form = Form().apply { itemId = id; step = 2 }; tab = 1; select1() })
                if (j.optInt("delete_left") > 0) body.addView(btn(t("Delete this object", "Bu nesneyi sil") + " (" + leftText(j.optInt("delete_left")) + ")", false) { confirmDelete("item", id) { select(0) } })
                if (!j.optBoolean("mine")) body.addView(btn(t("Report a problem", "Sorun bildir"), false) { reportDialog(id) })
            }
        }
    }
    private fun select1() { tabs.forEachIndexed { n, v -> v.setTextColor(if (n == 1) (if (p.light) Color.WHITE else Color.BLACK) else p.text); v.background = if (n == 1) rounded(p.accent, dp(12).toFloat()) else rounded(0, dp(12).toFloat(), p.line, dp(1)) }; showForm() }

    private fun confirmDelete(type: String, id: Int, done: () -> Unit) {
        AlertDialog.Builder(this).setMessage(t("Delete this permanently?", "Bu kalıcı olarak silinsin mi?"))
            .setPositiveButton(t("Delete", "Sil")) { _, _ ->
                run {
                    val (code, j) = ThApi.call(this, "delete", mapOf("type" to type, "id" to id.toString()))
                    post { if (code == 200) { Toast.makeText(this, t("Deleted.", "Silindi."), Toast.LENGTH_SHORT).show(); done() } else Toast.makeText(this, j?.optString("error").takeUnless { it.isNullOrEmpty() } ?: t("Could not delete.", "Silinemedi."), Toast.LENGTH_LONG).show() }
                }
            }.setNegativeButton(t("Cancel", "Vazgeç"), null).show()
    }
    private fun reportDialog(itemId: Int) {
        val e = EditText(this).apply { hint = t("What is wrong?", "Sorun nedir?"); setPadding(dp(20), dp(12), dp(20), dp(12)) }
        AlertDialog.Builder(this).setTitle(t("Report a problem", "Sorun bildir")).setMessage(t("Inappropriate photo, wrong or fake data, personal information.", "Uygunsuz fotoğraf, yanlış veya sahte veri, kişisel bilgi.")).setView(e)
            .setPositiveButton(t("Report", "Bildir")) { _, _ ->
                run {
                    val (_, j) = ThApi.call(this, "report", mapOf("item_id" to itemId.toString(), "reason" to e.text.toString()))
                    post { Toast.makeText(this, j?.optString("message").takeUnless { it.isNullOrEmpty() } ?: j?.optString("error") ?: t("Could not send.", "Gönderilemedi."), Toast.LENGTH_LONG).show() }
                }
            }.setNegativeButton(t("Cancel", "Vazgeç"), null).show()
    }

    // ---------- my entries ----------
    private fun showMine() {
        showMessage(t("Loading…", "Yükleniyor…"))
        run {
            val (code, j) = ThApi.call(this, "mine")
            post {
                if (code != 200 || j == null) { showError(t("Could not load your entries.", "Kayıtlarınız yüklenemedi.")); return@post }
                body.removeAllViews()
                body.addView(label(p, t("Your score: ", "Puanınız: ") + j.optInt("score"), 18f, bold = true).apply { setPadding(dp(2), dp(4), 0, dp(2)) })
                body.addView(label(p, t("10 points per object, 5 per measurement. You can delete your own entry for 2 hours after adding it.", "Nesne başına 10, ölçüm başına 5 puan. Kendi kaydınızı ekledikten sonra 2 saat içinde silebilirsiniz."), 12f, muted = true).apply { setPadding(dp(2), 0, 0, dp(10)) })
                val items = arr(j.optJSONArray("items"))
                if (items.isEmpty()) body.addView(label(p, t("You have not added anything yet.", "Henüz bir şey eklemediniz."), 14f, muted = true))
                items.forEach { o ->
                    val c = itemCard(o)
                    if (o.optString("status") != "public") (c as LinearLayout).addView(label(p, if (o.optString("status") == "hidden") t("hidden", "gizli") else t("waiting for review", "inceleme bekliyor"), 12f, p.accent))
                    if (o.optInt("delete_left") > 0) (c as LinearLayout).addView(btn(t("Delete", "Sil") + " (" + leftText(o.optInt("delete_left")) + ")", false) { confirmDelete("item", o.optInt("id")) { showMine() } })
                    body.addView(c)
                }
            }
        }
    }

    // ---------- the add form (4 steps for a new object, 2 for a measurement) ----------
    private fun showForm() {
        val m = meta ?: return
        body.removeAllViews()
        val f = form
        val newObj = f.itemId == 0
        val steps = if (newObj) 4 else 3
        val shown = if (newObj) f.step else f.step - 1
        body.addView(label(p, t("Step $shown of $steps", "Adım $shown / $steps"), 12f, muted = true).apply { setPadding(dp(2), dp(4), 0, dp(6)) })
        when (f.step) {
            1 -> stepObject(m)
            2 -> stepMeasure(m)
            3 -> stepPhotos()
            else -> stepConfirm()
        }
    }
    private fun nav(back: (() -> Unit)?, nextText: String, next: () -> Unit) {
        val r = hbox().apply { setPadding(0, dp(6), 0, 0) }
        if (back != null) r.addView(btn(t("Back", "Geri"), false) { back() }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) } })
        r.addView(btn(nextText, true) { next() }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        body.addView(r, matchWrap())
    }
    private fun warn(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun stepObject(m: JSONObject) {
        val f = form
        val cats = arr(m.optJSONArray("categories"))
        body.addView(label(p, t("What is it?", "Bu nedir?"), 18f, bold = true))
        body.addView(lab(t("What it is (salt substitute, wristwatch, floor tile…)", "Ne olduğu (tuz ikamesi, kol saati, yer karosu…)")))
        body.addView(field("", f.name) { f.name = it })
        body.addView(lab(t("Brand (write Unbranded if none)", "Marka (yoksa Markasız yazın)")))
        body.addView(field("", f.brand) { f.brand = it })
        body.addView(lab(t("Model or product name", "Model veya ürün adı")))
        body.addView(field("", f.model) { f.model = it })
        body.addView(lab(t("Category", "Kategori")))
        val ci = cats.indexOfFirst { it.optString("key") == f.category }.coerceAtLeast(0)
        if (f.category.isEmpty() && cats.isNotEmpty()) f.category = cats[0].optString("key")
        body.addView(spinner(cats.map { it.optString("label") }, ci) { f.category = cats[it].optString("key") })
        nav(null, t("Next", "İleri")) {
            when {
                f.name.isBlank() -> warn(t("Write what the object is.", "Nesnenin ne olduğunu yazın."))
                f.brand.isBlank() -> warn(t("Write the brand (or Unbranded).", "Markayı yazın (veya Markasız)."))
                f.model.isBlank() -> warn(t("Write the model or product name.", "Modeli veya ürün adını yazın."))
                else -> { f.step = 2; showForm() }
            }
        }
    }

    private fun stepMeasure(m: JSONObject) {
        val f = form
        val qs = arr(m.optJSONArray("quantities")); val devices = (0 until (m.optJSONArray("devices")?.length() ?: 0)).map { m.optJSONArray("devices")!!.getString(it) }
        val help = m.optJSONObject("help")
        body.addView(label(p, t("Measure it", "Ölçün"), 18f, bold = true))
        body.addView(lab(t("What did you measure?", "Neyi ölçtünüz?")))
        val qi = qs.indexOfFirst { it.optString("key") == f.quantity }.coerceAtLeast(0)
        val helpView = label(p, "", 12f, muted = true).apply { setPadding(dp(2), 0, 0, dp(8)) }
        val unit1 = label(p, "", 12f, muted = true); val unit2 = label(p, "", 12f, muted = true)
        val net = label(p, t("The net value appears here.", "Net değer burada görünür."), 15f, bold = true).apply { setPadding(dp(2), dp(4), 0, dp(8)) }
        fun unit() = qs.getOrNull(qs.indexOfFirst { it.optString("key") == f.quantity })?.optString("unit") ?: ""
        fun calc() {
            val b = f.bg.replace(',', '.').toDoubleOrNull(); val v = f.value.replace(',', '.').toDoubleOrNull()
            if (b == null || v == null) return
            val x = Math.round((v - b) * 10000) / 10000.0
            net.text = t("Net value: ", "Net değer: ") + x + " " + unit() + (if (x < 0) t(" (lower than the background)", " (arka plandan düşük)") else "")
        }
        fun refresh() { unit1.text = unit(); unit2.text = unit(); helpView.text = help?.optString(f.quantity) ?: ""; calc() }
        body.addView(spinner(qs.map { it.optString("label") + " (" + it.optString("unit") + ")" }, qi) { f.quantity = qs[it].optString("key"); refresh() })
        body.addView(helpView)
        body.addView(lab(t("Your device", "Cihazınız")))
        val di = devices.indexOf(f.device).coerceAtLeast(0)
        if (f.device.isEmpty() && devices.isNotEmpty()) f.device = devices[0]
        val other = field(t("Brand and model", "Marka ve model"), f.deviceOther) { f.deviceOther = it }
        fun showOther() { other.visibility = if (f.device.startsWith("Other")) View.VISIBLE else View.GONE }
        body.addView(spinner(devices, di) { f.device = devices[it]; showOther() })
        body.addView(other); showOther()
        body.addView(lab(t("1. Background (object away from the detector)", "1. Arka plan (nesne detektörden uzakta)")))
        body.addView(field("", f.bg, true) { f.bg = it; calc() }); body.addView(unit1)
        body.addView(lab(t("2. With the object (detector touching it)", "2. Nesneyle (detektör nesneye değerken)")))
        body.addView(field("", f.value, true) { f.value = it; calc() }); body.addView(unit2)
        body.addView(net)
        body.addView(lab(t("Averaging time (seconds)", "Ortalama süresi (saniye)")))
        body.addView(field("", f.dur, true) { f.dur = it })
        body.addView(lab(t("Distance (cm, 0 = touching)", "Mesafe (cm, 0 = temas)")))
        body.addView(field("", f.dist, true) { f.dist = it })
        body.addView(lab(t("Note (optional)", "Not (isteğe bağlı)")))
        body.addView(field("", f.note) { f.note = it })
        refresh()
        nav(if (f.itemId == 0) ({ f.step = 1; showForm() }) else null, t("Next", "İleri")) {
            if (f.bg.replace(',', '.').toDoubleOrNull() == null || f.value.replace(',', '.').toDoubleOrNull() == null) warn(t("Enter the background and the object reading as numbers.", "Arka plan ve nesne ölçümünü sayı olarak girin."))
            else if (f.device.startsWith("Other") && f.deviceOther.isBlank()) warn(t("Write your device name.", "Cihaz adınızı yazın."))
            else { f.step = 3; showForm() }
        }
    }

    private fun photoZone(title: String, hint: String, prev: Bitmap?, target: Int) {
        val c = card(p, 12)
        c.addView(label(p, title, 15f, bold = true)); c.addView(label(p, hint, 12f, muted = true).apply { setPadding(0, dp(2), 0, dp(8)) })
        if (prev != null) c.addView(ImageView(this).apply { setImageBitmap(prev); adjustViewBounds = true; scaleType = ImageView.ScaleType.CENTER_CROP; background = rounded(p.line, dp(10).toFloat()); clipToOutline = true }, LinearLayout.LayoutParams(-1, dp(180)))
        val r = hbox()
        r.addView(btn(t("Take photo", "Fotoğraf çek"), true) { startCapture(target) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) } })
        r.addView(btn(t("From gallery", "Galeriden"), false) { pendingTarget = target; pickPicture.launch("image/*") }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        c.addView(r, matchWrap()); body.addView(c)
    }
    private fun startCapture(target: Int) {
        pendingTarget = target
        val dir = File(cacheDir, "th").apply { mkdirs() }
        val file = File(dir, "cap_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.thfiles", file)
        camUri = uri
        try { takePicture.launch(uri) } catch (e: Throwable) { warn(t("No camera app found. Use the gallery.", "Kamera uygulaması bulunamadı. Galeriyi kullanın.")) }
    }
    private fun handlePhoto(uri: Uri) {
        val target = pendingTarget
        run {
            val r = loadJpeg(uri)
            post {
                if (r == null) { warn(t("The photo could not be read.", "Fotoğraf okunamadı.")); return@post }
                if (target == 0) { form.photoObj = r.first; form.prevObj = r.second } else { form.photoDisp = r.first; form.prevDisp = r.second }
                if (form.step == 3) showForm()
            }
        }
    }
    /** Decodes, applies the camera rotation, shrinks to 1600 px and returns JPEG bytes plus a small preview. */
    private fun loadJpeg(uri: Uri): Pair<ByteArray, Bitmap>? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > 3200) s *= 2
        var bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) } ?: return null
        val rot = contentResolver.openInputStream(uri)?.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } ?: 1
        val deg = when (rot) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        if (deg != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        val k = minOf(1f, 1600f / maxOf(bmp.width, bmp.height))
        if (k < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        val pk = minOf(1f, 640f / maxOf(bmp.width, bmp.height))
        Pair(out.toByteArray(), Bitmap.createScaledBitmap(bmp, (bmp.width * pk).toInt().coerceAtLeast(1), (bmp.height * pk).toInt().coerceAtLeast(1), true))
    } catch (e: Throwable) { null }

    private fun stepPhotos() {
        val f = form
        body.addView(label(p, t("Photos", "Fotoğraflar"), 18f, bold = true))
        body.addView(label(p, t("No faces, addresses or papers in the picture.", "Fotoğrafta yüz, adres veya evrak olmasın."), 12f, muted = true).apply { setPadding(0, 0, 0, dp(8)) })
        if (f.itemId == 0) photoZone(t("Photo 1: the object", "Fotoğraf 1: nesne"), t("The whole object, well lit", "Nesnenin tamamı, iyi aydınlatılmış"), f.prevObj, 0)
        photoZone(t("Photo 2: the device screen", "Fotoğraf 2: cihaz ekranı"), t("The reading must be readable", "Ölçüm okunabilir olmalı"), f.prevDisp, 1)
        nav({ f.step = 2; showForm() }, t("Next", "İleri")) {
            if ((f.itemId == 0 && f.photoObj == null) || f.photoDisp == null) warn(t("Choose the photos.", "Fotoğrafları seçin.")) else { f.step = 4; showForm() }
        }
    }

    private fun stepConfirm() {
        val f = form
        body.addView(label(p, t("Almost done", "Neredeyse bitti"), 18f, bold = true))
        val c = card(p, 12)
        if (f.itemId == 0) c.addView(label(p, f.name + " (" + f.brand + " " + f.model + ")", 15f, bold = true))
        c.addView(label(p, t("Reading ", "Ölçüm ") + f.value + " · " + t("background ", "arka plan ") + f.bg, 13f, muted = true))
        body.addView(c)
        val cb = CheckBox(this).apply {
            text = t("I took these photos and measurements myself and agree to publish them under the Creative Commons Attribution 4.0 licence (CC BY 4.0), so the project can share them as an open dataset and for research.",
                "Bu fotoğrafları ve ölçümleri kendim yaptım ve Creative Commons Atıf 4.0 (CC BY 4.0) lisansı altında yayımlanmasını kabul ediyorum; proje bunları açık veri kümesi olarak ve araştırma için paylaşabilir.")
            setTextColor(p.text); textSize = 13f; isChecked = f.licence; setOnCheckedChangeListener { _, v -> f.licence = v }
        }
        body.addView(cb)
        body.addView(label(p, t("Photos get a faint BitBOINC mark. You can delete this for 2 hours.", "Fotoğraflara hafif bir BitBOINC damgası eklenir. Bunu 2 saat içinde silebilirsiniz."), 12f, muted = true).apply { setPadding(dp(2), dp(6), 0, 0) })
        nav({ f.step = 3; showForm() }, t("Upload", "Gönder")) { if (!f.licence) warn(t("Please accept the licence.", "Lütfen lisansı kabul edin.")) else upload() }
    }

    private fun upload() {
        val f = form
        showMessage(t("Uploading…", "Gönderiliyor…"))
        run {
            val dev = if (f.device.startsWith("Other")) "Other" else f.device
            val fields = HashMap<String, String>().apply {
                put("quantity", f.quantity); put("value", f.value); put("bg_value", f.bg); put("duration_s", f.dur); put("distance_cm", f.dist)
                put("device", dev); put("device_other", f.deviceOther); put("mnote", f.note); put("licence", "1")
            }
            val files = HashMap<String, ByteArray>().apply { f.photoDisp?.let { put("photo_display", it) }; if (f.itemId == 0) f.photoObj?.let { put("photo_object", it) } }
            val (code, j) = if (f.itemId == 0) {
                fields.putAll(mapOf("name" to f.name, "brand" to f.brand, "model" to f.model, "category" to f.category))
                ThApi.call(this, "add_item", fields, files)
            } else { fields["item_id"] = f.itemId.toString(); ThApi.call(this, "add_measure", fields, files) }
            post {
                when {
                    code == 200 && j?.optBoolean("ok") == true -> {
                        Toast.makeText(this, if (j.optBoolean("pending")) t("Added. It will appear after a quick review.", "Eklendi. Kısa bir incelemeden sonra görünecek.") else t("Added. Thank you!", "Eklendi. Teşekkürler!"), Toast.LENGTH_LONG).show()
                        form = Form(); select(2)
                    }
                    code == 409 && j?.optString("error") == "duplicate" -> {
                        AlertDialog.Builder(this).setMessage(j.optString("message")).setPositiveButton(t("Open it", "Aç")) { _, _ -> showItem(j.optInt("item_id")) }.setNegativeButton(t("Close", "Kapat"), null).show()
                        showForm()
                    }
                    else -> { warn(j?.optString("error").takeUnless { it.isNullOrEmpty() } ?: if (code == 0) t("No connection.", "Bağlantı yok.") else t("Could not upload.", "Yüklenemedi.")); form.step = if (j?.optString("field")?.startsWith("photo") == true) 3 else if (j?.optString("field") == "measure") 2 else form.step; showForm() }
                }
            }
        }
    }

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, ThesaurosActivity::class.java))
    }
}
