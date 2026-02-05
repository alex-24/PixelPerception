package com.applicassion.pixelperception.core

import android.util.Log
import androidx.camera.core.ImageProxy
import com.applicassion.pixelperception.core.model.CoreDebugOutput
import com.applicassion.pixelperception.core.model.CoreOutputGrid
import com.applicassion.pixelperception.core.utils.applyGainClamped8U
import com.applicassion.pixelperception.core.utils.rotate90CCWThenFlipHorizontal
import com.applicassion.pixelperception.core.utils.toMat
import com.applicassion.pixelperception.core.vision.CannyEdgeDetector
import com.applicassion.pixelperception.core.vision.EdgeDetectorConfig
import com.applicassion.pixelperception.platform.OnFrameListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.opencv.core.CvType

class PerceptionEngine(
    val coroutineScope: CoroutineScope
) : OnFrameListener {

    companion object {
        const val TAG = "PerceptionEngine"
    }

    enum class OutputType {
        PixelPerceptionGrid,
        CameraFeedMat,
        GreyScaleMat,
        EdgeDetectionMat,
        MotionDetectionMat,
        DepthDetectionMat
    }


    private val _isOutputEnabled = mutableMapOf(
        OutputType.PixelPerceptionGrid to false,
        OutputType.CameraFeedMat to false,
        OutputType.GreyScaleMat to false,
        OutputType.EdgeDetectionMat to false,
        OutputType.MotionDetectionMat to false,
        OutputType.DepthDetectionMat to false,
    )

    private val _framesFlow: MutableSharedFlow<ImageProxy> = MutableSharedFlow(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val _frameCollector: Job?

    private val _pixelPerceptionOutputFlow: MutableSharedFlow<CoreOutputGrid> = MutableSharedFlow(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val pixelPerceptionOutputFlow = _pixelPerceptionOutputFlow.asSharedFlow()

    private val _greyScaleDebugFlow: MutableSharedFlow<CoreDebugOutput.GreyScale> = MutableSharedFlow(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val greyScaleDebugFlow = _greyScaleDebugFlow.asSharedFlow()

    private val _edgeDetectionFlow: MutableSharedFlow<CoreDebugOutput.EdgeDetection> = MutableSharedFlow(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val edgeDetectionDebugFlow = _edgeDetectionFlow.asSharedFlow()

    private val _motionDetectionFlow: MutableSharedFlow<CoreDebugOutput.MotionDetection> = MutableSharedFlow(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val motionDetectionDebugFlow = _motionDetectionFlow.asSharedFlow()

    private val _depthDetectionFlow: MutableSharedFlow<CoreDebugOutput.DepthDetection> = MutableSharedFlow(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val depthDetectionDebugFlow = _depthDetectionFlow.asSharedFlow()

    init {
        _frameCollector = coroutineScope
            .launch(Dispatchers.IO) {
                _framesFlow
                    .collect { frame ->
                        try {

                            frame.toMat(CvType.CV_8UC1)
                                .also { greyScale ->
                                    val gs = greyScale.applyGainClamped8U()
                                    _greyScaleDebugFlow.emit(
                                        CoreDebugOutput.GreyScale(mat = gs.clone().rotate90CCWThenFlipHorizontal())
                                    )

                                    CannyEdgeDetector
                                        .processFrame(
                                            image = gs,
                                            config = EdgeDetectorConfig(
                                                lowThreshold = 60.0,
                                                highThreshold = 160.0
                                            )
                                        ).also { edges ->
                                            if (_isOutputEnabled[OutputType.EdgeDetectionMat] == true) {
                                                _edgeDetectionFlow.emit(
                                                    CoreDebugOutput.EdgeDetection(mat = edges.clone().rotate90CCWThenFlipHorizontal())
                                                )
                                            }
                                        }.release()
                            }.release()
                        } catch (e: Exception) {
                            Log.e(TAG, "Frame processing failed", e)
                        } finally {
                            frame.close()
                        }
                    }
            }

    }

    override fun onFrameSuccess(frame: ImageProxy) {
        coroutineScope.launch { _framesFlow.emit(frame) }
    }

    override fun onFrameError(error: Throwable) {
        //TODO("Not yet implemented")
    }

    fun enableSingleOutputType(
        type: OutputType,
        autoDisableOtherTypes: Boolean = true
    ) {
        _isOutputEnabled[type] = true

        if (autoDisableOtherTypes) {
            OutputType
                .entries
                .filter { it != type }
                .forEach { _isOutputEnabled[it] = false }
        }
    }

    fun enableAllOutputTypes() {
        OutputType
            .entries
            .forEach { _isOutputEnabled[it] = true }
    }

    fun disableAllOutputTypes() {
        OutputType
            .entries
            .forEach { _isOutputEnabled[it] = true }
    }

    fun dispose() {
        disableAllOutputTypes()
        _frameCollector?.cancel()
    }
}