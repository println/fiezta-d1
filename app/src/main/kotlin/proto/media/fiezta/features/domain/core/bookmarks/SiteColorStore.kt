package proto.media.fiezta.features.domain.core.bookmarks

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap

object SiteColorStore {
    private const val PREFS = "site_colors"
    private const val SAMPLE_SIZE = 32

    fun colorOf(context: Context, url: String?): Int? {
        val domain = SiteColor.domainOf(url) ?: return null
        val prefs = prefs(context)
        return if (prefs.contains(domain)) prefs.getInt(domain, 0) else null
    }

    fun learnFromIcon(context: Context, url: String?, icon: Bitmap) {
        val domain = SiteColor.domainOf(url) ?: return
        val color = SiteColor.ofIcon(pixelsOf(icon)) ?: return
        val prefs = prefs(context)
        if (prefs.contains(domain) && prefs.getInt(domain, 0) == color) return
        prefs.edit().putInt(domain, color).apply()
    }

    fun registerChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun pixelsOf(icon: Bitmap): IntArray {
        val sample = Bitmap.createScaledBitmap(icon.copy(Bitmap.Config.ARGB_8888, false), SAMPLE_SIZE, SAMPLE_SIZE, true)
        return IntArray(SAMPLE_SIZE * SAMPLE_SIZE).also { sample.getPixels(it, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE) }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
