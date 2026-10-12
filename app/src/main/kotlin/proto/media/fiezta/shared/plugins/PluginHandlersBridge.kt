package proto.media.fiezta.shared.plugins

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import proto.media.fiezta.support.plugins.contract.PluginPageListener

class PluginHandlersBridge(
    private val listener: PluginPageListener,
    private val showToast: (String) -> Unit,
    private val feedback: (defaultText: String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onHandlersChanged(names: String) {
        val events = names.split(',').filter { it.isNotEmpty() }.toSet()
        mainHandler.post { listener.onHandlersChanged(events) }
    }

    @JavascriptInterface
    fun showToast(text: String) {
        mainHandler.post { showToast.invoke(text) }
    }

    @JavascriptInterface
    fun feedback(name: String, defaultText: String) {
        mainHandler.post { feedback.invoke(defaultText) }
    }

    @JavascriptInterface
    fun onVoiceSearchProgress(id: String, generation: String, stage: String) {
        val requestId = id.toLongOrNull() ?: return
        val documentGeneration = generation.toLongOrNull() ?: return
        mainHandler.post { listener.onVoiceSearchProgress(requestId, documentGeneration, stage) }
    }
}
