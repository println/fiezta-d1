package proto.media.fiezta.features.domain.phone.browser

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import proto.media.fiezta.R
import proto.media.fiezta.features.domain.core.settings.SettingsUtils
import proto.media.fiezta.features.domain.core.settings.SettingsStorage
import proto.media.fiezta.features.domain.core.bookmarks.Bookmark
import proto.media.fiezta.features.domain.core.bookmarks.BookmarksClickCallback
import proto.media.fiezta.features.domain.phone.bookmarks.BookmarksFragment
import proto.media.fiezta.features.domain.phone.warnings.AppWarningResolver
import proto.media.fiezta.features.domain.phone.warnings.AppWarningRows
import proto.media.fiezta.features.domain.phone.warnings.PhoneSetupProbe
import proto.media.fiezta.features.domain.car.browser.CarCastUtils
import proto.media.fiezta.features.domain.core.browser.BrowserStorageUtils
import proto.media.fiezta.support.webviewex.VideoWebView
import proto.media.fiezta.support.webviewex.WebViewEx
import proto.media.fiezta.support.webviewex.WebViewExChromeClient
import proto.media.fiezta.support.webviewex.navigation.HostName
import java.util.concurrent.atomic.AtomicInteger
import proto.media.fiezta.features.domain.core.browser.AutocompleteUtils
import proto.media.fiezta.features.domain.core.browser.SearchSuggestClient
import proto.media.fiezta.support.search.SearchEngine
import proto.media.fiezta.support.plugins.model.PluginEnv
import proto.media.fiezta.support.plugins.model.Requirement
import proto.media.fiezta.support.plugins.injection.PluginInjector
import proto.media.fiezta.shared.plugins.AppPlugins
import proto.media.fiezta.support.webviewex.WebViewExConfig
import proto.media.fiezta.support.webviewex.WebViewExDelegate
import proto.media.fiezta.support.webviewex.LoadingState
import proto.media.fiezta.support.webviewex.WebViewExListener
import proto.media.fiezta.support.webviewex.document.DocumentStage
import proto.media.fiezta.support.webviewex.navigation.NavigationFailure
import proto.media.fiezta.support.webviewex.navigation.NavigationTransaction
import proto.media.fiezta.shared.search.SearchEngineSource
import proto.media.fiezta.shared.plugins.AndroidPluginLog
import proto.media.fiezta.shared.plugins.toPageStage
import proto.media.fiezta.shared.webviewex.AppWebViewExHost
import proto.media.fiezta.shared.webviewex.AppWebViewExTexts

class WebViewPhoneFragment : Fragment(), BookmarksClickCallback {

    private val TAG = "WebViewPhoneFragment"
    private lateinit var browser: WebViewEx<VideoWebView>
    private val webView: View
        get() = browser.screenView!!
    private lateinit var pluginInjector: PluginInjector
    private val googleSearchEngine: SearchEngine by lazy { SearchEngineSource(requireContext()).selector().fallback }
    private lateinit var editText: UrlEditText
    private lateinit var progressBar: ProgressBar
    private lateinit var eraseButton: ImageButton
    private lateinit var shieldView: ImageView
    private lateinit var clearButton: ImageButton
    private lateinit var castButton: ImageButton
    private lateinit var overflowButton: ImageButton
    private lateinit var rootLayout: View
    private lateinit var editOverlay: View
    private lateinit var homeWarnings: ViewGroup
    private val microphoneRequest: ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        warningResolver().onMicrophoneAnswered(granted)
        refreshWarnings()
    }

    private var barHasPage = false

    private var currentFavicon: Bitmap? = null

    private val blockedCount = AtomicInteger()

    private var pageHadError = false

    private lateinit var searchSuggestClient: SearchSuggestClient
    private lateinit var suggestionsContainer: View

    private lateinit var suggestionRows: Array<View>
    private lateinit var suggestionTextViews: Array<TextView>
    private lateinit var suggestionFillButtons: Array<View>

    private val sharedPrefs: SharedPreferences by lazy {
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_webview_phone, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rootLayout = view.findViewById(R.id.root_layout)
        progressBar = view.findViewById(R.id.progress_bar)
        editText = view.findViewById(R.id.edittext_url)
        eraseButton = view.findViewById(R.id.btn_erase)
        shieldView = view.findViewById(R.id.img_shield)
        clearButton = view.findViewById(R.id.btn_clear)
        castButton = view.findViewById(R.id.btn_send_to_car)
        overflowButton = view.findViewById(R.id.btn_overflow)
        editOverlay = view.findViewById(R.id.edit_overlay)
        homeWarnings = view.findViewById(R.id.home_warnings)
        suggestionsContainer = view.findViewById(R.id.suggestions_container)
        suggestionRows = arrayOf(
            view.findViewById(R.id.suggestion_row_0),
            view.findViewById(R.id.suggestion_row_1),
            view.findViewById(R.id.suggestion_row_2),
            view.findViewById(R.id.suggestion_row_3),
        )
        suggestionTextViews = Array(suggestionRows.size) { i -> suggestionRows[i].findViewById(R.id.suggestion_text) }
        suggestionFillButtons = Array(suggestionRows.size) { i -> suggestionRows[i].findViewById(R.id.suggestion_fill) }
        searchSuggestClient = SearchSuggestClient("search-suggest", PHONE_SUGGEST_DEBOUNCE_MILLIS, PHONE_SUGGEST_MAX_RESULTS)
        setupBarResources()
        val pluginSource = AppPlugins.source(requireActivity())
        val webViewContainer: ViewGroup = view.findViewById(R.id.container)
        val fullScreenView: ViewGroup = view.findViewById(R.id.full_screen_view)
        browser = WebViewEx(
            context = requireContext(),
            config = WebViewExConfig.phone { query -> googleSearchEngine.searchUrl(query) },
            host = AppWebViewExHost(requireContext()) { blockedCount.incrementAndGet() },
            texts = AppWebViewExTexts(requireContext()),
            delegate = object : WebViewExDelegate {},
            webViewFactory = { context -> VideoWebView(context) },
            chromeClientFactory = { WebViewExChromeClient() }
        )
        browser.observe(browserListener)
        browser.attach(view.findViewById(R.id.web_view_slot), requireActivity())
        webView.id = R.id.web_view
        val videoEnabledWebChromeClient = browser.chromeClient as WebViewExChromeClient
        browser.bindVideoChrome(webViewContainer, fullScreenView, ProgressBar(activity))
        videoEnabledWebChromeClient.protectedContentAllowed = {
            SettingsUtils.isProtectedContentAllowed(requireContext())
        }
        pluginInjector = PluginInjector(
            evaluate = browser.scripts::evaluate,
            source = pluginSource,
            env = PluginEnv.PHONE,
            isRequirementMet = { requirement, url ->
                when (requirement) {
                    Requirement.ADBLOCK -> SettingsStorage.isAdBlockEnabledForHost(requireActivity(), Uri.parse(url).host)
                }
            },
            isOptionEnabled = { pluginId, script, option ->
                val (key, default) = pluginSource.effectiveOptionKey(pluginId, script, option)
                SettingsUtils.isPluginOptionEnabled(requireActivity(), key, default)
            },
            onEventsCleared = {},
            log = AndroidPluginLog("PluginInjector")
        )
        showHome()
        webView.setOnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (browser.canGoBack()) {
                        browser.goBack()
                        return@setOnKeyListener true
                    } else if (webView.visibility == View.VISIBLE) {
                        showHome()
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
        editText.addTextChangedListener(object : android.text.TextWatcher {
            private var lengthBeforeChange = 0
            private var autocompleting = false

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                lengthBeforeChange = s.length
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: android.text.Editable) {
                if (autocompleting) {
                    autocompleting = false
                    return
                }
                if (!editText.hasFocus()) {
                    return
                }
                val growing = s.length > lengthBeforeChange
                val cursorAtEnd = editText.selectionStart == s.length && editText.selectionEnd == s.length
                if (growing && cursorAtEnd) {
                    val typed = s.toString()
                    val match = AutocompleteUtils.findMatch(requireActivity(), typed.lowercase())
                    if (match != null && match.length > typed.length) {
                        autocompleting = true
                        s.append(match.substring(typed.length))
                        editText.setSelection(typed.length, s.length)
                    }
                }
                fetchSuggestions(s.toString().substring(0, editText.selectionStart))
                updateClearButtonVisibility()
            }
        })
        editText.setBackListener {
            hideKeyboard(editText)
            editText.clearFocus()
            webView.requestFocus()
            true
        }
        editText.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            setEditModeActive(hasFocus)
            if (hasFocus) {
                editText.post { editText.selectAll() }
                updateClearButtonVisibility()
            } else {
                clearButton.visibility = View.GONE
                if (browser.url != null && "about:blank" != browser.url) {
                    editText.setText(browser.url)
                }
            }
        }
        clearButton.setOnClickListener {
            editText.setText("")
            editText.requestFocus()
        }
        shieldView.setOnClickListener {
            SiteInfoPopup.show(requireActivity(), browser.url, currentFavicon, blockedCount.get()) {
                updateShieldIcon()
            }
        }
        castButton.setOnClickListener {
            val url = browser.url
            if (url != null) {
                CarCastUtils.probeCar(requireActivity(), object : CarCastUtils.Callback {
                    override fun onResult(carActive: Boolean) {
                        if (!isAdded) {
                            return
                        }
                        if (carActive) {
                            showSendToCarDialog(url)
                        } else {
                            showCarInactiveDialog()
                        }
                    }
                })
            }
        }
        overflowButton.setOnClickListener {
            hideKeyboard(editText)
            editText.clearFocus()
            val hasPage = webView.visibility == View.VISIBLE
            BrowserMenuPopup.show(requireActivity(), overflowButton, browser, hasPage) {
                showBookmarksScreen()
            }
        }
        editOverlay.setOnClickListener {
            hideKeyboard(editText)
            editText.clearFocus()
            webView.requestFocus()
        }
        eraseButton.setOnClickListener {
            browser.stopLoading()
            browser.open("about:blank")
            browser.clearHistory()
            browser.clearFormData()
            sharedPrefs.edit().remove(HOME_URL).apply()
            showHome()
            Toast.makeText(activity, R.string.browser_erase_done, Toast.LENGTH_SHORT).show()
        }
        editText.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                onDone(editText)
                hideKeyboard(editText)
                editText.clearFocus()
                webView.visibility = View.VISIBLE
                webView.requestFocus()
                true
            } else {
                false
            }
        }
    }

    private fun showSendToCarDialog(url: String) {
        val builder = AlertDialog.Builder(requireActivity(), R.style.DialogStyle)
        val content = LayoutInflater.from(activity).inflate(R.layout.dialog_send_to_car, null)
        content.findViewById<TextView>(R.id.send_to_car_url).text = url
        builder.setView(content)
        builder.setCancelable(true)
        val dialog = builder.create()

        content.findViewById<View>(R.id.send_to_car_cancel).setOnClickListener {
            dialog.dismiss()
        }
        content.findViewById<View>(R.id.send_to_car_send).setOnClickListener {
            dialog.dismiss()
            CarCastUtils.castUrl(requireActivity(), url)
            Toast.makeText(activity, R.string.browser_send_to_car_done, Toast.LENGTH_SHORT).show()
        }
        dialog.show()
    }

    private fun showCarInactiveDialog() {
        val builder = AlertDialog.Builder(requireActivity(), R.style.DialogStyle)
        val content = LayoutInflater.from(activity).inflate(R.layout.dialog_car_inactive, null)
        builder.setView(content)
        builder.setCancelable(true)
        val dialog = builder.create()

        content.findViewById<View>(R.id.car_inactive_dismiss).setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showHome() {
        editText.setText("")
        webView.visibility = View.GONE
        updateBarState(false)
        editText.requestFocus()
        val root = view
        if (root != null && !root.hasWindowFocus()) {
            lateinit var listener: ViewTreeObserver.OnWindowFocusChangeListener
            listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                if (!hasFocus || !isAdded) {
                    return@OnWindowFocusChangeListener
                }
                root.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
                openUrlKeyboard()
            }
            root.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        } else {
            editText.postDelayed({
                if (isAdded) {
                    openUrlKeyboard()
                }
            }, 150)
        }
    }

    private fun updateShieldIcon() = updateShieldIcon(browser.url, false)

    private fun updateShieldIcon(url: String?, isError: Boolean = false) {
        val iconRes = if (url == null || !url.startsWith("https://")) {
            R.drawable.mozac_ic_shield_disabled
        } else if (isError) {
            R.drawable.mozac_ic_shield_exclamation_mark_24
        } else {
            val host = HostName.of(url)
            if (SettingsStorage.isAdBlockEnabledForHost(requireActivity(), host))
                R.drawable.mozac_ic_shield_checkmark_24
            else
                R.drawable.mozac_ic_shield_cross_24
        }
        shieldView.setImageDrawable(AppCompatResources.getDrawable(requireActivity(), iconRes))
    }

    private fun openUrlKeyboard() {
        editText.requestFocus()
        val imm = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        if (imm != null) {
            imm.restartInput(editText)
            imm.showSoftInput(editText, InputMethodManager.SHOW_FORCED)
        }
    }

    private fun setupBarResources() {
        val context = requireActivity()

        editText.typeface = ResourcesCompat.getFont(context, R.font.inter_regular)

        val iconTint = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primaryText))
        ImageViewCompat.setImageTintList(eraseButton, iconTint)
        ImageViewCompat.setImageTintList(shieldView, iconTint)
        ImageViewCompat.setImageTintList(clearButton, iconTint)
        ImageViewCompat.setImageTintList(castButton, iconTint)
        ImageViewCompat.setImageTintList(overflowButton, iconTint)
    }

    private fun updateBarState(hasPage: Boolean) {
        barHasPage = hasPage
        val visibility = if (hasPage) View.VISIBLE else View.GONE
        eraseButton.visibility = visibility
        shieldView.visibility = visibility
        castButton.visibility = visibility
    }

    private fun setEditModeActive(editing: Boolean) {
        val editingOverPage = editing && barHasPage
        editOverlay.visibility = if (editingOverPage) View.VISIBLE else View.GONE
        overflowButton.visibility = if (editingOverPage) View.GONE else View.VISIBLE
        if (editing) {
            eraseButton.visibility = View.GONE
            shieldView.visibility = View.GONE
            castButton.visibility = View.GONE
        } else {
            val visibility = if (barHasPage) View.VISIBLE else View.GONE
            eraseButton.visibility = visibility
            shieldView.visibility = visibility
            castButton.visibility = visibility
        }
        clearButton.setBackgroundResource(if (editing) R.drawable.clear_button_circle_background else 0)
        if (!editing) {
            showSuggestions("", null)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        searchSuggestClient.shutdown()
        browser.unbindVideoChrome()
        browser.destroy()
    }

    private fun updateClearButtonVisibility() {
        clearButton.visibility = if (editText.hasFocus() && editText.length() > 0) View.VISIBLE else View.GONE
    }

    private fun hideKeyboard(editTextURL: EditText) {
        val imm = editTextURL.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        imm?.hideSoftInputFromWindow(editTextURL.windowToken, 0)
    }

    private fun onDone(editText: EditText) {
        browser.navigate(editText.text.toString())
    }

    private fun open(url: String) {
        browser.open(url)
    }

    override fun onResume() {
        super.onResume()
        webView.requestFocus()
        refreshWarnings()
    }

    private fun refreshWarnings() {
        AppWarningRows.show(homeWarnings, PhoneSetupProbe.read(requireContext()).warnings) { warningResolver().resolve(it) }
    }

    private fun warningResolver(): AppWarningResolver =
        AppWarningResolver(requireActivity()) { microphoneRequest.launch(Manifest.permission.RECORD_AUDIO) }

    override fun onPause() {
        super.onPause()
        browser.flushCookies()
    }

    override fun onBookmarkSelected(bookmark: Bookmark) {
        open(bookmark.url!!)
    }

    override fun onBookmarkFragmentClose() {
        hideBookmarksScreen()
    }

    private fun hideBookmarksScreen() {
        if (isAdded) {
            requireView().findViewById<View>(R.id.full_screen_view).visibility = View.GONE
            val childFragmentManager = childFragmentManager
            val oldFragment = childFragmentManager.findFragmentByTag(BOOKMARKS_FRAGMENT_TAG)
            if (oldFragment != null) {
                childFragmentManager.beginTransaction()
                    .remove(oldFragment)
                    .setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out)
                    .commitAllowingStateLoss()
            }
        }
        webView.requestFocus()
    }

    private fun showBookmarksScreen() {
        if (isAdded && view != null) {
            requireView().findViewById<View>(R.id.full_screen_view).visibility = View.VISIBLE
            val childFragmentManager = childFragmentManager
            val bookmarksFragment = childFragmentManager.findFragmentByTag(BOOKMARKS_FRAGMENT_TAG) as? BookmarksFragment
                ?: BookmarksFragment()
            childFragmentManager.beginTransaction()
                .replace(R.id.full_screen_view, bookmarksFragment, BOOKMARKS_FRAGMENT_TAG)
                .setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out)
                .commitAllowingStateLoss()
        }
    }

    private fun fetchSuggestions(query: String) {
        if (!editText.hasFocus()) {
            showSuggestions("", null)
            return
        }
        if (!SettingsUtils.isSearchSuggestionsEnabled(requireActivity()) || query.trim().isEmpty()) {
            showSuggestions(query, null)
            return
        }
        searchSuggestClient.fetch(googleSearchEngine.suggestionsUrl(query)) { suggestions ->
            if (isAdded && editText.hasFocus()) {
                showSuggestions(query, suggestions)
            }
        }
    }

    private fun showSuggestions(query: String?, networkSuggestions: List<String>?) {
        val literal = query?.trim() ?: ""
        val any = literal.isNotEmpty()
        suggestionsContainer.visibility = if (any) View.VISIBLE else View.GONE
        if (!any) {
            return
        }
        for (i in suggestionRows.indices) {
            val isLiteral = i == 0
            val suggestion: String? = when {
                isLiteral -> literal
                networkSuggestions != null && i - 1 < networkSuggestions.size -> networkSuggestions[i - 1]
                else -> null
            }
            if (suggestion == null) {
                suggestionRows[i].visibility = View.GONE
                continue
            }
            suggestionRows[i].visibility = View.VISIBLE
            suggestionTextViews[i].text = suggestion
            suggestionRows[i].setOnClickListener {
                hideKeyboard(editText)
                open(googleSearchEngine.searchUrl(suggestion))
                editText.clearFocus()
                webView.visibility = View.VISIBLE
                webView.requestFocus()
            }
            suggestionFillButtons[i].visibility = if (isLiteral) View.GONE else View.VISIBLE
            suggestionFillButtons[i].setOnClickListener {
                editText.setText(suggestion)
                editText.setSelection(suggestion.length)
            }
        }
    }

    private val browserListener = object : WebViewExListener {

        override fun onMainFrameRequested(url: String) {
            editText.setText(url)
        }

        override fun onNavigationStarted(
            transaction: NavigationTransaction, url: String, favicon: Bitmap?, isErrorDocument: Boolean
        ) {
            currentFavicon = favicon
            blockedCount.set(0)
            if (!isErrorDocument) {
                pageHadError = false
            }
            if ("about:blank" == url) {
                return
            }
            webView.visibility = View.VISIBLE
            updateBarState(true)
        }

        override fun onPageFinished(url: String?, isErrorDocument: Boolean) {
            if ("about:blank" == url) {
                return
            }
            editText.setText(url)
            updateShieldIcon(url, pageHadError)
            Log.d(TAG, "page finished $url")
            sharedPrefs.edit().putString(HOME_URL, url).commit()
            BrowserStorageUtils.recordHost(requireActivity(), url)
            if (url != null && (url.contains("accounts.google.com") ||
                    url.contains("youtube.com") ||
                    url.contains("google.com"))
            ) {
                browser.flushCookies()
            }
        }

        override fun onNavigationFailed(transaction: NavigationTransaction, failure: NavigationFailure) {
            pageHadError = true
            updateShieldIcon(transaction.currentUrl, true)
        }

        override fun onLoadingChanged(state: LoadingState) {
            progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
            progressBar.isIndeterminate = state.progress <= 0
            if (state.progress > 0) progressBar.progress = state.progress
        }

        override fun onDocumentStage(stage: DocumentStage, documentId: String, url: String?) =
            pluginInjector.onPageStage(stage.toPageStage(), url)

        override fun onSchemeFallback(url: String, host: String?) {
            if (!isAdded) return
            AlertDialog.Builder(requireActivity(), R.style.DialogStyle)
                .setMessage(R.string.browser_http_fallback_notice)
                .setPositiveButton(R.string.browser_http_fallback_keep_https) { _, _ ->
                    browser.pinToHttps(host)
                    browser.open(Uri.parse(url).buildUpon().scheme("https").build().toString())
                }
                .setNegativeButton(android.R.string.ok, null)
                .show()
        }

        override fun onExternalLinkBlocked(url: String, failure: NavigationFailure) {
            Toast.makeText(activity, R.string.browser_external_app_missing, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val HOME_URL = "home_url"
        private const val BOOKMARKS_FRAGMENT_TAG = "bookmarks"
        private const val PREFS = "phone_prefs"
        private const val PHONE_SUGGEST_DEBOUNCE_MILLIS = 300L
        private const val PHONE_SUGGEST_MAX_RESULTS = 4
    }
}
