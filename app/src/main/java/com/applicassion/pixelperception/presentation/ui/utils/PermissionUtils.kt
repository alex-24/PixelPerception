package com.applicassion.pixelperception.presentation.ui.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object PermissionUtils {
    val REQUIRED_PERMISSIONS: Array<String> = arrayOf(
        Manifest.permission.CAMERA
    )

    fun hasAllPermissions(context: Context): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
}
