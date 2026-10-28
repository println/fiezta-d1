package proto.media.fiezta.features.domain.phone.warnings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import proto.media.fiezta.features.domain.core.warnings.PhoneSetup

object PhoneSetupProbe {

    fun read(context: Context): PhoneSetup = PhoneSetup(
        batteryRestricted = !isIgnoringBatteryOptimizations(context),
        microphoneGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    )

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }
}
