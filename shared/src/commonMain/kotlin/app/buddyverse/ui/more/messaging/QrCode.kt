package app.buddyverse.ui.more.messaging

import kotlin.math.abs

/**
 * A QR code encoder (byte mode, error correction M, versions 1–40, best of the 8 masks), so the Telegram and WhatsApp
 * setup QR codes draw natively on phone and desktop alike. Port of Project Nayuki's reference QR Code generator (MIT).
 */
object QrCode {
    /** Rows of modules, `true` = dark, without the quiet zone; null when [text] is too long for a QR code. */
    fun encode(text: String): Array<BooleanArray>? {
        val data = text.encodeToByteArray()
        val ver = (1..40).firstOrNull { v -> 4 + (if (v < 10) 8 else 16) + data.size * 8 <= dataCodewords(v) * 8 } ?: return null
        val bits = Bits()
        bits.add(4, 4)
        bits.add(data.size, if (ver < 10) 8 else 16)
        for (b in data) bits.add(b.toInt() and 0xff, 8)
        val capacity = dataCodewords(ver) * 8
        bits.add(0, minOf(4, capacity - bits.size))
        bits.add(0, (8 - bits.size % 8) % 8)
        var pad = 0xEC
        while (bits.size < capacity) { bits.add(pad, 8); pad = pad xor 0xEC xor 0x11 }
        val codewords = IntArray(bits.size / 8) { i -> (0 until 8).fold(0) { acc, j -> (acc shl 1) or (if (bits[i * 8 + j]) 1 else 0) } }
        return Matrix(ver).build(interleave(ver, codewords))
    }

    private val ECC_PER_BLOCK = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28)
    private val BLOCKS = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49)

    private fun rawModules(ver: Int): Int {
        var r = (16 * ver + 128) * ver + 64
        if (ver >= 2) {
            val n = ver / 7 + 2
            r -= (25 * n - 10) * n - 55
            if (ver >= 7) r -= 36
        }
        return r
    }

    private fun dataCodewords(ver: Int) = rawModules(ver) / 8 - ECC_PER_BLOCK[ver] * BLOCKS[ver]

    private fun interleave(ver: Int, data: IntArray): IntArray {
        val numBlocks = BLOCKS[ver]
        val eccLen = ECC_PER_BLOCK[ver]
        val raw = rawModules(ver) / 8
        val numShort = numBlocks - raw % numBlocks
        val shortLen = raw / numBlocks
        val divisor = rsDivisor(eccLen)
        var k = 0
        val blocks = List(numBlocks) { i ->
            val len = shortLen - eccLen + (if (i < numShort) 0 else 1)
            val dat = data.copyOfRange(k, k + len).also { k += len }
            val ecc = rsRemainder(dat, divisor)
            (if (i < numShort) dat + 0 else dat) + ecc
        }
        val out = ArrayList<Int>(raw)
        for (i in blocks[0].indices) for ((j, b) in blocks.withIndex()) if (i != shortLen - eccLen || j >= numShort) out += b[i]
        return out.toIntArray()
    }

    private fun gfMul(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    private fun rsDivisor(degree: Int): IntArray {
        val r = IntArray(degree).also { it[degree - 1] = 1 }
        var root = 1
        repeat(degree) {
            for (j in r.indices) {
                r[j] = gfMul(r[j], root)
                if (j + 1 < r.size) r[j] = r[j] xor r[j + 1]
            }
            root = gfMul(root, 0x02)
        }
        return r
    }

    private fun rsRemainder(data: IntArray, divisor: IntArray): IntArray {
        val r = IntArray(divisor.size)
        for (b in data) {
            val factor = b xor r[0]
            for (i in 0 until r.size - 1) r[i] = r[i + 1]
            r[r.size - 1] = 0
            for (i in r.indices) r[i] = r[i] xor gfMul(divisor[i], factor)
        }
        return r
    }

    private class Bits {
        private val list = ArrayList<Boolean>()
        val size get() = list.size
        operator fun get(i: Int) = list[i]
        fun add(value: Int, count: Int) { for (i in count - 1 downTo 0) list += ((value ushr i) and 1) == 1 }
    }

    private class Matrix(val ver: Int) {
        val size = ver * 4 + 17
        val m = Array(size) { BooleanArray(size) }
        val fn = Array(size) { BooleanArray(size) }

        fun set(x: Int, y: Int, dark: Boolean) { m[y][x] = dark; fn[y][x] = true }

        fun build(codewords: IntArray): Array<BooleanArray> {
            drawFunctionPatterns()
            drawCodewords(codewords)
            var best = 0
            var bestScore = Int.MAX_VALUE
            for (mask in 0 until 8) {
                applyMask(mask); drawFormat(mask)
                val p = penalty()
                if (p < bestScore) { best = mask; bestScore = p }
                applyMask(mask)
            }
            applyMask(best); drawFormat(best)
            return m
        }

        private fun drawFunctionPatterns() {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            val pos = alignmentPositions()
            val n = pos.size
            for (i in 0 until n) for (j in 0 until n) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) set(pos[i] + dx, pos[j] + dy, maxOf(abs(dx), abs(dy)) != 1)
            }
            drawFormat(0)
            if (ver >= 7) {
                var rem = ver
                repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
                val bits = (ver shl 12) or rem
                for (i in 0 until 18) {
                    val bit = ((bits ushr i) and 1) == 1
                    val a = size - 11 + i % 3
                    val b = i / 3
                    set(a, b, bit); set(b, a, bit)
                }
            }
        }

        private fun finder(x: Int, y: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val d = maxOf(abs(dx), abs(dy))
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until size && yy in 0 until size) set(xx, yy, d != 2 && d != 4)
            }
        }

        private fun alignmentPositions(): IntArray {
            if (ver == 1) return IntArray(0)
            val n = ver / 7 + 2
            val step = (ver * 8 + n * 3 + 5) / (n * 4 - 4) * 2
            val r = IntArray(n)
            r[0] = 6
            var p = size - 7
            for (i in n - 1 downTo 1) { r[i] = p; p -= step }
            return r
        }

        /** Format bits for error correction M (`00`) and [mask]. */
        private fun drawFormat(mask: Int) {
            val data = mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = ((bits ushr i) and 1) == 1
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        private fun drawCodewords(data: IntArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!fn[y][x] && i < data.size * 8) {
                        m[y][x] = ((data[i ushr 3] ushr (7 - (i and 7))) and 1) == 1
                        i++
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val flip = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (flip && !fn[y][x]) m[y][x] = !m[y][x]
            }
        }

        private fun penalty(): Int {
            var r = 0
            for (pass in 0..1) for (a in 0 until size) {
                var color = false
                var run = 0
                val hist = IntArray(7)
                for (b in 0 until size) {
                    val c = if (pass == 0) m[a][b] else m[b][a]
                    if (c == color) {
                        run++
                        if (run == 5) r += 3 else if (run > 5) r++
                    } else {
                        addHistory(run, hist)
                        if (!color) r += countFinders(hist) * 40
                        color = c
                        run = 1
                    }
                }
                if (color) { addHistory(run, hist); run = 0 }
                addHistory(run + size, hist)
                r += countFinders(hist) * 40
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = m[y][x]
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) r += 3
            }
            val dark = m.sumOf { row -> row.count { it } }
            val total = size * size
            val k = (abs(dark * 20 - total * 10) + total - 1) / total - 1
            return r + k * 10
        }

        private fun addHistory(runLength: Int, h: IntArray) {
            val len = if (h[0] == 0) runLength + size else runLength
            for (i in h.size - 1 downTo 1) h[i] = h[i - 1]
            h[0] = len
        }

        private fun countFinders(h: IntArray): Int {
            val n = h[1]
            val core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n
            return (if (core && h[0] >= n * 4 && h[6] >= n) 1 else 0) + (if (core && h[6] >= n * 4 && h[0] >= n) 1 else 0)
        }
    }
}
