package com.applicassion.pixelperception.presentation.ui.screens.live

import PerceptionOverlay
import android.util.Size
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    allPermissionsGranted: State<Boolean>,
    onRequestPermissions: () -> Unit,
    viewModel: LiveScreenViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val granted = allPermissionsGranted.value

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Pixel Perception", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        )

        if (!granted) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Camera access is needed for the live view.",
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onRequestPermissions) {
                        Text("Allow camera")
                    }
                }
            }
        } else {
            LaunchedEffect(lifecycleOwner) {
                viewModel.startCamera(
                    context = context,
                    enablePreview = true,
                    targetSize = Size(640, 480),
                    cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA,
                    lifecycleOwner = lifecycleOwner,
                )
            }

            DisposableEffect(lifecycleOwner) {
                onDispose {
                    viewModel.stopCamera()
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (viewModel.isCameraPreviewEnabled.value && viewModel.surfaceRequest.value != null) {
                    CameraXViewfinder(
                        surfaceRequest = viewModel.surfaceRequest.value!!
                    )
                }

                if (viewModel.isPerceptionOverlayEnabled.value && viewModel.edgeDetection.value != null) {
                    PerceptionOverlay(viewModel.edgeDetection.value!!)
                }

                if (viewModel.isDebugOverlayEnabled.value) {
                    Text("Debug")
                }
            }
        }
    }
}