package com.applicassion.pixelperception.presentation.ui.screens.live.overlays

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import com.applicassion.pixelperception.core.utils.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Mat

@Composable
fun DebugVisualization(
    data: Mat,
    modifier: Modifier = Modifier
) {

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(data) {
        if (data.empty()) {
            bitmap = null
        } else {
            val converted = withContext(Dispatchers.IO) { data.toBitmap() }
            bitmap = converted
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("ERROR !!!!!!!!!!!!!!!!!!!!!!!") // todo error widget
        }
    }
}