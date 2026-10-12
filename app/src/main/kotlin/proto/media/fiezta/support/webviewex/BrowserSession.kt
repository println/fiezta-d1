package proto.media.fiezta.support.webviewex

import proto.media.fiezta.support.webviewex.navigation.NavigationFailure
import proto.media.fiezta.support.webviewex.navigation.NavigationTransaction

data class BrowserSession(
    val url: String?,
    val host: String?,
    val title: String?,
    val isSecure: Boolean,
    val isLoading: Boolean,
    val failure: NavigationFailure?,
    val isErrorDocument: Boolean
)

data class LoadingState(val isLoading: Boolean, val progress: Int) {
    companion object {
        val IDLE = LoadingState(isLoading = false, progress = 0)

        private const val PROGRESS_OF_A_STOPPED_LOAD = 100

        fun of(transaction: NavigationTransaction?, progress: Int): LoadingState {
            val inFlight = transaction != null && !transaction.isTerminal && progress < PROGRESS_OF_A_STOPPED_LOAD
            return if (inFlight) LoadingState(true, progress) else IDLE
        }
    }
}

data class HistoryEntry(val rawIndex: Int, val url: String, val title: String?)

data class BrowserHistory(val entries: List<HistoryEntry>, val currentPosition: Int) {
    companion object {
        val EMPTY = BrowserHistory(emptyList(), -1)
    }
}
