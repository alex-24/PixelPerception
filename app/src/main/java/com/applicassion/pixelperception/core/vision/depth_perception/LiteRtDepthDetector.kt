package com.applicassion.pixelperception.core.vision.depth_perception

import android.content.Context
import android.util.Log
import com.applicassion.pixelperception.core.vision.IFrameProcessor
import com.applicassion.pixelperception.core.vision.IFrameProcessorConfig
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * LiteRT monocular depth detector (GPU by default) producing grayscale depth:
 * - GreyScale_Raw: CV_32FC1 at model resolution (raw model output)
 * - GreyScale_Normalized_01: CV_32FC1 in [0..1] at camera resolution (stable mapping)
 *
 * Colorization for display is handled by CoreUtils.toBitmap() with FloatMapping.DepthColor.
 *
 * Key points:
 * - No per-frame NORM_MINMAX (psychedelic); uses EMA min/max for stability
 * - Optional invert + gamma contrast
 * - Optional ImageNet normalization for MiDaS-like models
 */
class LiteRtDepthDetector(
    context: Context,
    modelAssetPath: String,
    accelerator: Accelerator,
) : IFrameProcessor<LiteRtDepthDetectorConfig> {

    companion object {
        private const val TAG = "LiteRtDepthDetector"
    }

    enum class OutputType {
        /** Raw model output at model resolution (not normalized) */
        GreyScale_Raw,
        /** Normalized [0..1] at camera resolution (use with FloatMapping.DepthColor for display) */
        GreyScale_Normalized_01;

        fun getCVType(): Int = CvType.CV_32FC1
    }

    // --- LiteRT model ---
    private val model: CompiledModel = CompiledModel.create(
        context.assets,
        modelAssetPath,
        CompiledModel.Options(accelerator),
        /* env = */ null
    )

    private val inputBuffers = model.createInputBuffers()
    private val outputBuffers = model.createOutputBuffers()

    // --- Stable normalization state (EMA of min/max) ---
    private var emaMin: Double? = null
    private var emaMax: Double? = null

    override fun processFrame(
        image: Mat,
        config: LiteRtDepthDetectorConfig
        ): Mat {
        require(image.type() == CvType.CV_8UC4) { "Expected RGBA CV_8UC4 input" }
        if (image.empty()) {
            return  Mat.zeros(image.size(), config.desiredOutputType.getCVType())
        }

        val inW = config.inputWidth
        val inH = config.inputHeight

        // 1) RGBA -> RGB
        val rgb = Mat()
        Imgproc.cvtColor(image, rgb, Imgproc.COLOR_RGBA2RGB)

        // 2) Resize to model input
        val resized = Mat()
        Imgproc.resize(
            rgb,
            resized,
            Size(inW.toDouble(), inH.toDouble()),
            0.0,
            0.0,
            Imgproc.INTER_AREA
        )

        // 3) Pack float input [H*W*3] in RGB order
        val pix = ByteArray(inW * inH * 3)
        resized.get(0, 0, pix)

        val inputFloats = FloatArray(inW * inH * 3)

        if (config.useImageNetNorm) {
            // Common for MiDaS-ish pipelines
            val mean = config.imageNetMean
            val std = config.imageNetStd
            var p = 0
            for (i in 0 until (inW * inH)) {
                val r = (pix[p++].toInt() and 0xFF) / 255f
                val g = (pix[p++].toInt() and 0xFF) / 255f
                val b = (pix[p++].toInt() and 0xFF) / 255f
                val base = i * 3
                inputFloats[base + 0] = (r - mean[0]) / std[0]
                inputFloats[base + 1] = (g - mean[1]) / std[1]
                inputFloats[base + 2] = (b - mean[2]) / std[2]
            }
        } else if (config.useMinusOneToOne) {
            // Alternate common normalization [-1..1]
            var p = 0
            var j = 0
            while (p < pix.size) {
                val r = (pix[p++].toInt() and 0xFF) / 255f
                val g = (pix[p++].toInt() and 0xFF) / 255f
                val b = (pix[p++].toInt() and 0xFF) / 255f
                inputFloats[j++] = r * 2f - 1f
                inputFloats[j++] = g * 2f - 1f
                inputFloats[j++] = b * 2f - 1f
            }
        } else {
            // Plain [0..1]
            var p = 0
            var j = 0
            while (p < pix.size) {
                inputFloats[j++] = (pix[p++].toInt() and 0xFF) / 255f
                inputFloats[j++] = (pix[p++].toInt() and 0xFF) / 255f
                inputFloats[j++] = (pix[p++].toInt() and 0xFF) / 255f
            }
        }

        // 4) Run LiteRT
        inputBuffers[0].writeFloat(inputFloats)
        model.run(inputBuffers, outputBuffers)

        // 5) Read output floats and wrap into a Mat
        val out = outputBuffers[0].readFloat()
        
        // Infer output dimensions from buffer size (assume square output)
        val outPixels = out.size
        val outDim = kotlin.math.sqrt(outPixels.toDouble()).toInt()
        
        if (outDim * outDim != outPixels) {
            Log.e(TAG, "Output size=$outPixels is not a perfect square. Cannot infer dimensions.")
            rgb.release()
            resized.release()
            return Mat.zeros(image.size(), config.desiredOutputType.getCVType())
        }
        
        if (outDim != inW || outDim != inH) {
            Log.w(TAG, "Output dimensions ${outDim}x${outDim} differ from input ${inW}x${inH}")
        }

        val depthSmall = Mat(outDim, outDim, OutputType.GreyScale_Raw.getCVType())
        depthSmall.put(0, 0, out)

        // 6) Stable mapping to [0..1] using EMA min/max (prevents “psychedelic” per-frame scaling)
        val mm = Core.minMaxLoc(depthSmall)
        val curMin = mm.minVal
        val curMax = mm.maxVal

        val a = config.emaAlpha
        emaMin = if (emaMin == null) curMin else (1.0 - a) * emaMin!! + a * curMin
        emaMax = if (emaMax == null) curMax else (1.0 - a) * emaMax!! + a * curMax

        val minV = emaMin!!
        val maxV = (emaMax!!).coerceAtLeast(minV + 1e-6)

        val depth01Small = Mat()
        Core.subtract(depthSmall, Scalar(minV), depth01Small)
        Core.multiply(depth01Small, Scalar(1.0 / (maxV - minV)), depth01Small)
        Core.min(depth01Small, Scalar(1.0), depth01Small)
        Core.max(depth01Small, Scalar(0.0), depth01Small)

        if (config.invert01) {
            // depth01Small = 1.0 - depth01Small
            Core.multiply(depth01Small, Scalar(-1.0), depth01Small)
            Core.add(depth01Small, Scalar(1.0), depth01Small)
        }

        // Light smoothing to kill speckle before colormap (keep it tiny)
        if (config.blurKSize > 0) {
            Imgproc.GaussianBlur(
                depth01Small,
                depth01Small,
                Size(config.blurKSize.toDouble(), config.blurKSize.toDouble()),
                0.0
            )
        }

        // Contrast shaping
        if (config.gamma != 1.0) {
            Core.pow(depth01Small, config.gamma, depth01Small)
        }

        // 7) Resize depth01 back to camera size
        val depth01Full = Mat()
        Imgproc.resize(
            depth01Small,
            depth01Full,
            Size(image.cols().toDouble(), image.rows().toDouble()),
            0.0,
            0.0,
            Imgproc.INTER_LINEAR
        )

        // Cleanup intermediate mats
        rgb.release()
        resized.release()
        depth01Small.release()

        // Return based on desired output type
        return when (config.desiredOutputType) {
            OutputType.GreyScale_Raw -> {
                depth01Full.release()
                depthSmall
            }
            OutputType.GreyScale_Normalized_01 -> {
                depthSmall.release()
                depth01Full
            }
        }
    }

    fun close() {
        try {
            model.close()
        } catch (_: Throwable) {
        }
    }
}

/**
 * Config:
 * - Use EMA normalization to avoid “psychedelic” per-frame scaling
 * - Choose preprocessing normalization based on your model
 */
data class LiteRtDepthDetectorConfig(
    val desiredOutputType: LiteRtDepthDetector.OutputType = LiteRtDepthDetector.OutputType.GreyScale_Normalized_01,

    // Model input size (set to your model’s expected input)
    val inputWidth: Int = 256,
    val inputHeight: Int = 256,

    // Input normalization options (pick ONE typical path)
    val useImageNetNorm: Boolean = true,
    val useMinusOneToOne: Boolean = false,

    // ImageNet constants
    val imageNetMean: FloatArray = floatArrayOf(0.485f, 0.456f, 0.406f),
    val imageNetStd: FloatArray = floatArrayOf(0.229f, 0.224f, 0.225f),

    // Stable depth mapping
    val emaAlpha: Double = 0.05,   // 0.02..0.1
    val invert01: Boolean = false,  // flip if needed
    val gamma: Double = 0.7,       // 0.5..1.6, tweak for contrast

    // Post-processing
    val blurKSize: Int = 3         // 0 (off), 3, 5
) : IFrameProcessorConfig {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LiteRtDepthDetectorConfig

        if (desiredOutputType != other.desiredOutputType) return false
        if (inputWidth != other.inputWidth) return false
        if (inputHeight != other.inputHeight) return false
        if (useImageNetNorm != other.useImageNetNorm) return false
        if (useMinusOneToOne != other.useMinusOneToOne) return false
        if (emaAlpha != other.emaAlpha) return false
        if (invert01 != other.invert01) return false
        if (gamma != other.gamma) return false
        if (blurKSize != other.blurKSize) return false
        if (!imageNetMean.contentEquals(other.imageNetMean)) return false
        if (!imageNetStd.contentEquals(other.imageNetStd)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = desiredOutputType.hashCode()
        result = 31 * result + inputWidth
        result = 31 * result + inputHeight
        result = 31 * result + useImageNetNorm.hashCode()
        result = 31 * result + useMinusOneToOne.hashCode()
        result = 31 * result + emaAlpha.hashCode()
        result = 31 * result + invert01.hashCode()
        result = 31 * result + gamma.hashCode()
        result = 31 * result + blurKSize
        result = 31 * result + imageNetMean.contentHashCode()
        result = 31 * result + imageNetStd.contentHashCode()
        return result
    }
}