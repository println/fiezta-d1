package proto.media.fiezta.features.domain.car.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import proto.media.fiezta.R
import proto.media.fiezta.features.domain.car.app.CarScreenLauncher
import proto.media.fiezta.features.domain.car.settings.showCarToast
import proto.media.fiezta.features.domain.core.bookmarks.SiteColorStore
import proto.media.fiezta.features.domain.core.browser.BrowserStorageUtils
import proto.media.fiezta.features.domain.core.media.PreviousAction
import proto.media.fiezta.features.domain.core.media.QueueResolution
import proto.media.fiezta.features.domain.core.media.RecentlyPlayed
import proto.media.fiezta.features.domain.core.settings.SettingsStorage
import proto.media.fiezta.features.domain.core.settings.SettingsUtils
import proto.media.fiezta.features.domain.mediaserver.MediaControlBridge
import proto.media.fiezta.features.domain.mediaserver.PlaybackBrowserService
import proto.media.fiezta.features.domain.mediaserver.PlaybackSession
import proto.media.fiezta.shared.plugins.AndroidPluginLog
import proto.media.fiezta.shared.plugins.AppPlugins
import proto.media.fiezta.shared.plugins.PluginHandlersBridge
import proto.media.fiezta.shared.plugins.toPageStage
import proto.media.fiezta.shared.plugins.toPageVisibility
import proto.media.fiezta.shared.search.SearchEngineSource
import proto.media.fiezta.shared.webviewex.AndroidAudioOutputSignal
import proto.media.fiezta.shared.webviewex.AndroidAutoConnectionSignal
import proto.media.fiezta.shared.webviewex.AndroidCarModeExitSignal
import proto.media.fiezta.shared.webviewex.AppWebViewExHost
import proto.media.fiezta.shared.webviewex.AppWebViewExTexts
import proto.media.fiezta.support.media.contract.MediaRenderer
import proto.media.fiezta.support.media.dto.MediaAction
import proto.media.fiezta.support.media.dto.MediaCommandDto
import proto.media.fiezta.support.media.dto.MediaQueueDto
import proto.media.fiezta.support.media.dto.MediaReadingDto
import proto.media.fiezta.support.media.dto.QueueEntryDto
import proto.media.fiezta.support.media.dto.QueueShape
import proto.media.fiezta.support.media.dto.RendererAvailability
import proto.media.fiezta.support.media.dto.RendererEventDto
import proto.media.fiezta.support.media.server.MediaServer
import proto.media.fiezta.support.media.server.MediaSession
import proto.media.fiezta.support.plugins.activation.PluginSource
import proto.media.fiezta.support.plugins.injection.PluginInjector
import proto.media.fiezta.support.plugins.model.CarEvent
import proto.media.fiezta.support.plugins.model.PluginEnv
import proto.media.fiezta.support.plugins.model.Requirement
import proto.media.fiezta.support.plugins.voice.VoiceDestination
import proto.media.fiezta.support.plugins.voice.VoiceLanding
import proto.media.fiezta.support.search.SearchEngineSelector
import proto.media.fiezta.support.webviewex.LoadingState
import proto.media.fiezta.support.webviewex.RenderGoneResponse
import proto.media.fiezta.support.webviewex.VideoWebView
import proto.media.fiezta.support.webviewex.WebViewEx
import proto.media.fiezta.support.webviewex.WebViewExChromeClient
import proto.media.fiezta.support.webviewex.WebViewExDelegate
import proto.media.fiezta.support.webviewex.WebViewExListener
import proto.media.fiezta.support.webviewex.document.DocumentStage
import proto.media.fiezta.support.webviewex.gearhead.AndroidAutoWebViewExGearhead
import proto.media.fiezta.support.webviewex.gearhead.ObservedCommandKind
import proto.media.fiezta.support.webviewex.gearhead.PlaybackSnapshot
import proto.media.fiezta.support.webviewex.gearhead.PlaybackVerdict
import proto.media.fiezta.support.webviewex.media.MediaElementState
import proto.media.fiezta.support.webviewex.navigation.NavigationFailure
import proto.media.fiezta.support.webviewex.navigation.NavigationTransaction
import proto.media.fiezta.support.webviewex.presentation.RenderMode
import proto.media.fiezta.support.webviewex.video.JavascriptCallback

object CarPlayer : JavascriptCallback.JSCallbacks, MediaControlBridge.Callbacks, PlaybackSession.Host, MediaRenderer {

    interface UiCallbacks {
        fun onShowKeyboardFromJS(oldText: String)
        fun onHideKeyboardFromJS()
        fun onVideoElementDiscovered()
        fun onFullScreenUnavailable()
        fun onPageLoadingChanged(loading: Boolean)
        fun onUnhandledKeyEvent(event: KeyEvent)
        fun onWebViewRecreated()
    }

    private object NullUiCallbacks : UiCallbacks {
        override fun onShowKeyboardFromJS(oldText: String) = Unit
        override fun onHideKeyboardFromJS() = Unit
        override fun onVideoElementDiscovered() = Unit
        override fun onFullScreenUnavailable() = Unit
        override fun onPageLoadingChanged(loading: Boolean) = Unit
        override fun onUnhandledKeyEvent(event: KeyEvent) = Unit
        override fun onWebViewRecreated() = Unit
    }

    const val PREFS = CarPlayerStateStore.PREFS
    const val HOME_URL = CarPlayerStateStore.HOME_URL
    private const val TAG = "CarPlayer"
    private const val APP_ORIGIN = "app"
    private const val JAVASCRIPT_INTERFACE = "nativecallbacks"
    private const val MEDIA_CONTROL_INTERFACE = "mediacontrol"
    private const val PLUGIN_HANDLERS_INTERFACE = "fieztaplugins"
    private const val PENDING_PLAY_TIMEOUT_MILLIS = 15_000L
    private const val VOICE_SEARCH_FAILURE_GRACE_MILLIS = 5_000L
    private const val SAVE_WHILE_PLAYING_MILLIS = 10_000L
    private const val DESTROY_WHEN_IDLE_MILLIS = 60_000L
    private const val AWAIT_PAUSE_VERDICT_MILLIS = 12_000L
    private const val VOICE_SEARCH_ERROR_DISPLAY_MILLIS = 5_000L

    private lateinit var appContext: Context
    private lateinit var stateStore: CarPlayerStateStore
    private lateinit var mediaSession: MediaSession
    private val handler = Handler(Looper.getMainLooper())

    lateinit var browser: WebViewEx<VideoWebView>
        private set
    private lateinit var pluginInjector: PluginInjector
    private lateinit var androidAuto: AndroidAutoWebViewExGearhead
    private var resumeAfterRenderProcessGone = false
    private val plugins: PluginSource by lazy {
        AppPlugins.source(appContext).also { source ->
            source.addStackListener { browser.url?.let { pluginInjector.onStackChanged(it) } }
        }
    }
    private var ui: UiCallbacks = NullUiCallbacks
    private var wasPlayingTrack = false
    private var pauseAwaitingVerdict: Boolean? = null
    private var commandAdmittedWithOrigin: MediaCommandDto? = null
    private var searchEngines: SearchEngineSelector? = null
    private var pendingPlay = false
        set(value) {
            field = value
            publishAvailability()
        }
    private var pageLoading = false
        set(value) {
            field = value
            publishAvailability()
        }
    private var pageQueue = MediaQueueDto.EMPTY
    private var historyQueue = MediaQueueDto.EMPTY
    private var mediaUrls = mutableSetOf<String>()
    private var recentlyPlayedEntries = emptyList<QueueEntryDto>()
    private var voiceSearch: VoiceSearchRequest? = null
    private var pendingVoiceScript: Pair<String, String>? = null
    private var nextVoiceSearchId = 0L
    private var documentGeneration = 0L
    private var lastPositionSeconds = -1
    private var lastPositionUrl: String? = null
    private var positionRestorePending = false

    private var pageLoadingComplete = false
    private var lastReadingHasMedia = false
    private var lastReadingWasTrack = false
    private var readingSeenForDocument = false
    private var lateVoiceSearchFailure: Runnable? = null
    private var lastRecordedTrackIdentity = ""
    private val recentlyPlayed: RecentlyPlayedStore by lazy { RecentlyPlayedStore(appContext) }

    private val saveWhilePlaying = object : Runnable {
        override fun run() {
            saveState()
            handler.postDelayed(this, SAVE_WHILE_PLAYING_MILLIS)
        }
    }
    private val destroyWhenIdle = Runnable { destroy("idle without screen") }
    private val confirmPauseWithoutVerdict = Runnable {
        Log.d(TAG, "no verdict for the pause, confirming it")
        confirmPauseAwaitingVerdict()
    }
    private val focusRegain by lazy { AudioFocusRegain(appContext) { mediaSession.send(MediaCommandDto.Play) } }
    private val voiceSearchTimeout = Runnable { failVoiceSearch("timeout") }
    private val giveUpPendingPlay = Runnable {
        if (pendingPlay) {
            Log.d(TAG, "pending play gave up, no video on the page")
            pendingPlay = false
            scheduleIdleDestroy()
        }
    }

    override val id = "car"

    private val isPlaying: Boolean
        get() = mediaSession.state.audible

    val host: String?
        get() = browser.session.host

    val isScreenAttached: Boolean
        get() = browser.isAttached

    private val currentPlayback: PlaybackSnapshot
        get() = checkNotNull(browser.playback)

    fun bind(context: Context, server: MediaServer) {
        appContext = context.applicationContext
        stateStore = CarPlayerStateStore(appContext)
        androidAuto = AndroidAutoWebViewExGearhead(
            appContext,
            AndroidAudioOutputSignal(appContext),
            AndroidAutoConnectionSignal(appContext),
            AndroidCarModeExitSignal(appContext)
        )
        recentlyPlayedEntries = recentlyPlayed.entries
        mediaSession = server.register(this)
        searchEngines = SearchEngineSource(appContext).selector()
        createBrowser()
        publishHistory()
        androidAuto.onCarModeExited = ::onCarModeExited
        Log.d(TAG, "bound to media session")
    }

    private fun publishAvailability() {
        mediaSession.report(RendererEventDto.AvailabilityChanged(availability()))
        syncLoadingIndicator()
    }

    private fun syncLoadingIndicator() {
        ui.onPageLoadingChanged(browser.loading.isLoading)
    }

    private fun availability() = when {
        pendingPlay -> RendererAvailability.STARTING
        !browser.hasView -> RendererAvailability.ABSENT
        pageLoading -> RendererAvailability.LOADING
        else -> RendererAvailability.READY
    }

    private fun publishHistory() {
        mediaSession.report(RendererEventDto.HistoryRead(recentlyPlayedEntries.asReversed()))
    }

    private fun publishQueue() {
        val queue = if (lastReadingWasTrack) {
            QueueResolution.resolve(pageQueue, historyQueue, previousTrack())
        } else {
            MediaQueueDto.EMPTY
        }
        mediaSession.report(RendererEventDto.QueueRead(queue))
    }

    fun attach(container: ViewGroup, activity: Context, ui: UiCallbacks) {
        PlaybackBrowserService.onCarEntered(activity)
        handler.removeCallbacks(destroyWhenIdle)
        browser.attach(container, activity)
        this.ui = ui
        syncLoadingIndicator()
        Log.d(TAG, "attach")
    }

    fun detach() {
        val hadView = browser.hasView
        ui = NullUiCallbacks
        if (browser.isVideoFullscreen()) {
            browser.exitFullScreen()
        }
        browser.unbindVideoChrome()
        browser.detach()
        if (!hadView) return
        saveState()
        scheduleIdleDestroy()
        Log.d(TAG, "detach")
    }

    fun onNotificationDismissed() {
        if (isPlaying) return
        destroy("notification dismissed")
    }

    private fun destroy(reason: String) {
        if (!browser.hasView) return
        Log.d(TAG, "destroy: $reason")
        prepareForViewDestruction()
        browser.destroy()
        resetAfterViewDestroyed()
    }

    private fun prepareForViewDestruction() {
        forgetPauseAwaitingVerdict()
        handler.removeCallbacks(destroyWhenIdle)
        handler.removeCallbacks(saveWhilePlaying)
        handler.removeCallbacks(giveUpPendingPlay)
        saveState()
        browser.flushCookies()
    }

    private fun resetAfterViewDestroyed() {
        pluginInjector.onViewDestroyed()
        wasPlayingTrack = false
        forgetPauseAwaitingVerdict()
        pendingPlay = false
        cancelVoiceSearch()
        lastPositionSeconds = -1
        lastReadingHasMedia = false
        lastReadingWasTrack = false
        readingSeenForDocument = false
        pageLoadingComplete = false
        lastRecordedTrackIdentity = ""
        publishAvailability()
        historyQueue = MediaQueueDto.EMPTY
        pageQueue = MediaQueueDto.EMPTY
        mediaUrls = mutableSetOf()
        publishQueue()
    }

    private fun createBrowser() {
        browser = WebViewEx(
            context = appContext,
            config = AndroidAutoWebViewExGearhead.config { query -> carSearchUrl(query) },
            host = AppWebViewExHost(appContext),
            texts = AppWebViewExTexts(appContext),
            delegate = browserDelegate,
            webViewFactory = { context -> VideoWebView(context) },
            chromeClientFactory = { WebViewExChromeClient() },
            gearhead = androidAuto
        )
        browser.observe(browserListener)
        pluginInjector = PluginInjector(
            evaluate = browser.scripts::evaluate,
            source = plugins,
            env = PluginEnv.CAR,
            isRequirementMet = { requirement, url ->
                when (requirement) {
                    Requirement.ADBLOCK -> SettingsStorage.isAdBlockEnabledForHost(appContext, Uri.parse(url).host)
                }
            },
            isOptionEnabled = { pluginId, script, option ->
                val (key, default) = plugins.effectiveOptionKey(pluginId, script, option)
                SettingsUtils.isPluginOptionEnabled(appContext, key, default)
            },
            onEventsCleared = {},
            log = AndroidPluginLog("PluginInjector")
        )
        pluginInjector.voiceSearchProgressListener = ::onVoiceSearchProgress
        browser.addBridge(JAVASCRIPT_INTERFACE, JavascriptCallback(this))
        browser.addBridge(MEDIA_CONTROL_INTERFACE, MediaControlBridge(this))
        browser.addBridge(
            PLUGIN_HANDLERS_INTERFACE,
            PluginHandlersBridge(pluginInjector, ::toast, ::feedback)
        )
    }

    private fun carSearchUrl(query: String): String {
        val engines = searchEngines ?: error("Search engines are not configured")
        return engines.forUrl(browser.url).searchUrl(query)
    }

    private val browserDelegate = object : WebViewExDelegate {
        override fun initialUrl(): String = this@CarPlayer.initialUrl()

        override fun fallbackUrl(): String = SettingsUtils.getHomeUrl(appContext)

        override fun onRenderProcessGone(): RenderGoneResponse {
            if (voiceSearch != null) {
                failVoiceSearch("renderer process gone")
                return RenderGoneResponse.DESTROY
            }
            resumeAfterRenderProcessGone = isPlaying || pendingPlay
            return if (browser.isAttached || resumeAfterRenderProcessGone) RenderGoneResponse.RECREATE else RenderGoneResponse.DESTROY
        }
    }

    private val browserListener = object : WebViewExListener {
        override fun onViewCreated() {
            positionRestorePending = true
            publishAvailability()
            Log.d(TAG, "create")
        }

        override fun onPlaybackVerdict(playback: PlaybackSnapshot) = onVerdictChanged(playback)

        override fun onViewDestroying() = prepareForViewDestruction()

        override fun onViewDestroyed() {
            resetAfterViewDestroyed()
            pendingPlay = resumeAfterRenderProcessGone
        }

        override fun onViewRecreated() = ui.onWebViewRecreated()

        override fun onNavigationStarted(
            transaction: NavigationTransaction, url: String, favicon: Bitmap?, isErrorDocument: Boolean
        ) {
            if (isErrorDocument) return
            documentGeneration++
            voiceSearch?.let { request ->
                voiceSearch = request.withDocument(documentGeneration)
                pluginInjector.setVoiceSearchContext(request.id, documentGeneration, request.query)
            }
            pageLoadingComplete = false
        }

        override fun onPageFinished(url: String?, isErrorDocument: Boolean) {
            pageLoadingComplete = true
            if (isErrorDocument) return
            if (url != null) stateStore.saveUrl(url)
            BrowserStorageUtils.recordHost(appContext, url)
            browser.observeMedia()
            publishHistoryQueue()
            if (pendingPlay) armGiveUpPendingPlay()
            if (url != null && (url.contains("accounts.google.com") ||
                    url.contains("youtube.com") ||
                    url.contains("google.com"))
            ) {
                browser.flushCookies()
            }
        }

        override fun onVisitedHistoryUpdated(url: String?, isReload: Boolean, isErrorDocument: Boolean) {
            if (isErrorDocument) return
            publishHistoryQueue()
            if (url != null && !isReload) {
                lastPositionSeconds = -1
                stateStore.saveUrl(url)
            }
        }

        override fun onLoadingChanged(state: LoadingState) {
            pageLoading = state.isLoading
            ui.onPageLoadingChanged(state.isLoading)
        }

        override fun onIconReceived(url: String?, icon: Bitmap) = SiteColorStore.learnFromIcon(appContext, url, icon)

        override fun onDocumentStage(stage: DocumentStage, documentId: String, url: String?) {
            pluginInjector.onPageStage(stage.toPageStage(), url)
            if (stage == DocumentStage.COMMITTED) runPendingVoiceScript()
        }

        override fun onRenderModeChanged(mode: RenderMode) = pluginInjector.onPageVisibilityChanged(mode.toPageVisibility())

        override fun onSchemeFallback(url: String, host: String?) =
            toast(appContext.getString(R.string.browser_http_fallback_notice))

        override fun onExternalLinkBlocked(url: String, failure: NavigationFailure) = toast(
            appContext.getString(
                if (failure == NavigationFailure.AppNotInstalled) R.string.browser_external_app_missing
                else R.string.browser_external_link_blocked
            )
        )

        override fun onUnhandledKeyEvent(event: KeyEvent) {
            if (event.action == KeyEvent.ACTION_UP) ui.onUnhandledKeyEvent(event)
        }
    }

    private fun toast(text: String) {
        browser.screenView?.let { showCarToast(it, text) }
    }

    private fun feedback(defaultText: String) {
        if (SettingsUtils.isPluginFeedbackEnabled(appContext)) toast(defaultText)
    }

    private fun initialUrl(): String {
        val homeUrl = SettingsUtils.getHomeUrl(appContext)
        if (!SettingsUtils.isRestoreLastUrl(appContext) && !pendingPlay) return homeUrl
        return stateStore.url ?: homeUrl
    }

    fun saveState() {
        val session = browser.session
        val url = session.url?.takeUnless { session.isErrorDocument } ?: return
        stateStore.saveUrl(url)
        val positionBelongsToPage = lastPositionUrl == url && !positionRestorePending
        if (lastPositionSeconds >= 0 && positionBelongsToPage) {
            Log.d(TAG, "saving $url at ${lastPositionSeconds}s")
            stateStore.saveVideoTime(url, lastPositionSeconds)
        } else {
            Log.d(TAG, "saving $url without a position")
        }
    }

    private fun scheduleIdleDestroy() {
        handler.removeCallbacks(destroyWhenIdle)
        if (!isPlaying && !pendingPlay && !browser.isAttached && browser.hasView) {
            Log.d(TAG, "paused without screen, destroying in ${DESTROY_WHEN_IDLE_MILLIS / 1000}s")
            handler.postDelayed(destroyWhenIdle, DESTROY_WHEN_IDLE_MILLIS)
        }
    }

    private fun onCarModeExited() {
        val wasPlaying = isPlaying || pauseAwaitingVerdict != null
        Log.d(TAG, "car mode exited, playing=$wasPlaying")
        focusRegain.cancel()
        destroy("car mode exited")
        stateStore.playbackInterrupted = wasPlaying
        PlaybackBrowserService.onCarLeft(appContext)
    }

    private fun dispatch(event: CarEvent, argument: String? = null): Boolean =
        pluginInjector.dispatch(event, argument)

    override fun onVideoElementDiscovered() {
        browser.evaluateJavascript(plugins.runtime())
        val savedSeconds = stateStore.takeVideoTimeFor(browser.url)
        Log.d(TAG, "video discovered at ${browser.url}, restoring ${savedSeconds ?: "nothing"}")
        savedSeconds?.let { browser.mediaSeekTo(it) }
        positionRestorePending = false
        if (pendingPlay) {
            browser.mediaPlay()
        }
        ui.onVideoElementDiscovered()
    }

    override fun onFullScreenUnavailable() = ui.onFullScreenUnavailable()

    override fun onShowKeyboardFromJS(oldText: String) = ui.onShowKeyboardFromJS(oldText)

    override fun onHideKeyboardFromJS() = ui.onHideKeyboardFromJS()

    override fun onPageQueue(queue: MediaQueueDto) {
        pageQueue = queue
        publishQueue()
    }

    override fun onSearchResult(found: Boolean) {
        Log.d(TAG, "search result found=$found")
        if (!found) {
            scheduleIdleDestroy()
        }
    }

    override fun onReading(reading: MediaReadingDto) {
        if (!browser.hasView) return
        val playback = currentPlayback
        if (playback.verdict != PlaybackVerdict.INTERRUPTED) {
            lastPositionSeconds = positionOf(reading, playback)
            lastPositionUrl = reading.pageUrl
        } else if (playback.element != MediaElementState.NONE) {
            lastPositionSeconds = playback.trustedPositionSeconds.toInt()
        }
        lastReadingHasMedia = reading.hasMedia
        readingSeenForDocument = true
        val leavingMedia = lastReadingWasTrack && !reading.isTrack
        lastReadingWasTrack = reading.isTrack
        if (reading.isTrack) mediaUrls += reading.pageUrl
        val commandBeforeReading = mediaSession.pendingCommand
        val playingTrack = reading.playing && reading.isTrack
        val wasPlaying = wasPlayingTrack
        val stopping = !playingTrack && wasPlaying
        if (stopping && !leavingMedia && playback.verdict == PlaybackVerdict.INTERRUPTED) {
            mediaSession.report(RendererEventDto.Interrupted(true))
        }
        if (leavingMedia) publishQueue()
        mediaSession.report(RendererEventDto.Read(reading))
        wasPlayingTrack = playingTrack
        when {
            leavingMedia -> onLeftMedia()
            playingTrack && !wasPlaying -> onReadingStartedPlaying()
            stopping -> {
                Log.d(TAG, "reading stopped: hasMedia=${reading.hasMedia} position=${reading.positionSeconds} url=${reading.pageUrl}")
                awaitVerdictForPause(commandedByUs = commandBeforeReading == MediaCommandDto.Pause, playback)
            }
        }
        if (RecentlyPlayed.shouldRecord(reading, lastRecordedTrackIdentity)) {
            lastRecordedTrackIdentity = reading.trackIdentity
            recentlyPlayed.record(reading)
            recentlyPlayedEntries = recentlyPlayed.excluding(reading.trackIdentity)
            publishHistory()
            publishQueue()
        }
    }

    private fun onLeftMedia() {
        Log.d(TAG, "left media for a plain page")
        forgetPauseAwaitingVerdict()
        focusRegain.cancel()
        stateStore.playbackInterrupted = false
        handler.removeCallbacks(saveWhilePlaying)
        saveState()
        scheduleIdleDestroy()
    }

    private fun positionOf(reading: MediaReadingDto, playback: PlaybackSnapshot): Int =
        if (playback.element == MediaElementState.NONE) reading.positionSeconds.toInt()
        else playback.trustedPositionSeconds.toInt()

    private fun onVerdictChanged(playback: PlaybackSnapshot) {
        if (pauseAwaitingVerdict != null) settlePauseAwaitingVerdict(playback)
    }

    private fun awaitVerdictForPause(commandedByUs: Boolean, playback: PlaybackSnapshot) {
        pauseAwaitingVerdict = commandedByUs
        handler.removeCallbacks(confirmPauseWithoutVerdict)
        handler.postDelayed(confirmPauseWithoutVerdict, AWAIT_PAUSE_VERDICT_MILLIS)
        settlePauseAwaitingVerdict(playback)
    }

    private fun settlePauseAwaitingVerdict(playback: PlaybackSnapshot) {
        if (pauseAwaitingVerdict == null) return
        if (playback.verdict == PlaybackVerdict.INTERRUPTED || playback.verdict == PlaybackVerdict.PLAYING) {
            Log.d(TAG, "reading stopped, waiting for the verdict (${playback.verdict})")
            return
        }
        confirmPauseAwaitingVerdict()
    }

    private fun confirmPauseAwaitingVerdict() {
        val commandedByUs = pauseAwaitingVerdict ?: return
        forgetPauseAwaitingVerdict()
        val playback = currentPlayback
        if (playback.element != MediaElementState.NONE) lastPositionSeconds = playback.trustedPositionSeconds.toInt()
        onReadingStoppedPlaying(commandedByUs)
    }

    private fun forgetPauseAwaitingVerdict() {
        if (pauseAwaitingVerdict != null) mediaSession.report(RendererEventDto.Interrupted(false))
        pauseAwaitingVerdict = null
        handler.removeCallbacks(confirmPauseWithoutVerdict)
    }

    private fun onReadingStartedPlaying() {
        Log.d(TAG, "playing")
        forgetPauseAwaitingVerdict()
        cancelLateVoiceSearchFailure()
        pendingPlay = false
        focusRegain.cancel()
        completeVoiceSearch()
        stateStore.playbackInterrupted = true
        handler.removeCallbacks(giveUpPendingPlay)
        handler.removeCallbacks(destroyWhenIdle)
        handler.removeCallbacks(saveWhilePlaying)
        handler.postDelayed(saveWhilePlaying, SAVE_WHILE_PLAYING_MILLIS)
    }

    private fun onReadingStoppedPlaying(commandedByUs: Boolean) {
        Log.d(TAG, "paused")
        if (commandedByUs) focusRegain.cancel() else focusRegain.startIfFocusWasTaken()
        stateStore.playbackInterrupted = false
        handler.removeCallbacks(saveWhilePlaying)
        saveState()
        scheduleIdleDestroy()
    }

    override fun openBrowser() {
        Log.d(TAG, "command openBrowser, screenAttached=$isScreenAttached")
        CarScreenLauncher.open(appContext)
    }

    override fun wake(command: MediaCommandDto) = execute(command)

    override fun admits(command: MediaCommandDto, caller: String): Boolean {
        commandAdmittedWithOrigin = null
        val kind = observedKind(command) ?: return true
        val admitted = browser.observeCommand(kind, caller)
        if (admitted) commandAdmittedWithOrigin = command
        return admitted
    }

    private fun observedKind(command: MediaCommandDto): ObservedCommandKind? = when (command) {
        MediaCommandDto.Play -> ObservedCommandKind.PLAY
        MediaCommandDto.Pause -> ObservedCommandKind.PAUSE
        MediaCommandDto.Stop -> ObservedCommandKind.STOP
        else -> null
    }

    private fun observeCommandFromApp(command: MediaCommandDto) {
        if (commandAdmittedWithOrigin == command) {
            commandAdmittedWithOrigin = null
            return
        }
        observedKind(command)?.let { browser.observeCommand(it, APP_ORIGIN) }
    }

    override fun execute(command: MediaCommandDto) {
        observeCommandFromApp(command)
        when (command) {
            MediaCommandDto.Play -> play()
            MediaCommandDto.Pause -> pause()
            MediaCommandDto.Stop -> stop()
            MediaCommandDto.SkipToNext -> skipToNext()
            MediaCommandDto.SkipToPrevious -> skipToPreviousOrRestart()
            MediaCommandDto.FastForward -> fastForward()
            MediaCommandDto.Rewind -> rewind()
            is MediaCommandDto.SeekTo -> seekTo(command.positionMillis)
            is MediaCommandDto.PlayFromSearch -> playFromSearch(command.query)
            is MediaCommandDto.SkipToQueueItem -> skipToQueueItem(command.id)
        }
    }

    private fun play() {
        Log.d(TAG, "command play, hasWebView=${browser.hasView}")
        focusRegain.cancel()
        handler.removeCallbacks(destroyWhenIdle)
        if (!browser.hasView) {
            startFromSavedState()
            return
        }
        if (dispatch(CarEvent.PLAY)) return
        if (pageHasMedia()) {
            browser.mediaPlay()
            return
        }
        if (pageLoadingComplete) browser.reload()
    }

    private fun pause() {
        Log.d(TAG, "command pause, hasWebView=${browser.hasView}")
        cancelVoiceSearch()
        if (!browser.hasView) return
        if (dispatch(CarEvent.PAUSE)) return
        if (pageHasMedia()) {
            browser.mediaPause()
            return
        }
        browser.stopLoading()
    }

    private fun stop() {
        Log.d(TAG, "command stop")
        focusRegain.cancel()
        cancelVoiceSearch()
        if (browser.isAttached) {
            pause()
            return
        }
        destroy("stop")
        stateStore.playbackInterrupted = false
        PlaybackBrowserService.cancelPlaybackNotification(appContext)
    }

    private fun skipToNext() {
        expectTrackChange()
        dispatch(CarEvent.NEXT_CLICK)
    }

    private fun skipToPreviousOrRestart() {
        val state = mediaSession.state
        val hasPrevious = MediaAction.SKIP_TO_PREVIOUS in state.capabilities
        if (PreviousAction.decide(state.progress.positionMillis, hasPrevious) == PreviousAction.RESTART) {
            restart()
        } else {
            skipToPrevious()
        }
    }

    private fun skipToPrevious() {
        expectTrackChange()
        dispatch(CarEvent.PREVIOUS_CLICK)
    }

    private fun expectTrackChange() {
        if (!isPlaying) return
        pendingPlay = true
        armGiveUpPendingPlay()
    }

    private fun armGiveUpPendingPlay() {
        handler.removeCallbacks(giveUpPendingPlay)
        handler.postDelayed(giveUpPendingPlay, PENDING_PLAY_TIMEOUT_MILLIS)
    }

    private fun fastForward() {
        if (!browser.hasView) return
        if (!dispatch(CarEvent.NEXT_LONG_PRESS)) browser.mediaSeekBy(10)
    }

    private fun rewind() {
        if (!browser.hasView) return
        if (!dispatch(CarEvent.PREVIOUS_LONG_PRESS)) browser.mediaSeekBy(-10)
    }

    private fun seekTo(positionMillis: Long) {
        if (!browser.hasView) return
        val seconds = (positionMillis / 1000).toInt()
        if (dispatch(CarEvent.SEEK_TO, seconds.toString())) return
        browser.mediaSeekTo(seconds)
    }

    private fun restart() {
        if (!browser.hasView) return
        if (dispatch(CarEvent.RESTART)) return
        if (pageHasMedia()) {
            browser.mediaSeekTo(0)
        } else {
            browser.reload()
        }
    }

    private fun skipToQueueItem(id: Long) {
        if (!browser.hasView) return
        if (id < 0) {
            if (id == previousPageId()) {
                browser.goBack()
                return
            }
            recentlyPlayed.pageUrlFor(id)?.let { browser.open(it) }
            return
        }
        if (mediaSession.state.queue.shape == QueueShape.NONE) {
            goToHistoryItem(id)
            return
        }
        dispatch(CarEvent.QUEUE_ITEM, id.toString())
    }

    private fun pageHasMedia() = lastReadingHasMedia || !readingSeenForDocument

    private fun browsingHistory(): MediaQueueDto {
        if (!browser.hasView) return MediaQueueDto.EMPTY
        val history = QueueResolution.toMediaOnly(browser.history, mediaUrls)
        val entries = history.entries.map { entry ->
            val host = hostOf(entry.url) ?: ""
            QueueEntryDto(
                id = entry.rawIndex.toLong(),
                title = entry.title?.takeIf { it.isNotEmpty() } ?: host,
                subtitle = host,
                iconUrl = ""
            )
        }
        return MediaQueueDto(
            title = appContext.getString(R.string.car_media_queue_history),
            entries = entries.asReversed(),
            cursor = entries.size - 1 - history.currentPosition,
            shape = QueueShape.NONE
        )
    }

    private fun hostOf(url: String): String? = Uri.parse(url).host?.removePrefix("www.")

    private fun previousTrack(): QueueEntryDto? = previousPage() ?: recentlyPlayedEntries.lastOrNull()

    private fun previousPage(): QueueEntryDto? {
        val entry = historyQueue.entries.getOrNull(historyQueue.cursor + 1) ?: return null
        val id = previousPageId() ?: return null
        return entry.copy(id = id)
    }

    private fun previousPageId(): Long? {
        val history = browser.history
        val previous = history.entries.getOrNull(history.currentPosition - 1) ?: return null
        return RecentlyPlayed.idFor(previous.url)
    }

    private fun publishHistoryQueue() {
        historyQueue = browsingHistory()
        publishQueue()
    }

    private fun goToHistoryItem(id: Long) {
        browser.goToHistoryEntry(id.toInt())
    }

    fun resumeInterruptedPlayback() {
        if (isPlaying || pendingPlay || !stateStore.playbackInterrupted) return
        Log.d(TAG, "resuming the playback interrupted when the car went away")
        execute(MediaCommandDto.Play)
    }

    private fun playFromSearch(query: String) {
        Log.d(TAG, "command playFromSearch, hasWebView=${browser.hasView}")
        handler.removeCallbacks(destroyWhenIdle)
        val request = VoiceSearchRequest.create(++nextVoiceSearchId, query, SystemClock.elapsedRealtime())
        voiceSearch = request
        pendingPlay = true
        armGiveUpPendingPlay()
        mediaSession.report(RendererEventDto.Preparing(query))
        PlaybackBrowserService.updatePlaybackNotification(appContext, force = true)
        handler.removeCallbacks(voiceSearchTimeout)
        handler.postDelayed(voiceSearchTimeout, VoiceSearchRequest.TOTAL_TIMEOUT_MILLIS)
        val landing = voiceLanding(query)
        pendingVoiceScript = landing.pluginId?.let { id -> landing.script?.let { id to it } }
        browser.open(landing.url)
    }

    private fun voiceLanding(query: String): VoiceLanding {
        val engines = searchEngines ?: error("Search engines are not configured")
        return VoiceDestination(engines).of(
            stack = plugins.stack(),
            selectedPluginId = SettingsUtils.getVoicePluginId(appContext),
            query = query,
            contextUrl = browser.url ?: initialUrl()
        )
    }

    private fun runPendingVoiceScript() {
        val (pluginId, script) = pendingVoiceScript ?: return
        pendingVoiceScript = null
        pluginInjector.runVoiceScript(pluginId, script)
    }

    private fun startFromSavedState() {
        pendingPlay = true
        browser.create()
    }

    private fun onVoiceSearchProgress(id: Long, generation: Long, stage: String) {
        val request = voiceSearch ?: return
        if (!request.belongsTo(id, generation)) return
        when (stage) {
            "confirmed", "searching" -> markVoiceSearch(request, VoiceSearchRequest.Stage.SEARCHING, stage)
            "result" -> markVoiceSearch(request, VoiceSearchRequest.Stage.OPENING_RESULT, stage)
            "playRequested" -> markVoiceSearch(request, VoiceSearchRequest.Stage.WAITING_FOR_PLAYBACK, stage)
            "failed" -> failVoiceSearch("plugin failed")
        }
    }

    private fun markVoiceSearch(request: VoiceSearchRequest, stage: VoiceSearchRequest.Stage, event: String) {
        voiceSearch = request.advance(stage)
        Log.d(TAG, "voiceSearch id=${request.id} stage=$event generation=${request.documentGeneration}")
        mediaSession.report(RendererEventDto.Preparing(request.query))
    }

    private fun cancelLateVoiceSearchFailure() {
        lateVoiceSearchFailure?.let { handler.removeCallbacks(it) }
        lateVoiceSearchFailure = null
    }

    private fun completeVoiceSearch() {
        voiceSearch?.let { Log.d(TAG, "voiceSearch id=${it.id} completed") }
        cancelVoiceSearch()
    }

    private fun failVoiceSearch(reason: String) {
        val request = voiceSearch ?: return
        Log.w(TAG, "voiceSearch id=${request.id} failed: $reason")
        cancelVoiceSearch()
        val message = appContext.getString(R.string.car_media_voice_search_failed, request.query)
        if (request.stage == VoiceSearchRequest.Stage.WAITING_FOR_PLUGIN ||
            request.stage == VoiceSearchRequest.Stage.SEARCHING
        ) {
            announceVoiceSearchFailure(message)
        } else {
            handler.postDelayed(announceLateFailure(message), VOICE_SEARCH_FAILURE_GRACE_MILLIS)
        }
        scheduleIdleDestroy()
    }

    private fun announceLateFailure(message: String) = Runnable {
        if (!isPlaying) announceVoiceSearchFailure(message)
    }.also { lateVoiceSearchFailure = it }

    private fun announceVoiceSearchFailure(message: String) {
        mediaSession.report(RendererEventDto.Failed(message))
        handler.postDelayed({ mediaSession.report(RendererEventDto.Failed(null)) }, VOICE_SEARCH_ERROR_DISPLAY_MILLIS)
    }

    private fun cancelVoiceSearch() {
        handler.removeCallbacks(voiceSearchTimeout)
        voiceSearch = null
        pendingVoiceScript = null
        pluginInjector.clearVoiceSearchContext()
        mediaSession.report(RendererEventDto.Preparing(null))
    }
}
