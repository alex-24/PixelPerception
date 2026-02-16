package com.applicassion.pixelperception.presentation.di

import android.content.Context
import com.applicassion.pixelperception.core.PerceptionEngine
import com.applicassion.pixelperception.platform.CameraController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
class AppModule {

    @Provides
    fun provideCameraController(@ApplicationContext context: Context): CameraController {
        return CameraController(context)
    }

    @Provides
    fun providePerceptionEngine(@ApplicationContext context: Context): PerceptionEngine {
        return PerceptionEngine(context = context)
    }
}