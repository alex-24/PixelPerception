package com.applicassion.pixelperception.core.vision.output_combinator

import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

class WeightedOutputAggregator(): OutputAggregator<WeightedOutputAggregatorConfig>() {

    companion object {
        const val TAG = "WeightedOutputAggregator"
    }
    override fun aggregate(
        edge01: Mat,
        motion01: Mat,
        depth01: Mat,
        config: WeightedOutputAggregatorConfig
    ): Mat {
        require(edge01.size() == motion01.size() && edge01.size() == depth01.size()) {
            "All inputs must have same size: edge=${edge01.size()} motion=${motion01.size()} depth=${depth01.size()}".also {
                Log.e(TAG, it)
            }
        }

        val edgeF = ensureFloat01(edge01, assume8uIs255 = true)
        val motionF = ensureFloat01(motion01, assume8uIs255 = false)
        val depthF = ensureFloat01(depth01, assume8uIs255 = true)

        val out = Mat(edge01.size(), CvType.CV_32FC1)
        val tmp = Mat(edge01.size(), CvType.CV_32FC1)

        try {
            // out = wE*edge
            Core.multiply(edgeF, Scalar(config.wE.toDouble()), out)

            // out += wM*motion
            Core.multiply(motionF, Scalar(config.wM.toDouble()), tmp)
            Core.add(out, tmp, out)

            // out += wD*depth
            Core.multiply(depthF, Scalar(config.wD.toDouble()), tmp)
            Core.add(out, tmp, out)

            // out += wNM*(depth*motion)
            Core.multiply(depthF, motionF, tmp)
            Core.multiply(tmp, Scalar(config.wNM.toDouble()), tmp)
            Core.add(out, tmp, out)

            // out += wNE*(depth*edge)
            Core.multiply(depthF, edgeF, tmp)
            Core.multiply(tmp, Scalar(config.wNE.toDouble()), tmp)
            Core.add(out, tmp, out)

            // clamp 0..1
            Core.min(out, Scalar(1.0), out)
            Core.max(out, Scalar(0.0), out)

            return out
        } finally {
            edgeF.release()
            motionF.release()
            depthF.release()
            tmp.release()
        }
    }

    /**
     * Returns a NEW Mat(CV_32FC1) in [0..1].
     * - If src is CV_8U and assume8uIs255=true -> scales by 1/255
     * - If src is CV_32F -> clamps to [0..1] (no scaling)
     */
    private fun ensureFloat01(src: Mat, assume8uIs255: Boolean): Mat {
        val dst = Mat(src.size(), CvType.CV_32FC1)
        when (src.type()) {
            CvType.CV_32FC1 -> {
                src.copyTo(dst)
                Core.min(dst, Scalar(1.0), dst)
                Core.max(dst, Scalar(0.0), dst)
            }
            CvType.CV_8UC1 -> {
                val scale = if (assume8uIs255) (1.0 / 255.0) else 1.0
                src.convertTo(dst, CvType.CV_32FC1, scale)
                Core.min(dst, Scalar(1.0), dst)
                Core.max(dst, Scalar(0.0), dst)
            }
            else -> {
                // Fallback: convert to float, then clamp
                src.convertTo(dst, CvType.CV_32FC1)
                Core.min(dst, Scalar(1.0), dst)
                Core.max(dst, Scalar(0.0), dst)
            }
        }
        return dst
    }

}

/***
 *                                            near motion      near edge
 * Formula: (wE * E) + (wM * M) + (wD * D) + (wNM * D * M) + (wNE * D * E)
 */
data class WeightedOutputAggregatorConfig(
    val wE: Float = 0.45f,
    val wM: Float = 0.35f,
    val wD: Float = 0.20f,
    val wNM: Float = 0.30f,
    val wNE: Float = 0.15f,
): OutputAggregatorConfig()