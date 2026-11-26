package com.notia.mobile.sys

import android.app.Activity
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.OpenableColumns
import android.webkit.WebView
import android.webkit.WebViewClient
import com.notia.mobile.books.BookException
import com.notia.mobile.books.openTextBook
import com.notia.mobile.core.Book
import com.notia.mobile.core.Note
import com.notia.mobile.core.Store
import com.notia.mobile.core.esc
import com.notia.mobile.core.newId
import com.notia.mobile.doc.DocxWriter
import com.notia.mobile.doc.HtmlModel
import com.notia.mobile.doc.ImageRun
import com.notia.mobile.doc.Images
import com.notia.mobile.doc.PdfAndroid
import com.notia.mobile.doc.PdfWriter
import java.io.ByteArrayOutputStream
import java.io.File

/** Serves files from cache/share to the apps the user picks in the share sheet. Not exported. */
class ShareProvider : ContentProvider() {
    private fun dir() = File(context!!.cacheDir, "share")
    private fun resolve(uri: Uri): File {
        val f = File(dir(), uri.lastPathSegment ?: "").canonicalFile
        if (!f.path.startsWith(dir().canonicalPath + File.separator) || !f.isFile) throw java.io.FileNotFoundException()
        return f
    }
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = resolve(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }
    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

object Exporter {
    const val AUTH = "com.notia.mobile.share"
    const val MIME_PDF = "application/pdf"
    const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    private fun safeName(t: String, ext: String): String {
        val b = t.replace(Regex("[^\\p{L}\\p{N} _.-]"), "").trim().take(40).ifEmpty { "Note" }
        return "$b.$ext"
    }

    /** Re-encodes images the writers cannot embed (gif, webp, bmp) as PNG. */
    private val conv: (String, ByteArray) -> ByteArray? = { _, b ->
        BitmapFactory.decodeByteArray(b, 0, b.size)?.let { bmp ->
            val bg = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(bg).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
            ByteArrayOutputStream().also { bg.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }

    /**
     * DOCX: pictures are shrunk to at most 1100 px and stored as JPEG (the ZIP container deflates the XML); a note with a photo
     * stays at a few hundred KB.
     */
    fun docxBytes(n: Note): ByteArray {
        val blocks = HtmlModel.mapImages(HtmlModel.parse(n.html, conv)) { im ->
            val s = Images.forDocx(im.bytes)
            if (s == null) im else ImageRun(s.bytes, s.ext, s.w, s.h, im.pct)
        }
        return DocxWriter.build(n.title, blocks)
    }

    /**
     * PDF: real text in the built-in PDF fonts (nothing embedded), pictures downscaled to ~150 dpi JPEG. Android's own PdfDocument
     * embeds whole system fonts and stores pictures uncompressed, which is how a few lines used to reach 6 MB.
     * Formats the PDF writer cannot read directly (gif, webp, bmp) are decoded by Android when the picture is placed.
     */
    fun pdfBytes(n: Note): ByteArray = PdfWriter(n.title, HtmlModel.parse(n.html) { _, b -> b }, PdfAndroid).build()

    fun write(ctx: Context, name: String, bytes: ByteArray): File {
        val d = File(ctx.cacheDir, "share").apply { mkdirs() }
        d.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
        return File(d, name).also { it.writeBytes(bytes) }
    }

    fun fileFor(ctx: Context, n: Note, pdf: Boolean): File =
        write(ctx, safeName(n.title, if (pdf) "pdf" else "docx"), if (pdf) pdfBytes(n) else docxBytes(n))

    fun shareFile(a: Activity, f: File, mime: String) {
        val uri = Uri.parse("content://$AUTH/" + Uri.encode(f.name))
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri(f.name, uri)
        a.startActivity(Intent.createChooser(i, f.name))
    }

    fun shareText(a: Activity, n: Note) {
        val t = (if (n.title.isNotBlank()) n.title + "\n\n" else "") + n.text
        a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, n.title).putExtra(Intent.EXTRA_TEXT, t), n.title.ifEmpty { "Note" }))
    }

    /** Fallback when the native PDF writer fails: system print dialog ("Save as PDF"). */
    fun printNote(a: Activity, n: Note) {
        val wv = WebView(a)
        wv.settings.javaScriptEnabled = false
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String?) {
                val pm = a.getSystemService(Context.PRINT_SERVICE) as PrintManager
                pm.print(n.title.ifEmpty { "Note" }, v.createPrintDocumentAdapter(n.title.ifEmpty { "Note" }), PrintAttributes.Builder().build())
            }
        }
        wv.loadDataWithBaseURL(null, "<html><head><meta charset=\"utf-8\"><style>body{font-family:sans-serif;font-size:12pt}img{max-width:100%}table{border-collapse:collapse}td,th{border:1px solid #999;padding:4px}</style></head><body><h1>${esc(n.title)}</h1>${n.html}</body></html>", "text/html", "utf-8", null)
    }
}

/** Adds books to the library: copies the file, checks it looks right, extracts a cover and page count. */
object Library {
    const val MAX_BOOKS = 10
    private const val MAX_BYTES = 60L * 1024 * 1024
    val EXT = setOf("pdf", "epub", "mobi", "azw", "azw3", "prc", "docx", "cbz", "txt", "html", "htm")

    class Fail(val code: String) : Exception(code)

    private fun kindOf(ext: String, mime: String): String? = when {
        ext == "pdf" || mime == "application/pdf" -> "pdf"
        ext == "epub" || mime == "application/epub+zip" -> "epub"
        ext in setOf("mobi", "azw", "azw3", "prc") || mime.contains("mobi") || mime.contains("amazon") -> "mobi"
        ext == "docx" || mime.contains("wordprocessingml") -> "docx"
        ext == "cbz" || mime == "application/vnd.comicbook+zip" || mime == "application/x-cbz" -> "cbz"
        ext == "txt" || mime == "text/plain" -> "txt"
        ext == "html" || ext == "htm" || mime == "text/html" -> "html"
        else -> null
    }

    /** Last resort for files with a missing or misleading name/type: look at the first bytes. */
    private fun sniff(ctx: Context, uri: Uri): String? = try {
        val h = ctx.contentResolver.openInputStream(uri)?.use { it.readNBytesCompat(100) } ?: ByteArray(0)
        fun s(o: Int, n: Int) = if (h.size >= o + n) String(h, o, n, Charsets.ISO_8859_1) else ""
        when {
            s(0, 4) == "%PDF" -> "pdf"
            s(60, 8) == "BOOKMOBI" || s(60, 8) == "TEXtREAd" -> "mobi"
            s(0, 2) == "PK" -> {
                val nameLen = if (h.size > 28) (h[26].toInt() and 0xFF) or ((h[27].toInt() and 0xFF) shl 8) else 0
                val first = s(30, Math.min(nameLen, Math.max(0, h.size - 30))).lowercase()
                when {
                    first == "mimetype" -> "epub"
                    first.startsWith("[content_types]") -> "docx"
                    first.substringAfterLast('.', "") in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> "cbz"
                    else -> null
                }
            }
            else -> null
        }
    } catch (e: Exception) { null }

    private fun titleOf(name: String) = name.substringBeforeLast('.', name).replace('_', ' ').trim().ifEmpty { "Book" }.take(120)

    /** Display name and size of a picked or shared file (size -1 when unknown). */
    private fun meta(ctx: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Book"; var size = -1L
        try { ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) {
            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).let { if (it >= 0) name = c.getString(it) ?: name }
            c.getColumnIndex(OpenableColumns.SIZE).let { if (it >= 0 && !c.isNull(it)) size = c.getLong(it) }
        } } } catch (e: Exception) {}
        return name to size
    }

    /** The library book this file was already imported as (same title and size), or null. */
    fun existing(ctx: Context, uri: Uri): Book? {
        val (name, size) = meta(ctx, uri)
        if (size <= 0) return null
        val t = titleOf(name)
        return Store.books.firstOrNull { it.title == t && Store.bookFile(it).length() == size }
    }

    /** Blocking; call from a worker thread. Throws Fail(code): limit, unsupported, big, empty, invalid. */
    fun import(ctx: Context, uri: Uri): Book {
        if (Store.books.size >= MAX_BOOKS) throw Fail("limit")
        val (name, size) = meta(ctx, uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        val kind = kindOf(ext, ctx.contentResolver.getType(uri) ?: "") ?: sniff(ctx, uri) ?: throw Fail("unsupported")
        if (size > MAX_BYTES) throw Fail("big")
        val id = newId()
        val fileName = "${System.currentTimeMillis()}.$kind"
        val f = File(Store.dir, fileName)
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { ins -> f.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var total = 0L
                while (true) { val r = ins.read(buf); if (r < 0) break; total += r; if (total > MAX_BYTES) throw Fail("big"); o.write(buf, 0, r) }
            } }
            if (f.length() == 0L) throw Fail("empty")
            val head = f.inputStream().use { it.readNBytesCompat(1100) }
            val ok = when (kind) {
                "pdf" -> String(head, Charsets.ISO_8859_1).contains("%PDF")
                "epub", "docx", "cbz" -> head.size > 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
                "mobi" -> head.size > 68 && String(head, 60, 8, Charsets.ISO_8859_1).let { it == "BOOKMOBI" || it == "TEXtREAd" }
                else -> true
            }
            if (!ok) throw Fail("invalid")
            val b = Book(id, titleOf(name), kind, fileName)
            prepare(b)
            return b
        } catch (e: Exception) { f.delete(); throw if (e is Fail) e else Fail("invalid") }
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val b = ByteArray(n); var got = 0
        while (got < n) { val r = read(b, got, n - got); if (r < 0) break; got += r }
        return b.copyOf(got)
    }

    // ---- author metadata (best effort; "" when the file has none) ----
    private fun xmlText(s: String): String = HtmlModel.dec(s.replace(Regex("<[^>]*>"), " ")).replace(Regex("\\s+"), " ").trim()

    private fun zipText(z: java.util.zip.ZipFile, name: String): String? =
        z.getEntry(name)?.let { e -> z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }

    private fun creators(xml: String): String =
        Regex("<(?:dc:)?creator\\b[^>]*>([\\s\\S]*?)</(?:dc:)?creator>", RegexOption.IGNORE_CASE).findAll(xml)
            .map { xmlText(it.groupValues[1]) }.filter { it.isNotEmpty() }.distinct().joinToString(", ")

    private fun epubAuthor(f: File): String = java.util.zip.ZipFile(f).use { z ->
        val cont = zipText(z, "META-INF/container.xml") ?: return ""
        val opfPath = Regex("full-path\\s*=\\s*[\"']([^\"']+)").find(cont)?.groupValues?.get(1) ?: return ""
        creators(zipText(z, opfPath) ?: return "")
    }

    private fun docxAuthor(f: File): String = java.util.zip.ZipFile(f).use { z -> creators(zipText(z, "docProps/core.xml") ?: return "") }

    private fun mobiAuthor(f: File): String {
        val b = f.inputStream().use { it.readNBytesCompat(1 shl 20) }
        fun u32(o: Int) = if (o >= 0 && o + 4 <= b.size) ((b[o].toInt() and 255) shl 24) or ((b[o + 1].toInt() and 255) shl 16) or ((b[o + 2].toInt() and 255) shl 8) or (b[o + 3].toInt() and 255) else -1
        if (b.size < 100) return ""
        val nrec = ((b[76].toInt() and 255) shl 8) or (b[77].toInt() and 255)
        if (nrec < 1) return ""
        val off0 = u32(78)
        val off1 = if (nrec > 1) u32(86) else b.size
        if (off0 < 0 || off0 >= b.size) return ""
        val r = b.copyOfRange(off0, Math.min(if (off1 in (off0 + 1)..b.size) off1 else b.size, b.size))
        fun v(o: Int) = if (o >= 0 && o + 4 <= r.size) ((r[o].toInt() and 255) shl 24) or ((r[o + 1].toInt() and 255) shl 16) or ((r[o + 2].toInt() and 255) shl 8) or (r[o + 3].toInt() and 255) else -1
        if (r.size < 132 || String(r, 16, 4, Charsets.ISO_8859_1) != "MOBI" || (v(128) and 0x40) == 0) return ""
        val ex = 16 + v(20)
        if (ex < 0 || ex + 12 > r.size || String(r, ex, 4, Charsets.ISO_8859_1) != "EXTH") return ""
        var q = ex + 12
        for (i in 0 until v(ex + 8)) {
            if (q + 8 > r.size) break
            val ty = v(q); val ln = v(q + 4)
            if (ln < 8) break
            if (ty == 100 && q + ln <= r.size) return String(r, q + 8, ln - 8, Charsets.UTF_8).trim()
            q += ln
        }
        return ""
    }

    private fun pdfAuthor(ctx: Context, f: File): String {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx.applicationContext)
        return com.tom_roush.pdfbox.pdmodel.PDDocument.load(f).use { it.documentInformation?.author?.trim() ?: "" }
    }

    /** The book's author from its metadata, remembered after the first lookup. Blocking: call from a worker thread. "" when unknown. */
    fun author(ctx: Context, b: Book): String {
        b.author?.let { return it }
        val f = Store.bookFile(b)
        val a = try {
            when (b.kind) { "epub" -> epubAuthor(f); "docx" -> docxAuthor(f); "mobi" -> mobiAuthor(f); "pdf" -> pdfAuthor(ctx, f); else -> "" }
        } catch (e: Throwable) { "" }.replace(Regex("\\s+"), " ").trim().take(200)
        b.author = a
        return a
    }

    /** Extracts page count (PDF) and cover. Best effort. */
    fun prepare(b: Book) {
        val f = Store.bookFile(b)
        try {
            if (b.kind == "pdf") {
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    val r = PdfRenderer(pfd)
                    try {
                        b.total = r.pageCount
                        if (r.pageCount > 0) r.openPage(0).use { p ->
                            val w = 300; val h = Math.max(1, (w.toFloat() * p.height / p.width).toInt())
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); bmp.eraseColor(Color.WHITE)
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            saveCover(b, bmp)
                        }
                    } finally { r.close() }
                }
            } else if (b.kind == "epub" || b.kind == "mobi" || b.kind == "cbz") {
                openTextBook(b.kind, f).use { tb -> tb.cover()?.let { bytes -> decodeScaled(bytes, 300)?.let { saveCover(b, it) } } }
            }
        } catch (e: BookException) {
        } catch (e: Throwable) {}
    }

    private fun PdfRenderer.Page.use(block: (PdfRenderer.Page) -> Unit) { try { block(this) } finally { close() } }

    fun decodeScaled(bytes: ByteArray, w: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        var s = 1; while (o.outWidth / (s * 2) >= w) s *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        return if (bmp.width > w) Bitmap.createScaledBitmap(bmp, w, Math.max(1, bmp.height * w / bmp.width), true) else bmp
    }

    private fun saveCover(b: Book, bmp: Bitmap) {
        File(Store.dir, "covers/${b.id}.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    fun delete(b: Book) {
        Store.bookFile(b).delete(); File(Store.dir, "covers/${b.id}.jpg").delete()
    }
}
