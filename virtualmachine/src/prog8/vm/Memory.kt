package prog8.vm

import prog8.code.target.VMTarget
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MemoryAccessException(val address: UInt, val size: Int): Exception($$"access of $$size byte(s) at $$${address.toString(16)} is outside the VM's memory")

/**
 * 16 MB of random access memory. Initialized to a repeating nonzero debug pattern.
 */
class Memory {
    private val mem = ByteArray(VMTarget.RAM_SIZE).also { fillPattern(it) }

    // bulk-copy a repeating pattern of recognizable debug marker words; a large repeated
    // block keeps the number of IntBuffer.put calls low, and the marker words make reads
    // of uninitialized memory stand out.
    // The words are written in BIG_ENDIAN order (the VM itself is little-endian) so that a
    // linear memory dump shows the marker literally, e.g. 0xC0DEDBAD as C0 DE DB AD.
    private fun fillPattern(bytes: ByteArray) {
        val words = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asIntBuffer()
        val block = IntArray(BLOCK_WORDS) { DEBUG_PATTERN[it % DEBUG_PATTERN.size] }
        while(words.hasRemaining()) {
            val chunk = if(words.remaining() >= block.size) block else block.copyOf(words.remaining())
            words.put(chunk)
        }
    }

    private companion object {
        // must be a multiple of DEBUG_PATTERN.size to keep the pattern continuous
        const val BLOCK_WORDS = 256
        val DEBUG_PATTERN = intArrayOf(
            0xC0DEDBAD.toInt(),
            0xDEADBEEF.toInt(),
            0xBAADF00D.toInt(),
            0xFEEDFACE.toInt()
        )
    }

    fun reset() {
        mem.fill(0)
    }

    private fun index(address: UInt, size: Int): Int {
        if(address > (mem.size - size).toUInt())
            throw MemoryAccessException(address, size)
        return address.toInt()
    }

    fun getUB(address: UInt): UByte = mem[index(address, 1)].toUByte()
    fun getSB(address: UInt): Byte = mem[index(address, 1)]
    fun setUB(address: UInt, value: UByte) { mem[index(address, 1)] = value.toByte() }
    fun setSB(address: UInt, value: Byte) { mem[index(address, 1)] = value }

    fun getUW(address: UInt): UShort {
        val a = index(address, 2)
        return ((mem[a].toInt() and 0xFF) or ((mem[a+1].toInt() and 0xFF) shl 8)).toUShort()
    }

    fun getSW(address: UInt): Short {
        val a = index(address, 2)
        return ((mem[a].toInt() and 0xFF) or ((mem[a+1].toInt() and 0xFF) shl 8)).toShort()
    }

    fun getSL(address: UInt): Int {
        val a = index(address, 4)
        return (mem[a].toInt() and 0xFF) or
               ((mem[a+1].toInt() and 0xFF) shl 8) or
               ((mem[a+2].toInt() and 0xFF) shl 16) or
               ((mem[a+3].toInt() and 0xFF) shl 24)
    }

    fun getUL(address: UInt): UInt {
        val a = index(address, 4)
        return ((mem[a].toInt() and 0xFF) or
               ((mem[a+1].toInt() and 0xFF) shl 8) or
               ((mem[a+2].toInt() and 0xFF) shl 16) or
               ((mem[a+3].toInt() and 0xFF) shl 24)).toUInt()
    }

    fun setUW(address: UInt, value: UShort) {
        val a = index(address, 2)
        mem[a] = (value.toInt() and 0xFF).toByte()
        mem[a+1] = ((value.toInt() shr 8) and 0xFF).toByte()
    }

    fun setSW(address: UInt, value: Short) {
        val a = index(address, 2)
        mem[a] = (value.toInt() and 0xFF).toByte()
        mem[a+1] = ((value.toInt() shr 8) and 0xFF).toByte()
    }

    fun setSL(address: UInt, value: Int) {
        val a = index(address, 4)
        mem[a] = (value and 0xFF).toByte()
        mem[a+1] = ((value shr 8) and 0xFF).toByte()
        mem[a+2] = ((value shr 16) and 0xFF).toByte()
        mem[a+3] = ((value shr 24) and 0xFF).toByte()
    }

    fun setUL(address: UInt, value: UInt) {
        val v = value.toInt()
        val a = index(address, 4)
        mem[a] = (v and 0xFF).toByte()
        mem[a+1] = ((v shr 8) and 0xFF).toByte()
        mem[a+2] = ((v shr 16) and 0xFF).toByte()
        mem[a+3] = ((v shr 24) and 0xFF).toByte()
    }

    fun getSQ(address: UInt): Long {
        val a = index(address, 8)
        return (mem[a].toLong() and 0xFF) or
            ((mem[a+1].toLong() and 0xFF) shl 8) or
            ((mem[a+2].toLong() and 0xFF) shl 16) or
            ((mem[a+3].toLong() and 0xFF) shl 24) or
            ((mem[a+4].toLong() and 0xFF) shl 32) or
            ((mem[a+5].toLong() and 0xFF) shl 40) or
            ((mem[a+6].toLong() and 0xFF) shl 48) or
            ((mem[a+7].toLong() and 0xFF) shl 56)
    }

    fun getUQ(address: UInt): ULong = getSQ(address).toULong()

    fun setSQ(address: UInt, value: Long) {
        val a = index(address, 8)
        mem[a] = (value and 0xFF).toByte()
        mem[a+1] = ((value shr 8) and 0xFF).toByte()
        mem[a+2] = ((value shr 16) and 0xFF).toByte()
        mem[a+3] = ((value shr 24) and 0xFF).toByte()
        mem[a+4] = ((value shr 32) and 0xFF).toByte()
        mem[a+5] = ((value shr 40) and 0xFF).toByte()
        mem[a+6] = ((value shr 48) and 0xFF).toByte()
        mem[a+7] = ((value shr 56) and 0xFF).toByte()
    }

    fun setUQ(address: UInt, value: ULong) = setSQ(address, value.toLong())

    fun setFloat(address: UInt, value: Double) {
        var bits = value.toBits()
        val a = index(address, 8)
        mem[a] = (bits and 0xFF).toByte()
        bits = bits ushr 8
        mem[a+1] = (bits and 0xFF).toByte()
        bits = bits ushr 8
        mem[a+2] = (bits and 0xFF).toByte()
        bits = bits ushr 8
        mem[a+3] = (bits and 0xFF).toByte()
        bits = bits ushr 8
        mem[a+4] = (bits and 0xFF).toByte()
        bits = bits ushr 8
        mem[a+5] = (bits and 0xFF).toByte()
        bits = bits ushr 8
        mem[a+6] = (bits and 0xFF).toByte()
        bits = bits ushr 8
        mem[a+7] = (bits and 0xFF).toByte()
    }

    fun getFloat(address: UInt): Double {
        val a = index(address, 8)
        val bits = (mem[a].toLong() and 0xFF) or
            ((mem[a+1].toLong() and 0xFF) shl 8) or
            ((mem[a+2].toLong() and 0xFF) shl 16) or
            ((mem[a+3].toLong() and 0xFF) shl 24) or
            ((mem[a+4].toLong() and 0xFF) shl 32) or
            ((mem[a+5].toLong() and 0xFF) shl 40) or
            ((mem[a+6].toLong() and 0xFF) shl 48) or
            ((mem[a+7].toLong() and 0xFF) shl 56)
        return Double.fromBits(bits)
    }

    fun setString(address: UInt, string: String, zeroTerminated: Boolean) {
        val length = string.length
        val a = index(address, if(zeroTerminated) length + 1 else length)
        if (length > 0)
            System.arraycopy(string.toByteArray(), 0, mem, a, length)
        if(zeroTerminated)
            mem[a + length] = 0
    }

    fun getString(address: UInt): String {
        val a = index(address, 1)
        var length = 0
        while (mem[index(address + length.toUInt(), 1)] != 0.toByte()) length++
        return String(mem, a, length)
    }
}
