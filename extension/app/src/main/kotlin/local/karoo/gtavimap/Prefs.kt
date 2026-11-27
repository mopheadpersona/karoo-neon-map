package local.karoo.gtavimap

import android.content.Context
import kotlin.math.roundToInt

/** The switches of the extension, kept in the app's own settings file. */
class Prefs(context: Context) {
    private val store = context.applicationContext.getSharedPreferences("gtavimap", Context.MODE_PRIVATE)

    /** Zoom the map by speed while a ride is recording. */
    var autoZoom: Boolean
        get() = store.getBoolean("autoZoom", true)
        set(value) = store.edit().putBoolean("autoZoom", value).apply()

    /** What the extension does with the ride app's map theme file. */
    var themeMode: ThemeMode
        get() = ThemeMode.entries.firstOrNull { it.name == store.getString("themeMode", null) } ?: ThemeMode.FOLLOW
        set(value) = store.edit().putString("themeMode", value.name).apply()

    /** Put the place icons (shelters, shops, gas stations...) on the map. */
    var mapIcons: Boolean
        get() = store.getBoolean("mapIcons", true)
        set(value) = store.edit().putBoolean("mapIcons", value).apply()

    /** How big the place icons are drawn. Large is the default. */
    var iconSize: IconSize
        get() = IconSize.entries.firstOrNull { it.name == store.getString("iconSize", null) } ?: IconSize.L
        set(value) = store.edit().putString("iconSize", value.name).apply()

    /** Icons standing up as Karoo pins (they stay upright on a tilted map), or lying flat. */
    var iconStyle: IconStyle
        get() = IconStyle.entries.firstOrNull { it.name == store.getString("iconStyle", null) } ?: IconStyle.STANDING
        set(value) = store.edit().putString("iconStyle", value.name).apply()

    /** The font of the fields' labels and numbers. */
    var condensedFont: CondensedFont
        get() = CondensedFont.entries.firstOrNull { it.name == store.getString("condensedFont", null) } ?: CondensedFont.BARLOW
        set(value) = store.edit().putString("condensedFont", value.name).apply()

    /** The font of the cash-style numbers. */
    var chunkyFont: ChunkyFont
        get() = ChunkyFont.entries.firstOrNull { it.name == store.getString("chunkyFont", null) } ?: ChunkyFont.BUNGEE
        set(value) = store.edit().putString("chunkyFont", value.name).apply()
}

/** The sizes the icons are drawn in (make_icons.py, SIZES_DP: keep the two in step). */
enum class IconSize(val label: String, val suffix: String, val dp: Int) {
    S("small", "s", 20),
    M("medium", "m", 24),
    L("large", "l", 28),
    X("extra large", "x", 32);

    /** The size on this screen, in pixels. */
    fun px(context: Context) = (dp * context.resources.displayMetrics.density).roundToInt()
}

/** How the place icons go on the map (Pins.kt). Standing ones are always the large size. */
enum class IconStyle(val label: String) {
    STANDING("standing up"),
    FLAT("lying flat"),
}

enum class ThemeMode(val label: String) {
    FOLLOW("Follows Dark Mode"),
    DAY("Always day"),
    NIGHT("Always night"),
    OFF("Hands off"),
}
