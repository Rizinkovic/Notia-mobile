package com.notia.mobile.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import com.notia.mobile.MainActivity
import com.notia.mobile.core.Mark
import com.notia.mobile.core.newId
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.util.concurrent.Executors

/** Highlight colours offered while reading (yellow first: it is the default for older highlights). */
val HL_COLORS = intArrayOf(0xFFFFE08A.toInt(), 0xFFA8E6A1.toInt(), 0xFFA5D4FF.toInt(), 0xFFFFB3CF.toInt(), 0xFFFFCBA4.toInt())

/** A word of PDF text with its box as fractions (0..1) of the page width and height. */
class PdfWord(val text: String, val l: Float, val t: Float, val r: Float, val b: Float, val newLine: Boolean)
class PdfPageText(val words: List<PdfWord>)

/** Collects the words of one page together with their positions. */
private class WordStripper(private val pgW: Float, private val pgH: Float) : PDFTextStripper() {
    val words = ArrayList<PdfWord>()
    private var pl = 0f; private var pt = 0f; private var pr = 0f; private var pb = 0f
    private var any = false

    override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
        val tps = textPositions ?: return
        val sb = StringBuilder()
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = 0f; var b = 0f
        fun flush() {
            val w = sb.toString().trim()
            if (w.isNotEmpty() && l < r) {
                val nl = Math.max(0f, l / pgW); val nt = Math.max(0f, t / pgH); val nr = Math.min(1f, r / pgW); val nb = Math.min(1f, b / pgH)
                val h = nb - nt
                val newLine = any && Math.abs((nt + nb) / 2 - (pt + pb) / 2) > 0.6f * Math.max(h, pb - pt)
                words.add(PdfWord(w, nl, nt, nr, nb, newLine))
                pl = nl; pt = nt; pr = nr; pb = nb; any = true
            }
            sb.setLength(0); l = Float.MAX_VALUE; t = Float.MAX_VALUE; r = 0f; b = 0f
        }
        for (tp in tps) {
            val u = tp.getUnicode() ?: continue
            if (u.isBlank()) { flush(); continue }
            sb.append(u)
            val x = tp.getXDirAdj(); val w = tp.getWidthDirAdj(); val h = tp.getHeightDir(); val y = tp.getYDirAdj()
            l = Math.min(l, x); r = Math.max(r, x + w); t = Math.min(t, y - h); b = Math.max(b, y + h * 0.2f)
        }
        flush()
    }
}

private fun parseRects(s: String?): List<RectF> {
    if (s.isNullOrEmpty()) return emptyList()
    val out = ArrayList<RectF>()
    for (part in s.split(';')) {
        val v = part.split(',').mapNotNull { it.toFloatOrNull() }
        if (v.size == 4) out.add(RectF(v[0], v[1], v[2], v[3]))
    }
    return out
}

/**
 * PDF reader surface. Pages are drawn as 512 px tiles rendered at the real on-screen resolution, so text stays sharp
 * at any zoom without holding whole zoomed pages in memory. Pinch, double-tap and the +/- buttons zoom (1x to 5x);
 * long-press selects a word, handles extend the selection, and the selection can be copied or highlighted in colour.
 * Text comes from PDFBox (PdfRenderer only produces pixels); scanned PDFs have no text to select.
 */
class PdfView(
    private val a: MainActivity, private val file: File, private val start: Double,
    private val onProgress: (Double) -> Unit, private val onReady: (Int) -> Unit, private val onError: () -> Unit,
    private val onSelection: (Boolean) -> Unit,
) : View(a) {
    companion object { private const val MAX_ZOOM = 5f; private const val T = 512 }

    private val io = Executors.newSingleThreadExecutor()       // PdfRenderer: one page open at a time, so one thread
    private val textIo = Executors.newSingleThreadExecutor()   // PDFBox
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var pd: PDDocument? = null
    private var n = 0
    private var pw = FloatArray(0); private var ph = FloatArray(0)      // page size in points
    private var tops = FloatArray(0); private var dhI = IntArray(0)     // layout at the current zoom
    private var zoom = 1f; private var dwI = 0; private var left = 0f
    private var contentW = 0f; private var contentH = 0f
    private var sx = 0f; private var sy = 0f
    private val pad = a.dp(8).toFloat()
    private val bottomRoom = a.dp(140).toFloat()
    private var opened = false; private var closed = false

    private val tiles = object : LruCache<Long, Bitmap>(Math.min(56L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(k: Long, v: Bitmap) = v.byteCount
    }
    private val thumbs = object : LruCache<Int, Bitmap>(10 * 1024 * 1024) { override fun sizeOf(k: Int, v: Bitmap) = v.byteCount }
    private val pending = HashSet<Long>()
    private val wanted = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val thumbPending = HashSet<Int>()

    private val white = Paint().apply { color = Color.WHITE }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hlPaint = Paint()
    private val selPaint = Paint().apply { color = 0x553D7BFF }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3D7BFF.toInt() }

    // ---- gestures ----
    private val scroller = OverScroller(a)
    private var scaling = false
    private var pinch = 1f; private var fx = 0f; private var fy = 0f
    private val flingStep = object : Runnable {
        override fun run() {
            if (closed) return
            if (scroller.computeScrollOffset()) { sx = scroller.currX.toFloat(); sy = scroller.currY.toFloat(); clampScroll(); invalidate(); notifyProgress(); postOnAnimation(this) }
        }
    }
    private val scaleDetector = ScaleGestureDetector(a, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = true; pinch = 1f; fx = d.focusX; fy = d.focusY; scroller.forceFinished(true); return true }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            pinch = (pinch * d.scaleFactor).coerceIn(1f / zoom, MAX_ZOOM / zoom); fx = d.focusX; fy = d.focusY; invalidate(); return true
        }
        override fun onScaleEnd(d: ScaleGestureDetector) { val f = pinch; pinch = 1f; scaling = false; setZoom(zoom * f, fx, fy) }
    })
    // Only taps, double-taps and long-presses go through GestureDetector; dragging and flinging are handled in onTouchEvent
    // (the nullability of GestureDetector's scroll/fling parameters differs between Android SDK versions).
    private val gestures = GestureDetector(a, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean { setZoom(if (zoom > 1.2f) 1f else 2.5f, e.x, e.y); return true }
        override fun onSingleTapUp(e: MotionEvent): Boolean { if (selPage >= 0) clearSelection(); return true }
        override fun onLongPress(e: MotionEvent) { beginSelection(e.x, e.y) }
    })
    private var vt: android.view.VelocityTracker? = null
    private var lastX = 0f; private var lastY = 0f; private var haveLast = false
    private val minFling = android.view.ViewConfiguration.get(a).scaledMinimumFlingVelocity
    private val maxFling = android.view.ViewConfiguration.get(a).scaledMaximumFlingVelocity

    // ---- selection ----
    private var selPage = -1
    private var selA = 0; private var selF = 0
    private var selWords: List<PdfWord> = emptyList()
    private var dragHandle = 0          // 0 none, 1 start handle, 2 end handle
    private var extending = false       // finger still down after the long-press: dragging extends the selection
    private val texts = LruCache<Int, PdfPageText>(24)
    private val textWaiters = HashMap<Int, MutableList<(PdfPageText) -> Unit>>()
    private var marks: List<Mark> = emptyList()
    private var markRects: Map<String, List<RectF>> = emptyMap()

    init { setBackgroundColor(Th.bg) }

    // ---------------------------------------------------------------- opening and layout

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0 || h <= 0) return
        if (!opened) { opened = true; open() } else if (n > 0) { val f = fraction(); layoutPages(); sy = (f * room()).toFloat(); clampScroll(); invalidate() }
    }

    private fun open() {
        io.execute {
            try {
                val p = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(p)
                pfd = p; renderer = r
                val count = r.pageCount
                if (count <= 0) throw java.io.IOException("empty")
                val w = FloatArray(count); val h = FloatArray(count)
                for (i in 0 until count) { val pg = r.openPage(i); try { w[i] = pg.width.toFloat(); h[i] = pg.height.toFloat() } finally { pg.close() } }
                a.post(Runnable { if (!closed) ready(w, h) })
            } catch (e: Throwable) { a.post(Runnable { if (!closed) onError() }) }
        }
    }

    private fun ready(w: FloatArray, h: FloatArray) {
        n = w.size; pw = w; ph = h; tops = FloatArray(n); dhI = IntArray(n)
        layoutPages()
        sy = start.coerceIn(0.0, 1.0).toFloat() * room(); clampScroll()
        onReady(n); invalidate()
    }

    private fun layoutPages() {
        val fitW = Math.max(1f, width - 2 * pad)
        dwI = Math.max(1, Math.round(fitW * zoom))
        left = Math.max(pad, (width - dwI) / 2f)
        var y = pad
        for (i in 0 until n) { dhI[i] = Math.max(1, Math.round(dwI * ph[i] / pw[i])); tops[i] = y; y += dhI[i] + pad }
        contentW = Math.max(width.toFloat(), left * 2 + dwI)
        contentH = y + bottomRoom
    }

    private fun room() = Math.max(1f, contentH - height)
    fun fraction(): Double = (sy / room()).toDouble().coerceIn(0.0, 1.0)
    private fun clampScroll() { sx = sx.coerceIn(0f, Math.max(0f, contentW - width)); sy = sy.coerceIn(0f, Math.max(0f, contentH - height)) }
    private fun notifyProgress() { if (n > 0) onProgress(fraction()) }

    fun jump(p: Double) { sy = p.coerceIn(0.0, 1.0).toFloat() * room(); clampScroll(); invalidate(); notifyProgress() }
    fun jumpToPage(page: Int) { if (n > 0) { sy = (tops[(page - 1).coerceIn(0, n - 1)] - pad).coerceAtLeast(0f); clampScroll(); invalidate(); notifyProgress() } }

    // ---------------------------------------------------------------- zoom

    fun zoomBy(f: Float) = setZoom(zoom * f, width / 2f, height / 2f)

    private fun setZoom(z: Float, fx: Float, fy: Float) {
        if (n == 0) return
        val nz = z.coerceIn(1f, MAX_ZOOM)
        if (Math.abs(nz - zoom) < 0.01f) { invalidate(); return }
        val docX = sx + fx; val docY = sy + fy; val ratio = nz / zoom
        zoom = nz; layoutPages()
        sx = docX * ratio - fx; sy = docY * ratio - fy; clampScroll()
        invalidate(); notifyProgress()
    }

    // ---------------------------------------------------------------- drawing

    private fun pageAtY(y: Float): Int {
        var lo = 0; var hi = n - 1
        while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (tops[mid] <= y) lo = mid else hi = mid - 1 }
        return lo
    }

    private fun key(page: Int, tx: Int, ty: Int): Long = (page.toLong() shl 40) or (Math.round(zoom * 100).toLong() shl 28) or (tx.toLong() shl 14) or ty.toLong()

    override fun onDraw(c: Canvas) {
        if (n == 0) return
        wanted.clear()
        c.save()
        if (pinch != 1f) c.scale(pinch, pinch, fx, fy)
        c.translate(-sx, -sy)
        // content-space window that is on screen (wider than the view while a pinch preview shrinks the page)
        val visL = sx + fx - fx / pinch; val visR = sx + fx + (width - fx) / pinch
        val visT = sy + fy - fy / pinch; val visB = sy + fy + (height - fy) / pinch
        var i = pageAtY(visT)
        while (i < n && tops[i] < visB) {
            val top = tops[i]; val hgt = dhI[i].toFloat()
            if (top + hgt >= visT) drawPage(c, i, left, top, dwI.toFloat(), hgt, visL, visT, visR, visB)
            i++
        }
        c.restore()
    }

    private fun drawPage(c: Canvas, i: Int, pl: Float, pt: Float, pwid: Float, phei: Float, visL: Float, visT: Float, visR: Float, visB: Float) {
        c.drawRect(pl, pt, pl + pwid, pt + phei, white)
        val th = thumbs.get(i)
        if (th != null) c.drawBitmap(th, null, RectF(pl, pt, pl + pwid, pt + phei), bmpPaint) else requestThumb(i)
        val tilesX = (dwI + T - 1) / T; val tilesY = (dhI[i] + T - 1) / T
        val x0 = Math.max(0, Math.floor(((visL - pl) / T).toDouble()).toInt()); val x1 = Math.min(tilesX - 1, Math.floor(((visR - pl) / T).toDouble()).toInt())
        val y0 = Math.max(0, Math.floor(((visT - pt) / T).toDouble()).toInt()); val y1 = Math.min(tilesY - 1, Math.floor(((visB - pt) / T).toDouble()).toInt())
        for (ty in y0..y1) for (tx in x0..x1) {
            val k = key(i, tx, ty)
            wanted.add(k)
            val b = tiles.get(k)
            if (b != null) c.drawBitmap(b, pl + tx * T, pt + ty * T, bmpPaint) else requestTile(i, tx, ty, k)
        }
        // saved highlights
        for (m in marks) if (m.page == i + 1) {
            hlPaint.color = (if (m.color != 0) m.color else HL_COLORS[0]) and 0x00FFFFFF or 0x77000000
            for (r in markRects[m.id] ?: emptyList()) c.drawRect(pl + r.left * pwid, pt + r.top * phei, pl + r.right * pwid, pt + r.bottom * phei, hlPaint)
        }
        // live selection
        if (selPage == i) {
            for (r in selectionRects()) c.drawRect(pl + r.left * pwid, pt + r.top * phei, pl + r.right * pwid, pt + r.bottom * phei, selPaint)
            val h = handlePoints()
            if (h != null) { val rad = a.dpf(7f); c.drawCircle(pl + h[0] * pwid, pt + h[1] * phei + rad, rad, handlePaint); c.drawCircle(pl + h[2] * pwid, pt + h[3] * phei + rad, rad, handlePaint) }
        }
    }

    private fun requestTile(i: Int, tx: Int, ty: Int, k: Long) {
        if (!pending.add(k)) return
        val dw = dwI; val dh = dhI[i]
        io.execute {
            var bmp: Bitmap? = null
            try {
                val r = renderer
                if (r != null && !closed && wanted.contains(k)) {
                    val pg = r.openPage(i)
                    try {
                        val bw = Math.min(T, dw - tx * T); val bh = Math.min(T, dh - ty * T)
                        if (bw > 0 && bh > 0) {
                            val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                            b.eraseColor(Color.WHITE)
                            val m = Matrix(); val s = dw.toFloat() / pg.width
                            m.setScale(s, s); m.postTranslate(-(tx * T).toFloat(), -(ty * T).toFloat())
                            pg.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp = b
                        }
                    } finally { pg.close() }
                }
            } catch (e: Throwable) { bmp = null }
            a.post(Runnable { pending.remove(k); if (bmp != null && !closed) { tiles.put(k, bmp); invalidate() } })
        }
    }

    /** Small whole-page preview shown while sharp tiles are still being drawn. */
    private fun requestThumb(i: Int) {
        if (!thumbPending.add(i)) return
        io.execute {
            var bmp: Bitmap? = null
            try {
                val r = renderer
                if (r != null && !closed) {
                    val pg = r.openPage(i)
                    try {
                        val w = 320; val h = Math.max(1, Math.round(w * ph[i] / pw[i]))
                        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); b.eraseColor(Color.WHITE)
                        pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); bmp = b
                    } finally { pg.close() }
                }
            } catch (e: Throwable) { bmp = null }
            a.post(Runnable { thumbPending.remove(i); if (bmp != null && !closed) { thumbs.put(i, bmp); invalidate() } })
        }
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (n == 0) return true
        if (dragHandle != 0 || extending) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> moveSelection(ev.x, ev.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragHandle = 0; extending = false; haveLast = false; gestures.onTouchEvent(ev) }
            }
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && selPage >= 0) {
            val h = hitHandle(ev.x, ev.y)
            if (h != 0) { dragHandle = h; parent?.requestDisallowInterceptTouchEvent(true); return true }
        }
        scaleDetector.onTouchEvent(ev)
        gestures.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true); removeCallbacks(flingStep)
                vt?.recycle(); vt = android.view.VelocityTracker.obtain(); vt?.addMovement(ev)
                lastX = ev.x; lastY = ev.y; haveLast = true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                if (ev.pointerCount == 1 && !scaling) {
                    if (haveLast) { sx += lastX - ev.x; sy += lastY - ev.y; clampScroll(); invalidate(); notifyProgress() }
                    lastX = ev.x; lastY = ev.y; haveLast = true
                } else haveLast = false
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> haveLast = false
            MotionEvent.ACTION_UP -> {
                val t = vt
                if (t != null && haveLast && !scaling && ev.pointerCount == 1) {
                    t.addMovement(ev); t.computeCurrentVelocity(1000, maxFling.toFloat())
                    val vx = t.xVelocity; val vy = t.yVelocity
                    if (Math.abs(vx) > minFling || Math.abs(vy) > minFling) {
                        scroller.fling(sx.toInt(), sy.toInt(), (-vx).toInt(), (-vy).toInt(), 0, Math.max(0f, contentW - width).toInt(), 0, Math.max(0f, contentH - height).toInt())
                        postOnAnimation(flingStep)
                    }
                }
                t?.recycle(); vt = null; haveLast = false
            }
            MotionEvent.ACTION_CANCEL -> { vt?.recycle(); vt = null; haveLast = false }
        }
        return true
    }
    // ---------------------------------------------------------------- text selection

    private fun contentX(x: Float) = sx + x
    private fun contentY(y: Float) = sy + y

    private fun withText(page: Int, cb: (PdfPageText) -> Unit) {
        texts.get(page)?.let { cb(it); return }
        val list = textWaiters.getOrPut(page) { ArrayList() }
        list.add(cb)
        if (list.size > 1) return
        textIo.execute {
            var res: PdfPageText
            try {
                if (pd == null) { PDFBoxResourceLoader.init(a.applicationContext); pd = PDDocument.load(file) }
                val st = WordStripper(pw[page], ph[page])
                st.setSortByPosition(true); st.setStartPage(page + 1); st.setEndPage(page + 1)
                st.getText(pd)
                res = PdfPageText(st.words)
            } catch (e: Throwable) { res = PdfPageText(emptyList()) }
            a.post(Runnable {
                if (closed) return@Runnable
                texts.put(page, res)
                textWaiters.remove(page)?.forEach { it(res) }
            })
        }
    }

    private fun nearestWord(words: List<PdfWord>, nx: Float, ny: Float, slack: Float): Int {
        var best = -1; var bd = Float.MAX_VALUE
        for ((k, w) in words.withIndex()) {
            val dx = Math.max(Math.max(w.l - nx, nx - w.r), 0f); val dy = Math.max(Math.max(w.t - ny, ny - w.b), 0f)
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = k }
        }
        return if (best >= 0 && bd <= slack * slack) best else -1
    }

    private fun beginSelection(x: Float, y: Float) {
        if (n == 0 || scaling) return
        val cx = contentX(x); val cy = contentY(y)
        val i = pageAtY(cy)
        if (cy > tops[i] + dhI[i] || cx < left || cx > left + dwI) return
        val nx = (cx - left) / dwI; val ny = (cy - tops[i]) / dhI[i]
        withText(i) { pt ->
            val k = nearestWord(pt.words, nx, ny, 0.04f)
            if (k < 0) { a.toast(L("No selectable text here. Scanned pages have no text layer.", "Aucun texte sélectionnable ici. Les pages numérisées n’ont pas de texte.")); return@withText }
            selPage = i; selWords = pt.words; selA = k; selF = k; extending = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onSelection(true); invalidate()
        }
    }

    private fun moveSelection(x: Float, y: Float) {
        if (selPage < 0 || selPage >= n) return
        val nx = ((contentX(x) - left) / dwI).coerceIn(0f, 1f); val ny = ((contentY(y) - tops[selPage]) / dhI[selPage]).coerceIn(0f, 1f)
        val k = nearestWord(selWords, nx, ny, 2f)
        if (k < 0) return
        val lo = Math.min(selA, selF); val hi = Math.max(selA, selF)
        when {
            dragHandle == 1 -> { selA = k; selF = hi }
            dragHandle == 2 -> { selA = lo; selF = k }
            else -> selF = k
        }
        invalidate()
    }

    private fun hitHandle(x: Float, y: Float): Int {
        val h = handlePoints() ?: return 0
        val rad = a.dpf(7f); val reach = a.dpf(30f)
        val x1 = left + h[0] * dwI - sx; val y1 = tops[selPage] + h[1] * dhI[selPage] - sy + rad
        val x2 = left + h[2] * dwI - sx; val y2 = tops[selPage] + h[3] * dhI[selPage] - sy + rad
        val d1 = Math.hypot((x - x1).toDouble(), (y - y1).toDouble()); val d2 = Math.hypot((x - x2).toDouble(), (y - y2).toDouble())
        return when { d1 <= reach && d1 <= d2 -> 1; d2 <= reach -> 2; else -> 0 }
    }

    /** [x1, y1, x2, y2]: bottom-left of the first selected word and bottom-right of the last (page fractions). */
    private fun handlePoints(): FloatArray? {
        if (selPage < 0 || selWords.isEmpty()) return null
        val lo = selWords[Math.min(selA, selF)]; val hi = selWords[Math.max(selA, selF)]
        return floatArrayOf(lo.l, lo.b, hi.r, hi.b)
    }

    /** Selected words merged into one rectangle per line. */
    private fun selectionRects(): List<RectF> {
        if (selPage < 0 || selWords.isEmpty()) return emptyList()
        val lo = Math.min(selA, selF); val hi = Math.max(selA, selF)
        val out = ArrayList<RectF>()
        var cur: RectF? = null
        for (k in lo..hi) {
            val w = selWords[k]
            val c = cur
            if (c != null && !w.newLine) { c.left = Math.min(c.left, w.l); c.right = Math.max(c.right, w.r); c.top = Math.min(c.top, w.t); c.bottom = Math.max(c.bottom, w.b) }
            else { if (c != null) out.add(c); cur = RectF(w.l, w.t, w.r, w.b) }
        }
        cur?.let { out.add(it) }
        return out
    }

    fun hasSelection() = selPage >= 0

    fun selectedText(): String {
        if (selPage < 0 || selWords.isEmpty()) return ""
        val lo = Math.min(selA, selF); val hi = Math.max(selA, selF)
        val sb = StringBuilder()
        for (k in lo..hi) { val w = selWords[k]; if (k > lo) sb.append(if (w.newLine) '\n' else ' '); sb.append(w.text) }
        return sb.toString()
    }

    fun selectAllOnPage() { if (selPage >= 0 && selWords.isNotEmpty()) { selA = 0; selF = selWords.size - 1; invalidate() } }

    fun clearSelection() {
        if (selPage < 0) return
        selPage = -1; selWords = emptyList(); dragHandle = 0; extending = false
        onSelection(false); invalidate()
    }

    /** Turns the current selection into a highlight mark of the given colour (the caller stores it), then clears it. */
    fun highlightSelection(color: Int): Mark? {
        if (selPage < 0) return null
        val rects = selectionRects()
        if (rects.isEmpty()) return null
        val s = rects.joinToString(";") { r -> String.format(java.util.Locale.US, "%.4f,%.4f,%.4f,%.4f", r.left, r.top, r.right, r.bottom) }
        val page = selPage
        val m = Mark(newId(), (tops[page] / room()).toDouble().coerceIn(0.0, 1.0), page + 1, selectedText().take(500), color, s)
        clearSelection()
        return m
    }

    /** Highlights to draw (PDF highlights carry rectangles; older text-only marks are ignored here). */
    fun setHighlights(list: List<Mark>) {
        marks = list.filter { it.rects != null }
        markRects = marks.associate { it.id to parseRects(it.rects) }
        invalidate()
    }

    fun close() {
        closed = true
        removeCallbacks(flingStep)
        io.execute { try { renderer?.close(); pfd?.close() } catch (e: Exception) {} }
        io.shutdown()
        textIo.execute { try { pd?.close() } catch (e: Exception) {} }
        textIo.shutdown()
    }
}
