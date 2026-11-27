package local.karoo.gtavimap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.floor

class PlacesTest {
    /** Packs a list the way make_places.py does. */
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

    /** A point some metres east and north of the middle of Prague. */
    private fun at(eastM: Double, northM: Double, kind: Kind) = Triple(
        LAT + northM / Places.M_PER_DEGREE, LON + eastM / (Places.M_PER_DEGREE * cos(Math.toRadians(LAT))), kind)

    @Test
    fun `places are found within the radius, also across a cell border`() {
        val list = pack(
            at(100.0, 0.0, Kind.WATER),
            at(-3_000.0, 1_000.0, Kind.FUEL),       // lon 14.3999..: the cell to the west
            at(0.0, 9_000.0, Kind.SHELTER),         // too far
        )
        assertEquals(3, list.size)
        val found = list.near(LAT, LON, 5_000.0) { true }
        assertEquals(setOf(Kind.WATER, Kind.FUEL), found.map { it.kind }.toSet())
        val water = found.first { it.kind == Kind.WATER }
        assertEquals(LAT, water.lat, 0.00001)
        assertEquals(setOf(Kind.FUEL), list.near(LAT, LON, 5_000.0) { it == Kind.FUEL }.map { it.kind }.toSet())
    }

    @Test
    fun `from far only the far kinds show, close up all of them`() {
        val list = pack(
            // 800 m between the two far ones: at zoom 12 an icon covers about 550 m of ground.
            at(0.0, 400.0, Kind.FUEL), at(400.0, 0.0, Kind.FOOD), at(-400.0, 0.0, Kind.TOILETS), at(0.0, -400.0, Kind.SHELTER),
        )
        assertEquals(setOf(Kind.FUEL, Kind.SHELTER), IconPicker.pick(list, LAT, LON, 12.0, 45).map { it.kind }.toSet())
        assertEquals(setOf(Kind.FUEL, Kind.SHELTER, Kind.FOOD), IconPicker.pick(list, LAT, LON, 14.0, 45).map { it.kind }.toSet())
        assertEquals(4, IconPicker.pick(list, LAT, LON, 16.0, 45).size)
        assertTrue(IconPicker.pick(list, LAT, LON, 9.0, 45).isEmpty())
    }

    @Test
    fun `far kinds still show at the 10 km scale, thinned out`() {
        // A gas station every 2 km along a line: at zoom 10 a 52 px icon covers about 2.5 km of
        // ground, and two gas stations keep 3 widths apart.
        val road = (0 until 20).map { at(it * 2_000.0 - 20_000.0, 0.0, Kind.FUEL) }.toTypedArray()
        val list = pack(*road, at(500.0, 500.0, Kind.FOOD), at(-500.0, 3_000.0, Kind.SHELTER))
        val shown = IconPicker.pick(list, LAT, LON, 10.0, 52)
        assertEquals(setOf(Kind.FUEL, Kind.SHELTER), shown.map { it.kind }.toSet())
        val fuel = shown.filter { it.kind == Kind.FUEL }.size
        assertTrue("$fuel gas stations", fuel in 4..6)
    }

    @Test
    fun `icons never sit on top of each other, and the more important one stays`() {
        // At zoom 16 a pixel is about 0.77 m, so a 45 px icon is about 35 m wide.
        val list = pack(at(0.0, 0.0, Kind.FOOD), at(10.0, 0.0, Kind.WATER), at(200.0, 0.0, Kind.COFFEE))
        assertEquals(listOf(Kind.WATER, Kind.COFFEE), IconPicker.pick(list, LAT, LON, 16.0, 45).map { it.kind })
    }

    @Test
    fun `a street full of restaurants gets one burger, not ten`() {
        val street = (0 until 10).map { at(it * 45.0, 0.0, Kind.FOOD) }.toTypedArray()
        val shown = IconPicker.pick(pack(*street), LAT, LON, 16.0, 45)
        // 45 m apart is clear of overlap, but two burgers keep 5 icon widths apart: about 170 m.
        assertEquals(3, shown.size)
    }

    @Test
    fun `close in, a tilted map still gets what it can see`() {
        // Zoom 17.8, the 50 m scale: a pixel is about 0.22 m, so 2100 px is about 460 m. The
        // case seen on the unit (27.11.2026): the nearest gas station 660 m off, none shown.
        val list = pack(at(0.0, -660.0, Kind.FUEL), at(0.0, 300.0, Kind.FOOD), at(0.0, 600.0, Kind.FOOD),
            at(2_500.0, 0.0, Kind.FUEL))
        val shown = IconPicker.pick(list, LAT, LON, 17.8, 52)
        assertEquals(listOf(Kind.FUEL, Kind.FOOD), shown.map { it.kind })
        assertEquals(LAT - 660.0 / Places.M_PER_DEGREE, shown[0].lat, 0.00001)
    }

    @Test
    fun `when there are too many, gas stations and the like are kept further out`() {
        // 225 restaurants 60 m apart around the rider, and a gas station 1 km off: of the 120
        // kept, the gas station is one, though about 180 restaurants are nearer.
        val grid = (0 until 225).map { at((it % 15 - 7) * 60.0, (it / 15 - 7) * 60.0, Kind.FOOD) }.toTypedArray()
        val shown = IconPicker.pick(pack(*grid, at(0.0, 1_000.0, Kind.FUEL)), LAT, LON, 17.8, 52)
        assertEquals(IconPicker.MAX_ICONS, shown.size)
        assertTrue(shown.any { it.kind == Kind.FUEL })
    }

    @Test
    fun `the scale matches what the Karoo shows`() {
        // Read from the unit: at zoom 16 in Prague 100 m is about 130 px.
        assertEquals(130.0, 100.0 / IconPicker.metresPerPixel(50.08, 16.0), 2.0)
    }

    companion object {
        const val LAT = 50.0755
        const val LON = 14.4378
    }
}
