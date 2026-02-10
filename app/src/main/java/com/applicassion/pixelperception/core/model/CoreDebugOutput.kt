package com.applicassion.pixelperception.core.model

import org.opencv.core.Mat

/**
 * Represents an intermediary output in the perception pipeline.
 * It exposes a Mat that is readonly for the UI and the same size as
 * the perception input images (from the camera)
 */
sealed class CoreDebugOutput() {

    abstract fun getData(): Mat

    data class GreyScale(val mat: Mat): CoreDebugOutput() {
        override fun getData(): Mat {
            return mat
        }
    }

    data class EdgeDetection(val mat: Mat): CoreDebugOutput() {
        override fun getData(): Mat {
            return mat
        }
    }

    data class MotionDetection(val mat: Mat): CoreDebugOutput() {
        override fun getData(): Mat {
            return mat
        }
    }

    data class DepthDetection(val mat: Mat): CoreDebugOutput() {
        override fun getData(): Mat {
            return mat
        }
    }
}