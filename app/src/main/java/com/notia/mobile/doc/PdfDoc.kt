package com.notia.mobile.doc

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * A picture ready to be placed in a PDF. JPEG data is stored as-is (DCTDecode); raw pixels are Flate-compressed here.
 * Pixels are 8-bit: RGB (3 bytes per pixel) or gray (1 byte per pixel).
 */
class PdfImage(val w: Int, val h: Int, val data: ByteArray, val jpeg: Boolean, val gray: Boolean = false)

/**
 * Minimal PDF 1.4 writer: pages with a vector drawing API (top-left origin, points), the 14 standard fonts, images and
 * link annotations. No platform classes, so it also runs in plain JVM tests.
 * Content streams are deflated and fonts are never embedded; this is what keeps exported files small.
 */
class PdfDoc(private val title: String = "") {
    private val pages = ArrayList<PdfPage>()
    private val images = ArrayList<PdfImage>()

    fun newPage(w: Float, h: Float): PdfPage = PdfPage(this, w, h).also { pages.add(it) }
    val pageCount get() = pages.size

    fun addImage(img: PdfImage): Int {
        val i = images.indexOf(img)
        if (i >= 0) return i
        images.add(img); return images.size - 1
    }

    // ---- serialisation ----
    private class Out {
        val bos = ByteArrayOutputStream(1 shl 16)
        val offsets = ArrayList<Long>()
        fun size() = bos.size().toLong()
        fun str(s: String) { bos.write(s.toByteArray(Charsets.ISO_8859_1)) }
        fun bytes(b: ByteArray) { bos.write(b) }
        /** Starts object [n] (1-based). */
        fun begin(n: Int) { while (offsets.size < n) offsets.add(0L); offsets[n - 1] = size(); str("$n 0 obj\n") }
        fun end() { str("\nendobj\n") }
    }

    private fun deflate(b: ByteArray, level: Int = Deflater.BEST_COMPRESSION): ByteArray {
        val d = Deflater(level)
        try {
            d.setInput(b); d.finish()
            val o = ByteArrayOutputStream(Math.max(64, b.size / 3)); val buf = ByteArray(16384)
            while (!d.finished()) { val n = d.deflate(buf); o.write(buf, 0, n) }
            return o.toByteArray()
        } finally { d.end() }
    }

    private fun hex16(s: String): String {
        val sb = StringBuilder("<FEFF")
        for (c in s) sb.append(String.format("%04X", c.code))
        return sb.append('>').toString()
    }

    fun build(): ByteArray {
        val o = Out()
        o.str("%PDF-1.4\n%âãÏÓ\n")
        // fixed object numbers: 1 catalog, 2 page tree, 3 info, then fonts, images, then per page (page, content, annots)
        val usedFonts = sortedSetOf<Int>()
        for (p in pages) usedFonts.addAll(p.fonts)
        val fontObj = HashMap<Int, Int>()
        var next = 4
        for (f in usedFonts) fontObj[f] = next++
        val imgObj = IntArray(images.size) { next++ }
        val pageObj = IntArray(pages.size)
        val contentObj = IntArray(pages.size)
        val annotObjs = ArrayList<IntArray>()
        for (i in pages.indices) {
            pageObj[i] = next++; contentObj[i] = next++
            annotObjs.add(IntArray(pages[i].links.size) { next++ })
        }

        o.begin(1); o.str("<< /Type /Catalog /Pages 2 0 R >>"); o.end()
        o.begin(2); o.str("<< /Type /Pages /Count ${pages.size} /Kids [" + pageObj.joinToString(" ") { "$it 0 R" } + "] >>"); o.end()
        o.begin(3); o.str("<< /Producer (Notia)" + (if (title.isNotEmpty()) " /Title ${hex16(title)}" else "") + " >>"); o.end()

        for (f in usedFonts) {
            o.begin(fontObj[f]!!)
            val w = PdfFonts.widths(f)
            o.str("<< /Type /Font /Subtype /Type1 /BaseFont /${PdfFonts.BASE[f]} /Encoding /WinAnsiEncoding /FirstChar 32 /LastChar 255 /Widths [")
            o.str(w.joinToString(" ")); o.str("] >>"); o.end()
        }

        for ((i, im) in images.withIndex()) {
            val data = if (im.jpeg) im.data else deflate(im.data)
            o.begin(imgObj[i])
            o.str("<< /Type /XObject /Subtype /Image /Width ${im.w} /Height ${im.h} /ColorSpace /${if (im.gray) "DeviceGray" else "DeviceRGB"} /BitsPerComponent 8 /Filter /${if (im.jpeg) "DCTDecode" else "FlateDecode"} /Length ${data.size} >>\nstream\n")
            o.bytes(data); o.str("\nendstream"); o.end()
        }

        for ((i, p) in pages.withIndex()) {
            val content = deflate(p.content.toString().toByteArray(Charsets.ISO_8859_1))
            o.begin(pageObj[i])
            val fonts = if (p.fonts.isEmpty()) "" else "/Font << " + p.fonts.sorted().joinToString(" ") { "/F$it ${fontObj[it]} 0 R" } + " >>"
            val xo = if (p.imageIdx.isEmpty()) "" else "/XObject << " + p.imageIdx.sorted().joinToString(" ") { "/Im$it ${imgObj[it]} 0 R" } + " >>"
            val an = if (p.links.isEmpty()) "" else " /Annots [" + annotObjs[i].joinToString(" ") { "$it 0 R" } + "]"
            o.str("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${num(p.w)} ${num(p.h)}] /Resources << $fonts $xo >> /Contents ${contentObj[i]} 0 R$an >>"); o.end()
            o.begin(contentObj[i]); o.str("<< /Filter /FlateDecode /Length ${content.size} >>\nstream\n"); o.bytes(content); o.str("\nendstream"); o.end()
            for ((k, l) in p.links.withIndex()) {
                o.begin(annotObjs[i][k])
                o.str("<< /Type /Annot /Subtype /Link /Border [0 0 0] /Rect [${num(l.x)} ${num(p.h - l.y - l.h)} ${num(l.x + l.w)} ${num(p.h - l.y)}] /A << /S /URI /URI ${asciiStr(l.url)} >> >>"); o.end()
            }
        }

        val xref = o.size()
        val n = o.offsets.size + 1
        o.str("xref\n0 $n\n0000000000 65535 f \n")
        for (off in o.offsets) o.str(String.format("%010d 00000 n \n", off))
        o.str("trailer\n<< /Size $n /Root 1 0 R /Info 3 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return o.bos.toByteArray()
    }

    companion object {
        fun num(v: Float): String {
            if (v == Math.rint(v.toDouble()).toFloat() && Math.abs(v) < 1e7) return v.toInt().toString()
            return String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
        }

        /** A PDF literal string holding only printable ASCII (anything else is percent-encoded, as URIs require). */
        fun asciiStr(s: String): String {
            val sb = StringBuilder("(")
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                if (c in 33..126 && c != '('.code && c != ')'.code && c != '\\'.code) sb.append(c.toChar())
                else sb.append(String.format("%%%02X", c))
            }
            return sb.append(')').toString()
        }
    }
}

class PdfLink(val x: Float, val y: Float, val w: Float, val h: Float, val url: String)

/** One page. Coordinates: points, origin top-left, y grows downwards. */
class PdfPage(private val doc: PdfDoc, val w: Float, val h: Float) {
    val content = StringBuilder(4096)
    val fonts = HashSet<Int>()
    val imageIdx = HashSet<Int>()
    val links = ArrayList<PdfLink>()

    private fun n(v: Float) = PdfDoc.num(v)
    private fun yy(y: Float) = n(h - y)

    private fun rgb(c: Int, stroke: Boolean): String {
        val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
        fun f(v: Float) = String.format(java.util.Locale.US, "%.3f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }
        return "${f(r)} ${f(g)} ${f(b)} ${if (stroke) "RG" else "rg"}\n"
    }

    fun fillRect(x: Float, y: Float, rw: Float, rh: Float, color: Int) {
        content.append(rgb(color, false)).append("${n(x)} ${n(h - y - rh)} ${n(rw)} ${n(rh)} re f\n")
    }

    fun strokeRect(x: Float, y: Float, rw: Float, rh: Float, color: Int, width: Float) {
        content.append(rgb(color, true)).append("${n(width)} w ${n(x)} ${n(h - y - rh)} ${n(rw)} ${n(rh)} re S\n")
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {
        content.append(rgb(color, true)).append("${n(width)} w ${n(x1)} ${yy(y1)} m ${n(x2)} ${yy(y2)} l S\n")
    }

    fun polyline(xs: FloatArray, ys: FloatArray, color: Int, width: Float) {
        if (xs.size < 2) return
        content.append(rgb(color, true)).append("${n(width)} w 1 J 1 j ")
        for (i in xs.indices) content.append("${n(xs[i])} ${yy(ys[i])} ${if (i == 0) "m" else "l"} ")
        content.append("S\n")
    }

    fun fillPolygon(xs: FloatArray, ys: FloatArray, color: Int) {
        if (xs.size < 3) return
        content.append(rgb(color, false))
        for (i in xs.indices) content.append("${n(xs[i])} ${yy(ys[i])} ${if (i == 0) "m" else "l"} ")
        content.append("f\n")
    }

    fun fillCircle(cx: Float, cy: Float, r: Float, color: Int) {
        val k = 0.5523f * r
        content.append(rgb(color, false))
        content.append("${n(cx + r)} ${yy(cy)} m ")
        content.append("${n(cx + r)} ${yy(cy - k)} ${n(cx + k)} ${yy(cy - r)} ${n(cx)} ${yy(cy - r)} c ")
        content.append("${n(cx - k)} ${yy(cy - r)} ${n(cx - r)} ${yy(cy - k)} ${n(cx - r)} ${yy(cy)} c ")
        content.append("${n(cx - r)} ${yy(cy + k)} ${n(cx - k)} ${yy(cy + r)} ${n(cx)} ${yy(cy + r)} c ")
        content.append("${n(cx + k)} ${yy(cy + r)} ${n(cx + r)} ${yy(cy + k)} ${n(cx + r)} ${yy(cy)} c f\n")
    }

    /** Everything drawn until [clipEnd] is cut to this rectangle. */
    fun clipBegin(x: Float, y: Float, cw: Float, ch: Float) { content.append("q ${n(x)} ${n(h - y - ch)} ${n(cw)} ${n(ch)} re W n\n") }
    fun clipEnd() { content.append("Q\n") }

    /** Draws [s] with its baseline at [baseline]. Characters WinAnsi cannot hold are written as '?'. */
    fun text(x: Float, baseline: Float, s: CharSequence, font: Int, size: Float, color: Int, wordSpace: Float = 0f) {
        if (s.isEmpty()) return
        fonts.add(font)
        content.append(rgb(color, false))
        content.append("BT /F$font ${n(size)} Tf ")
        if (wordSpace != 0f) content.append("${PdfDoc.num(wordSpace)} Tw ")
        content.append("${n(x)} ${yy(baseline)} Td (")
        for (ch in s) {
            val b = if (PdfFonts.canEncode(ch)) PdfFonts.byteOf(ch) else if (ch == '\t') 32 else 63
            when {
                b == '('.code || b == ')'.code || b == '\\'.code -> { content.append('\\'); content.append(b.toChar()) }
                b in 32..126 -> content.append(b.toChar())
                else -> content.append(String.format("\\%03o", b))
            }
        }
        content.append(") Tj ")
        if (wordSpace != 0f) content.append("0 Tw ")
        content.append("ET\n")
    }

    fun image(img: PdfImage, x: Float, y: Float, iw: Float, ih: Float) {
        val i = doc.addImage(img); imageIdx.add(i)
        content.append("q ${n(iw)} 0 0 ${n(ih)} ${n(x)} ${n(h - y - ih)} cm /Im$i Do Q\n")
    }

    fun link(x: Float, y: Float, lw: Float, lh: Float, url: String) { links.add(PdfLink(x, y, lw, lh, url)) }
}
