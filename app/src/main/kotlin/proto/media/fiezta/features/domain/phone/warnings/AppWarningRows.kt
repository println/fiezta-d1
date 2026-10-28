package proto.media.fiezta.features.domain.phone.warnings

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import proto.media.fiezta.R
import proto.media.fiezta.features.domain.core.warnings.AppWarning

object AppWarningRows {

    fun show(container: ViewGroup, warnings: List<AppWarning>, onTap: (AppWarning) -> Unit) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        warnings.forEach { warning ->
            val row = inflater.inflate(R.layout.item_app_warning, container, false)
            row.findViewById<TextView>(R.id.app_warning_title).setText(titleOf(warning))
            row.findViewById<TextView>(R.id.app_warning_message).setText(messageOf(warning))
            row.setOnClickListener { onTap(warning) }
            container.addView(row)
        }
    }

    @StringRes
    private fun titleOf(warning: AppWarning): Int = when (warning) {
        AppWarning.BATTERY_SAVING -> R.string.app_warning_battery_title
        AppWarning.MICROPHONE_MISSING -> R.string.app_warning_microphone_title
    }

    @StringRes
    private fun messageOf(warning: AppWarning): Int = when (warning) {
        AppWarning.BATTERY_SAVING -> R.string.app_warning_battery_message
        AppWarning.MICROPHONE_MISSING -> R.string.app_warning_microphone_message
    }
}
