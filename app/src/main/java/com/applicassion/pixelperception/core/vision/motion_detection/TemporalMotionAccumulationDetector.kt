package com.applicassion.pixelperception.core.vision.motion_detection

import com.applicassion.pixelperception.core.vision.IFrameProcessor
import com.applicassion.pixelperception.core.vision.IFrameProcessorConfig
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

/**
 * Temporal Motion Accumulation Detector
 *
 * Accumulates motion (from a frame diff input) over time.
 * Output is a motion "heat map" where:
 * - Bright areas = recent motion
 * - Darker areas = older motion (fading)
 * - Black = no recent motion
 *
 * The decay parameter controls how fast old motion fades out.
 */
object TemporalMotionAccumulationDetector : IFrameProcessor<TemporalMotionAccumulationDetectorConfig> {
    private var acc: Mat? = null

    override fun processFrame(
        image: Mat,
        config: TemporalMotionAccumulationDetectorConfig
    ): Mat {
        if (image.empty()) {
            reset()
            return Mat()
        }

        val currentAcc = acc
        if (currentAcc == null || currentAcc.empty()) {
            acc?.release()
            acc = Mat.zeros(image.size(), CvType.CV_32FC1)
            return acc!!.clone()
        }

        // If size/type changed (orientation change, resolution change, different stage), re-init
        if (currentAcc.rows() != image.rows() || currentAcc.cols() != image.cols()) {
            currentAcc.release()
            acc = Mat.zeros(image.size(), CvType.CV_32FC1)
            return acc!!.clone()
        }

        // Decay existing accumulated motion
        Core.multiply(currentAcc, Scalar(1.0 - config.decay), currentAcc)

        // Add new motion to accumulator (scaled by gain)
        Core.scaleAdd(image, config.gain, currentAcc, currentAcc)

        Core.min(currentAcc, Scalar(1.0), currentAcc)

        return currentAcc.clone()
    }

    fun reset() {
        acc?.release()
        acc = null
    }
}

class TemporalMotionAccumulationDetectorConfig(
    /** How fast old motion fades out per frame (0-1). Higher = faster decay */
    val decay: Double = 0.05,

    /** How much to boost new motion (0-1+). Higher = more sensitive */
    val gain: Double = 0.5,
) : IFrameProcessorConfig