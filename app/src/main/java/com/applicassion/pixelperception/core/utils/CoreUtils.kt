package com.applicassion.pixelperception.core.utils

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import com.applicassion.pixelperception.core.model.CoreOutputGrid
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import kotlin.math.max
import org.opencv.core.Scalar
import androidx.core.graphics.createBitmap
import org.opencv.android.Utils

fun ByteBuffer.toByteArray(): ByteArray {
    rewind()
    val data = ByteArray(remaining())
    get(data)

    return data
}

fun ImageProxy.toMat(cvType: Int): Mat {
    return when (cvType) {
        CvType.CV_8UC1 -> {
            // Grayscale: just use Y plane
            val yPlane = planes[0]
            val yBuffer = yPlane.buffer
            yBuffer.rewind()
            
            val mat = Mat(height, width, CvType.CV_8UC1)
            mat.put(0, 0, yBuffer.toByteArray())
            mat
        }
        CvType.CV_8UC4 -> {
            // RGBA: convert from YUV_420_888
            toRgbaMat()
        }
        else -> {
            throw IllegalArgumentException("Unsupported cvType: $cvType. Use CV_8UC1 or CV_8UC4.")
        }
    }
}

/**
 * Convert YUV_420_888 ImageProxy to RGBA Mat.
 * Handles row stride and pixel stride properly.
 */
private fun ImageProxy.toRgbaMat(): Mat {
    val yPlane = planes[0]
    val uPlane = planes[1]
    val vPlane = planes[2]

    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer

    val yRowStride = yPlane.rowStride
    val uvRowStride = uPlane.rowStride
    val uvPixelStride = uPlane.pixelStride

    // Create output RGBA Mat
    val rgbaMat = Mat(height, width, CvType.CV_8UC4)
    val rgbaData = ByteArray(width * height * 4)

    var rgbaIdx = 0
    for (row in 0 until height) {
        for (col in 0 until width) {
            // Y value
            val yIdx = row * yRowStride + col
            val y = (yBuffer.get(yIdx).toInt() and 0xFF)

            // UV subsampled (2x2 block shares same UV)
            val uvRow = row / 2
            val uvCol = col / 2
            val uvIdx = uvRow * uvRowStride + uvCol * uvPixelStride

            val u = (uBuffer.get(uvIdx).toInt() and 0xFF) - 128
            val v = (vBuffer.get(uvIdx).toInt() and 0xFF) - 128

            // YUV to RGB conversion (BT.601)
            var r = y + (1.370705f * v).toInt()
            var g = y - (0.337633f * u).toInt() - (0.698001f * v).toInt()
            var b = y + (1.732446f * u).toInt()

            // Clamp to [0, 255]
            r = r.coerceIn(0, 255)
            g = g.coerceIn(0, 255)
            b = b.coerceIn(0, 255)

            // Write RGBA
            rgbaData[rgbaIdx++] = r.toByte()
            rgbaData[rgbaIdx++] = g.toByte()
            rgbaData[rgbaIdx++] = b.toByte()
            rgbaData[rgbaIdx++] = 255.toByte() // Alpha
        }
    }

    rgbaMat.put(0, 0, rgbaData)
    return rgbaMat
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

fun Mat.toBitmap(floatMapping: FloatMapping): Bitmap? {
    if (empty() || cols() <= 0 || rows() <= 0) return null

    val m = if (isContinuous) this else this.clone()

    val rgba = Mat()
    try {
        when (m.type()) {
            CvType.CV_8UC1 -> {
                Imgproc.cvtColor(m, rgba, Imgproc.COLOR_GRAY2RGBA)
            }

            CvType.CV_8UC4 -> {
                m.copyTo(rgba)
            }

            CvType.CV_8UC3 -> {
                Imgproc.cvtColor(m, rgba, Imgproc.COLOR_BGR2RGBA)
            }

            CvType.CV_32FC1 -> {
                when (floatMapping) {
                    FloatMapping.NormalizePerFrame -> {
                        val gray8 = Mat()
                        Core.normalize(m, gray8, 0.0, 255.0, Core.NORM_MINMAX)
                        gray8.convertTo(gray8, CvType.CV_8UC1)
                        Imgproc.cvtColor(gray8, rgba, Imgproc.COLOR_GRAY2RGBA)
                        gray8.release()
                    }

                    FloatMapping.Assume0to1 -> {
                        val gray8 = Mat()
                        m.convertTo(gray8, CvType.CV_8UC1, 255.0)
                        Imgproc.cvtColor(gray8, rgba, Imgproc.COLOR_GRAY2RGBA)
                        gray8.release()
                    }

                    FloatMapping.DepthColor -> {
                        val depth8 = Mat()
                        m.convertTo(depth8, CvType.CV_8UC1, 255.0)

                        val colored = Mat()
                        Imgproc.applyColorMap(depth8, colored, Imgproc.COLORMAP_WINTER)

                        Imgproc.cvtColor(colored, rgba, Imgproc.COLOR_BGR2RGBA)

                        depth8.release()
                        colored.release()
                    }
                }
            }

            else -> {
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
        rgba.release()
        if (m !== this) m.release()
    }
}

sealed class FloatMapping {
    data object NormalizePerFrame : FloatMapping()
    data object Assume0to1 : FloatMapping()
    data object DepthColor : FloatMapping()
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

fun Mat.adaptOrientationForDisplay(cameraSelector: CameraSelector): Mat {

    return when(cameraSelector) {
        CameraSelector.DEFAULT_BACK_CAMERA -> {
            val rotated = Mat()
            Core.rotate(this, rotated, Core.ROTATE_90_CLOCKWISE)

            rotated
        }
        CameraSelector.DEFAULT_FRONT_CAMERA -> {
            val rotated = Mat()
            Core.rotate(this, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            val flipped = Mat()
            Core.flip(rotated, flipped, 1)
            rotated.release()

            flipped
        }
        else -> throw UnsupportedOperationException("No transform implemented for this camera selector: $cameraSelector")
    }
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