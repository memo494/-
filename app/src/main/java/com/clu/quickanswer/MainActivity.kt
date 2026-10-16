package com.clu.quickanswer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var apiKeyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var overlayButton: Button
    private lateinit var providerGroup: RadioGroup
    private lateinit var claudeKeyInput: EditText
    private lateinit var claudeModelInput: EditText

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, OverlayService::class.java).apply {
                    putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(OverlayService.EXTRA_DATA, data)
                }
                ContextCompat.startForegroundService(this, intent)
                Toast.makeText(this, "تم التشغيل", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "لم يتم منح صلاحية التقاط الشاشة", Toast.LENGTH_SHORT).show()
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        apiKeyInput = findViewById(R.id.apiKeyInput)
        modelInput = findViewById(R.id.modelInput)
        overlayButton = findViewById(R.id.overlayButton)

        providerGroup = findViewById(R.id.providerGroup)
        claudeKeyInput = findViewById(R.id.claudeKeyInput)
        claudeModelInput = findViewById(R.id.claudeModelInput)

        apiKeyInput.setText(Prefs.apiKey(this))
        modelInput.setText(Prefs.model(this))
        claudeKeyInput.setText(Prefs.claudeKey(this))
        claudeModelInput.setText(Prefs.claudeModel(this))
        providerGroup.check(
            if (Prefs.provider(this) == "claude") R.id.providerClaude else R.id.providerGemini
        )
        findViewById<Button>(R.id.opusButton).setOnClickListener {
            claudeModelInput.setText("claude-opus-5-5")
        }
        findViewById<Button>(R.id.haikuButton).setOnClickListener {
            claudeModelInput.setText("claude-haiku-5-5")
        }

        overlayButton.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        findViewById<Button>(R.id.startButton).setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "تم الإيقاف", Toast.LENGTH_SHORT).show()
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        updateOverlayButton()
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    private fun saveSettings() {
        Prefs.save(this, apiKeyInput.text.toString(), modelInput.text.toString())
        Prefs.saveClaude(
            this,
            if (providerGroup.checkedRadioButtonId == R.id.providerClaude) "claude" else "gemini",
            claudeKeyInput.text.toString(),
            claudeModelInput.text.toString()
        )
    }

    private fun updateOverlayButton() {
        if (Settings.canDrawOverlays(this)) {
            overlayButton.text = "✓ تم منح صلاحية الظهور فوق التطبيقات"
            overlayButton.isEnabled = false
        } else {
            overlayButton.text = "منح صلاحية الظهور فوق التطبيقات"
            overlayButton.isEnabled = true
        }
    }

    private fun onStartClicked() {
        saveSettings()
        if (Prefs.provider(this) == "claude") {
            if (Prefs.claudeKey(this).isBlank()) {
                Toast.makeText(this, "أدخل مفتاح Claude أولاً", Toast.LENGTH_SHORT).show()
                return
            }
        } else if (Prefs.apiKey(this).isBlank()) {
            Toast.makeText(this, "أدخل مفتاح API أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "امنح صلاحية الظهور فوق التطبيقات أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Android 14+ lets the user share a single app, which makes every other app black.
        // Ask for the whole screen only.
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }
}
