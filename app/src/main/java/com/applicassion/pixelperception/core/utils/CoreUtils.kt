package com.applicassion.pixelperception.core.utils

import androidx.camera.core.ImageProxy
import com.applicassion.pixelperception.core.model.CoreOutputGrid
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import java.nio.ByteBuffer
import kotlin.math.max

fun ByteBuffer.toByteArray(): ByteArray {
    rewind()
    val data = ByteArray(remaining())
    get(data)

    return data
}

fun ImageProxy.toMat(cvType: Int): Mat {
    val plane = planes[0]
    val buffer: ByteBuffer = plane.buffer
    val width = width
    val height = height

    val mat = Mat(height, width, cvType)
    buffer.rewind()
    mat.put(0, 0, buffer.toByteArray())

    return mat
}

fun ImageProxy.toGrayscaleMat(): Mat {
    return toMat(CvType.CV_8UC1)
}

fun Mat.toCoreOutputGrid(
    targetWidth: Int,
    targetHeight: Int
): CoreOutputGrid {
    require(targetWidth > 0 && targetHeight > 0)
    val w = cols()
    val h = rows()

    // ensure cell sizes at least 1px
    val cellW = max(1, w / targetWidth)
    val cellH = max(1, h / targetHeight)

    val out = FloatArray(targetWidth * targetHeight)

    var idx = 0
    for (r in 0 until targetHeight) {
        val y = r * cellH
        val hh = if (r == targetHeight - 1) (h - y) else cellH

        for (c in 0 until targetWidth) {
            val x = c * cellW
            val ww = if (c == targetWidth - 1) (w - x) else cellW

            val roi = submat(Rect(x, y, ww, hh))
            // edges is 0 or 255. mean/255 => density in [0..1]
            val mean = Core.mean(roi).`val`[0] / 255.0
            roi.release()

            out[idx++] = mean.toFloat()
        }
    }

    return CoreOutputGrid(
        width = targetWidth,
        height = targetHeight,
        values = out
    )
        .rotate90CCW()
        .flipHorizontal()
}

fun CoreOutputGrid.applyGain(gain: Float = 3f): CoreOutputGrid {
    val out = values.copyOf()
    for (i in out.indices) {
        val v = out[i] * gain
        out[i] = if (v > 1f) 1f else v
    }
    return copy(values = out)
}

private fun CoreOutputGrid.rotate90CCW(): CoreOutputGrid {
    val inCols = width
    val inRows = height
    val outCols = inRows
    val outRows = inCols

    val out = FloatArray(outCols * outRows)

    for (y in 0 until inRows) {
        for (x in 0 until inCols) {
            val v = values[y * inCols + x]

            val x2 = y
            val y2 = inCols - 1 - x
            out[y2 * outCols + x2] = v
        }
    }

    return CoreOutputGrid(outCols, outRows, out)
}

private fun CoreOutputGrid.flipHorizontal(): CoreOutputGrid {
    val cols = width
    val rows = height
    val out = FloatArray(cols * rows)

    for (y in 0 until rows) {
        for (x in 0 until cols) {
            val v = values[y * cols + x]
            val x2 = cols - 1 - x
            out[y * cols + x2] = v
        }
    }

    return CoreOutputGrid(cols, rows, out)
}