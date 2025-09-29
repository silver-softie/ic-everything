package com.bradmoffat.iceverything

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    // Launcher for Bluetooth and Location permissions
    private val requestBluetoothPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allPermissionsGranted = permissions.entries.all { it.value }
            if (allPermissionsGranted) {
                // If all permissions are granted, start the service.
                startFloatingBubbleService()
            } else {
                Toast.makeText(this, "Permissions denied. App cannot function.", Toast.LENGTH_LONG).show()
            }
        }

    // Launcher for 'Draw over other apps' permission
    private val requestOverlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // After returning from the settings, re-check the permission and proceed
            checkAndRequestBluetoothPermissions()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start the permission check flow
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            // If the permission is not granted, launch the overlay permission request.
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            requestOverlayPermissionLauncher.launch(intent)
            Toast.makeText(this, "Please grant 'Draw over other apps' permission.", Toast.LENGTH_LONG).show()
        } else {
            // Overlay permission is already granted, proceed to Bluetooth permissions
            checkAndRequestBluetoothPermissions()
        }
    }

    private fun checkAndRequestBluetoothPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // For Android 12 (API 31) and higher
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            // For older Android versions, location permission is needed for Bluetooth scanning
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            // If permissions are missing, launch the request.
            requestBluetoothPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // All permissions are granted, so start the service.
            startFloatingBubbleService()
        }
    }

    private fun startFloatingBubbleService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, BikeService::class.java))
        } else {
            startService(Intent(this, BikeService::class.java))
        }
        finish()
    }
}
