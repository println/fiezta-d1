package proto.media.fiezta.features.domain.car.settings

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import proto.media.fiezta.R
import proto.media.fiezta.features.domain.car.browser.CarEditText
import proto.media.fiezta.features.domain.core.bookmarks.BookmarkUtils
import proto.media.fiezta.features.domain.core.browser.AutocompleteUtils
import proto.media.fiezta.features.domain.core.browser.BrowserStorageUtils
import proto.media.fiezta.features.domain.core.browser.SearchSuggestClient
import proto.media.fiezta.features.domain.core.browser.SuggestionMerger
import proto.media.fiezta.support.search.SearchEngine
import proto.media.fiezta.shared.search.texts
import proto.media.fiezta.features.domain.core.settings.SettingsUtils
import io.realm.Realm
import java.net.URI

class GoToAddressScreen(
    private val context: Context,
    private var engine: SearchEngine,
    private val engines: List<SearchEngine>,
    private val currentUrl: () -> String?,
    private val onTitleChanged: (Int) -> Unit,
    private val startWithVoice: Boolean,
    private val bindKeyboard: (CarEditText) -> Unit,
    private val unbindKeyboard: () -> Unit,
    private val startVoiceInput: (overlay: VoiceOverlay, onResult: (String) -> Unit, onInterrupted: () -> Unit) -> Unit,
    private val stopVoiceInput: () -> Unit,
    private val onClosedWithoutNavigation: () -> Unit,
    private val onAddressConfirmed: (input: String, search: (String) -> String) -> Unit
) : CarSettingsScreen {

    override val titleRes: Int
        get() = if (editingUrl) R.string.car_search_title_url else engine.texts.titleRes

    private val searchSuggestClient = SearchSuggestClient("go-to-address-suggest", SEARCH_DEBOUNCE_MILLIS)
    private lateinit var editText: CarEditText
    private lateinit var voiceOverlay: VoiceOverlay
    private lateinit var modes: View
    private lateinit var voiceButton: View
    private lateinit var voiceMode: View
    private lateinit var keyboardMode: View
    private lateinit var engineScroll: ScrollView
    private lateinit var urlButton: View
    private lateinit var engineButtons: Map<SearchEngine, View>
    private lateinit var suggestionRows: List<View>
    private lateinit var suggestionIcons: List<ImageView>
    private lateinit var suggestionTexts: List<TextView>
    private lateinit var voiceLabel: TextView
    private var inputMode = InputMode.NONE
        set(mode) {
            field = mode
            voiceMode.isActivated = mode == InputMode.VOICE
            keyboardMode.isActivated = mode == InputMode.KEYBOARD
        }
    private val listening get() = inputMode == InputMode.VOICE
    private var navigated = false
    private var editingUrl = false
    private val addressTextWatcher = AddressTextWatcher()

    override fun createView(inflater: LayoutInflater, parent: ViewGroup): View {
        val view = inflater.inflate(R.layout.car_settings_go_to_address, parent, false)
        editText = view.findViewById(R.id.go_to_address_edittext)
        voiceOverlay = VoiceOverlay(
            view.findViewById(R.id.go_to_address_voice_overlay),
            view.findViewById(R.id.go_to_address_speech_view),
            view.findViewById(R.id.go_to_address_voice_close)
        )
        voiceLabel = view.findViewById(R.id.go_to_address_voice_label)
        modes = view.findViewById(R.id.go_to_address_modes)
        val engineColumn = view.findViewById<ViewGroup>(R.id.go_to_address_engines)
        engineScroll = engineColumn.parent as ScrollView
        urlButton = createUrlButton(inflater, engineColumn)
        engineButtons = createEngineButtons(inflater, engineColumn)

        val suggestionsContainer = view.findViewById<ViewGroup>(R.id.go_to_address_suggestions)
        val rows = (0 until MAX_ROWS).map {
            inflater.inflate(R.layout.car_settings_address_suggestion_row, suggestionsContainer, false)
        }
        rows.forEach { suggestionsContainer.addView(it) }
        suggestionRows = rows
        suggestionIcons = rows.map { it.findViewById(R.id.suggestion_icon) }
        suggestionTexts = rows.map { it.findViewById(R.id.suggestion_text) }

        editText.addTextChangedListener(addressTextWatcher)
        editText.setOnClickListener { enterTextMode() }
        editText.setOnEditorActionListener { _, actionId, event -> onEditorAction(actionId, event) }

        voiceButton = view.findViewById(R.id.go_to_address_voice_button)
        voiceButton.setOnClickListener {
            if (listening) enterTextMode() else enterVoiceMode()
        }
        voiceMode = view.findViewById(R.id.go_to_address_voice_mode)
        voiceMode.setOnClickListener { enterVoiceMode() }
        keyboardMode = view.findViewById(R.id.go_to_address_keyboard_mode)
        keyboardMode.setOnClickListener { enterTextMode() }

        showSelection()
        renderRows(emptyList())
        return view
    }

    private fun createUrlButton(inflater: LayoutInflater, column: ViewGroup): View =
        createTile(
            inflater, column, R.layout.car_search_url_tile,
            R.drawable.ic_search_url, R.string.car_search_url, R.string.car_search_title_url
        ).apply {
            setOnClickListener {
                centerInColumn(this)
                selectUrl()
            }
        }

    private fun createEngineButtons(inflater: LayoutInflater, column: ViewGroup): Map<SearchEngine, View> =
        engines.associateWith { buttonEngine ->
            val texts = buttonEngine.texts
            createTile(inflater, column, R.layout.car_search_engine_tile, texts.iconRes, texts.nameRes, texts.titleRes)
                .apply {
                    setOnClickListener {
                        centerInColumn(this)
                        selectEngine(buttonEngine)
                    }
                }
        }

    private fun createTile(
        inflater: LayoutInflater,
        column: ViewGroup,
        layoutRes: Int,
        iconRes: Int,
        nameRes: Int,
        titleRes: Int
    ): View {
        val tile = inflater.inflate(layoutRes, column, false)
        tile.findViewById<ImageView>(R.id.engine_button_icon).setImageResource(iconRes)
        tile.findViewById<TextView>(R.id.engine_button_label).setText(nameRes)
        tile.contentDescription = context.getString(titleRes)
        column.addView(tile)
        return tile
    }

    private fun centerInColumn(tile: View) {
        val tileCenter = tile.top + tile.height / 2
        engineScroll.smoothScrollTo(0, tileCenter - engineScroll.height / 2)
    }

    private fun selectUrl() {
        if (editingUrl) return
        editingUrl = true
        showSelection()
        onTitleChanged(titleRes)
        showCurrentUrl()
        enterTextMode()
    }

    private fun showCurrentUrl() {
        val url = currentUrl().orEmpty()
        editText.removeTextChangedListener(addressTextWatcher)
        editText.setText(url)
        editText.setSelection(url.length)
        editText.addTextChangedListener(addressTextWatcher)
        renderRows(emptyList())
    }

    private fun selectEngine(selected: SearchEngine) {
        if (!editingUrl && selected == engine) return
        if (editingUrl) editText.text.clear()
        editingUrl = false
        engine = selected
        showSelection()
        onTitleChanged(titleRes)
        updateSuggestions(editText.text.toString())
        if (!listening) enterTextMode()
    }

    private fun showSelection() {
        val hintRes = if (editingUrl) R.string.car_search_hint_url else engine.texts.hintRes
        editText.hint = context.getString(hintRes)
        voiceLabel.setText(engine.texts.listeningRes)
        val voiceVisibility = if (editingUrl) View.GONE else View.VISIBLE
        voiceButton.visibility = voiceVisibility
        voiceMode.visibility = voiceVisibility
        highlight(urlButton, editingUrl)
        engineButtons.forEach { (buttonEngine, button) -> highlight(button, !editingUrl && buttonEngine == engine) }
    }

    private fun highlight(tile: View, isSelected: Boolean) {
        tile.isSelected = isSelected
        tile.isActivated = isSelected
        tile.findViewById<ImageView>(R.id.engine_button_icon).apply {
            colorFilter = if (isSelected) null else GRAYSCALE
            alpha = if (isSelected) 1f else UNSELECTED_ENGINE_ALPHA
        }
    }

    override fun onShown() {
        if (startWithVoice) enterVoiceMode() else enterTextMode()
    }

    override fun onHidden() {
        if (listening) stopVoiceInput()
        inputMode = InputMode.NONE
        unbindKeyboard()
        searchSuggestClient.shutdown()
        if (!navigated) onClosedWithoutNavigation()
    }

    private fun enterVoiceMode() {
        unbindKeyboard()
        inputMode = InputMode.VOICE
        startVoiceInput(
            voiceOverlay,
            { text ->
                inputMode = InputMode.NONE
                editText.setText(text)
                editText.setSelection(text.length)
                confirmText(text)
            },
            { inputMode = InputMode.NONE }
        )
    }

    private fun enterTextMode() {
        if (listening) stopVoiceInput()
        inputMode = InputMode.KEYBOARD
        bindKeyboard(editText)
        editText.requestFocus()
    }

    private fun onEditorAction(actionId: Int, event: KeyEvent?): Boolean {
        val isConfirmAction = actionId == EditorInfo.IME_ACTION_GO ||
            actionId == EditorInfo.IME_ACTION_DONE ||
            actionId == EditorInfo.IME_ACTION_SEARCH ||
            (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
        if (isConfirmAction) {
            confirmText(editText.text.toString())
            return true
        }
        return false
    }

    private fun confirmText(text: String) {
        if (text.isBlank()) {
            return
        }
        navigateTo(text)
    }

    private fun navigateTo(input: String) {
        navigated = true
        onAddressConfirmed(input) { engine.searchUrl(it) }
    }

    private fun updateSuggestions(typed: String) {
        val trimmed = typed.trim()
        if (trimmed.isEmpty()) {
            renderRows(emptyList())
            return
        }

        val inline = AutocompleteUtils.findMatch(context, trimmed.lowercase())?.let { match ->
            SuggestionMerger.Suggestion(SuggestionMerger.Type.INLINE_COMPLETION, match, "https://$match", match)
        }

        val history = BrowserStorageUtils.getRecordedHosts(context)
            .filter { it.contains(trimmed, ignoreCase = true) }
            .map { SuggestionMerger.Suggestion(SuggestionMerger.Type.HISTORY, it, "https://$it", it) }

        val bookmarks = queryBookmarks(trimmed)

        if (SettingsUtils.isSearchSuggestionsEnabled(context)) {
            searchSuggestClient.fetch(engine.suggestionsUrl(trimmed)) { results ->
                val search = results.map {
                    SuggestionMerger.Suggestion(SuggestionMerger.Type.SEARCH, it, engine.searchUrl(it), null)
                }
                renderRows(SuggestionMerger.merge(inline, history, bookmarks, search, MAX_ROWS))
            }
        } else {
            renderRows(SuggestionMerger.merge(inline, history, bookmarks, emptyList(), MAX_ROWS))
        }
    }

    private fun queryBookmarks(trimmed: String): List<SuggestionMerger.Suggestion> {
        val realm = Realm.getDefaultInstance()
        return try {
            BookmarkUtils.getBookmarks(realm)
                .filter { bookmark ->
                    bookmark.title?.contains(trimmed, ignoreCase = true) == true ||
                        bookmark.url?.contains(trimmed, ignoreCase = true) == true
                }
                .mapNotNull { bookmark ->
                    val url = bookmark.url ?: return@mapNotNull null
                    SuggestionMerger.Suggestion(SuggestionMerger.Type.BOOKMARK, bookmark.title ?: url, url, hostOf(url))
                }
        } finally {
            realm.close()
        }
    }

    private fun renderRows(suggestions: List<SuggestionMerger.Suggestion>) {
        modes.visibility = if (suggestions.isEmpty()) View.VISIBLE else View.INVISIBLE
        for (i in suggestionRows.indices) {
            val suggestion = suggestions.getOrNull(i)
            if (suggestion == null) {
                suggestionRows[i].visibility = View.GONE
                continue
            }
            suggestionRows[i].visibility = View.VISIBLE
            suggestionTexts[i].text = suggestion.text
            suggestionIcons[i].setImageResource(iconFor(suggestion.type))
            suggestionRows[i].setOnClickListener { navigateTo(suggestion.url) }
        }
    }

    private fun iconFor(type: SuggestionMerger.Type): Int = when (type) {
        SuggestionMerger.Type.INLINE_COMPLETION -> R.drawable.mozac_ic_globe
        SuggestionMerger.Type.HISTORY -> R.drawable.ic_car_history
        SuggestionMerger.Type.BOOKMARK -> R.drawable.ic_star
        SuggestionMerger.Type.SEARCH -> R.drawable.mozac_ic_search
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host
    } catch (e: Exception) {
        null
    }

    private inner class AddressTextWatcher : TextWatcher {
        private var lengthBeforeChange = 0
        private var autocompleting = false

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            lengthBeforeChange = s.length
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable) {
            if (autocompleting) {
                autocompleting = false
                return
            }
            val growing = s.length > lengthBeforeChange
            val cursorAtEnd = editText.selectionStart == s.length && editText.selectionEnd == s.length
            val typed = s.toString()
            if (growing && cursorAtEnd) {
                val match = AutocompleteUtils.findMatch(context, typed.lowercase())
                if (match != null && match.length > typed.length) {
                    autocompleting = true
                    s.append(match.substring(typed.length))
                    editText.setSelection(typed.length, s.length)
                }
            }
            updateSuggestions(typed)
        }
    }

    private enum class InputMode { NONE, VOICE, KEYBOARD }

    companion object {
        private const val MAX_ROWS = 6
        private const val UNSELECTED_ENGINE_ALPHA = 0.6f
        private val GRAYSCALE = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        private const val SEARCH_DEBOUNCE_MILLIS = 300L
    }
}
