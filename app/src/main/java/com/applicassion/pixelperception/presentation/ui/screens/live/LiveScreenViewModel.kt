package com.applicassion.pixelperception.presentation.ui.screens.live

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.SurfaceRequest
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applicassion.pixelperception.core.PerceptionEngine
import com.applicassion.pixelperception.core.model.CoreDebugOutput
import com.applicassion.pixelperception.core.model.CoreOutputGrid
import com.applicassion.pixelperception.core.utils.FloatMapping
import com.applicassion.pixelperception.platform.CameraController
import com.applicassion.pixelperception.platform.OnCameraError
import com.applicassion.pixelperception.platform.OnCameraReady
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LiveScreenViewModel @Inject constructor() : ViewModel() {
    companion object {
        const val TAG = "LiveScreenViewModel"
    }


    enum class VisualizationType {
        All,
        PixelPerception,
        CameraPreview,
        GreyScale,
        EdgeDetection,
        MotionDetection,
        DepthDetection;

        fun getLabel(): String {
            return when(this) {
                All -> "All"
                PixelPerception -> "Pixel perception"
                CameraPreview -> "Camera preview"
                GreyScale -> "Greyscale"
                EdgeDetection -> "Edge detection"
                MotionDetection -> "Motion detection"
                DepthDetection -> "Depth detection"
            }
        }

        fun getFloatMapping(): FloatMapping? {
            return when(this) {
                All -> null
                PixelPerception -> null
                CameraPreview -> null
                GreyScale -> FloatMapping.NormalizePerFrame
                EdgeDetection -> FloatMapping.NormalizePerFrame
                MotionDetection -> FloatMapping.NormalizePerFrame
                DepthDetection -> FloatMapping.DepthColor
            }
        }

        fun toEngineOutputType(): PerceptionEngine.OutputType? {
            return when(this) {
                All -> null
                PixelPerception -> PerceptionEngine.OutputType.PixelPerceptionGrid
                CameraPreview -> PerceptionEngine.OutputType.CameraFeedMat
                GreyScale -> PerceptionEngine.OutputType.GreyScaleMat
                EdgeDetection -> PerceptionEngine.OutputType.EdgeDetectionMat
                MotionDetection -> PerceptionEngine.OutputType.MotionDetectionMat
                DepthDetection -> PerceptionEngine.OutputType.DepthDetectionMat
            }
        }
    }

    @Inject
    lateinit var cameraController : CameraController

    @Inject
    lateinit var perceptionEngine : PerceptionEngine

    val surfaceRequest = mutableStateOf<SurfaceRequest?>(null)


    private var isCameraStarted = false


    var pixelPerceptionOutput: MutableState<CoreOutputGrid?> = mutableStateOf(null)
    var greyScale: MutableState<CoreDebugOutput.GreyScale?> = mutableStateOf(null)
    var edgeDetection: MutableState<CoreDebugOutput.EdgeDetection?> = mutableStateOf(null)
    var motionDetection: MutableState<CoreDebugOutput.MotionDetection?> = mutableStateOf(null)
    var depthDetection: MutableState<CoreDebugOutput.DepthDetection?> = mutableStateOf(null)


    var isDebugOverlayEnabled = mutableStateOf(true)

    private val _isVisualizationEnabled = mapOf(
        VisualizationType.All to false,
        VisualizationType.PixelPerception to false,
        VisualizationType.CameraPreview to false,
        VisualizationType.GreyScale to false,
        VisualizationType.EdgeDetection to false,
        VisualizationType.MotionDetection to false,
        VisualizationType.DepthDetection to false,
    )

    private val _visualizationData = mapOf(
        VisualizationType.All to null,
        VisualizationType.PixelPerception to pixelPerceptionOutput,
        VisualizationType.CameraPreview to surfaceRequest,
        VisualizationType.GreyScale to greyScale,
        VisualizationType.EdgeDetection to edgeDetection,
        VisualizationType.MotionDetection to motionDetection,
        VisualizationType.DepthDetection to depthDetection,
    )


    //var currentVisualizationType = mutableStateOf(VisualizationType.CameraPreview)
    //var currentVisualizationType = mutableStateOf(VisualizationType.GreyScale)
    //var currentVisualizationType = mutableStateOf(VisualizationType.EdgeDetection)
    //var currentVisualizationType = mutableStateOf(VisualizationType.MotionDetection)
    var currentVisualizationType = mutableStateOf(VisualizationType.DepthDetection)
        private set

    private var perceptionListenerJob: Job? = null

    private fun startPerceptionListenerJob() {
        perceptionListenerJob = viewModelScope.launch {
            launch {
                perceptionEngine.pixelPerceptionOutputFlow.collect {
                    pixelPerceptionOutput.value = it
                }
            }
            launch {
                perceptionEngine.greyScaleDebugFlow.collect {
                    greyScale.value = it
                }
            }
            launch {
                perceptionEngine.edgeDetectionDebugFlow.collect {
                    edgeDetection.value = it
                }
            }
            launch {
                perceptionEngine.motionDetectionDebugFlow.collect {
                    motionDetection.value = it
                }
            }
            launch {
                perceptionEngine.depthDetectionDebugFlow.collect {
                    depthDetection.value = it
                }
            }
        }
    }

    fun isVisualizationEnabled(type: VisualizationType): Boolean {
        return _isVisualizationEnabled[type] == true
    }

    fun setVisualizationType(type: VisualizationType) {
        when(type) {
            VisualizationType.All -> perceptionEngine.enableAllOutputTypes()
            else -> perceptionEngine.enableSingleOutputType(
                type = type.toEngineOutputType()!!,
                autoDisableOtherTypes = true
            )
        }
        currentVisualizationType.value = type
    }


    fun getVisualizationData(type: VisualizationType): MutableState<out Any?>? {
        return _visualizationData[type]
    }

    fun startCamera(
        cameraSelector: CameraSelector,
        enablePreview: Boolean = true,
        targetSize: Size = Size(640, 480),
        lifecycleOwner: LifecycleOwner,
        onCameraReady: OnCameraReady? = null,
        onCameraError: OnCameraError? = null
    ) {

        when (isCameraStarted) {
            true -> return
            false -> isCameraStarted = true
        }

        perceptionEngine.start(
            coroutineScope = viewModelScope,
            cameraSelector = cameraSelector
        )
        cameraController.setFrameListener(perceptionEngine)
        setVisualizationType(currentVisualizationType.value)
        startPerceptionListenerJob()

        cameraController.startCamera(
            cameraSelector = cameraSelector,
            enablePreview = enablePreview,
            targetSize = targetSize,
            lifecycleOwner = lifecycleOwner,
            onCameraReady = {
                onCameraReady?.invoke()
            },
            onError = {
                surfaceRequest.value = null
                onCameraError?.invoke(it)
            },
            onSurfaceRequestReady = { request ->
                surfaceRequest.value = request
            },
        )

    }

    fun stopCamera() {
        perceptionEngine.dispose()
        cameraController.stopCamera()
        cameraController.setFrameListener(null)
        surfaceRequest.value = null
        isCameraStarted = false
        edgeDetection.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopCamera()
    }

}