package proto.media.fiezta.support.webviewex

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Build
import android.os.Message
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.VideoView

internal interface ChromeHooks {
    fun onProgress(percent: Int)
    fun onIconReceived(url: String?, icon: Bitmap)
    fun onCreateWindow(view: WebView, isUserGesture: Boolean, resultMsg: Message): Boolean
}

open class WebViewExChromeClient : WebChromeClient(),
    MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {

    internal var hooks: ChromeHooks? = null

    private var activityNonVideoView: View? = null
    private var activityVideoView: ViewGroup? = null
    private var loadingView: View? = null
    private var webView: VideoWebView? = null

    private var videoTouchListener: View.OnTouchListener? = null
    private var isVideoFullscreen = false
    private var videoViewContainer: FrameLayout? = null
    private var videoViewCallback: CustomViewCallback? = null
    private var toggledFullscreenCallback: ToggledFullscreenCallback? = null
    private var videoStretchingEnabled = false

    var protectedContentAllowed: () -> Boolean = { true }

    fun bindViews(nonVideoView: View?, videoView: ViewGroup?, loadingView: View?, webView: VideoWebView?) {
        this.activityNonVideoView = nonVideoView
        this.activityVideoView = videoView
        this.loadingView = loadingView
        this.webView = webView
    }

    fun unbindViews() = bindViews(null, null, null, null)

    fun setVideoStretchingEnabled(videoStretchingEnabled: Boolean) {
        this.videoStretchingEnabled = videoStretchingEnabled
        handleVideoStretching()
    }

    fun getVideoTouchListener(): View.OnTouchListener? = videoTouchListener

    fun setVideoTouchListener(videoTouchListener: View.OnTouchListener?) {
        this.videoTouchListener = videoTouchListener
    }

    fun isVideoFullscreen(): Boolean = isVideoFullscreen

    fun setOnToggledFullscreen(callback: ToggledFullscreenCallback?) {
        this.toggledFullscreenCallback = callback
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        hooks?.onProgress(newProgress)
    }

    override fun onReceivedIcon(view: WebView, icon: Bitmap) {
        super.onReceivedIcon(view, icon)
        hooks?.onIconReceived(view.url, icon)
    }

    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean =
        hooks?.onCreateWindow(view, isUserGesture, resultMsg) ?: false

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        Log.d(javaClass.name, "onShowCustomView() called with: view = [$view], callback = [$callback]")
        if (view is FrameLayout) {
            val frameLayout = view
            val focusedChild = frameLayout.focusedChild
            if (focusedChild != null) {
                focusedChild.requestFocus()
                focusedChild.setOnKeyListener(View.OnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                            webView?.goBack()
                            return@OnKeyListener true
                        }
                    }
                    false
                })
            }

            this.isVideoFullscreen = true
            this.videoViewContainer = frameLayout
            this.videoViewCallback = callback

            activityNonVideoView?.visibility = View.INVISIBLE
            activityVideoView?.addView(videoViewContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            activityVideoView?.visibility = View.VISIBLE
            activityVideoView?.setBackgroundColor(Color.BLACK)
            if (focusedChild != null) {
                focusedChild.setBackgroundColor(Color.BLACK)
                videoTouchListener?.let { focusedChild.setOnTouchListener(it) }
            }

            if (focusedChild is VideoView) {
                focusedChild.setOnPreparedListener(this)
                focusedChild.setOnCompletionListener(this)
                focusedChild.setOnErrorListener(this)
            } else if (webView?.settings?.javaScriptEnabled == true) {
                handleVideoStretching()
            }

            toggledFullscreenCallback?.toggledFullscreen(true)
        }
    }

    private fun handleVideoStretching() {
        var js = "_ytrp_html5_video = document.getElementsByTagName('video')[0];"
        js += if (videoStretchingEnabled && isVideoFullscreen()) {
            "if (_ytrp_html5_video !== undefined) {" +
                "var originalWidth = _ytrp_html5_video.style.width; var originalHeight = _ytrp_html5_video.style.height; var originalTop = _ytrp_html5_video.style.top;" +
                "_ytrp_html5_video.style.objectFit='fill';" +
                " _ytrp_html5_video.style.height=window.innerHeight+'px';" +
                "_ytrp_html5_video.style.width=window.innerWidth+'px';" +
                "_ytrp_html5_video.style.top=0;"
        } else {
            "if (_ytrp_html5_video !== undefined) {" +
                "var originalWidth = _ytrp_html5_video.style.width; var originalHeight = _ytrp_html5_video.style.height; var originalTop = _ytrp_html5_video.style.top;"
        }
        js += "}"
        webView?.evaluateJavascript(js, null)
    }

    override fun onShowCustomView(view: View, requestedOrientation: Int, callback: CustomViewCallback) {
        onShowCustomView(view, callback)
    }

    override fun onHideCustomView() {
        if (!isVideoFullscreen) {
            return
        }
        // onCustomViewHidden() makes Chromium call back into onHideCustomView(); clearing the flag
        // first keeps the teardown and the callback from running twice.
        isVideoFullscreen = false
        val callback = videoViewCallback
        val container = videoViewContainer
        videoViewCallback = null
        videoViewContainer = null

        activityVideoView?.visibility = View.INVISIBLE
        activityVideoView?.removeView(container)
        activityNonVideoView?.visibility = View.VISIBLE

        callback?.onCustomViewHidden()

        toggledFullscreenCallback?.toggledFullscreen(false)
    }

    override fun getVideoLoadingProgressView(): View? {
        val loadingView = this.loadingView
        return if (loadingView != null) {
            loadingView.visibility = View.VISIBLE
            loadingView
        } else {
            super.getVideoLoadingProgressView()
        }
    }

    override fun onPrepared(mp: MediaPlayer) {
        loadingView?.visibility = View.GONE
    }

    override fun onCompletion(mp: MediaPlayer) {
        onHideCustomView()
    }

    override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean {
        return false // returning false makes onCompletion() get called
    }

    fun onBackPressed(): Boolean {
        return if (isVideoFullscreen) {
            onHideCustomView()
            true
        } else {
            false
        }
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return
        }
        Log.d(
            "DrmDiag", "onPermissionRequest origin=" + request.origin +
                " resources=" + request.resources.contentToString()
        )
        val protectedContentAllowed = webView == null || protectedContentAllowed()
        val granted = mutableListOf<String>()
        for (resource in request.resources) {
            if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID == resource && !protectedContentAllowed) {
                continue
            }
            if (ALLOWED_PERMISSIONS.contains(resource)) {
                granted.add(resource)
            }
        }
        if (granted.isEmpty()) {
            request.deny()
        } else {
            request.grant(granted.toTypedArray())
        }
    }

    override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
        if (cm.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
            cm.messageLevel() == ConsoleMessage.MessageLevel.WARNING
        ) {
            Log.d("WebViewConsole", cm.messageLevel().toString() + " " + cm.message() + " @" + cm.sourceId() + ":" + cm.lineNumber())
        }
        return true
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        super.onGeolocationPermissionsShowPrompt(origin, callback)
        callback.invoke(origin, false, false)
    }

    interface ToggledFullscreenCallback {
        fun toggledFullscreen(fullscreen: Boolean)
    }

    companion object {
        private val ALLOWED_PERMISSIONS: Set<String> = setOf(
            PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
            PermissionRequest.RESOURCE_AUDIO_CAPTURE
        )
    }
}
