package local.karoo.gtavimap

import android.content.Context
import android.content.res.Configuration
import android.os.Environment
import android.provider.Settings
import timber.log.Timber
import java.io.File

/**
 * Puts the GTA VI day or night theme where the Karoo's ride app looks for its map theme.
 *
 * The ride app reads this one file once, when its map starts. So a swap shows the next time the
 * ride app starts its map, not at once.
 */
class ThemeSwitcher(private val context: Context) {
    private val storage: File get() = Environment.getExternalStorageDirectory()

    /** The file the ride app reads. The name is fixed in the ride app (its 2026 version). */
    val target: File get() = File(storage, "offline_v15.xml")

    /** Hammerhead's own theme, kept so it can always be put back. */
    val backup: File get() = File(storage, "offline_v15_ORIGINAL.xml")

    enum class Kind { DAY, NIGHT, STOCK, OTHER, MISSING }

    private fun asset(night: Boolean): ByteArray =
        context.assets.open(if (night) "GTA-VI-night.xml" else "GTA-VI-day.xml").use { it.readBytes() }

    /** Is the Karoo in Dark Mode? Its switch writes the system setting ui_night_mode (2 = dark). */
    fun karooIsDark(): Boolean {
        val setting = try {
            Settings.Secure.getInt(context.contentResolver, "ui_night_mode", -1)
        } catch (e: Exception) {
            -1
        }
        if (setting == 2) return true
        if (setting == 1) return false
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    /** Which theme is on the storage now. */
    fun current(): Kind {
        val file = target
        if (!file.exists()) return Kind.MISSING
        return try {
            val bytes = file.readBytes()
            when {
                bytes.contentEquals(asset(false)) -> Kind.DAY
                bytes.contentEquals(asset(true)) -> Kind.NIGHT
                backup.exists() && bytes.contentEquals(backup.readBytes()) -> Kind.STOCK
                else -> Kind.OTHER
            }
        } catch (e: Exception) {
            Timber.w(e, "can't read the theme file")
            Kind.OTHER
        }
    }

    /**
     * Makes the theme on the storage the day or the night one. Returns true if the file changed.
     * Before the first change it keeps whatever was there as the backup, unless a backup exists.
     */
    fun put(night: Boolean): Boolean {
        val wanted = asset(night)
        val file = target
        try {
            if (file.exists() && file.readBytes().contentEquals(wanted)) return false
            if (file.exists() && !backup.exists() && !isOurs(file)) {
                file.copyTo(backup)
                Timber.i("kept the previous theme as %s", backup.name)
            }
            val fresh = File(storage, "offline_v15.xml.new")
            fresh.writeBytes(wanted)
            if (!fresh.renameTo(file)) {
                file.writeBytes(wanted)
                fresh.delete()
            }
            Timber.i("map theme on the storage is now %s", if (night) "night" else "day")
            return true
        } catch (e: Exception) {
            Timber.w(e, "can't write the theme file (is the storage permission granted?)")
            return false
        }
    }

    /** Puts Hammerhead's theme back. Returns false if there is no backup or it can't be written. */
    fun restoreStock(): Boolean {
        return try {
            if (!backup.exists()) return false
            backup.copyTo(target, overwrite = true)
            Timber.i("Hammerhead's theme is back")
            true
        } catch (e: Exception) {
            Timber.w(e, "can't restore the theme file")
            false
        }
    }

    private fun isOurs(file: File): Boolean =
        file.useLines { lines -> lines.take(4).any { "make_karoo_theme.py" in it } }
}
