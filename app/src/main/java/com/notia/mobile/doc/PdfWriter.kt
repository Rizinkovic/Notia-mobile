package com.notia.mobile.doc

import com.notia.mobile.core.CHART_PALETTE
import com.notia.mobile.core.fmtNum

/** What the PDF writer needs from the platform: picture decoding and a way to draw text that WinAnsi cannot hold. */
interface PdfAssets {
    /** Decodes [bytes], scales them for printing at [widthPt] points wide (about 150 dpi) and re-encodes them small. Null when unreadable. */
    fun picture(bytes: ByteArray, widthPt: Float): PdfImage?

    /** Draws a paragraph (CJK, Cyrillic, emoji, ...) as a picture [widthPt] wide. Null when not possible. */
    fun raster(p: Para, widthPt: Float): RasterPara?
}

/** [bottoms] holds the bottom edge of every text line in points from the top of the picture, so pages can break between lines. */
class RasterPara(val image: PdfImage, val heightPt: Float, val bottoms: FloatArray)

/**
 * Lays the block model out on A4 pages. Text is real PDF text in a standard font (selectable, searchable, a few KB per page);
 * pictures are downscaled JPEGs; only paragraphs in scripts the standard fonts lack become small pictures.
 * Page size matters more than looks here: a note with a few lines and one photo is ~100-300 KB instead of several MB.
 */
class PdfWriter(private val title: String, private val blocks: List<Block>, private val assets: PdfAssets? = null) {
    private val pw = 595f
    private val ph = 842f
    private val m = 40f
    private val cw = pw - 2 * m
    private val doc = PdfDoc(title)
    private var page: PdfPage? = null
    private var y = m

    private companion object {
        const val BASE = 11f
        const val LEAD = 1.3f
        const val BLACK = 0x000000
    }

    // ---------- pages ----------
    private fun newPage(): PdfPage { val p = doc.newPage(pw, ph); page = p; y = m; return p }
    private fun pg(): PdfPage = page ?: newPage()
    private fun room() = ph - m - y
    private fun ensure(h: Float) { if (h > room() && y > m + 1) newPage() }

    // ---------- tokens ----------
    private class Item(val text: String, val font: Int, val size: Float, val fmt: Fmt, val space: Boolean) {
        val w: Float = PdfFonts.width(font, text, size)
    }

    private sealed class Tok
    private class Word(val items: List<Item>) : Tok() { val w: Float = items.sumOf { it.w.toDouble() }.toFloat() }
    private class Gap(val item: Item) : Tok()
    private object Brk : Tok()

    private fun ignorable(c: Char) = c in '​'..'‏' || c == '﻿' || c == '️' || c == '⁠'
    private fun isGap(c: Char) = c == ' ' || c == '\t' || c == ' ' || (c.isWhitespace() && c != '\n' && c != '\r')

    private fun sizeOf(f: Fmt) = if (f.size > 0) f.size / 2f else if (f.code) 10f else BASE
    private fun fontOf(f: Fmt) = PdfFonts.style(f.b, f.i, f.code)

    private fun tokens(runs: List<Inline>): List<Tok> {
        val out = ArrayList<Tok>()
        var word = ArrayList<Item>()
        fun endWord() { if (word.isNotEmpty()) { out.add(Word(word)); word = ArrayList() } }
        for (r in runs) when (r) {
            is LineBreak -> { endWord(); out.add(Brk) }
            is ImageRun -> {}
            is TextRun -> {
                val f = r.fmt; val font = fontOf(f); val size = sizeOf(f)
                val sb = StringBuilder()
                fun flushText() { if (sb.isNotEmpty()) { word.add(Item(sb.toString(), font, size, f, false)); sb.setLength(0) } }
                var i = 0
                val t = r.text
                while (i < t.length) {
                    val c = t[i]
                    when {
                        c == '\n' -> { flushText(); endWord(); out.add(Brk) }
                        c == '\r' || ignorable(c) -> {}
                        isGap(c) -> {
                            flushText(); endWord()
                            var n = if (c == '\t') 4 else 1
                            while (i + 1 < t.length && isGap(t[i + 1])) { i++; n += if (t[i] == '\t') 4 else 1 }
                            out.add(Gap(Item(" ".repeat(if (font >= 4) n else 1), font, size, f, true)))
                        }
                        else -> sb.append(c)
                    }
                    i++
                }
                flushText()
            }
        }
        endWord()
        return out
    }

    // ---------- lines ----------
    private class Line(val items: ArrayList<Item>, val hard: Boolean) {
        var width = 0f
        var size = BASE
        val height get() = size * LEAD
    }

    private fun breakLines(toks: List<Tok>, width: Float): List<Line> {
        val lines = ArrayList<Line>()
        var cur = ArrayList<Item>(); var curW = 0f; var curSize = 0f
        var gap: Item? = null
        fun emit(hard: Boolean) {
            val l = Line(cur, hard); l.width = curW; l.size = if (curSize > 0) curSize else BASE
            lines.add(l); cur = ArrayList(); curW = 0f; curSize = 0f; gap = null
        }
        fun put(it: Item) { cur.add(it); curW += it.w; if (it.size > curSize) curSize = it.size }
        for (t in toks) when (t) {
            is Brk -> emit(true)
            is Gap -> { if (cur.isNotEmpty() || t.item.font >= 4) gap = t.item }
            is Word -> {
                val gw = gap?.w ?: 0f
                if (cur.isNotEmpty() && curW + gw + t.w > width + 0.01f) emit(false)
                if (t.w > width) {
                    // a single word wider than the line: cut it where it no longer fits
                    gap?.let { g -> if (cur.isNotEmpty()) put(g) }; gap = null
                    for (it in t.items) {
                        var s = 0
                        while (s < it.text.length) {
                            var e = s
                            var w = 0f
                            while (e < it.text.length) {
                                val cwid = PdfFonts.width(it.font, it.text.substring(e, e + 1), it.size)
                                if (curW + w + cwid > width && (e > s || cur.isNotEmpty())) break
                                w += cwid; e++
                            }
                            if (e == s) { emit(false); continue }
                            put(Item(it.text.substring(s, e), it.font, it.size, it.fmt, false))
                            s = e
                            if (s < it.text.length) emit(false)
                        }
                    }
                } else {
                    gap?.let { g -> if (cur.isNotEmpty()) put(g) }; gap = null
                    for (it in t.items) put(it)
                }
            }
        }
        if (cur.isNotEmpty() || lines.isEmpty()) emit(true)
        return lines
    }

    // ---------- laid out paragraphs ----------
    /** A paragraph measured and ready to draw in pieces: unit i spans top(i) until top(i+1). */
    private abstract class Laid {
        abstract val n: Int
        abstract fun top(i: Int): Float
        abstract fun draw(p: PdfPage, x: Float, y: Float, i: Int, j: Int)
        val height get() = top(n)
    }

    private fun col(hex: String?, def: Int): Int = hex?.toIntOrNull(16) ?: def

    /** [prefixDx] is where the list marker starts, relative to the text's left edge (negative: it hangs to the left). */
    private inner class Lines(val lines: List<Line>, val width: Float, val align: String?, val prefix: Item?, val prefixDx: Float, val quote: Boolean) : Laid() {
        private val tops = FloatArray(lines.size + 1).also { t -> for (k in lines.indices) t[k + 1] = t[k] + lines[k].height }
        override val n get() = lines.size
        override fun top(i: Int) = tops[i]
        override fun draw(p: PdfPage, x: Float, y: Float, i: Int, j: Int) {
            for (k in i until j) {
                val l = lines[k]; val ty = y + tops[k] - tops[i]
                if (quote) p.fillRect(x - 12f, ty, 2.5f, l.height, 0xB4AAE6)
                val base = ty + l.size * 1.0f
                if (k == 0 && prefix != null) p.text(x + prefixDx, base, prefix.text, prefix.font, prefix.size, BLACK)
                drawLine(p, l, x, ty, base, width, align)
            }
        }
    }

    private fun drawLine(p: PdfPage, l: Line, x: Float, top: Float, base: Float, width: Float, align: String?) {
        if (l.items.isEmpty()) return
        var extra = 0f
        if (align == "justify" && !l.hard) {
            val gaps = l.items.sumOf { if (it.space) it.text.length else 0 }
            if (gaps > 0) { val e = (width - l.width) / gaps; if (e > 0f && e < 14f) extra = e }
        }
        val total = l.width + extra * l.items.sumOf { if (it.space) it.text.length else 0 }
        var cx = when (align) { "center" -> x + (width - total) / 2f; "right" -> x + width - total; else -> x }
        var i = 0
        while (i < l.items.size) {
            // merge neighbouring items with the same look into one text run (keeps spaces in the text for search and copy)
            var j = i + 1
            val a = l.items[i]
            while (j < l.items.size && l.items[j].font == a.font && l.items[j].size == a.size && l.items[j].fmt.color == a.fmt.color &&
                l.items[j].fmt.bg == a.fmt.bg && l.items[j].fmt.u == a.fmt.u && l.items[j].fmt.link == a.fmt.link) j++
            val sb = StringBuilder(); var w = 0f; var sp = 0
            for (k in i until j) { sb.append(l.items[k].text); w += l.items[k].w; if (l.items[k].space) sp += l.items[k].text.length }
            val rw = w + extra * sp
            val f = a.fmt
            val color = col(f.color, BLACK)
            if (f.bg != null) p.fillRect(cx, base - a.size * 0.82f, rw, a.size * 1.05f, col(f.bg, 0xFFF3A3))
            p.text(cx, base, sb, a.font, a.size, color, extra)
            if (f.u) p.line(cx, base + a.size * 0.12f, cx + rw, base + a.size * 0.12f, color, Math.max(0.5f, a.size / 18f))
            if (f.link != null && f.link.isNotEmpty()) p.link(cx, base - a.size * 0.85f, rw, a.size * 1.1f, f.link)
            cx += rw
            i = j
        }
    }

    private inner class Raster(val rp: RasterPara, val w: Float) : Laid() {
        override val n get() = rp.bottoms.size
        override fun top(i: Int) = if (i <= 0) 0f else if (i >= n) rp.heightPt else rp.bottoms[i - 1]
        override fun draw(p: PdfPage, x: Float, y: Float, i: Int, j: Int) {
            p.clipBegin(x, y, w, top(j) - top(i))
            p.image(rp.image, x, y - top(i), w, rp.heightPt)
            p.clipEnd()
        }
    }

    private fun needsRaster(p: Para): Boolean {
        for (r in p.runs) if (r is TextRun) for (c in r.text) if (!ignorable(c) && !isGap(c) && c != '\n' && c != '\r' && !PdfFonts.canEncode(c)) return true
        return false
    }

    /** Indent of the text for a paragraph, and where the list marker sits. */
    private fun indentOf(p: Para): Float = if (p.prefix != null) 16f * Math.max(1, p.listDepth) else if (p.quote) 18f else 0f

    /** Measures a paragraph for a column [width] wide; draw it with the column's left edge as x. */
    private fun layout(p: Para, width: Float): Laid? {
        val ind = indentOf(p)
        val tw = Math.max(20f, width - ind)
        if (assets != null && needsRaster(p)) {
            val rp = try { assets.raster(p, tw) } catch (e: Throwable) { null }
            if (rp != null && rp.bottoms.isNotEmpty()) return Shifted(Raster(rp, tw), ind)
        }
        val lines = breakLines(tokens(p.runs), tw)
        if (p.prefix != null) {
            val pf = Item(p.prefix.trimEnd(), PdfFonts.REGULAR, BASE, Fmt(), false)
            return Shifted(Lines(lines, tw, p.align, pf, -pf.w - 4f, false), ind)
        }
        return Shifted(Lines(lines, tw, p.align, null, 0f, p.quote), ind)
    }

    /** A laid-out paragraph whose text starts [dx] to the right of the column edge (list and quote indents). */
    private inner class Shifted(val inner: Laid, val dx: Float) : Laid() {
        override val n get() = inner.n
        override fun top(i: Int) = inner.top(i)
        override fun draw(p: PdfPage, x: Float, y: Float, i: Int, j: Int) = inner.draw(p, x + dx, y, i, j)
    }

    // ---------- blocks ----------
    private fun drawLaid(l: Laid, x: Float) {
        var i = 0
        while (i < l.n) {
            val avail = room()
            var j = i
            while (j < l.n && l.top(j + 1) - l.top(i) <= avail + 0.01f) j++
            if (j == i) { if (y > m + 1) { newPage(); continue }; j = i + 1 }
            l.draw(pg(), x, y, i, j)
            y += l.top(j) - l.top(i)
            i = j
            if (i < l.n) newPage()
        }
    }

    private fun drawImage(im: ImageRun, align: String?) {
        val a = assets ?: return
        val target = if (im.pct > 0) cw * im.pct / 100f else Math.min(cw, if (im.w > 0) im.w * 0.75f else cw)
        val pic = try { a.picture(im.bytes, target) } catch (e: Throwable) { null } ?: return
        var w = target; var h = w * pic.h / pic.w
        val maxH = ph - 2 * m
        if (h > maxH) { w = w * maxH / h; h = maxH }
        ensure(h + 6)
        val x = when (align) { "center" -> m + (cw - w) / 2f; "right" -> m + cw - w; else -> m }
        pg().image(pic, x, y, w, h)
        y += h + 6
    }

    private fun drawPara(p: Para) {
        var seg = ArrayList<Inline>()
        var first = true
        fun flushSeg() {
            if (seg.isEmpty() && !(first && p.prefix != null)) return
            val part = Para(seg, if (first) p.prefix else null, p.listDepth, p.quote, p.align)
            val laid = layout(part, cw)
            if (laid != null && laid.n > 0) drawLaid(laid, m)
            first = false; seg = ArrayList()
        }
        for (r in p.runs) {
            if (r is ImageRun) { flushSeg(); drawImage(r, p.align) } else seg.add(r)
        }
        flushSeg()
        y += 5f
    }

    private fun drawTable(t: TableBlock) {
        if (t.rows.isEmpty()) return
        val cols = Math.max(1, t.rows.maxOf { it.size })
        val colW = cw / cols
        val grey = 0x969696
        for (row in t.rows) {
            val inner = colW - 10
            val cells = (0 until cols).map { c ->
                row.getOrNull(c)?.mapNotNull { b -> if (b is Para) layout(Para(b.runs.filter { it !is ImageRun }, b.prefix, b.listDepth, false, b.align), inner) else null } ?: emptyList()
            }
            val rowH = (cells.maxOfOrNull { cl -> Math.max(14f, cl.sumOf { (it.height + 2f).toDouble() }.toFloat()) } ?: 14f) + 8f
            ensure(Math.min(rowH, ph - 2 * m))
            val top = y
            val shown = Math.min(rowH, ph - m - top)
            val p = pg()
            for (c in 0 until cols) {
                val x = m + c * colW
                p.strokeRect(x, top, colW, shown, grey, 0.8f)
                p.clipBegin(x, top, colW, shown)
                var cy = top + 4
                for (l in cells[c]) { l.draw(p, x + 5, cy, 0, l.n); cy += l.height + 2f }
                p.clipEnd()
            }
            y = top + shown
        }
        y += 8f
    }

    private fun drawChart(c: ChartBlock) {
        val h = 200f
        ensure(h + 10)
        val p = pg()
        val x0 = m; val top = y
        fun clean(s: String) = buildString { for (ch in s) append(if (PdfFonts.canEncode(ch)) ch else '?') }
        p.text(x0, top + 12, clean(c.title.take(60)), PdfFonts.BOLD, 12f, BLACK)
        val gray = 0x3C3C3C
        val pts = c.pts
        val max = Math.max(pts.maxOfOrNull { it.value } ?: 1.0, 1.0)
        fun color(i: Int) = CHART_PALETTE[i % CHART_PALETTE.size] and 0xFFFFFF
        if (c.type == "pie") {
            val tot = pts.sumOf { it.value }.let { if (it == 0.0) 1.0 else it }
            val cx = x0 + 80f; val cy = top + 100f; val r = 70f
            var a = -90.0
            pts.forEachIndexed { i, pt ->
                val sweep = pt.value / tot * 360.0
                if (sweep > 0) {
                    val steps = Math.max(2, Math.ceil(sweep / 4.0).toInt())
                    val xs = FloatArray(steps + 2); val ys = FloatArray(steps + 2)
                    xs[0] = cx; ys[0] = cy
                    for (k in 0..steps) {
                        val ang = Math.toRadians(a + sweep * k / steps)
                        xs[k + 1] = (cx + r * Math.cos(ang)).toFloat(); ys[k + 1] = (cy + r * Math.sin(ang)).toFloat()
                    }
                    p.fillPolygon(xs, ys, color(i))
                }
                a += sweep
                p.fillRect(x0 + 170, top + 36 + i * 14, 8f, 8f, color(i))
                p.text(x0 + 184, top + 43.5f + i * 14, clean("${pt.label.take(16)} (${fmtNum(pt.value)})"), PdfFonts.REGULAR, 8f, gray)
            }
        } else if (pts.isNotEmpty()) {
            val left = x0 + 10; val right = x0 + 330; val areaTop = top + 30; val bottom = top + 170
            p.line(left, bottom, right, bottom, 0x888888, 0.8f)
            val bw = (right - left) / pts.size
            fun px(i: Int) = left + (i + 0.5f) * bw
            fun py(v: Double) = (bottom - (v / max) * (bottom - areaTop)).toFloat()
            if (c.type == "line") {
                val xs = FloatArray(pts.size) { px(it) }; val ys = FloatArray(pts.size) { py(pts[it].value) }
                p.polyline(xs, ys, 0x8B6CF6, 2f)
                for (i in pts.indices) p.fillCircle(xs[i], ys[i], 3f, 0x8B6CF6)
            } else pts.forEachIndexed { i, pt -> p.fillRect(px(i) - bw * 0.35f, py(pt.value), bw * 0.7f, bottom - py(pt.value), color(i)) }
            pts.forEachIndexed { i, pt ->
                val lbl = clean(pt.label.take(Math.max(3, (bw / 4.5f).toInt())))
                p.text(px(i) - PdfFonts.width(PdfFonts.REGULAR, lbl, 8f) / 2f, bottom + 11, lbl, PdfFonts.REGULAR, 8f, gray)
                val v = fmtNum(pt.value)
                p.text(px(i) - PdfFonts.width(PdfFonts.REGULAR, v, 8f) / 2f, py(pt.value) - 3, v, PdfFonts.REGULAR, 8f, gray)
            }
        }
        y = top + h + 6
    }

    fun build(): ByteArray {
        newPage()
        val tl = layout(Para(listOf(TextRun(title.ifEmpty { "Untitled" }, Fmt(b = true, size = 44))), null, 0, false), cw)
        if (tl != null) drawLaid(tl, m)
        y += 10f
        for (b in blocks) when (b) {
            is Para -> drawPara(b)
            is Rule -> { ensure(14f); pg().line(m, y + 5, m + cw, y + 5, 0xA0A0A0, 0.8f); y += 14f }
            is TableBlock -> drawTable(b)
            is ChartBlock -> drawChart(b)
        }
        return doc.build()
    }
}
