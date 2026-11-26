package com.notia.mobile.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.content.res.ColorStateList
import com.notia.mobile.MainActivity
import com.notia.mobile.R
import com.notia.mobile.core.IncomingNote
import com.notia.mobile.core.Md
import com.notia.mobile.core.Store
import com.notia.mobile.core.Task
import com.notia.mobile.core.fmtDate
import com.notia.mobile.core.newId
import com.notia.mobile.core.sanitize
import com.notia.mobile.net.Net
import com.notia.mobile.sys.Qr
import com.notia.mobile.sys.Reminders
import com.notia.mobile.sys.Vault
import com.notia.mobile.sys.VaultEntry
import java.io.File
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private fun dialogTheme() = if (Store.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

/** Alarm-time chooser shared by the "new task" form and the "edit task" sheet. */
private class DuePicker(private val a: MainActivity, initial: Long?) {
    val row = a.hbox()
    var due: Long? = initial
    private var key = if (initial != null) "custom" else "none"   // none | 1h | tonight | tomorrow | time | custom

    private fun now() = System.currentTimeMillis()
    private fun at(hour: Int, addDays: Int): Long = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, addDays); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    fun clear() { due = null; key = "none"; draw() }

    fun draw() {
        row.removeAllViews()
        fun c(label: String, k: String, value: () -> Long?) {
            row.put(a.chip(label, key == k, 0, { due = value(); key = k; draw() }), WRAP, WRAP, 0f, 0, 0, 8, 0)
        }
        c(L("No alarm", "Sans alarme"), "none") { null }
        c(L("In 1 hour", "Dans 1 h"), "1h") { now() + 3_600_000L }
        val today = at(20, 0) > now() + 60_000
        c(if (today) L("Tonight 20:00", "Ce soir 20:00") else L("Tomorrow 20:00", "Demain 20:00"), "tonight") { if (today) at(20, 0) else at(20, 1) }
        c(L("Tomorrow 09:00", "Demain 09:00"), "tomorrow") { at(9, 1) }
        val timed = key == "time" && due != null
        row.put(a.chip(if (timed) "⏰ " + fmtDate(due!!, true) else L("⏰ Pick a time", "⏰ Choisir l’heure"), timed, 0, { pickTime() }), WRAP, WRAP, 0f, 0, 0, 8, 0)
        val custom = key == "custom" && due != null
        row.put(a.chip(if (custom) "📅 " + fmtDate(due!!, true) else L("📅 Pick date & time", "📅 Choisir date et heure"), custom, 0, { pickDateTime() }), WRAP, WRAP)
    }

    /** Just a time of day, e.g. 8 PM: rings today if that time is still ahead, otherwise tomorrow. */
    private fun pickTime() {
        val c = Calendar.getInstance().apply { if (due != null) timeInMillis = due!! else add(Calendar.HOUR_OF_DAY, 1) }
        TimePickerDialog(a, dialogTheme(), { _, h, m ->
            val t = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            if (t.timeInMillis <= now() + 5_000) t.add(Calendar.DAY_OF_YEAR, 1)
            due = t.timeInMillis; key = "time"; draw()
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(a)).show()
    }

    private fun pickDateTime() {
        val c = Calendar.getInstance().apply { if (due != null) timeInMillis = due!! }
        DatePickerDialog(a, dialogTheme(), { _, y, mo, d ->
            TimePickerDialog(a, dialogTheme(), { _, h, mi ->
                val t = Calendar.getInstance().apply { set(y, mo, d, h, mi, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                if (t <= now()) a.toast(L("Choose a time in the future.", "Choisissez une heure dans le futur.")) else { due = t; key = "custom"; draw() }
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(a)).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }
}

class TasksScreen(private val a: MainActivity) : Screen() {
    private val root = a.vbox { setBackgroundColor(Th.bg) }
    private val listBox = a.vbox()
    private val warn = a.vbox()
    private val input = a.field(L("New task", "Nouvelle tâche"))
    private val picker = DuePicker(a, null)
    private val onData = { render() }

    init {
        root.put(a.tabHeader(L("Tasks", "Tâches")))
        val sv = a.vscroll {
            setPadding(a.dp(20), a.dp(6), a.dp(20), a.dp(120))
            put(warn)
            put(a.hbox {
                put(input, 0, WRAP, 1f)
                put(a.pill(L("Add", "Ajouter")) { add() }, WRAP, a.dp(48), 0f, 8, 0, 0, 0)
            })
            put(a.chipScroller(picker.row, 0), MATCH, WRAP, 0f, 0, 10, 0, 14)
            put(listBox)
        }
        input.setOnEditorActionListener { _, _, _ -> add(); true }
        root.put(sv, MATCH, 0, 1f)
    }

    private fun hideKeyboard() {
        (a.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    /** Schedules the alarm for [k] (asking for the notification permission first when needed). */
    private fun arm(k: Task, announce: Boolean) {
        val d = k.due ?: return
        if (d <= System.currentTimeMillis()) return
        Reminders.ask(a) { ok ->
            if (ok) {
                k.armed = true; Reminders.schedule(a, k.id, d)
                if (announce) a.toast(L("Alarm set for ", "Alarme réglée pour le ") + fmtDate(d, true))
            } else {
                k.armed = false
                a.toast(L("Saved, but notifications are off so it will not ring.", "Enregistré, mais les notifications sont désactivées : aucune sonnerie."))
            }
            Store.saveTasks(); render()
        }
    }

    private fun add() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) { a.toast(L("Type a task first.", "Écrivez d’abord une tâche.")); return }
        val d = picker.due
        if (d != null && d <= System.currentTimeMillis()) {
            a.toast(L("That time has already passed. Pick a later one.", "Cette heure est déjà passée. Choisissez-en une plus tardive."))
            picker.clear(); return
        }
        val k = Task(newId(), t.take(200), d, false)
        Store.tasks.add(k)
        input.setText(""); picker.clear(); hideKeyboard()
        Store.saveTasks(); render()
        if (d == null) a.toast(L("Task added.", "Tâche ajoutée.")) else arm(k, true)
    }

    private fun toggle(k: Task) {
        k.done = !k.done
        if (k.done) { Reminders.cancel(a, k.id); k.armed = false }
        else arm(k, false)
        Store.saveTasks(); render()
    }

    /** Tap a task to rename it or to add, change or remove its alarm. */
    private fun edit(k: Task) {
        val s = Sheet(a, L("Edit task", "Modifier la tâche"))
        val title = a.field(L("Task", "Tâche")).apply { setText(k.title) }
        val p = DuePicker(a, k.due); p.draw()
        s.body.put(title, MATCH, WRAP, 0f, 0, 0, 0, 14)
        s.body.put(a.text(L("Alarm", "Alarme"), 13f, Th.sub, true), MATCH, WRAP, 0f, 2, 0, 0, 8)
        s.body.put(a.chipScroller(p.row, 0), MATCH, WRAP, 0f, 0, 0, 0, 18)
        s.body.put(a.pill(L("Save", "Enregistrer")) {
            val t = title.text.toString().trim()
            if (t.isEmpty()) { a.toast(L("Type a task first.", "Écrivez d’abord une tâche.")); return@pill }
            val d = p.due
            if (d != null && d != k.due && d <= System.currentTimeMillis()) { a.toast(L("That time has already passed. Pick a later one.", "Cette heure est déjà passée. Choisissez-en une plus tardive.")); return@pill }
            k.title = t.take(200)
            if (d != k.due) { Reminders.cancel(a, k.id); k.armed = false; k.due = d }
            s.dismiss(); Store.saveTasks(); render()
            if (!k.done && d != null && !k.armed) arm(k, true)
        }, MATCH, WRAP)
        s.show()
    }

    private fun render() {
        warn.removeAllViews()
        val hasDue = Store.tasks.any { !it.done && it.due != null && it.due!! > System.currentTimeMillis() }
        if (hasDue && !Reminders.enabled(a)) warn.put(note(L("Notifications are off for Notia, so alarms will not ring.", "Les notifications sont désactivées pour Notia : les alarmes ne sonneront pas."), L("Open settings", "Ouvrir les réglages")) { Reminders.openNotificationSettings(a) }, MATCH, WRAP, 0f, 0, 0, 0, 12)
        listBox.removeAllViews()
        val sorted = Store.tasks.sortedWith(compareBy<Task>({ it.done }, { it.due ?: Long.MAX_VALUE }))
        if (sorted.isEmpty()) listBox.put(a.text(L("No tasks yet.\nType one above, pick an alarm time if you like, then tap Add.", "Aucune tâche.\nÉcrivez-en une ci-dessus, choisissez une heure d’alarme si vous voulez, puis touchez Ajouter."), 15f, Th.sub).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }, MATCH, WRAP, 0f, 0, 40, 0, 0)
        else listBox.put(a.text(L("${sorted.count { !it.done }} to do  •  ${sorted.count { it.done }} done", "${sorted.count { !it.done }} à faire  •  ${sorted.count { it.done }} terminée(s)"), 12f, Th.sub, true), MATCH, WRAP, 0f, 4, 0, 0, 8)
        for (k in sorted) listBox.put(a.hbox {
            background = a.pressable(Th.card, 18f, Th.line); setPadding(a.dp(6), a.dp(4), a.dp(6), a.dp(4)); minimumHeight = a.dp(60)
            isClickable = true; setOnClickListener { edit(k) }
            put(a.iconBtn(if (k.done) R.drawable.ic_check_circle else R.drawable.ic_circle, L("Done", "Terminé"), if (k.done) Th.accent else Th.sub, 24) { toggle(k) })
            put(a.vbox {
                put(a.text(k.title, 16f, if (k.done) Th.sub else Th.ink, true).apply { if (k.done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG })
                if (k.due != null) {
                    val late = !k.done && k.due!! < System.currentTimeMillis()
                    put(a.text((if (k.armed && !k.done) "⏰ " else "") + fmtDate(k.due!!, true) + (if (late) "  •  " + L("overdue", "en retard") else ""), 12f, if (late) Th.danger else Th.sub), MATCH, WRAP, 0f, 0, 3, 0, 0)
                }
            }, 0, WRAP, 1f, 4, 0, 4, 0)
            put(a.iconBtn(R.drawable.ic_delete, L("Delete", "Supprimer"), Th.sub, 20) { Reminders.cancel(a, k.id); Store.tasks.remove(k); Store.saveTasks(); render() })
        }, MATCH, WRAP, 0f, 0, 0, 0, 8)
    }

    private fun note(msg: String, action: String, onClick: () -> Unit): View = a.vbox {
        background = a.rounded(Th.field, 16f); setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12))
        put(a.text(msg, 13f, Th.ink).apply { setLineSpacing(0f, 1.15f) })
        put(a.text(action, 14f, Th.accent, true).apply { isClickable = true; setOnClickListener { onClick() }; setPadding(0, a.dp(8), 0, a.dp(4)) }, WRAP, WRAP)
    }

    override fun view(): View = root
    override fun onShow() { Store.listen(onData); picker.draw(); render() }
    override fun onHide() { Store.unlisten(onData); Store.saveTasks() }
}

class VaultScreen(private val a: MainActivity) : Screen() {
    private val root = FrameLayout(a).apply { setBackgroundColor(Th.bg) }
    private var entries: MutableList<VaultEntry>? = null
    private val revealed = HashSet<String>()
    private var visible = false

    private fun render() {
        root.removeAllViews()
        val v = if (!Vault.isSet(a)) setup() else if (entries == null) locked() else unlocked()
        root.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun centered(build: LinearLayout.() -> Unit): View = a.vscroll {
        setPadding(a.dp(28), a.dp(60), a.dp(28), a.dp(120)); gravity = Gravity.CENTER_HORIZONTAL
        put(a.icon(R.drawable.ic_lock, Th.accent, 48).also { (it.layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL }, a.dp(48), a.dp(48), 0f, 0, 0, 0, 16)
        build()
    }

    private fun pw(hint: String) = a.field(hint).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; typeface = android.graphics.Typeface.DEFAULT }

    private fun setup(): View = centered {
        put(a.text(L("Create your vault", "Créez votre coffre"), 24f, Th.ink, true).apply { gravity = Gravity.CENTER })
        put(a.text(L("Choose a 5-digit PIN, or a password of 6+ characters with letters and numbers. Your passwords are encrypted on this phone.", "Choisissez un code à 5 chiffres, ou un mot de passe de 6 caractères minimum avec lettres et chiffres. Vos mots de passe sont chiffrés sur ce téléphone."), 14f, Th.sub).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }, MATCH, WRAP, 0f, 0, 10, 0, 18)
        val p1 = pw(L("PIN or password", "Code ou mot de passe")); val p2 = pw(L("Repeat", "Répéter"))
        val err = a.text("", 13f, Th.danger).apply { gravity = Gravity.CENTER }
        put(p1, MATCH, WRAP, 0f, 0, 0, 0, 10); put(p2, MATCH, WRAP, 0f, 0, 0, 0, 10); put(err, MATCH, WRAP, 0f, 0, 0, 0, 10)
        put(a.pill(L("Create vault", "Créer le coffre")) {
            val s = p1.text.toString()
            if (!Vault.valid(s)) err.text = L("Use 5 digits, or 6+ characters with a letter and a number.", "5 chiffres, ou 6 caractères minimum avec une lettre et un chiffre.")
            else if (s != p2.text.toString()) err.text = L("The two entries differ.", "Les deux saisies diffèrent.")
            else { Vault.setSecret(a, s); entries = ArrayList(); render() }
        })
    }

    private fun locked(): View = centered {
        put(a.text(L("Vault locked", "Coffre verrouillé"), 24f, Th.ink, true).apply { gravity = Gravity.CENTER }, MATCH, WRAP, 0f, 0, 0, 0, 18)
        val p = pw(L("PIN or password", "Code ou mot de passe"))
        val err = a.text("", 13f, Th.danger).apply { gravity = Gravity.CENTER }
        fun go() {
            val w = Vault.waitSeconds(a)
            if (w > 0) { err.text = L("Too many attempts. Try again in ${w}s.", "Trop d’essais. Réessayez dans ${w} s."); return }
            if (Vault.verify(a, p.text.toString())) { entries = Vault.load(a); render() }
            else { p.setText(""); val w2 = Vault.waitSeconds(a); err.text = if (w2 > 0) L("Too many attempts. Try again in ${w2}s.", "Trop d’essais. Réessayez dans ${w2} s.") else L("Wrong PIN or password.", "Code ou mot de passe incorrect.") }
        }
        p.setOnEditorActionListener { _, _, _ -> go(); true }
        put(p, MATCH, WRAP, 0f, 0, 0, 0, 10); put(err, MATCH, WRAP, 0f, 0, 0, 0, 10)
        put(a.pill(L("Unlock", "Déverrouiller")) { go() })
        put(a.text(L("Forgot it? Reset the vault", "Oublié ? Réinitialiser le coffre"), 13f, Th.sub).apply {
            gravity = Gravity.CENTER; setPadding(0, a.dp(14), 0, a.dp(14)); isClickable = true
            setOnClickListener { a.confirm(L("Reset the vault?", "Réinitialiser le coffre ?"), L("All saved passwords will be permanently deleted.", "Tous les mots de passe enregistrés seront définitivement supprimés."), L("Reset", "Réinitialiser")) {
                File(a.filesDir, "vault.json").delete(); File(a.filesDir, "vault.bin").delete(); entries = null; render() } }
        }, MATCH, WRAP, 0f, 0, 14, 0, 0)
    }

    private fun copy(text: String) {
        val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("password", text)
        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        cm.setPrimaryClip(clip)
        a.toast(L("Copied. Cleared in 30 s.", "Copié. Effacé dans 30 s."))
        Handler(Looper.getMainLooper()).postDelayed({ try { if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", "")) } catch (e: Exception) {} }, 30_000)
    }

    private fun unlocked(): View {
        val list = entries!!
        val col = a.vbox()
        col.put(a.tabHeader(L("Vault", "Coffre"), a.iconBtn(R.drawable.ic_lock, L("Lock", "Verrouiller"), Th.ink) { lock() }))
        col.put(a.vscroll {
            setPadding(a.dp(20), a.dp(8), a.dp(20), a.dp(120))
            put(a.pill(L("Add password", "Ajouter un mot de passe")) { edit(null) }, MATCH, WRAP, 0f, 0, 0, 0, 14)
            if (list.isEmpty()) put(a.text(L("No passwords saved.", "Aucun mot de passe enregistré."), 15f, Th.sub).apply { gravity = Gravity.CENTER }, MATCH, WRAP, 0f, 0, 30, 0, 0)
            for (e in list) put(a.hbox {
                background = a.pressable(Th.card, 18f, Th.line); setPadding(a.dp(16), a.dp(12), a.dp(6), a.dp(12))
                isClickable = true; setOnClickListener { edit(e) }   // tap the card to edit
                put(a.vbox {
                    put(a.text(e.site.ifBlank { "—" }, 16f, Th.ink, true))
                    if (e.user.isNotBlank()) put(a.text(e.user, 13f, Th.sub), MATCH, WRAP, 0f, 0, 3, 0, 0)
                    put(a.text(if (e.id in revealed) e.pass else "••••••••", 14f, Th.accent).apply { typeface = android.graphics.Typeface.MONOSPACE }, MATCH, WRAP, 0f, 0, 6, 0, 0)
                }, 0, WRAP, 1f)
                put(a.iconBtn(if (e.id in revealed) R.drawable.ic_eye_off else R.drawable.ic_eye, L("Show", "Afficher"), Th.sub, 20) { if (!revealed.add(e.id)) revealed.remove(e.id); render() })
                put(a.iconBtn(R.drawable.ic_doc, L("Copy", "Copier"), Th.sub, 20) { copy(e.pass) })
                put(a.iconBtn(R.drawable.ic_delete, L("Delete", "Supprimer"), Th.danger, 20) {
                    a.confirm(L("Delete this password?", "Supprimer ce mot de passe ?"), e.site, L("Delete", "Supprimer")) { list.remove(e); Vault.save(a, list); render() }
                })
            }, MATCH, WRAP, 0f, 0, 0, 0, 8)
        }, MATCH, 0, 1f)
        return col
    }

    private fun edit(e: VaultEntry?) {
        val s = Sheet(a, if (e == null) L("New password", "Nouveau mot de passe") else L("Edit password", "Modifier"))
        val site = a.field(L("Site or app", "Site ou application")).apply { setText(e?.site ?: "") }
        val user = a.field(L("Username or email", "Identifiant ou e-mail")).apply { setText(e?.user ?: "") }
        val pass = a.field(L("Password", "Mot de passe")).apply { setText(e?.pass ?: "") }
        s.body.put(site, MATCH, WRAP, 0f, 0, 0, 0, 10); s.body.put(user, MATCH, WRAP, 0f, 0, 0, 0, 10); s.body.put(pass, MATCH, WRAP, 0f, 0, 0, 0, 14)
        s.body.put(a.pill(L("Save", "Enregistrer")) {
            if (pass.text.isNullOrEmpty()) return@pill
            val list = entries ?: return@pill
            if (e == null) list.add(0, VaultEntry(newId(), site.text.toString().trim(), user.text.toString().trim(), pass.text.toString()))
            else { e.site = site.text.toString().trim(); e.user = user.text.toString().trim(); e.pass = pass.text.toString() }
            Vault.save(a, list); s.dismiss(); render()
        }); s.show()
    }

    fun lock() { if (entries != null) { entries = null; revealed.clear(); if (visible) render() } }

    override fun view(): View = root
    override fun onShow() { visible = true; render() }
    override fun onHide() { visible = false; lock() }
}

class TransferScreen(private val a: MainActivity, pre: String? = null) : Screen() {
    private val root = a.vbox { setBackgroundColor(Th.bg) }
    private val selected = HashSet<String>().apply { if (pre != null) add(pre) }
    private val notesBox = a.vbox()
    private val report = a.text("", 13f, Th.sub)
    private val rxInfo = a.text("", 20f, Th.accent, true)
    private val rxCount = a.text("", 13f, Th.sub)
    private var server: Net.Server? = null
    private var received = 0
    private var busy = false

    init {
        root.put(a.pageHeader({ a.pop() }, a.text(L("Wi-Fi transfer", "Transfert Wi-Fi"), 22f, Th.ink, true)))
        val sv = a.vscroll {
            setPadding(a.dp(20), a.dp(14), a.dp(20), a.dp(40))
            // desktop sync
            put(section(L("Sync from desktop", "Synchroniser depuis le bureau")) {
                put(a.text(L("On Notia Desktop open Share → “Sync notes to phone (QR)”, then scan the code. One scan is enough. Both devices must be on the same Wi-Fi.", "Sur Notia Desktop ouvrez Partager → « Synchroniser vers le téléphone (QR) », puis scannez le code. Un seul scan suffit. Les deux appareils doivent être sur le même Wi-Fi."), 13f, Th.sub).apply { setLineSpacing(0f, 1.15f) }, MATCH, WRAP, 0f, 0, 0, 0, 12)
                put(a.pill(L("Scan desktop QR code", "Scanner le QR code du bureau")) { scan() })
                val link = a.field(L("…or paste the link under the QR code", "…ou collez le lien sous le QR code"))
                put(link, MATCH, WRAP, 0f, 0, 10, 0, 0)
                put(a.pill(L("Use link", "Utiliser le lien"), false) { handle(link.text.toString()) }, MATCH, WRAP, 0f, 0, 8, 0, 0)
            }, MATCH, WRAP, 0f, 0, 0, 0, 14)
            // send
            put(section(L("Send notes", "Envoyer des notes")) {
                put(notesBox)
                put(a.text(L("To the desktop: tick notes, then scan the QR code of Notia Desktop → Share → “Receive notes from phone”.", "Vers le bureau : cochez des notes, puis scannez le QR code de Notia Desktop → Partager → « Recevoir des notes du téléphone »."), 13f, Th.sub).apply { setLineSpacing(0f, 1.15f) }, MATCH, WRAP, 0f, 0, 12, 0, 10)
                put(a.pill(L("Scan desktop QR code and send", "Scanner le QR du bureau et envoyer")) { scan() })
                put(a.text(L("To another phone: enter the address and code it shows under “Receive notes”.", "Vers un autre téléphone : entrez l’adresse et le code affichés sous « Recevoir des notes »."), 13f, Th.sub).apply { setLineSpacing(0f, 1.15f) }, MATCH, WRAP, 0f, 0, 16, 0, 0)
                val targets = a.field(L("192.168.1.20 4821\n(address, then code; one device per line)", "192.168.1.20 4821\n(adresse puis code ; un appareil par ligne)"), false, 2)
                put(targets, MATCH, WRAP, 0f, 0, 8, 0, 10)
                put(a.pill(L("Send to phone", "Envoyer au téléphone"), false) { send(targets.text.toString()) })
                put(report, MATCH, WRAP, 0f, 0, 10, 0, 0)
            }, MATCH, WRAP, 0f, 0, 0, 0, 14)
            // receive
            put(section(L("Receive notes", "Recevoir des notes")) {
                put(a.hbox {
                    put(a.text(L("Receive from another device", "Recevoir d’un autre appareil"), 15f, Th.ink, true), 0, WRAP, 1f)
                    put(Switch(a).apply {
                        thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt()); trackTintList = ColorStateList.valueOf(Th.accent)
                        setOnCheckedChangeListener { _, on -> if (on) startRx() else stopRx() }
                    })
                })
                put(rxInfo, MATCH, WRAP, 0f, 0, 10, 0, 0); put(rxCount, MATCH, WRAP, 0f, 0, 4, 0, 0)
            })
        }
        root.put(sv, MATCH, 0, 1f)
        drawNotes()
    }

    private fun section(title: String, build: LinearLayout.() -> Unit): View = a.vbox {
        background = a.rounded(Th.card, 20f, Th.line); setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(16))
        put(a.text(title, 17f, Th.ink, true), MATCH, WRAP, 0f, 0, 0, 0, 10)
        build()
    }

    private fun drawNotes() {
        notesBox.removeAllViews()
        if (Store.notes.isEmpty()) notesBox.put(a.text(L("No notes to send.", "Aucune note à envoyer."), 13f, Th.sub))
        for (n in Store.notes.take(100)) notesBox.put(a.hbox {
            minimumHeight = a.dp(44)
            put(a.icon(if (n.id in selected) R.drawable.ic_box_on else R.drawable.ic_box_off, if (n.id in selected) Th.accent else Th.sub, 22), a.dp(22), a.dp(22), 0f, 0, 0, 12, 0)
            put(a.text(n.title.ifBlank { L("Untitled", "Sans titre") }, 15f, Th.ink).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, 0, WRAP, 1f)
            isClickable = true
            setOnClickListener { if (!selected.add(n.id)) selected.remove(n.id); drawNotes() }
        })
    }

    private fun send(targetsText: String) {
        if (busy) return
        val notes = Store.notes.filter { it.id in selected }
        val tg = Net.parseTargets(targetsText)
        if (notes.isEmpty()) { report.text = L("Select at least one note.", "Sélectionnez au moins une note."); return }
        if (tg.isEmpty()) { report.text = L("Enter the receiver address, e.g. 192.168.1.20 4821", "Entrez l’adresse du destinataire, ex. 192.168.1.20 4821"); return }
        busy = true; report.text = L("Sending…", "Envoi…")
        val body = Net.payload(notes)
        Thread {
            val lines = tg.map { t ->
                val c = Net.send(a, t, body)
                "${t.host}:${t.port} — " + when (c) {
                    200 -> L("sent ✓", "envoyé ✓"); 401 -> L("wrong code", "code incorrect")
                    0 -> L("unreachable (same Wi-Fi? app open and receiving?)", "injoignable (même Wi-Fi ? application ouverte en réception ?)"); else -> "error $c"
                }
            }
            a.post(Runnable { report.text = lines.joinToString("\n"); busy = false })
        }.start()
    }

    private fun scan() {
        Qr.scan(a, { v -> a.post(Runnable { handle(v) }) }, { m -> a.post(Runnable {
            a.info(L("Scanner unavailable", "Scanner indisponible"),
                L("The scanner needs Google Play services (", "Le scanner nécessite les services Google Play (") + m + L("). Paste the link shown under the QR code instead.", "). Collez plutôt le lien affiché sous le QR code."))
        }) })
    }

    /** One entry point for a scanned or pasted Notia link: sync from, send to, or point to the browser. */
    private fun handle(v: String) {
        val l = Net.parseLink(v)
        when (l?.kind) {
            "sync" -> sync(l.url)
            "inbox" -> sendInbox(l.url)
            "files" -> importShared(l.url)
            else -> a.info(L("Not a Notia code", "Ce n’est pas un code Notia"), L("This is not a Notia sync code for your local network. Use the code shown by Notia Desktop (same Wi-Fi).", "Ce n’est pas un code de synchronisation Notia de votre réseau local. Utilisez le code affiché par Notia Desktop (même Wi-Fi)."))
        }
    }

    private fun sync(u: java.net.URL) {
        if (busy) return
        busy = true
        a.toast(L("Connecting and importing notes…", "Connexion et importation des notes…"))
        Thread {
            try {
                val list = Net.fetchSync(a, u)
                a.post(Runnable {
                    busy = false
                    if (list.isEmpty()) { a.info(L("Nothing to import", "Rien à importer"), L("The desktop sent no readable notes.", "Le bureau n’a envoyé aucune note lisible.")); return@Runnable }
                    val n = Store.importNotes(list); Store.changed()
                    a.info(L("Sync complete", "Synchronisation terminée"), if (n == 0) L("Your notes are already up to date.", "Vos notes sont déjà à jour.") else L("$n of ${list.size} note(s) imported or updated.", "$n note(s) sur ${list.size} importée(s) ou mise(s) à jour."))
                })
            } catch (e: Net.NotJson) {
                // the address is Notia Desktop's file share (a page listing an exported note): import that file instead
                a.post(Runnable { busy = false; importShared(u) })
            } catch (e: Exception) {
                a.post(Runnable { busy = false; a.info(L("Sync failed", "Échec de la synchronisation"), Net.explain(e)) })
            }
        }.start()
    }

    /**
     * Notia Desktop "Wi-Fi share": downloads the shared file(s) and imports them. Markdown and text files become notes,
     * HTML becomes a note, PDF / DOCX / EPUB ... go to the Library.
     */
    private fun importShared(u: java.net.URL) {
        if (busy) return
        busy = true
        a.toast(L("Downloading from the desktop…", "Téléchargement depuis le bureau…"))
        Thread {
            var notes = 0; var books = 0; var skipped = 0
            var failure: Throwable? = null
            try {
                val files = Net.sharedFiles(a, u)
                if (files.isEmpty()) throw java.io.IOException(L("the desktop shares no file", "le bureau ne partage aucun fichier"))
                for (f in files) {
                    try {
                        val bytes = Net.download(a, u, f)
                        val ext = f.name.substringAfterLast('.', "").lowercase()
                        val stem = f.name.substringBeforeLast('.', f.name).trim().ifEmpty { L("Imported note", "Note importée") }
                        when (ext) {
                            "md", "markdown", "txt", "text" -> {
                                val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
                                val html = if (ext == "md" || ext == "markdown") Md.toHtml(text) else text.replace("\r\n", "\n").split('\n').joinToString("") { "<div>" + (if (it.isBlank()) "<br>" else it.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")) + "</div>" }
                                val title = (if (ext == "md" || ext == "markdown") Md.title(text) else null) ?: stem
                                val now = System.currentTimeMillis()
                                a.runOnUiThread { Store.importNotes(listOf(IncomingNote(newId(), title.take(200), html.take(5_000_000), "", now, now))); Store.changed() }
                                notes++
                            }
                            "html", "htm" -> {
                                val raw = String(bytes, Charsets.UTF_8)
                                val body = Regex("<body[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1) ?: raw
                                val now = System.currentTimeMillis()
                                a.runOnUiThread { Store.importNotes(listOf(IncomingNote(newId(), stem.take(200), sanitize(body).take(5_000_000), "", now, now))); Store.changed() }
                                notes++
                            }
                            else -> {
                                val tmp = java.io.File(a.cacheDir, "dl-" + System.nanoTime() + "." + ext.ifEmpty { "bin" })
                                try {
                                    tmp.writeBytes(bytes)
                                    if (com.notia.mobile.sys.Library.EXT.contains(ext) && Store.books.size < com.notia.mobile.sys.Library.MAX_BOOKS) {
                                        val b = com.notia.mobile.sys.Library.import(a, android.net.Uri.fromFile(tmp)).also { it.title = stem }
                                        a.runOnUiThread { Store.books.add(0, b); Store.saveLib() }
                                        books++
                                    } else skipped++
                                } catch (e: Exception) { skipped++ } finally { tmp.delete() }
                            }
                        }
                    } catch (e: Exception) { failure = e; skipped++ }
                }
            } catch (e: Exception) { failure = e }
            a.post(Runnable {
                busy = false
                if (notes + books == 0) a.info(L("Import failed", "Échec de l’importation"), failure?.let { Net.explain(it) } ?: L("Nothing could be imported from this code.", "Rien n’a pu être importé depuis ce code."))
                else a.info(L("Imported from the desktop", "Importé depuis le bureau"),
                    listOfNotNull(if (notes > 0) L("$notes note(s) added to Notes", "$notes note(s) ajoutée(s) aux Notes") else null,
                        if (books > 0) L("$books file(s) added to the Library", "$books fichier(s) ajouté(s) à la Bibliothèque") else null,
                        if (skipped > 0) L("$skipped skipped", "$skipped ignoré(s)") else null).joinToString("\n"))
            })
        }.start()
    }

    private fun sendInbox(u: java.net.URL) {
        val notes = Store.notes.filter { it.id in selected }
        if (notes.isEmpty()) { a.info(L("Select notes first", "Choisissez d’abord des notes"), L("Tick the notes to send above, then scan the desktop code again.", "Cochez d’abord les notes à envoyer ci-dessus, puis scannez de nouveau le code du bureau.")); return }
        if (busy) return
        busy = true; report.text = L("Sending…", "Envoi…")
        val body = Net.payload(notes)
        Thread {
            val c = Net.postInbox(a, u, body)
            a.post(Runnable {
                busy = false
                report.text = when (c) {
                    200 -> L("Sent ${notes.size} note(s) to the desktop ✓", "${notes.size} note(s) envoyée(s) au bureau ✓")
                    404 -> L("The desktop is no longer waiting. Open “Receive notes from phone” again and scan the new code.", "Le bureau n’attend plus. Rouvrez « Recevoir des notes du téléphone » et scannez le nouveau code.")
                    0 -> L("Desktop unreachable (same Wi-Fi? window still open?)", "Bureau injoignable (même Wi-Fi ? fenêtre toujours ouverte ?)")
                    else -> "error $c"
                }
            })
        }.start()
    }

    private fun startRx() {
        val ip = Net.myIp()
        if (ip == null) { a.toast(L("Connect to Wi-Fi first.", "Connectez-vous d’abord au Wi-Fi.")); return }
        val code = (1000 + java.security.SecureRandom().nextInt(9000)).toString()
        val s = Net.Server(code, { list ->
            val latch = CountDownLatch(1); var n = 0
            a.post(Runnable { n = Store.importNotes(list); Store.changed(); received += n; rxCount.text = L("Received: $received note(s)", "Reçu : $received note(s)"); latch.countDown() })
            latch.await(5, TimeUnit.SECONDS); n
        }, { m -> a.post(Runnable { rxInfo.text = ""; a.toast(L("Cannot receive: ", "Réception impossible : ") + m) }) })
        if (s.start()) { server = s; rxInfo.text = "$ip:${Net.PORT}  $code"; rxCount.text = L("Give this address and code to the sender. Keep this screen open.", "Donnez cette adresse et ce code à l’expéditeur. Gardez cet écran ouvert.") }
    }

    private fun stopRx() { server?.stop(); server = null; rxInfo.text = ""; rxCount.text = "" }

    override fun view(): View = root
    override fun onDestroy() { stopRx() }
    override fun onBack(): Boolean { return false }
}
