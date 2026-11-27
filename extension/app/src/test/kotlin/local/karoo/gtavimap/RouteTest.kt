package local.karoo.gtavimap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.floor

class RouteTest {
    private val perMetreLat = 1 / Places.M_PER_DEGREE
    private val perMetreLon = 1 / (Places.M_PER_DEGREE * cos(Math.toRadians(LAT)))

    /** A point some metres east and north of the middle of Prague. */
    private fun at(eastM: Double, northM: Double) = doubleArrayOf(LAT + northM * perMetreLat, LON + eastM * perMetreLon)

    /** Ten kilometres east, then ten north: an L. */
    private fun corner(heights: List<DoubleArray> = emptyList(), length: Double? = null) =
        Route(listOf(at(0.0, 0.0), at(10_000.0, 0.0), at(10_000.0, 10_000.0)), length, heights, emptyList())

    /** Packs a place list the way make_places.py does (as in PlacesTest). */
    private fun pack(vararg places: Triple<Double, Double, Kind>): Places {
        val cell = 100_000
        val cells = sortedMapOf<Long, MutableList<IntArray>>()
        for ((lat, lon, kind) in places) {
            val mLat = Math.round(lat * 1e6).toInt()
            val mLon = Math.round(lon * 1e6).toInt()
            val cLat = floor(mLat / cell.toDouble()).toInt()
            val cLon = floor(mLon / cell.toDouble()).toInt()
            val rec = intArrayOf(((mLat - cLat * cell) * 65536L / cell).toInt(), ((mLon - cLon * cell) * 65536L / cell).toInt(), kind.ordinal)
            cells.getOrPut(cLat * 65536L + (cLon + 32768)) { ArrayList() }.add(rec)
        }
        val buf = ByteBuffer.allocate(14 + cells.size * 8 + 4 + places.size * 5).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("GTAP".toByteArray()).put(1).put(Kind.entries.size.toByte()).putInt(cell).putInt(cells.size)
        var first = 0
        for ((key, recs) in cells) {
            buf.putShort(Math.floorDiv(key, 65536L).toShort()).putShort((Math.floorMod(key, 65536L) - 32768).toShort()).putInt(first)
            first += recs.size
        }
        buf.putInt(first)
        for (recs in cells.values) for (r in recs) buf.putShort(r[0].toShort()).putShort(r[1].toShort()).put(r[2].toByte())
        return Places(buf.array())
    }

    private fun place(eastM: Double, northM: Double, kind: Kind) = at(eastM, northM).let { Triple(it[0], it[1], kind) }

    @Test
    fun `a polyline is read as Google writes it`() {
        // The example from Google's description of the format.
        val points = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, points.size)
        assertEquals(38.5, points[0][0], 1e-9)
        assertEquals(-120.2, points[0][1], 1e-9)
        assertEquals(43.252, points[2][0], 1e-9)
        assertEquals(-126.453, points[2][1], 1e-9)
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", Polyline.encode(points, 5))
    }

    @Test
    fun `distance and height pairs survive the trip at one decimal`() {
        val pairs = listOf(doubleArrayOf(0.0, 240.5), doubleArrayOf(100.0, 238.2), doubleArrayOf(250_000.0, 1_890.4), doubleArrayOf(250_100.0, -3.0))
        val back = Polyline.decode(Polyline.encode(pairs, 1), 1)
        assertEquals(pairs.size, back.size)
        for (i in pairs.indices) {
            assertEquals(pairs[i][0], back[i][0], 1e-9)
            assertEquals(pairs[i][1], back[i][1], 1e-9)
        }
        assertEquals(2, Polyline.decode(Polyline.encode(pairs, 1).dropLast(1), 1).size.coerceAtMost(2))   // a cut-off end is dropped, not made up
    }

    @Test
    fun `a position is found along the line, with how far it is off`() {
        val route = corner()
        assertEquals(20_000.0, route.length, 20.0)
        val onFirstLeg = route.locate(at(4_000.0, 50.0)[0], at(4_000.0, 50.0)[1])!!
        assertEquals(4_000.0, onFirstLeg.at, 5.0)
        assertEquals(50.0, onFirstLeg.off, 1.0)
        val onSecondLeg = route.locate(at(10_120.0, 7_000.0)[0], at(10_120.0, 7_000.0)[1])!!
        assertEquals(17_000.0, onSecondLeg.at, 20.0)
        assertEquals(120.0, onSecondLeg.off, 1.0)
        // Only looked for on the first leg: the nearest point there is its end.
        assertEquals(10_000.0, route.locate(at(10_120.0, 7_000.0)[0], at(10_120.0, 7_000.0)[1], 0.0, 9_000.0)!!.at, 20.0)
        val middle = route.pointAt(15_000.0)
        assertEquals(at(10_000.0, 5_000.0)[0], middle[0], 1e-4)
    }

    @Test
    fun `the Karoo's figure for the length wins over the measured one`() {
        val route = corner(length = 21_000.0)
        assertEquals(21_000.0, route.length, 0.001)
        assertEquals(10_500.0, route.locate(at(10_000.0, 0.0)[0], at(10_000.0, 0.0)[1])!!.at, 20.0)
    }

    @Test
    fun `climb counts every rise and no descent`() {
        val heights = listOf(0.0 to 200.0, 5_000.0 to 300.0, 10_000.0 to 250.0, 20_000.0 to 450.0).map { doubleArrayOf(it.first, it.second) }
        val route = corner(heights)
        assertTrue(route.hasHeights)
        assertEquals(200.0, route.lowest, 0.0)
        assertEquals(450.0, route.highest, 0.0)
        assertEquals(250.0, route.heightAt(2_500.0), 1e-9)
        assertEquals(300.0, route.climb(0.0, 20_000.0), 1e-9)
        assertEquals(50.0, route.climb(2_500.0, 7_500.0), 1e-9)       // up 50, then down 25
        assertEquals(100.0, route.climb(7_500.0, 15_000.0), 1e-9)     // down 25, then up 100
        assertEquals(0.0, route.climb(5_000.0, 5_000.0), 0.0)
        assertFalse(corner().hasHeights)
        assertEquals(0.0, corner().climb(0.0, 20_000.0), 0.0)
    }

    @Test
    fun `places near the route are found in the order the rider meets them`() {
        val list = pack(
            place(16_000.0 - 6_000.0, 6_000.0, Kind.FUEL),      // on the second leg, 16 km along
            place(3_000.0, 120.0, Kind.WATER),                  // 3 km along, 120 m off
            place(5_200.0, -299.0, Kind.SHELTER),               // 5.2 km along, just near enough
            place(7_000.0, 400.0, Kind.FOOD),                   // too far off
            place(-250.0, 0.0, Kind.COFFEE),                    // behind the start, still within reach of it
        )
        val found = Along.find(corner(), list)
        assertEquals(listOf("CAFE", "WATER", "SHELTER", "GAS STATION"), found.map { it.name })
        assertEquals(3_000.0, found[1].at, 10.0)
        assertEquals(120.0, found[1].off, 2.0)
        assertEquals(16_000.0, found[3].at, 30.0)
        assertEquals("fuel", found[3].icon)
    }

    @Test
    fun `the list shows the next of each kind and every saved place`() {
        fun our(kind: Kind, at: Double) = RoutePlace(kind.icon, Along.label(kind), kind.name, at, 0.0, false)
        val sorted = listOf(
            our(Kind.WATER, 1_000.0), our(Kind.WATER, 2_000.0), our(Kind.FOOD, 2_500.0),
            RoutePlace("pin", "LUNCH", "own", 3_000.0, 0.0, true), our(Kind.WATER, 4_000.0),
            RoutePlace("pin", "VIEW", "own", 5_000.0, 0.0, true), our(Kind.SHOP, 6_000.0), our(Kind.SUPERMARKET, 7_000.0),
        )
        assertEquals(listOf(1_000.0, 2_500.0, 3_000.0, 5_000.0, 6_000.0, 7_000.0), Along.ahead(sorted, 0.0, 7).map { it.at })
        // The water at 1 km is reached: the next water takes its place.
        assertEquals(listOf(2_000.0, 2_500.0, 3_000.0), Along.ahead(sorted, 990.0, 3).map { it.at })
        assertEquals(listOf(7_000.0), Along.ahead(sorted, 6_500.0, 7).map { it.at })
        assertTrue(Along.ahead(sorted, 7_000.0, 7).isEmpty())
    }

    @Test
    fun `the made-up route gives the numbers of the mockups`() {
        val route = SampleRoute.route
        assertEquals(106_000.0, route.length, 0.001)
        assertEquals(912.0, route.climb(SampleRoute.AT, route.length), 6.0)
        assertEquals(223.0, route.climb(SampleRoute.AT, 54_900.0), 4.0)
        assertEquals(7, Along.ahead(SampleRoute.places, SampleRoute.AT, 7).size)
        assertEquals(4, route.climbAt(69_400.0))
        assertNull(route.climbAt(60_000.0))
        assertNotNull(route.locate(50.08, 14.6))
        val scale = heightScale(route.lowest, route.highest)
        assertEquals(150.0, scale[0], 0.0)
        assertEquals(850.0, scale[1], 0.0)
        assertEquals(200.0, scale[2], 0.0)
    }

    private companion object {
        const val LAT = 50.0755
        const val LON = 14.4378
    }
}
