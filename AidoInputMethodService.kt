package com.rr.aido.service

import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.media.AudioManager
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.speech.RecognizerIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.lifecycleScope
import com.rr.aido.R
import com.rr.aido.data.DataStoreManager
import com.rr.aido.data.models.TriggerMethod
import com.rr.aido.data.repository.GeminiRepositoryImpl
import com.rr.aido.utils.PromptParser
import com.rr.aido.utils.WordDatabase
import com.rr.aido.keyboard_service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Aido Custom Keyboard with AI Integration
 * Complete keyboard implementation with trigger detection and processing
 */
class AidoInputMethodService : InputMethodService(), 
    androidx.lifecycle.LifecycleOwner, 
    androidx.lifecycle.ViewModelStoreOwner, 
    androidx.savedstate.SavedStateRegistryOwner {

    // Compose Keyboard State if needed
    private var caps = false
    private var mainKeyboardView: android.view.View? = null
    
    private lateinit var dataStoreManager: DataStoreManager
    private val geminiRepository = GeminiRepositoryImpl()
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Suggestion strip components
    // private var suggestionContainer: android.widget.LinearLayout? = null // Legacy removed
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    private var currentWord = StringBuilder()
    
    // Track recently used words for better predictions
    private val recentWords = mutableListOf<String>()
    private val maxRecentWords = 50
    
    // Keyboard service modules
    private lateinit var clipboardManager: KeyboardClipboardManager
    private lateinit var textEditingManager: TextEditingManager
    private lateinit var menuHandler: KeyboardMenuHandler
    private lateinit var emojiPanelHandler: EmojiPanelHandler
    private lateinit var clipboardPanelHandler: ClipboardPanelHandler
    private lateinit var triggerPanelHandler: TriggerPanelHandler
    
    private enum class KeyboardType {
        QWERTY, SYMBOLS
    }
    
    companion object {
        private const val TAG = "AidoKeyboard"
        private const val KEYCODE_SPACE = 32
        private const val KEYCODE_ENTER = 10
        private const val KEYCODE_DELETE = -5
        private const val KEYCODE_SHIFT = -1
        private const val KEYCODE_MODE_CHANGE = -2  // Switch to symbols
        private const val KEYCODE_MODE_CHANGE_BACK = -3  // Switch to ABC
        private const val KEYCODE_DONE = -4
        private const val KEYCODE_VOICE = -100
    }

    // Theme State
    private var themeMode = com.rr.aido.data.models.ThemeMode.SYSTEM
    private var currentThemeId = 0
    
    // Lifecycle and SavedState
    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
    private val savedStateRegistryController = androidx.savedstate.SavedStateRegistryController.create(this)
    private val store = androidx.lifecycle.ViewModelStore()
    
    override val lifecycle: androidx.lifecycle.Lifecycle
        get() = lifecycleRegistry

    private val currentViewState = kotlinx.coroutines.flow.MutableStateFlow(com.rr.aido.keyboard.ui.KeyboardView.ALPHA)

    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        
        // Auto-detect view based on input type
        val inputType = info?.inputType ?: 0
        val variation = inputType and EditorInfo.TYPE_MASK_VARIATION
        val classType = inputType and EditorInfo.TYPE_MASK_CLASS
        
        when (classType) {
            EditorInfo.TYPE_CLASS_NUMBER, 
            EditorInfo.TYPE_CLASS_PHONE, 
            EditorInfo.TYPE_CLASS_DATETIME -> {
                currentViewState.value = com.rr.aido.keyboard.ui.KeyboardView.NUMBER_PAD
            }
            else -> {
                // Check variation for visible password/web password etc if needed, but usually default to ALPHA
                currentViewState.value = com.rr.aido.keyboard.ui.KeyboardView.ALPHA
            }
        }
    }

    override val viewModelStore: androidx.lifecycle.ViewModelStore
        get() = store

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        
        // Set ViewTree owners on the window decor view for WindowRecomposer
        window?.window?.let { window ->
            val decorView = window.decorView
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
            
            // Enable edge-to-edge to handle insets manually
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        
        dataStoreManager = DataStoreManager(applicationContext)
        
        // Listen for settings changes
        serviceScope.launch {
            dataStoreManager.settingsFlow.collect { settings ->
                if (themeMode != settings.themeMode) {
                    themeMode = settings.themeMode
                    updateTheme()
                }
            }
        }
    }



    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (themeMode == com.rr.aido.data.models.ThemeMode.SYSTEM) {
            updateTheme()
        }
    }

    private fun getThemeId(): Int {
        return when (themeMode) {
            com.rr.aido.data.models.ThemeMode.LIGHT -> R.style.Theme_Aido_Keyboard_Light
            com.rr.aido.data.models.ThemeMode.DARK -> R.style.Theme_Aido_Keyboard_Dark
            com.rr.aido.data.models.ThemeMode.SYSTEM -> {
                val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                if (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                    R.style.Theme_Aido_Keyboard_Dark
                } else {
                    R.style.Theme_Aido_Keyboard_Light
                }
            }
        }
    }

    private fun updateTheme() {
        // Compose handles theme updates automatically via isSystemInDarkTheme()
        // But if we need to force recreate we can do it here
        setInputView(onCreateInputView())
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        
        val inputView = window?.window?.decorView
        if (inputView != null && inputView.visibility == View.VISIBLE) {
            // Get the actual height of the keyboard view
            val location = IntArray(2)
            inputView.getLocationInWindow(location)
            
            val viewHeight = inputView.height
            val viewTop = location[1]
            
            // Set content insets to tell the app where the keyboard starts
            outInsets.contentTopInsets = viewTop
            outInsets.visibleTopInsets = viewTop
            
            // Touch only region - keyboard should receive all touch events
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            outInsets.touchableRegion.setEmpty()
        } else {
            // Keyboard is not visible
            outInsets.contentTopInsets = 0
            outInsets.visibleTopInsets = 0
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
        }
    }

    override fun onCreateInputView(): View {
        // Ensure DataStore is initialized if onCreateInputView is called before onCreate (rare but possible)
        if (!::dataStoreManager.isInitialized) {
            dataStoreManager = DataStoreManager(applicationContext)
        }

        // Initialize keyboard service modules
        clipboardManager = KeyboardClipboardManager(applicationContext)
        textEditingManager = TextEditingManager(applicationContext)
        // Menu handler simplified or removed for now as we transition to Compose
        
        // Create ComposeView
        val composeView = androidx.compose.ui.platform.ComposeView(this).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            
            setContent {
                val suggestions by _suggestions.collectAsState()
                val clipboardHistory = clipboardManager.historyFlow.collectAsState(initial = emptyList()).value // Need to add flow to manager
                val settings = dataStoreManager.settingsFlow.collectAsState(initial = com.rr.aido.data.models.Settings()).value // Need default
                val triggers = dataStoreManager.prepromptsFlow.collectAsState(initial = emptyList()).value

                // View State - Hoisted to Service level
                val currentView by currentViewState.collectAsState()
                
                com.rr.aido.keyboard.ui.AidoKeyboard(
                    actionListener = object : com.rr.aido.keyboard.ui.KeyboardActionListener {
                        override fun onKey(code: Int) {
                            // Legacy
                        }
                        override fun onText(text: String) {
                            currentInputConnection?.commitText(text, 1)
                            if (text == " ") {
                                currentWord.clear()
                                updateSuggestionsForCurrentWord() // Ensure suggestions trigger
                                checkForTrigger(currentInputConnection)
                            } else {
                                currentWord.append(text)
                                updateSuggestionsForCurrentWord()
                                checkForTrigger(currentInputConnection)
                            }
                        }
                        override fun onDelete() {
                            handleDelete(currentInputConnection)
                        }
                        override fun onEnter() {
                            handleEnter(currentInputConnection)
                        }
                        override fun onEmoji() {
                             currentViewState.value = com.rr.aido.keyboard.ui.KeyboardView.EMOJI
                        }
                        override fun onMedia(url: String, mimeType: String) {
                             // Launch download and commit
                             serviceScope.launch(Dispatchers.IO) {
                                 downloadAndCommitMedia(url, mimeType)
                             }
                        }
                        override fun onMoveCursor(offset: Int) {
                             handleMoveCursor(offset)
                        }
                    },
                    suggestionListener = object : com.rr.aido.keyboard.ui.SuggestionListener {
                        override fun onPickSuggestion(text: String) {
                            insertSuggestion(text)
                        }
                        override fun onMenuClick() {
                            currentViewState.value = if (currentViewState.value == com.rr.aido.keyboard.ui.KeyboardView.MENU) {
                                com.rr.aido.keyboard.ui.KeyboardView.ALPHA
                            } else {
                                com.rr.aido.keyboard.ui.KeyboardView.MENU
                            }
                        }
                        override fun onVoiceClick() {
                            handleVoiceInput()
                        }
                        override fun onUndoClick() {
                            handleUndo()
                        }
                        override fun onRedoClick() {
                            handleRedo()
                        }
                        override fun onClipboardClick() {
                            // Refresh clipboard before showing
                            clipboardManager.refreshFromSystemClipboard()
                            currentViewState.value = com.rr.aido.keyboard.ui.KeyboardView.CLIPBOARD
                        }
                        override fun onTriggerClick() {
                            currentViewState.value = com.rr.aido.keyboard.ui.KeyboardView.TRIGGERS
                        }
                    },
                    suggestions = suggestions,
                    themeMode = themeMode,
                    
                    // State & Actions
                    currentView = currentView,
                    onViewChange = { newView -> currentViewState.value = newView },
                    
                    // Menu Actions
                    onSettingsClick = {
                        try {
                            val intent = Intent(applicationContext, com.rr.aido.MainActivity::class.java)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                            requestHideSelf(0)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error opening settings", e)
                        }
                    },
                    onThemeClick = {
                         val nightModeFlags = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                         val isSystemDark = nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
                         
                         val nextTheme = when (themeMode) {
                             com.rr.aido.data.models.ThemeMode.SYSTEM -> {
                                 // If System is Dark, go Light. If System is Light, go Dark.
                                 if (isSystemDark) com.rr.aido.data.models.ThemeMode.LIGHT 
                                 else com.rr.aido.data.models.ThemeMode.DARK
                             }
                             com.rr.aido.data.models.ThemeMode.LIGHT -> {
                                 // From Light: If System is Dark, go System(Dark). Else go Dark.
                                 if (isSystemDark) com.rr.aido.data.models.ThemeMode.SYSTEM
                                 else com.rr.aido.data.models.ThemeMode.DARK
                             }
                             com.rr.aido.data.models.ThemeMode.DARK -> {
                                 // From Dark: If System is Dark, go Light. Else go System(Light).
                                 if (isSystemDark) com.rr.aido.data.models.ThemeMode.LIGHT
                                 else com.rr.aido.data.models.ThemeMode.SYSTEM
                             }
                         }
                         serviceScope.launch {
                             dataStoreManager.saveThemeMode(nextTheme)
                         }
                    },
                    
                    // Clipboard
                    clipboardHistory = clipboardHistory,
                    onPasteClick = { text ->
                        currentInputConnection?.commitText(text, 1)
                    },
                    onDeleteClipClick = { index ->
                        clipboardManager.deleteFromHistory(index)
                    },
                    onClearClipboardClick = {
                        clipboardManager.clearHistory()
                    },
                    
                    // Triggers
                    triggers = triggers,
                    onTriggerClick = { text ->
                        currentInputConnection?.commitText(text, 1)
                        if (currentInputConnection != null) {
                             Handler(Looper.getMainLooper()).postDelayed({
                                checkForTrigger(currentInputConnection)
                             }, 100)
                        }
                    }
                )
            }
        }
        
        Log.d(TAG, "Aido Keyboard connected via Compose")
        mainKeyboardView = composeView
        return composeView
    }

    private fun setupActionBarButtons(view: View) {
        // Menu button (Hamburger)
        view.findViewById<android.widget.ImageButton>(R.id.btn_menu)?.setOnClickListener {
            val keyboardView = window?.window?.decorView
            keyboardView?.let { menuHandler.showMenuPopup(it) }
        }
        
        // Microphone button
        view.findViewById<android.widget.ImageButton>(R.id.btn_microphone)?.setOnClickListener {
            handleVoiceInput()
        }

        // Undo button
        view.findViewById<android.widget.ImageButton>(R.id.btn_undo)?.setOnClickListener {
            handleUndo()
        }

        // Redo button
        view.findViewById<android.widget.ImageButton>(R.id.btn_redo)?.setOnClickListener {
            handleRedo()
        }
    }

    private fun showEmojiPanel() {
        try {
            emojiPanelHandler = EmojiPanelHandler(
                context = this,
                layoutInflater = layoutInflater,
                inputConnection = currentInputConnection
            )
            val emojiView = emojiPanelHandler.createEmojiPanel {
                // Recreate keyboard view properly when back button is pressed
                val newView = onCreateInputView()
                setInputView(newView)
                // Force keyboard to be active and visible
                Handler(Looper.getMainLooper()).postDelayed({
                    mainKeyboardView?.isEnabled = true
                    mainKeyboardView?.visibility = View.VISIBLE
                    mainKeyboardView?.requestLayout()
                    window?.window?.decorView?.requestLayout()
                }, 50)
            }
            setInputView(emojiView)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing emoji panel", e)
        }
    }

    private fun showClipboardPanel() {
        try {
            clipboardPanelHandler = ClipboardPanelHandler(
                context = this,
                layoutInflater = layoutInflater,
                clipboardManager = clipboardManager,
                onPasteClick = { text ->
                    currentInputConnection?.commitText(text, 1)
                    // Recreate keyboard view properly
                    val newView = onCreateInputView()
                    setInputView(newView)
                    // Force keyboard to be active and visible
                    Handler(Looper.getMainLooper()).postDelayed({
                        mainKeyboardView?.isEnabled = true
                        mainKeyboardView?.visibility = View.VISIBLE
                        mainKeyboardView?.requestLayout()
                        window?.window?.decorView?.requestLayout()
                    }, 50)
                }
            )
            val clipboardView = clipboardPanelHandler.createClipboardPanel {
                // Recreate keyboard view properly when back button is pressed
                val newView = onCreateInputView()
                setInputView(newView)
                // Force keyboard to be active and visible
                Handler(Looper.getMainLooper()).postDelayed({
                    mainKeyboardView?.isEnabled = true
                    mainKeyboardView?.visibility = View.VISIBLE
                    mainKeyboardView?.requestLayout()
                    window?.window?.decorView?.requestLayout()
                }, 50)
            }
            setInputView(clipboardView)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing clipboard panel", e)
        }
    }

    private fun showTriggerPanel() {
        try {
            triggerPanelHandler = TriggerPanelHandler(
                context = this,
                layoutInflater = layoutInflater,
                dataStoreManager = dataStoreManager,
                onTriggerClick = { trigger ->
                    val ic = currentInputConnection
                    ic?.commitText(trigger, 1)
                    // Recreate keyboard view properly
                    val newView = onCreateInputView()
                    setInputView(newView)
                    // Force keyboard to be active and visible
                    Handler(Looper.getMainLooper()).postDelayed({
                        mainKeyboardView?.isEnabled = true
                        mainKeyboardView?.visibility = View.VISIBLE
                        mainKeyboardView?.requestLayout()
                        window?.window?.decorView?.requestLayout()
                    }, 50)
                    
                    // Trigger insert karne ke baad check karo
                    if (ic != null) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            checkForTrigger(ic)
                        }, 100)
                    }
                }
            )
            val triggerView = triggerPanelHandler.createTriggerPanel {
                // Recreate keyboard view properly when back button is pressed
                val newView = onCreateInputView()
                setInputView(newView)
                // Force keyboard to be active and visible
                Handler(Looper.getMainLooper()).postDelayed({
                    mainKeyboardView?.isEnabled = true
                    mainKeyboardView?.visibility = View.VISIBLE
                    mainKeyboardView?.requestLayout()
                    window?.window?.decorView?.requestLayout()
                }, 50)
            }
            setInputView(triggerView)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing trigger panel", e)
        }
    }

    // Undo/Redo History - Better Implementation
    private data class TextState(
        val text: String,
        val cursorPosition: Int
    )
    
    private val undoStack = java.util.Stack<TextState>()
    private val redoStack = java.util.Stack<TextState>()
    private var isUndoingOrRedoing = false
    private var lastSavedText = ""

    private fun saveHistory() {
        if (isUndoingOrRedoing) return
        
        val ic = currentInputConnection ?: return
        
        // Get all text and cursor position
        val textBefore = ic.getTextBeforeCursor(10000, 0)?.toString() ?: ""
        val textAfter = ic.getTextAfterCursor(10000, 0)?.toString() ?: ""
        val fullText = textBefore + textAfter
        val cursorPos = textBefore.length
        
        // Only save if text actually changed
        if (fullText != lastSavedText) {
            val state = TextState(fullText, cursorPos)
            
            // Don't save duplicate states
            if (undoStack.isEmpty() || undoStack.peek().text != fullText) {
                undoStack.push(state)
                redoStack.clear()
                lastSavedText = fullText
                
                Log.d(TAG, "History saved: text='${fullText.take(50)}...', cursor=$cursorPos, stack size=${undoStack.size}")
            }
            
            // Limit stack size
            if (undoStack.size > 50) {
                undoStack.removeAt(0)
            }
        }
    }

    private fun handleUndo() {
        val ic = currentInputConnection ?: return
        
        if (undoStack.isEmpty()) {
            Log.d(TAG, "Undo: Stack is empty")
            android.widget.Toast.makeText(this, "Nothing to undo", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        isUndoingOrRedoing = true
        try {
            // Save current state to redo stack
            val textBefore = ic.getTextBeforeCursor(10000, 0)?.toString() ?: ""
            val textAfter = ic.getTextAfterCursor(10000, 0)?.toString() ?: ""
            val currentText = textBefore + textAfter
            val currentCursor = textBefore.length
            
            redoStack.push(TextState(currentText, currentCursor))
            
            // Get previous state
            val previousState = undoStack.pop()
            
            Log.d(TAG, "Undo: from '${currentText.take(30)}...' to '${previousState.text.take(30)}...'")
            
            // Clear all text
            ic.beginBatchEdit()
            ic.deleteSurroundingText(textBefore.length, textAfter.length)
            
            // Insert previous text
            ic.commitText(previousState.text, 1)
            
            // Set cursor position
            if (previousState.cursorPosition <= previousState.text.length) {
                ic.setSelection(previousState.cursorPosition, previousState.cursorPosition)
            }
            ic.endBatchEdit()
            
            lastSavedText = previousState.text
            
        } catch (e: Exception) {
            Log.e(TAG, "Undo error", e)
        } finally {
            isUndoingOrRedoing = false
        }
    }

    private fun handleRedo() {
        val ic = currentInputConnection ?: return
        
        if (redoStack.isEmpty()) {
            Log.d(TAG, "Redo: Stack is empty")
            android.widget.Toast.makeText(this, "Nothing to redo", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        isUndoingOrRedoing = true
        try {
            // Save current state to undo stack
            val textBefore = ic.getTextBeforeCursor(10000, 0)?.toString() ?: ""
            val textAfter = ic.getTextAfterCursor(10000, 0)?.toString() ?: ""
            val currentText = textBefore + textAfter
            val currentCursor = textBefore.length
            
            undoStack.push(TextState(currentText, currentCursor))
            
            // Get next state
            val nextState = redoStack.pop()
            
            Log.d(TAG, "Redo: from '${currentText.take(30)}...' to '${nextState.text.take(30)}...'")
            
            // Clear all text
            ic.beginBatchEdit()
            ic.deleteSurroundingText(textBefore.length, textAfter.length)
            
            // Insert next text
            ic.commitText(nextState.text, 1)
            
            // Set cursor position
            if (nextState.cursorPosition <= nextState.text.length) {
                ic.setSelection(nextState.cursorPosition, nextState.cursorPosition)
            }
            ic.endBatchEdit()
            
            lastSavedText = nextState.text
            
        } catch (e: Exception) {
            Log.e(TAG, "Redo error", e)
        } finally {
            isUndoingOrRedoing = false
        }
    }

    private fun handleMoveCursor(offset: Int) {
        val ic = currentInputConnection ?: return
        try {
            val extractedText = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            if (extractedText != null) {
                val start = extractedText.selectionStart
                val end = extractedText.selectionEnd
                
                var newPos = start + offset
                
                // Clamp
                if (newPos < 0) newPos = 0
                if (newPos > extractedText.text.length) newPos = extractedText.text.length
                
                ic.setSelection(newPos, newPos)
            } else {
                 // Fallback if extracted text not supported
                 if (offset < 0) {
                     for (i in 0 until -offset) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
                 } else {
                     for (i in 0 until offset) ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                 }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error moving cursor", e)
        }
    }

    // Legacy methods adapted for Compose actions
    
    private fun handleEnter(ic: InputConnection?) {
        ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    private fun handleDelete(ic: InputConnection?) {
        if (ic == null) return
        saveHistory()
        val selectedText = ic.getSelectedText(0)
        if (TextUtils.isEmpty(selectedText)) {
            ic.deleteSurroundingText(1, 0)
            
            // Remove last character from current word
            if (currentWord.isNotEmpty()) {
                currentWord.deleteCharAt(currentWord.length - 1)
                if (currentWord.isEmpty()) {
                    updateSuggestions(emptyList())
                } else {
                    updateSuggestionsForCurrentWord()
                }
            }
        } else {
            ic.commitText("", 1)
            currentWord.clear()
            updateSuggestions(emptyList())
        }
    }

    private fun handleDone(ic: InputConnection) {
        val imeOptions = currentInputEditorInfo.imeOptions
        val actionId = imeOptions and EditorInfo.IME_MASK_ACTION
        
        when (actionId) {
            EditorInfo.IME_ACTION_SEARCH -> ic.performEditorAction(EditorInfo.IME_ACTION_SEARCH)
            EditorInfo.IME_ACTION_SEND -> ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
            EditorInfo.IME_ACTION_GO -> ic.performEditorAction(EditorInfo.IME_ACTION_GO)
            EditorInfo.IME_ACTION_NEXT -> ic.performEditorAction(EditorInfo.IME_ACTION_NEXT)
            EditorInfo.IME_ACTION_DONE -> ic.performEditorAction(EditorInfo.IME_ACTION_DONE)
            else -> handleEnter(ic)
        }
    }

    private fun handleVoiceInput() {
        try {
            // Switch to system voice input method (like Google Voice Typing)
            if (switchToVoiceInputMethod()) {
                Log.d(TAG, "Switched to voice input method")
            } else {
                Log.w(TAG, "No voice input method available")
                // Show toast to inform user
                android.widget.Toast.makeText(
                    this,
                    "No voice input method found. Please install Google Voice Typing.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Voice input error", e)
            android.widget.Toast.makeText(
                this,
                "Voice input error: ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Switch to voice input method (similar to FlorisBoard implementation)
     * Searches for available voice input methods and switches to the first one found
     */
    private fun switchToVoiceInputMethod(): Boolean {
        try {
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java) ?: return false
            val list: List<android.view.inputmethod.InputMethodInfo> = imm.enabledInputMethodList
            
            for (el in list) {
                for (i in 0 until el.subtypeCount) {
                    val subtype = el.getSubtypeAt(i)
                    
                    // Check if this is a voice input subtype
                    if (subtype.mode == "voice") {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            // Android 9+ (API 28+)
                            switchInputMethod(el.id, subtype)
                            return true
                        } else {
                            // Android 7-8 (API 24-27)
                            window.window?.let { window ->
                                @Suppress("DEPRECATION")
                                imm.setInputMethod(window.attributes.token, el.id)
                                return true
                            }
                        }
                    }
                }
            }
            
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error switching to voice input", e)
            return false
        }
    }

    private fun checkForTrigger(ic: InputConnection) {
        serviceScope.launch {
            try {
                // Get settings first to check trigger method
                val settings = dataStoreManager.settingsFlow.first()
                
                // Check if service is enabled
                if (!settings.isServiceEnabled) {
                    Log.d(TAG, "Service is disabled by user")
                    return@launch
                }
                
                // Check if keyboard method is selected
                if (settings.triggerMethod != TriggerMethod.KEYBOARD) {
                    Log.d(TAG, "Keyboard method not selected, skipping trigger")
                    return@launch
                }
                
                // Get text before cursor (last 100 characters to check for trigger)
                val textBeforeCursor = ic.getTextBeforeCursor(100, 0)?.toString() ?: ""
                
                // Extract trigger using PromptParser
                val trigger = PromptParser.extractTrigger(textBeforeCursor)
                
                if (trigger != null) {
                    Log.d(TAG, "Trigger detected: $trigger")
                    
                    // Get preprompts
                    val preprompts = dataStoreManager.prepromptsFlow.first()
                    
                    // Parse input with preprompt
                    val parseResult = PromptParser.parseInput(textBeforeCursor, preprompts)
                    
                    if (parseResult != null && parseResult.matchedPreprompt != null) {
                        Log.d(TAG, "Processing with preprompt: ${parseResult.matchedPreprompt.trigger}")
                        
                        // Send to AI
                        val initialPrompt = parseResult.finalPrompt
                        val finalPrompt = if (settings.responseLanguage != "English" && settings.responseLanguage != "None") {
                            "(Please reply in ${settings.responseLanguage}) $initialPrompt"
                        } else {
                            initialPrompt
                        }

                        val result = geminiRepository.sendPrompt(
                            provider = settings.provider,
                            apiKey = settings.apiKey,
                            model = settings.selectedModel,
                            prompt = finalPrompt
                        )
                        
                        if (result is com.rr.aido.data.repository.Result.Success) {
                            val response = result.data
                            
                            // Delete the original text with trigger
                            val textLength = textBeforeCursor.length
                            ic.deleteSurroundingText(textLength, 0)
                            
                            // Insert AI response
                            ic.commitText(response, 1)
                            
                            Log.d(TAG, "AI response inserted successfully")
                        } else if (result is com.rr.aido.data.repository.Result.Error) {
                            Log.e(TAG, "AI error: ${result.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking trigger", e)
            }
        }
    }

    private fun playClick(keyCode: Int) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        when (keyCode) {
            KEYCODE_DONE, KEYCODE_ENTER -> am.playSoundEffect(AudioManager.FX_KEYPRESS_RETURN)
            KEYCODE_DELETE -> am.playSoundEffect(AudioManager.FX_KEYPRESS_DELETE)
            KEYCODE_SPACE -> am.playSoundEffect(AudioManager.FX_KEYPRESS_SPACEBAR)
            else -> am.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
        }
    }
    
    /**
     * Update suggestions based on current word being typed
     * Uses WordDatabase with smart frequency-based ranking
     */
    private fun updateSuggestionsForCurrentWord() {
        // Next Word Prediction Logic
        if (currentWord.isEmpty()) {
            val ic = currentInputConnection
            if (ic == null) {
                updateSuggestions(emptyList())
                return
            }
            // Get text before cursor to find the last word
            val textBefore = ic.getTextBeforeCursor(50, 0)?.toString() ?: ""
            // Find last word (split by spaces, ignore empty)
            val words = textBefore.trim().split("\\s+".toRegex())
            val lastWord = if (words.isNotEmpty()) words.last() else ""
            
            if (lastWord.isNotEmpty()) {
                val predictions = com.rr.aido.utils.SuggestionEngine.getPredictions(lastWord, 6)
                // Always show something if possible, or clear
                if (predictions.isNotEmpty()) {
                    updateSuggestions(predictions)
                } else {
                    updateSuggestions(emptyList())
                }
            } else {
                 updateSuggestions(emptyList())
            }
            return
        }
        
        // Word Completion Logic
        val prefix = currentWord.toString().lowercase()
        
        val completions = com.rr.aido.utils.SuggestionEngine.getCompletions(prefix, 6)
        
        val recentMatches = recentWords
            .filter { it.startsWith(prefix) && it != prefix }
            .take(2)
            
        val allSuggestions = (recentMatches + completions)
            .distinct()
            .take(6)
        
        updateSuggestions(allSuggestions)
    }
    
    /**
     * Update the suggestion strip with new suggestions
     */
    /**
     * Update the suggestion strip with new suggestions
     */
    private fun updateSuggestions(suggestions: List<String>) {
        _suggestions.value = suggestions
    }
    
    /**
     * Insert a suggestion when user taps it
     */
    private fun insertSuggestion(word: String) {
        saveHistory()
        val ic = currentInputConnection ?: return
        
        // Delete the current partial word
        if (currentWord.isNotEmpty()) {
            ic.deleteSurroundingText(currentWord.length, 0)
        }
        
        // Insert the complete word with a space
        ic.commitText("$word ", 1)
        
        // Add to recent words for better future predictions
        addToRecentWords(word)
        
        // Clear current word
        currentWord.clear()
        
        // Trigger next word prediction after a short delay to let InputConnection update
        Handler(Looper.getMainLooper()).postDelayed({
            updateSuggestionsForCurrentWord()
        }, 50)
    }
    
    /**
     * Track recently used words for personalized suggestions
     */
    private fun addToRecentWords(word: String) {
        val lowerWord = word.lowercase()
        
        // Remove if already exists (to move to front)
        recentWords.remove(lowerWord)
        
        // Add to front
        recentWords.add(0, lowerWord)
        
        // Keep only last N words
        if (recentWords.size > maxRecentWords) {
            recentWords.removeAt(recentWords.size - 1)
        }
    }

    /**
     * Downloads media to local cache and shares via FileProvider
     */
    private suspend fun downloadAndCommitMedia(url: String, mimeType: String) {
        val ic = currentInputConnection ?: return
        
        try {
            // 1. Download File
            val client = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder().url(url).build()
            
            // Should be on IO thread (called from launch(Dispatchers.IO))
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to download media: $url")
                ic.commitText(url, 1) // Fallback
                return
            }
            
            val body = response.body ?: return
            
            // Create cache file
            val cachePath = java.io.File(cacheDir, "images")
            if (!cachePath.exists()) {
                cachePath.mkdirs()
            }
            
            // Clean up old files (basic)
            if ((cachePath.listFiles()?.size ?: 0) > 20) {
                 cachePath.listFiles()?.forEach { it.delete() }
            }
            
            val fileName = "media_${System.currentTimeMillis()}.${if(mimeType.contains("gif")) "gif" else "png"}"
            val file = java.io.File(cachePath, fileName)
            
            val fos = java.io.FileOutputStream(file)
            fos.write(body.bytes())
            fos.close()
            
            // 2. Get Content URI
            val contentUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            
            // 3. Commit Content
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                val description = android.content.ClipDescription("Media", arrayOf(mimeType))
                
                val inputContentInfo = android.view.inputmethod.InputContentInfo(
                    contentUri,
                    description,
                    android.net.Uri.parse(url) // Link URI fallback
                )
                
                val flags = android.view.inputmethod.InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
                
                withContext(Dispatchers.Main) {
                    if (!ic.commitContent(inputContentInfo, flags, null)) {
                        Log.w(TAG, "Editor rejected content, falling back to text")
                        ic.commitText(url, 1)
                    }
                }
            } else {
                 withContext(Dispatchers.Main) {
                     ic.commitText(url, 1)
                 }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing media", e)
            withContext(Dispatchers.Main) {
                ic.commitText(url, 1)
            }
        }
    }



    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
        store.clear()
        serviceScope.cancel()
        Log.d(TAG, "Aido Keyboard destroyed")
    }
}
