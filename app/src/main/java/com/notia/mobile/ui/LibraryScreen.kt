package com.notia.mobile.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.notia.mobile.MainActivity
import com.notia.mobile.R
import com.notia.mobile.core.Book
import com.notia.mobile.core.Store
import com.notia.mobile.core.fmtTime
import com.notia.mobile.sys.Library
import com.notia.mobile.sys.Reminders
import java.io.File
import java.util.concurrent.Executors

/** "312 KB" / "4.2 MB" */
fun fmtSize(n: Long): String = if (n >= 1024 * 1024) String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0) else Math.max(1L, n / 1024).toString() + " KB"

fun importError(code: String) = when (code) {
    "unsupported" -> L("Unsupported file. Use PDF, EPUB, MOBI, CBZ, DOCX, TXT or HTML.", "Fichier non pris en charge. Utilisez PDF, EPUB, MOBI, CBZ, DOCX, TXT ou HTML.")
    "big" -> L("This file is too large (60 MB max).", "Ce fichier est trop volumineux (60 Mo max).")
    "empty" -> L("This file is empty.", "Ce fichier est vide.")
    "limit" -> L("Library is full (10 books max). Remove one first.", "Bibliothèque pleine (10 livres max). Retirez-en un d’abord.")
    else -> L("This file looks damaged or is not a valid book.", "Ce fichier semble endommagé ou n’est pas un livre valide.")
}

class LibraryScreen(private val a: MainActivity) : Screen() {
    private val root = a.vbox { setBackgroundColor(Th.bg) }
    private val list = ListView(a)
    private val stats = a.vbox()
    private val empty = a.text("", 15f, Th.sub).apply { gravity = Gravity.CENTER }
    private val covers = LruCache<String, Bitmap>(24)
    private var importing = false
    private val onData = { refresh() }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = Store.books.size
        override fun getItem(p: Int) = Store.books[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View = FrameLayout(a).apply {
            val b = Store.books[p]
            setPadding(0, a.dp(5), 0, a.dp(5))
            addView(a.hbox {
                background = a.pressable(Th.card, 20f, Th.line); setPadding(a.dp(12), a.dp(12), a.dp(14), a.dp(12))
                put(cover(b), a.dp(58), a.dp(82), 0f, 0, 0, 14, 0)
                put(a.vbox {
                    put(a.hbox {
                        put(a.text(b.title, 16f, Th.ink, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, 0, WRAP, 1f)
                        // three dots: name, type, author and size of this file
                        put(a.iconBtn(R.drawable.ic_more, L("Details", "Détails"), Th.sub, 20) { details(b) }.also { it.layoutParams = LinearLayout.LayoutParams(a.dp(36), a.dp(36)) }, AUTO, AUTO, 0f, 4, -6, -8, -6)
                    }, MATCH, WRAP)
                    put(progressBar(b.pct), MATCH, a.dp(5), 0f, 0, 10, 0, 6)
                    val page = if (b.total > 0) "  •  p. ${Math.max(1, Math.round(b.pct * (b.total - 1)).toInt() + 1)}/${b.total}" else ""
                    put(a.text("${Math.round(b.pct * 100)}%  •  ${fmtTime(b.time)}$page", 12f, Th.sub))
                }, 0, WRAP, 1f)
                isClickable = true
                setOnClickListener { a.push(ReaderScreen(a, b.id)) }
                setOnLongClickListener { remove(b); true }
            }, FrameLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun progressBar(p: Double): View = FrameLayout(a).apply {
        background = a.rounded(Th.line, 3f)
        addView(View(a).apply { background = a.rounded(Th.accent, 3f) }, FrameLayout.LayoutParams(0, MATCH).also { lp -> post { lp.width = (width * p.coerceIn(0.0, 1.0)).toInt(); requestLayout() } })
    }

    private fun cover(b: Book): View {
        val f = Store.cover(b)
        val bmp = f?.let { covers.get(b.id) ?: BitmapFactory.decodeFile(it.path)?.also { d -> covers.put(b.id, d) } }
        return if (bmp != null) ImageView(a).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP; clipRound(10f) }
        else FrameLayout(a).apply {
            background = a.rounded(Th.accent, 10f)
            addView(a.text(b.title.take(1).uppercase(), 24f, Th.accentInk, true), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        }
    }

    init {
        root.put(a.tabHeader(L("Library", "Bibliothèque")))
        root.put(stats, MATCH, WRAP, 0f, 20, 8, 20, 8)
        list.divider = null; list.dividerHeight = 0; list.clipToPadding = false; list.selector = android.graphics.drawable.ColorDrawable(0)
        list.setPadding(a.dp(20), a.dp(4), a.dp(20), a.dp(170)); list.adapter = adapter
        val body = FrameLayout(a)
        body.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(empty, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        val fab = FrameLayout(a).apply {
            background = a.rounded(Th.accent, 28f); elevation = a.dpf(8f); isClickable = true; contentDescription = L("Add book", "Ajouter un livre")
            addView(a.icon(R.drawable.ic_add, Th.accentInk, 26), FrameLayout.LayoutParams(a.dp(26), a.dp(26), Gravity.CENTER))
            setOnClickListener { addBook() }
        }
        body.addView(fab, FrameLayout.LayoutParams(a.dp(56), a.dp(56), Gravity.BOTTOM or Gravity.END).also { it.setMargins(0, 0, a.dp(24), a.dp(92)) })
        root.put(body, MATCH, 0, 1f)
    }

    private fun refresh() {
        adapter.notifyDataSetChanged()
        stats.removeAllViews()
        val total = Store.books.sumOf { it.time }
        stats.put(a.hbox {
            background = a.rounded(Th.card, 20f, Th.line); setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14))
            put(a.vbox { put(a.text(L("Reading time", "Temps de lecture"), 12f, Th.sub)); put(a.text(fmtTime(total), 22f, Th.ink, true), MATCH, WRAP, 0f, 0, 4, 0, 0) }, 0, WRAP, 1f)
            put(a.vbox { put(a.text(L("Books", "Livres"), 12f, Th.sub)); put(a.text("${Store.books.size}/${Library.MAX_BOOKS}", 22f, Th.ink, true), MATCH, WRAP, 0f, 0, 4, 0, 0) }, 0, WRAP, 1f)
        })
        empty.text = if (Store.books.isEmpty()) L("Your library is empty.\nTap + to add a PDF, EPUB, MOBI, CBZ comic, DOCX or text file.", "Votre bibliothèque est vide.\nAppuyez sur + pour ajouter un PDF, EPUB, MOBI, DOCX ou fichier texte.") else ""
    }

    private fun typeName(b: Book): String = when (b.kind) {
        "pdf" -> L("PDF document", "Document PDF")
        "epub" -> L("EPUB book", "Livre EPUB")
        "mobi" -> L("MOBI / AZW book", "Livre MOBI / AZW")
        "cbz" -> L("CBZ comic", "Bande dessinée CBZ")
        "docx" -> L("Word document", "Document Word")
        "txt" -> L("Text file", "Fichier texte")
        "html" -> L("HTML page", "Page HTML")
        else -> b.kind.uppercase()
    }

    /** Name, file type, author and size of a library file. The author is read from the file's metadata the first time. */
    private fun details(b: Book) {
        val s = Sheet(a, L("Details", "Détails"))
        fun line(label: String, value: String): TextView {
            val v = a.text(value, 16f, Th.ink, true).apply { setLineSpacing(0f, 1.1f); setTextIsSelectable(true) }
            s.body.put(a.vbox {
                put(a.text(label.uppercase(), 11f, Th.sub, true).apply { letterSpacing = 0.08f }, MATCH, WRAP, 0f, 0, 0, 0, 4)
                put(v, MATCH, WRAP)
            }, MATCH, WRAP, 0f, 0, 0, 0, 14)
            return v
        }
        line(L("Name", "Nom"), b.title)
        line(L("File type", "Type de fichier"), typeName(b) + "  (." + b.kind + ")")
        val unknown = L("Unknown", "Inconnu")
        val authorView = line(L("Author", "Auteur"), b.author?.ifEmpty { null } ?: if (b.author == null) "…" else unknown)
        line(L("Size", "Taille"), fmtSize(Store.bookFile(b).length()))
        if (b.author == null) Thread {
            val au = Library.author(a, b)
            a.post(Runnable { authorView.text = au.ifEmpty { unknown }; Store.saveLib() })
        }.start()
        s.body.put(a.pill(L("Close", "Fermer"), false) { s.dismiss() }, MATCH, WRAP, 0f, 0, 4, 0, 8)
        s.body.put(a.pill(L("Remove from library", "Retirer de la bibliothèque"), false, true) { s.dismiss(); remove(b) })
        s.show()
    }

    private fun remove(b: Book) {
        a.confirm(L("Remove “${b.title}”?", "Retirer « ${b.title} » ?"), L("The book file and your reading progress are deleted from Notia.", "Le fichier du livre et votre progression sont supprimés de Notia."), L("Remove", "Retirer")) {
            Library.delete(b); Store.books.remove(b); Store.saveLib(); Reminders.scheduleReading(a); refresh()
        }
    }

    private fun addBook() {
        if (importing) return
        if (Store.books.size >= Library.MAX_BOOKS) { a.toast(L("Library is full (10 books max). Remove one first.", "Bibliothèque pleine (10 livres max). Retirez-en un d’abord.")); return }
        // no MIME filter: Android reports .mobi, .azw3 and .cbz under many different types (or none), so a filter hides them; the file is checked after picking
        a.pick(arrayOf("*/*")) { uri ->
            if (uri == null) return@pick
            importing = true; a.toast(L("Adding book…", "Ajout du livre…"))
            Thread {
                val res = try { Library.import(a, uri) } catch (e: Library.Fail) { e.code } catch (e: Throwable) { "invalid" }
                a.post(Runnable {
                    importing = false
                    if (res is Book) { Store.books.add(0, res); Store.saveLib(); refresh() }
                    else a.toast(importError(res as String))
                })
            }.start()
        }
    }

    override fun view(): View = root
    override fun onShow() {
        Store.listen(onData); refresh()
        if (Store.purged > 0) { a.toast(L("${Store.purged} book(s) were missing from storage and removed.", "${Store.purged} livre(s) introuvable(s) ont été retirés.")); Store.purged = 0; Store.saveLib() }
    }
    override fun onHide() { Store.unlisten(onData) }
}
