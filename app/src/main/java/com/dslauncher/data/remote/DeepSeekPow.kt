package com.dslauncher.data.remote

/**
 * DeepSeekHashV1 PoW 求解器。
 * DeepSeekHashV1 = SHA3-256 但跳过 Keccak-f[1600] round 0（只做 rounds 1..23）。
 * 移植自 github.com/CJackHwang/ds2api (pow 包)。
 */
object DeepSeekPow {

    private val RC = longArrayOf(
        0x0000000000000001uL.toLong(), 0x0000000000008082uL.toLong(), 0x800000000000808AuL.toLong(), 0x8000000080008000uL.toLong(),
        0x000000000000808BuL.toLong(), 0x0000000080000001uL.toLong(), 0x8000000080008081uL.toLong(), 0x8000000000008009uL.toLong(),
        0x000000000000008AuL.toLong(), 0x0000000000000088uL.toLong(), 0x0000000080008009uL.toLong(), 0x000000008000000AuL.toLong(),
        0x000000008000808BuL.toLong(), 0x800000000000008BuL.toLong(), 0x8000000000008089uL.toLong(), 0x8000000000008003uL.toLong(),
        0x8000000000008002uL.toLong(), 0x8000000000000080uL.toLong(), 0x000000000000800AuL.toLong(), 0x800000008000000AuL.toLong(),
        0x8000000080008081uL.toLong(), 0x8000000000008080uL.toLong(), 0x0000000080000001uL.toLong(), 0x8000000080008008uL.toLong()
    )

    private fun rotl64(v: Long, k: Int): Long = (v shl k) or (v ushr (64 - k))

    private fun keccakF23(s: LongArray) {
        var a0 = s[0]; var a1 = s[1]; var a2 = s[2]; var a3 = s[3]; var a4 = s[4]
        var a5 = s[5]; var a6 = s[6]; var a7 = s[7]; var a8 = s[8]; var a9 = s[9]
        var a10 = s[10]; var a11 = s[11]; var a12 = s[12]; var a13 = s[13]; var a14 = s[14]
        var a15 = s[15]; var a16 = s[16]; var a17 = s[17]; var a18 = s[18]; var a19 = s[19]
        var a20 = s[20]; var a21 = s[21]; var a22 = s[22]; var a23 = s[23]; var a24 = s[24]

        for (r in 1 until 24) {
            val c0 = a0 xor a5 xor a10 xor a15 xor a20
            val c1 = a1 xor a6 xor a11 xor a16 xor a21
            val c2 = a2 xor a7 xor a12 xor a17 xor a22
            val c3 = a3 xor a8 xor a13 xor a18 xor a23
            val c4 = a4 xor a9 xor a14 xor a19 xor a24
            val d0 = c4 xor rotl64(c1, 1)
            val d1 = c0 xor rotl64(c2, 1)
            val d2 = c1 xor rotl64(c3, 1)
            val d3 = c2 xor rotl64(c4, 1)
            val d4 = c3 xor rotl64(c0, 1)

            a0 = a0 xor d0; a5 = a5 xor d0; a10 = a10 xor d0; a15 = a15 xor d0; a20 = a20 xor d0
            a1 = a1 xor d1; a6 = a6 xor d1; a11 = a11 xor d1; a16 = a16 xor d1; a21 = a21 xor d1
            a2 = a2 xor d2; a7 = a7 xor d2; a12 = a12 xor d2; a17 = a17 xor d2; a22 = a22 xor d2
            a3 = a3 xor d3; a8 = a8 xor d3; a13 = a13 xor d3; a18 = a18 xor d3; a23 = a23 xor d3
            a4 = a4 xor d4; a9 = a9 xor d4; a14 = a14 xor d4; a19 = a19 xor d4; a24 = a24 xor d4

            val b0 = a0
            val b10 = rotl64(a1, 1)
            val b20 = rotl64(a2, 62)
            val b5 = rotl64(a3, 28)
            val b15 = rotl64(a4, 27)
            val b16 = rotl64(a5, 36)
            val b1 = rotl64(a6, 44)
            val b11 = rotl64(a7, 6)
            val b21 = rotl64(a8, 55)
            val b6 = rotl64(a9, 20)
            val b7 = rotl64(a10, 3)
            val b17 = rotl64(a11, 10)
            val b2 = rotl64(a12, 43)
            val b12 = rotl64(a13, 25)
            val b22 = rotl64(a14, 39)
            val b23 = rotl64(a15, 41)
            val b8 = rotl64(a16, 45)
            val b18 = rotl64(a17, 15)
            val b3 = rotl64(a18, 21)
            val b13 = rotl64(a19, 8)
            val b14 = rotl64(a20, 18)
            val b24 = rotl64(a21, 2)
            val b9 = rotl64(a22, 61)
            val b19 = rotl64(a23, 56)
            val b4 = rotl64(a24, 14)

            a0 = b0 xor (b1.inv() and b2)
            a1 = b1 xor (b2.inv() and b3)
            a2 = b2 xor (b3.inv() and b4)
            a3 = b3 xor (b4.inv() and b0)
            a4 = b4 xor (b0.inv() and b1)
            a5 = b5 xor (b6.inv() and b7)
            a6 = b6 xor (b7.inv() and b8)
            a7 = b7 xor (b8.inv() and b9)
            a8 = b8 xor (b9.inv() and b5)
            a9 = b9 xor (b5.inv() and b6)
            a10 = b10 xor (b11.inv() and b12)
            a11 = b11 xor (b12.inv() and b13)
            a12 = b12 xor (b13.inv() and b14)
            a13 = b13 xor (b14.inv() and b10)
            a14 = b14 xor (b10.inv() and b11)
            a15 = b15 xor (b16.inv() and b17)
            a16 = b16 xor (b17.inv() and b18)
            a17 = b17 xor (b18.inv() and b19)
            a18 = b18 xor (b19.inv() and b15)
            a19 = b19 xor (b15.inv() and b16)
            a20 = b20 xor (b21.inv() and b22)
            a21 = b21 xor (b22.inv() and b23)
            a22 = b22 xor (b23.inv() and b24)
            a23 = b23 xor (b24.inv() and b20)
            a24 = b24 xor (b20.inv() and b21)

            a0 = a0 xor RC[r]
        }

        s[0] = a0; s[1] = a1; s[2] = a2; s[3] = a3; s[4] = a4
        s[5] = a5; s[6] = a6; s[7] = a7; s[8] = a8; s[9] = a9
        s[10] = a10; s[11] = a11; s[12] = a12; s[13] = a13; s[14] = a14
        s[15] = a15; s[16] = a16; s[17] = a17; s[18] = a18; s[19] = a19
        s[20] = a20; s[21] = a21; s[22] = a22; s[23] = a23; s[24] = a24
    }

    private fun littleEndianUint64(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) {
            v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return v
    }

    private fun putLittleEndianUint64(bytes: ByteArray, offset: Int, v: Long) {
        var x = v
        for (i in 0 until 8) {
            bytes[offset + i] = (x and 0xFF).toByte()
            x = x ushr 8
        }
    }

    /** DeepSeekHashV1 摘要（32 字节） */
    private fun hashV1(data: ByteArray): LongArray {
        val RATE = 136
        val s = LongArray(25)

        var off = 0
        while (off + RATE <= data.size) {
            for (i in 0 until RATE / 8) {
                s[i] = s[i] xor littleEndianUint64(data, off + i * 8)
            }
            keccakF23(s)
            off += RATE
        }

        val final = ByteArray(RATE)
        System.arraycopy(data, off, final, 0, data.size - off)
        final[data.size - off] = 0x06
        final[RATE - 1] = (final[RATE - 1].toInt() or 0x80).toByte()
        for (i in 0 until RATE / 8) {
            s[i] = s[i] xor littleEndianUint64(final, i * 8)
        }
        keccakF23(s)
        return s
    }

    /**
     * 求解 PoW：搜索 nonce ∈ [0, difficulty) 使得
     * DeepSeekHashV1(salt_expireAt_nonce) 的前 4 个 uint64 == target 前 4 个 uint64。
     */
    fun solve(challengeHex: String, salt: String, expireAt: Long, difficulty: Long): Long {
        if (challengeHex.length != 64) throw IllegalArgumentException("challenge must be 64 hex chars")
        val target = hexToBytes(challengeHex)
        val t0 = littleEndianUint64(target, 0)
        val t1 = littleEndianUint64(target, 8)
        val t2 = littleEndianUint64(target, 16)
        val t3 = littleEndianUint64(target, 24)

        val prefix = (salt + "_" + expireAt.toString() + "_").toByteArray(Charsets.US_ASCII)
        val RATE = 136
        val baseState = LongArray(25)
        var off = 0
        while (off + RATE <= prefix.size) {
            for (i in 0 until RATE / 8) {
                baseState[i] = baseState[i] xor littleEndianUint64(prefix, off + i * 8)
            }
            keccakF23(baseState)
            off += RATE
        }
        val tailLen = prefix.size - off
        val tail = ByteArray(RATE)
        System.arraycopy(prefix, off, tail, 0, tailLen)

        val numBuf = ByteArray(20)
        var n = 0L
        while (n < difficulty) {
            val v = n
            var pos = 20
            if (v == 0L) {
                pos--
                numBuf[pos] = '0'.code.toByte()
            } else {
                var x = v
                while (x > 0) {
                    pos--
                    numBuf[pos] = ('0'.code + (x % 10)).toInt().toByte()
                    x /= 10
                }
            }
            val numLen = 20 - pos
            val s = baseState.copyOf()
            val totalTail = tailLen + numLen
            if (totalTail < RATE) {
                val buf = ByteArray(RATE)
                System.arraycopy(tail, 0, buf, 0, tailLen)
                System.arraycopy(numBuf, pos, buf, tailLen, numLen)
                buf[totalTail] = 0x06
                buf[RATE - 1] = (buf[RATE - 1].toInt() or 0x80).toByte()
                for (i in 0 until RATE / 8) {
                    s[i] = s[i] xor littleEndianUint64(buf, i * 8)
                }
                keccakF23(s)
            } else {
                val buf = ByteArray(RATE)
                System.arraycopy(tail, 0, buf, 0, tailLen)
                System.arraycopy(numBuf, pos, buf, tailLen, RATE - tailLen)
                for (i in 0 until RATE / 8) {
                    s[i] = s[i] xor littleEndianUint64(buf, i * 8)
                }
                keccakF23(s)
                val rem = totalTail - RATE
                val buf2 = ByteArray(RATE)
                System.arraycopy(numBuf, pos + (RATE - tailLen), buf2, 0, rem)
                buf2[rem] = 0x06
                buf2[RATE - 1] = (buf2[RATE - 1].toInt() or 0x80).toByte()
                for (i in 0 until RATE / 8) {
                    s[i] = s[i] xor littleEndianUint64(buf2, i * 8)
                }
                keccakF23(s)
            }
            if (s[0] == t0 && s[1] == t1 && s[2] == t2 && s[3] == t3) {
                return n
            }
            n++
        }
        throw IllegalArgumentException("pow: no solution within difficulty")
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
