package com.applicassion.pixelperception.core.utils

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.applicassion.pixelperception.core.model.CoreOutputGrid
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import kotlin.math.max
import androidx.core.graphics.createBitmap
import org.opencv.core.Scalar

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

fun Mat.toBitmap(): Bitmap? {

    if (this.empty() || this.cols() <= 0 || this.rows() <= 0) return null
    // Work on a local copy so we can safely transform types.
    val m = clone()

    val rgba = Mat()
    try {
        when (m.type()) {
            CvType.CV_8UC1 -> {
                // Gray -> RGBA
                Imgproc.cvtColor(m, rgba, Imgproc.COLOR_GRAY2RGBA)
            }

            CvType.CV_8UC4 -> {
                // Already RGBA-ish
                m.copyTo(rgba)
            }

            CvType.CV_8UC3 -> {
                // BGR -> RGBA (common OpenCV default)
                Imgproc.cvtColor(m, rgba, Imgproc.COLOR_BGR2RGBA)
            }

            CvType.CV_32FC1 -> {
                // Float field (depth/magnitude): normalize to 0..255 for display
                val norm = Mat()
                Core.normalize(m, norm, 0.0, 255.0, Core.NORM_MINMAX)
                norm.convertTo(norm, CvType.CV_8UC1)
                Imgproc.cvtColor(norm, rgba, Imgproc.COLOR_GRAY2RGBA)
                norm.release()
            }

            else -> {
                // Fallback: try convert to 8U then display as gray
                val tmp = Mat()
                m.convertTo(tmp, CvType.CV_8UC1)
                Imgproc.cvtColor(tmp, rgba, Imgproc.COLOR_GRAY2RGBA)
                tmp.release()
            }
        }

        val bmp = createBitmap(rgba.cols(), rgba.rows())
        Utils.matToBitmap(rgba, bmp)
        return bmp
    } finally {
        m.release()
        rgba.release()
    }
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

fun Mat.rotate90CCW(): Mat {
    val dst = Mat()
    Core.rotate(this, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
    return dst
}

fun Mat.flipHorizontal(): Mat {
    val dst = Mat()
    Core.flip(this, dst, 1) // 1 = horizontal
    return dst
}

fun Mat.rotate90CCWThenFlipHorizontal(): Mat {
    val rotated = Mat()
    Core.rotate(this, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)

    val flipped = Mat()
    Core.flip(rotated, flipped, 1)

    rotated.release()
    return flipped
}

fun Mat.applyGainClamped32F(gain: Float = 3f): Mat {
    if (this.empty()) return Mat()

    val scaled = Mat()
    Core.multiply(this, Scalar(gain.toDouble()), scaled)

    val clamped = Mat()
    Core.min(scaled, Scalar(1.0), clamped)

    scaled.release()
    return clamped
}

fun Mat.applyGainClamped8U(gain: Double = 3.0): Mat {
    val scaled = Mat()
    this.convertTo(scaled, CvType.CV_8UC1, gain)

    val clamped = Mat()
    Core.min(scaled, Scalar(255.0), clamped)

    scaled.release()
    return clamped
}