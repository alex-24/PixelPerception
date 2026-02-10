package com.applicassion.pixelperception.core.model

data class CoreOutputGrid(
    val width: Int,
    val height: Int,
    val values: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CoreOutputGrid

        if (width != other.width) return false
        if (height != other.height) return false
        if (!values.contentEquals(other.values)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + values.contentHashCode()
        return result
    }
}
