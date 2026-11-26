package com.notia.mobile.doc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import java.io.ByteArrayOutputStream

/**
 * Picture handling shared by every export (PDF, DOCX, scans). Everything is downscaled to what the page can actually show and
 * stored as JPEG, which is what keeps a note with a photo at a few hundred KB instead of several MB.
 */
object Images {
    /** Print resolution for pictures in PDF files (points are 1/72 inch). */
    private const val DPI = 150f
    const val MAX_PX = 1100

    class Small(val bytes: ByteArray, val ext: String, val w: Int, val h: Int)

    /** Decodes with a power-of-two sample size so that the long edge stays at least [longEdge] pixels (or the full size when smaller). */
    fun decode(bytes: ByteArray, longEdge: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) null
        else {
            var s = 1
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= longEdge) s *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
        }
    } catch (e: Throwable) { null }

    /** Scales [b] so its width is at most [maxW] (never enlarges). Recycles [b] when a copy is made. */
    fun fit(b: Bitmap, maxW: Int): Bitmap {
        if (b.width <= maxW) return b
        val h = Math.max(1, Math.round(b.height.toFloat() * maxW / b.width))
        val r = Bitmap.createScaledBitmap(b, maxW, h, true)
        if (r !== b) b.recycle()
        return r
    }

    /** Transparent pixels become white (JPEG has no alpha). */
    fun flatten(b: Bitmap): Bitmap {
        if (!b.hasAlpha()) return b
        val o = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(o).apply { drawColor(Color.WHITE); drawBitmap(b, 0f, 0f, null) }
        b.recycle()
        return o
    }

    fun jpeg(b: Bitmap, quality: Int): ByteArray = ByteArrayOutputStream(64 * 1024).also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /** A picture for a PDF page [widthPt] points wide. */
    fun forPdf(bytes: ByteArray, widthPt: Float, quality: Int = 72): PdfImage? {
        val target = Math.max(240, Math.min(MAX_PX, Math.round(widthPt / 72f * DPI)))
        val raw = decode(bytes, target) ?: return null
        val b = flatten(fit(raw, target))
        val data = jpeg(b, quality)
        val r = PdfImage(b.width, b.height, data, true)
        b.recycle()
        return r
    }

    /** A smaller copy of a picture for DOCX files: opaque images become JPEG, images with transparency stay PNG. */
    fun forDocx(bytes: ByteArray, quality: Int = 75): Small? {
        val raw = decode(bytes, MAX_PX) ?: return null
        val alpha = raw.hasAlpha()
        val b = fit(raw, MAX_PX)
        val out = ByteArrayOutputStream(64 * 1024)
        if (alpha) b.compress(Bitmap.CompressFormat.PNG, 100, out) else b.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val s = Small(out.toByteArray(), if (alpha) "png" else "jpeg", b.width, b.height)
        b.recycle()
        // never make a picture bigger than it was
        return if (s.bytes.size < bytes.size) s else null
    }

    /** Reads a picture from a content/file URI, applies its EXIF rotation and shrinks it to [longEdge] pixels. */
    fun load(ctx: Context, uri: Uri, longEdge: Int): Bitmap {
        val cr = ctx.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= longEdge) s *= 2
        var bmp = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
            ?: throw java.io.IOException("decode")
        val rot = try { cr.openInputStream(uri)!!.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } } catch (e: Exception) { 1 }
        val deg = when (rot) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        if (deg != 0f) { val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true); if (r !== bmp) bmp.recycle(); bmp = r }
        // scale so the LONG edge is at most longEdge
        val long = Math.max(bmp.width, bmp.height)
        if (long > longEdge) {
            val k = longEdge.toFloat() / long
            val r = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bmp.width * k)), Math.max(1, Math.round(bmp.height * k)), true)
            if (r !== bmp) bmp.recycle(); bmp = r
        }
        return flatten(bmp)
    }
}

/** The platform half of the PDF writer: picture re-encoding and the picture fallback for non-Latin text. */
object PdfAndroid : PdfAssets {
    private const val SCALE = 2f          // 144 dpi
    private const val MAX_H = 12000       // pixels per paragraph picture

    override fun picture(bytes: ByteArray, widthPt: Float): PdfImage? = Images.forPdf(bytes, widthPt)

    private fun colorOf(hex: String?, def: Int): Int = try { if (hex == null) def else Color.parseColor("#$hex") } catch (e: Exception) { def }

    override fun raster(p: Para, widthPt: Float): RasterPara? {
        val sb = SpannableStringBuilder()
        var colored = false
        if (p.prefix != null) sb.append(p.prefix)
        for (r in p.runs) when (r) {
            is TextRun -> {
                val s = sb.length; sb.append(r.text); val e = sb.length
                if (e == s) continue
                val f = r.fmt; val fl = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                val style = (if (f.b) Typeface.BOLD else 0) or (if (f.i) Typeface.ITALIC else 0)
                if (style != 0) sb.setSpan(StyleSpan(style), s, e, fl)
                if (f.u) sb.setSpan(UnderlineSpan(), s, e, fl)
                if (f.code) sb.setSpan(TypefaceSpan("monospace"), s, e, fl)
                if (f.color != null) { sb.setSpan(ForegroundColorSpan(colorOf(f.color, Color.BLACK)), s, e, fl); colored = true }
                if (f.bg != null) { sb.setSpan(BackgroundColorSpan(colorOf(f.bg, Color.YELLOW)), s, e, fl); colored = true }
                val size = if (f.size > 0) f.size / 2f else if (f.code) 10f else 11f
                sb.setSpan(AbsoluteSizeSpan(Math.round(size * SCALE), false), s, e, fl)
            }
            is LineBreak -> sb.append('\n')
            is ImageRun -> {}
        }
        if (sb.isEmpty()) return null
        val wPx = Math.max(40, Math.round(widthPt * SCALE))
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * SCALE; color = Color.BLACK }
        val align = when (p.align) { "center" -> Layout.Alignment.ALIGN_CENTER; "right" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
        val b = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, wPx).setAlignment(align).setLineSpacing(0f, 1.3f)
        if (p.align == "justify" && android.os.Build.VERSION.SDK_INT >= 26) b.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        val l = b.build()
        val hPx = l.height
        if (hPx <= 0 || hPx > MAX_H) return null

        // draw in bands so a long paragraph never needs a full-size bitmap
        val band = 800
        val bmp = Bitmap.createBitmap(wPx, Math.min(band, hPx), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val bpp = if (colored) 3 else 1
        val out = ByteArray(wPx * hPx * bpp)
        val row = IntArray(wPx)
        var top = 0; var o = 0
        while (top < hPx) {
            val bh = Math.min(band, hPx - top)
            bmp.eraseColor(Color.WHITE)
            cv.save(); cv.translate(0f, -top.toFloat()); l.draw(cv); cv.restore()
            for (yy in 0 until bh) {
                bmp.getPixels(row, 0, wPx, 0, yy, wPx, 1)
                if (colored) for (px in row) { out[o++] = (px shr 16).toByte(); out[o++] = (px shr 8).toByte(); out[o++] = px.toByte() }
                else for (px in row) out[o++] = (((px shr 16 and 255) * 30 + (px shr 8 and 255) * 59 + (px and 255) * 11) / 100).toByte()
            }
            top += bh
        }
        bmp.recycle()
        val bottoms = FloatArray(l.lineCount) { l.getLineBottom(it) / SCALE }
        return RasterPara(PdfImage(wPx, hPx, out, false, !colored), hPx / SCALE, bottoms)
    }
}

/** Builds a PDF from scanned pages: one picture per page, sized like iPhone Notes scans, a few hundred KB per page. */
object ScanPdf {
    /** Long edge of a page picture: A4 at about 200 dpi. */
    private const val LONG_EDGE = 1650
    private const val QUALITY = 68

    /** One page, white margin-free, in A4 portrait or landscape depending on the picture. */
    fun build(ctx: Context, pages: List<Uri>, title: String, onPage: (Int) -> Unit = {}): ByteArray {
        val doc = PdfDoc(title)
        var n = 0
        for (u in pages) {
            val bmp = Images.load(ctx, u, LONG_EDGE)
            val img = PdfImage(bmp.width, bmp.height, Images.jpeg(bmp, QUALITY), true)
            val portrait = bmp.height >= bmp.width
            val pw = if (portrait) 595f else 842f
            val ph = if (portrait) 842f else 595f
            val k = Math.min(pw / bmp.width, ph / bmp.height)
            val w = bmp.width * k; val h = bmp.height * k
            doc.newPage(pw, ph).image(img, (pw - w) / 2f, (ph - h) / 2f, w, h)
            bmp.recycle()
            onPage(++n)
        }
        return doc.build()
    }
}
