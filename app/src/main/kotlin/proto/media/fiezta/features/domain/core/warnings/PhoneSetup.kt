package proto.media.fiezta.features.domain.core.warnings

enum class AppWarning { BATTERY_SAVING, MICROPHONE_MISSING }

data class PhoneSetup(val batteryRestricted: Boolean, val microphoneGranted: Boolean) {

    val warnings: List<AppWarning>
        get() = buildList {
            if (batteryRestricted) add(AppWarning.BATTERY_SAVING)
            if (!microphoneGranted) add(AppWarning.MICROPHONE_MISSING)
        }
}
