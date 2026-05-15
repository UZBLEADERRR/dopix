package com.dopix.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dopix.app.databinding.ActivityPermissionBinding
import com.dopix.app.services.DopixAccessibilityService

/**
 * Dedicated activity for walking users through required permissions.
 */
class PermissionActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_MIC = 101
        private const val REQUEST_OVERLAY = 102
    }

    private lateinit var binding: ActivityPermissionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        updateButtonStates()
    }

    private fun setupButtons() {
        binding.btnGrantOverlay.setOnClickListener {
            requestOverlayPermission()
        }

        binding.btnGrantMic.setOnClickListener {
            requestMicPermission()
        }

        binding.btnGrantAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, REQUEST_OVERLAY)
    }

    private fun requestMicPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_MIC
        )
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun updateButtonStates() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val accessibilityEnabled = DopixAccessibilityService.instance != null

        binding.btnGrantOverlay.isEnabled = !overlayGranted
        binding.btnGrantMic.isEnabled = !micGranted
        binding.btnGrantAccessibility.isEnabled = !accessibilityEnabled

        if (overlayGranted && micGranted && accessibilityEnabled) {
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateButtonStates()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        updateButtonStates()
    }
}
