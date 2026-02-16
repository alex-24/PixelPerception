package com.applicassion.pixelperception.core.vision.output_combinator

import com.applicassion.pixelperception.core.model.CoreOutputGrid
import org.opencv.core.Mat

abstract class OutputAggregator<T: OutputAggregatorConfig>() {
    enum class PoolingMode { AVG, MAX }

    abstract fun aggregate(
        edge01: Mat,
        motion01: Mat,
        depth01: Mat,
        config: T
    ): Mat

    fun projectToGrid(
        aggregate01: Mat,
        gridW: Int,
        gridH: Int,
        mode: PoolingMode
    ): CoreOutputGrid {
        val W = aggregate01.cols()
        val H = aggregate01.rows()

        val cellW = W.toFloat() / gridW
        val cellH = H.toFloat() / gridH

        val out = FloatArray(gridW * gridH)
        val rowBuf = FloatArray(W)

        for (gy in 0 until gridH) {
            val y0 = (gy * cellH).toInt()
            val y1 = ((gy + 1) * cellH).toInt().coerceAtMost(H)

            for (gx in 0 until gridW) {
                val x0 = (gx * cellW).toInt()
                val x1 = ((gx + 1) * cellW).toInt().coerceAtMost(W)

                var acc = 0.0
                var count = 0
                var maxv = 0.0

                for (y in y0 until y1) {
                    aggregate01.get(y, 0, rowBuf)
                    for (x in x0 until x1) {
                        val v = rowBuf[x].toDouble()
                        if (mode == PoolingMode.AVG) {
                            acc += v
                            count++
                        } else {
                            if (v > maxv) maxv = v
                        }
                    }
                }

                val value = when (mode) {
                    PoolingMode.AVG -> if (count == 0) 0f else (acc / count).toFloat()
                    PoolingMode.MAX -> maxv.toFloat()
                }

                out[gy * gridW + gx] = value.coerceIn(0f, 1f)
            }
        }

        return CoreOutputGrid(gridW, gridH, out)
    }
}

abstract class OutputAggregatorConfig()