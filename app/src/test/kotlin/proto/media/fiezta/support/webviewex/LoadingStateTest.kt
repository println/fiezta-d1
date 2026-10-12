package proto.media.fiezta.support.webviewex

import org.junit.Assert.assertEquals
import org.junit.Test
import proto.media.fiezta.support.webviewex.navigation.NavigationOrigin
import proto.media.fiezta.support.webviewex.navigation.NavigationTracker

class LoadingStateTest {

    private val tracker = NavigationTracker()

    @Test
    fun aNavigationInFlightIsLoadingWithItsProgress() {
        tracker.begin(NavigationOrigin.APP, "a.com", "https://a.com/")
        assertEquals(LoadingState(true, 0), LoadingState.of(tracker.current, 0))
        tracker.onPageStarted("https://a.com/")
        assertEquals(LoadingState(true, 48), LoadingState.of(tracker.current, 48))
    }

    @Test
    fun aFinishedNavigationIsIdle() {
        tracker.begin(NavigationOrigin.APP, "a.com", "https://a.com/")
        tracker.onPageStarted("https://a.com/")
        tracker.onPageFinished("https://a.com/")
        assertEquals(LoadingState.IDLE, LoadingState.of(tracker.current, 100))
    }

    @Test
    fun noNavigationIsIdle() {
        assertEquals(LoadingState.IDLE, LoadingState.of(null, 30))
    }

    @Test
    fun aLinkThatNeverBecomesAPageIsIdleOnceTheLoadStops() {
        tracker.begin(NavigationOrigin.PAGE, "https://a.com/next", "https://a.com/next")
        assertEquals(LoadingState.IDLE, LoadingState.of(tracker.current, 100))
    }

    @Test
    fun aPageThatFinishesUnderAnotherAddressIsIdleOnceTheLoadStops() {
        tracker.begin(NavigationOrigin.APP, "a.com", "https://a.com/")
        tracker.onPageStarted("https://a.com/")
        tracker.onPageFinished("https://a.com/rewritten")
        assertEquals(LoadingState.IDLE, LoadingState.of(tracker.current, 100))
    }
}
