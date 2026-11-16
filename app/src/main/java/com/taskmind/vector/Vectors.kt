package com.taskmind.vector

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Vectors as they are kept: 384 signed bytes and one scale, 388 bytes each,
 * a quarter of the floats they come from. Tens of thousands fit in memory at
 * once, and a search reads them all in a few tens of milliseconds.
 *
 * Pure.
 */
object Vectors {

    /** The vector packed as its scale (4 bytes) then one byte per dimension. */
    fun pack(v: FloatArray): ByteArray {
        val max = v.maxOf { abs(it) }.coerceAtLeast(1e-9f)
        val scale = max / 127f
        val buffer = ByteBuffer.allocate(4 + v.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putFloat(scale)
        for (x in v) buffer.put((x / scale).roundToInt().coerceIn(-127, 127).toByte())
        return buffer.array()
    }

    fun unpack(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val scale = buffer.getFloat()
        return FloatArray(bytes.size - 4) { buffer.get() * scale }
    }

    /**
     * Many packed vectors laid end to end, searched as one. [ids] says which
     * stored stretch each one is.
     */
    class Index(val ids: LongArray, private val scales: FloatArray, private val values: ByteArray, val dim: Int) {
        val size: Int get() = ids.size

        /** The [k] closest to [query] (a unit float vector), best first, with their scores. */
        fun top(query: FloatArray, k: Int): List<Pair<Long, Float>> {
            if (size == 0 || k <= 0) return emptyList()
            val bestIds = LongArray(k)
            val bestScores = FloatArray(k) { Float.NEGATIVE_INFINITY }
            var held = 0
            for (i in 0 until size) {
                var sum = 0f
                val o = i * dim
                for (d in 0 until dim) sum += query[d] * values[o + d]
                val score = sum * scales[i]
                if (held < k || score > bestScores[held - 1]) {
                    var j = if (held < k) held++ else k - 1
                    while (j > 0 && bestScores[j - 1] < score) {
                        bestScores[j] = bestScores[j - 1]
                        bestIds[j] = bestIds[j - 1]
                        j--
                    }
                    bestScores[j] = score
                    bestIds[j] = ids[i]
                }
            }
            return (0 until held).map { bestIds[it] to bestScores[it] }
        }

        companion object {
            fun of(rows: List<Pair<Long, ByteArray>>, dim: Int): Index {
                val ids = LongArray(rows.size)
                val scales = FloatArray(rows.size)
                val values = ByteArray(rows.size * dim)
                rows.forEachIndexed { i, (id, packed) ->
                    ids[i] = id
                    val b = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN)
                    scales[i] = b.getFloat()
                    System.arraycopy(packed, 4, values, i * dim, dim)
                }
                return Index(ids, scales, values, dim)
            }
        }
    }

    /**
     * Which of the best matches to keep. e5's scores sit close together
     * (unrelated text still scores about 0.75), so the cut is relative: within
     * [margin] of the best, and above [floor]. Measured on test chats: the
     * right stretch scored 0.82 to 0.89 and came first for every question
     * that had one; a question with no answer in them topped out at 0.78.
     */
    fun keep(scored: List<Pair<Long, Float>>, floor: Float = FLOOR, margin: Float = MARGIN, max: Int = 3): List<Pair<Long, Float>> {
        val best = scored.firstOrNull()?.second ?: return emptyList()
        if (best < floor) return emptyList()
        return scored.filter { it.second >= floor && it.second >= best - margin }.take(max)
    }

    const val FLOOR = 0.80f
    const val MARGIN = 0.04f
}
