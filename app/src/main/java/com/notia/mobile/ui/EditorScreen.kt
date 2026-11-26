package com.notia.mobile.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.notia.mobile.MainActivity
import com.notia.mobile.R
import com.notia.mobile.core.MathError
import com.notia.mobile.core.Note
import com.notia.mobile.core.Store
import com.notia.mobile.core.chartImg
import com.notia.mobile.core.esc
import com.notia.mobile.core.evalMath
import com.notia.mobile.core.fmtDate
import com.notia.mobile.core.fmtNum
import com.notia.mobile.core.parsePts
import com.notia.mobile.core.tableHtml
import com.notia.mobile.sys.Exporter
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream

fun cssColor(c: Int) = String.format("#%06X", c and 0xFFFFFF)

object Pages {
    fun load(a: MainActivity, name: String, vars: Map<String, String>): String {
        var s = a.assets.open(name).bufferedReader().use { it.readText() }
        for ((k, v) in vars) s = s.replace(k, v)
        return s
    }
    fun nonce(): String { val b = ByteArray(12); java.security.SecureRandom().nextBytes(b); return b.joinToString("") { "%02x".format(it) } }
}

class EditorScreen(private val a: MainActivity, private val id: String) : Screen() {
    private val note: Note? = Store.note(id)
    private val root = a.vbox { setBackgroundColor(Th.bg) }
    private val web = WebView(a)
    private val meta = a.text("", 11f, Th.sub)
    private val chips = a.hbox()
    private var chars = note?.text?.length ?: 0
    private var done = false
    private val title = a.field(L("Title", "Titre")).apply {
        background = null; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setPadding(a.dp(20), a.dp(4), a.dp(20), a.dp(4))
        setText(note?.title ?: "")
    }

    private inner class Bridge {
        @JavascriptInterface fun ready() { a.post(Runnable {
            val n = note ?: return@Runnable
            web.evaluateJavascript("setContent(" + JSONObject.quote(n.html) + ")", null)
            if (n.title.isBlank() && n.html.isBlank()) { title.requestFocus(); (a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(title, 0) }
        }) }
        @JavascriptInterface fun change(html: String, count: Int) { a.post(Runnable { onText(html, count) }) }
        @JavascriptInterface fun image(pct: Int) { a.post(Runnable { imageSheet(pct) }) }
        @JavascriptInterface fun noImage() { a.post(Runnable { a.toast(L("Tap a picture in the note to select it, then drag its round handle or use this button.", "Touchez une image de la note pour la sélectionner, puis faites glisser sa poignée ronde ou utilisez ce bouton.")) }) }
    }

    private fun onText(html: String, count: Int) {
        val n = note ?: return
        if (n.html != html) { n.html = html; Store.touch(n) }
        chars = count; updateMeta()
    }

    private fun updateMeta() {
        val n = note ?: return
        meta.text = "$chars " + L("characters", "caractères") + "  •  " + L("Created", "Créée") + " " + fmtDate(n.created, true) + "  •  " + L("Modified", "Modifiée") + " " + fmtDate(n.modified, true)
    }

    private fun js(s: String) { web.evaluateJavascript(s, null) }

    /** Pulls the freshest HTML out of the page, then continues. */
    private fun sync(next: () -> Unit) {
        web.evaluateJavascript("(function(){return ed.innerHTML})()") { v ->
            try { (JSONTokener(v).nextValue() as? String)?.let { h -> note?.let { n -> if (n.html != h) { n.html = h; Store.touch(n) } } } } catch (e: Exception) {}
            next()
        }
    }

    init {
        val n = note
        // top bar: back on the left, share and delete on the right, all 44dp and vertically centred
        root.put(a.pageHeader({ onBack() }, null,
            a.iconBtn(R.drawable.ic_share, L("Share", "Partager"), Th.ink) { share() },
            a.iconBtn(R.drawable.ic_delete, L("Delete", "Supprimer"), Th.danger) {
                a.confirm(L("Delete this note?", "Supprimer cette note ?"), null, L("Delete", "Supprimer")) { done = true; Store.removeNote(id); a.pop() }
            }))
        root.put(title, MATCH, WRAP, 0f, 0, 4, 0, 0)
        title.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { val nn = note ?: return; val t = s?.toString() ?: ""; if (nn.title != t) { nn.title = t; Store.touch(nn); updateMeta() } }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        root.put(a.chipScroller(chips), MATCH, WRAP, 0f, 0, 6, 0, 8)
        drawChips()

        // web editor
        web.setBackgroundColor(Th.bg)
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.settings.apply {
            javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false
            setSupportZoom(false); builtInZoomControls = false; mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        web.addJavascriptInterface(Bridge(), "Notia")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = true
        }
        root.put(web, MATCH, 0, 1f)
        root.put(meta, MATCH, WRAP, 0f, 20, 4, 20, 6)
        updateMeta()

        // toolbar: the basics are always one row; "all tools" opens a second panel above it
        val bar = a.vbox { background = barBg(); elevation = a.dpf(8f); setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(6)) }
        val extra = a.vbox {
            put(cells(
                tText("H1", Typeface.BOLD, desc = L("Heading 1", "Titre 1")) { js("block('h1')") },
                tText("H2", Typeface.BOLD, desc = L("Heading 2", "Titre 2")) { js("block('h2')") },
                tIcon(R.drawable.ic_code, L("Code", "Code")) { js("block('pre')") },
                tIcon(R.drawable.ic_hr, L("Line", "Ligne")) { js("cmd('insertHorizontalRule')") },
                tIcon(R.drawable.ic_link, L("Link", "Lien"), Th.accent) { linkSheet() },
                tIcon(R.drawable.ic_image, L("Image", "Image"), Th.accent) { pickImage() },
            ))
            put(cells(
                tIcon(R.drawable.ic_table, L("Table", "Tableau"), Th.accent) { tableSheet() },
                tIcon(R.drawable.ic_calc, L("Formula", "Formule"), Th.accent) { formulaSheet() },
                tIcon(R.drawable.ic_chart, L("Chart", "Graphique"), Th.accent) { chartSheet() },
                tText("A", Typeface.BOLD, true, L("Text colour", "Couleur du texte"), 0xFFE5484D.toInt()) { swatchSheet(true) },
                highlightBtn(),
                tText("Tx", Typeface.NORMAL, false, L("Clear formatting", "Effacer la mise en forme"), Th.sub) { js("cmd('removeFormat')") },
            ))
            put(cells(
                tIcon(R.drawable.ic_align_left, L("Align left", "Aligner à gauche")) { js("cmd('justifyLeft')") },
                tIcon(R.drawable.ic_align_center, L("Centre", "Centrer")) { js("cmd('justifyCenter')") },
                tIcon(R.drawable.ic_align_right, L("Align right", "Aligner à droite")) { js("cmd('justifyRight')") },
                tIcon(R.drawable.ic_justify, L("Justify", "Justifier"), Th.accent) { js("cmd('justifyFull')") },
                tIcon(R.drawable.ic_scan, L("Scan pages", "Scanner des pages"), Th.accent) { scanIntoNote() },
                tIcon(R.drawable.ic_resize, L("Resize picture", "Redimensionner l’image"), Th.accent) { js("imgPick()") },
            ))
            put(View(a).apply { setBackgroundColor(Th.line) }, MATCH, 1, 0f, 8, 4, 8, 4)
        }
        val chevron = ImageView(a).apply { setImageResource(R.drawable.ic_expand); setColorFilter(Th.accent) }
        val toggle = FrameLayout(a).apply {
            isClickable = true
            // inset background: the touch area stays a full cell, the visible tile is slightly smaller
            background = android.graphics.drawable.InsetDrawable(a.pressable(Th.field, 12f), a.dp(3))
            addView(chevron, FrameLayout.LayoutParams(a.dp(22), a.dp(22), Gravity.CENTER))
        }
        toggle.setOnClickListener { Store.toolsAll = !Store.toolsAll; Store.saveState(); applyTools(bar, extra, chevron, toggle, true) }
        bar.put(extra)
        bar.put(cells(
            tIcon(R.drawable.ic_undo, L("Undo", "Annuler")) { js("cmd('undo')") },
            tIcon(R.drawable.ic_redo, L("Redo", "Rétablir")) { js("cmd('redo')") },
            tText("B", Typeface.BOLD, desc = L("Bold", "Gras")) { js("cmd('bold')") },
            tText("I", Typeface.ITALIC, desc = L("Italic", "Italique")) { js("cmd('italic')") },
            tText("U", Typeface.NORMAL, true, L("Underline", "Souligné")) { js("cmd('underline')") },
            tIcon(R.drawable.ic_bullets, L("Bullets", "Puces")) { js("cmd('insertUnorderedList')") },
            tIcon(R.drawable.ic_numbered, L("Numbered list", "Liste numérotée")) { js("cmd('insertOrderedList')") },
            toggle,
        ))
        applyTools(bar, extra, chevron, toggle, false)
        root.put(bar)

        val html = Pages.load(a, "editor.html", mapOf("%NONCE%" to Pages.nonce(), "%BG%" to cssColor(Th.bg), "%INK%" to cssColor(Th.ink), "%SUB%" to cssColor(Th.sub),
            "%ACCENT%" to cssColor(Th.accent), "%PH%" to esc(L("Start writing…", "Commencez à écrire…"))))
        web.loadDataWithBaseURL("https://notia.local/", html, "text/html", "utf-8", null)
        if (n == null) a.post(Runnable { a.pop() })
    }

    private fun barBg() = GradientDrawable().apply {
        val r = a.dpf(22f); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        setColor(Th.card); setStroke(a.dp(1), Th.line)
    }

    /** Shows or hides the "all tools" panel and points the chevron the right way. */
    private fun applyTools(bar: LinearLayout, extra: View, chevron: View, toggle: View, animate: Boolean) {
        if (animate) android.transition.TransitionManager.beginDelayedTransition(bar)
        val all = Store.toolsAll
        extra.visibility = if (all) View.VISIBLE else View.GONE
        chevron.rotation = if (all) 180f else 0f
        toggle.contentDescription = if (all) L("Show basic tools only", "Afficher les outils de base") else L("Show all tools", "Afficher tous les outils")
    }

    /** A row of equally wide, 44dp-tall cells so every button gets the same comfortable touch area. */
    private fun cells(vararg c: View): LinearLayout = a.hbox().also { r -> for (v in c) r.put(v, 0, a.dp(44), 1f) }

    private fun tIcon(res: Int, desc: String, tint: Int = Th.ink, onClick: () -> Unit): View = FrameLayout(a).apply {
        contentDescription = desc; isClickable = true; background = a.pressable(Color.TRANSPARENT, 12f)
        addView(android.widget.ImageView(a).apply { setImageResource(res); setColorFilter(tint) }, FrameLayout.LayoutParams(a.dp(22), a.dp(22), Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    private fun tText(label: String, style: Int, underline: Boolean = false, desc: String = label, color: Int = Th.ink, onClick: () -> Unit): View = a.text(label, 16f, color, true).apply {
        setTypeface(Typeface.DEFAULT, if (style == Typeface.NORMAL) Typeface.BOLD else style)
        if (underline) paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        gravity = Gravity.CENTER; isClickable = true; contentDescription = desc; background = a.pressable(Color.TRANSPARENT, 12f)
        setOnClickListener { onClick() }
    }

    private fun highlightBtn(): View = FrameLayout(a).apply {
        contentDescription = L("Highlight", "Surlignage"); isClickable = true; background = a.pressable(Color.TRANSPARENT, 12f)
        addView(a.text("A", 15f, 0xFF1B1830.toInt(), true).apply { gravity = Gravity.CENTER; background = a.rounded(0xFFFFE08A.toInt(), 8f) }, FrameLayout.LayoutParams(a.dp(28), a.dp(28), Gravity.CENTER))
        setOnClickListener { swatchSheet(false) }
    }

    /** Colour picker: big, labelled swatches instead of 11 tiny dots in a scrolling row. */
    private fun swatchSheet(ink: Boolean) {
        val s = Sheet(a, if (ink) L("Text colour", "Couleur du texte") else L("Highlight", "Surlignage"))
        val colors: List<Int> = if (ink)
            listOf(Th.ink, 0xFFE5484D.toInt(), 0xFFF76B15.toInt(), 0xFFF2B33D.toInt(), 0xFF3FBF7F.toInt(), 0xFF12A594.toInt(), 0xFF3DA5E0.toInt(), 0xFF3E63DD.toInt(), 0xFF8B6CF6.toInt(), 0xFFEF6B9A.toInt())
        else listOf(0xFFFFF3A3.toInt(), 0xFFC9F3D5.toInt(), 0xFFCDE6FF.toInt(), 0xFFFFD3E2.toInt(), 0xFFFFE0B8.toInt(), 0xFFE4D9FF.toInt(), 0)
        for (chunk in colors.chunked(5)) {
            val r = a.hbox()
            for (c in chunk) r.put(FrameLayout(a).apply {
                isClickable = true
                contentDescription = if (ink) L("Text colour", "Couleur du texte") else L("Highlight", "Surlignage")
                background = a.pressable(Color.TRANSPARENT, 14f)
                addView(View(a).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        if (c == 0) { setColor(Color.TRANSPARENT); setStroke(a.dp(2), Th.sub) } else { setColor(c); setStroke(a.dp(1), Th.line) }
                    }
                }, FrameLayout.LayoutParams(a.dp(40), a.dp(40), Gravity.CENTER))
                if (c == 0) addView(a.text("✕", 14f, Th.sub, true), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                setOnClickListener {
                    if (ink) js("cmd('foreColor','${cssColor(c)}')") else js("cmd('hiliteColor','${if (c == 0) "transparent" else cssColor(c)}')")
                    s.dismiss()
                }
            }, 0, a.dp(56), 1f)
            repeat(5 - chunk.size) { r.put(View(a), 0, a.dp(56), 1f) }
            s.body.put(r)
        }
        s.show()
    }

    private fun drawChips() {
        chips.removeAllViews()
        val n = note ?: return
        for (c in Store.cats) chips.put(a.chip(c.name, n.cat == c.id, Color.parseColor(c.color), {
            n.cat = if (n.cat == c.id) null else c.id; Store.touch(n); drawChips()
        }), WRAP, WRAP, 0f, 0, 0, 8, 0)
    }

    // ---- inserts ----
    private fun ins(html: String) { js("ins(" + JSONObject.quote(html) + ")") }

    private fun pickImage() {
        a.pick(arrayOf("image/*")) { uri ->
            if (uri == null) return@pick
            a.toast(L("Adding image…", "Ajout de l’image…"))
            Thread {
                val data = try { imageDataUri(a, uri) } catch (e: Throwable) { null }
                a.post(Runnable { if (data == null) a.toast(L("Could not add this image.", "Impossible d’ajouter cette image.")) else ins("<img src=\"$data\" style=\"max-width:100%;height:auto;border-radius:8px\"/><div><br></div>") })
            }.start()
        }
    }

    /** Size, alignment and delete for the selected picture; the page updates live while the slider moves. */
    private fun imageSheet(start: Int) {
        val s = Sheet(a, L("Picture size", "Taille de l’image"))
        var pct = start.coerceIn(10, 100)
        val label = a.text("$pct%", 16f, Th.accent, true)
        val bar = android.widget.SeekBar(a)
        fun setPct(p: Int, fromBar: Boolean) {
            pct = p.coerceIn(10, 100); label.text = "$pct%"
            if (!fromBar) bar.progress = pct - 10
            js("imgSet($pct)")
        }
        bar.max = 90; bar.progress = pct - 10
        bar.progressTintList = android.content.res.ColorStateList.valueOf(Th.accent); bar.thumbTintList = android.content.res.ColorStateList.valueOf(Th.accent)
        bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) setPct(p + 10, true) }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        s.body.put(a.hbox {
            put(a.text(L("Width", "Largeur"), 15f, Th.ink, true), 0, WRAP, 1f)
            put(label, WRAP, WRAP)
        }, MATCH, WRAP, 0f, 0, 0, 0, 6)
        s.body.put(bar, MATCH, WRAP, 0f, 0, 0, 0, 10)
        val presets = a.hbox()
        for ((v, lbl) in listOf(25 to L("Small", "Petite"), 50 to L("Medium", "Moyenne"), 75 to L("Large", "Grande"), 100 to L("Full", "Pleine")))
            presets.put(a.chip("$lbl", false, 0, { setPct(v, false) }), WRAP, WRAP, 0f, 0, 0, 8, 0)
        s.body.put(a.chipScroller(presets, 0), MATCH, WRAP, 0f, 0, 0, 0, 14)
        s.body.put(a.hbox {
            put(a.iconBtn(R.drawable.ic_align_left, L("Align left", "Aligner à gauche"), Th.ink) { js("imgAlign('left')") })
            put(a.iconBtn(R.drawable.ic_align_center, L("Centre", "Centrer"), Th.ink) { js("imgAlign('center')") })
            put(a.iconBtn(R.drawable.ic_align_right, L("Align right", "Aligner à droite"), Th.ink) { js("imgAlign('right')") })
        }, MATCH, WRAP, 0f, 0, 0, 0, 12)
        s.body.put(a.pill(L("Delete picture", "Supprimer l’image"), false, true) { js("imgDelete()"); s.dismiss() }, MATCH, WRAP, 0f, 0, 0, 0, 8)
        s.body.put(a.pill(L("Done", "Terminé")) { s.dismiss() })
        s.show()
    }

    /** Scans pages (or takes gallery photos when the scanner is unavailable) and puts them into the note as pictures. */
    private fun scanIntoNote() {
        ScanFlow.pick(a) { uris ->
            a.toast(L("Adding pages…", "Ajout des pages…"))
            Thread {
                val sb = StringBuilder()
                for (u in uris) try { sb.append("<img src=\"").append(imageDataUri(a, u)).append("\" style=\"width:100%;height:auto;border-radius:8px\"/><div><br></div>") } catch (e: Throwable) {}
                a.post(Runnable { if (sb.isEmpty()) a.toast(L("Could not read the scanned pages.", "Impossible de lire les pages scannées.")) else ins(sb.toString()) })
            }.start()
        }
    }

    private fun linkSheet() {
        val s = Sheet(a, L("Insert link", "Insérer un lien"))
        val t = a.field(L("Text (optional)", "Texte (facultatif)")); val u = a.field("https://…").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        s.body.put(t, MATCH, WRAP, 0f, 0, 0, 0, 10); s.body.put(u, MATCH, WRAP, 0f, 0, 0, 0, 14)
        s.body.put(a.pill(L("Insert", "Insérer")) {
            var url = u.text.toString().trim()
            if (url.isEmpty()) return@pill
            if (!Regex("^(https?|mailto|tel):", RegexOption.IGNORE_CASE).containsMatchIn(url)) url = if (url.contains("@") && !url.contains("/")) "mailto:$url" else "https://$url"
            ins("<a href=\"${esc(url)}\">${esc(t.text.toString().trim().ifEmpty { url })}</a>&nbsp;"); s.dismiss()
        })
        s.show(); u.requestFocus()
    }

    private fun stepper(label: String, init: Int, min: Int, max: Int, out: IntArray, idx: Int): View {
        val v = a.text(init.toString(), 18f, Th.ink, true).apply { gravity = Gravity.CENTER }
        out[idx] = init
        fun step(d: Int) { out[idx] = Math.max(min, Math.min(max, out[idx] + d)); v.text = out[idx].toString() }
        return a.hbox {
            put(a.text(label, 16f, Th.ink, true), 0, WRAP, 1f)
            put(a.pill("−", false) { step(-1) }.apply { minimumWidth = a.dp(52); setPadding(0, 0, 0, 0) }, a.dp(52), a.dp(44))
            put(v, a.dp(48), WRAP)
            put(a.pill("+", false) { step(1) }.apply { minimumWidth = a.dp(52); setPadding(0, 0, 0, 0) }, a.dp(52), a.dp(44))
        }
    }

    private fun tableSheet() {
        val s = Sheet(a, L("Insert table", "Insérer un tableau")); val v = IntArray(2)
        s.body.put(stepper(L("Rows", "Lignes"), 3, 1, 12, v, 0), MATCH, WRAP, 0f, 0, 0, 0, 10)
        s.body.put(stepper(L("Columns", "Colonnes"), 3, 1, 6, v, 1), MATCH, WRAP, 0f, 0, 0, 0, 16)
        s.body.put(a.pill(L("Insert", "Insérer")) { ins(tableHtml(v[0], v[1])); s.dismiss() }); s.show()
    }

    private fun formulaSheet() {
        val s = Sheet(a, L("Formula", "Formule"))
        val f = a.field("(12 + 8) × 3 / sqrt(16)").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT }
        val res = a.text("", 20f, Th.accent, true)
        s.body.put(f, MATCH, WRAP, 0f, 0, 0, 0, 10)
        s.body.put(a.text(L("Supports + − × ÷ % ^ ( ) sqrt sin cos tan log ln abs round pi e", "Accepte + − × ÷ % ^ ( ) sqrt sin cos tan log ln abs round pi e"), 12f, Th.sub), MATCH, WRAP, 0f, 0, 0, 0, 10)
        s.body.put(res, MATCH, WRAP, 0f, 0, 0, 0, 14)
        var value: String? = null
        fun calc() {
            val e = f.text.toString()
            if (e.isBlank()) { res.text = ""; value = null; return }
            try { value = fmtNum(evalMath(e)); res.text = "= $value"; res.setTextColor(Th.accent) }
            catch (x: MathError) { value = null; res.text = x.message ?: "?"; res.setTextColor(Th.danger) }
            catch (x: Exception) { value = null; res.text = "?"; res.setTextColor(Th.danger) }
        }
        f.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(x: Editable?) { calc() }
            override fun beforeTextChanged(x: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(x: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        s.body.put(a.pill(L("Insert", "Insérer")) { val v = value ?: return@pill; ins(esc(f.text.toString().trim()) + " = <b>" + esc(v) + "</b>&nbsp;"); s.dismiss() })
        s.show(); f.requestFocus()
    }

    private fun chartSheet() {
        val s = Sheet(a, L("Chart", "Graphique")); var type = "bar"
        val types = a.hbox()
        fun drawTypes() {
            types.removeAllViews()
            for ((k, lbl) in listOf("bar" to L("Bars", "Barres"), "line" to L("Line", "Courbe"), "pie" to L("Pie", "Camembert")))
                types.put(a.chip(lbl, type == k, 0, { type = k; drawTypes() }), WRAP, WRAP, 0f, 0, 0, 8, 0)
        }
        drawTypes()
        val t = a.field(L("Title", "Titre")).apply { filters = arrayOf(android.text.InputFilter.LengthFilter(40)) }
        val d = a.field(L("January: 12\nFebruary: 19\nMarch: 8", "Janvier : 12\nFévrier : 19\nMars : 8"), false, 5)
        val err = a.text("", 13f, Th.danger)
        s.body.put(types, MATCH, WRAP, 0f, 0, 0, 0, 12); s.body.put(t, MATCH, WRAP, 0f, 0, 0, 0, 10)
        s.body.put(d, MATCH, WRAP, 0f, 0, 0, 0, 6)
        s.body.put(a.text(L("One per line: Label: value (max 12)", "Une par ligne : Libellé : valeur (12 max)"), 12f, Th.sub), MATCH, WRAP, 0f, 0, 0, 0, 10)
        s.body.put(err, MATCH, WRAP, 0f, 0, 0, 0, 6)
        s.body.put(a.pill(L("Insert", "Insérer")) {
            val pts = parsePts(d.text.toString()).take(12)
            if (pts.isEmpty()) { err.text = L("Add at least one line like “Label: 10”.", "Ajoutez au moins une ligne comme « Libellé : 10 »."); return@pill }
            ins(chartImg(type, t.text.toString().trim().ifEmpty { L("Chart", "Graphique") }, pts)); s.dismiss()
        }); s.show()
    }

    // ---- share ----
    private fun share() {
        val n = note ?: return
        sync {
            val s = Sheet(a, L("Share note", "Partager la note"))
            s.row(R.drawable.ic_doc, L("PDF", "PDF")) { export(true) }
            s.row(R.drawable.ic_doc, L("Word (DOCX)", "Word (DOCX)")) { export(false) }
            s.row(R.drawable.ic_chat, L("Text (email, WhatsApp, Telegram…)", "Texte (e-mail, WhatsApp, Telegram…)")) { Exporter.shareText(a, n) }
            s.row(R.drawable.ic_wifi, L("Send over Wi-Fi", "Envoyer par Wi-Fi")) { a.push(TransferScreen(a, n.id)) }
            s.show()
        }
    }

    private fun export(pdf: Boolean) {
        val n = note ?: return
        a.toast(L("Preparing file…", "Préparation du fichier…"))
        Thread {
            try {
                val f = Exporter.fileFor(a, n, pdf)
                a.post(Runnable { Exporter.shareFile(a, f, if (pdf) Exporter.MIME_PDF else Exporter.MIME_DOCX) })
            } catch (e: Throwable) {
                a.post(Runnable { if (pdf) Exporter.printNote(a, n) else a.toast(L("Export failed.", "L’export a échoué.")) })
            }
        }.start()
    }

    override fun view(): View = root
    override fun onHide() { js("flush()") }

    override fun onBack(): Boolean {
        if (done) return true
        done = true
        sync { a.pop() }
        return true
    }

    override fun onDestroy() {
        val n = note
        if (n != null && n.title.isBlank() && n.text.isBlank() && !n.media && Store.note(n.id) != null) { Store.removeNote(n.id) }
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        web.removeJavascriptInterface("Notia")
        web.destroy()
        Store.flush()
        Store.changed()
    }
}

/** Reads an image, fixes its rotation, shrinks it (max 1280px) and returns a data: URI. */
fun imageDataUri(a: MainActivity, uri: Uri): String {
    val cr = a.contentResolver
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1; while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 1280) s *= 2
    var bmp = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) } ?: throw java.io.IOException("decode")
    val rot = try { cr.openInputStream(uri)!!.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } } catch (e: Exception) { 1 }
    val deg = when (rot) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
    if (deg != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
    val alpha = bmp.hasAlpha()
    val bos = ByteArrayOutputStream()
    bmp.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 78, bos)
    return "data:image/" + (if (alpha) "png" else "jpeg") + ";base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
}
