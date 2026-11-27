package local.karoo.gtavimap

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// The route the rider follows, as the fields need it: where the rider is along it, how high it
// is, what lies ahead. Plain Kotlin, no Android, so it is tested on the Mac (RouteTest).

/** Google's "encoded polyline": pairs of numbers, each stored as the step from the pair before. */
object Polyline {
    /** [precision] is the number of decimals kept: 5 for the Karoo's positions, 1 for its distance and height pairs. */
    fun decode(encoded: String, precision: Int): List<DoubleArray> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<DoubleArray>()
        var i = 0
        var a = 0L
        var b = 0L
        while (i < encoded.length) {
            val step = LongArray(2)
            for (k in 0..1) {
                var shift = 0
                var bits = 0L
                while (true) {
                    if (i >= encoded.length) return out          // cut short: keep what is whole
                    val c = encoded[i++].code - 63
                    bits = bits or ((c and 0x1F).toLong() shl shift)
                    shift += 5
                    if (c < 0x20) break
                }
                step[k] = if (bits and 1L != 0L) (bits shr 1).inv() else bits shr 1
            }
            a += step[0]
            b += step[1]
            out.add(doubleArrayOf(a / factor, b / factor))
        }
        return out
    }

    fun encode(pairs: List<DoubleArray>, precision: Int): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = StringBuilder()
        var a = 0L
        var b = 0L
        for (p in pairs) {
            val na = Math.round(p[0] * factor)
            val nb = Math.round(p[1] * factor)
            for (step in longArrayOf(na - a, nb - b)) {
                var bits = if (step < 0) (step shl 1).inv() else step shl 1
                while (bits >= 0x20) {
                    out.append(((0x20L or (bits and 0x1F)) + 63).toInt().toChar())
                    bits = bits shr 5
                }
                out.append((bits + 63).toInt().toChar())
            }
            a = na
            b = nb
        }
        return out.toString()
    }
}

/** A climb on the route, in metres along it. */
class RouteClimb(val start: Double, val end: Double)

/**
 * A place on or beside the route. [at] is metres along the route, [off] metres to the side.
 * [group] says what kind it is: the list shows the next one of each group. [own] marks a place
 * the rider saved in the route itself; those have a [name] of their own.
 */
class RoutePlace(val icon: String, val name: String, val group: String, val at: Double, val off: Double, val own: Boolean)

/** Where a point is, seen from the route. */
class Located(val at: Double, val off: Double)

/**
 * @param line the route as latitude and longitude pairs
 * @param knownLength the Karoo's figure for the length in metres, or null to measure the line
 * @param heights metres along the route and metres above the sea, in pairs; may be empty
 */
class Route(line: List<DoubleArray>, knownLength: Double?, heights: List<DoubleArray>, val climbs: List<RouteClimb>) {
    private val lat = DoubleArray(line.size) { line[it][0] }
    private val lon = DoubleArray(line.size) { line[it][1] }
    private val along = DoubleArray(line.size)
    private val cosLat = cos(Math.toRadians(if (line.isEmpty()) 0.0 else line[line.size / 2][0])).coerceAtLeast(0.01)

    private val heightAt: DoubleArray
    private val height: DoubleArray
    private val gain: DoubleArray

    /** Metres from the start to the end. */
    val length: Double

    val points get() = lat.size
    val hasHeights get() = height.size >= 2
    var lowest = 0.0
        private set
    var highest = 0.0
        private set

    init {
        for (i in 1 until lat.size) along[i] = along[i - 1] + metres(lat[i - 1], lon[i - 1], lat[i], lon[i])
        val measured = if (lat.isEmpty()) 0.0 else along[lat.size - 1]
        length = knownLength?.takeIf { it > 0 } ?: measured
        // The Karoo's own figure wins: everything it tells in metres along the route then fits.
        if (measured > 0 && length != measured) for (i in along.indices) along[i] *= length / measured

        val sorted = heights.filter { it[0].isFinite() && it[1].isFinite() }.sortedBy { it[0] }
        heightAt = DoubleArray(sorted.size) { sorted[it][0] }
        height = DoubleArray(sorted.size) { sorted[it][1] }
        gain = DoubleArray(sorted.size)
        for (i in 1 until height.size) gain[i] = gain[i - 1] + max(0.0, height[i] - height[i - 1])
        if (height.isNotEmpty()) {
            lowest = height.min()
            highest = height.max()
        }
    }

    private fun metres(lat0: Double, lon0: Double, lat1: Double, lon1: Double): Double {
        val x = (lon1 - lon0) * Places.M_PER_DEGREE * cos(Math.toRadians((lat0 + lat1) / 2))
        val y = (lat1 - lat0) * Places.M_PER_DEGREE
        return sqrt(x * x + y * y)
    }

    private fun between(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
        if (xs.isEmpty()) return 0.0
        if (x <= xs[0]) return ys[0]
        if (x >= xs[xs.size - 1]) return ys[xs.size - 1]
        var low = 0
        var high = xs.size - 1
        while (high - low > 1) {
            val mid = (low + high) ushr 1
            if (xs[mid] <= x) low = mid else high = mid
        }
        val span = xs[high] - xs[low]
        return if (span <= 0) ys[low] else ys[low] + (ys[high] - ys[low]) * (x - xs[low]) / span
    }

    /** Metres above the sea at [d] metres along the route. */
    fun heightAt(d: Double) = between(heightAt, height, d)

    /** Metres climbed between two points of the route: every rise counted, no descent taken off. */
    fun climb(from: Double, to: Double) = if (!hasHeights || to <= from) 0.0 else between(heightAt, gain, to) - between(heightAt, gain, from)

    /** The last point of the line at or before [d] metres along. */
    private fun indexAt(d: Double): Int {
        var low = 0
        var high = along.size - 1
        while (high - low > 1) {
            val mid = (low + high) ushr 1
            if (along[mid] <= d) low = mid else high = mid
        }
        return low
    }

    /** The position at [d] metres along the route, as latitude and longitude. */
    fun pointAt(d: Double): DoubleArray {
        if (lat.isEmpty()) return doubleArrayOf(0.0, 0.0)
        if (lat.size == 1) return doubleArrayOf(lat[0], lon[0])
        val i = indexAt(d.coerceIn(0.0, length))
        val span = along[i + 1] - along[i]
        val t = if (span <= 0) 0.0 else ((d - along[i]) / span).coerceIn(0.0, 1.0)
        return doubleArrayOf(lat[i] + (lat[i + 1] - lat[i]) * t, lon[i] + (lon[i + 1] - lon[i]) * t)
    }

    /**
     * The point of the route nearest to a position, looked for between [from] and [to] metres
     * along. Null if the route has no line.
     */
    fun locate(pLat: Double, pLon: Double, from: Double = 0.0, to: Double = length): Located? {
        if (lat.size < 2) return null
        val first = indexAt(from.coerceIn(0.0, length))
        val last = min(indexAt(to.coerceIn(0.0, length)), lat.size - 2)
        var best = Double.MAX_VALUE
        var bestAt = 0.0
        val px = pLon * Places.M_PER_DEGREE * cosLat
        val py = pLat * Places.M_PER_DEGREE
        for (i in first..last) {
            val ax = lon[i] * Places.M_PER_DEGREE * cosLat
            val ay = lat[i] * Places.M_PER_DEGREE
            val dx = lon[i + 1] * Places.M_PER_DEGREE * cosLat - ax
            val dy = lat[i + 1] * Places.M_PER_DEGREE - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val ox = px - (ax + dx * t)
            val oy = py - (ay + dy * t)
            val d2 = ox * ox + oy * oy
            if (d2 < best) {
                best = d2
                bestAt = along[i] + (along[i + 1] - along[i]) * t
            }
        }
        return Located(bestAt, sqrt(best))
    }

    /** The climb the rider is on at [d] metres along, or null. */
    fun climbAt(d: Double) = climbs.indexOfFirst { d >= it.start && d < it.end }.takeIf { it >= 0 }
}

/** Finds what lies along a route, and picks what the fields show. */
object Along {
    /** A place counts as "on the way" up to this many metres from the route. */
    const val MAX_OFF = 300.0

    /** Closer to the route than this reads as "on route". */
    const val ON_ROUTE = 30.0

    /** A place this close behind or ahead counts as reached: it leaves the list. */
    const val REACHED = 30.0

    private const val STEP = 400.0

    /** What a kind of place is called in the list. */
    fun label(kind: Kind) = when (kind) {
        Kind.SHELTER -> "SHELTER"
        Kind.WATER -> "WATER"
        Kind.SUPERMARKET -> "GROCERIES"
        Kind.SHOP -> "SHOP"
        Kind.FOOD -> "FOOD"
        Kind.FUEL -> "GAS STATION"
        Kind.COFFEE -> "CAFE"
        Kind.BIKE -> "BIKE SHOP"
        Kind.TRAIN -> "TRAIN"
        Kind.TOILETS -> "TOILETS"
    }

    /**
     * Every place of the list within [MAX_OFF] of the route, sorted by where it is along the
     * route. A route that passes a place twice, a good way apart, lists it twice.
     */
    fun find(route: Route, places: Places): List<RoutePlace> {
        if (route.points < 2) return emptyList()
        // Looked up every STEP metres along the line, far enough around to miss nothing between.
        val reach = sqrt(MAX_OFF * MAX_OFF + STEP * STEP / 4) + 20
        val seen = HashMap<Int, MutableList<Double>>()
        val out = ArrayList<RoutePlace>()
        var d = 0.0
        while (d <= route.length + STEP) {
            val here = min(d, route.length)
            val p = route.pointAt(here)
            for (place in places.near(p[0], p[1], reach) { true }) {
                val spot = route.locate(place.lat, place.lon, here - STEP - reach, here + STEP + reach) ?: continue
                if (spot.off > MAX_OFF) continue
                val before = seen.getOrPut(place.id) { ArrayList() }
                if (before.any { Math.abs(it - spot.at) < 1500 }) continue
                before.add(spot.at)
                out.add(RoutePlace(place.kind.icon, label(place.kind), place.kind.name, spot.at, spot.off, false))
            }
            d += STEP
        }
        out.sortBy { it.at }
        return out
    }

    /**
     * What the list and the elevation show from [at] metres along: the next place of each kind
     * and every place the rider saved in the route, nearest first, [count] at most.
     * [sorted] must be in order of [RoutePlace.at].
     */
    fun ahead(sorted: List<RoutePlace>, at: Double, count: Int): List<RoutePlace> {
        var low = 0
        var high = sorted.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sorted[mid].at <= at + REACHED) low = mid + 1 else high = mid
        }
        val out = ArrayList<RoutePlace>()
        val groups = HashSet<String>()
        var i = low
        while (i < sorted.size && out.size < count) {
            val p = sorted[i++]
            if (p.own || groups.add(p.group)) out.add(p)
        }
        return out
    }
}

/** A made-up route for the previews: the Karoo's field picker and the preview screen. */
object SampleRoute {
    const val AT = 42_500.0

    private val shape = arrayOf(
        doubleArrayOf(0.0, 240.0), doubleArrayOf(5.0, 300.0), doubleArrayOf(9.0, 560.0), doubleArrayOf(13.0, 330.0),
        doubleArrayOf(18.0, 380.0), doubleArrayOf(22.0, 690.0), doubleArrayOf(27.0, 360.0), doubleArrayOf(31.0, 420.0),
        doubleArrayOf(34.0, 610.0), doubleArrayOf(38.0, 340.0), doubleArrayOf(42.5, 300.0), doubleArrayOf(47.0, 330.0),
        doubleArrayOf(51.0, 520.0), doubleArrayOf(56.0, 350.0), doubleArrayOf(61.0, 300.0), doubleArrayOf(66.0, 330.0),
        doubleArrayOf(67.0, 356.0), doubleArrayOf(68.0, 403.0), doubleArrayOf(69.0, 463.0), doubleArrayOf(70.0, 493.0),
        doubleArrayOf(71.0, 547.0), doubleArrayOf(72.0, 625.0), doubleArrayOf(73.0, 723.0), doubleArrayOf(74.0, 790.0),
        doubleArrayOf(79.0, 480.0), doubleArrayOf(84.0, 400.0), doubleArrayOf(88.0, 430.0), doubleArrayOf(92.0, 590.0),
        doubleArrayOf(96.0, 380.0), doubleArrayOf(101.0, 300.0), doubleArrayOf(106.0, 260.0),
    )

    /** A smooth curve through the points above, with a little ripple. */
    private fun heightAtKm(d: Double): Double {
        val n = shape.size
        var i = 0
        while (i < n - 2 && d > shape[i + 1][0]) i++
        fun slope(k: Int) = (shape[k + 1][1] - shape[k][1]) / (shape[k + 1][0] - shape[k][0])
        fun lean(k: Int) = if (k <= 0) slope(0) else if (k >= n - 1) slope(n - 2) else (slope(k - 1) + slope(k)) / 2
        val span = shape[i + 1][0] - shape[i][0]
        val t = ((d - shape[i][0]) / span).coerceIn(0.0, 1.0)
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * shape[i][1] + (t3 - 2 * t2 + t) * span * lean(i) +
            (-2 * t3 + 3 * t2) * shape[i + 1][1] + (t3 - t2) * span * lean(i + 1) + 4 * sin(d * 1.7)
    }

    val route: Route by lazy {
        val km = 106.0
        // A straight line east from Prague: only its length matters here.
        val perKm = 1000.0 / (Places.M_PER_DEGREE * cos(Math.toRadians(50.08)))
        val line = (0..106).map { doubleArrayOf(50.08, 14.42 + it * perKm) }
        val heights = (0..(km * 10).roundToInt()).map { doubleArrayOf(it * 100.0, heightAtKm(it / 10.0)) }
        val climbs = listOf(5 to 9, 18 to 22, 31 to 34, 47 to 51, 66 to 74, 88 to 92).map { RouteClimb(it.first * 1000.0, it.second * 1000.0) }
        Route(line, km * 1000, heights, climbs)
    }

    val places = listOf(
        RoutePlace("water", "WATER", "WATER", 54_900.0, 0.0, false),
        RoutePlace("groceries", "GROCERIES", "SUPERMARKET", 62_800.0, 150.0, false),
        RoutePlace("coffee", "CAFE", "COFFEE", 70_400.0, 0.0, false),
        RoutePlace("pin", "KOKOŘÍN CASTLE", "own", 74_200.0, 0.0, true),
        RoutePlace("shelter", "SHELTER", "SHELTER", 81_100.0, 40.0, false),
        RoutePlace("fuel", "GAS STATION", "FUEL", 86_500.0, 0.0, false),
        RoutePlace("bike", "BIKE SHOP", "BIKE", 93_700.0, 300.0, false),
    )
}

/** Round steps for a scale: the smallest of [steps] that cuts [span] into [most] pieces or fewer. */
fun niceStep(span: Double, most: Int, steps: DoubleArray): Double =
    steps.firstOrNull { span / it <= most } ?: (ceil(span / most / steps.last()) * steps.last())

/** The bottom and top of a height scale that leaves a little air around [low] and [high]. */
fun heightScale(low: Double, high: Double): DoubleArray {
    val step = niceStep(max(high - low, 40.0), 4, doubleArrayOf(20.0, 50.0, 100.0, 200.0, 250.0, 500.0, 1000.0))
    return doubleArrayOf(floor(low / step) * step - step * 0.25, ceil(high / step) * step + step * 0.25, step)
}
