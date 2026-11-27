package local.karoo.gtavimap

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.Symbol
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Puts the place icons on the Karoo's own map.
 *
 * The ride app asks an extension marked `mapLayer` for things to draw whenever a map opens
 * ([attach]). This class answers with the icons around the rider: it looks the places up in the
 * list ([Places]), lets [IconPicker] choose, and tells the map which icons to add and which to
 * take away. It works again when the rider has moved a fair bit or the zoom changed.
 *
 * Seen on the unit (27.11.2026): the ride app draws an icon at the drawable's own size, and it
 * does not repaint a still map when icons are added or taken away: the change shows the next
 * time the map moves or zooms. On a ride that is at once; on a desk it needs a nudge.
 * Icons put up by an extension process that has since died stay until the Ride screen is opened
 * again, so reinstall with the Ride screen closed.
 *
 * Everything here runs on the main thread, except reading the list at the start.
 */
class MapIcons(private val context: Context, private val prefs: Prefs) {
    private val main = Handler(Looper.getMainLooper())
    private val maps = LinkedHashSet<Emitter<MapEffect>>()
    /** The place list, once it is read. The route's fields use it too. */
    var places: Places? = null
        private set

    /** Called on the main thread when the place list has been read. */
    var onPlaces: (() -> Unit)? = null
    private val shown = LinkedHashMap<String, Symbol.Icon>()

    private var lat: Double? = null
    private var lon: Double? = null
    private var zoom: Double? = null

    private var doneLat = 0.0
    private var doneLon = 0.0
    private var doneZoom = -1.0
    private var doneSize: IconSize? = null

    fun start() {
        Thread {
            try {
                val list = Places(context.assets.open("places.bin").use { it.readBytes() })
                main.post {
                    places = list
                    Status.placeCount = list.size
                    Timber.i("%s: place list read, %d places", GtaExtension.TAG, list.size)
                    refresh(true)
                    onPlaces?.invoke()
                }
            } catch (e: Exception) {
                Timber.w(e, "%s: no place list", GtaExtension.TAG)
                main.post { Status.iconNote = "No place list in this build." }
            }
        }.start()
    }

    /** A map opened in the ride app and wants our layer. */
    fun attach(map: Emitter<MapEffect>) {
        maps.add(map)
        Status.mapsOpen = maps.size
        Timber.i("%s: a map opened (%d open)", GtaExtension.TAG, maps.size)
        if (shown.isNotEmpty()) send(map, ShowSymbols(shown.values.toList()))
        refresh(false)
    }

    fun detach(map: Emitter<MapEffect>) {
        maps.remove(map)
        Status.mapsOpen = maps.size
    }

    fun onLocation(lat: Double, lon: Double) {
        this.lat = lat
        this.lon = lon
        refresh(false)
    }

    fun onZoom(zoom: Double) {
        this.zoom = zoom
        refresh(false)
    }

    /** A switch on the settings screen changed. */
    fun settingsChanged() = refresh(true)

    /** While true the icons are only moved by [raw]: for finding out on a desk what the map accepts. */
    var manual = false

    /** One hand-made step: show or hide a single icon. */
    fun raw(op: String, id: String, lat: Double, lon: Double, icon: String, size: IconSize) {
        val res = context.resources.getIdentifier("poi_${icon}_${size.suffix}", "drawable", context.packageName)
        when (op) {
            "show" -> sendAll(ShowSymbols(listOf(Symbol.Icon(id, lat, lon, res, 0f))))
            "hide" -> sendAll(HideSymbols(listOf(id)))
        }
        Timber.i("%s: raw %s %s at %.5f, %.5f as %s %s to %d maps", GtaExtension.TAG, op, id, lat, lon, icon, size.suffix, maps.size)
    }

    private fun refresh(force: Boolean) {
        if (manual) return
        val list = places ?: return
        val size = prefs.iconSize
        if (!prefs.mapIcons) {
            if (shown.isNotEmpty()) {
                sendAll(HideSymbols(shown.keys.toList()))
                shown.clear()
            }
            doneZoom = -1.0
            Status.iconsShown = 0
            Status.iconNote = "Map icons are switched off."
            return
        }
        val lat = this.lat
        val lon = this.lon
        if (lat == null || lon == null) {
            Status.iconNote = "No position yet, so no icons."
            return
        }
        val zoom = this.zoom ?: DEFAULT_ZOOM
        val mpp = IconPicker.metresPerPixel(lat, zoom)
        val moved = hypot((lat - doneLat) * Places.M_PER_DEGREE,
            (lon - doneLon) * Places.M_PER_DEGREE * cos(Math.toRadians(lat)))
        if (!force && size == doneSize && abs(zoom - doneZoom) < 0.3 && moved < MOVE_PX * mpp) return
        doneLat = lat
        doneLon = lon
        doneZoom = zoom
        doneSize = size

        val picked = IconPicker.pick(list, lat, lon, zoom, size.px(context))
        val wanted = LinkedHashMap<String, Symbol.Icon>()
        for (p in picked) {
            val res = drawable(p.kind, size)
            val id = "p${p.id}"
            if (res != 0) wanted[id] = Symbol.Icon(id, p.lat, p.lon, res, 0f)
        }
        val gone = shown.keys.filter { it !in wanted }
        // An icon shown again under the same name replaces the old one, so another size or
        // picture for a place needs no taking away first.
        val new = wanted.filter { (id, icon) -> shown[id] != icon }.values.toList()
        if (gone.isNotEmpty()) sendAll(HideSymbols(gone))
        if (new.isNotEmpty()) sendAll(ShowSymbols(new))
        shown.clear()
        shown.putAll(wanted)
        Status.iconsShown = shown.size
        Status.iconNote = ""
        Timber.i("%s: icons at zoom %.1f: %d shown (+%d, -%d)", GtaExtension.TAG, zoom, shown.size, new.size, gone.size)
    }

    private val drawables = HashMap<String, Int>()

    private fun drawable(kind: Kind, size: IconSize): Int {
        val name = "poi_${kind.icon}_${size.suffix}"
        return drawables.getOrPut(name) { context.resources.getIdentifier(name, "drawable", context.packageName) }
    }

    private fun sendAll(effect: MapEffect) = maps.toList().forEach { send(it, effect) }

    private fun send(map: Emitter<MapEffect>, effect: MapEffect) {
        try {
            map.onNext(effect)
        } catch (e: Exception) {
            // The ride app closed this map without telling us.
            Timber.w("%s: a map no longer answers (%s)", GtaExtension.TAG, e.message)
            maps.remove(map)
            Status.mapsOpen = maps.size
        }
    }

    companion object {
        /** Used until the Karoo says what the map's zoom is. */
        private const val DEFAULT_ZOOM = 15.0

        /** Pick again once the rider is this far, in screen pixels, from where it was last done. */
        private const val MOVE_PX = 150.0
    }
}
