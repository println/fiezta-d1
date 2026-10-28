package proto.media.fiezta.features.domain.phone.warnings

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import proto.media.fiezta.features.domain.core.warnings.AppWarning

class AppWarningResolver(private val activity: Activity, private val requestMicrophone: () -> Unit) {

    fun resolve(warning: AppWarning) = when (warning) {
        AppWarning.BATTERY_SAVING -> openAppSettings()
        AppWarning.MICROPHONE_MISSING -> requestMicrophone()
    }

    fun onMicrophoneAnswered(granted: Boolean) {
        if (granted || canAskForMicrophoneAgain()) return
        openAppSettings()
    }

    private fun canAskForMicrophoneAgain(): Boolean =
        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
