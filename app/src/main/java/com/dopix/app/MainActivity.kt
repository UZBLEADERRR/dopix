package com.dopix.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dopix.app.databinding.ActivityMainBinding
import com.dopix.app.services.DopixAccessibilityService
import com.dopix.app.services.DopixOverlayService
import com.dopix.app.services.GeminiLiveService
import com.dopix.app.utils.PreferencesManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_MIC_PERMISSION = 100
        private const val REQUEST_OVERLAY_PERMISSION = 101
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager.getInstance(this)

        setupUI()
        observeState()
        loadSavedKey()
        checkAndUpdatePermissions()
    }

    override fun onResume() {
        super.onResume()
        checkAndUpdatePermissions()
    }

    // -------------------------------------------------------------------------
    // UI setup
    // -------------------------------------------------------------------------

    private fun setupUI() {
        // Save API key
        binding.btnSaveKey.setOnClickListener {
            val key = binding.etApiKey.text?.toString()?.trim() ?: ""
            if (key.isBlank()) {
                Toast.makeText(this, "API key cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.apiKey = key
            Toast.makeText(this, getString(R.string.api_key_saved), Toast.LENGTH_SHORT).show()
            binding.etApiKey.clearFocus()
        }

        // Start / Stop toggle
        binding.btnToggle.setOnClickListener {
            if (DopixState.isRunning()) {
                stopDopix()
            } else {
                startDopix()
            }
        }

        // Permission buttons
        binding.btnOverlayPerm.setOnClickListener { requestOverlayPermission() }
        binding.btnMicPerm.setOnClickListener { requestMicPermission() }
        binding.btnAccessibilityPerm.setOnClickListener { openAccessibilitySettings() }
    }

    private fun loadSavedKey() {
        val key = prefs.apiKey
        if (key.isNotBlank()) {
            binding.etApiKey.setText(key)
        }
    }

    // -------------------------------------------------------------------------
    // State observation
    // -------------------------------------------------------------------------

    private fun observeState() {
        lifecycleScope.launch {
            DopixState.mode.collectLatest { mode ->
                updateStatusUI(mode)
            }
        }

        lifecycleScope.launch {
            DopixState.lastTranscript.collectLatest { text ->
                if (text.isNotBlank()) {
                    binding.tvLastTranscript.text = text
                }
            }
        }

        lifecycleScope.launch {
            DopixState.lastAiResponse.collectLatest { text ->
                // Could display AI response in a dedicated view here
            }
        }

        // Set up callbacks for real-time updates from services
        DopixState.onTranscriptReceived = { text ->
            runOnUiThread {
                binding.tvLastTranscript.text = text
            }
        }
    }

    private fun updateStatusUI(mode: DopixState.Mode) {
        when (mode) {
            DopixState.Mode.ACTIVE -> {
                binding.tvStatus.text = getString(R.string.status_active)
                binding.statusDot.setBackgroundResource(R.drawable.bg_overlay_active)
                binding.btnToggle.text = getString(R.string.btn_stop)
                binding.btnToggle.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.status_error)
            }
            DopixState.Mode.SLEEPING -> {
                binding.tvStatus.text = getString(R.string.status_sleeping)
                binding.statusDot.setBackgroundResource(R.drawable.bg_overlay_idle)
                binding.btnToggle.text = getString(R.string.btn_stop)
                binding.btnToggle.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.status_sleeping)
            }
            DopixState.Mode.CONNECTING -> {
                binding.tvStatus.text = getString(R.string.status_connecting)
                binding.statusDot.setBackgroundResource(R.drawable.bg_overlay_idle)
                binding.btnToggle.text = getString(R.string.btn_stop)
                binding.btnToggle.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.status_connecting)
            }
            DopixState.Mode.STOPPED -> {
                binding.tvStatus.text = getString(R.string.status_stopped)
                binding.statusDot.setBackgroundResource(R.drawable.bg_overlay_idle)
                binding.btnToggle.text = getString(R.string.btn_start)
                binding.btnToggle.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.colorPrimary)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Start / Stop
    // -------------------------------------------------------------------------

    private fun startDopix() {
        if (!prefs.hasApiKey()) {
            Toast.makeText(this, getString(R.string.missing_api_key), Toast.LENGTH_LONG).show()
            return
        }

        if (!checkAllPermissions()) {
            Toast.makeText(this, "Please grant all required permissions first", Toast.LENGTH_LONG).show()
            return
        }

        // Start overlay service
        DopixOverlayService.startService(this)

        // Start Gemini service
        GeminiLiveService.startService(this)

        Toast.makeText(this, "Dopix started!", Toast.LENGTH_SHORT).show()
    }

    private fun stopDopix() {
        GeminiLiveService.stopService(this)
        DopixOverlayService.stopService(this)
        DopixState.setMode(DopixState.Mode.STOPPED)
        Toast.makeText(this, "Dopix stopped", Toast.LENGTH_SHORT).show()
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private fun checkAllPermissions(): Boolean {
        val overlayOk = Settings.canDrawOverlays(this)
        val micOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val accessibilityOk = DopixAccessibilityService.instance != null
        return overlayOk && micOk && accessibilityOk
    }

    private fun checkAndUpdatePermissions() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val accessibilityEnabled = DopixAccessibilityService.instance != null

        // Update icons
        binding.icOverlayPerm.setImageResource(
            if (overlayGranted) android.R.drawable.presence_online
            else android.R.drawable.presence_offline
        )
        binding.icMicPerm.setImageResource(
            if (micGranted) android.R.drawable.presence_online
            else android.R.drawable.presence_offline
        )
        binding.icAccessibilityPerm.setImageResource(
            if (accessibilityEnabled) android.R.drawable.presence_online
            else android.R.drawable.presence_offline
        )

        // Update button states
        binding.btnOverlayPerm.visibility = if (overlayGranted) View.GONE else View.VISIBLE
        binding.btnMicPerm.visibility = if (micGranted) View.GONE else View.VISIBLE
        binding.btnAccessibilityPerm.visibility = if (accessibilityEnabled) View.GONE else View.VISIBLE

        if (overlayGranted && micGranted && accessibilityEnabled) {
            binding.cardPermissions.visibility = View.GONE
        } else {
            binding.cardPermissions.visibility = View.VISIBLE
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
    }

    private fun requestMicPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_MIC_PERMISSION
        )
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(
            this,
            "Find \"Dopix Assistant\" and enable it",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        checkAndUpdatePermissions()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        checkAndUpdatePermissions()
    }

    override fun onDestroy() {
        // Clear callbacks to avoid leaks
        DopixState.onTranscriptReceived = null
        super.onDestroy()
    }
}
