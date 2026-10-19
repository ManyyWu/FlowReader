// SPDX-License-Identifier: LGPL-3.0-only
// Kotlin compatibility port of rany2/edge-tts src/edge_tts/drm.py (v2 token algorithm).
// Upstream: https://github.com/rany2/edge-tts ; license bundled in assets/licenses/edge-tts.txt.
package com.flowreader.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object EdgeGecV2 {
    private val initial = longArrayOf(0x6A09E667L,0xBB67AE85L,0x3C6EF372L,0xA54FF53AL,0x510E527FL,0x9B05688CL,0x1F83D9ABL,0x5BE0CD19L).map { it.toInt() }.toIntArray()
    private val prefixState = longArrayOf(0xFD9FBEA2L,0x91243CE7L,0xBE499094L,0xCD2E1E0AL,0x6C006F49L,0x04BD35ABL,0xFEB0A7E4L,0x03EFFBD2L).map { it.toInt() }.toIntArray()
    private val schedule = longArrayOf(0xEB24BF9EL,0x1558691FL,0xA0C09EE2L,0x0CFA32BAL,0xA622EE8CL,0xD1DD9491L,0xC0DD25A0L,0xF9E70AB6L,0xF0C31573L,0x7058CE4EL,0x7188E606L,0x00E5AB33L,0xD258B060L,0x72766564L).map { it.toInt() }.toIntArray()
    private val constants = longArrayOf(0x428A2F98L,0x71374491L,0xB5C0FBCFL,0xE9B5DBA5L,0x3956C25BL,0x59F111F1L,0x923F82A4L,0xAB1C5ED5L,0xD807AA98L,0x12835B01L,0x243185BEL,0x550C7DC3L,0x72BE5D74L,0x80DEB1FEL,0x9BDC06A7L,0xC19BF174L,0xE49B69C1L,0xEFBE4786L,0x0FC19DC6L,0x240CA1CCL,0x2DE92C6FL,0x4A7484AAL,0x5CB0A9DCL,0x76F988DAL,0x983E5152L,0xA831C66DL,0xB00327C8L,0xBF597FC7L,0xC6E00BF3L,0xD5A79147L,0x06CA6351L,0x14292967L,0x27B70A85L,0x2E1B2138L,0x4D2C6DFCL,0x53380D13L,0x650A7354L,0x766A0ABBL,0x81C2C92EL,0x92722C85L,0xA2BFE8A1L,0xA81A664BL,0xC24B8B70L,0xC76C51A3L,0xD192E819L,0xD6990624L,0xF40E3585L,0x106AA070L,0x19A4C116L,0x1E376C08L,0x2748774CL,0x34B0BCB5L,0x391C0CB3L,0x4ED8AA4AL,0x5B9CCA4FL,0x682E6FF3L,0x748F82EEL,0x78A5636FL,0x84C87814L,0x8CC70208L,0x90BEFFFAL,0xA4506CEBL,0xBEF9A3F7L,0xC67178F2L).map { it.toInt() }.toIntArray()
    private val coefficients = longArrayOf(0xE82E91B1L,0x44DE24C6L,0x3BEB65BCL,0xC89C5933L,0x8D1EC211L,0x314EF5D5L,0xE92491B1L,0xF0A5B9D5L,0x06064CF3L,0xDA79FA60L,0xBED20560L,0x89F9C9F8L,0x35FA38C6L,0xCB49E3C6L).map { it.toInt() }
    private val offsets = longArrayOf(0xB32700D0L,0x88DFF51DL,0x29514038L,0xB328CBDFL,0xD405B4D5L,0x26E55F3CL,0x607801C6L,0x82B2C1FBL,0xFA51A3DAL,0x207CC464L,0x3C3C74F3L,0xD2D14B61L,0xBB04A2C1L,0xC5626167L).map { it.toInt() }

    private fun s0(x: Int) = Integer.rotateRight(x, 7) xor Integer.rotateRight(x, 18) xor (x ushr 3)
    private fun s1(x: Int) = Integer.rotateRight(x, 17) xor Integer.rotateRight(x, 19) xor (x ushr 10)
    private fun expand(w: IntArray, start: Int) {
        for (i in start..63) w[i] = s1(w[i - 2]) + w[i - 7] + s0(w[i - 15]) + w[i - 16]
    }
    private fun rounds(state: IntArray, w: IntArray, start: Int = 0): IntArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (i in start..63) {
            val k = if (start == 14 && i == 14) 0xC9DC06A7L.toInt() else constants[i]
            val t1 = h + (Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor Integer.rotateRight(e, 25)) +
                (g xor (e and (f xor g))) + k + w[i]
            val t2 = (Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor Integer.rotateRight(a, 22)) +
                ((a and b) or (c and (a or b)))
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }
    private fun digest(data: ByteArray): ByteArray {
        require(data.size >= 7)
        val w = IntArray(64)
        w[14] = ((data[0].toInt() and 255) shl 16) or ((data[1].toInt() and 255) shl 8) or (data[2].toInt() and 255)
        w[15] = ByteBuffer.wrap(data, 3, 4).int
        for (i in 16..29) {
            var x = schedule[i - 16]
            if (i - 2 >= 14) x += if (i == 16) s1(w[i - 2]) xor 0x000B92C0 else s1(w[i - 2])
            if (i - 7 >= 14) x += w[i - 7]
            if (i - 15 >= 14) x += if (i == 29) s0(w[i - 15]) xor 0x059C0B80 else s0(w[i - 15])
            w[i] = x
        }
        w[30] = s1(w[28]) + w[23] + s0(w[15]) + w[14] + 0xC9DC06A7L.toInt() - constants[14]
        expand(w, 31)
        var state = rounds(prefixState, w, 14).mapIndexed { i, x -> x + initial[i] }.toIntArray()
        val length = 57 + data.size
        val zeros = ((55 - length) % 64 + 64) % 64
        val padded = data.copyOfRange(7, data.size) + byteArrayOf(0x80.toByte()) + ByteArray(zeros) +
            ByteBuffer.allocate(8).putLong(length.toLong() * 8).array()
        for (offset in padded.indices step 64) {
            val input = ByteBuffer.wrap(padded, offset, 64)
            for (i in 0..15) w[i] = input.int
            expand(w, 16)
            val work = rounds(state, w)
            state = state.mapIndexed { i, x -> x + work[i] }.toIntArray()
        }
        return ByteBuffer.allocate(32).apply { state.forEach { putInt(it) } }.array()
    }
    private fun rotate(x: Int) = Integer.rotateLeft(x, 16)
    private fun cs64(data: ByteArray, digest: ByteArray): ByteArray {
        val input = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
        val a = input.int or 1; val b = input.int or 1
        val factorA = a * 0x82893933L.toInt(); val factorB = b * 0x82893933L.toInt()
        val terms = coefficients.mapIndexed { i, x -> x * if (i % 2 == 0) factorA else factorB }.toMutableList()
        val values = offsets.toMutableList()
        val dataBytes = byteArrayOf(0) + data
        val padded = dataBytes + ByteArray((8 - dataBytes.size % 8) % 8)
        val words = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (words.hasRemaining()) {
            val word = words.int
            var term = word * if (i % 2 == 0) a else b
            var offset = rotate(word) * if (i % 2 == 0) 0xEF0569FBL.toInt() else 0xC31713DBL.toInt()
            if (i == 0) { term += 0xF2F90B1AL.toInt() * factorA; offset += 0x0B1A0000 }
            terms += term; values += offset; i++
        }
        var swapped = 0; var last = 0; var state = 0; var acc = 0
        for (j in terms.indices step 2) {
            val x = terms[j]; val y = terms[j + 1]
            var q = rotate(values[j] + x) * 0x76F50F34
            swapped += rotate(q) * 0xC3EFEA97L.toInt() + q
            q = rotate(values[j + 1] + y) * 0x1881CE3C
            last = rotate(q) * 0x35BD1EC9 + q
            swapped += last
            state = state * a + x
            for (k in longArrayOf(0xCF98B111L, 0x87085B9FL, 0x12CEB96DL, 0x257E1D83L)) state = rotate(state) * k.toInt()
            acc += state
            var t = state * b + y
            t = rotate(t) * 0xA27416F5L.toInt(); t = rotate(t) * 0xD38396FFL.toInt()
            state = rotate(rotate(t) * 0x7C932B89) * 0xBFA49F69L.toInt()
            acc += state
        }
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(last xor state).putInt(swapped xor acc).array()
    }
    private fun hex(data: ByteArray) = data.joinToString("") { "%02X".format(it.toInt() and 255) }
    fun generate(token: String, unixSeconds: Long): String {
        val seconds = unixSeconds + 11644473600L
        val data = "${(seconds - seconds % 300) * 10_000_000L}$token".toByteArray(Charsets.US_ASCII)
        val digest = digest(data)
        return "${hex(cs64(data, digest))}-${hex(digest)}"
    }
}
