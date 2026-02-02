package com.applicassion.pixelperception

import android.app.Application
import android.util.Log
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig

class PixelPerceptionApplication: Application(), CameraXConfig.Provider {

    lateinit var cameraXConfig: CameraXConfig
        private set

    override fun onCreate() {
        super.onCreate()
    }

    override fun getCameraXConfig(): CameraXConfig {
        cameraXConfig = CameraXConfig
            .Builder
            .fromConfig(Camera2Config.defaultConfig())
            .setMinimumLoggingLevel(Log.ERROR)
            .setAvailableCamerasLimiter(CameraSelector.DEFAULT_FRONT_CAMERA)
            .build()

        return cameraXConfig
    }
}