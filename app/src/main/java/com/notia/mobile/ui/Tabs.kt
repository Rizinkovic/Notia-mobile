package com.notia.mobile.ui

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import com.notia.mobile.BuildConfig
import com.notia.mobile.MainActivity
import com.notia.mobile.R
import com.notia.mobile.core.Note
import com.notia.mobile.core.Store
import com.notia.mobile.core.fmtDate
import com.notia.mobile.sys.Reminders

/** Hosts the five main screens and the floating pill navigation. */
class Tabs(private val a: MainActivity, start: Int) : Screen() {
    private val vault = VaultScreen(a)
    private val screens: List<Screen> = listOf(NotesScreen(a), LibraryScreen(a), TasksScreen(a), vault, SettingsScreen(a))
    private val icons = intArrayOf(R.drawable.ic_doc, R.drawable.ic_book, R.drawable.ic_box_on, R.drawable.ic_lock, R.drawable.ic_settings)
    private val names get() = listOf(L("Notes", "Notes"), L("Library", "Bibliothèque"), L("Tasks", "Tâches"), L("Vault", "Coffre"), L("Settings", "Réglages"))
    private val content = FrameLayout(a)
    private val bar = a.hbox()
    private val frame = FrameLayout(a)
    private var cur = -1
    private var shown = false
    private var initial = start
    private val kb = ViewTreeObserver.OnGlobalLayoutListener {
        val r = Rect(); frame.getWindowVisibleDisplayFrame(r)
        val full = frame.rootView.height
        bar.visibility = if (full - r.bottom > full * 0.15) View.GONE else View.VISIBLE
    }

    init {
        frame.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        bar.background = a.rounded(Th.p.nav, 30f)
        bar.elevation = a.dpf(10f)
        bar.setPadding(a.dp(8), 0, a.dp(8), 0)
        for (i in screens.indices) {
            val b = FrameLayout(a)
            b.contentDescription = names[i]
            b.isClickable = true
            b.setOnClickListener { select(i) }
            b.addView(android.widget.ImageView(a).apply { setImageResource(icons[i]) }, FrameLayout.LayoutParams(a.dp(24), a.dp(24), Gravity.CENTER))
            bar.put(b, 0, a.dp(56), 1f)
        }
        frame.addView(bar, FrameLayout.LayoutParams(MATCH, a.dp(58), Gravity.BOTTOM).also { it.setMargins(a.dp(16), 0, a.dp(16), a.dp(12)) })
        select(initial)
    }

    fun select(i: Int) {
        if (i == cur) return
        if (cur >= 0 && shown) screens[cur].onHide()
        if (cur == 3) a.setSecure(false)
        cur = i
        content.removeAllViews()
        val v = screens[i].view()
        (v.parent as? ViewGroup)?.removeView(v)
        content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        for (k in 0 until bar.childCount) ((bar.getChildAt(k) as FrameLayout).getChildAt(0) as android.widget.ImageView).setColorFilter(if (k == i) Th.p.accent else 0xFF8F8AA8.toInt())
        if (i == 3) a.setSecure(true)
        if (shown) screens[i].onShow()
    }

    fun lockVault() = vault.lock()

    override fun view(): View = frame
    override fun onShow() { shown = true; frame.viewTreeObserver.addOnGlobalLayoutListener(kb); screens[cur].onShow() }
    override fun onHide() { shown = false; if (frame.viewTreeObserver.isAlive) frame.viewTreeObserver.removeOnGlobalLayoutListener(kb); screens[cur].onHide() }
    override fun onDestroy() { screens.forEach { it.onDestroy() } }
    override fun onBack(): Boolean { if (cur != 0) { select(0); return true }; return false }
}

class NotesScreen(private val a: MainActivity) : Screen() {
    private val root = a.vbox()
    private val list = ListView(a)
    private val chips = a.hbox()
    private val empty = a.text("", 15f, Th.sub)
    private var query = ""
    private var cat: String? = null
    private var shown: List<Note> = emptyList()
    private val onData = { refresh() }

    /** Views of one note card, built once and re-filled while scrolling (the old adapter rebuilt every card on every scroll). */
    private class Holder(val root: FrameLayout, val card: LinearLayout, val strip: View, val title: TextView, val body: TextView, val date: TextView)

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val h = (cv?.tag as? Holder) ?: build()
            val n = shown[p]
            h.strip.setBackgroundColor(Store.cats.firstOrNull { it.id == n.cat }?.color?.let { android.graphics.Color.parseColor(it) } ?: Th.line)
            h.title.text = n.title.ifBlank { L("Untitled", "Sans titre") }
            h.body.text = n.text.take(200); h.body.visibility = if (n.text.isEmpty()) View.GONE else View.VISIBLE
            h.date.text = fmtDate(n.modified, true)
            h.card.setOnClickListener { a.push(EditorScreen(a, n.id)) }
            h.card.setOnLongClickListener { a.confirm(L("Delete this note?", "Supprimer cette note ?"), null, L("Delete", "Supprimer")) { Store.removeNote(n.id); Store.changed() }; true }
            return h.root
        }

        private fun build(): Holder {
            val strip = View(a)
            val title = a.text("", 17f, Th.ink, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val body = a.text("", 14f, Th.sub).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f) }
            val date = a.text("", 11f, Th.sub)
            val card = a.hbox {
                background = a.pressable(Th.card, 20f, Th.line); clipRound(20f)
                put(strip, a.dp(5), MATCH)
                put(a.vbox {
                    setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14))
                    put(title); put(body, MATCH, WRAP, 0f, 0, 6, 0, 0); put(date, MATCH, WRAP, 0f, 0, 8, 0, 0)
                }, 0, WRAP, 1f)
                isClickable = true
            }
            val root = FrameLayout(a).apply { setPadding(0, a.dp(5), 0, a.dp(5)); addView(card, FrameLayout.LayoutParams(MATCH, WRAP)) }
            return Holder(root, card, strip, title, body, date).also { root.tag = it }
        }
    }

    init {
        root.setBackgroundColor(Th.bg)
        root.put(a.tabHeader(L("Notes", "Notes"),
            a.iconBtn(R.drawable.ic_scan, L("Scan to PDF", "Scanner en PDF"), Th.ink) { ScanFlow.run(a) },
            a.iconBtn(R.drawable.ic_swap, L("Transfer", "Transfert"), Th.ink) { a.push(TransferScreen(a)) }))
        val search = a.field(L("Search", "Rechercher"))
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { query = s?.toString() ?: ""; refresh() }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        root.put(search, MATCH, WRAP, 0f, 20, 8, 20, 0)
        root.put(a.chipScroller(chips), MATCH, WRAP, 0f, 0, 12, 0, 6)
        list.divider = null; list.dividerHeight = 0; list.clipToPadding = false
        list.setPadding(a.dp(20), a.dp(4), a.dp(20), a.dp(170)); list.selector = android.graphics.drawable.ColorDrawable(0)
        list.adapter = adapter
        val body = FrameLayout(a)
        body.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(empty, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        val fab = FrameLayout(a).apply {
            background = a.rounded(Th.accent, 28f); elevation = a.dpf(8f); isClickable = true; contentDescription = L("New note", "Nouvelle note")
            addView(a.icon(R.drawable.ic_add, Th.accentInk, 26), FrameLayout.LayoutParams(a.dp(26), a.dp(26), Gravity.CENTER))
            setOnClickListener { a.push(EditorScreen(a, Store.addNote(cat).id)) }
        }
        body.addView(fab, FrameLayout.LayoutParams(a.dp(56), a.dp(56), Gravity.BOTTOM or Gravity.END).also { it.setMargins(0, 0, a.dp(24), a.dp(92)) })
        root.put(body, MATCH, 0, 1f)
    }

    private fun refresh() {
        val q = query.trim().lowercase()
        shown = Store.notes.filter { (cat == null || it.cat == cat) && (q.isEmpty() || it.title.lowercase().contains(q) || it.text.lowercase().contains(q)) }
        adapter.notifyDataSetChanged()
        empty.text = if (Store.notes.isEmpty()) L("No notes yet.\nTap + to write your first one.", "Aucune note.\nAppuyez sur + pour écrire la première.") else if (shown.isEmpty()) L("Nothing found.", "Aucun résultat.") else ""
        empty.gravity = Gravity.CENTER
        chips.removeAllViews()
        chips.put(a.chip(L("All", "Toutes"), cat == null, 0, { cat = null; refresh() }), WRAP, WRAP, 0f, 0, 0, 8, 0)
        for (c in Store.cats) chips.put(a.chip(c.name, cat == c.id, android.graphics.Color.parseColor(c.color), { cat = c.id; refresh() }, {
            a.confirm(L("Delete category “${c.name}”?", "Supprimer la catégorie « ${c.name} » ?"), L("Notes stay, they just lose this category.", "Les notes sont conservées, sans catégorie."), L("Delete", "Supprimer")) { if (cat == c.id) cat = null; Store.removeCat(c.id); refresh() }
        }), WRAP, WRAP, 0f, 0, 0, 8, 0)
        chips.put(a.chip("+", false, 0, { addCat() }), WRAP, WRAP)
    }

    private fun addCat() {
        val s = Sheet(a, L("New category", "Nouvelle catégorie"))
        val f = a.field(L("Name", "Nom")); f.filters = arrayOf(android.text.InputFilter.LengthFilter(24))
        s.body.put(f, MATCH, WRAP, 0f, 0, 0, 0, 14)
        s.body.put(a.pill(L("Create", "Créer")) { val n = f.text.toString().trim(); if (n.isNotEmpty() && Store.cats.none { it.name.equals(n, true) }) { Store.addCat(n); refresh() }; s.dismiss() })
        s.show(); f.requestFocus()
    }

    override fun view(): View = root
    override fun onShow() { Store.listen(onData); refresh() }
    override fun onHide() { Store.unlisten(onData) }
}

class SettingsScreen(private val a: MainActivity) : Screen() {
    private val root = a.vbox { setBackgroundColor(Th.bg) }
    private val notifBox = a.vbox()
    companion object {
        const val SITE = "notia.srichard.site"
        const val EMAIL = "rsongrichard@gmail.com"
    }

    init {
        root.put(a.tabHeader(L("Settings", "Réglages")))
        val sv = a.vscroll {
            setPadding(a.dp(20), a.dp(6), a.dp(20), a.dp(120))

            put(cap(L("Appearance", "Apparence")), MATCH, WRAP, 0f, 4, 6, 0, 8)
            put(group {
                put(row(R.drawable.ic_eye, L("Dark mode", "Mode sombre"), null, sw(Store.dark) { c -> Store.dark = c; Store.saveState(); Store.flush(); a.start(4) }, null))
                put(divider(), MATCH, 1, 0f, 52, 0, 0, 0)
                put(a.vbox {
                    setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(14))
                    put(a.text(L("Language", "Langue"), 16f, Th.ink, true), MATCH, WRAP, 0f, 0, 0, 0, 10)
                    put(a.hbox {
                        put(a.chip("English", Store.lang == "en", 0, { setLang("en") }), WRAP, WRAP, 0f, 0, 0, 8, 0)
                        put(a.chip("Français", Store.lang == "fr", 0, { setLang("fr") }))
                    })
                })
            }, MATCH, WRAP, 0f, 0, 0, 0, 18)

            put(cap(L("Reminders", "Rappels")), MATCH, WRAP, 0f, 4, 0, 0, 8)
            put(notifBox, MATCH, WRAP, 0f, 0, 0, 0, 18)

            put(cap(L("About", "À propos")), MATCH, WRAP, 0f, 4, 0, 0, 8)
            put(aboutCard(), MATCH, WRAP, 0f, 0, 0, 0, 10)
            put(group {
                put(row(R.drawable.ic_doc, L("About Notia", "À propos de Notia"), L("What Notia does", "Ce que fait Notia"), chevron()) { aboutSheet() })
                put(divider(), MATCH, 1, 0f, 52, 0, 0, 0)
                put(row(R.drawable.ic_lock, L("Privacy policy", "Politique de confidentialité"), L("Your data stays on this phone", "Vos données restent sur ce téléphone"), chevron()) { privacySheet() })
            }, MATCH, WRAP, 0f, 0, 0, 0, 18)

            // every way to reach the developer, in one place
            put(cap(L("Contact", "Contact")), MATCH, WRAP, 0f, 4, 0, 0, 8)
            put(group {
                put(row(R.drawable.ic_heart, L("Support Developer", "Soutenir le développeur"), L("Help keep Notia free and improving", "Aidez Notia à rester gratuit et à s’améliorer"), chevron()) { openUrl("https://srichard.site/donate") })
                put(divider(), MATCH, 1, 0f, 52, 0, 0, 0)
                put(row(R.drawable.ic_pen, L("Developer", "Développeur"), "Song Richard  •  srichard.site", chevron()) { openUrl("https://srichard.site") })
                put(divider(), MATCH, 1, 0f, 52, 0, 0, 0)
                put(row(R.drawable.ic_link, L("Website", "Site web"), SITE, chevron()) { openUrl("https://$SITE") })
                put(divider(), MATCH, 1, 0f, 52, 0, 0, 0)
                put(row(R.drawable.ic_chat, L("Email", "E-mail"), EMAIL, chevron()) { mail() })
            }, MATCH, WRAP, 0f, 0, 0, 0, 16)
            put(a.text(L("© 2026 Notia. All rights reserved.", "© 2026 Notia. Tous droits réservés."), 12f, Th.sub).apply { gravity = Gravity.CENTER }, MATCH, WRAP, 0f, 0, 4, 0, 0)
        }
        root.put(sv, MATCH, 0, 1f)
        drawNotifs()
    }

    // ---------- building blocks ----------
    private fun cap(t: String): View = a.text(t.uppercase(), 12f, Th.sub, true).apply { letterSpacing = 0.08f }

    private fun group(build: LinearLayout.() -> Unit): LinearLayout = a.vbox { background = a.rounded(Th.card, 20f, Th.line); clipRound(20f); build() }

    private fun divider(): View = View(a).apply { setBackgroundColor(Th.line) }

    private fun chevron(): View = a.text("›", 24f, Th.sub)

    private fun sw(on: Boolean, change: (Boolean) -> Unit): Switch = Switch(a).apply {
        isChecked = on
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), 0xFFB8B4CC.toInt()))
        trackTintList = ColorStateList(states, intArrayOf(Th.accent, Th.line))
        setOnCheckedChangeListener { _, c -> change(c) }
    }

    /** One settings row: accent icon, title + optional sub-line, optional trailing control, optional tap action. */
    private fun row(res: Int, title: String, sub: String?, trailing: View?, onClick: (() -> Unit)?): View = a.hbox {
        minimumHeight = a.dp(60); setPadding(a.dp(16), a.dp(10), a.dp(14), a.dp(10))
        if (onClick != null) { background = a.pressable(android.graphics.Color.TRANSPARENT, 0f); isClickable = true; setOnClickListener { onClick() } }
        put(a.icon(res, Th.accent, 22), a.dp(22), a.dp(22), 0f, 0, 0, 14, 0)
        put(a.vbox {
            put(a.text(title, 16f, Th.ink, true))
            if (sub != null) put(a.text(sub, 12.5f, Th.sub).apply { setLineSpacing(0f, 1.1f) }, MATCH, WRAP, 0f, 0, 3, 0, 0)
        }, 0, WRAP, 1f)
        if (trailing != null) put(trailing, WRAP, WRAP, 0f, 10, 0, 0, 0)
    }

    // ---------- reminders ----------
    private fun drawNotifs() {
        notifBox.removeAllViews()
        if (!Reminders.enabled(a)) {
            notifBox.put(a.vbox {
                background = a.rounded(Th.field, 16f); setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12))
                put(a.text(L("Notifications are turned off for Notia, so no reminder can reach you.", "Les notifications sont désactivées pour Notia : aucun rappel ne pourra vous parvenir."), 13f, Th.ink).apply { setLineSpacing(0f, 1.15f) })
                put(a.text(L("Open settings", "Ouvrir les réglages"), 14f, Th.accent, true).apply {
                    setPadding(0, a.dp(8), 0, a.dp(4)); isClickable = true
                    setOnClickListener { if (Build.VERSION.SDK_INT >= 33 && !Reminders.hasPermission(a)) Reminders.ask(a) { drawNotifs() } else Reminders.openNotificationSettings(a) }
                }, WRAP, WRAP)
            }, MATCH, WRAP, 0f, 0, 0, 0, 10)
        }
        notifBox.put(group {
            put(row(R.drawable.ic_wifi, L("Come-back reminder", "Rappel de retour"), L("A gentle nudge after 3, 7, 14 and 30 days without opening Notia", "Un petit rappel après 3, 7, 14 et 30 jours sans ouvrir Notia"),
                sw(Store.remInactive) { c -> Store.remInactive = c; Store.saveState(); Reminders.scheduleIdle(a); if (c) Reminders.ask(a) { drawNotifs() } else drawNotifs() }, null))
            put(divider(), MATCH, 1, 0f, 52, 0, 0, 0)
            put(row(R.drawable.ic_book, L("Finish your book", "Terminez votre livre"), L("Evening reminder (19:00) for the book you are reading", "Rappel le soir (19:00) pour le livre en cours"),
                sw(Store.remBooks) { c -> Store.remBooks = c; Store.saveState(); Reminders.scheduleReading(a); if (c) Reminders.ask(a) { drawNotifs() } else drawNotifs() }, null))
        })
    }

    // ---------- about ----------
    private fun aboutCard(): View = a.hbox {
        background = a.rounded(Th.card, 20f, Th.line); setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16))
        put(android.widget.ImageView(a).apply { setImageResource(R.drawable.logo); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; clipRound(14f) }, a.dp(56), a.dp(56), 0f, 0, 0, 14, 0)
        put(a.vbox {
            put(a.text("Notia", 20f, Th.ink, true))
            put(a.text(L("Version ", "Version ") + BuildConfig.VERSION_NAME + "  (build " + BuildConfig.VERSION_CODE + ")", 13f, Th.sub), MATCH, WRAP, 0f, 0, 4, 0, 0)
            put(a.text(L("Notes, tasks, books and a vault. Private and offline.", "Notes, tâches, livres et coffre. Privé et hors ligne."), 12.5f, Th.sub).apply { setLineSpacing(0f, 1.15f) }, MATCH, WRAP, 0f, 0, 6, 0, 0)
        }, 0, WRAP, 1f)
    }

    private fun docSheet(title: String, parts: List<Pair<String?, String>>) {
        val s = Sheet(a, title)
        for ((h, body) in parts) {
            if (h != null) s.body.put(a.text(h, 15f, Th.accent, true), MATCH, WRAP, 0f, 0, 10, 0, 4)
            s.body.put(a.text(body, 14f, Th.ink).apply { setLineSpacing(0f, 1.25f) }, MATCH, WRAP, 0f, 0, 0, 0, 4)
        }
        s.body.put(a.pill(L("Close", "Fermer"), false) { s.dismiss() }, MATCH, WRAP, 0f, 0, 16, 0, 0)
        s.show()
    }

    private fun aboutSheet() = docSheet(L("About Notia", "À propos de Notia"), listOf(
        null to L("Notia is a calm, private place for everything you want to remember: notes, tasks, books and passwords. Everything works offline and stays on your phone.",
            "Notia est un endroit calme et privé pour tout ce que vous voulez retenir : notes, tâches, livres et mots de passe. Tout fonctionne hors ligne et reste sur votre téléphone."),
        L("Notes", "Notes") to L("A rich editor with headings, lists, colours, highlights, images, links, tables, formulas and charts. Organise with categories, search, and export or share as PDF, Word or text.",
            "Un éditeur riche : titres, listes, couleurs, surlignages, images, liens, tableaux, formules et graphiques. Catégories, recherche, export et partage en PDF, Word ou texte."),
        L("Tasks", "Tâches") to L("Add a task with a date and time and Notia rings like an alarm, with Done and Snooze buttons.",
            "Ajoutez une tâche avec date et heure : Notia sonne comme une alarme, avec les boutons Terminé et Reporter."),
        L("Library", "Bibliothèque") to L("Read PDF, EPUB, MOBI, DOCX, TXT and HTML books with progress, reading time, bookmarks, highlights and a table of contents.",
            "Lisez vos livres PDF, EPUB, MOBI, DOCX, TXT et HTML avec progression, temps de lecture, marque-pages, surlignages et sommaire."),
        L("Vault", "Coffre") to L("Store passwords encrypted with a PIN or password that only you know.",
            "Gardez vos mots de passe chiffrés par un code ou un mot de passe que vous seul connaissez."),
        L("Wi-Fi transfer", "Transfert Wi-Fi") to L("Send notes between your devices on the same Wi-Fi, or sync from Notia Desktop with a QR code.",
            "Envoyez des notes entre vos appareils sur le même Wi-Fi, ou synchronisez depuis Notia Desktop avec un QR code."),
    ))

    private fun privacySheet() = docSheet(L("Privacy policy", "Politique de confidentialité"), listOf(
        null to L("Last updated: November 2026", "Dernière mise à jour : novembre 2026"),
        L("In short", "En bref") to L("Notia has no account, no analytics, no tracking and no ads. We do not collect your data and we cannot see it. Your notes, tasks, books and passwords stay on your phone.",
            "Notia n’a ni compte, ni statistiques, ni suivi, ni publicité. Nous ne collectons pas vos données et ne pouvons pas les voir. Vos notes, tâches, livres et mots de passe restent sur votre téléphone."),
        L("What is stored on your phone", "Ce qui est stocké sur votre téléphone") to L("Notes (text, images, tables), categories, tasks and their reminder times, the books you add with covers, reading progress, bookmarks and highlights, your settings, and your vault entries. All of it lives in Notia's private storage, which other apps cannot read. Notia disables Android cloud backup, so uninstalling the app permanently deletes this data.",
            "Notes (texte, images, tableaux), catégories, tâches et heures de rappel, livres ajoutés avec couvertures, progression de lecture, marque-pages et surlignages, réglages et entrées du coffre. Tout se trouve dans le stockage privé de Notia, inaccessible aux autres applications. Notia désactive la sauvegarde cloud d’Android : désinstaller l’application supprime définitivement ces données."),
        L("Vault", "Coffre") to L("Vault entries are encrypted with AES-256-GCM using a key held in the Android Keystore and protected by your PIN or password. If you forget it, the vault cannot be recovered. It can only be reset, which deletes its content.",
            "Les entrées du coffre sont chiffrées en AES-256-GCM avec une clé conservée dans l’Android Keystore et protégée par votre code ou mot de passe. En cas d’oubli, le coffre est irrécupérable : on ne peut que le réinitialiser, ce qui supprime son contenu."),
        L("Network use", "Utilisation du réseau") to L("Notia connects to a network only when you start a Wi-Fi transfer or a desktop sync, and only to devices on your local network. Transfers use a short code and are not encrypted, so use trusted Wi-Fi. Links you tap open in your browser. Scanning a QR code uses Google Play services' code scanner, which processes the camera image on your device; Notia only receives the text of the code and does not request the camera permission.",
            "Notia se connecte à un réseau uniquement lorsque vous lancez un transfert Wi-Fi ou une synchronisation avec le bureau, et seulement vers des appareils de votre réseau local. Les transferts utilisent un court code et ne sont pas chiffrés : utilisez un Wi-Fi de confiance. Les liens touchés s’ouvrent dans votre navigateur. Le scan de QR code utilise le lecteur des services Google Play, qui traite l’image sur votre appareil ; Notia ne reçoit que le texte du code et ne demande pas l’autorisation de la caméra."),
        L("Permissions", "Autorisations") to L("Notifications and alarms: to ring your task reminders and send the reminders you enable in Settings. Start at boot: to restore reminders after a restart. Internet: for Wi-Fi transfer only. Reminders are created on your phone; no server is involved.",
            "Notifications et alarmes : pour faire sonner vos rappels de tâches et envoyer ceux que vous activez dans les réglages. Démarrage : pour restaurer les rappels après un redémarrage. Internet : uniquement pour le transfert Wi-Fi. Les rappels sont créés sur votre téléphone, sans aucun serveur."),
        L("Sharing and export", "Partage et export") to L("When you share or export a note, the file goes to the app you choose. What that app does with it is covered by its own policy.",
            "Lorsque vous partagez ou exportez une note, le fichier est transmis à l’application que vous choisissez. Son traitement dépend de la politique de cette application."),
        L("Your control", "Votre contrôle") to L("You can delete any note, task, book or password inside the app, reset the vault, and turn every reminder off in Settings.",
            "Vous pouvez supprimer toute note, tâche, livre ou mot de passe dans l’application, réinitialiser le coffre et désactiver chaque rappel dans les réglages."),
        L("Changes and contact", "Modifications et contact") to L("If this policy changes, the new version will appear here. Questions: $EMAIL or $SITE.",
            "Si cette politique change, la nouvelle version apparaîtra ici. Questions : $EMAIL ou $SITE."),
    ))

    private fun openUrl(u: String) { try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) { a.toast(L("No app can open this link.", "Aucune application ne peut ouvrir ce lien.")) } }

    private fun mail() {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
            .putExtra(Intent.EXTRA_SUBJECT, "Notia ${BuildConfig.VERSION_NAME}")
            .putExtra(Intent.EXTRA_TEXT, "\n\n—\nNotia ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}")
        try { a.startActivity(i) } catch (e: Exception) { a.toast(L("No email app found. Write to $EMAIL", "Aucune application e-mail. Écrivez à $EMAIL")) }
    }

    private fun setLang(l: String) { if (Store.lang == l) return; Store.lang = l; Store.saveState(); Store.flush(); Reminders.channels(a); a.start(4) }

    override fun view(): View = root
    override fun onShow() { drawNotifs() }
}
