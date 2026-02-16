package com.applicassion.pixelperception.presentation.ui.screens.live

import PerceptionOverlay
import android.util.Log
import android.util.Size
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.SurfaceRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.applicassion.pixelperception.core.model.CoreDebugOutput
import com.applicassion.pixelperception.core.model.CoreOutputGrid
import com.applicassion.pixelperception.presentation.ui.screens.live.overlays.DebugVisualization

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
                    //enablePreview = viewModel.currentVisualizationType.value == LiveScreenViewModel.VisualizationType.CameraPreview,
                    enablePreview = true,
                    targetSize = Size(640, 480),
                    cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA,
                    //cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
                    lifecycleOwner = lifecycleOwner,
                )
            }

            DisposableEffect(lifecycleOwner) {
                onDispose {
                    viewModel.stopCamera()
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                viewModel
                    .currentVisualizationType
                    .value
                    .let { visualizationType ->

                        when(viewModel.currentVisualizationType.value) {
                            LiveScreenViewModel.VisualizationType.All -> {
                                Column(
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    listOf(
                                        listOf(LiveScreenViewModel.VisualizationType.CameraPreview, LiveScreenViewModel.VisualizationType.GreyScale),
                                        listOf(LiveScreenViewModel.VisualizationType.EdgeDetection, LiveScreenViewModel.VisualizationType.RawMotionDetection),
                                        listOf(LiveScreenViewModel.VisualizationType.DepthDetection, LiveScreenViewModel.VisualizationType.PixelPerception),
                                    ).forEach { visualization ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1.0f)
                                        ) {
                                            visualization.forEach { type ->
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxHeight()
                                                        .weight(0.5f)
                                                ) {

                                                    when (type) {
                                                        LiveScreenViewModel.VisualizationType.CameraPreview -> {
                                                            if (viewModel.surfaceRequest.value != null) {
                                                                CameraXViewfinder(
                                                                    surfaceRequest = viewModel.surfaceRequest.value as SurfaceRequest,
                                                                    modifier = Modifier.fillMaxSize()
                                                                )
                                                            } else {
                                                                Surface(
                                                                    color = Color.Black,
                                                                    modifier = Modifier.fillMaxSize()
                                                                ) {}
                                                            }
                                                        }

                                                        else -> {
                                                            when (val data = viewModel.getVisualizationData(type)?.value) {
                                                                null -> {
                                                                    Surface(
                                                                        color = Color.Black,
                                                                        modifier = Modifier.fillMaxSize()
                                                                    ) {}
                                                                }
                                                                is CoreDebugOutput -> {
                                                                    DebugVisualization(
                                                                        type = type,
                                                                        data = data.getData(),// todo delegate
                                                                        modifier = Modifier.fillMaxSize()
                                                                    )
                                                                }
                                                                is CoreOutputGrid -> {
                                                                    PerceptionOverlay(
                                                                        grid = data,
                                                                        modifier = Modifier.fillMaxSize()
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }

                                                    Row(
                                                        horizontalArrangement = Arrangement.Center,
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Surface(
                                                            color = Color.Black.copy(alpha = 0.5f),
                                                            shape = RoundedCornerShape(4.dp),
                                                            modifier = Modifier.padding(top = 2.dp)
                                                        ) {
                                                            Text(
                                                                text = type.getLabel(),
                                                                fontWeight = FontWeight.Bold,
                                                                color = Color.White,
                                                                textAlign = TextAlign.Center,
                                                                modifier = Modifier
                                                                    .padding(
                                                                        horizontal = 4.dp,
                                                                        vertical = 2.dp
                                                                    )
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            LiveScreenViewModel.VisualizationType.CameraPreview -> {
                                if (viewModel.getVisualizationData(visualizationType)?.value  != null) {
                                    CameraXViewfinder(
                                        surfaceRequest = viewModel.getVisualizationData(visualizationType)!!.value!! as SurfaceRequest
                                    )
                                }
                            }
                            else -> {
                                if (viewModel.surfaceRequest.value != null) {
                                    Log.e("SANITY", "yes")
                                    CameraXViewfinder(
                                        surfaceRequest = viewModel.surfaceRequest.value as SurfaceRequest
                                    )
                                } else {
                                    Log.e("SANITY", "no")
                                }

                                when (viewModel.getVisualizationData(visualizationType)?.value) {
                                    null -> {}
                                    is CoreOutputGrid -> {
                                        PerceptionOverlay(
                                            grid = viewModel.getVisualizationData(visualizationType)!!.value!! as CoreOutputGrid
                                        )
                                    }
                                    else -> {
                                        DebugVisualization(
                                            type = visualizationType,
                                            data = (viewModel.getVisualizationData(visualizationType)!!.value!! as CoreDebugOutput?)!!.getData() // todo delegate
                                        )
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }
}