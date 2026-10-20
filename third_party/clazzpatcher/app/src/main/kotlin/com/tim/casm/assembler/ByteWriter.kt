package com.tim.casm.assembler

internal class ByteWriter(capacity: Int = 256) {
    var buf = ByteArray(maxOf(capacity, 64))
    private set
    var pos = 0
    private set

    fun ensure(extra: Int) {
        if (pos + extra > buf.size) buf = buf.copyOf(maxOf(pos + extra, buf.size * 2))
    }

    fun u1(v: Int) { ensure(1); buf[pos++] = v.toByte() }
    fun u2(v: Int) { u1(v ushr 8); u1(v) }
    fun s2(v: Int) { u2(v and 0xFFFF) }
    fun s4(v: Int) { u1(v ushr 24); u1(v ushr 16); u1(v ushr 8); u1(v) }
    fun bytes(b: ByteArray) { ensure(b.size); System.arraycopy(b, 0, buf, pos, b.size); pos += b.size }

    fun align4() { while (pos % 4 != 0) u1(0) }

    fun patchS2(at: Int, v: Int) { buf[at] = (v ushr 8).toByte(); buf[at + 1] = v.toByte() }
    fun patchS4(at: Int, v: Int) {
        buf[at] = (v ushr 24).toByte(); buf[at + 1] = (v ushr 16).toByte()
        buf[at + 2] = (v ushr 8).toByte(); buf[at + 3] = v.toByte()
    }

    fun toByteArray(): ByteArray = buf.copyOf(pos)
}
