package com.notia.mobile.doc

import java.nio.charset.Charset

/**
 * The PDF "standard 14" fonts need no embedding, which keeps exported files tiny. Text is written in WinAnsi (Windows-1252),
 * the same Latin set the desktop app exports. Anything outside it is rendered by the caller as a small picture instead.
 * Widths are the Adobe AFM widths (1/1000 em) for bytes 32..255.
 */
object PdfFonts {
    const val REGULAR = 0
    const val BOLD = 1
    const val ITALIC = 2
    const val BOLD_ITALIC = 3
    const val MONO = 4
    const val MONO_BOLD = 5
    const val MONO_ITALIC = 6
    const val MONO_BOLD_ITALIC = 7

    val BASE = arrayOf("Helvetica", "Helvetica-Bold", "Helvetica-Oblique", "Helvetica-BoldOblique",
        "Courier", "Courier-Bold", "Courier-Oblique", "Courier-BoldOblique")

    private val CP: Charset = Charset.forName("windows-1252")

    private val HELV = intArrayOf(
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584, 761,
        556, 278, 222, 556, 333, 1000, 556, 556, 333, 1000, 667, 333, 1000, 278, 611, 278,
        278, 222, 222, 333, 333, 350, 556, 1000, 333, 1000, 500, 333, 944, 278, 500, 667,
        278, 333, 556, 556, 556, 556, 260, 556, 333, 737, 370, 556, 584, 333, 737, 333,
        400, 584, 333, 333, 333, 556, 537, 278, 333, 333, 365, 556, 834, 834, 834, 611,
        667, 667, 667, 667, 667, 667, 1000, 722, 667, 667, 667, 667, 278, 278, 278, 278,
        722, 722, 778, 778, 778, 778, 778, 584, 778, 722, 722, 722, 722, 667, 667, 611,
        556, 556, 556, 556, 556, 556, 889, 500, 556, 556, 556, 556, 278, 278, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 584, 611, 556, 556, 556, 556, 500, 556, 500,
    )
    private val HELV_BOLD = intArrayOf(
        278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611,
        975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556,
        333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611,
        611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584, 761,
        556, 278, 278, 556, 500, 1000, 556, 556, 333, 1000, 667, 333, 1000, 278, 611, 278,
        278, 278, 278, 500, 500, 350, 556, 1000, 333, 1000, 556, 333, 944, 278, 500, 667,
        278, 333, 556, 556, 556, 556, 280, 556, 333, 737, 370, 556, 584, 333, 737, 333,
        400, 584, 333, 333, 333, 611, 556, 278, 333, 333, 365, 556, 834, 834, 834, 611,
        722, 722, 722, 722, 722, 722, 1000, 722, 667, 667, 667, 667, 278, 278, 278, 278,
        722, 722, 778, 778, 778, 778, 778, 584, 778, 722, 722, 722, 722, 667, 667, 611,
        556, 556, 556, 556, 556, 556, 889, 556, 556, 556, 556, 556, 278, 278, 278, 278,
        611, 611, 611, 611, 611, 611, 611, 584, 611, 611, 611, 611, 611, 556, 611, 556,
    )

    fun style(bold: Boolean, italic: Boolean, mono: Boolean): Int =
        (if (mono) 4 else 0) + (if (bold) 1 else 0) + (if (italic) 2 else 0)

    /** Width of one WinAnsi byte in 1/1000 em. */
    fun widthOf(font: Int, b: Int): Int {
        if (font >= 4) return 600
        val i = b - 32
        if (i < 0 || i >= HELV.size) return 278
        return if (font and 1 == 1) HELV_BOLD[i] else HELV[i]
    }

    /** All widths 32..255 of the font (for the /Widths entry). */
    fun widths(font: Int): IntArray = IntArray(224) { widthOf(font, it + 32) }

    /** Can this character be written in WinAnsi? Control characters other than tab/newline cannot. */
    fun canEncode(c: Char): Boolean {
        if (c.code < 32) return false
        if (c.code < 127) return true
        if (c == '\u00A0' || c == '\u00AD') return true
        if (c.code in 127..159) return false
        if (c.code in 160..255) return true
        return when (c) {
            '\u20AC', '\u201A', '\u0192', '\u201E', '\u2026', '\u2020', '\u2021', '\u02C6', '\u2030', '\u0160', '\u2039', '\u0152',
            '\u017D', '\u2018', '\u2019', '\u201C', '\u201D', '\u2022', '\u2013', '\u2014', '\u02DC', '\u2122', '\u0161', '\u203A',
            '\u0153', '\u017E', '\u0178' -> true
            else -> false
        }
    }

    /** Byte of a character in WinAnsi; non-breaking space becomes a plain space, soft hyphen a hyphen. */
    fun byteOf(c: Char): Int {
        if (c == '\u00A0') return 32
        if (c == '\u00AD') return 45
        if (c.code < 128) return c.code
        val b = c.toString().toByteArray(CP)
        return if (b.size == 1) b[0].toInt() and 0xFF else 63
    }

    fun width(font: Int, s: CharSequence, size: Float): Float {
        var w = 0
        for (ch in s) w += widthOf(font, byteOf(ch))
        return w * size / 1000f
    }
}
