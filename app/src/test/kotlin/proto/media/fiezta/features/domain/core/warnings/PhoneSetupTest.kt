package proto.media.fiezta.features.domain.core.warnings

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneSetupTest {

    @Test
    fun `a phone set up correctly has no warnings`() {
        assertEquals(emptyList<AppWarning>(), PhoneSetup(batteryRestricted = false, microphoneGranted = true).warnings)
    }

    @Test
    fun `battery saving restricting the app is a warning`() {
        assertEquals(listOf(AppWarning.BATTERY_SAVING), PhoneSetup(batteryRestricted = true, microphoneGranted = true).warnings)
    }

    @Test
    fun `a microphone not granted is a warning`() {
        assertEquals(listOf(AppWarning.MICROPHONE_MISSING), PhoneSetup(batteryRestricted = false, microphoneGranted = false).warnings)
    }

    @Test
    fun `battery saving comes before the microphone`() {
        assertEquals(
            listOf(AppWarning.BATTERY_SAVING, AppWarning.MICROPHONE_MISSING),
            PhoneSetup(batteryRestricted = true, microphoneGranted = false).warnings
        )
    }
}
