package com.applicassion.pixelperception.core.vision.motion_detection

import com.applicassion.pixelperception.core.vision.IFrameProcessor
import com.applicassion.pixelperception.core.vision.IFrameProcessorConfig
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.Double

/**
 * Simple motion detection: calculates the difference between 2 frames (very sensitive to noise)
 */
object FrameDiffMotionDetector: IFrameProcessor<FrameDiffMotionDetectorConfig> {
    private var previousImage: Mat? = null

    override fun processFrame(
        image: Mat,
        config: FrameDiffMotionDetectorConfig
    ): Mat {
        if (previousImage == null) {
            previousImage = image.clone()
            return Mat.zeros(image.size(), CvType.CV_32FC1)
        }

        val diff = Mat()
        Core.absdiff(image, previousImage, diff)
        Imgproc.threshold(
            diff,
            diff,
            config.motionMinThreshold,
            config.motionMaxThreshold,
            Imgproc.THRESH_TOZERO
        )

        if (config.enableSmoothing && config.smoothingKernelSize > 1.0) {
            Imgproc.GaussianBlur(
                diff,
                diff,
                Size(
                    config.smoothingKernelSize,
                    config.smoothingKernelSize
                ),
                0.0)
        }

        // Normalize to [0..1]
        val motion = Mat()
        diff.convertTo(motion, CvType.CV_32FC1, 1.0 / 255.0)

        previousImage?.release()
        previousImage = image.clone()

        diff.release()
        return motion
    }
}

class FrameDiffMotionDetectorConfig(
    val enableSmoothing: Boolean = true,
    val smoothingKernelSize: Double = 5.0,
    val motionMinThreshold: Double = 25.0,
    val motionMaxThreshold: Double = 255.0
): IFrameProcessorConfig