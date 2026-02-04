package com.applicassion.pixelperception.presentation.ui.screens.live

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.SurfaceRequest
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.applicassion.pixelperception.core.PerceptionEngine
import com.applicassion.pixelperception.core.model.CoreOutputGrid
import com.applicassion.pixelperception.platform.CameraController
import com.applicassion.pixelperception.platform.OnCameraError
import com.applicassion.pixelperception.platform.OnCameraReady
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LiveScreenViewModel @Inject constructor() : ViewModel() {
    companion object {
        const val TAG = "LiveScreenViewModel"
    }

    @Inject
    lateinit var cameraController : CameraController
    
    var isCameraPreviewEnabled = mutableStateOf(true)
    var isPerceptionOverlayEnabled = mutableStateOf(true)
    var isDebugOverlayEnabled = mutableStateOf(true)

    val surfaceRequest = mutableStateOf<SurfaceRequest?>(null)


    private var isCameraStarted = false

    private val perceptionEngine = PerceptionEngine(viewModelScope)

    var edgeDetection: MutableState<CoreOutputGrid?> = mutableStateOf(null)

    init {
        viewModelScope.launch {
            perceptionEngine.edgeDetectionFlow.collect {
                edgeDetection.value = it
            }
        }
    }

    fun startCamera(
        context: Context,
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

        cameraController.setFrameListener(perceptionEngine)

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