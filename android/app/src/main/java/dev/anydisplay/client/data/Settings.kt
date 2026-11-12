package dev.anydisplay.client.data

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

enum class TouchMode(val key: String) {
    DIRECT("direct"), TRACKPAD("trackpad"), PEN_ONLY("pen");

    fun next() = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: DIRECT
    }
}

enum class StreamOrientation(val key: String) {
    LANDSCAPE("landscape"), PORTRAIT("portrait"), AUTO("auto");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: LANDSCAPE
    }
}

/** Client-side scaling of the decoded video when the stream is smaller than the displayed video rectangle. */
enum class Upscaler(val key: String) {
    CURRENT("current"), FSR("fsr"), NEAREST("nearest");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: CURRENT
    }
}

/** Typed view over the user settings (res/xml/settings.xml defines the same keys and defaults). */
class Settings(context: Context) {
    val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    /**
     * Stream scale: a preset (1.0 native, 0.75, 0.6667, 0.5) or the custom 25-150% slider when "custom" is
     * selected. Old saved preset values keep working unchanged.
     */
    val scale: Float
        get() {
            val preset = prefs.getString(K_SCALE, "1.0")
            if (preset == SCALE_CUSTOM) {
                // The SeekBar stores 0-125; 0 is 25%, 75 is 100% and 125 is 150%.
                val percent = prefs.getInt(K_SCALE_PERCENT, 75).coerceIn(0, 125) + 25
                return percent / 100f
            }
            return preset?.toFloatOrNull()?.coerceIn(0.25f, 1.5f) ?: 1f
        }
    val refreshHz: Int get() = prefs.getString(K_REFRESH, "60")?.toIntOrNull() ?: 60
    val codec: String get() = prefs.getString(K_CODEC, "auto") ?: "auto"
    val bitrateKbps: Int get() = prefs.getString(K_BITRATE, "0")?.toIntOrNull() ?: 0
    val quality: Int get() = prefs.getInt(K_QUALITY, 80).coerceIn(1, 100)
    val hidpi: Boolean get() = prefs.getBoolean(K_HIDPI, true)
    val audio: Boolean get() = prefs.getBoolean(K_AUDIO, false)
    val clientCursor: Boolean get() = prefs.getBoolean(K_CLIENT_CURSOR, true)
    val mirror: Boolean get() = prefs.getBoolean(K_MIRROR, false)
    val palmRejection: Boolean get() = prefs.getBoolean(K_PALM, true)
    val statsOverlay: Boolean get() = prefs.getBoolean(K_STATS, false)
    val autoStart: Boolean get() = prefs.getBoolean(K_AUTO_START, true)
    val orientation: StreamOrientation get() = StreamOrientation.of(prefs.getString(K_ORIENTATION, "landscape"))
    val upscaler: Upscaler get() = Upscaler.of(prefs.getString(K_UPSCALER, "current"))
    /** FSR RCAS sharpness, 0 (least) to 100 (strongest). */
    val fsrSharpness: Int get() = prefs.getInt(K_FSR_SHARPNESS, 50).coerceIn(0, 100)

    val touchMode: TouchMode get() = TouchMode.of(prefs.getString(K_TOUCH_MODE, "direct"))

    var menuPosition: Pair<Float, Float>?
        get() = if (prefs.contains(K_MENU_X)) prefs.getFloat(K_MENU_X, 0f) to prefs.getFloat(K_MENU_Y, 0f) else null
        set(v) {
            prefs.edit().apply { if (v == null) remove(K_MENU_X).remove(K_MENU_Y) else putFloat(K_MENU_X, v.first).putFloat(K_MENU_Y, v.second) }.apply()
        }

    companion object {
        const val K_SCALE = "scale"
        const val K_SCALE_PERCENT = "scale_percent"
        const val SCALE_CUSTOM = "custom"
        const val K_REFRESH = "refresh"
        const val K_CODEC = "codec"
        const val K_BITRATE = "bitrate"
        const val K_QUALITY = "quality"
        const val K_HIDPI = "hidpi"
        const val K_AUDIO = "audio"
        const val K_CLIENT_CURSOR = "client_cursor"
        const val K_MIRROR = "mirror"
        const val K_TOUCH_MODE = "touch_mode"
        const val K_PALM = "palm_rejection"
        const val K_STATS = "stats_overlay"
        const val K_AUTO_START = "auto_start"
        const val K_ORIENTATION = "orientation"
        const val K_UPSCALER = "upscaler"
        const val K_FSR_SHARPNESS = "fsr_sharpness"
        private const val K_MENU_X = "menu_x"
        private const val K_MENU_Y = "menu_y"
    }
}
