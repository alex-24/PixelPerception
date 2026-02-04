package com.applicassion.pixelperception.presentation

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.applicassion.pixelperception.presentation.ui.screens.live.LiveScreen
import com.applicassion.pixelperception.presentation.ui.theme.PixelPerceptionTheme
import com.applicassion.pixelperception.presentation.ui.utils.PermissionUtils
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val allPermissionsGranted = mutableStateOf(false)

    private val permissionRequester = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        allPermissionsGranted.value = PermissionUtils.REQUIRED_PERMISSIONS.all { result[it] == true }
        if (!allPermissionsGranted.value) {
            Toast.makeText(this, "Camera permission is needed for the live view.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Check permissions after Activity is properly initialized
        allPermissionsGranted.value = PermissionUtils.hasAllPermissions(this)
        setContent {
            PixelPerceptionTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
                    LiveScreen(
                        allPermissionsGranted = allPermissionsGranted,
                        onRequestPermissions = { permissionRequester.launch(PermissionUtils.REQUIRED_PERMISSIONS) }
                    )
                }
            }
        }
    }
}