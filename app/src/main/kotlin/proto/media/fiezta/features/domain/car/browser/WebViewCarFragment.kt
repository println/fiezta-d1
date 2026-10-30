package proto.media.fiezta.features.domain.car.browser

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.support.car.Car
import android.support.car.CarConnectionCallback
import android.support.car.CarInfoManager
import android.support.car.hardware.CarSensorManager
import android.support.v4.content.ContextCompat
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import com.github.zagum.speechrecognitionview.RecognitionProgressView
import proto.media.fiezta.BuildConfig
import proto.media.fiezta.R
import proto.media.fiezta.config.wiring.MyApplication
import proto.media.fiezta.features.domain.core.settings.SettingsUtils
import proto.media.fiezta.features.domain.core.settings.CarToolbarAutoHide
import proto.media.fiezta.features.domain.core.settings.CarMenuBubblePlacement
import proto.media.fiezta.features.domain.core.settings.FullscreenControlsPlacement
import proto.media.fiezta.features.domain.core.settings.ScreenSide
import proto.media.fiezta.features.domain.core.settings.ToolbarPosition
import proto.media.fiezta.features.domain.car.settings.CarRealtimeSettingsSync
import proto.media.fiezta.features.domain.car.settings.VoiceOverlay
import proto.media.fiezta.features.domain.car.app.CarFragment
import proto.media.fiezta.features.domain.car.app.MainCarActivity
import proto.media.fiezta.features.domain.car.app.SafetyWarningFragment
import proto.media.fiezta.features.domain.car.player.CarPlayer
import proto.media.fiezta.features.domain.car.bookmarks.CarFavoritesBarAdapter
import proto.media.fiezta.features.domain.car.settings.BookmarksScreen
import proto.media.fiezta.features.domain.car.settings.CarSettingsNavigator
import proto.media.fiezta.features.domain.car.settings.CarSettingsPanel
import proto.media.fiezta.features.domain.car.settings.CarSettingsPanelLayout
import proto.media.fiezta.features.domain.car.settings.showCarToast
import proto.media.fiezta.features.domain.car.settings.GoToAddressScreen
import proto.media.fiezta.features.domain.core.bookmarks.Bookmark
import proto.media.fiezta.features.domain.core.bookmarks.BookmarkUtils
import proto.media.fiezta.features.domain.core.bookmarks.SiteColorStore
import proto.media.fiezta.support.search.SearchEngine
import proto.media.fiezta.support.search.SearchEngineSelector
import proto.media.fiezta.support.media.contract.ClientHandle
import proto.media.fiezta.support.media.contract.MediaClient
import proto.media.fiezta.support.media.dto.MediaEventDto
import proto.media.fiezta.support.webviewex.WebViewExChromeClient
import proto.media.fiezta.shared.search.SearchEngineSource
import proto.media.fiezta.support.system.DrmDiagnostics
import io.realm.OrderedRealmCollectionChangeListener
import io.realm.Realm
import io.realm.RealmResults
import java.util.Locale

class WebViewCarFragment : CarFragment(), MainCarActivity.ActivityCallbacks,
    SafetyWarningFragment.FragmentInteractionListener, CarPlayer.UiCallbacks, MediaClient {

    private var handlerThread: HandlerThread? = null
    private var isParkingEngaged = false
    private var car: Car? = null
    private val webView: View
        get() = CarPlayer.browser.screenView!!
    private var webViewBound = false
    private val searchEngines: SearchEngineSelector by lazy { SearchEngineSource(context!!).selector() }
    private var speechRecognizer: SpeechRecognizer? = null
    private var activeRecognitionProgressView: RecognitionProgressView? = null
    private var activeVoiceOverlay: View? = null
    private lateinit var progressBar: ProgressBar
    private lateinit var toolbar: View
    private val toolbarHideRunnable = Runnable { hideToolbar() }
    private var fullScreenToggleFromPanel = false
    private var swallowingVideoTouch = false
    private val panelToggleExpiryRunnable = Runnable { fullScreenToggleFromPanel = false }
    private var warningAccepted = false
    private var currentAspectRatio: AspectRatio? = null
    private lateinit var handler: Handler
    private var mediaHandle: ClientHandle? = null
    private val isPlaying: Boolean
        get() = mediaHandle?.state?.audible == true
    private val sharedPrefs: SharedPreferences by lazy { context!!.getSharedPreferences(CarPlayer.PREFS, Context.MODE_PRIVATE) }
    private lateinit var cornerControls: View
    private val cornerControlsHideRunnable = Runnable { hideCornerControls(true) }
    private lateinit var cornerControlsHandle: View
    private var draggingCornerControls = false
    private var dragDownRawX = 0f
    private var dragDownRawY = 0f
    private var dragStartLeft = 0f
    private var dragStartTop = 0f
    private lateinit var aspectRatioGroup: RadioGroup
    private var voiceButton: ImageButton? = null
    private lateinit var settingsPanel: CarSettingsPanelLayout
    private lateinit var settingsScreen: View
    private lateinit var settingsScreenTitle: TextView
    private lateinit var settingsNavigator: CarSettingsNavigator
    private lateinit var carSettingsPanel: CarSettingsPanel
    private lateinit var container: LinearLayout
    private lateinit var toolbarWrapper: View
    private lateinit var webViewContainer: View
    private lateinit var webViewSlot: FrameLayout
    private lateinit var rootLayout: FrameLayout
    private lateinit var menuBubble: CarMenuBubble
    private lateinit var menuBubbleView: ImageView
    private lateinit var realtimeSettingsSync: CarRealtimeSettingsSync
    private lateinit var favoritesBar: RecyclerView
    private lateinit var favoritesBarAdapter: CarFavoritesBarAdapter
    private var favoritesBarBookmarks: RealmResults<Bookmark>? = null
    private var favoritesBarRealm: Realm? = null
    private val siteColorListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        favoritesBarAdapter.notifyDataSetChanged()
    }
    private var appliedZoomPercent = 0

    private var toolbarPosition = ToolbarPosition.LEFT
    private var driverSide = ScreenSide.LEFT
    private var mirroredSide = ScreenSide.LEFT
    private var autoHideEnabled = false
    private var bookmarksBarEnabled = false
    private var warningScreenOpen = false
    private val sensorsListener = CarSensorManager.OnSensorChangedListener { sensorManager, ev ->
        try {
            if (ev.sensorType == CarSensorManager.SENSOR_TYPE_PARKING_BRAKE) {
                val parkingBrakeData = ev.parkingBrakeData
                if (parkingBrakeData != null) {
                    setParkingBrake(parkingBrakeData.isEngaged)
                }
            }
        } catch (e: Error) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    private val carConnectionCallback = object : CarConnectionCallback() {
        override fun onConnected(car: Car) {
            rememberDriverSide(car)
            try {
                val sensorManager = car.getCarManager(Car.SENSOR_SERVICE) as CarSensorManager
                this@WebViewCarFragment.car = car
                sensorManager.addListener(
                    sensorsListener, CarSensorManager.SENSOR_TYPE_PARKING_BRAKE,
                    CarSensorManager.SENSOR_RATE_NORMAL
                )

                if (sensorManager.isSensorSupported(CarSensorManager.SENSOR_TYPE_PARKING_BRAKE)) {
                    val ds = sensorManager.getLatestSensorEvent(CarSensorManager.SENSOR_TYPE_PARKING_BRAKE)
                    if (ds != null) {
                        sensorsListener.onSensorChanged(sensorManager, ds)
                    } else {
                        setParkingBrake(false)
                    }
                } else {
                    setParkingBrake(false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                setParkingBrake(false)
            }
        }

        override fun onDisconnected(car: Car) {
            Log.d(TAG, "Disconnected from car")
        }
    }
    private lateinit var fakeEditText: CarEditText

    private fun rememberDriverSide(car: Car) {
        val side = readDriverSide(car) ?: return
        context?.let { SettingsUtils.setCarReportedDriverSide(it, side) }
    }

    private fun readDriverSide(car: Car): ScreenSide? = try {
        val driverPosition = (car.getCarManager(Car.INFO_SERVICE) as CarInfoManager).driverPosition
        Log.d(TAG, "Driver position reported by the car: $driverPosition")
        when (driverPosition) {
            CarInfoManager.DRIVER_SIDE_LEFT, CarInfoManager.DRIVER_SIDE_CENTER -> ScreenSide.LEFT
            CarInfoManager.DRIVER_SIDE_RIGHT -> ScreenSide.RIGHT
            else -> null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Driver position unavailable", e)
        null
    }

    private var castUrlReceiver: BroadcastReceiver? = null
    private val instanceId = Integer.toHexString(System.identityHashCode(this))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "LIFECYCLE[$instanceId] onCreate")
        val thread = HandlerThread("autosuggest")
        handlerThread = thread
        thread.start()
        handler = Handler(thread.looper)
    }

    override fun onAttach(context: Context?) {
        super.onAttach(context)
        val newCar = Car.createCar(context, carConnectionCallback)
        newCar.connect()

        castUrlReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (!webViewBound) {
                    return
                }
                if (CarCastUtils.ACTION_CAST_PING == intent.action) {
                    resultCode = Activity.RESULT_OK
                    return
                }
                val pendingUrl = sharedPrefs.getString(CarCastUtils.KEY_CAST_PENDING_URL, null)
                if (pendingUrl != null) {
                    sharedPrefs.edit().remove(CarCastUtils.KEY_CAST_PENDING_URL).apply()
                    CarPlayer.browser.open(pendingUrl)
                }
            }
        }
        val castFilter = IntentFilter(CarCastUtils.ACTION_CAST_URL)
        castFilter.addAction(CarCastUtils.ACTION_CAST_PING)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context!!.registerReceiver(castUrlReceiver, castFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context!!.registerReceiver(castUrlReceiver, castFilter)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_webview_car, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "LIFECYCLE[$instanceId] onViewCreated")

        if (BuildConfig.DEBUG) {
            DrmDiagnostics.log()
        }

        val mainCarActivity = context as MainCarActivity
        webViewSlot = view.findViewById(R.id.web_view_slot)
        progressBar = view.findViewById(R.id.progress_bar)
        CarPlayer.attach(webViewSlot, mainCarActivity, this); webViewBound = true
        mainCarActivity.setIgnoreConfigChanges(
            ActivityInfo.CONFIG_ORIENTATION
                or ActivityInfo.CONFIG_SCREEN_SIZE
                or ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE
                or ActivityInfo.CONFIG_SCREEN_LAYOUT
                or ActivityInfo.CONFIG_KEYBOARD_HIDDEN
                or ActivityInfo.CONFIG_UI_MODE
                or ActivityInfo.CONFIG_NAVIGATION
                or ActivityInfo.CONFIG_DENSITY
        )

        fakeEditText = view.findViewById(R.id.fake_edittext)
        setupEditTextMirroring(fakeEditText)

        val carUiController = mainCarActivity.carUiController

        mainCarActivity.getWindow().volumeControlStream = AudioManager.STREAM_MUSIC
        val backButton = view.findViewById<ImageButton>(R.id.back_button)
        backButton.setOnClickListener { goBack() }
        settingsPanel = view.findViewById(R.id.settings_panel)
        settingsPanel.clipToOutline = true
        settingsScreen = view.findViewById(R.id.settings_screen)
        settingsScreenTitle = view.findViewById(R.id.settings_screen_title)
        val settingsScreenBody = view.findViewById<FrameLayout>(R.id.settings_screen_body)
        settingsNavigator = CarSettingsNavigator(
            settingsPanel, view.findViewById(R.id.settings_panel_outside), settingsScreen, settingsScreenBody,
            settingsScreenTitle, LayoutInflater.from(context), mirroredToDriver = { mirroredSide == ScreenSide.RIGHT },
            onPanelClosedWhenIdle = { scheduleAutoHide() }
        )
        carSettingsPanel = CarSettingsPanel(
            settingsPanel, CarPlayer.browser, { CarPlayer.host },
            object : CarSettingsPanel.Callbacks {
                override fun isFullScreen(): Boolean = CarPlayer.browser.isVideoFullscreen()
                override fun toggleFullScreen() = this@WebViewCarFragment.toggleFullScreen()
            }
        )

        view.findViewById<ImageButton>(R.id.settings_button).setOnClickListener {
            settingsNavigator.togglePanel()
            if (settingsNavigator.isPanelOpen()) {
                carSettingsPanel.refresh()
            }
        }
        view.findViewById<ImageButton>(R.id.settings_panel_close).setOnClickListener { settingsNavigator.closeAll() }
        view.findViewById<ImageButton>(R.id.settings_screen_close).setOnClickListener { settingsNavigator.closeAll() }
        view.findViewById<ImageButton>(R.id.settings_screen_back).setOnClickListener { settingsNavigator.back() }
        view.findViewById<View>(R.id.settings_open_bookmarks).setOnClickListener {
            settingsNavigator.open(
                BookmarksScreen(
                    context!!,
                    currentUrl = { CarPlayer.browser.url },
                    onAddCurrentSite = { BookmarkUtils.addBookmark(CarPlayer.browser) },
                    onBookmarkSelected = { url -> CarPlayer.browser.open(url); settingsNavigator.closeAll() }
                )
            )
        }
        view.findViewById<View>(R.id.settings_open_go_to_address).setOnClickListener {
            openSearchScreen(searchEngines.fallback, startWithVoice = false)
        }
        val homeButton = view.findViewById<ImageButton>(R.id.home_button)
        homeButton.setOnClickListener { CarPlayer.browser.open(SettingsUtils.getHomeUrl(context!!)) }
        cornerControls = view.findViewById(R.id.fullscreen_corner_controls)
        cornerControlsHandle = view.findViewById(R.id.fullscreen_controls_handle)
        cornerControlsHandle.setOnTouchListener { _, event -> onCornerControlsHandleTouch(event) }

        aspectRatioGroup = view.findViewById(R.id.aspect_ratio_group)
        ASPECT_RATIO_OPTIONS.forEach { (optionId, aspectRatio) ->
            val option = view.findViewById<View>(optionId)
            option.setOnClickListener {
                setAspectRatio(aspectRatio)
                keepFullscreenControlsVisible()
            }
            option.setOnFocusChangeListener { _, _ -> keepFullscreenControlsVisible() }
        }
        val exitFullscreenButton = view.findViewById<View>(R.id.exit_fullscreen_button)
        exitFullscreenButton.setOnClickListener { exitFullscreen() }
        exitFullscreenButton.setOnFocusChangeListener { _, _ -> keepFullscreenControlsVisible() }
        (ASPECT_RATIO_OPTIONS.map { it.first } + R.id.exit_fullscreen_button).forEach {
            enlargeTopIcon(view.findViewById(it))
        }
        val refreshButton = view.findViewById<ImageButton>(R.id.refresh_button)
        refreshButton.setOnClickListener { CarPlayer.browser.reload() }
        val voiceButton = view.findViewById<ImageButton>(R.id.voice_button)
        this.voiceButton = voiceButton
        voiceButton.setOnClickListener { openSearchScreen(searchEngines.forUrl(CarPlayer.browser.url), startWithVoice = true) }

        handleVoiceVisibility()
        toolbar = view.findViewById(R.id.toolbar)
        container = view.findViewById(R.id.container)
        toolbarWrapper = view.findViewById(R.id.toolbar_wrapper)
        webViewContainer = view.findViewById(R.id.webview_container)
        rootLayout = view.findViewById(R.id.root_layout)
        rootLayout.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (cornerControls.visibility == View.VISIBLE && !draggingCornerControls) {
                resolveCornerControlsOverlap()
            }
        }
        menuBubbleView = view.findViewById(R.id.menu_bubble)
        menuBubble = CarMenuBubble(
            menuBubbleView,
            onTap = { toggleToolbarFromBubble() },
            onMoved = { x, y -> SettingsUtils.setCarMenuBubblePosition(context!!, x, y) }
        )
        autoHideEnabled = SettingsUtils.isCarToolbarAutoHide(context!!)
        bookmarksBarEnabled = SettingsUtils.isCarBookmarksBarVisible(context!!)

        favoritesBar = view.findViewById(R.id.car_favorites_bar)
        favoritesBar.layoutManager = LinearLayoutManager(context)
        favoritesBarAdapter = CarFavoritesBarAdapter(emptyList())
        favoritesBarAdapter.setOnBookmarkSelected { bookmark -> bookmark.url?.let { CarPlayer.browser.open(it) } }
        favoritesBar.adapter = favoritesBarAdapter
        SiteColorStore.registerChangeListener(context!!, siteColorListener)
        val favoritesRealm = Realm.getDefaultInstance()
        favoritesBarRealm = favoritesRealm
        BookmarkUtils.seedDefaultFavoritesOnce(context!!, favoritesRealm)
        val favoritesBookmarks = BookmarkUtils.getBookmarks(favoritesRealm)
        favoritesBarBookmarks = favoritesBookmarks
        favoritesBarAdapter.setBookmarks(favoritesBookmarks.toMutableList())
        favoritesBarAdapter.notifyDataSetChanged()
        favoritesBookmarks.addChangeListener(OrderedRealmCollectionChangeListener<RealmResults<Bookmark>> { results, _ ->
            if (isAdded && context != null) {
                favoritesBarAdapter.setBookmarks(results.toMutableList())
                favoritesBarAdapter.notifyDataSetChanged()
            }
        })
        handler.post {
            val aspectRatio = sharedPrefs.getInt(ASPECT_RATIO_KEY, 0)
            Handler(Looper.getMainLooper()).post {
                if (AspectRatio.values().size > aspectRatio) {
                    setAspectRatio(AspectRatio.values()[aspectRatio])
                }
            }
        }

        bindWebView()
        carUiController.statusBarController.hideAppHeader()

        driverSide = SettingsUtils.getCarDriverSide(context!!)
        mirrorToDriverSide()
        applyToolbarPosition(storedToolbarPosition())
        if (autoHideEnabled) {
            toolbarWrapper.post { hideToolbarImmediate() }
        }

        realtimeSettingsSync = CarRealtimeSettingsSync(
            context!!,
            onPositionChanged = { view.post { refreshToolbarPlacement(view as ViewGroup) } },
            onAutoHideChanged = { enabled ->
                view.post { setAutoHideEnabled(enabled) }
            },
            onBookmarksBarChanged = { visible ->
                view.post { setBookmarksBarEnabled(visible) }
            },
            onZoomChanged = { percent ->
                view.post {
                    if (percent != appliedZoomPercent) {
                        applyZoom(percent)
                        showCarToast(view, getString(R.string.car_settings_zoom_percent, percent))
                    }
                }
            }
        )
        realtimeSettingsSync.register()

        mainCarActivity.addActivityCallback(this)
    }

    private fun storedToolbarPosition(): ToolbarPosition =
        ToolbarPosition.parse(SettingsUtils.getCarToolbarPosition(context!!), driverSide)

    private fun refreshToolbarPlacement(root: ViewGroup) {
        val previousPosition = toolbarPosition
        val previousDriverSide = driverSide
        driverSide = SettingsUtils.getCarDriverSide(context!!)
        val position = storedToolbarPosition()
        if (position == previousPosition && driverSide == previousDriverSide) return
        SettingsUtils.clearCarFullscreenControlsPosition(context!!)
        android.transition.TransitionManager.beginDelayedTransition(
            root,
            android.transition.AutoTransition().excludeTarget(menuBubbleView, true)
        )
        mirrorToDriverSide()
        applyToolbarPosition(position)
        if (position != previousPosition) {
            showCarToast(root, getString(R.string.car_settings_toolbar_position, getHumanText(position)))
        }
    }

    private fun mirrorToDriverSide() {
        if (mirroredSide == driverSide) return
        DriverSideMirror.flip(rootLayout)
        mirroredSide = driverSide
    }

    private fun bindWebView() {
        webView.id = R.id.web_view
        val fullScreenVideoView = view!!.findViewById<ViewGroup>(R.id.full_screen_view)
        val videoEnabledWebChromeClient = CarPlayer.browser.chromeClient as WebViewExChromeClient
        CarPlayer.browser.bindVideoChrome(webViewContainer, fullScreenVideoView, ProgressBar(context))
        videoEnabledWebChromeClient.protectedContentAllowed = {
            SettingsUtils.isProtectedContentAllowed(context!!)
        }
        appliedZoomPercent = SettingsUtils.getZoomPercent(context!!)
        CarPlayer.browser.setInitialScale(appliedZoomPercent)
        if (resources.getBoolean(R.bool.isNight)) {
            webView.setBackgroundColor(Color.BLACK)
        }
        webView.requestFocus()
        webView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && canAutoHideNow() && isToolbarShown()) {
                hideToolbar()
            }
            false
        }
        webView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP) {
                when (keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        if (settingsNavigator.back()) return@setOnKeyListener true
                        if (goBack()) return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> if (CarPlayer.browser.isVideoFullscreen()) {
                        showAndHideToolbarAnimation()
                        showAndHideCornerControlsAnimation()
                        return@setOnKeyListener true
                    }
                }
                false
            } else {
                false
            }
        }

        val carUiController = (context as MainCarActivity).carUiController
        videoEnabledWebChromeClient.setOnToggledFullscreen(object : WebViewExChromeClient.ToggledFullscreenCallback {
            override fun toggledFullscreen(fullscreen: Boolean) {
                if (fullscreen) {
                    carUiController.statusBarController.hideAppHeader()
                    carUiController.statusBarController.hideConnectivityLevel()
                }
                if (!isAdded) {
                    fullScreenToggleFromPanel = false
                    return
                }
                carSettingsPanel.refresh()
                if (fullScreenToggleFromPanel) {
                    fullScreenToggleFromPanel = false
                    showCarToast(
                        toolbar,
                        getString(
                            if (fullscreen) R.string.car_settings_fullscreen_on
                            else R.string.car_settings_fullscreen_off
                        )
                    )
                }
                if (fullscreen) {
                    hideToolbar()
                    hideCornerControls(false)
                    favoritesBar.visibility = View.GONE
                    refreshMenuBubbleVisibility()
                } else {
                    showToolbar()
                    hideCornerControls(false)
                    if (bookmarksBarEnabled) favoritesBar.visibility = View.VISIBLE
                    refreshMenuBubbleVisibility()
                }
            }
        })
        videoEnabledWebChromeClient.setVideoTouchListener { _, event -> onFullscreenVideoTouch(event) }
    }

    override fun onWebViewRecreated() {
        bindWebView()
        applyToolbarPosition(toolbarPosition)
    }

    private fun setupEditTextMirroring(fakeEditText: CarEditText) {
        fakeEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                CarPlayer.browser.enterKeyboardText(fakeEditText.text.toString())
            }

            override fun afterTextChanged(s: Editable) {
            }
        })

        fakeEditText.setOnEditorActionListener { _, actionId, event ->
            // The head unit keyboard doesn't always send IME_ACTION_DONE: it depends on the
            // imeOptions inferred from the page's field, and some units send only a raw Enter.
            val isConfirmAction = actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_NEXT ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN)
            if (isConfirmAction) {
                CarPlayer.browser.sendKeyboardEnter()
                onHideKeyboardFromJS()
            }
            false
        }
    }

    private fun keepFullscreenControlsVisible() {
        showAndHideCornerControlsAnimation()
        showAndHideToolbarAnimation()
    }

    private fun exitFullscreen() {
        CarPlayer.browser.exitFullScreen()
    }

    private fun openSearchScreen(engine: SearchEngine, startWithVoice: Boolean) {
        val mainCarActivity = context as MainCarActivity
        settingsNavigator.open(
            GoToAddressScreen(
                context!!,
                engine = engine,
                engines = searchEngines.engines,
                currentUrl = { CarPlayer.browser.url },
                onTitleChanged = { settingsNavigator.setTitle(it) },
                startWithVoice = startWithVoice,
                bindKeyboard = { editText -> mainCarActivity.a().startInput(editText) },
                unbindKeyboard = { mainCarActivity.a().stopInput() },
                startVoiceInput = { overlay, onResult, onInterrupted -> startVoiceInput(overlay, onResult, onInterrupted) },
                stopVoiceInput = { stopVoiceInput(resumeVideo = false) },
                onClosedWithoutNavigation = { CarPlayer.browser.resumeMediaIfInterrupted() },
                onAddressConfirmed = { input, search -> CarPlayer.browser.navigate(input, search); settingsNavigator.closeAll() }
            )
        )
    }

    private fun refreshMenuBubbleVisibility() {
        if (autoHideEnabled && !CarPlayer.browser.isVideoFullscreen()) menuBubble.show() else menuBubble.hide()
    }

    private fun toggleFullScreen() {
        fullScreenToggleFromPanel = true
        toolbar.removeCallbacks(panelToggleExpiryRunnable)
        toolbar.postDelayed(panelToggleExpiryRunnable, PANEL_TOGGLE_FEEDBACK_TIMEOUT_MILLIS)
        if (CarPlayer.browser.isVideoFullscreen()) CarPlayer.browser.exitFullScreen() else CarPlayer.browser.requestFullScreen()
    }

    private fun pauseForVoiceInput() {
        if (!isPlaying) return
        mediaHandle?.pause()
        CarPlayer.browser.markMediaInterrupted()
    }

    private fun startVoiceInput(overlay: VoiceOverlay, onResult: (String) -> Unit, onInterrupted: () -> Unit) {
        if (speechRecognizer != null) {
            stopVoiceInput(resumeVideo = false)
        }
        pauseForVoiceInput()
        val ctx = context!!
        val voiceOverlay = overlay.container
        val recognitionProgressView = overlay.progressView
        activeVoiceOverlay = voiceOverlay
        activeRecognitionProgressView = recognitionProgressView
        val exitVoiceMode = View.OnClickListener {
            stopVoiceInput(resumeVideo = false)
            onInterrupted()
        }
        recognitionProgressView.setOnClickListener(exitVoiceMode)
        overlay.closeButton.setOnClickListener(exitVoiceMode)

        val newRecognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
        val listener = FinalResultRecognitionListener(object : FinalResultRecognitionListener.Callbacks {
            override fun onFinalResult(text: String) {
                if (speechRecognizer !== newRecognizer) return
                stopVoiceInput(resumeVideo = false)
                onResult(text)
            }

            override fun onError(error: Int) {
                if (speechRecognizer !== newRecognizer) return
                showCarToast(voiceOverlay, getString(voiceErrorMessage(error)))
                stopVoiceInput(resumeVideo = false)
                onInterrupted()
            }
        })
        recognitionProgressView.setSpeechRecognizer(newRecognizer)
        recognitionProgressView.setRecognitionListener(listener)
        val colors = intArrayOf(
            ContextCompat.getColor(ctx, R.color.color1),
            ContextCompat.getColor(ctx, R.color.color2),
            ContextCompat.getColor(ctx, R.color.color3),
            ContextCompat.getColor(ctx, R.color.color4),
            ContextCompat.getColor(ctx, R.color.color5)
        )
        recognitionProgressView.setColors(colors)

        speechRecognizer = newRecognizer
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        }
        newRecognizer.startListening(recognizerIntent)

        voiceOverlay.visibility = View.VISIBLE
        recognitionProgressView.play()
    }

    private fun voiceErrorMessage(error: Int): Int = when (error) {
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER -> R.string.car_voice_search_network
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.car_voice_search_no_permission
        else -> R.string.car_voice_search_error
    }

    private fun stopVoiceInput(resumeVideo: Boolean) {
        speechRecognizer?.let {
            it.cancel()
            it.destroy()
        }
        speechRecognizer = null
        activeRecognitionProgressView?.let {
            it.stop()
            it.setOnClickListener(null)
        }
        activeRecognitionProgressView = null
        activeVoiceOverlay?.visibility = View.GONE
        activeVoiceOverlay = null
        if (resumeVideo) {
            CarPlayer.browser.resumeMediaIfInterrupted()
        }
    }

    private fun goBack(): Boolean {
        if (CarPlayer.browser.isVideoFullscreen()) {
            CarPlayer.browser.exitFullScreen()
            return true
        }
        if (CarPlayer.browser.canGoBack() && webView.hasFocus()) {
            CarPlayer.browser.goBack()
            return true
        }
        return false
    }

    private fun isToolbarVertical(): Boolean = toolbarPosition.isVertical

    private fun toolbarHiddenTranslation(): Float {
        if (isToolbarVertical()) {
            val w = toolbar.measuredWidth
            return (if (toolbarPosition == ToolbarPosition.LEFT) -w else w).toFloat()
        }
        val h = toolbar.measuredHeight
        return (if (toolbarPosition == ToolbarPosition.TOP) -h else h).toFloat()
    }

    private fun isToolbarShown(): Boolean =
        if (isToolbarVertical()) toolbar.translationX == 0f else toolbar.translationY == 0f

    private fun hideToolbar() {
        menuBubble.showMenuClosed()
        toolbar.animate().cancel()
        if (isToolbarShown()) {
            val t = toolbarHiddenTranslation()
            if (isToolbarVertical()) {
                toolbar.animate().setDuration(200).translationX(t)
            } else {
                toolbar.animate().setDuration(200).translationY(t)
            }
        }
    }

    private fun hideToolbarImmediate() {
        menuBubble.showMenuClosed()
        toolbar.animate().cancel()
        toolbar.removeCallbacks(toolbarHideRunnable)
        val t = toolbarHiddenTranslation()
        if (isToolbarVertical()) toolbar.translationX = t else toolbar.translationY = t
    }

    private fun isOverlayScreenOpen(): Boolean =
        (::settingsNavigator.isInitialized && settingsNavigator.isPanelOpen()) ||
            warningScreenOpen || speechRecognizer != null

    private fun canAutoHideNow(): Boolean =
        CarToolbarAutoHide.canHide(autoHideEnabled, isOverlayScreenOpen(), CarPlayer.browser.isVideoFullscreen())

    private fun scheduleAutoHide() {
        toolbar.removeCallbacks(toolbarHideRunnable)
        if (canAutoHideNow()) {
            toolbar.postDelayed(toolbarHideRunnable, AUTO_HIDE_IDLE_DELAY_MILLIS)
        }
    }

    private fun hideCornerControls(animated: Boolean) {
        cornerControls.clearFocus()
        if (!CarPlayer.browser.isVideoFullscreen()) {
            cornerControls.animate().cancel()
            cornerControls.visibility = View.INVISIBLE
            moveCornerControlsTo(cornerControlsHiddenOffset())
        } else if (animated) {
            cornerControls.visibility = View.VISIBLE
            cornerControls.clearAnimation()
            if (areCornerControlsInPlace()) {
                val offset = cornerControlsHiddenOffset()
                cornerControls.animate().setDuration(200).translationX(offset.x).translationY(offset.y)
            }
        } else {
            moveCornerControlsTo(cornerControlsHiddenOffset())
        }
    }

    private fun showToolbar() {
        menuBubble.showMenuOpen()
        toolbar.visibility = View.VISIBLE
        toolbar.removeCallbacks(toolbarHideRunnable)
        toolbar.animate().cancel()
        if (!isToolbarShown()) {
            if (isToolbarVertical()) {
                toolbar.animate().setDuration(200).translationX(0f)
            } else {
                toolbar.animate().setDuration(200).translationY(0f)
            }
        }
        scheduleAutoHide()
    }

    private fun cornerControlsHiddenOffset(): FullscreenControlsPlacement.Offset {
        val area = videoAreaBox()
        if (area == null || cornerControls.height <= 0) {
            val params = cornerControls.layoutParams as FrameLayout.LayoutParams
            val height = if (cornerControls.height > 0) cornerControls.height else CORNER_CONTROLS_FALLBACK_HEIGHT_PX
            return FullscreenControlsPlacement.Offset(0f, -(height + params.topMargin).toFloat())
        }
        return FullscreenControlsPlacement.offsetOutThroughNearestEdge(currentCornerControlsBox(), area)
    }

    private fun moveCornerControlsTo(offset: FullscreenControlsPlacement.Offset) {
        cornerControls.translationX = offset.x
        cornerControls.translationY = offset.y
    }

    private fun areCornerControlsInPlace(): Boolean =
        cornerControls.translationX == 0f && cornerControls.translationY == 0f

    private fun showCornerControls() {
        if (!CarPlayer.browser.isVideoFullscreen()) return
        cornerControls.visibility = View.VISIBLE
        cornerControls.removeCallbacks(cornerControlsHideRunnable)
        cornerControls.clearAnimation()
        if (!areCornerControlsInPlace()) {
            cornerControls.animate().setDuration(200).translationX(0f).translationY(0f)
        }
    }

    private fun showAndHideToolbarAnimation() {
        toolbar.removeCallbacks(toolbarHideRunnable)
        showToolbar()
        toolbar.postDelayed(toolbarHideRunnable, 3000)
    }

    private fun showAndHideCornerControlsAnimation() {
        cornerControls.removeCallbacks(cornerControlsHideRunnable)
        showCornerControls()
        cornerControls.postDelayed(cornerControlsHideRunnable, CORNER_CONTROLS_AUTO_HIDE_DELAY_MILLIS)
    }

    private fun onFullscreenVideoTouch(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            swallowingVideoTouch = !areCornerControlsInPlace()
            keepFullscreenControlsVisible()
        }
        return swallowingVideoTouch
    }

    private fun applyToolbarPosition(position: ToolbarPosition) {
        val wasToolbarShown = isToolbarShown()
        toolbarPosition = position
        val vertical = position.isVertical
        val bar = toolbar as LinearLayout

        container.orientation = if (vertical) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        bar.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        val thickness = resources.getDimensionPixelSize(R.dimen.car_toolbar_thickness)
        val match = LinearLayout.LayoutParams.MATCH_PARENT

        toolbarWrapper.layoutParams = if (vertical) {
            LinearLayout.LayoutParams(thickness, match, 0f)
        } else {
            LinearLayout.LayoutParams(match, thickness, 0f)
        }

        webViewContainer.layoutParams = if (vertical) {
            LinearLayout.LayoutParams(0, match, 1f)
        } else {
            LinearLayout.LayoutParams(match, 0, 1f)
        }

        detachToolbarWrapper()
        container.addView(toolbarWrapper, if (position.barFirst) 0 else container.childCount)

        val order = if (!vertical && driverSide == ScreenSide.RIGHT) TOOLBAR_ORDER.reversedArray() else TOOLBAR_ORDER
        val buttons = order.map { bar.findViewById<View>(it) }
        positionSettingsPanel(thickness)
        bar.layoutParams = FrameLayout.LayoutParams(match, match)
        bar.removeAllViews()
        for (b in buttons) {
            b.layoutParams = if (vertical) {
                LinearLayout.LayoutParams(thickness, 0, 1f)
            } else {
                LinearLayout.LayoutParams(thickness, thickness, 0f).apply {
                    val halfSpacing = resources.getDimensionPixelSize(R.dimen.car_toolbar_button_spacing) / 2
                    setMargins(halfSpacing, 0, halfSpacing, 0)
                }
            }
            bar.addView(b)
        }

        assignToolbarFocus(order, position)

        cancelToolbarHideAnimation()

        positionCornerControls(thickness)

        applyAutoHideOverlay(wasToolbarShown)
        positionFavoritesBar(position)
    }

    private fun favoritesBarOnLeft(position: ToolbarPosition): Boolean =
        position.favoritesBarSide(driverSide) == ScreenSide.LEFT

    private fun favoritesBarWidth(): Int =
        if (bookmarksBarEnabled) resources.getDimensionPixelSize(R.dimen.car_favorites_bar_width) else 0

    private fun positionFavoritesBar(position: ToolbarPosition) {
        if (!::favoritesBar.isInitialized) return
        val onLeft = favoritesBarOnLeft(position)
        val width = favoritesBarWidth()
        favoritesBar.visibility = if (bookmarksBarEnabled && !CarPlayer.browser.isVideoFullscreen()) View.VISIBLE else View.GONE
        favoritesBar.layoutParams = (favoritesBar.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (onLeft) Gravity.START else Gravity.END
        }
        webViewSlot.layoutParams = (webViewSlot.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = if (onLeft) width else 0
            rightMargin = if (onLeft) 0 else width
        }
    }

    private fun applyZoom(percent: Int) {
        CarPlayer.browser.setInitialScale(percent)
        CarPlayer.browser.zoomBy(percent.toFloat() / appliedZoomPercent)
        appliedZoomPercent = percent
    }

    private fun setBookmarksBarEnabled(enabled: Boolean) {
        if (bookmarksBarEnabled == enabled) return
        bookmarksBarEnabled = enabled
        applyToolbarPosition(toolbarPosition)
    }

    private fun setAutoHideEnabled(enabled: Boolean) {
        if (autoHideEnabled == enabled) return
        autoHideEnabled = enabled
        applyToolbarPosition(toolbarPosition)
        if (enabled) {
            toolbarWrapper.post { hideToolbarImmediate() }
        } else {
            showToolbar()
        }
    }

    private fun detachToolbarWrapper() {
        (toolbarWrapper.parent as? ViewGroup)?.removeView(toolbarWrapper)
    }

    private fun toolbarGravity(): Int = when (toolbarPosition) {
        ToolbarPosition.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
        ToolbarPosition.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
        ToolbarPosition.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ToolbarPosition.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    }

    private fun applyAutoHideOverlay(wasToolbarShown: Boolean) {
        if (!autoHideEnabled) {
            menuBubble.hide()
            return
        }
        detachToolbarWrapper()
        rootLayout.addView(toolbarWrapper, rootLayout.indexOfChild(menuBubbleView) + 1)

        val vertical = toolbarPosition.isVertical
        val thickness = resources.getDimensionPixelSize(R.dimen.car_toolbar_thickness)
        val match = FrameLayout.LayoutParams.MATCH_PARENT
        toolbarWrapper.layoutParams = FrameLayout.LayoutParams(
            if (vertical) thickness else match,
            if (vertical) match else thickness
        ).apply { gravity = toolbarGravity() }

        webViewContainer.layoutParams =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)

        positionMenuBubble()
        refreshMenuBubbleVisibility()
        if (!wasToolbarShown) {
            toolbarWrapper.post { hideToolbarImmediate() }
        }
    }

    private fun toggleToolbarFromBubble() {
        if (isToolbarShown()) hideToolbar() else showToolbar()
    }

    private fun positionMenuBubble() {
        val margin = resources.getDimensionPixelSize(R.dimen.car_menu_bubble_margin)
        val clearOfToolbar = margin + resources.getDimensionPixelSize(R.dimen.car_toolbar_thickness)
        val leftExtra = if (favoritesBarOnLeft(toolbarPosition)) favoritesBarWidth() else 0
        val rightExtra = if (favoritesBarOnLeft(toolbarPosition)) 0 else favoritesBarWidth()
        menuBubbleView.layoutParams = (menuBubbleView.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = toolbarGravity()
            setMargins(
                (if (toolbarPosition == ToolbarPosition.LEFT) clearOfToolbar else margin) + leftExtra,
                if (toolbarPosition == ToolbarPosition.TOP) clearOfToolbar else margin,
                (if (toolbarPosition == ToolbarPosition.RIGHT) clearOfToolbar else margin) + rightExtra,
                if (toolbarPosition == ToolbarPosition.BOTTOM) clearOfToolbar else margin
            )
        }
        menuBubbleView.translationX = 0f
        menuBubbleView.translationY = 0f
        val appContext = context!!.applicationContext
        SettingsUtils.getCarMenuBubblePosition(appContext)?.let { (x, y) ->
            menuBubble.placeAt(x, y) { SettingsUtils.clearCarMenuBubblePosition(appContext) }
        }
    }

    private val toolbarFocusChangeListener = View.OnFocusChangeListener { focusedView, hasFocus ->
        if (hasFocus) {
            showToolbar()
        } else {
            focusedView.post { if (!toolbar.hasFocus()) scheduleAutoHide() }
        }
    }

    private fun assignToolbarFocus(order: IntArray, position: ToolbarPosition) {
        val vertical = position.isVertical
        for (i in order.indices) {
            val b = toolbar.findViewById<View>(order[i])
            b.onFocusChangeListener = toolbarFocusChangeListener
            b.nextFocusUpId = View.NO_ID
            b.nextFocusDownId = View.NO_ID
            b.nextFocusLeftId = View.NO_ID
            b.nextFocusRightId = View.NO_ID

            val prev = order[if (i == 0) 0 else i - 1]
            val next = order[if (i == order.size - 1) i else i + 1]
            if (vertical) {
                b.nextFocusUpId = prev
                b.nextFocusDownId = next
                if (position == ToolbarPosition.LEFT) {
                    b.nextFocusRightId = R.id.web_view
                } else {
                    b.nextFocusLeftId = R.id.web_view
                }
            } else {
                b.nextFocusLeftId = prev
                b.nextFocusRightId = next
                if (position == ToolbarPosition.TOP) {
                    b.nextFocusDownId = R.id.web_view
                } else {
                    b.nextFocusUpId = R.id.web_view
                }
            }
        }

        webView.nextFocusUpId = View.NO_ID
        webView.nextFocusDownId = View.NO_ID
        webView.nextFocusLeftId = View.NO_ID
        webView.nextFocusRightId = View.NO_ID

        val firstButton = order[0]
        if (vertical) {
            if (position == ToolbarPosition.LEFT) {
                webView.nextFocusLeftId = firstButton
            } else {
                webView.nextFocusRightId = firstButton
            }
        } else {
            if (position == ToolbarPosition.TOP) {
                webView.nextFocusUpId = firstButton
            } else {
                webView.nextFocusDownId = firstButton
            }
        }
    }

    private fun cancelToolbarHideAnimation() {
        menuBubble.showMenuOpen()
        toolbar.removeCallbacks(toolbarHideRunnable)
        toolbar.animate().cancel()
        toolbar.translationX = 0f
        toolbar.translationY = 0f
        toolbar.visibility = View.VISIBLE
    }

    private fun isRecordAudioGranted(): Boolean =
        ContextCompat.checkSelfPermission(context!!, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "LIFECYCLE[$instanceId] onResume")
        MyApplication.mediaServer.connect(this)
        resumePlayback()
    }

    override fun onConnected(handle: ClientHandle) {
        mediaHandle = handle
    }

    override fun onEvent(event: MediaEventDto) = Unit

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "LIFECYCLE[$instanceId] onStop")
        CarPlayer.browser.flushCookies()
    }

    fun resumePlayback() {
        if (webViewBound && SettingsUtils.isResumeOnFocus(context!!)) {
            CarPlayer.browser.resumeMediaIfInterrupted()
        }
        handleVoiceVisibility()
    }

    private fun handleVoiceVisibility() {
        voiceButton?.visibility = if (isRecordAudioGranted()) View.VISIBLE else View.GONE
    }

    override fun onPause() {
        Log.d(TAG, "LIFECYCLE[$instanceId] onPause")
        super.onPause()
        mediaHandle?.disconnect()
        mediaHandle = null
        CarPlayer.saveState()
        CarPlayer.browser.flushCookies()
    }

    override fun onDetach() {
        castUrlReceiver?.let {
            try {
                context!!.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
            }
            castUrlReceiver = null
        }
        super.onDetach()
    }

    override fun onDestroy() {
        Log.d(TAG, "LIFECYCLE[$instanceId] onDestroy")
        handlerThread?.quit()
        val car = car
        if (car != null && car.isConnected) {
            car.disconnect()
        }

        super.onDestroy()
    }

    override fun onDestroyView() {
        stopVoiceInput(resumeVideo = false)
        voiceButton = null
        if (::carSettingsPanel.isInitialized) {
            carSettingsPanel.release()
        }
        if (::settingsNavigator.isInitialized) {
            settingsNavigator.release()
        }
        if (::realtimeSettingsSync.isInitialized) {
            realtimeSettingsSync.unregister()
        }
        context?.let { SiteColorStore.unregisterChangeListener(it, siteColorListener) }
        favoritesBarBookmarks?.let {
            it.removeAllChangeListeners()
            favoritesBarBookmarks = null
        }
        favoritesBarRealm?.let {
            it.close()
            favoritesBarRealm = null
        }
        if (webViewBound) {
            CarPlayer.detach()
        }
        super.onDestroyView()
        val mainCarActivity = context as MainCarActivity
        mainCarActivity.removeActivityCallback(this)
    }

    override fun onConfigChanged() {
        Log.d(TAG, "LIFECYCLE[$instanceId] onConfigChanged")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) {
            onHideKeyboardFromJS()
        }
    }

    override fun onFullScreenUnavailable() {
        fullScreenToggleFromPanel = false
        if (!isAdded) return
        showCarToast(toolbar, getString(R.string.car_settings_fullscreen_unavailable))
    }

    override fun onVideoElementDiscovered() {
        currentAspectRatio?.let {
            CarPlayer.browser.setAspectRatio(it.name.lowercase(Locale.getDefault()))
        }
    }

    override fun onShowKeyboardFromJS(oldText: String) {
        fakeEditText.setText(oldText)
        val mainCarActivity = context as MainCarActivity
        mainCarActivity.a().startInput(fakeEditText)
        fakeEditText.setSelection(fakeEditText.text.length)
        val layoutParams = webView.layoutParams
        layoutParams.height =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 150f, resources.displayMetrics).toInt()
        webView.layoutParams = layoutParams
        CarPlayer.browser.scrollActiveElementIntoView(true)
    }

    override fun onHideKeyboardFromJS() {
        val mainCarActivity = context as MainCarActivity
        mainCarActivity.a().stopInput()
        val layoutParams = webView.layoutParams
        layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        webView.layoutParams = layoutParams
    }

    fun setParkingBrake(parkingBrake: Boolean) {
        isParkingEngaged = parkingBrake
        if (!isParkingEngaged && !warningAccepted && SettingsUtils.isSafetyWarningEnabled(context!!)) {
            showWarningScreen()
        } else {
            hideWarningScreen()
        }
    }

    private fun hideWarningScreen() {
        if (warningScreenOpen) {
            if (isAdded) {
                webView.visibility = View.VISIBLE
                showToolbar()
                val childFragmentManager = childFragmentManager
                val oldFragment = childFragmentManager.findFragmentByTag(SAFETY_WARNING_FRAGMENT_TAG)
                if (oldFragment != null) {
                    warningScreenOpen = false
                    val fragmentTransaction = childFragmentManager.beginTransaction()
                    fragmentTransaction.remove(oldFragment)
                    fragmentTransaction.setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out)
                    fragmentTransaction.commitAllowingStateLoss()
                }
            }
            webView.requestFocus()
        }
    }

    private fun showWarningScreen() {
        if (isAdded) {
            warningScreenOpen = true
            val childFragmentManager = childFragmentManager
            var warningFragment =
                childFragmentManager.findFragmentByTag(SAFETY_WARNING_FRAGMENT_TAG) as SafetyWarningFragment?
            if (warningFragment == null) {
                warningFragment = SafetyWarningFragment()
            }
            val fragmentTransaction = childFragmentManager.beginTransaction()
            fragmentTransaction.replace(R.id.overlay_container, warningFragment, SAFETY_WARNING_FRAGMENT_TAG)
            fragmentTransaction.setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out)
            fragmentTransaction.commitNow()
            webView.visibility = View.GONE
            toolbar.visibility = View.GONE
        }
    }

    private fun positionSettingsPanel(toolbarThickness: Int) {
        if (!::settingsPanel.isInitialized) return
        val params = settingsPanel.layoutParams as FrameLayout.LayoutParams
        val margin = resources.getDimensionPixelSize(R.dimen.car_settings_panel_margin)
        params.setMargins(margin, margin, margin, margin)
        val reservedByToolbar = if (autoHideEnabled) 0 else toolbarThickness
        params.gravity = if (toolbarPosition.settingsPanelSide(driverSide) == ScreenSide.LEFT) Gravity.START else Gravity.END
        when (toolbarPosition) {
            ToolbarPosition.LEFT -> params.leftMargin += reservedByToolbar
            ToolbarPosition.RIGHT -> params.rightMargin += reservedByToolbar
            ToolbarPosition.TOP -> params.topMargin += reservedByToolbar
            ToolbarPosition.BOTTOM -> params.bottomMargin += reservedByToolbar
        }
        if (favoritesBarOnLeft(toolbarPosition)) {
            params.leftMargin += favoritesBarWidth()
        } else {
            params.rightMargin += favoritesBarWidth()
        }
        settingsPanel.layoutParams = params
    }

    private fun positionCornerControls(toolbarThickness: Int) {
        val savedPosition = SettingsUtils.getCarFullscreenControlsPosition(context!!, cornerControlsLayout())
        if (savedPosition != null) {
            val area = videoAreaBox()
            if (area == null) {
                webViewContainer.post { positionCornerControls(toolbarThickness) }
                return
            }
            val (xFraction, yFraction) = savedPosition
            applyCornerControlsBox(
                FullscreenControlsPlacement.clampToArea(
                    area.left + CarMenuBubblePlacement.toOffset(xFraction, area.width.toInt()),
                    area.top + CarMenuBubblePlacement.toOffset(yFraction, area.height.toInt()),
                    cornerControls.width.toFloat(), cornerControls.height.toFloat(), area
                )
            )
            return
        }
        val params = cornerControls.layoutParams as FrameLayout.LayoutParams
        val margin = resources.getDimensionPixelSize(R.dimen.car_fullscreen_menu_margin)
        params.setMargins(margin, margin, margin, margin)
        val reservedByToolbar = if (autoHideEnabled) 0 else toolbarThickness
        when (toolbarPosition) {
            ToolbarPosition.LEFT -> params.leftMargin += reservedByToolbar
            ToolbarPosition.RIGHT -> params.rightMargin += reservedByToolbar
            ToolbarPosition.TOP -> params.topMargin += reservedByToolbar
            ToolbarPosition.BOTTOM -> Unit
        }
        params.gravity = Gravity.TOP or
            if (toolbarPosition.settingsPanelSide(driverSide) == ScreenSide.LEFT) Gravity.START else Gravity.END
        cornerControls.layoutParams = params
    }

    private fun onCornerControlsHandleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startCornerControlsDrag(event)
            MotionEvent.ACTION_MOVE -> dragCornerControlsTo(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishCornerControlsDrag()
        }
        return true
    }

    private fun startCornerControlsDrag(event: MotionEvent) {
        dragDownRawX = event.rawX
        dragDownRawY = event.rawY
        dragStartLeft = cornerControls.left.toFloat()
        dragStartTop = cornerControls.top.toFloat()
        draggingCornerControls = false
        cornerControls.removeCallbacks(cornerControlsHideRunnable)
        toolbar.removeCallbacks(toolbarHideRunnable)
    }

    private fun dragCornerControlsTo(event: MotionEvent) {
        val dx = event.rawX - dragDownRawX
        val dy = event.rawY - dragDownRawY
        val touchSlop = ViewConfiguration.get(cornerControls.context).scaledTouchSlop
        if (!draggingCornerControls && Math.hypot(dx.toDouble(), dy.toDouble()) < touchSlop) return
        draggingCornerControls = true
        val area = videoAreaBox() ?: return
        applyCornerControlsBox(
            FullscreenControlsPlacement.clampToArea(
                dragStartLeft + dx, dragStartTop + dy,
                cornerControls.width.toFloat(), cornerControls.height.toFloat(), area
            )
        )
    }

    private fun finishCornerControlsDrag() {
        if (draggingCornerControls) {
            resolveCornerControlsOverlap(persistPosition = true)
        }
        draggingCornerControls = false
        showAndHideToolbarAnimation()
        cornerControls.postDelayed(cornerControlsHideRunnable, CORNER_CONTROLS_AUTO_HIDE_DELAY_MILLIS)
    }

    private fun resolveCornerControlsOverlap(persistPosition: Boolean = false) {
        val area = videoAreaBox() ?: return
        val resolved = FullscreenControlsPlacement.avoidOverlap(currentCornerControlsBox(), cornerControlsObstacles(), area)
        applyCornerControlsBox(resolved)
        if (persistPosition || SettingsUtils.getCarFullscreenControlsPosition(context!!, cornerControlsLayout()) != null) {
            SettingsUtils.setCarFullscreenControlsPosition(
                context!!,
                cornerControlsLayout(),
                CarMenuBubblePlacement.toFraction(resolved.left - area.left, area.width.toInt()),
                CarMenuBubblePlacement.toFraction(resolved.top - area.top, area.height.toInt())
            )
        }
    }

    private fun enlargeTopIcon(button: TextView) {
        val icon = button.compoundDrawablesRelative[1] ?: return
        val size = resources.getDimensionPixelSize(R.dimen.car_fullscreen_menu_icon_size)
        icon.setBounds(0, 0, size, size)
        button.setCompoundDrawablesRelative(null, icon, null, null)
    }

    private fun cornerControlsLayout(): String = "${driverSide.name}:${toolbarPosition.name}"

    private fun cornerControlsObstacles(): List<FullscreenControlsPlacement.Box> =
        listOfNotNull(boxIfVisible(toolbarWrapper), boxIfVisible(menuBubbleView), boxIfVisible(settingsPanel), boxIfVisible(settingsScreen))

    private fun boxIfVisible(view: View): FullscreenControlsPlacement.Box? {
        if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return null
        return FullscreenControlsPlacement.Box(view.left.toFloat(), view.top.toFloat(), view.width.toFloat(), view.height.toFloat())
    }

    private fun videoAreaBox(): FullscreenControlsPlacement.Box? {
        if (webViewContainer.width <= 0 || webViewContainer.height <= 0) return null
        return FullscreenControlsPlacement.Box(
            webViewContainer.left.toFloat(), webViewContainer.top.toFloat(),
            webViewContainer.width.toFloat(), webViewContainer.height.toFloat()
        )
    }

    private fun currentCornerControlsBox(): FullscreenControlsPlacement.Box = FullscreenControlsPlacement.Box(
        cornerControls.left.toFloat(), cornerControls.top.toFloat(),
        cornerControls.width.toFloat(), cornerControls.height.toFloat()
    )

    private fun applyCornerControlsBox(box: FullscreenControlsPlacement.Box) {
        val params = cornerControls.layoutParams as FrameLayout.LayoutParams
        params.gravity = Gravity.TOP or Gravity.START
        params.setMargins(box.left.toInt(), box.top.toInt(), 0, 0)
        cornerControls.layoutParams = params
    }

    fun setAspectRatio(aspectRatio: AspectRatio) {
        currentAspectRatio = aspectRatio
        aspectRatioGroup.check(ASPECT_RATIO_OPTIONS.first { it.second == aspectRatio }.first)
        CarPlayer.browser.setAspectRatio(aspectRatio.name.lowercase(Locale.getDefault()))
        sharedPrefs.edit().putInt(ASPECT_RATIO_KEY, aspectRatio.ordinal).apply()
    }

    override fun onReadyToExitSafetyInstructions(warningFragment: SafetyWarningFragment?) {
        warningAccepted = true
        hideWarningScreen()
    }

    private fun getHumanText(position: ToolbarPosition): String = when (position) {
        ToolbarPosition.TOP -> getString(R.string.car_toolbar_position_top)
        ToolbarPosition.LEFT -> getString(R.string.car_toolbar_position_left)
        ToolbarPosition.RIGHT -> getString(R.string.car_toolbar_position_right)
        ToolbarPosition.BOTTOM -> getString(R.string.car_toolbar_position_bottom)
    }

    enum class AspectRatio {
        CONTAIN, FILL, COVER
    }

    override fun onPageLoadingChanged(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

    override fun onUnhandledKeyEvent(event: KeyEvent) {
        if (warningScreenOpen && event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            onReadyToExitSafetyInstructions(null)
            return
        }
        if (!CarPlayer.browser.isVideoFullscreen()) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                showToolbar()
                showCornerControls()
                cornerControls.requestFocus(View.FOCUS_UP)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                cornerControls.clearFocus()
                hideToolbar()
                hideCornerControls(true)
            }
            KeyEvent.KEYCODE_DPAD_CENTER -> if (isPlaying) mediaHandle?.pause() else mediaHandle?.play()
            KeyEvent.KEYCODE_DPAD_RIGHT -> CarPlayer.browser.mediaSeekBy(10)
            KeyEvent.KEYCODE_DPAD_LEFT -> CarPlayer.browser.mediaSeekBy(-10)
        }
    }

    companion object {
        const val SAFETY_WARNING_FRAGMENT_TAG = "safety"
        private const val TAG = "WebViewCarFragment"
        private const val ASPECT_RATIO_KEY = "aspect_ratio"
        private const val AUTO_HIDE_IDLE_DELAY_MILLIS = 5000L
        private const val PANEL_TOGGLE_FEEDBACK_TIMEOUT_MILLIS = 3000L
        private const val CORNER_CONTROLS_FALLBACK_HEIGHT_PX = 500
        private const val CORNER_CONTROLS_AUTO_HIDE_DELAY_MILLIS = 3000L
        private val ASPECT_RATIO_OPTIONS = listOf(
            R.id.aspect_ratio_contain to AspectRatio.CONTAIN,
            R.id.aspect_ratio_cover to AspectRatio.COVER,
            R.id.aspect_ratio_fill to AspectRatio.FILL
        )

        private val TOOLBAR_ORDER = intArrayOf(
            R.id.home_button,
            R.id.refresh_button,
            R.id.back_button,
            R.id.voice_button,
            R.id.settings_button
        )
    }
}
