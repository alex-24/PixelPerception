package com.applicassion.pixelperception.core.vision.edge_detection

import com.applicassion.pixelperception.core.vision.IFrameProcessor
import com.applicassion.pixelperception.core.vision.IFrameProcessorConfig
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.Double

object CannyEdgeDetector: IFrameProcessor<EdgeDetectorConfig> {
    override fun processFrame(
        image: Mat,
        config: EdgeDetectorConfig
    ): Mat {
        val edges = Mat()
        Imgproc.Canny(
            image,
            edges,
            config.lowThreshold,
            config.highThreshold
        )
        return edges
    }
}

class EdgeDetectorConfig(
    val lowThreshold: Double,
    val highThreshold: Double
): IFrameProcessorConfig