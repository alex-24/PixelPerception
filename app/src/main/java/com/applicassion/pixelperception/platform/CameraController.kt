package com.applicassion.pixelperception.platform

import android.content.Context
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.Preview.Builder
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias OnCameraReady = () -> Unit
typealias OnCameraError = (Throwable) -> Unit
typealias OnSurfaceRequestReady = (SurfaceRequest) -> Unit

interface OnFrameListener {
    fun onFrameSuccess(frame: ImageProxy)
    fun onFrameError(error: Throwable)
}

class CameraController(private val applicationContext: Context) {

     companion object Companion {
         const val TAG = "CameraController"
     }

     private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

     private var _cameraProvider: ProcessCameraProvider? = null
     val cameraProvider: ProcessCameraProvider?
        get() = _cameraProvider

     var surfaceRequest: SurfaceRequest? = null
        private set

     var previewUseCase: Preview? = null
         private set

     var imageAnalysisUseCase: ImageAnalysis? = null
         private set

     private var _onFrameListener: OnFrameListener? = null

     private val orientationEventListener = object : OrientationEventListener(applicationContext) {
         override fun onOrientationChanged(orientation : Int) {
             val rotation : Int = when (orientation) {
                 in 45..134 -> Surface.ROTATION_270
                 in 135..224 -> Surface.ROTATION_180
                 in 225..314 -> Surface.ROTATION_90
                 else -> Surface.ROTATION_0
             }

             previewUseCase?.targetRotation = rotation
             imageAnalysisUseCase?.targetRotation = rotation
         }
     }

     fun setFrameListener(listener: OnFrameListener?) {
         _onFrameListener = listener
     }

     fun startCamera(
        cameraSelector: CameraSelector,
        enablePreview: Boolean = true,
        enableAnalysis: Boolean = true,
        targetSize: Size = Size(640, 480),
        lifecycleOwner: LifecycleOwner,
        onCameraReady: OnCameraReady,
        onError: OnCameraError,
        onSurfaceRequestReady: OnSurfaceRequestReady? = null,
    ) {
        ProcessCameraProvider
            .getInstance(applicationContext)
            .let {
                it.addListener(
                    {
                        _cameraProvider = it.get()


                        when (enablePreview) {
                            true -> {
                                Log.d(TAG, "Preview use case enabled")
                                previewUseCase = Builder().build().apply {
                                    // CameraX requires setSurfaceProvider on the main thread
                                    setSurfaceProvider { request ->
                                        this@CameraController.surfaceRequest = request
                                        onSurfaceRequestReady?.invoke(request)
                                    }
                                }
                            }
                            false -> {
                                Log.d(TAG, "Preview use case disabled")
                                previewUseCase = null
                                surfaceRequest = null
                            }
                        }

                        when(_onFrameListener != null && enableAnalysis) {
                            true -> {
                                Log.d(TAG, "Image analysis use case enabled")
                                imageAnalysisUseCase = ImageAnalysis
                                    .Builder()
                                    //.setResolutionSelector(ResolutionSelector.Builder().build().apply { targetSize = targetSize })
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                                    .build().apply {
                                        setAnalyzer(analysisExecutor) { frame ->
                                            try {
                                                _onFrameListener?.onFrameSuccess(frame)
                                            } catch (e: Exception) {
                                                _onFrameListener?.onFrameError(e)
                                                frame.close()
                                            }
                                        }
                                    }
                            }
                            false -> {
                                Log.d(TAG, "Image analysis use case disabled")
                                imageAnalysisUseCase = null
                                if (_onFrameListener == null) {
                                    Log.w(TAG, "OnFrameListener was not set")
                                }
                            }
                        }

                        try {
                            val useCases = listOf(
                                previewUseCase,
                                imageAnalysisUseCase,
                            )
                                .filter { useCase -> useCase != null }
                                .toTypedArray()
                            _cameraProvider?.unbindAll()

                            if (useCases.isEmpty()) {
                                orientationEventListener.disable()
                                throw IllegalStateException("No use case was set")
                            } else {
                                orientationEventListener.enable()
                            }

                            _cameraProvider?.bindToLifecycle(
                                lifecycleOwner = lifecycleOwner,
                                cameraSelector = cameraSelector,
                                useCases = useCases
                            )
                            onCameraReady()
                        } catch (e: Exception) {
                            Log.e(TAG, "Unable to bind camera use cases", e)
                            onError(e)
                        }
                    },
                    ContextCompat.getMainExecutor(applicationContext)
                )
            }
    }

     fun stopCamera() {
         previewUseCase = null
         imageAnalysisUseCase?.clearAnalyzer()
         imageAnalysisUseCase = null
         analysisExecutor.shutdown()
         orientationEventListener.disable()
         _cameraProvider?.unbindAll()
         _cameraProvider = null
     }
}