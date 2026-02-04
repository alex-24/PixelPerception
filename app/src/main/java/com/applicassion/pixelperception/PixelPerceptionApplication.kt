package com.applicassion.pixelperception

import android.app.Application
import com.applicassion.pixelperception.platform.CameraController
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PixelPerceptionApplication: Application() {

    lateinit var cameraController : CameraController

    override fun onCreate() {
        super.onCreate()
        cameraController = CameraController(this)
    }
}