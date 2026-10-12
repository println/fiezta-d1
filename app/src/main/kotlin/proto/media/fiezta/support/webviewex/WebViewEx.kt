package proto.media.fiezta.support.webviewex

import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebBackForwardList
import android.webkit.WebHistoryItem
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import proto.media.fiezta.support.webviewex.document.DocumentBridge
import proto.media.fiezta.support.webviewex.document.DocumentLifecycle
import proto.media.fiezta.support.webviewex.document.DocumentStage
import proto.media.fiezta.support.webviewex.document.ScriptHost
import proto.media.fiezta.support.webviewex.error.ErrorPageRenderer
import proto.media.fiezta.support.webviewex.error.ErrorPageStyle
import proto.media.fiezta.support.webviewex.external.ExternalSchemeHandler
import proto.media.fiezta.support.webviewex.external.ExternalSchemePolicy
import proto.media.fiezta.support.webviewex.external.UrlKind
import proto.media.fiezta.support.webviewex.gearhead.GuardAction
import proto.media.fiezta.support.webviewex.gearhead.ObservedCommandKind
import proto.media.fiezta.support.webviewex.gearhead.PlaybackSnapshot
import proto.media.fiezta.support.webviewex.gearhead.WebViewExGearhead
import proto.media.fiezta.support.webviewex.internal.BrowserClientHooks
import proto.media.fiezta.support.webviewex.internal.BrowserClients
import proto.media.fiezta.support.webviewex.internal.BrowserSettings
import proto.media.fiezta.support.webviewex.media.MediaElementEvent
import proto.media.fiezta.support.webviewex.media.MediaSessionCommands
import proto.media.fiezta.support.webviewex.navigation.AddressResolver
import proto.media.fiezta.support.webviewex.navigation.HostName
import proto.media.fiezta.support.webviewex.navigation.NavigationFailure
import proto.media.fiezta.support.webviewex.navigation.NavigationOrigin
import proto.media.fiezta.support.webviewex.navigation.NavigationState
import proto.media.fiezta.support.webviewex.navigation.NavigationTracker
import proto.media.fiezta.support.webviewex.navigation.NavigationTransaction
import proto.media.fiezta.support.webviewex.navigation.PreferencesSchemeStore
import proto.media.fiezta.support.webviewex.navigation.RecoveryPolicy
import proto.media.fiezta.support.webviewex.navigation.Scheme
import proto.media.fiezta.support.webviewex.navigation.SchemeMemory
import proto.media.fiezta.support.webviewex.navigation.SchemePolicy
import proto.media.fiezta.support.webviewex.navigation.WindowAction
import proto.media.fiezta.support.webviewex.navigation.WindowPolicy
import proto.media.fiezta.support.webviewex.presentation.RenderMode

class WebViewEx<W : VideoWebView>(
    context: Context,
    val config: WebViewExConfig,
    private val host: WebViewExHost,
    texts: WebViewExTexts,
    private val delegate: WebViewExDelegate,
    private val webViewFactory: (Context) -> W,
    private val chromeClientFactory: () -> WebViewExChromeClient = { WebViewExChromeClient() },
    private val schemeMemory: SchemeMemory = SchemeMemory(PreferencesSchemeStore(context)),
    private val recoveryPolicy: RecoveryPolicy = RecoveryPolicy(),
    private val gearhead: WebViewExGearhead? = null
) {

    private val appContext: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<WebViewExListener>()
    private val bridges = LinkedHashMap<String, Any>()

    private val tracker = NavigationTracker()
    private val lifecycle = DocumentLifecycle()
    private val externalHandler = ExternalSchemeHandler(appContext, gearhead?.allowsExternalApps ?: true)
    private val errorPage = ErrorPageRenderer(
        appContext, texts, gearhead?.errorPageStyle ?: ErrorPageStyle.DETAILED, ::onErrorAction
    )
    private val bootstrapSource: String by lazy {
        appContext.assets.open(BOOTSTRAP_ASSET).bufferedReader().use { it.readText() }
    }

    private inner class Hooks : BrowserClientHooks, ChromeHooks {
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = handlePageStarted(view, url, favicon)
        override fun onContentPainted(url: String?) {
            if (!currentDocumentIsError) tracker.onContentPainted(url)
        }

        override fun onPageFinished(view: WebView, url: String?) = handlePageFinished(view, url)
        override fun onVisitedHistoryUpdated(url: String?, isReload: Boolean) = handleVisitedHistory(url, isReload)
        override fun onMainFrameError(url: String, errorCode: Int, description: String) =
            handleMainFrameError(url, errorCode, description)
        override fun onSslError(handler: SslErrorHandler, error: SslError) = handleSslError(handler, error)
        override fun shouldBlockRequest(url: String) = shouldBlock(url)
        override fun onMainFrameLink(url: String, isRedirect: Boolean, method: String) =
            handleMainFrameLink(url, isRedirect, method)
        override fun onUnhandledKeyEvent(event: KeyEvent) = handleUnhandledKey(event)
        override fun onRenderProcessGone(view: WebView) = handleRenderProcessGone(view)
        override fun onProgress(percent: Int) = handleProgress(percent)
        override fun onIconReceived(url: String?, icon: Bitmap) {
            if (!currentDocumentIsError) listeners.forEach { it.onIconReceived(url, icon) }
        }
        override fun onCreateWindow(view: WebView, isUserGesture: Boolean, resultMsg: Message) =
            handleCreateWindow(view, isUserGesture, resultMsg)
    }

    private val logPrefix = "[${config.name}]"

    private val hooks = Hooks()
    private var webView: W? = null
    private var contextWrapper: MutableContextWrapper? = null
    private var container: ViewGroup? = null

    private var currentDocumentIsError = false
    private var lastProgress = 0
    private var publishedLoading = LoadingState.IDLE
    private var errorDocumentPending = false
    private var failedTransaction: NavigationTransaction? = null
    private var currentFailure: NavigationFailure? = null

    private var pendingRecovery: Runnable? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkWakeups = 0
    private val renderRecoveries = mutableListOf<Long>()

    private var lastAttachedWidth = 0
    private var lastAttachedHeight = 0

    @Volatile
    private var currentHost: String? = null

    val scripts = ScriptHost(lifecycle) { script ->
        val view = webView
        view?.evaluateJavascript(script, null)
        view != null
    }

    var renderMode: RenderMode = RenderMode.BACKGROUND
        private set

    init {
        gearhead?.observePlayback { playback -> listeners.forEach { it.onPlaybackVerdict(playback) } }
    }

    val playback: PlaybackSnapshot?
        get() = gearhead?.playback

    fun observeCommand(kind: ObservedCommandKind, origin: String): Boolean =
        gearhead?.admits(kind, origin) ?: true

    val hasView: Boolean
        get() = webView != null

    val isAttached: Boolean
        get() = container != null

    var chromeClient: WebViewExChromeClient? = null
        private set

    val screenView: View?
        get() = webView

    val url: String?
        get() = webView?.url

    val title: String?
        get() = webView?.title?.takeUnless { it.startsWith(ErrorPageRenderer.TITLE_MARKER) }

    val session: BrowserSession
        get() {
            val view = webView
            val pageUrl = view?.url
            val current = tracker.current
            return BrowserSession(
                url = pageUrl,
                host = hostOf(pageUrl),
                title = title,
                isSecure = pageUrl != null && Scheme.of(pageUrl) == Scheme.HTTPS,
                isLoading = loading.isLoading,
                failure = currentFailure,
                isErrorDocument = currentDocumentIsError
            )
        }

    val loading: LoadingState
        get() =
            if (webView == null || currentDocumentIsError) LoadingState.IDLE
            else LoadingState.of(tracker.current, lastProgress)

    val history: BrowserHistory
        get() {
            val list = webView?.copyBackForwardList() ?: return BrowserHistory.EMPTY
            val entries = (0 until list.size)
                .filterNot { isSkippableEntry(list, it) }
                .map { index -> list.getItemAtIndex(index).let { HistoryEntry(index, it.url, it.title) } }
            val position = entries.indexOfLast { it.rawIndex <= list.currentIndex }
            return BrowserHistory(entries, position)
        }

    fun observe(listener: WebViewExListener) {
        listeners.add(listener)
    }

    fun removeObserver(listener: WebViewExListener) {
        listeners.remove(listener)
    }

    fun addBridge(name: String, bridge: Any) {
        bridges[name] = bridge
        webView?.addJavascriptInterface(bridge, name)
    }

    fun create(windowContext: Context = appContext, loadInitial: Boolean = true) {
        if (webView == null) createView(windowContext, loadInitial, onScreen = container != null)
    }

    private fun createView(windowContext: Context, loadInitial: Boolean, onScreen: Boolean): W {
        val wrapper = MutableContextWrapper(gearhead?.creationContext() ?: windowContext)
        val created = webViewFactory(wrapper)
        contextWrapper = wrapper
        webView = created
        Log.d(TAG, "$logPrefix WebView created")
        configure(created)
        if (!onScreen) hostWithoutScreen(created)
        listeners.forEach { it.onViewCreated() }
        applyRenderMode(if (onScreen) RenderMode.FOREGROUND else RenderMode.BACKGROUND, force = true)
        if (loadInitial) delegate.initialUrl()?.let { open(it) }
        return created
    }

    fun attach(container: ViewGroup, windowContext: Context) {
        val created = webView ?: createView(windowContext, loadInitial = true, onScreen = true)
        contextWrapper?.baseContext = windowContext
        this.container = container
        addTo(container, created)
        gearhead?.onAttachedToScreen(created, container)
        applyRenderMode(RenderMode.FOREGROUND)
        created.requestLayout()
    }

    fun detach() {
        val current = webView
        container = null
        if (current == null) return
        rememberForegroundSize(current)
        (current.parent as? ViewGroup)?.removeView(current)
        hostWithoutScreen(current)
        applyRenderMode(RenderMode.BACKGROUND)
    }

    private fun hostWithoutScreen(view: W) {
        contextWrapper?.baseContext = gearhead?.hostWithoutScreen(view) ?: appContext
    }

    fun destroy() {
        val current = webView ?: return
        cancelRecovery()
        tracker.abort()
        (current.parent as? ViewGroup)?.removeView(current)
        gearhead?.onViewDestroyed(current)
        current.destroy()
        Log.d(TAG, "$logPrefix WebView destroyed")
        webView = null
        contextWrapper = null
        currentHost = null
        currentDocumentIsError = false
        errorDocumentPending = false
        currentFailure = null
        failedTransaction = null
        errorPage.invalidate()
        lifecycle.begin()
        publishLoading()
    }

    fun navigate(input: String, search: ((String) -> String)? = null): NavigationTransaction {
        val address = AddressResolver.resolveDetailed(input, search ?: config.searchUrl)
        val remembered = schemeMemory.remembered(hostOf(address.url))
        val url = SchemePolicy.initialUrl(address, remembered)
        return start(NavigationOrigin.APP, input, url, address.schemeGuessed)
    }

    fun loadUrl(url: String): NavigationTransaction = navigate(url)

    fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>): NavigationTransaction {
        val address = AddressResolver.resolveDetailed(url, config.searchUrl)
        val remembered = schemeMemory.remembered(hostOf(address.url))
        val resolvedUrl = SchemePolicy.initialUrl(address, remembered)
        return start(NavigationOrigin.APP, url, resolvedUrl, address.schemeGuessed, headers = additionalHttpHeaders)
    }

    fun loadData(data: String, mimeType: String? = null, encoding: String? = null) {
        webView?.loadData(data, mimeType, encoding)
    }

    fun loadDataWithBaseURL(baseUrl: String?, data: String, mimeType: String?, encoding: String?, historyUrl: String?) {
        webView?.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl)
    }

    fun open(url: String): NavigationTransaction? {
        if (Scheme.of(url) == null) {
            Log.w(TAG, "$logPrefix refused to open $url; falling back")
            return openFallback()
        }
        return start(NavigationOrigin.APP, url, url, schemeGuessed = false)
    }

    private fun openFallback(): NavigationTransaction? {
        val fallback = delegate.fallbackUrl() ?: return null
        return start(NavigationOrigin.APP, fallback, fallback, schemeGuessed = false)
    }

    fun reload() {
        val current = webView ?: return
        current.url?.let(::applyUserAgentFor)
        current.reload()
    }

    fun stopLoading() {
        cancelRecovery()
        tracker.abort()
        webView?.stopLoading()
        publishLoading()
    }

    fun evaluateJavascript(script: String, callback: ((String) -> Unit)? = null) {
        webView?.evaluateJavascript(script, callback?.let { c -> ValueCallback { result -> c(result) } })
    }

    fun clearHistory() {
        webView?.clearHistory()
    }

    fun clearFormData() {
        webView?.clearFormData()
    }

    fun canGoBack(): Boolean = previousEntryIndex() != null

    fun goBack(): Boolean {
        val current = webView ?: return false
        val list = current.copyBackForwardList()
        logHistory(list)
        val target = previousEntryIndex()
        if (target == null) {
            Log.d(TAG, "$logPrefix goBack: no entry before ${list.currentIndex}")
            return false
        }
        Log.d(TAG, "$logPrefix goBack: ${list.currentIndex} -> $target")
        list.getItemAtIndex(target)?.url?.let(::applyUserAgentFor)
        current.goBackOrForward(target - list.currentIndex)
        return true
    }

    fun canGoForward(): Boolean = webView?.canGoForward() == true

    fun goForward() {
        val current = webView ?: return
        val list = current.copyBackForwardList()
        list.getItemAtIndex(list.currentIndex + 1)?.url?.let(::applyUserAgentFor)
        current.goForward()
    }

    fun goToHistoryEntry(rawIndex: Int) {
        val current = webView ?: return
        val list = current.copyBackForwardList()
        if (rawIndex < 0 || rawIndex >= list.size) return
        list.getItemAtIndex(rawIndex)?.url?.let(::applyUserAgentFor)
        current.goBackOrForward(rawIndex - list.currentIndex)
    }

    fun pinToHttps(host: String?) = schemeMemory.pinToHttps(host)

    fun requestFocus(): Boolean = webView?.requestFocus() ?: false

    fun hasFocus(): Boolean = webView?.hasFocus() == true

    fun setOnKeyListener(listener: View.OnKeyListener?) {
        webView?.setOnKeyListener(listener)
    }

    fun setBackgroundColor(color: Int) {
        webView?.setBackgroundColor(color)
    }

    fun mediaPlay() {
        evaluateJavascript(MediaSessionCommands.play())
    }

    fun mediaPause() {
        evaluateJavascript(MediaSessionCommands.pause())
    }

    fun mediaSeekBy(seconds: Int) {
        evaluateJavascript(MediaSessionCommands.seekBy(seconds))
    }

    fun mediaSeekTo(seconds: Int) {
        evaluateJavascript(MediaSessionCommands.seekTo(seconds))
    }

    fun observeMedia() {
        evaluateJavascript(MediaSessionCommands.observe())
    }

    fun markMediaInterrupted() {
        evaluateJavascript(MediaSessionCommands.markInterrupted())
    }

    fun resumeMediaIfInterrupted() {
        evaluateJavascript(MediaSessionCommands.resumeIfInterrupted())
    }

    fun isVideoFullscreen(): Boolean = webView?.isVideoFullscreen() == true

    fun exitFullScreen() {
        webView?.exitFullScreen()
    }

    fun bindVideoChrome(nonVideoView: View?, videoContainer: ViewGroup?, loadingView: View?) {
        (chromeClient as? WebViewExChromeClient)?.bindViews(nonVideoView, videoContainer, loadingView, webView)
    }

    fun unbindVideoChrome() {
        (chromeClient as? WebViewExChromeClient)?.unbindViews()
    }

    fun setInitialScale(percent: Int) {
        webView?.setInitialScale(percent)
    }

    fun zoomBy(factor: Float) {
        webView?.zoomBy(factor)
    }

    fun enterKeyboardText(text: String) {
        val view = webView ?: return
        gearhead?.enterKeyboardText(view, text)
    }

    fun sendKeyboardEnter() {
        val view = webView ?: return
        gearhead?.sendKeyboardEnter(view)
    }

    fun scrollActiveElementIntoView(alignToTop: Boolean) {
        val view = webView ?: return
        gearhead?.scrollActiveElementIntoView(view, alignToTop)
    }

    fun setAspectRatio(mode: String) {
        val view = webView ?: return
        gearhead?.setAspectRatio(view, mode)
    }

    fun requestFullScreen() {
        val view = webView ?: return
        gearhead?.requestFullScreen(view)
    }

    fun flushCookies() {
        CookieManager.getInstance().flush()
    }

    fun capturePage(sizeDp: Float, callback: (ByteArray?) -> Unit) {
        val view = webView
        if (view == null) {
            callback(null)
            return
        }
        view.scrollTo(0, 0)
        view.post {
            val width = view.measuredWidth
            val height = view.measuredHeight
            if (width <= 0 || height <= 0) {
                callback(null)
                return@post
            }
            var bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val aspectRatio = width.toFloat() / height.toFloat()
            view.draw(Canvas(bitmap))
            val px = 3 * TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, sizeDp, view.resources.displayMetrics)
            bitmap = Bitmap.createScaledBitmap(bitmap, px.toInt(), (px / aspectRatio).toInt(), false)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
            callback(stream.toByteArray())
        }
    }

    private fun start(
        origin: NavigationOrigin,
        input: String,
        url: String,
        schemeGuessed: Boolean,
        fellBackToHttp: Boolean = false,
        recoveryAttempts: Int = 0,
        headers: Map<String, String> = emptyMap()
    ): NavigationTransaction {
        val current = webView ?: createView(container?.context ?: appContext, loadInitial = false, onScreen = container != null)
        cancelRecovery()
        if (origin != NavigationOrigin.RECOVERY) networkWakeups = 0
        currentFailure = null
        Log.d(TAG, "$logPrefix navigate[$origin] $url attempt=$recoveryAttempts")
        val transaction = tracker.begin(origin, input, url, "GET", schemeGuessed, fellBackToHttp, recoveryAttempts)
        applyUserAgentFor(url)
        lastProgress = 0
        if (headers.isEmpty()) current.loadUrl(url) else current.loadUrl(url, headers)
        publishLoading()
        return transaction
    }

    private fun configure(created: W) {
        BrowserSettings.apply(created, config, host, gearhead)
        created.carInputHooks = gearhead?.let { g -> { view: WebView -> g.onDpadCenterOrTap(view) } }
        val chrome = chromeClientFactory()
        chrome.hooks = hooks
        chromeClient = chrome
        created.webViewClient = BrowserClients(hooks)
        created.webChromeClient = chrome
        created.addJavascriptInterface(DocumentBridge(::onDocumentStage, ::onMediaElementEvent), DocumentBridge.NAME)
        created.addJavascriptInterface(errorPage.bridge, ErrorPageRenderer.BRIDGE_NAME)
        bridges.forEach { (name, bridge) -> created.addJavascriptInterface(bridge, name) }
        applyUserAgentFor("")
    }

    private fun addTo(container: ViewGroup, view: W) {
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun applyUserAgentFor(url: String) {
        val current = webView ?: return
        val pageHost = hostOf(url)
        BrowserSettings.applyUserAgent(appContext, current, host.prefersDesktop(pageHost), host.stripWebViewMarker())
    }

    private fun hostOf(url: String?): String? = HostName.of(url)

    private fun previousEntryIndex(): Int? {
        val list = webView?.copyBackForwardList() ?: return null
        return (list.currentIndex - 1 downTo 0).firstOrNull { !isSkippableEntry(list, it) }
    }

    private fun isSkippableEntry(list: WebBackForwardList, index: Int): Boolean {
        if (isErrorEntry(list.getItemAtIndex(index))) return true
        val next = index + 1
        return next < list.size && isErrorEntry(list.getItemAtIndex(next))
    }

    private fun isErrorEntry(item: WebHistoryItem): Boolean =
        item.title?.startsWith(ErrorPageRenderer.TITLE_MARKER) == true

    private fun logHistory(list: WebBackForwardList) {
        val entries = (0 until list.size).joinToString(" | ") { index ->
            val item = list.getItemAtIndex(index)
            val mark = if (isSkippableEntry(list, index)) "skip " else ""
            "$index:$mark${item.url}"
        }
        Log.d(TAG, "$logPrefix history[${list.currentIndex}] $entries")
    }

    private fun applyRenderMode(mode: RenderMode, force: Boolean = false) {
        if (mode == renderMode && !force) return
        renderMode = mode
        val current = webView
        if (mode == RenderMode.BACKGROUND) layoutOffscreen(foregroundWidth(), foregroundHeight())
        if (current != null) gearhead?.onRenderModeChanged(current, mode)
        listeners.forEach { it.onRenderModeChanged(mode) }
    }

    private fun layoutOffscreen(widthPixels: Int, heightPixels: Int) {
        val current = webView ?: return
        if (current.parent != null) return
        current.measure(
            View.MeasureSpec.makeMeasureSpec(widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPixels, View.MeasureSpec.EXACTLY)
        )
        current.layout(0, 0, widthPixels, heightPixels)
    }

    private fun rememberForegroundSize(view: W) {
        if (view.width > 0 && view.height > 0) {
            lastAttachedWidth = view.width
            lastAttachedHeight = view.height
        }
    }

    private fun foregroundWidth(): Int =
        if (lastAttachedWidth > 0) lastAttachedWidth else appContext.resources.displayMetrics.widthPixels

    private fun foregroundHeight(): Int =
        if (lastAttachedHeight > 0) lastAttachedHeight else appContext.resources.displayMetrics.heightPixels

    private fun handlePageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        val address = url ?: ""
        if (errorDocumentPending) {
            errorDocumentPending = false
            currentDocumentIsError = true
            lifecycle.begin()
            val transaction = tracker.current ?: tracker.begin(NavigationOrigin.PAGE, address, address)
            publishLoading()
            listeners.forEach { it.onNavigationStarted(transaction, address, favicon, true) }
            return
        }
        currentDocumentIsError = false
        errorPage.invalidate()
        val transaction = tracker.onPageStarted(address)
        currentHost = hostOf(address)
        val documentId = lifecycle.begin()
        view.evaluateJavascript(bootstrapSource.replace(DOCUMENT_ID_PLACEHOLDER, documentId), null)
        publishLoading()
        listeners.forEach { it.onNavigationStarted(transaction, address, favicon, false) }
        onDocumentStage(documentId, DocumentStage.COMMITTED, address)
    }

    private fun handlePageFinished(view: WebView, url: String?) {
        val isErrorDocument = currentDocumentIsError || tracker.current?.state == NavigationState.FAILED
        if (isErrorDocument) {
            publishLoading()
            listeners.forEach { it.onPageFinished(url, true) }
            return
        }
        val finished = tracker.onPageFinished(url)
        if (finished != null && url != null) {
            SchemePolicy.schemeToRemember(finished, url)?.let { schemeMemory.remember(hostOf(url), it) }
        }
        onDocumentStage(lifecycle.documentId, DocumentStage.LOADED, url)
        publishLoading()
        listeners.forEach { it.onPageFinished(url, false) }
    }

    private fun handleVisitedHistory(url: String?, isReload: Boolean) {
        listeners.forEach { it.onVisitedHistoryUpdated(url, isReload, currentDocumentIsError) }
    }

    private fun handleMainFrameError(url: String, errorCode: Int, description: String) {
        Log.w(TAG, "$logPrefix main frame error url=$url code=$errorCode desc=$description")
        if (ABORT_MARKER in description) return
        val transaction = tracker.mainFrameFailure(url) ?: return
        val failure = NavigationFailure.classify(errorCode, description, isOnline())
        handleFailure(transaction, failure, url, listOf("code $errorCode", description))
    }

    private fun handleSslError(handler: SslErrorHandler, error: SslError) {
        Log.w(TAG, "$logPrefix SSL error ${error.primaryError} on ${error.url}")
        handler.cancel()
        val transaction = tracker.strictFailure(error.url) ?: return
        handleFailure(transaction, NavigationFailure.SslError, error.url, listOf("ssl error ${error.primaryError}"))
    }

    private fun shouldBlock(url: String): Boolean {
        if (!host.isRequestBlocked(url, currentHost)) return false
        host.onRequestBlocked(url)
        return true
    }

    private fun handleMainFrameLink(url: String, isRedirect: Boolean, method: String): Boolean {
        listeners.forEach { it.onMainFrameRequested(url) }
        if (ExternalSchemePolicy.classify(url) != UrlKind.WEB) {
            handleExternal(url)
            return true
        }
        if (isRedirect) {
            tracker.redirect(url)
        } else {
            tracker.begin(NavigationOrigin.PAGE, url, url, method)
            applyUserAgentFor(url)
        }
        return false
    }

    private fun handleUnhandledKey(event: KeyEvent) {
        listeners.forEach { it.onUnhandledKeyEvent(event) }
    }

    private fun handleRenderProcessGone(view: WebView) {
        handler.post { recoverFromRenderProcessGone(view) }
    }

    private fun handleProgress(percent: Int) {
        lastProgress = percent
        publishLoading()
    }

    private fun publishLoading() {
        val state = loading
        if (state == publishedLoading) return
        publishedLoading = state
        Log.d(TAG, "$logPrefix loading=${state.isLoading} progress=${state.progress}")
        listeners.forEach { it.onLoadingChanged(state) }
    }

    private fun handleCreateWindow(view: WebView, isUserGesture: Boolean, resultMsg: Message): Boolean {
        if (WindowPolicy.decide(config.windowMode, isUserGesture) == WindowAction.DENY) return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        val popup = WebView(appContext)
        popup.webViewClient = PopupClient(popup)
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    private inner class PopupClient(private val popup: WebView) : WebViewClient() {
        private var delivered = false

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            deliver(request.url.toString())
            return true
        }

        private fun handlePageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            deliver(url)
            view.stopLoading()
        }

        private fun deliver(url: String?) {
            if (delivered || url.isNullOrBlank() || url == BLANK) return
            delivered = true
            handler.post {
                openFromPopup(url)
                popup.destroy()
            }
        }
    }

    private fun openFromPopup(url: String) {
        when {
            ExternalSchemePolicy.isWebUrl(url) -> open(url)
            ExternalSchemePolicy.classify(url) != UrlKind.WEB -> handleExternal(url)
        }
    }

    private fun handleExternal(url: String) {
        when (val outcome = externalHandler.handle(url)) {
            ExternalSchemeHandler.Outcome.Launched -> Unit
            ExternalSchemeHandler.Outcome.Denied ->
                listeners.forEach { it.onExternalLinkBlocked(url, NavigationFailure.UnsupportedScheme) }
            ExternalSchemeHandler.Outcome.NotInstalled ->
                listeners.forEach { it.onExternalLinkBlocked(url, NavigationFailure.AppNotInstalled) }
            is ExternalSchemeHandler.Outcome.LoadFallback -> open(outcome.url)
        }
    }

    private fun onMediaElementEvent(documentId: String, event: MediaElementEvent, positionSeconds: Double) {
        if (documentId != lifecycle.documentId) return
        val activeGearhead = gearhead
        if (webView != null && activeGearhead != null) {
            resumeAfterScreenTransition(activeGearhead.onMediaElementEvent(event, positionSeconds, documentId), documentId)
        }
        listeners.forEach { it.onMediaElementEvent(event, positionSeconds, documentId) }
    }

    private fun resumeAfterScreenTransition(action: GuardAction, documentId: String) {
        when (action) {
            GuardAction.None -> Unit
            GuardAction.Play -> scripts.evaluate(MediaSessionCommands.play(), documentId)
            is GuardAction.PlayFrom -> {
                scripts.evaluate(MediaSessionCommands.seekTo(action.seconds), documentId)
                scripts.evaluate(MediaSessionCommands.play(), documentId)
            }
        }
    }

    private fun onDocumentStage(documentId: String, stage: DocumentStage, url: String? = webView?.url) {
        if (!lifecycle.enter(documentId, stage)) return
        listeners.forEach { it.onDocumentStage(stage, documentId, url) }
    }

    private fun handleFailure(
        transaction: NavigationTransaction,
        failure: NavigationFailure,
        failingUrl: String,
        details: List<String>
    ) {
        val fallback = SchemePolicy.fallbackUrl(transaction, failure, schemeMemory.isPinnedToHttps(hostOf(failingUrl)))
        if (fallback != null) {
            listeners.forEach { it.onSchemeFallback(fallback, hostOf(fallback)) }
            start(transaction.origin, transaction.input, fallback, schemeGuessed = true, fellBackToHttp = true)
            return
        }
        failedTransaction = transaction
        currentFailure = failure
        Log.w(TAG, "$logPrefix navigation ${transaction.id} failed on $failingUrl: ${failure.kind} ${details.joinToString()}")
        publishLoading()
        listeners.forEach { it.onNavigationFailed(transaction, failure) }
        val recovery = scheduleRecovery(transaction, failure)
        showErrorPage(failingUrl, failure, offersHttpRetry(failingUrl, failure), details + failingUrl, recovery)
    }

    private fun showErrorPage(
        failingUrl: String,
        failure: NavigationFailure,
        offersHttpRetry: Boolean,
        details: List<String>,
        recovery: ErrorPageRenderer.Recovery?
    ) {
        val current = webView ?: return
        errorDocumentPending = true
        errorPage.render(current, failingUrl, failure, offersHttpRetry, details, recovery)
    }

    private fun offersHttpRetry(url: String, failure: NavigationFailure): Boolean =
        Scheme.of(url) == Scheme.HTTPS && (failure.isConnectionLevel || failure == NavigationFailure.Timeout)

    private fun scheduleRecovery(transaction: NavigationTransaction, failure: NavigationFailure): ErrorPageRenderer.Recovery? {
        val context = RecoveryPolicy.Context(
            requestedUrlStillCurrent = tracker.current?.id == transaction.id,
            viewAlive = webView != null,
            online = isOnline(),
            networkWakeups = networkWakeups
        )
        val decision = recoveryPolicy.decide(failure, transaction, context)
        Log.d(TAG, "$logPrefix recovery for ${transaction.id}: $decision")
        return when (decision) {
            is RecoveryPolicy.Decision.RetryAfter -> {
                val runnable = Runnable { replay(transaction, decision.attempt) }
                pendingRecovery = runnable
                handler.postDelayed(runnable, decision.millis)
                ErrorPageRenderer.Recovery(decision.attempt, (decision.millis / 1000).toInt(), waitingForNetwork = false)
            }
            RecoveryPolicy.Decision.WaitForNetwork -> {
                waitForNetwork(transaction)
                ErrorPageRenderer.Recovery(transaction.recoveryAttempts, 0, waitingForNetwork = true)
            }
            is RecoveryPolicy.Decision.GiveUp -> null
        }
    }

    private fun replay(transaction: NavigationTransaction, attempt: Int) {
        pendingRecovery = null
        if (webView == null || tracker.current?.id != transaction.id) return
        start(
            NavigationOrigin.RECOVERY, transaction.input, transaction.requestedUrl,
            transaction.schemeGuessed, transaction.fellBackToHttp, attempt
        )
    }

    private fun waitForNetwork(transaction: NavigationTransaction) {
        if (networkCallback != null) return
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post {
                    stopWatchingNetwork()
                    networkWakeups++
                    replay(transaction, 0)
                }
            }
        }
        networkCallback = callback
        try {
            manager.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "$logPrefix Cannot watch the network", e)
            networkCallback = null
        }
    }

    private fun stopWatchingNetwork() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            appContext.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "$logPrefix Network callback was already gone", e)
        }
    }

    private fun cancelRecovery() {
        pendingRecovery?.let(handler::removeCallbacks)
        pendingRecovery = null
        stopWatchingNetwork()
    }

    private fun isOnline(): Boolean = try {
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        when {
            manager == null -> true
            network == null -> false
            else -> manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
    } catch (e: SecurityException) {
        true
    }

    private fun onErrorAction(action: ErrorAction) {
        val failed = failedTransaction ?: return
        when (action) {
            ErrorAction.RETRY -> start(
                NavigationOrigin.RETRY, failed.input, failed.requestedUrl,
                failed.schemeGuessed, failed.fellBackToHttp
            )
            ErrorAction.RETRY_HTTP -> start(
                NavigationOrigin.RETRY, failed.input,
                Scheme.HTTP.prefix + failed.requestedUrl.removePrefix(Scheme.HTTPS.prefix),
                schemeGuessed = true, fellBackToHttp = true
            )
            ErrorAction.GO_BACK -> goBack()
            ErrorAction.OPEN_EXTERNALLY -> externalHandler.openInSystemBrowser(failed.requestedUrl)
            ErrorAction.CANCEL_RECOVERY -> {
                cancelRecovery()
                webView?.let { errorPage.updateRecovery(it, null, offersHttpRetry(failed.requestedUrl, currentFailure ?: return)) }
            }
        }
    }

    private fun recoverFromRenderProcessGone(gone: WebView) {
        if (gone !== webView) return
        val response = delegate.onRenderProcessGone()
        val attachedContainer = container
        listeners.forEach { it.onViewDestroying() }
        destroy()
        listeners.forEach { it.onViewDestroyed() }
        val now = SystemClock.elapsedRealtime()
        if (response != RenderGoneResponse.RECREATE || !recoveryPolicy.allowsRenderProcessRecovery(renderRecoveries, now)) return
        renderRecoveries.add(now)
        val created = createView(attachedContainer?.context ?: appContext, loadInitial = false, onScreen = attachedContainer != null)
        if (attachedContainer != null) {
            contextWrapper?.baseContext = attachedContainer.context
            addTo(attachedContainer, created)
            gearhead?.onAttachedToScreen(created, attachedContainer)
            listeners.forEach { it.onViewRecreated() }
        }
    }

    private companion object {
        const val TAG = "WebViewEx"
        const val ABORT_MARKER = "ERR_ABORTED"
        const val BLANK = "about:blank"
        const val BOOTSTRAP_ASSET = "webviewex/bootstrap.js"
        const val DOCUMENT_ID_PLACEHOLDER = "__DOCUMENT_ID__"
    }
}
