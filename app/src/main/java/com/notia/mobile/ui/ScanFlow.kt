package com.notia.mobile.ui

import android.net.Uri
import com.notia.mobile.MainActivity
import com.notia.mobile.R
import com.notia.mobile.core.Store
import com.notia.mobile.doc.ScanPdf
import com.notia.mobile.sys.Exporter
import com.notia.mobile.sys.Scanner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Notes → Scan: scan pages with the document scanner and export them as one small PDF, like Notes on iPhone. */
object ScanFlow {
    /** Opens the scanner; when Google Play services cannot provide it, offers to pick photos of the pages instead. */
    fun pick(a: MainActivity, onPages: (List<Uri>) -> Unit) {
        Scanner.scan(a, { pages -> a.post(Runnable { if (pages.isNotEmpty()) onPages(pages) }) }, { m ->
            a.post(Runnable {
                a.confirm(L("Scanner unavailable", "Scanner indisponible"),
                    L("The document scanner needs Google Play services (", "Le scanner de documents nécessite les services Google Play (") + m +
                        L("). Choose photos of your pages from the gallery instead?", "). Choisir plutôt des photos de vos pages dans la galerie ?"),
                    L("Choose photos", "Choisir des photos"), false) {
                    a.pickImages { list -> if (list.isNotEmpty()) onPages(list.take(Scanner.MAX_PAGES)) }
                }
            })
        })
    }

    /** Scan, build the PDF, then offer to share, save or keep it as a note. */
    fun run(a: MainActivity) = pick(a) { pages -> make(a, pages) }

    private fun fileSize(n: Long): String = if (n >= 1024 * 1024) String.format(Locale.US, "%.1f MB", n / 1048576.0) else Math.max(1L, n / 1024).toString() + " KB"

    private fun make(a: MainActivity, pages: List<Uri>) {
        a.toast(L("Creating PDF…", "Création du PDF…"))
        val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date())
        val name = "Scan $stamp"
        Thread {
            try {
                val bytes = ScanPdf.build(a.applicationContext, pages, name)
                val f = Exporter.write(a, "$name.pdf", bytes)
                a.post(Runnable { done(a, pages, f) })
            } catch (e: Throwable) {
                a.post(Runnable { a.toast(L("Could not create the PDF.", "Impossible de créer le PDF.")) })
            }
        }.start()
    }

    private fun done(a: MainActivity, pages: List<Uri>, f: File) {
        val s = Sheet(a, L("Scan ready", "Scan prêt"))
        s.body.put(a.text(pages.size.toString() + L(" page(s)  •  PDF  •  ", " page(s)  •  PDF  •  ") + fileSize(f.length()), 14f, Th.sub), MATCH, WRAP, 0f, 0, 0, 0, 14)
        s.row(R.drawable.ic_share, L("Share PDF", "Partager le PDF")) { Exporter.shareFile(a, f, Exporter.MIME_PDF) }
        s.row(R.drawable.ic_doc, L("Save PDF to this phone", "Enregistrer le PDF sur ce téléphone")) { save(a, f) }
        s.row(R.drawable.ic_pen, L("Also keep as a note", "Garder aussi comme note")) { asNote(a, pages, f.nameWithoutExtension) }
        s.show()
    }

    private fun save(a: MainActivity, f: File) {
        a.createDocument(f.name, Exporter.MIME_PDF) { uri ->
            if (uri == null) return@createDocument
            Thread {
                val ok = try { a.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }; true } catch (e: Throwable) { false }
                a.post(Runnable { a.toast(if (ok) L("PDF saved.", "PDF enregistré.") else L("Could not save the PDF.", "Impossible d’enregistrer le PDF.")) })
            }.start()
        }
    }

    /** A new note holding the scanned pages as pictures; exporting that note to PDF stays small too. */
    private fun asNote(a: MainActivity, pages: List<Uri>, title: String) {
        a.toast(L("Adding pages to a note…", "Ajout des pages à une note…"))
        Thread {
            val sb = StringBuilder()
            for (u in pages) try { sb.append("<img src=\"").append(imageDataUri(a, u)).append("\" style=\"width:100%;height:auto;border-radius:8px\"/><div><br></div>") } catch (e: Throwable) {}
            a.post(Runnable {
                if (sb.isEmpty()) { a.toast(L("Could not read the scanned pages.", "Impossible de lire les pages scannées.")); return@Runnable }
                val n = Store.addNote(null)
                n.title = title; n.html = sb.toString(); Store.touch(n); Store.changed()
                a.push(EditorScreen(a, n.id))
            })
        }.start()
    }
}
