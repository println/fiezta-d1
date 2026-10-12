package proto.media.fiezta.features.domain.car.settings

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import proto.media.fiezta.R
import proto.media.fiezta.features.domain.car.browser.DriverSideMirror

class CarSettingsNavigator(
    private val panelView: CarSettingsPanelLayout,
    private val panelOutsideView: View,
    private val screenView: View,
    private val screenBody: FrameLayout,
    private val screenTitle: TextView,
    private val inflater: LayoutInflater,
    private val mirroredToDriver: () -> Boolean,
    private val onPanelClosedWhenIdle: () -> Unit
) {
    private var currentScreen: CarSettingsScreen? = null

    private val closePanelWhenIdle = Runnable {
        closeAll()
        onPanelClosedWhenIdle()
    }

    init {
        panelOutsideView.setOnClickListener { closeAll() }
        panelView.onUserActivity = { if (isPanelOpen()) restartIdleCountdown() }
    }

    fun release() {
        panelView.removeCallbacks(closePanelWhenIdle)
    }

    fun isPanelOpen(): Boolean = panelView.visibility == View.VISIBLE

    fun isScreenOpen(): Boolean = screenView.visibility == View.VISIBLE

    fun isAnyOpen(): Boolean = isPanelOpen() || isScreenOpen()

    fun togglePanel() {
        if (isPanelOpen()) closeAll() else openPanel()
    }

    fun openPanel() {
        panelView.findViewById<View>(R.id.settings_panel_scroll).scrollTo(0, 0)
        screenView.visibility = View.GONE
        panelView.visibility = View.VISIBLE
        panelOutsideView.visibility = View.VISIBLE
        restartIdleCountdown()
    }

    fun open(screen: CarSettingsScreen) {
        closeCurrentScreen()
        val screenContent = screen.createView(inflater, screenBody)
        if (mirroredToDriver()) DriverSideMirror.flip(screenContent)
        screenBody.addView(screenContent)
        screenTitle.setText(screen.titleRes)
        currentScreen = screen
        panelView.removeCallbacks(closePanelWhenIdle)
        panelView.visibility = View.GONE
        screenView.visibility = View.VISIBLE
        panelOutsideView.visibility = View.GONE
        screen.onShown()
    }

    fun setTitle(titleRes: Int) {
        screenTitle.setText(titleRes)
    }

    fun back(): Boolean {
        if (!isScreenOpen()) {
            return false
        }
        if (currentScreen?.handleBack() == true) {
            return true
        }
        closeCurrentScreen()
        screenView.visibility = View.GONE
        panelView.visibility = View.VISIBLE
        panelOutsideView.visibility = View.VISIBLE
        restartIdleCountdown()
        return true
    }

    fun closeAll() {
        panelView.removeCallbacks(closePanelWhenIdle)
        closeCurrentScreen()
        screenView.visibility = View.GONE
        panelView.visibility = View.GONE
        panelOutsideView.visibility = View.GONE
    }

    private fun restartIdleCountdown() {
        panelView.removeCallbacks(closePanelWhenIdle)
        panelView.postDelayed(closePanelWhenIdle, PANEL_IDLE_TIMEOUT_MILLIS)
    }

    private fun closeCurrentScreen() {
        currentScreen?.onHidden()
        currentScreen = null
        screenBody.removeAllViews()
    }

    private companion object {
        const val PANEL_IDLE_TIMEOUT_MILLIS = 60_000L
    }
}
