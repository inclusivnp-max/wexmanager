package com.tim.dex.model

data class CodeItem(
    val registersSize: Int,
    val insSize: Int,
    val outsSize: Int,
    val triesSize: Int,
    val debugInfoIdx: Int,                  // -1 = нет
    val insnsSize: Int,
    val insns: ByteArray,
    val tries: List<TryItem>,
    val catchHandlers: List<CatchHandler>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CodeItem) return false
        return registersSize == other.registersSize &&
                insSize == other.insSize &&
                outsSize == other.outsSize &&
                triesSize == other.triesSize &&
                debugInfoIdx == other.debugInfoIdx &&
                insnsSize == other.insnsSize &&
                insns.contentEquals(other.insns) &&
                tries == other.tries &&
                catchHandlers == other.catchHandlers
    }

    override fun hashCode(): Int {
        var result = registersSize
        result = 31 * result + insSize
        result = 31 * result + outsSize
        result = 31 * result + triesSize
        result = 31 * result + debugInfoIdx
        result = 31 * result + insnsSize
        result = 31 * result + insns.contentHashCode()
        result = 31 * result + tries.hashCode()
        result = 31 * result + catchHandlers.hashCode()
        return result
    }
}