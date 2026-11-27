package local.karoo.gtavimap

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow

/**
 * From which map zoom a kind of place is shown. The Karoo's scale reads "10 km" at about zoom 10,
 * "2 km" at 12, "500 m" at 14, "200 m" at 15. The far kinds went out to the "2 km" scale until
 * version 0.4, and now go to the "10 km" scale. Their
 * spacing is counted in icon widths, so far out they are as sparse on the screen as at 2 km.
 */
const val FAR = 9.5
const val MID = 13.5
const val CLOSE = 14.5

/**
 * The kinds of place, in the order of their number in places.bin (make_places.py, KINDS: keep
 * the two in step).
 *
 * [icon] names the drawable (poi_<icon>_<size>), [fromZoom] is the widest zoom it shows at,
 * [rank] says who wins when two places would sit on top of each other (lowest first), and
 * [spread] is how many icon widths two of the same icon keep apart: one burger says "food here".
 */
enum class Kind(val icon: String, val fromZoom: Double, val rank: Int, val spread: Double) {
    SHELTER("shelter", FAR, 0, 3.0),
    WATER("water", MID, 1, 3.0),
    SUPERMARKET("groceries", FAR, 2, 3.5),
    SHOP("groceries", MID, 4, 4.5),
    FOOD("food", MID, 5, 5.0),
    FUEL("fuel", FAR, 3, 3.0),
    COFFEE("coffee", MID, 6, 5.0),
    BIKE("bike", MID, 8, 3.0),
    TRAIN("train", FAR, 7, 3.0),
    TOILETS("toilets", CLOSE, 9, 4.0),
}

/** One place of the list. [id] is its number in the file, the same every time the file is read. */
class Place(val id: Int, val lat: Double, val lon: Double, val kind: Kind)

/**
 * The place list packed by make_places.py: every shelter, shop, gas station and so on of the
 * regions whose maps are on the Karoo, sorted into cells of a tenth of a degree.
 *
 * The file stays in memory as it is (a few megabytes); nothing is unpacked.
 */
class Places(bytes: ByteArray) {
    private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private val cell: Double
    private val cells: Int
    private val recordsAt: Int

    /** How many places the list holds. */
    val size: Int

    init {
        require(bytes.size >= HEADER + 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "GTAP") { "not a place list" }
        require(bytes[4].toInt() == 1) { "place list version ${bytes[4]} is not understood" }
        cell = buf.getInt(6) / 1e6
        cells = buf.getInt(10)
        size = buf.getInt(HEADER + cells * CELL)
        recordsAt = HEADER + cells * CELL + 4
        require(bytes.size >= recordsAt + size * RECORD) { "place list is cut short" }
    }

    private fun key(lat: Int, lon: Int) = lat * 65536L + (lon + 32768)
    private fun keyAt(i: Int) = key(buf.getShort(HEADER + i * CELL).toInt(), buf.getShort(HEADER + i * CELL + 2).toInt())
    private fun firstRecord(i: Int) = if (i < cells) buf.getInt(HEADER + i * CELL + 4) else size

    private fun find(lat: Int, lon: Int): Int {
        val wanted = key(lat, lon)
        var low = 0
        var high = cells - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val k = keyAt(mid)
            when {
                k < wanted -> low = mid + 1
                k > wanted -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** Every place of a [wanted] kind within [radiusM] metres of a point. */
    fun near(lat: Double, lon: Double, radiusM: Double, wanted: (Kind) -> Boolean): List<Place> {
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val dLat = radiusM / M_PER_DEGREE
        val dLon = radiusM / (M_PER_DEGREE * cosLat)
        val out = ArrayList<Place>()
        for (cLat in floor((lat - dLat) / cell).toInt()..floor((lat + dLat) / cell).toInt()) {
            for (cLon in floor((lon - dLon) / cell).toInt()..floor((lon + dLon) / cell).toInt()) {
                val i = find(cLat, cLon)
                if (i < 0) continue
                for (r in firstRecord(i) until firstRecord(i + 1)) {
                    val at = recordsAt + r * RECORD
                    val kind = Kind.entries.getOrNull(buf.get(at + 4).toInt() and 0xFF) ?: continue
                    if (!wanted(kind)) continue
                    val pLat = (cLat + (buf.getShort(at).toInt() and 0xFFFF) / 65536.0) * cell
                    val pLon = (cLon + (buf.getShort(at + 2).toInt() and 0xFFFF) / 65536.0) * cell
                    val y = (pLat - lat) * M_PER_DEGREE
                    val x = (pLon - lon) * M_PER_DEGREE * cosLat
                    if (x * x + y * y <= radiusM * radiusM) out.add(Place(r, pLat, pLon, kind))
                }
            }
        }
        return out
    }

    companion object {
        const val M_PER_DEGREE = 111_320.0
        private const val HEADER = 14
        private const val CELL = 8
        private const val RECORD = 5
    }
}

/** Picks which places get an icon for a position and a zoom. Plain Kotlin, so it is tested on the Mac. */
object IconPicker {
    /** Places are looked for this far from the position, in screen pixels: the 480 x 800 screen and some room to pan. */
    const val VIEW_PX = 700.0

    /** No two icons closer than this many icon widths, so they never sit on top of each other. */
    const val GAP_ANY = 1.15

    /** Never more icons than this at once; the nearest are kept. */
    const val MAX_ICONS = 120

    /**
     * Metres of ground per screen pixel on the Karoo's map. Measured on the unit: at zoom 16 in
     * Prague, 100 m on the scale is about 130 px, which is a map tile drawn 512 px wide.
     */
    fun metresPerPixel(lat: Double, zoom: Double) =
        156_543.03392 * cos(Math.toRadians(lat)) / 2.0.pow(zoom) / 2.0

    fun pick(places: Places, lat: Double, lon: Double, zoom: Double, iconPx: Int): List<Place> {
        if (zoom < FAR) return emptyList()
        val mpp = metresPerPixel(lat, zoom)
        // The same order whatever the position, so an icon does not come and go as the rider moves.
        val candidates = places.near(lat, lon, VIEW_PX * mpp) { zoom >= it.fromZoom }
            .sortedWith(compareBy({ it.kind.rank }, { it.id }))

        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val gapAny = iconPx * GAP_ANY * mpp
        val cell = iconPx * Kind.entries.maxOf { it.spread } * mpp
        val grid = HashMap<Long, MutableList<Spot>>()
        val reach = 1
        val kept = ArrayList<Spot>()
        for (p in candidates) {
            val x = (p.lon - lon) * Places.M_PER_DEGREE * cosLat
            val y = (p.lat - lat) * Places.M_PER_DEGREE
            val gx = floor(x / cell).toLong()
            val gy = floor(y / cell).toLong()
            var free = true
            search@ for (ix in gx - reach..gx + reach) {
                for (iy in gy - reach..gy + reach) {
                    for (other in grid[ix * 1_000_003L + iy] ?: continue) {
                        val d2 = (other.x - x) * (other.x - x) + (other.y - y) * (other.y - y)
                        val gap = if (other.place.kind.icon == p.kind.icon) iconPx * p.kind.spread * mpp else gapAny
                        if (d2 < gap * gap) {
                            free = false
                            break@search
                        }
                    }
                }
            }
            if (!free) continue
            val spot = Spot(p, x, y)
            grid.getOrPut(gx * 1_000_003L + gy) { ArrayList() }.add(spot)
            kept.add(spot)
        }
        if (kept.size > MAX_ICONS) kept.sortBy { it.x * it.x + it.y * it.y }
        return kept.take(MAX_ICONS).map { it.place }
    }

    private class Spot(val place: Place, val x: Double, val y: Double)
}
