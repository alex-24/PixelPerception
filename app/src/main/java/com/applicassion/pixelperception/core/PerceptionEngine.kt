package com.applicassion.pixelperception.core

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import com.applicassion.pixelperception.core.model.CoreDebugOutput
import com.applicassion.pixelperception.core.model.CoreOutputGrid
import com.applicassion.pixelperception.core.utils.adaptOrientationForDisplay
import com.applicassion.pixelperception.core.utils.applyGainClamped8U
import com.applicassion.pixelperception.core.utils.toMat
import com.applicassion.pixelperception.core.vision.frame_processors.depth_detection.LiteRtDepthDetector
import com.applicassion.pixelperception.core.vision.frame_processors.depth_detection.LiteRtDepthDetectorConfig
import com.applicassion.pixelperception.core.vision.frame_processors.edge_detection.CannyEdgeDetector
import com.applicassion.pixelperception.core.vision.frame_processors.edge_detection.EdgeDetectorConfig
import com.applicassion.pixelperception.core.vision.frame_processors.motion_detection.FrameDiffMotionDetector
import com.applicassion.pixelperception.core.vision.frame_processors.motion_detection.FrameDiffMotionDetectorConfig
import com.applicassion.pixelperception.core.vision.frame_processors.motion_detection.LKSparseMotionDetector
import com.applicassion.pixelperception.core.vision.frame_processors.motion_detection.LKSparseMotionDetectorConfig
import com.applicassion.pixelperception.core.vision.frame_processors.motion_detection.TemporalMotionAccumulationDetector
import com.applicassion.pixelperception.core.vision.frame_processors.motion_detection.TemporalMotionAccumulationDetectorConfig
import com.applicassion.pixelperception.core.vision.output_combinator.OutputAggregator
import com.applicassion.pixelperception.core.vision.output_combinator.WeightedOutputAggregator
import com.applicassion.pixelperception.core.vision.output_combinator.WeightedOutputAggregatorConfig
import com.applicassion.pixelperception.platform.OnFrameListener
import com.google.ai.edge.litert.Accelerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.core.TermCriteria

class PerceptionEngine(
    val context: Context,
) : OnFrameListener {

    companion object {
        const val TAG = "PerceptionEngine"
    }

    private var coroutineScope: CoroutineScope? = null

    enum class OutputType {
        PixelPerceptionGrid,
        CameraFeedMat,
        GreyScaleMat,
        EdgeDetectionMat,
        RawMotionDetectionMat,
        AccumulatedMotionDetectionMat,
        DepthDetectionMat
    }

    enum class MotionDetectionType {
        FrameDiff,
        SparseLK
    }

    private val _isOutputEnabled = mutableMapOf(
        OutputType.PixelPerceptionGrid to false,
        OutputType.CameraFeedMat to false,
        OutputType.GreyScaleMat to false,
        OutputType.EdgeDetectionMat to false,
        OutputType.RawMotionDetectionMat to false,
        OutputType.AccumulatedMotionDetectionMat to false,
        OutputType.DepthDetectionMat to false,
    )

    private val _framesFlow: MutableSharedFlow<Pair<ImageProxy, Long>> = MutableSharedFlow(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private var _frameCollector: Job? = null

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

    private val _rawMotionDetectionFlow: MutableSharedFlow<CoreDebugOutput.RawMotionDetection> = MutableSharedFlow(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val rawMotionDetectionDebugFlow = _rawMotionDetectionFlow.asSharedFlow()

    private val _accumulatedMotionDetectionFlow: MutableSharedFlow<CoreDebugOutput.AccumulatedMotionDetection> = MutableSharedFlow(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val accumulatedMotionDetectionDebugFlow = _accumulatedMotionDetectionFlow.asSharedFlow()

    private val _depthDetectionDebugFlow: MutableSharedFlow<CoreDebugOutput.DepthDetection> = MutableSharedFlow(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val depthDetectionDebugFlow = _depthDetectionDebugFlow.asSharedFlow()

    private val _depthDetectionStateFlow: MutableStateFlow<Mat?> = MutableStateFlow(
        value = null
    )
    val depthDetectionStateFlow = _depthDetectionDebugFlow.asSharedFlow()

    private var _depthDetector: LiteRtDepthDetector? = null

    private var _depthDetectionFrequencyMs = 33 * 2 // 30fps
    private var _lastDepthDetectionTsMs = 0L

    fun start(
        coroutineScope: CoroutineScope,
        cameraSelector: CameraSelector,
        motionDetectionType: MotionDetectionType = MotionDetectionType.SparseLK
    ) {
        this.coroutineScope = coroutineScope
        _frameCollector?.cancel()
        _frameCollector = coroutineScope
            .launch(Dispatchers.IO) {
                _framesFlow
                    .collect { (frame, timeStampMS) ->
                        try {
                            if (timeStampMS - _lastDepthDetectionTsMs >= _depthDetectionFrequencyMs) {
                                if (_depthDetector == null) {
                                    _depthDetector = LiteRtDepthDetector(
                                        context = context,
                                        modelAssetPath = "models/midas-tflite-v2-1-small-lite-v1.tflite",
                                        accelerator = Accelerator.GPU
                                    )
                                }

                                _depthDetector?.processFrame(
                                    image = frame.toMat(CvType.CV_8UC4),
                                    config = LiteRtDepthDetectorConfig(
                                        desiredOutputType = LiteRtDepthDetector.OutputType.GreyScale_Normalized_01
                                    )
                                )?.also { depth ->
                                    if (_isOutputEnabled[OutputType.DepthDetectionMat] == true) {
                                        _depthDetectionDebugFlow.emit(
                                            CoreDebugOutput.DepthDetection(mat = depth.clone().adaptOrientationForDisplay(cameraSelector))
                                        )
                                    }

                                    _depthDetectionStateFlow.value?.release()
                                    _depthDetectionStateFlow.value = depth
                                }

                                _lastDepthDetectionTsMs = SystemClock.elapsedRealtime()
                            }

                            frame.toMat(CvType.CV_8UC1)
                                .also { greyScale ->
                                    val gs = greyScale.applyGainClamped8U(2.0)
                                    _greyScaleDebugFlow.emit(
                                        CoreDebugOutput.GreyScale(mat = gs.clone().adaptOrientationForDisplay(cameraSelector))
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
                                                    CoreDebugOutput.EdgeDetection(mat = edges.clone().adaptOrientationForDisplay(cameraSelector))
                                                )
                                            }

                                            var accumulatedMotion : Mat? = null
                                            when (motionDetectionType) {
                                                MotionDetectionType.FrameDiff -> {
                                                    FrameDiffMotionDetector
                                                        .processFrame(
                                                            image = gs,
                                                            config = FrameDiffMotionDetectorConfig(
                                                                enableSmoothing = true,
                                                                smoothingKernelSize = 3.0,
                                                                motionMinThreshold = 15.0
                                                            )
                                                        ).also { motion ->
                                                            if (_isOutputEnabled[OutputType.RawMotionDetectionMat] == true) {
                                                                _rawMotionDetectionFlow.emit(
                                                                    CoreDebugOutput.RawMotionDetection(mat = motion.clone().adaptOrientationForDisplay(cameraSelector))
                                                                )
                                                            }
                                                            TemporalMotionAccumulationDetector
                                                                .processFrame(
                                                                    image = motion,
                                                                    config = TemporalMotionAccumulationDetectorConfig(
                                                                        decay = 0.5,
                                                                        gain = 1.0,
                                                                    )
                                                                ).also { accumulation ->
                                                                    if (_isOutputEnabled[OutputType.AccumulatedMotionDetectionMat] == true) { // todo manage stage outputs better
                                                                        _accumulatedMotionDetectionFlow.emit(
                                                                            CoreDebugOutput.AccumulatedMotionDetection(mat = accumulation.clone().adaptOrientationForDisplay(cameraSelector))
                                                                        )
                                                                    }
                                                                    accumulatedMotion = accumulation
                                                                }
                                                        }.release()
                                                }
                                                MotionDetectionType.SparseLK -> {
                                                    LKSparseMotionDetector
                                                        .processFrame(
                                                            image = gs,
                                                            config = LKSparseMotionDetectorConfig(
                                                                maxCorners = 1600,
                                                                qualityLevel = 0.01,
                                                                minDistance = 8.0,
                                                                blockSize = 15,
                                                                k = 0.04,
                                                                winSize = Size(
                                                                    25.0,
                                                                    25.0
                                                                ),
                                                                maxLevel = 3,
                                                                termCriteria = TermCriteria(
                                                                    TermCriteria.COUNT + TermCriteria.EPS,
                                                                    20,
                                                                    0.03
                                                                ),
                                                                minEigThreshold = 1e-4,
                                                                refreshEveryNFrames = 8,
                                                                minTrackedPoints = 120,
                                                                minPixelMotion = 1.0f,
                                                                fixedNormMax = 12.0f,
                                                                splatBlurKernel = Size(
                                                                    15.0,
                                                                    15.0
                                                                )
                                                            )
                                                        ).also { motion ->
                                                            if (_isOutputEnabled[OutputType.RawMotionDetectionMat] == true) {
                                                                _rawMotionDetectionFlow.emit(
                                                                    CoreDebugOutput.RawMotionDetection(mat = motion.clone().adaptOrientationForDisplay(cameraSelector))
                                                                )
                                                            }
                                                            TemporalMotionAccumulationDetector
                                                                .processFrame(
                                                                    image = motion,
                                                                    config = TemporalMotionAccumulationDetectorConfig(
                                                                        decay = 0.5,
                                                                        gain = 1.0,
                                                                    )
                                                                ).also { accumulation ->
                                                                    if (_isOutputEnabled[OutputType.AccumulatedMotionDetectionMat] == true) {
                                                                        _accumulatedMotionDetectionFlow.emit(
                                                                            CoreDebugOutput.AccumulatedMotionDetection(mat = accumulation.clone().adaptOrientationForDisplay(cameraSelector))
                                                                        )
                                                                    }
                                                                    accumulatedMotion = accumulation
                                                                }
                                                        }.release()
                                                }
                                            }

                                            WeightedOutputAggregator()
                                                .apply {
                                                    val edges01 = edges
                                                    val motion01 = accumulatedMotion ?: Mat.zeros(edges.size(), edges.type())
                                                    val depth01 = _depthDetectionStateFlow.value ?: Mat.zeros(edges.size(), edges.type())
                                                    aggregate(
                                                        edge01 = edges01,
                                                        motion01 = motion01,
                                                        depth01 = depth01,
                                                        config = WeightedOutputAggregatorConfig(
                                                            wE  = 0.30f,
                                                            wM  = 0.40f,
                                                            wD  = 0.30f,
                                                            wNM  = 0.40f,
                                                            wNE = 0.95f
                                                        )
                                                    ).also { aggregatedOutput01 ->
                                                        edges01.release()
                                                        motion01.release()
                                                        depth01.release()

                                                        projectToGrid(
                                                            aggregate01 = aggregatedOutput01,
                                                            gridW = 16 * 3,
                                                            gridH = 9 * 3,
                                                            mode = OutputAggregator.PoolingMode.AVG
                                                        ).also { projection01 ->
                                                            projection01.adaptOrientationForDisplay(cameraSelector)
                                                                .also { pixelPerception ->
                                                                    _pixelPerceptionOutputFlow.emit(pixelPerception)
                                                                }
                                                        }
                                                    }
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
        coroutineScope?.launch { _framesFlow.emit(Pair(frame, SystemClock.elapsedRealtime())) }
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
            .forEach { _isOutputEnabled[it] = false }
    }

    fun dispose() {
        disableAllOutputTypes()
        _frameCollector?.cancel()
        _depthDetector?.close()
        _depthDetector = null
        _depthDetectionStateFlow.value?.release()
        _depthDetectionStateFlow.value = null
    }
}