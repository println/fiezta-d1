package proto.media.fiezta.features.domain.core.bookmarks

import proto.media.fiezta.features.domain.core.settings.SettingsStorage
import proto.media.fiezta.support.webviewex.navigation.HostName

object SiteColor {
    private const val OPAQUE_ALPHA = 128
    private const val MIN_SATURATION = 0.35f
    private const val MIN_BRIGHTNESS = 0.25f
    private const val MIN_COLORFUL_SHARE = 0.05f
    private const val HUE_BUCKETS = 12
    private const val HUE_BUCKET_DEGREES = 360 / HUE_BUCKETS
    private const val MAX_LUMINANCE_UNDER_WHITE_TEXT = 0.6f
    private const val MIN_SHARE_OF_A_DOMINANT_ACCENT = 0.6
    private const val WHITE = 0xFFFFFFFF.toInt()

    fun domainOf(url: String?): String? = SettingsStorage.domainKey(HostName.of(url))

    fun fallbackIndex(domain: String?, paletteSize: Int): Int = Math.floorMod(domain.orEmpty().hashCode(), paletteSize)

    fun ofIcon(argbPixels: IntArray): Int? {
        val buckets = Array(HUE_BUCKETS) { HueBucket() }
        var opaque = 0
        var colorful = 0
        for (pixel in argbPixels) {
            if (pixel ushr 24 < OPAQUE_ALPHA) continue
            opaque++
            val red = pixel shr 16 and 0xFF
            val green = pixel shr 8 and 0xFF
            val blue = pixel and 0xFF
            val strongest = maxOf(red, green, blue)
            val weakest = minOf(red, green, blue)
            if (strongest == 0) continue
            val saturation = (strongest - weakest) / strongest.toFloat()
            val brightness = strongest / 255f
            if (saturation < MIN_SATURATION || brightness < MIN_BRIGHTNESS) continue
            colorful++
            buckets[bucketOf(hueOf(red, green, blue, strongest, weakest))].add(red, green, blue, saturation * brightness)
        }
        if (opaque == 0 || colorful < opaque * MIN_COLORFUL_SHARE) return null
        val accent = buckets.indices.maxByOrNull { buckets[it].weight }!!
        if (!dominates(accent, buckets)) return WHITE
        return readableUnderWhiteText(buckets[accent].average())
    }

    fun needsDarkInitials(color: Int): Boolean = luminanceOf(color shr 16 and 0xFF, color shr 8 and 0xFF, color and 0xFF) >
        MAX_LUMINANCE_UNDER_WHITE_TEXT

    private fun dominates(accent: Int, buckets: Array<HueBucket>): Boolean {
        val withNeighbours = buckets[accent].weight +
            buckets[(accent + 1) % HUE_BUCKETS].weight +
            buckets[(accent + HUE_BUCKETS - 1) % HUE_BUCKETS].weight
        return withNeighbours >= buckets.sumOf { it.weight.toDouble() } * MIN_SHARE_OF_A_DOMINANT_ACCENT
    }

    private fun luminanceOf(red: Int, green: Int, blue: Int): Float = (0.299f * red + 0.587f * green + 0.114f * blue) / 255f

    private fun hueOf(red: Int, green: Int, blue: Int, strongest: Int, weakest: Int): Float {
        val spread = (strongest - weakest).toFloat()
        val hue = when (strongest) {
            red -> 60f * ((green - blue) / spread)
            green -> 60f * ((blue - red) / spread + 2f)
            else -> 60f * ((red - green) / spread + 4f)
        }
        return (hue + 360f) % 360f
    }

    private fun bucketOf(hue: Float): Int = ((hue + HUE_BUCKET_DEGREES / 2f) % 360f / HUE_BUCKET_DEGREES).toInt() % HUE_BUCKETS

    private fun readableUnderWhiteText(rgb: IntArray): Int {
        val luminance = luminanceOf(rgb[0], rgb[1], rgb[2])
        val dim = if (luminance > MAX_LUMINANCE_UNDER_WHITE_TEXT) MAX_LUMINANCE_UNDER_WHITE_TEXT / luminance else 1f
        return (0xFF shl 24) or ((rgb[0] * dim).toInt() shl 16) or ((rgb[1] * dim).toInt() shl 8) or (rgb[2] * dim).toInt()
    }

    private class HueBucket {
        var weight = 0f
            private set
        private var red = 0f
        private var green = 0f
        private var blue = 0f

        fun add(red: Int, green: Int, blue: Int, weight: Float) {
            this.weight += weight
            this.red += red * weight
            this.green += green * weight
            this.blue += blue * weight
        }

        fun average(): IntArray =
            intArrayOf(Math.round(red / weight), Math.round(green / weight), Math.round(blue / weight))
    }
}
