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
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.clu.quickanswer.ocr.TemplateState

class MainActivity : AppCompatActivity() {

    companion object {
        /** Set by OverlayService's "bubble stopped" notification: start the bubble again on open. */
        const val EXTRA_RESTART = "com.clu.quickanswer.RESTART"
    }

    private lateinit var apiKeyInput: EditText
    private lateinit var modelSpinner: Spinner
    private lateinit var modelIdText: TextView
    private lateinit var refreshModelsButton: Button
    private lateinit var overlayButton: Button
    private lateinit var providerGroup: RadioGroup
    private lateinit var claudeKeyInput: EditText
    private lateinit var claudeModelInput: EditText
    private lateinit var panelPositionGroup: RadioGroup
    private lateinit var smartReadingCheck: CheckBox
    private lateinit var highlightCheck: CheckBox
    private lateinit var vibrateCheck: CheckBox
    private lateinit var clearMemoryButton: Button
    private lateinit var templatesStatus: TextView
    private lateinit var clearTemplatesButton: Button
    private lateinit var volumeKeyCheck: CheckBox
    private lateinit var volumeKeyStatus: TextView

    // Model ids in the same order as the spinner entries.
    private var modelIds: List<String> = emptyList()

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

    // Question bank: the answer memory saved to / merged from a file the user picks (e.g. to share
    // with friends who play the same games, or to move to a new phone).
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            val app = applicationContext
            // Both files must have been loaded, or the export would miss what is on disk.
            AnswerStore.runAfterLoad {
                TemplateStore.runAfterLoad {
                    val ok = try {
                        val text = BankFile.encode(AnswerStore.exportJson(), TemplateStore.templates.encode())
                        app.contentResolver.openOutputStream(uri, "wt")?.use {
                            it.write(text.toByteArray(Charsets.UTF_8))
                        } != null
                    } catch (_: Exception) {
                        false
                    }
                    val n = AnswerStore.memory.size
                    val t = TemplateStore.templates.size
                    runOnUiThread {
                        Toast.makeText(app, if (ok) "تم تصدير $n سؤال و$t قالب" else "تعذّر حفظ الملف", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val app = applicationContext
            AnswerStore.runAfterLoad {
                TemplateStore.runAfterLoad {
                    var tooBig = false
                    var notABank = false
                    var merged: AnswerMemory.MergeResult? = null
                    var templatesAdded = 0
                    // Throwable: a huge or deeply nested file can run out of memory or stack, which is not an Exception.
                    try {
                        val bytes = app.contentResolver.openInputStream(uri)?.use {
                            AnswerMemory.readAtMost(it, AnswerMemory.MAX_IMPORT_BYTES)
                        }
                        val parts = bytes?.let { BankFile.decode(it.toString(Charsets.UTF_8)) }
                        when {
                            bytes == null -> tooBig = true
                            parts == null -> notABank = true
                            else -> {
                                merged = parts.memoryJson?.let { AnswerStore.memory.merge(it) }
                                val templates = parts.templatesJson?.let { TemplateStore.templates.merge(it) }
                                templatesAdded = templates?.added ?: 0
                                // A file whose parts are both unreadable is not a bank either.
                                if (merged == null && templates == null) notABank = true
                            }
                        }
                    } catch (_: Throwable) {
                        notABank = true
                    }
                    val n = AnswerStore.memory.size
                    val counts = templateCounts()
                    val m = merged
                    runOnUiThread {
                        val message = when {
                            tooBig -> "الملف كبير جدًا (الحد 5 ميغابايت)"
                            notABank -> "الملف ليس بنك أسئلة من Clu"
                            m != null && m.noRoom > 0 ->
                                "أُضيف ${m.added} سؤال و$templatesAdded قالب (المجموع $n سؤال)؛ الذاكرة ممتلئة فتُرك ${m.noRoom} سؤال"
                            else -> "أُضيف ${m?.added ?: 0} سؤال و$templatesAdded قالب (المجموع $n سؤال)"
                        }
                        Toast.makeText(app, message, Toast.LENGTH_LONG).show()
                        if (!isDestroyed) {
                            updateMemoryButton(n)
                            updateTemplatesStatus(counts)
                        }
                    }
                }
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        GeminiRuntime.init(this)

        apiKeyInput = findViewById(R.id.apiKeyInput)
        modelSpinner = findViewById(R.id.modelSpinner)
        modelIdText = findViewById(R.id.modelIdText)
        refreshModelsButton = findViewById(R.id.refreshModelsButton)
        overlayButton = findViewById(R.id.overlayButton)

        providerGroup = findViewById(R.id.providerGroup)
        claudeKeyInput = findViewById(R.id.claudeKeyInput)
        claudeModelInput = findViewById(R.id.claudeModelInput)
        panelPositionGroup = findViewById(R.id.panelPositionGroup)

        apiKeyInput.setText(Prefs.apiKey(this))
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                showModelId(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        rebuildModelSpinner(Prefs.model(this))
        refreshModelsButton.setOnClickListener { refreshModelList() }
        claudeKeyInput.setText(Prefs.claudeKey(this))
        claudeModelInput.setText(Prefs.claudeModel(this))
        providerGroup.check(
            if (Prefs.provider(this) == "claude") R.id.providerClaude else R.id.providerGemini
        )
        panelPositionGroup.check(
            if (Prefs.panelMode(this) == PanelPlacement.Mode.NEXT_TO_BUBBLE) R.id.panelPosBubble else R.id.panelPosTop
        )
        smartReadingCheck = findViewById(R.id.smartReadingCheck)
        highlightCheck = findViewById(R.id.highlightCheck)
        vibrateCheck = findViewById(R.id.vibrateCheck)
        clearMemoryButton = findViewById(R.id.clearMemoryButton)
        smartReadingCheck.isChecked = Prefs.smartReading(this)
        highlightCheck.isChecked = Prefs.highlightAnswer(this)
        vibrateCheck.isChecked = Prefs.vibrateOnAnswer(this)
        AnswerStore.init(this)
        TemplateStore.init(this)
        templatesStatus = findViewById(R.id.templatesStatus)
        clearTemplatesButton = findViewById(R.id.clearTemplatesButton)
        volumeKeyCheck = findViewById(R.id.volumeKeyCheck)
        volumeKeyStatus = findViewById(R.id.volumeKeyStatus)
        volumeKeyCheck.isChecked = Prefs.volumeKey(this)
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "افتح الإعدادات ← إمكانية الوصول", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.appInfoButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.exportMemoryButton).setOnClickListener {
            exportLauncher.launch("clu-question-bank.json")
        }
        findViewById<Button>(R.id.importMemoryButton).setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        clearMemoryButton.setOnClickListener {
            AnswerStore.runAfterLoad {
                AnswerStore.memory.clear()
                runOnUiThread {
                    if (!isDestroyed) {
                        updateMemoryButton(0)
                        Toast.makeText(this, "تم مسح الذاكرة", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        clearTemplatesButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("مسح كل قوالب الألعاب؟ رح يرجع يتعلّمها من جديد.")
                .setPositiveButton("مسح") { _, _ ->
                    TemplateStore.runAfterLoad {
                        TemplateStore.resetToBuiltins(this)
                        val counts = templateCounts()
                        runOnUiThread { if (!isDestroyed) updateTemplatesStatus(counts) }
                    }
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
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
        if (savedInstanceState == null) handleRestart(intent)
        maybeShowWelcome()
    }

    /**
     * Three steps for the first launch. Someone who already has a key (an update, not a first
     * launch) does not need them, so for them it is only marked as seen. The flag is saved when the
     * dialog is answered or cancelled, not when it is shown: a rotation that dismisses it would
     * otherwise lose it unread.
     */
    private fun maybeShowWelcome() {
        if (Prefs.welcomeShown(this)) return
        if (Prefs.apiKey(this).isNotEmpty() || Prefs.claudeKey(this).isNotEmpty()) {
            Prefs.saveWelcomeShown(this)
            return
        }
        showRtl(
            AlertDialog.Builder(this)
                .setTitle("أهلًا بك في Clu")
                .setMessage(
                    "1) الصق مفتاح Gemini المجاني (الرابط تحت).\n" +
                        "2) اضغط «منح صلاحية الظهور فوق التطبيقات».\n" +
                        "3) اضغط «تشغيل» وبعدين المس الفقاعة أو زر خفض الصوت لما يطلع السؤال."
                )
                .setPositiveButton("يلا نبلّش") { _, _ -> Prefs.saveWelcomeShown(this) }
                .setOnCancelListener { Prefs.saveWelcomeShown(this) }
        )
    }

    /** Shows the dialog right-to-left whatever the phone's language: the screen itself is. */
    private fun showRtl(builder: AlertDialog.Builder) {
        val dialog = builder.show()
        dialog.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRestart(intent)
    }

    /** Opened from the "bubble stopped" notification: ask for the screen capture again right away. */
    private fun handleRestart(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_RESTART, false) != true) return
        intent.removeExtra(EXTRA_RESTART)
        onStartClicked()
    }

    // A refresh may finish while this screen is showing, or after it was recreated by a rotation.
    private val refreshListener = Runnable {
        refreshModelsButton.isEnabled = !GeminiRuntime.refreshingModels
        rebuildModelSpinner(selectedModelId())
    }

    override fun onResume() {
        super.onResume()
        updateOverlayButton()
        GeminiRuntime.addRefreshListener(refreshListener)
        // A model may have run out of quota (or recovered), or a refresh may have finished, since the
        // list was last drawn.
        refreshModelsButton.isEnabled = !GeminiRuntime.refreshingModels
        rebuildModelSpinner(selectedModelId())
        updateVolumeKeyStatus()
        // The count is known once the file has been loaded (in the background).
        AnswerStore.runAfterLoad {
            val n = AnswerStore.memory.size
            runOnUiThread { if (!isDestroyed) updateMemoryButton(n) }
        }
        TemplateStore.runAfterLoad {
            val counts = templateCounts()
            runOnUiThread { if (!isDestroyed) updateTemplatesStatus(counts) }
        }
    }

    private fun updateVolumeKeyStatus() {
        volumeKeyStatus.text = if (VolumeKeyService.isEnabled(this)) {
            "✓ زر الصوت مفعّل: اضغط خفض الصوت أثناء عمل الفقاعة"
        } else {
            "زر الصوت غير مفعّل بعد في إمكانية الوصول"
        }
    }

    private fun updateMemoryButton(count: Int) {
        clearMemoryButton.text = "مسح الذاكرة ($count سؤال)"
        clearMemoryButton.isEnabled = count > 0
    }

    /** Trusted and candidate template counts; call on [TemplateStore.runAfterLoad]'s thread. */
    private fun templateCounts(): Pair<Int, Int> {
        val all = TemplateStore.templates.all()
        val trusted = all.count { it.state == TemplateState.TRUSTED }
        return trusted to all.size - trusted
    }

    private fun updateTemplatesStatus(counts: Pair<Int, Int>) {
        val (trusted, candidate) = counts
        templatesStatus.text = "قوالب الألعاب: $trusted معتمد، $candidate قيد التعلّم"
        clearTemplatesButton.isEnabled = trusted + candidate > 0
    }

    override fun onPause() {
        super.onPause()
        GeminiRuntime.removeRefreshListener(refreshListener)
        saveSettings()
    }

    private fun selectedModelId(): String =
        modelIds.getOrNull(modelSpinner.selectedItemPosition) ?: Prefs.model(this)

    /**
     * Fills the spinner with "label — note" lines: the catalog (or, after a refresh, the models the
     * key can use) with a marker on models the quota tracker has blocked. A saved model that is not
     * in the list is added so it is never silently replaced.
     */
    private fun rebuildModelSpinner(selectedId: String) {
        val entries = GeminiModels.mergeWithCatalog(Prefs.modelList(this)).toMutableList()
        if (entries.none { it.id == selectedId }) entries.add(0, GeminiModels.entryFor(selectedId))
        modelIds = entries.map { it.id }
        val texts = entries.map { it.pickerText(GeminiRuntime.quota.isBlocked(it.id)) }
        // Own item layouts: the stock ones are single-line and cut the end of the text, which is
        // exactly where the "blocked" marker is.
        val adapter = ArrayAdapter(this, R.layout.spinner_item, texts)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        modelSpinner.adapter = adapter
        val index = modelIds.indexOf(selectedId).coerceAtLeast(0)
        modelSpinner.setSelection(index)
        showModelId(index)
    }

    /** The raw id under the spinner, plus the blocked state so it is visible however long the entry is. */
    private fun showModelId(position: Int) {
        val id = modelIds.getOrNull(position) ?: ""
        val blocked = id.isNotEmpty() && GeminiRuntime.quota.isBlocked(id)
        modelIdText.text = if (blocked) "$id —${ModelEntry.BLOCKED_SUFFIX}" else id
    }

    /** Asks Google which models the key can use. The network call runs on its own thread. */
    private fun refreshModelList() {
        val key = Prefs.cleanKey(apiKeyInput.text.toString()).ifEmpty { Prefs.apiKey(this) }
        if (key.isEmpty()) {
            Toast.makeText(this, "أدخل مفتاح API أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        if (GeminiRuntime.refreshModelList(this, key)) refreshModelsButton.isEnabled = false
    }

    private fun saveSettings() {
        val newKey = Prefs.cleanKey(apiKeyInput.text.toString())
        // Quota blocks belong to one key; a new key starts with a clean slate.
        if (newKey != Prefs.apiKey(this)) GeminiRuntime.quota.clearAll()
        Prefs.save(this, newKey, selectedModelId())
        Prefs.saveClaude(
            this,
            if (providerGroup.checkedRadioButtonId == R.id.providerClaude) "claude" else "gemini",
            claudeKeyInput.text.toString(),
            claudeModelInput.text.toString()
        )
        Prefs.savePanelMode(
            this,
            if (panelPositionGroup.checkedRadioButtonId == R.id.panelPosBubble) PanelPlacement.Mode.NEXT_TO_BUBBLE
            else PanelPlacement.Mode.TOP
        )
        Prefs.saveReading(this, smartReadingCheck.isChecked, highlightCheck.isChecked)
        Prefs.saveVibrateOnAnswer(this, vibrateCheck.isChecked)
        Prefs.saveVolumeKey(this, volumeKeyCheck.isChecked)
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
