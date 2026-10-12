package proto.media.fiezta.support.webviewex

import android.graphics.Bitmap
import android.view.KeyEvent
import proto.media.fiezta.support.webviewex.document.DocumentStage
import proto.media.fiezta.support.webviewex.gearhead.PlaybackSnapshot
import proto.media.fiezta.support.webviewex.media.MediaElementEvent
import proto.media.fiezta.support.webviewex.navigation.NavigationFailure
import proto.media.fiezta.support.webviewex.navigation.NavigationTransaction
import proto.media.fiezta.support.webviewex.presentation.RenderMode

interface WebViewExListener {
    fun onMainFrameRequested(url: String) = Unit
    fun onNavigationStarted(transaction: NavigationTransaction, url: String, favicon: Bitmap?, isErrorDocument: Boolean) = Unit
    fun onPageFinished(url: String?, isErrorDocument: Boolean) = Unit
    fun onVisitedHistoryUpdated(url: String?, isReload: Boolean, isErrorDocument: Boolean) = Unit
    fun onNavigationFailed(transaction: NavigationTransaction, failure: NavigationFailure) = Unit
    fun onDocumentStage(stage: DocumentStage, documentId: String, url: String?) = Unit
    fun onMediaElementEvent(event: MediaElementEvent, positionSeconds: Double, documentId: String) = Unit
    fun onPlaybackVerdict(playback: PlaybackSnapshot) = Unit
    fun onLoadingChanged(state: LoadingState) = Unit
    fun onIconReceived(url: String?, icon: Bitmap) = Unit
    fun onRenderModeChanged(mode: RenderMode) = Unit
    fun onSchemeFallback(url: String, host: String?) = Unit
    fun onExternalLinkBlocked(url: String, failure: NavigationFailure) = Unit
    fun onUnhandledKeyEvent(event: KeyEvent) = Unit
    fun onViewCreated() = Unit
    fun onViewDestroying() = Unit
    fun onViewDestroyed() = Unit
    fun onViewRecreated() = Unit
}

enum class RenderGoneResponse { DESTROY, RECREATE }

interface WebViewExDelegate {
    fun initialUrl(): String? = null
    fun fallbackUrl(): String? = null
    fun onRenderProcessGone(): RenderGoneResponse = RenderGoneResponse.RECREATE
}
