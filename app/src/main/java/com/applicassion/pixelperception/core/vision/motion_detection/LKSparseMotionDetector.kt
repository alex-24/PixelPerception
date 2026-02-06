package com.applicassion.pixelperception.core.vision.motion_detection

import com.applicassion.pixelperception.core.vision.IFrameProcessor
import com.applicassion.pixelperception.core.vision.IFrameProcessorConfig
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

/**
 * Sparse Lucas–Kanade optical flow:
 * - Detect corners (goodFeaturesToTrack) every N frames (or when too few points)
 * - Track points with calcOpticalFlowPyrLK
 * - Convert sparse vectors -> dense-ish motion-energy map (CV_32FC1 in [0..1])
 */
object LKSparseMotionDetector : IFrameProcessor<LKSparseMotionDetectorConfig> {

    private var previousImage: Mat? = null
    private var prevPts: MatOfPoint2f? = null
    private var frameIdx: Int = 0

    override fun processFrame(image: Mat, config: LKSparseMotionDetectorConfig): Mat {
        require(image.type() == CvType.CV_8UC1) { "LK expects CV_8UC1 grayscale input" }
        if (image.empty()) return Mat()

        val prev = previousImage
        if (prev == null || prev.empty() || prev.size() != image.size()) {
            reset()
            previousImage = image.clone()
            // Return zeros motion map
            return Mat.zeros(image.size(), CvType.CV_32FC1)
        }

        frameIdx++

        // (Re)detect features if needed
        if (prevPts == null
            || prevPts!!.empty()
            || (frameIdx % config.refreshEveryNFrames == 0)
        ) {
            prevPts = detectFeatures(prev, config)
        }

        // If still no points, just update prev and return zeros
        if (prevPts == null || prevPts!!.empty()) {
            previousImage?.release()
            previousImage = image.clone()
            return Mat.zeros(image.size(), CvType.CV_32FC1)
        }

        val nextPts = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()

        // LK tracking
        Video.calcOpticalFlowPyrLK(
            prev, image,
            prevPts, nextPts,
            status, err,
            config.winSize,
            config.maxLevel,
            config.termCriteria,
            0,
            config.minEigThreshold
        )

        // Build motion energy map
        val energy = Mat.zeros(image.size(), CvType.CV_32FC1)

        val prevArr = prevPts!!.toArray()
        val nextArr = nextPts.toArray()
        val st = status.toArray()

        var goodCount = 0
        var maxMag = 1e-6f

        // First pass: compute magnitudes + write sparse energy
        for (i in st.indices) {
            if (st[i].toInt() != 0) {
                val p0 = prevArr[i]
                val p1 = nextArr[i]

                val dx = (p1.x - p0.x).toFloat()
                val dy = (p1.y - p0.y).toFloat()
                val mag = kotlin.math.sqrt(dx * dx + dy * dy)

                // Ignore tiny jitter (subpixel noise)
                if (mag >= config.minPixelMotion) {
                    goodCount++
                    if (mag > maxMag) maxMag = mag

                    val x = p0.x.toInt().coerceIn(0, energy.cols() - 1)
                    val y = p0.y.toInt().coerceIn(0, energy.rows() - 1)

                    // Put magnitude at that location (sparse)
                    energy.put(y, x, mag.toDouble())
                }
            }
        }

        // Normalize to [0..1] using a stable scale
        // Use either per-frame maxMag (reactive) or config.fixedNormMax (stable)
        val denom = if (config.fixedNormMax > 0f) config.fixedNormMax else maxMag
        if (denom > 1e-6f) {
            Core.multiply(energy, Scalar(1.0 / denom.toDouble()), energy)
        }

        // Spread sparse points to look like a dense motion field (debug + grid-friendly)
        if (goodCount > 0) {
            Imgproc.GaussianBlur(energy, energy, config.splatBlurKernel, 0.0)
            // Clamp just in case
            Core.min(energy, Scalar(1.0), energy)
            Core.max(energy, Scalar(0.0), energy)
        }

        // Update tracked points: keep only good ones, re-wrap into MatOfPoint2f
        prevPts = filterGoodPoints(prevArr, nextArr, st, config)

        // If we lost too many points, force refresh next frame
        if (prevPts == null || prevPts!!.rows() < config.minTrackedPoints) {
            prevPts?.release()
            prevPts = MatOfPoint2f() // empty => trigger re-detect next call
        }

        // Update prev image
        previousImage?.release()
        previousImage = image.clone()

        // Release temp mats
        nextPts.release()
        status.release()
        err.release()

        return energy
    }

    fun reset() {
        previousImage?.release(); previousImage = null
        prevPts?.release(); prevPts = null
        frameIdx = 0
    }

    private fun detectFeatures(gray: Mat, config: LKSparseMotionDetectorConfig): MatOfPoint2f {
        val corners = MatOfPoint()
        Imgproc.goodFeaturesToTrack(
            gray,
            corners,
            config.maxCorners,
            config.qualityLevel,
            config.minDistance,
            Mat(),
            config.blockSize,
            false,
            config.k
        )
        val pts2f = MatOfPoint2f()
        pts2f.fromArray(*corners.toArray())
        corners.release()
        return pts2f
    }

    private fun filterGoodPoints(
        prevArr: Array<Point>,
        nextArr: Array<Point>,
        st: ByteArray,
        config: LKSparseMotionDetectorConfig
    ): MatOfPoint2f {
        val kept = ArrayList<Point>(prevArr.size)
        for (i in st.indices) {
            if (st[i].toInt() != 0) {
                val p0 = prevArr[i]
                val p1 = nextArr[i]
                val dx = (p1.x - p0.x).toFloat()
                val dy = (p1.y - p0.y).toFloat()
                val mag = kotlin.math.sqrt(dx * dx + dy * dy)
                if (mag >= config.minPixelMotion) {
                    // Keep the NEW point to track forward
                    kept.add(p1)
                }
            }
        }
        val out = MatOfPoint2f()
        out.fromList(kept)
        return out
    }
}

data class LKSparseMotionDetectorConfig(
    // Feature detection (goodFeaturesToTrack)
    val maxCorners: Int = 600,
    val qualityLevel: Double = 0.01,
    val minDistance: Double = 8.0,
    val blockSize: Int = 7,
    val k: Double = 0.04,

    // LK tracking params
    val winSize: Size = Size(21.0, 21.0),
    val maxLevel: Int = 3,
    val termCriteria: TermCriteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 20, 0.03),
    val minEigThreshold: Double = 1e-4,

    // Stability knobs
    val refreshEveryNFrames: Int = 8,
    val minTrackedPoints: Int = 120,
    val minPixelMotion: Float = 1.0f,   // ignore subpixel jitter

    // Normalization
    val fixedNormMax: Float = 12.0f,    // px/frame; set 0 to use per-frame max

    // “Splat” blur makes sparse points look like a dense field
    val splatBlurKernel: Size = Size(15.0, 15.0),
) : IFrameProcessorConfig