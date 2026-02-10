package com.applicassion.pixelperception.core.vision

import org.opencv.core.Mat

interface IFrameProcessor<T: IFrameProcessorConfig> {
    fun processFrame(image: Mat, config: T): Mat
}

interface IFrameProcessorConfig