package com.smartkeyboard.ai

import android.content.ClipboardManager
import android.inputmethodservice.InputMethodService
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Smart Keyboard AI - a real Input Method Service.
 *
 * Phase 1: QWERTY letters page, numbers page, symbols page, shift,
 * backspace, space, enter, and a placeholder language key.
 *
 * Phase 2 (additive only - phase 1 typing behaviour is untouched):
 * a clipboard history manager (SQLite-backed, up to 1000 items),
 * pin/favorite, search, categories, a 5-item recent bar, and
 * backup/restore.
 *
 * Phase 3 (additive only - phase 1 and phase 2 behaviour is untouched):
 * Smart Clipboard Formatter - user-defined rules that transform clipboard
 * text before it is pasted, with live preview, auto-format, and a
 * phone-number-aware mode.
 *
 * Phase 4 (additive only - phases 1-3 behaviour is untouched):
 * Smart Auto Mode - per-field-type detection (phone/OTP/email/URL/
 * password/normal) that auto-switches the keyboard page and shows
 * contextual quick-action chips; a customizable Smart Toolbar (Clipboard
 * and Paste are permanent, the rest can be shown/hidden and reordered
 * from a settings page); and Undo/Redo for paste actions. Voice, Emoji,
 * GIF and Translate are shown as toolbar items but are placeholders
 * (they show a "coming soon" message) - building real versions of those
 * needs things this project deliberately doesn't take on: microphone
 * permission + speech recognition, a full emoji picker, and a GIF/
 * translation API with a key, none of which fit a lightweight offline
 * keyboard and weren't part of the original brief. Still no AI, no
 * suggestions, no autocorrect.
 */
class KeyboardService : InputMethodService() {

    private enum class Mode { LETTERS, NUMBERS, SYMBOLS, CLIPBOARD, FORMATTER, TOOLBAR_SETTINGS }

    private var currentMode = Mode.LETTERS
    private var isShifted = false
    private var isPhoneField = false
    private var currentFieldType: FieldType = FieldType.NORMAL

    private lateinit var lettersView: View
    private lateinit var numbersView: View
    private lateinit var symbolsView: View
    private lateinit var clipboardManagerView: View
    private lateinit var formatterSettingsView: View
    private lateinit var toolbarSettingsView: View
    private var shiftButton: Button? = null

    // ---- Clipboard feature state ----
    private lateinit var dbHelper: ClipboardDbHelper
    private lateinit var systemClipboard: ClipboardManager
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    private lateinit var recentBarScroll: View
    private lateinit var recentBarContainer: LinearLayout
    private lateinit var clipBrowsePanel: View
    private lateinit var clipSettingsPanel: View
    private lateinit var clipRecyclerView: RecyclerView
    private lateinit var clipSearchInput: EditText
    private lateinit var clipMaxSizeInput: EditText
    private lateinit var clipAdapter: ClipboardAdapter
    private var currentClipCategory: String = ClipboardCategorizer.ALL

    // ---- Formatter feature state ----
    private lateinit var formatterDb: FormatterDbHelper
    private lateinit var formatPreviewBar: View
    private lateinit var formatPreviewOriginalText: TextView
    private lateinit var formatPreviewFormattedText: TextView
    private var pendingOriginalText: String? = null
    private var pendingFormattedText: String? = null

    private lateinit var formatterListPanel: View
    private lateinit var formatterRuleEditor: View
    private lateinit var formatterRulesRecycler: RecyclerView
    private lateinit var formatterAdapter: FormatterRuleAdapter
    private lateinit var toggleFormatterMasterBtn: Button
    private lateinit var toggleAutoFormatBtn: Button
    private lateinit var togglePhoneModeBtn: Button
    private lateinit var ruleNameInput: EditText
    private lateinit var ruleParam1Input: EditText
    private lateinit var ruleParam2Input: EditText
    private lateinit var rulePriorityInput: EditText
    private lateinit var ruleTestInput: EditText
    private lateinit var rulePreviewOutput: TextView
    private lateinit var ruleSelectedTypeLabel: TextView
    private lateinit var rulePhoneOnlyToggleBtn: Button
    private var editingRuleId: Long? = null
    private var editingRuleType: String? = null
    private var editingRulePhoneOnly = false

    // ---- Smart Auto Mode (Phase 4) state ----
    private lateinit var toolbarPrefs: ToolbarPrefs
    private lateinit var toolbarExtraContainer: LinearLayout
    private lateinit var quickActionsBarScroll: View
    private lateinit var quickActionsBarContainer: LinearLayout
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    override fun onCreate() {
        super.onCreate()
        dbHelper = ClipboardDbHelper(this)
        formatterDb = FormatterDbHelper(this)
        toolbarPrefs = ToolbarPrefs(this)
        try {
            systemClipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val listener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboardChange() }
            clipListener = listener
            systemClipboard.addPrimaryClipChangedListener(listener)
        } catch (e: Exception) {
            // If the system clipboard can't be reached for any reason, the
            // rest of the keyboard must keep working normally.
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (::systemClipboard.isInitialized) {
                clipListener?.let { systemClipboard.removePrimaryClipChangedListener(it) }
            }
            if (::dbHelper.isInitialized) {
                dbHelper.close()
            }
            if (::formatterDb.isInitialized) {
                formatterDb.close()
            }
        } catch (e: Exception) {
            // ignore - shutting down anyway
        }
    }

    override fun onCreateInputView(): View {
        // layoutInflater.inflate never returns null for a valid resource id;
        // if keyboard_container.xml failed to inflate this would throw during
        // development rather than silently returning null at runtime.
        val root = layoutInflater.inflate(R.layout.keyboard_container, null)

        lettersView = root.findViewById(R.id.letters_keyboard)
        numbersView = root.findViewById(R.id.numbers_keyboard)
        symbolsView = root.findViewById(R.id.symbols_keyboard)
        clipboardManagerView = root.findViewById(R.id.clipboard_manager)
        formatterSettingsView = root.findViewById(R.id.formatter_settings)
        toolbarSettingsView = root.findViewById(R.id.toolbar_settings)

        shiftButton = lettersView.findViewWithTag("SHIFT")

        attachListeners(root)
        setupClipboardViews(root)
        setupFormatterViews(root)
        setupToolbar(root)
        showLetters()

        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        isShifted = false
        updateShiftVisual()
        recentBarScroll.visibility = View.GONE
        hideFormatPreview()
        undoStack.clear()
        redoStack.clear()

        currentFieldType = FieldDetector.detect(info)
        isPhoneField = (currentFieldType == FieldType.PHONE)

        when (currentFieldType) {
            FieldType.PHONE, FieldType.OTP -> showNumbers()
            else -> showLetters()
        }

        if (currentFieldType == FieldType.PASSWORD) {
            // Secure typing: no clipboard content preview in password fields.
            quickActionsBarContainer.removeAllViews()
            quickActionsBarScroll.visibility = View.GONE
        } else {
            rebuildQuickActionsBar()
        }
    }

    // ---------- Generic listener wiring ----------

    private fun attachListeners(view: View) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                attachListeners(view.getChildAt(i))
            }
        } else if (view is Button) {
            addPressAnimation(view)
            view.setOnClickListener { onKeyTapped(it as Button) }
        }
    }

    private fun addPressAnimation(view: Button) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate()
                        .scaleX(0.88f)
                        .scaleY(0.88f)
                        .setDuration(60)
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(120)
                        .setInterpolator(OvershootInterpolator())
                        .start()
                }
            }
            false // never consume: let the normal click listener still fire
        }
    }

    // ---------- Key handling ----------

    private fun onKeyTapped(view: Button) {
        val tag = view.tag as? String ?: return
        if (handleToolbarSettingsTag(tag)) return
        val ic = currentInputConnection

        when (tag) {
            "SHIFT" -> {
                isShifted = !isShifted
                updateShiftVisual()
            }

            "BACKSPACE" -> {
                ic?.let {
                    val selected = it.getSelectedText(0)
                    if (selected.isNullOrEmpty()) {
                        it.deleteSurroundingText(1, 0)
                    } else {
                        it.commitText("", 1)
                    }
                }
            }

            "SPACE" -> ic?.commitText(" ", 1)

            "ENTER" -> {
                ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }

            "LANG" -> {
                // Placeholder only - no real language switching in Phase 1.
                Toast.makeText(this, "Language switching coming soon", Toast.LENGTH_SHORT).show()
            }

            "TO_NUMBERS" -> showNumbers()
            "TO_SYMBOLS" -> showSymbols()
            "TO_LETTERS" -> showLetters()

            "CLIPBOARD_TOGGLE" -> toggleRecentBar()
            "CLOSE_CLIPBOARD_MANAGER" -> closeClipboardManager()
            "TOGGLE_CLIP_SETTINGS" -> toggleClipSettingsPanel()
            "SAVE_CLIP_SETTINGS" -> saveClipSettings()
            "AUTO_DELETE_0" -> setAutoDeleteHours(0)
            "AUTO_DELETE_24" -> setAutoDeleteHours(24)
            "AUTO_DELETE_168" -> setAutoDeleteHours(168)
            "AUTO_DELETE_720" -> setAutoDeleteHours(720)
            "CLEAR_ALL_HISTORY" -> confirmClearAll()
            "BACKUP_CLIPBOARD" -> performBackup()
            "RESTORE_CLIPBOARD" -> performRestore()

            "OPEN_FORMATTER" -> openFormatter()
            "CLOSE_FORMATTER" -> closeFormatter()
            "TOGGLE_FORMATTER_MASTER" -> toggleFormatterMaster()
            "TOGGLE_AUTO_FORMAT" -> toggleAutoFormat()
            "TOGGLE_PHONE_MODE" -> togglePhoneMode()
            "ADD_FORMATTER_RULE" -> openRuleEditor(null)
            "CANCEL_RULE_EDIT" -> closeRuleEditor()
            "SAVE_RULE" -> saveRuleFromEditor()
            "TOGGLE_RULE_PHONE_ONLY" -> toggleRulePhoneOnlyInEditor()
            "PASTE_PREVIEW_ORIGINAL" -> confirmPendingPaste(useFormatted = false)
            "PASTE_PREVIEW_FORMATTED" -> confirmPendingPaste(useFormatted = true)
            "PASTE_LATEST" -> pasteLatestClip()
            "CLOSE_TOOLBAR_SETTINGS" -> closeToolbarSettings()

            in FormatRule.ALL_TYPES -> {
                if (currentMode == Mode.FORMATTER) selectRuleType(tag)
            }

            ClipboardCategorizer.ALL,
            ClipboardCategorizer.PHONE,
            ClipboardCategorizer.OTP,
            ClipboardCategorizer.EMAIL,
            ClipboardCategorizer.URL,
            ClipboardCategorizer.ADDRESS,
            ClipboardCategorizer.NOTES,
            ClipboardCategorizer.OTHERS -> {
                if (currentMode == Mode.CLIPBOARD) filterByCategory(tag)
            }

            else -> {
                // Literal character key: a letter, digit, or symbol.
                var char = tag
                if (currentMode == Mode.LETTERS && tag.length == 1 && tag[0].isLetter()) {
                    char = if (isShifted) tag.uppercase() else tag.lowercase()
                    if (isShifted) {
                        isShifted = false
                        updateShiftVisual()
                    }
                }
                ic?.commitText(char, 1)
            }
        }
    }

    private fun updateShiftVisual() {
        shiftButton?.setBackgroundResource(
            if (isShifted) R.drawable.accent_key_bg else R.drawable.special_key_bg
        )
    }

    // ---------- Page switching ----------

    private fun showLetters() {
        currentMode = Mode.LETTERS
        lettersView.visibility = View.VISIBLE
        numbersView.visibility = View.GONE
        symbolsView.visibility = View.GONE
        clipboardManagerView.visibility = View.GONE
        formatterSettingsView.visibility = View.GONE
        toolbarSettingsView.visibility = View.GONE
    }

    private fun showNumbers() {
        currentMode = Mode.NUMBERS
        lettersView.visibility = View.GONE
        numbersView.visibility = View.VISIBLE
        symbolsView.visibility = View.GONE
        clipboardManagerView.visibility = View.GONE
        formatterSettingsView.visibility = View.GONE
        toolbarSettingsView.visibility = View.GONE
    }

    private fun showSymbols() {
        currentMode = Mode.SYMBOLS
        lettersView.visibility = View.GONE
        numbersView.visibility = View.GONE
        symbolsView.visibility = View.VISIBLE
        clipboardManagerView.visibility = View.GONE
        formatterSettingsView.visibility = View.GONE
        toolbarSettingsView.visibility = View.GONE
    }

    private fun showClipboardManagerPage() {
        currentMode = Mode.CLIPBOARD
        lettersView.visibility = View.GONE
        numbersView.visibility = View.GONE
        symbolsView.visibility = View.GONE
        clipboardManagerView.visibility = View.VISIBLE
        formatterSettingsView.visibility = View.GONE
        toolbarSettingsView.visibility = View.GONE
    }

    private fun showFormatterPage() {
        currentMode = Mode.FORMATTER
        lettersView.visibility = View.GONE
        numbersView.visibility = View.GONE
        symbolsView.visibility = View.GONE
        clipboardManagerView.visibility = View.GONE
        formatterSettingsView.visibility = View.VISIBLE
        toolbarSettingsView.visibility = View.GONE
    }

    private fun showToolbarSettingsPage() {
        currentMode = Mode.TOOLBAR_SETTINGS
        lettersView.visibility = View.GONE
        numbersView.visibility = View.GONE
        symbolsView.visibility = View.GONE
        clipboardManagerView.visibility = View.GONE
        formatterSettingsView.visibility = View.GONE
        toolbarSettingsView.visibility = View.VISIBLE
    }

    // =====================================================================
    // Clipboard feature (Phase 2) - everything below is additive and does
    // not alter any Phase 1 typing behaviour above.
    // =====================================================================

    private fun setupClipboardViews(root: View) {
        recentBarScroll = root.findViewById(R.id.recent_clip_bar_scroll)
        recentBarContainer = root.findViewById(R.id.recent_clip_bar_container)
        clipBrowsePanel = root.findViewById(R.id.clip_browse_panel)
        clipSettingsPanel = root.findViewById(R.id.clip_settings_panel)
        clipRecyclerView = root.findViewById(R.id.clip_recycler_view)
        clipSearchInput = root.findViewById(R.id.clip_search_input)
        clipMaxSizeInput = root.findViewById(R.id.clip_max_size_input)

        val clipboardButton = root.findViewById<Button>(R.id.clipboard_toolbar_button)
        clipboardButton.setOnLongClickListener {
            openClipboardManager()
            true
        }

        clipAdapter = ClipboardAdapter(
            onPaste = { pasteClip(it) },
            onTogglePin = { setPinned(it) },
            onToggleFavorite = { setFavorite(it) },
            onDelete = { deleteClip(it) }
        )
        clipRecyclerView.layoutManager = LinearLayoutManager(this)
        clipRecyclerView.adapter = clipAdapter

        clipSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshClipList()
            }
        })

        try {
            clipMaxSizeInput.setText(dbHelper.getMaxHistorySize().toString())
        } catch (e: Exception) {
            // non-fatal - settings field just stays blank
        }
    }

    private fun captureClipboardChange() {
        try {
            val clip = systemClipboard.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            if (text.isBlank()) return
            dbHelper.insertClip(text)
            if (::recentBarContainer.isInitialized) {
                refreshRecentBar()
            }
            if (::quickActionsBarContainer.isInitialized && currentFieldType != FieldType.PASSWORD) {
                rebuildQuickActionsBar()
            }
        } catch (e: Exception) {
            // clipboard capture must never crash the keyboard
        }
    }

    private fun toggleRecentBar() {
        if (recentBarScroll.visibility == View.VISIBLE) {
            recentBarScroll.visibility = View.GONE
        } else {
            refreshRecentBar()
            recentBarScroll.visibility = View.VISIBLE
        }
    }

    private fun refreshRecentBar() {
        try {
            recentBarContainer.removeAllViews()
            val recent = dbHelper.getRecent(5)
            for (item in recent) {
                val chip = TextView(this).apply {
                    text = if (item.content.length > 18) item.content.take(18) + "…" else item.content
                    setTextColor(resources.getColor(R.color.on_surface, theme))
                    textSize = 13f
                    setPadding(20, 12, 20, 12)
                    setBackgroundResource(R.drawable.special_key_bg)
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params.marginEnd = 6
                    layoutParams = params
                }
                addPressAnimationToView(chip)
                chip.setOnClickListener { pasteClip(item) }
                recentBarContainer.addView(chip)
            }
        } catch (e: Exception) {
            // non-fatal - recent bar just stays empty
        }
    }

    private fun addPressAnimationToView(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(60).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120)
                        .setInterpolator(OvershootInterpolator()).start()
            }
            false
        }
    }

    private fun openClipboardManager() {
        showClipboardManagerPage()
        clipBrowsePanel.visibility = View.VISIBLE
        clipSettingsPanel.visibility = View.GONE
        currentClipCategory = ClipboardCategorizer.ALL
        clipSearchInput.setText("")
        refreshClipList()
    }

    private fun closeClipboardManager() {
        showLetters()
    }

    private fun toggleClipSettingsPanel() {
        if (currentMode != Mode.CLIPBOARD) {
            openClipboardManager()
        }
        val showingSettings = clipSettingsPanel.visibility == View.VISIBLE
        clipSettingsPanel.visibility = if (showingSettings) View.GONE else View.VISIBLE
        clipBrowsePanel.visibility = if (showingSettings) View.VISIBLE else View.GONE
        if (showingSettings) refreshClipList()
    }

    private fun filterByCategory(category: String) {
        currentClipCategory = category
        refreshClipList()
    }

    private fun refreshClipList() {
        try {
            val query = clipSearchInput.text?.toString().orEmpty()
            val results = if (query.isBlank()) {
                dbHelper.getByCategory(currentClipCategory)
            } else {
                dbHelper.search(query, currentClipCategory)
            }
            clipAdapter.submitList(results)
        } catch (e: Exception) {
            // non-fatal - list just stays as-is
        }
    }

    private fun pasteClip(item: ClipItem) {
        requestPaste(item.content)
    }

    private fun setPinned(item: ClipItem) {
        dbHelper.setPinned(item.id, !item.pinned)
        refreshClipList()
    }

    private fun setFavorite(item: ClipItem) {
        dbHelper.setFavorite(item.id, !item.favorite)
        refreshClipList()
    }

    private fun deleteClip(item: ClipItem) {
        dbHelper.deleteClip(item.id)
        refreshClipList()
    }

    private fun confirmClearAll() {
        // No AlertDialog here deliberately - dialogs from an IME window are
        // unreliable across launchers/OEM skins. The "Clear All" button
        // itself is the confirmation; a toast reports what happened.
        dbHelper.clearAll()
        refreshClipList()
        refreshRecentBar()
        Toast.makeText(this, "Clipboard history cleared", Toast.LENGTH_SHORT).show()
    }

    private fun performBackup() {
        val ok = dbHelper.exportBackup()
        val message = if (ok) {
            "Backed up to Android/data/${packageName}/files/clipboard_backup.json"
        } else {
            "Backup failed"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun performRestore() {
        val count = dbHelper.importBackup()
        val message = when {
            count > 0 -> "Restored $count item(s)"
            count == 0 -> "Backup file was empty"
            else -> "No backup file found"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        refreshClipList()
        refreshRecentBar()
    }

    private fun saveClipSettings() {
        try {
            val size = clipMaxSizeInput.text?.toString()?.toIntOrNull() ?: 1000
            dbHelper.setMaxHistorySize(size)
            clipMaxSizeInput.setText(dbHelper.getMaxHistorySize().toString())
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // ignore - non-fatal
        }
    }

    private fun setAutoDeleteHours(hours: Int) {
        dbHelper.setAutoDeleteHours(hours)
        val label = when (hours) {
            0 -> "Never"
            24 -> "1 Day"
            168 -> "7 Days"
            720 -> "30 Days"
            else -> "$hours hours"
        }
        Toast.makeText(this, "Auto-delete: $label", Toast.LENGTH_SHORT).show()
    }

    // =====================================================================
    // Smart Clipboard Formatter (Phase 3) - everything below is additive
    // and does not alter any Phase 1 or Phase 2 behaviour above. Pasting
    // now flows through requestPaste() instead of committing text directly.
    // =====================================================================

    private fun setupFormatterViews(root: View) {
        formatPreviewBar = root.findViewById(R.id.format_preview_bar)
        formatPreviewOriginalText = root.findViewById(R.id.format_preview_original)
        formatPreviewFormattedText = root.findViewById(R.id.format_preview_formatted)

        formatterListPanel = root.findViewById(R.id.formatter_list_panel)
        formatterRuleEditor = root.findViewById(R.id.formatter_rule_editor)
        formatterRulesRecycler = root.findViewById(R.id.formatter_rules_recycler)
        toggleFormatterMasterBtn = root.findViewById(R.id.toggle_formatter_master)
        toggleAutoFormatBtn = root.findViewById(R.id.toggle_auto_format)
        togglePhoneModeBtn = root.findViewById(R.id.toggle_phone_mode)

        ruleNameInput = root.findViewById(R.id.rule_name_input)
        ruleParam1Input = root.findViewById(R.id.rule_param1_input)
        ruleParam2Input = root.findViewById(R.id.rule_param2_input)
        rulePriorityInput = root.findViewById(R.id.rule_priority_input)
        ruleTestInput = root.findViewById(R.id.rule_test_input)
        rulePreviewOutput = root.findViewById(R.id.rule_preview_output)
        ruleSelectedTypeLabel = root.findViewById(R.id.rule_selected_type_label)
        rulePhoneOnlyToggleBtn = root.findViewById(R.id.rule_phone_only_toggle)

        formatterAdapter = FormatterRuleAdapter(
            onEdit = { openRuleEditor(it) },
            onToggleEnabled = { formatterDb.setRuleEnabled(it.id, !it.enabled); refreshFormatterList() },
            onDelete = { formatterDb.deleteRule(it.id); refreshFormatterList() }
        )
        formatterRulesRecycler.layoutManager = LinearLayoutManager(this)
        formatterRulesRecycler.adapter = formatterAdapter

        val previewWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateRuleLivePreview()
            }
        }
        ruleParam1Input.addTextChangedListener(previewWatcher)
        ruleParam2Input.addTextChangedListener(previewWatcher)
        ruleTestInput.addTextChangedListener(previewWatcher)

        updateFormatterToggleLabels()
    }

    /**
     * Called whenever a clipboard item is about to be pasted (recent bar
     * chip or clipboard manager row). Decides, based on the formatter
     * settings, whether to paste the original text untouched, paste the
     * formatted text immediately, or show a preview and let the user pick.
     */
    private fun requestPaste(original: String) {
        if (!formatterDb.isFormatterEnabled()) {
            commitPasteText(original)
            return
        }

        val rules = formatterDb.getEnabledRulesSorted()
        val formatted = try {
            FormatterEngine.applyRules(original, isPhoneField, rules)
        } catch (e: Exception) {
            original
        }

        if (formatted == original) {
            commitPasteText(original)
            return
        }

        val autoApply = formatterDb.isAutoFormatEnabled() ||
            (isPhoneField && formatterDb.isSmartPhoneModeEnabled())

        if (autoApply) {
            commitPasteText(formatted)
        } else {
            showFormatPreview(original, formatted)
        }
    }

    private fun commitPasteText(text: String) {
        try {
            currentInputConnection?.commitText(text, 1)
            undoStack.addLast(text)
            if (undoStack.size > 50) undoStack.removeFirst()
            redoStack.clear()
        } catch (e: Exception) {
            // ignore - nothing we can safely do if the input connection is gone
        }
    }

    private fun showFormatPreview(original: String, formatted: String) {
        pendingOriginalText = original
        pendingFormattedText = formatted
        formatPreviewOriginalText.text = "Original: " + truncateForPreview(original)
        formatPreviewFormattedText.text = "Formatted: " + truncateForPreview(formatted)
        formatPreviewBar.visibility = View.VISIBLE
    }

    private fun hideFormatPreview() {
        if (::formatPreviewBar.isInitialized) {
            formatPreviewBar.visibility = View.GONE
        }
        pendingOriginalText = null
        pendingFormattedText = null
    }

    private fun confirmPendingPaste(useFormatted: Boolean) {
        val text = if (useFormatted) pendingFormattedText else pendingOriginalText
        if (text != null) commitPasteText(text)
        hideFormatPreview()
    }

    private fun truncateForPreview(text: String): String =
        if (text.length > 40) text.take(40) + "…" else text

    // ---- Formatter settings page ----

    private fun openFormatter() {
        showFormatterPage()
        formatterListPanel.visibility = View.VISIBLE
        formatterRuleEditor.visibility = View.GONE
        updateFormatterToggleLabels()
        refreshFormatterList()
    }

    private fun closeFormatter() {
        showLetters()
    }

    private fun toggleFormatterMaster() {
        formatterDb.setFormatterEnabled(!formatterDb.isFormatterEnabled())
        updateFormatterToggleLabels()
    }

    private fun toggleAutoFormat() {
        formatterDb.setAutoFormatEnabled(!formatterDb.isAutoFormatEnabled())
        updateFormatterToggleLabels()
    }

    private fun togglePhoneMode() {
        formatterDb.setSmartPhoneModeEnabled(!formatterDb.isSmartPhoneModeEnabled())
        updateFormatterToggleLabels()
    }

    private fun updateFormatterToggleLabels() {
        if (!::toggleFormatterMasterBtn.isInitialized) return
        toggleFormatterMasterBtn.text = "Formatter: " + if (formatterDb.isFormatterEnabled()) "ON" else "OFF"
        toggleAutoFormatBtn.text = "Auto: " + if (formatterDb.isAutoFormatEnabled()) "ON" else "OFF"
        togglePhoneModeBtn.text = "Phone: " + if (formatterDb.isSmartPhoneModeEnabled()) "ON" else "OFF"
    }

    private fun refreshFormatterList() {
        try {
            formatterAdapter.submitList(formatterDb.getAllRulesSorted())
        } catch (e: Exception) {
            // non-fatal - list just stays as-is
        }
    }

    // ---- Rule editor ----

    private fun openRuleEditor(existing: FormatRule?) {
        showFormatterPage()
        formatterListPanel.visibility = View.GONE
        formatterRuleEditor.visibility = View.VISIBLE

        editingRuleId = existing?.id
        editingRuleType = existing?.type
        editingRulePhoneOnly = existing?.phoneOnly ?: false

        ruleNameInput.setText(existing?.name ?: "")
        rulePriorityInput.setText((existing?.priority ?: 0).toString())
        rulePhoneOnlyToggleBtn.text = "Phone field only: " + if (editingRulePhoneOnly) "ON" else "OFF"

        val config = try {
            if (existing != null) org.json.JSONObject(existing.config) else org.json.JSONObject()
        } catch (e: Exception) {
            org.json.JSONObject()
        }

        when (existing?.type) {
            FormatRule.REMOVE_FIRST_N, FormatRule.REMOVE_LAST_N ->
                ruleParam1Input.setText(config.optInt("n", 0).toString())
            FormatRule.REMOVE_COUNTRY_CODE ->
                ruleParam1Input.setText(config.optString("codes", ""))
            FormatRule.REPLACE_TEXT -> {
                ruleParam1Input.setText(config.optString("find", ""))
                ruleParam2Input.setText(config.optString("replace", ""))
            }
            FormatRule.CUSTOM_REGEX -> {
                ruleParam1Input.setText(config.optString("pattern", ""))
                ruleParam2Input.setText(config.optString("replacement", ""))
            }
            else -> {
                ruleParam1Input.setText("")
                ruleParam2Input.setText("")
            }
        }

        ruleSelectedTypeLabel.text = "Selected type: " + (existing?.type ?: "(none)")
        updateRuleLivePreview()
    }

    private fun closeRuleEditor() {
        editingRuleId = null
        editingRuleType = null
        formatterRuleEditor.visibility = View.GONE
        formatterListPanel.visibility = View.VISIBLE
        refreshFormatterList()
    }

    private fun selectRuleType(type: String) {
        editingRuleType = type
        ruleSelectedTypeLabel.text = "Selected type: $type"
        updateRuleLivePreview()
    }

    private fun toggleRulePhoneOnlyInEditor() {
        editingRulePhoneOnly = !editingRulePhoneOnly
        rulePhoneOnlyToggleBtn.text = "Phone field only: " + if (editingRulePhoneOnly) "ON" else "OFF"
    }

    private fun buildConfigJsonFromEditor(type: String): String {
        val obj = org.json.JSONObject()
        val p1 = ruleParam1Input.text?.toString().orEmpty()
        val p2 = ruleParam2Input.text?.toString().orEmpty()
        when (type) {
            FormatRule.REMOVE_FIRST_N, FormatRule.REMOVE_LAST_N ->
                obj.put("n", p1.toIntOrNull() ?: 0)
            FormatRule.REMOVE_COUNTRY_CODE ->
                obj.put("codes", p1)
            FormatRule.REPLACE_TEXT -> {
                obj.put("find", p1)
                obj.put("replace", p2)
            }
            FormatRule.CUSTOM_REGEX -> {
                obj.put("pattern", p1)
                obj.put("replacement", p2)
            }
        }
        return obj.toString()
    }

    private fun updateRuleLivePreview() {
        if (!::rulePreviewOutput.isInitialized) return
        val type = editingRuleType
        val sample = ruleTestInput.text?.toString().orEmpty()
        if (type == null) {
            rulePreviewOutput.text = "Preview: (pick a rule type above)"
            return
        }
        val config = buildConfigJsonFromEditor(type)
        val result = FormatterEngine.previewSingleRule(sample, type, config)
        rulePreviewOutput.text = "Preview: $result"
    }

    private fun saveRuleFromEditor() {
        val type = editingRuleType
        if (type == null) {
            Toast.makeText(this, "Pick a rule type first", Toast.LENGTH_SHORT).show()
            return
        }
        val name = ruleNameInput.text?.toString()?.ifBlank { type } ?: type
        val priority = rulePriorityInput.text?.toString()?.toIntOrNull() ?: 0
        val config = buildConfigJsonFromEditor(type)

        val rule = FormatRule(
            id = editingRuleId ?: 0,
            name = name,
            type = type,
            enabled = true,
            priority = priority,
            phoneOnly = editingRulePhoneOnly,
            config = config
        )

        if (editingRuleId == null) {
            formatterDb.insertRule(rule)
        } else {
            formatterDb.updateRule(rule)
        }

        Toast.makeText(this, "Rule saved", Toast.LENGTH_SHORT).show()
        closeRuleEditor()
    }

    // =====================================================================
    // Smart Auto Mode (Phase 4) - everything below is additive and does
    // not alter any Phase 1, 2, or 3 behaviour above.
    // =====================================================================

    // ---- Smart Toolbar ----

    private fun setupToolbar(root: View) {
        toolbarExtraContainer = root.findViewById(R.id.toolbar_extra_container)
        quickActionsBarScroll = root.findViewById(R.id.quick_actions_bar_scroll)
        quickActionsBarContainer = root.findViewById(R.id.quick_actions_bar_container)
        rebuildToolbarExtras()
        refreshToolbarSettingsUI()
    }

    /** Rebuilds the customizable part of the toolbar from ToolbarPrefs. */
    private fun rebuildToolbarExtras() {
        try {
            toolbarExtraContainer.removeAllViews()
            for (item in toolbarPrefs.getOrder()) {
                if (!toolbarPrefs.isVisible(item)) continue
                val button = TextView(this).apply {
                    text = toolbarPrefs.labelFor(item).substringBefore(' ') // icon only, compact
                    setTextColor(resources.getColor(R.color.on_surface, theme))
                    textSize = 16f
                    gravity = android.view.Gravity.CENTER
                    setBackgroundResource(R.drawable.special_key_bg)
                    val params = LinearLayout.LayoutParams(
                        dpToPx(36), LinearLayout.LayoutParams.MATCH_PARENT
                    )
                    params.marginEnd = dpToPx(4)
                    layoutParams = params
                }
                addPressAnimationToView(button)
                button.setOnClickListener { onToolbarItemTapped(item) }
                toolbarExtraContainer.addView(button)
            }
        } catch (e: Exception) {
            // non-fatal - toolbar extras just stay as they were
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun onToolbarItemTapped(item: String) {
        when (item) {
            ToolbarPrefs.VOICE -> showComingSoonToast("Voice typing")
            ToolbarPrefs.EMOJI -> showComingSoonToast("Emoji keyboard")
            ToolbarPrefs.GIF -> showComingSoonToast("GIF search")
            ToolbarPrefs.TRANSLATE -> showComingSoonToast("Translate")
            ToolbarPrefs.UNDO -> performUndo()
            ToolbarPrefs.REDO -> performRedo()
            ToolbarPrefs.SETTINGS -> openToolbarSettings()
        }
    }

    private fun showComingSoonToast(feature: String) {
        Toast.makeText(this, "$feature - coming soon", Toast.LENGTH_SHORT).show()
    }

    private fun pasteLatestClip() {
        val latest = try {
            dbHelper.getRecent(1).firstOrNull()
        } catch (e: Exception) {
            null
        }
        if (latest == null) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        requestPaste(latest.content)
    }

    private fun performUndo() {
        val text = undoStack.removeLastOrNull()
        if (text == null) {
            Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            currentInputConnection?.deleteSurroundingText(text.length, 0)
            redoStack.addLast(text)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun performRedo() {
        val text = redoStack.removeLastOrNull()
        if (text == null) {
            Toast.makeText(this, "Nothing to redo", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            currentInputConnection?.commitText(text, 1)
            undoStack.addLast(text)
        } catch (e: Exception) {
            // ignore
        }
    }

    // ---- Toolbar Settings page ----

    private fun openToolbarSettings() {
        showToolbarSettingsPage()
        refreshToolbarSettingsUI()
    }

    private fun closeToolbarSettings() {
        showLetters()
        rebuildToolbarExtras()
    }

    private fun refreshToolbarSettingsUI() {
        if (!::toolbarSettingsView.isInitialized) return
        for (item in ToolbarPrefs.DEFAULT_ORDER) {
            val btn = toolbarSettingsView.findViewWithTag<Button>("TOOLBAR_TOGGLE_$item") ?: continue
            val visible = toolbarPrefs.isVisible(item)
            btn.text = if (visible) "ON" else "OFF"
            btn.setBackgroundResource(if (visible) R.drawable.accent_key_bg else R.drawable.special_key_bg)
        }
    }

    /**
     * Handles the TOOLBAR_TOGGLE_, TOOLBAR_UP_, TOOLBAR_DOWN_ buttons from
     * the Toolbar Settings page. Returns true if the tag was handled, so
     * the caller can skip the main key-dispatch table entirely for these.
     */
    private fun handleToolbarSettingsTag(tag: String): Boolean {
        when {
            tag.startsWith("TOOLBAR_TOGGLE_") -> {
                val item = tag.removePrefix("TOOLBAR_TOGGLE_")
                toolbarPrefs.setVisible(item, !toolbarPrefs.isVisible(item))
                refreshToolbarSettingsUI()
                return true
            }
            tag.startsWith("TOOLBAR_UP_") -> {
                toolbarPrefs.moveUp(tag.removePrefix("TOOLBAR_UP_"))
                return true
            }
            tag.startsWith("TOOLBAR_DOWN_") -> {
                toolbarPrefs.moveDown(tag.removePrefix("TOOLBAR_DOWN_"))
                return true
            }
        }
        return false
    }

    // ---- Smart Quick Actions bar ----

    private fun rebuildQuickActionsBar() {
        try {
            quickActionsBarContainer.removeAllViews()
            if (currentFieldType == FieldType.PASSWORD) {
                quickActionsBarScroll.visibility = View.GONE
                return
            }

            val chips = mutableListOf<Pair<String, () -> Unit>>()

            when (currentFieldType) {
                FieldType.EMAIL -> {
                    chips.add("@" to { commitPasteText("@") })
                    chips.add(".com" to { commitPasteText(".com") })
                    chips.add(".in" to { commitPasteText(".in") })
                    chips.add(".org" to { commitPasteText(".org") })
                }
                FieldType.URL -> {
                    chips.add("https://" to { commitPasteText("https://") })
                    chips.add("www." to { commitPasteText("www.") })
                    chips.add(".com" to { commitPasteText(".com") })
                    chips.add("/" to { commitPasteText("/") })
                }
                else -> {}
            }

            val latest = dbHelper.getRecent(1).firstOrNull()
            if (latest != null) {
                val label = when (latest.category) {
                    ClipboardCategorizer.PHONE -> "📞 Paste Number"
                    ClipboardCategorizer.OTP -> "🔑 Paste OTP"
                    ClipboardCategorizer.EMAIL -> "✉ Paste Email"
                    ClipboardCategorizer.URL -> "🔗 Paste Link"
                    ClipboardCategorizer.ADDRESS -> "🏠 Paste Address"
                    else -> null
                }
                if (label != null) {
                    chips.add(label to { requestPaste(latest.content) })
                }
            }

            for ((label, action) in chips) {
                val chip = TextView(this).apply {
                    text = label
                    setTextColor(resources.getColor(R.color.on_surface, theme))
                    textSize = 13f
                    setPadding(20, 12, 20, 12)
                    setBackgroundResource(R.drawable.special_key_bg)
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params.marginEnd = 6
                    layoutParams = params
                }
                addPressAnimationToView(chip)
                chip.setOnClickListener { action() }
                quickActionsBarContainer.addView(chip)
            }

            quickActionsBarScroll.visibility = if (chips.isEmpty()) View.GONE else View.VISIBLE
        } catch (e: Exception) {
            try {
                quickActionsBarScroll.visibility = View.GONE
            } catch (e2: Exception) {
                // ignore
            }
        }
    }
}
