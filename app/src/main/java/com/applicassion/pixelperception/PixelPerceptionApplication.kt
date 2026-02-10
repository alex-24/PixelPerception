package com.applicassion.pixelperception

import android.app.Application
import com.applicassion.pixelperception.platform.CameraController
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader

@HiltAndroidApp
class PixelPerceptionApplication: Application() {


    override fun onCreate() {
        super.onCreate()

        check(OpenCVLoader.initDebug()) {
            "OpenCV init failed"
        }
    }
}