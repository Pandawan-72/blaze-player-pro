package fr.retrospare.blazeplayer.player

import android.graphics.Bitmap
import android.graphics.Color

/** Minimal QR generator for Blaze Party invite payloads.
 * Fixed to QR version 5, ECC-L, byte mode, mask 0.
 *
 * The previous version-2 QR capacity was too small for some Blaze Party deep-link
 * payloads, which crashed the app when hosting a party. Version 5-L keeps the
 * implementation simple while allowing payloads up to 106 ISO-8859-1 bytes.
 */
object SimpleQrCode {
    private const val VERSION = 5
    private const val SIZE = 37
    private const val DATA_CODEWORDS = 108
    private const val ECC_CODEWORDS = 26
    private const val MAX_BYTE_PAYLOAD = DATA_CODEWORDS - 2
    private const val FORMAT_L_MASK_0 = 0x77C4

    fun bitmap(text: String, scale: Int = 8, quiet: Int = 4): Bitmap {
        val modules = encode(text)
        val px = (SIZE + quiet * 2) * scale
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        for (y in 0 until px) for (x in 0 until px) bmp.setPixel(x, y, Color.WHITE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            if (modules[y][x]) {
                for (dy in 0 until scale) for (dx in 0 until scale) {
                    bmp.setPixel((x + quiet) * scale + dx, (y + quiet) * scale + dy, Color.BLACK)
                }
            }
        }
        return bmp
    }

    private fun encode(text: String): Array<BooleanArray> {
        val data = text.toByteArray(Charsets.ISO_8859_1)
        require(data.size <= MAX_BYTE_PAYLOAD) { "Blaze Party QR payload too long" }
        val bits = mutableListOf<Int>()
        fun append(value: Int, count: Int) { for (i in count - 1 downTo 0) bits += (value ushr i) and 1 }
        append(0b0100, 4) // byte mode
        append(data.size, 8)
        data.forEach { append(it.toInt() and 0xFF, 8) }
        repeat(minOf(4, DATA_CODEWORDS * 8 - bits.size)) { bits += 0 }
        while (bits.size % 8 != 0) bits += 0
        val codewords = mutableListOf<Int>()
        bits.chunked(8).forEach { b -> codewords += b.fold(0) { acc, bit -> (acc shl 1) or bit } }
        var pad = true
        while (codewords.size < DATA_CODEWORDS) {
            codewords += if (pad) 0xEC else 0x11
            pad = !pad
        }
        val all = codewords + reedSolomonRemainder(codewords, ECC_CODEWORDS)

        val m = Array(SIZE) { IntArray(SIZE) { -1 } }
        val reserved = Array(SIZE) { BooleanArray(SIZE) }
        fun set(x: Int, y: Int, black: Boolean, function: Boolean = true) {
            if (x !in 0 until SIZE || y !in 0 until SIZE) return
            m[y][x] = if (black) 1 else 0
            if (function) reserved[y][x] = true
        }
        fun finder(x0: Int, y0: Int) {
            for (dy in -1..7) for (dx in -1..7) {
                val x = x0 + dx; val y = y0 + dy
                val black = dx in 0..6 && dy in 0..6 && (dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx in 2..4 && dy in 2..4))
                set(x, y, black)
            }
        }
        finder(0, 0); finder(SIZE - 7, 0); finder(0, SIZE - 7)
        for (i in 8 until SIZE - 8) { set(i, 6, i % 2 == 0); set(6, i, i % 2 == 0) }
        alignment(30, 30, ::set)
        set(8, 4 * VERSION + 9, true)
        drawFormat(::set)

        val dataBits = all.flatMap { cw -> (7 downTo 0).map { (cw ushr it) and 1 } }
        var bitIndex = 0
        var upward = true
        var x = SIZE - 1
        while (x > 0) {
            if (x == 6) x--
            for (i in 0 until SIZE) {
                val y = if (upward) SIZE - 1 - i else i
                for (xx in x downTo x - 1) {
                    if (!reserved[y][xx]) {
                        val raw = if (bitIndex < dataBits.size) dataBits[bitIndex++] == 1 else false
                        val masked = raw xor ((xx + y) % 2 == 0)
                        m[y][xx] = if (masked) 1 else 0
                    }
                }
            }
            upward = !upward
            x -= 2
        }
        return Array(SIZE) { y -> BooleanArray(SIZE) { x2 -> m[y][x2] == 1 } }
    }

    private fun alignment(cx: Int, cy: Int, set: (Int, Int, Boolean, Boolean) -> Unit) {
        for (dy in -2..2) for (dx in -2..2) {
            set(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1, true)
        }
    }

    private fun drawFormat(set: (Int, Int, Boolean, Boolean) -> Unit) {
        for (i in 0 until 15) {
            val bit = ((FORMAT_L_MASK_0 ushr i) and 1) != 0
            val a = intArrayOf(0,1,2,3,4,5,7,8,8,8,8,8,8,8,8)
            val b = intArrayOf(8,8,8,8,8,8,8,8,7,5,4,3,2,1,0)
            set(a[i], b[i], bit, true)

            val x = if (i < 8) SIZE - 1 - i else 8
            val y = if (i < 8) 8 else SIZE - 1 - (i - 8)
            set(x, y, bit, true)
        }
    }

    private fun reedSolomonRemainder(data: List<Int>, degree: Int): List<Int> {
        val gen = generator(degree)
        val res = IntArray(degree)
        data.forEach { b ->
            val factor = b xor res[0]
            for (i in 0 until degree - 1) res[i] = res[i + 1] xor multiply(gen[i], factor)
            res[degree - 1] = multiply(gen[degree - 1], factor)
        }
        return res.toList()
    }

    private fun generator(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(poly.size + 1)
            for (j in poly.indices) {
                next[j] = next[j] xor multiply(poly[j], exp(i))
                next[j + 1] = next[j + 1] xor poly[j]
            }
            poly = next
        }
        return poly.drop(1).toIntArray()
    }

    private fun multiply(x: Int, y: Int): Int = if (x == 0 || y == 0) 0 else exp(log(x) + log(y))
    private fun exp(i: Int): Int { var x = 1; repeat(i % 255) { x = x shl 1; if (x and 0x100 != 0) x = x xor 0x11D }; return x }
    private fun log(v: Int): Int { var x = 1; for (i in 0 until 255) { if (x == v) return i; x = x shl 1; if (x and 0x100 != 0) x = x xor 0x11D }; error("bad GF value") }
}
