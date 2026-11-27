package local.karoo.gtavimap

import android.content.Context
import io.hammerhead.karooext.models.Symbol

/**
 * Standing icons: the places sent to the map as the Karoo's own pins.
 *
 * The ride app lays a [Symbol.Icon] flat on the map, so it lies on the ground when the map is
 * tilted. A [Symbol.POI] it draws upright, stood on the place by its bottom middle, from its own
 * pictures for the POI's type (read from the ride app, Karoo 2, 27.11.2026). The colour
 * overlay `local.karoo.gtavicolours` from version 0.2 on puts our icons in place of those
 * pictures, so each kind of ours takes one POI type. make_icons.py (PIN_TYPES) writes the
 * overlay's pictures: keep the two in step (PinsTest checks it).
 */
object Pins {
    const val OVERLAY = "local.karoo.gtavicolours"

    /** The overlay's first version with our pin pictures. */
    const val OVERLAY_VERSION = 2

    /** The POI type whose picture the overlay swaps for this kind's icon. */
    fun type(kind: Kind): String = when (kind) {
        Kind.SHELTER -> Symbol.POI.Types.REST_STOP
        Kind.WATER -> Symbol.POI.Types.WATER
        Kind.SUPERMARKET, Kind.SHOP -> Symbol.POI.Types.CONVENIENCE_STORE
        Kind.FOOD -> Symbol.POI.Types.FOOD
        Kind.FUEL -> Symbol.POI.Types.GAS_STATION
        Kind.COFFEE -> Symbol.POI.Types.COFFEE
        Kind.BIKE -> Symbol.POI.Types.BIKE_SHOP
        Kind.TRAIN -> Symbol.POI.Types.TRANSIT_CENTER
        Kind.TOILETS -> Symbol.POI.Types.RESTROOM
    }

    /** The name the Karoo shows when the pin is tapped. The list has no names of its own. */
    fun title(kind: Kind): String = when (kind) {
        Kind.SHELTER -> "Shelter"
        Kind.WATER -> "Drinking water"
        Kind.SUPERMARKET -> "Supermarket"
        Kind.SHOP -> "Shop"
        Kind.FOOD -> "Food"
        Kind.FUEL -> "Gas station"
        Kind.COFFEE -> "Cafe or bakery"
        Kind.BIKE -> "Bike shop"
        Kind.TRAIN -> "Train station"
        Kind.TOILETS -> "Toilets"
    }

    fun symbol(id: String, place: Place): Symbol.POI =
        Symbol.POI(id, place.lat, place.lon, type(place.kind), title(place.kind))

    /** Whether the overlay on this unit has our pin pictures. Without them a POI shows as a grey Karoo bubble. */
    fun overlayReady(context: Context): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(OVERLAY, 0).versionCode >= OVERLAY_VERSION
    } catch (e: Exception) {
        false
    }
}
