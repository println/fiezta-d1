package proto.media.fiezta.features.domain.car.settings

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.LinearLayout

class CarSettingsPanelLayout(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {

    var onUserActivity: (() -> Unit)? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        onUserActivity?.invoke()
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        onUserActivity?.invoke()
        return super.dispatchKeyEvent(event)
    }
}
