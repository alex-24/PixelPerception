package com.applicassion.pixelperception.core

import android.util.Log
import androidx.camera.core.ImageProxy
import com.applicassion.pixelperception.core.model.CoreOutputGrid
import com.applicassion.pixelperception.core.utils.applyGain
import com.applicassion.pixelperception.core.utils.toCoreOutputGrid
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

    private val _framesFlow: MutableSharedFlow<ImageProxy> = MutableSharedFlow(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val _frameCollector: Job?

    private val _edgeDetectionFlow: MutableSharedFlow<CoreOutputGrid> = MutableSharedFlow(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val edgeDetectionFlow = _edgeDetectionFlow.asSharedFlow()

    var enableEdgeDetectionUiOutput = true

    init {
        _frameCollector = coroutineScope
            .launch(Dispatchers.IO) {
                _framesFlow
                    .collect { frame ->
                        try {

                            frame.toMat(CvType.CV_8UC1)
                                .also { greyScale ->
                                    CannyEdgeDetector
                                        .processFrame(
                                            image = greyScale,
                                            config = EdgeDetectorConfig(
                                                lowThreshold = 60.0,
                                                highThreshold = 160.0
                                            )
                                        ).also { edges ->
                                            if (enableEdgeDetectionUiOutput) {
                                                _edgeDetectionFlow.emit(
                                                    edges.toCoreOutputGrid(
                                                        16 * 2,
                                                        12 * 2
                                                    ).applyGain())
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

    fun dispose() {
        _frameCollector?.cancel()
    }
}