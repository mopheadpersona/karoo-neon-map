package local.karoo.gtavimap

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import io.hammerhead.karooext.models.DataType
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The data fields of the extension. Each one draws its whole cell itself, in the look of the
 * game's on-screen displays: a dark panel, condensed white numbers,
 * round badges, bars split at the zones, wanted stars. Our own drawing; no game artwork or fonts.
 *
 * [typeId] is the name the Karoo knows the field by (res/xml/extension_info.xml: keep the two in
 * step); [streams] are the ride values it needs.
 */
enum class FieldKind(val typeId: String, val streams: List<String>) {
    /** Heart rate as wanted stars: one star per heart-rate zone. */
    WANTED("wanted", listOf(DataType.Type.HEART_RATE)),

    /** Heart rate as a bar split at the zones, with a heart badge. */
    HEALTH("health", listOf(DataType.Type.HEART_RATE)),

    /** Power (3 s average) as a bar split at the zones, with a lightning badge. */
    STAMINA("stamina", listOf(DataType.Type.SMOOTHED_3S_AVERAGE_POWER)),

    /** Cadence as a bar. */
    FOCUS("focus", listOf(DataType.Type.CADENCE)),

    /** Speed, big. */
    SPEED("speed", listOf(DataType.Type.SPEED)),

    /** Distance and climb, one above the other, like the game's two money counters. */
    CASH("cash", listOf(DataType.Type.DISTANCE, DataType.Type.ELEVATION_GAIN)),

    /** Ride time, big, with the time of day in a small box above it. */
    TIME("time", listOf(DataType.Type.ELAPSED_TIME)),

    /**
     * For the row above the map: speed, the wanted stars, watts and cadence in one cell. The Karoo
     * gives a single field there a row 191 px tall (seen 27.11.2026), half as tall again as a row
     * of two fields; the four values keep their size and sit in its middle.
     */
    HUD("hud", listOf(DataType.Type.SPEED, DataType.Type.HEART_RATE, DataType.Type.SMOOTHED_3S_AVERAGE_POWER, DataType.Type.CADENCE)),

    /**
     * The right half of that row as a cell of its own: the stars above watts and cadence. With
     * "Speed, big" on its left the row above the map is 125 px tall, the thinnest the Karoo gives.
     */
    HUD_SIDE("hudside", listOf(DataType.Type.HEART_RATE, DataType.Type.SMOOTHED_3S_AVERAGE_POWER, DataType.Type.CADENCE)),

    /** Speed as a bar, to go with the health, stamina and focus bars. */
    SPEED_BAR("speedbar", listOf(DataType.Type.SPEED)),

    /** Two counters in one cell: average and highest speed. */
    SPEED_PAIR("speedpair", listOf(DataType.Type.AVERAGE_SPEED, DataType.Type.MAX_SPEED)),

    /** Average and highest heart rate. */
    HR_PAIR("hrpair", listOf(DataType.Type.AVERAGE_HR, DataType.Type.MAX_HR)),

    /** Average and normalised power. */
    POWER_PAIR("powerpair", listOf(DataType.Type.AVERAGE_POWER, DataType.Type.NORMALIZED_POWER)),

    /** The route's elevation with the places ahead: for a big cell. */
    PROFILE("profile", listOf(DataType.Type.DISTANCE_TO_DESTINATION)),

    /** How far to the next place on the route. */
    NEXT_PLACE("nextplace", listOf(DataType.Type.DISTANCE_TO_DESTINATION)),

    /** How much climbing to the next place on the route. */
    NEXT_CLIMB("nextclimb", listOf(DataType.Type.DISTANCE_TO_DESTINATION)),

    /** The places ahead as a list: for a cell that fills the page. */
    PLACES("places", listOf(DataType.Type.DISTANCE_TO_DESTINATION)),
}

/**
 * The fonts for labels and numbers. They ship in the app (extension/fonts, SIL OFL). The Karoo's
 * own "sans-serif-condensed" was tried on the unit and is not condensed there: numbers ran into
 * each other.
 */
enum class CondensedFont(val label: String, val asset: String) {
    BARLOW("Barlow Condensed Bold", "BarlowCondensed-Bold.ttf"),
    BARLOW_SEMI("Barlow Condensed SemiBold", "BarlowCondensed-SemiBold.ttf"),
    BEBAS("Bebas Neue", "BebasNeue-Regular.ttf"),
}

/** The fonts for the cash-style numbers. */
enum class ChunkyFont(val label: String, val asset: String) {
    BUNGEE("Bungee", "Bungee-Regular.ttf"),
    RUSSO("Russo One", "RussoOne-Regular.ttf"),
}

class FieldStyle(val condensed: Typeface, val chunky: Typeface, private val icons: (String) -> Drawable?) {
    /** One of the map icons (shelter, water, ... and "pin" for a place from the route), to draw in a field. */
    fun icon(name: String) = icons(name)

    companion object {
        private val cache = HashMap<String, Typeface>()
        private val drawables = HashMap<String, Drawable?>()

        fun of(context: Context, condensed: CondensedFont, chunky: ChunkyFont): FieldStyle {
            val app = context.applicationContext
            return FieldStyle(load(app, condensed.asset), load(app, chunky.asset)) { name ->
                drawables.getOrPut(name) {
                    val id = app.resources.getIdentifier("poi_${name}_l", "drawable", app.packageName)
                    if (id == 0) null else ContextCompat.getDrawable(app, id)?.mutate()
                }
            }
        }

        private fun load(context: Context, asset: String) =
            cache.getOrPut(asset) { Typeface.createFromAsset(context.assets, asset) }
    }
}

/**
 * What the fields show right now. Speeds, distance and climb are already in the rider's units;
 * everything about the route is in metres.
 */
data class FieldValues(
    val hr: Double? = null,
    val hrZones: List<IntRange> = emptyList(),
    val power: Double? = null,
    val powerZones: List<IntRange> = emptyList(),
    val cadence: Double? = null,
    val speed: Double? = null,
    val distance: Double? = null,
    val climb: Double? = null,
    val rideSeconds: Long? = null,
    val clock: String = "",
    val imperial: Boolean = false,
    /** Heights in feet. */
    val feet: Boolean = false,
    /** Flips every second: for things that flash. */
    val blink: Boolean = false,
    val avgSpeed: Double? = null,
    val maxSpeed: Double? = null,
    val avgHr: Double? = null,
    val maxHr: Double? = null,
    val avgPower: Double? = null,
    val np: Double? = null,
    /** The route being followed, how far along it the rider is, and what the list shows from there. */
    val route: Route? = null,
    val routeAt: Double? = null,
    val ahead: List<RoutePlace> = emptyList(),
) {
    /** Which zone a value is in, counted from 1; 0 below the first zone. */
    fun zone(value: Double?, zones: List<IntRange>): Int {
        if (value == null || zones.isEmpty()) return 0
        if (value < zones.first().first) return 0
        val i = zones.indexOfFirst { value <= it.last }
        return if (i < 0) zones.size else i + 1
    }

    companion object {
        /** Made-up values for the preview and the Karoo's field picker. */
        val SAMPLE = FieldValues(
            hr = 152.0, hrZones = listOf(98..117, 118..136, 137..155, 156..174, 175..195),
            power = 230.0, powerZones = listOf(0..137, 138..187, 188..225, 226..262, 263..300, 301..375, 376..500),
            cadence = 88.0, speed = 27.4, distance = 42.5, climb = 1479.0, rideSeconds = 5025, clock = "12:31",
            avgSpeed = 30.4, maxSpeed = 61.8, avgHr = 148.0, maxHr = 181.0, avgPower = 187.0, np = 204.0,
            route = SampleRoute.route, routeAt = SampleRoute.AT, ahead = SampleRoute.places,
        )
    }
}

object FieldArt {
    // Colours picked by eye from pictures of the game's displays.
    // Change them here.
    const val PANEL = 0xFF12122A.toInt()      // the cell's background
    const val CHIP = 0xFF2A2848.toInt()       // badges, boxes, the empty part of a bar
    const val INK = 0xFF05050F.toInt()        // outlines
    const val WHITE = 0xFFF2F4FA.toInt()
    const val DIM = 0xFFA3A6C2.toInt()
    const val PINK = 0xFFF27DB6.toInt()       // health
    const val TEAL = 0xFF5EE6CC.toInt()       // stamina
    const val PURPLE = 0xFF9274F5.toInt()     // focus
    const val GREEN = 0xFF7FE08E.toInt()      // the wallet
    const val RED = 0xFFE5484D.toInt()        // stars at the top zone
    const val YELLOW = 0xFFFFD23F.toInt()     // speed; climbs on the route's elevation
    const val ORANGE = 0xFFFF8A3D.toInt()     // a steep piece of a climb
    const val ARROW = 0xFFFFF200.toInt()      // the rider, as on the map
    const val RIDDEN = 0xFF5B5A82.toInt()     // the edge of the part of the route already ridden

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    @Synchronized
    fun draw(kind: FieldKind, c: Canvas, w: Int, h: Int, v: FieldValues, s: FieldStyle) {
        c.drawColor(PANEL)
        val wf = w.toFloat()
        val hf = h.toFloat()
        when (kind) {
            FieldKind.WANTED -> wanted(c, wf, hf, v, s)
            FieldKind.HEALTH -> bar(c, wf, hf, s, PINK, Glyph.HEART, number(v.hr), "BPM",
                zoneLabel(v.zone(v.hr, v.hrZones)), fraction(v.hr, v.hrZones, hrFloor(v.hrZones)),
                ticks(v.hrZones, hrFloor(v.hrZones)))
            FieldKind.STAMINA -> bar(c, wf, hf, s, TEAL, Glyph.BOLT, number(v.power), "W",
                zoneLabel(v.zone(v.power, v.powerZones)), fraction(v.power, v.powerZones, 0.0), ticks(v.powerZones, 0.0))
            FieldKind.FOCUS -> bar(c, wf, hf, s, PURPLE, Glyph.SPIN, number(v.cadence), "RPM", "",
                v.cadence?.let { ((it - 40) / 80).toFloat().coerceIn(0f, 1f) } ?: 0f, listOf(0.375f, 0.75f))
            FieldKind.SPEED -> speed(c, wf, hf, v, s)
            FieldKind.CASH -> cash(c, wf, hf, v, s)
            FieldKind.TIME -> time(c, wf, hf, v, s)
            FieldKind.HUD -> hud(c, wf, hf, v, s)
            FieldKind.HUD_SIDE -> stats(c, hf * 0.08f, wf - hf * 0.08f, hf, v, s)
            FieldKind.SPEED_BAR -> bar(c, wf, hf, s, YELLOW, Glyph.SPEEDO, one(v.speed), speedUnit(v), "",
                v.speed?.let { (it / if (v.imperial) 40 else 60).toFloat().coerceIn(0f, 1f) } ?: 0f, listOf(1 / 3f, 2 / 3f))
            FieldKind.SPEED_PAIR -> counters(c, wf, hf, s, YELLOW, Glyph.SPEEDO,
                listOf(Counter(one(v.avgSpeed), YELLOW, "AVG"), Counter(one(v.maxSpeed), YELLOW, "MAX")))
            FieldKind.HR_PAIR -> counters(c, wf, hf, s, PINK, Glyph.HEART,
                listOf(Counter(number(v.avgHr), PINK, "AVG"), Counter(number(v.maxHr), PINK, "MAX")))
            FieldKind.POWER_PAIR -> counters(c, wf, hf, s, TEAL, Glyph.BOLT,
                listOf(Counter(number(v.avgPower), TEAL, "AVG"), Counter(number(v.np), TEAL, "NP")))
            FieldKind.PROFILE -> profile(c, wf, hf, v, s)
            FieldKind.NEXT_PLACE -> nextPlace(c, wf, hf, v, s)
            FieldKind.NEXT_CLIMB -> nextClimb(c, wf, hf, v, s)
            FieldKind.PLACES -> placeList(c, wf, hf, v, s)
        }
    }

    // Numbers are written the same on every Karoo, whatever language it is set to.
    private fun number(value: Double?) = value?.roundToInt()?.toString() ?: "--"
    private fun one(value: Double?) = value?.let { String.format(Locale.US, "%.1f", it) } ?: "--"
    private fun thousands(value: Int) = String.format(Locale.US, "%,d", value)
    private fun speedUnit(v: FieldValues) = if (v.imperial) "MPH" else "KM/H"
    private fun distanceWord(v: FieldValues) = if (v.imperial) "MI" else "KM"

    /** Metres of the route as kilometres or miles: one decimal below a hundred. */
    private fun far(metres: Double, v: FieldValues): String {
        val d = max(0.0, metres) / if (v.imperial) 1609.344 else 1000.0
        return String.format(Locale.US, if (d >= 99.95) "%.0f" else "%.1f", d)
    }

    /** Metres of climbing as metres or feet. */
    private fun up(metres: Double, v: FieldValues) = thousands((metres * if (v.feet) 3.28084 else 1.0).roundToInt())
    private fun zoneLabel(zone: Int) = if (zone > 0) "Z$zone" else ""

    /**
     * Where the heart-rate bar starts: one zone's width below the second zone, so the first zone
     * is drawn as wide as the others and the zones don't bunch up at the right end.
     */
    private fun hrFloor(zones: List<IntRange>): Double {
        if (zones.size < 3) return 60.0
        return (zones[1].first - (zones[2].first - zones[1].first)).toDouble().coerceAtLeast(0.0)
    }

    /** Where a value sits on a bar that runs from [floor] to a little into the last zone. */
    private fun fraction(value: Double?, zones: List<IntRange>, floor: Double): Float {
        if (value == null) return 0f
        return ((value - floor) / (top(zones, floor) - floor)).toFloat().coerceIn(0f, 1f)
    }

    private fun ticks(zones: List<IntRange>, floor: Double): List<Float> {
        val top = top(zones, floor)
        return zones.drop(1).map { ((it.first - floor) / (top - floor)).toFloat() }.filter { it > 0.02f && it < 0.98f }
    }

    /** The right-hand end of a bar: a little into the last zone, so its start still shows. */
    private fun top(zones: List<IntRange>, floor: Double): Double {
        if (zones.isEmpty()) return floor + 200
        val last = zones.last()
        val width = if (zones.size > 1) (last.first - zones[zones.size - 2].first).toDouble() else 30.0
        return min(last.last.toDouble(), last.first + width)
    }

    // ---- the fields ----------------------------------------------------------------------

    /** How many of the five stars are lit, and whether the rider is in the top zone. */
    private fun wantedLevel(v: FieldValues): Pair<Int, Boolean> {
        val zones = if (v.hrZones.isEmpty()) STARS else v.hrZones.size
        val zone = v.zone(v.hr, v.hrZones)
        // More or fewer than five zones are spread over the five stars.
        val lit = if (zone == 0) 0 else ((zone * STARS + zones - 1) / zones).coerceIn(1, STARS)
        return Pair(lit, zone >= zones && zone > 0)
    }

    private const val STARS = 5
    private fun starsWidth(r: Float) = STARS * 2 * r + (STARS - 1) * r * 0.22f

    /** Five stars in a row from [left], [lit] of them white, or red while [red]. */
    private fun starRow(c: Canvas, left: Float, cy: Float, r: Float, lit: Int, red: Boolean) {
        var cx = left + r
        for (i in 0 until STARS) {
            star(c, cx, cy, r, if (i >= lit) CHIP else if (red) RED else WHITE)
            cx += 2 * r + r * 0.22f
        }
    }

    private fun wanted(c: Canvas, w: Float, h: Float, v: FieldValues, s: FieldStyle) {
        val (lit, maxed) = wantedLevel(v)
        val wide = w > h * 2.6f
        // In a wide cell the stars take the left two thirds and the number the rest.
        val starsW = if (wide) w * 0.64f else w
        val r = min(h * (if (wide) 0.30f else 0.21f), starsW / 11.4f)
        starRow(c, (starsW - starsWidth(r)) / 2, if (wide) h * 0.5f else h * 0.28f, r, lit, maxed && v.blink)
        val size = h * (if (wide) 0.56f else 0.47f)
        val small = h * 0.19f
        val num = number(v.hr)
        val numW = measure(num, size, s.condensed)
        val unitW = measure("BPM", small, s.condensed)
        val all = numW + h * 0.05f + unitW
        val x = if (wide) starsW + (w - starsW - all) / 2 else (w - all) / 2
        val base = if (wide) h * 0.5f + size * 0.36f else h * 0.93f
        text(c, num, x, base, size, s.condensed, WHITE)
        text(c, "BPM", x + numW + h * 0.05f, base, small, s.condensed, DIM)
    }

    private fun bar(
        c: Canvas, w: Float, h: Float, s: FieldStyle, colour: Int, glyph: Glyph,
        value: String, unit: String, note: String, fill: Float, ticks: List<Float>,
    ) {
        val pad = h * 0.09f
        val r = h * 0.27f
        badge(c, pad + r, h * 0.5f, r, colour, glyph, s)
        val left = pad + 2 * r + pad
        val right = w - pad
        val size = h * 0.46f
        val small = h * 0.19f
        val base = h * 0.50f
        text(c, value, left, base, size, s.condensed, WHITE)
        val after = left + measure(value, size, s.condensed) + h * 0.04f
        text(c, unit, after, base, small, s.condensed, DIM)
        if (note.isNotEmpty()) text(c, note, right, base, small * 1.25f, s.condensed, colour, Paint.Align.RIGHT)

        val top = h * 0.62f
        val box = RectF(left, top, right, top + h * 0.24f)
        val line = h * 0.026f
        val mark = if (ticks.size > 4) line * 0.6f else line
        paint.style = Paint.Style.FILL
        paint.color = CHIP
        c.drawRect(box, paint)
        paint.color = colour
        c.drawRect(box.left, box.top, box.left + box.width() * fill, box.bottom, paint)
        paint.color = INK
        for (t in ticks) {
            val x = box.left + box.width() * t
            c.drawRect(x - mark / 2, box.top, x + mark / 2, box.bottom, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = line
        c.drawRect(box, paint)
        paint.style = Paint.Style.FILL
    }

    private fun speed(c: Canvas, w: Float, h: Float, v: FieldValues, s: FieldStyle) {
        val num = one(v.speed)
        val unit = speedUnit(v)
        var size = h * 0.78f
        val small = h * 0.19f
        val gap = h * 0.05f
        val unitW = measure(unit, small, s.condensed)
        val room = w - h * 0.16f
        val numW = measure(num, size, s.condensed)
        if (numW + gap + unitW > room) size *= room / (numW + gap + unitW)
        val x = (w - (measure(num, size, s.condensed) + gap + unitW)) / 2
        val base = h * 0.5f + size * 0.36f
        text(c, num, x, base, size, s.condensed, WHITE)
        text(c, unit, x + measure(num, size, s.condensed) + gap, base, small, s.condensed, DIM)
    }

    /** One line of [counters]: a number with a round badge that holds a short [word], or a [glyph]. */
    private class Counter(val value: String, val colour: Int, val word: String, val glyph: Glyph = Glyph.NONE)

    /**
     * Two counters, one above the other, like the game's two money counters. [sign] puts a round
     * badge on the left that says what the two numbers are about.
     */
    private fun counters(c: Canvas, w: Float, h: Float, s: FieldStyle, signColour: Int, sign: Glyph, rows: List<Counter>) {
        val pad = h * 0.09f
        val r = h * 0.17f
        val size = h * 0.34f
        var floor = pad * 0.9f
        if (sign != Glyph.NONE) {
            val lr = h * 0.2f
            badge(c, pad + lr, h * 0.5f, lr, signColour, sign, s)
            floor = pad + 2 * lr + pad * 0.5f
        }
        rows.forEachIndexed { i, row ->
            val cy = h * (0.28f + 0.44f * i)
            val cx = w - pad - r
            badge(c, cx, cy, r, row.colour, row.glyph, s, row.word)
            var fit = size
            val room = cx - r - pad * 0.7f - floor
            val wide = measure(row.value, fit, s.chunky)
            if (wide > room) fit *= room / wide
            text(c, row.value, cx - r - pad * 0.7f, cy + fit * 0.36f, fit, s.chunky, WHITE, Paint.Align.RIGHT)
        }
    }

    private fun cash(c: Canvas, w: Float, h: Float, v: FieldValues, s: FieldStyle) = counters(c, w, h, s, 0, Glyph.NONE, listOf(
        Counter(one(v.distance), PURPLE, distanceWord(v)),
        Counter(v.climb?.let { thousands(it.roundToInt()) } ?: "--", GREEN, "", Glyph.PEAK),
    ))

    private fun time(c: Canvas, w: Float, h: Float, v: FieldValues, s: FieldStyle) {
        val pad = h * 0.10f
        val small = h * 0.20f
        // The time of day in a box, with its label, like the game's "LAP 1/2".
        val clock = v.clock.ifEmpty { "--:--" }
        val boxW = measure(clock, small * 1.1f, s.condensed) + small * 0.9f
        val box = RectF(w - pad - boxW, h * 0.10f, w - pad, h * 0.10f + small * 1.5f)
        paint.style = Paint.Style.FILL
        paint.color = CHIP
        c.drawRect(box, paint)
        text(c, clock, box.centerX(), box.centerY() + small * 0.40f, small * 1.1f, s.condensed, WHITE, Paint.Align.CENTER)
        text(c, "CLOCK", box.left - small * 0.45f, box.centerY() + small * 0.36f, small, s.condensed, WHITE, Paint.Align.RIGHT)

        val t = v.rideSeconds
        val ride = when {
            t == null -> "--:--"
            t >= 3600 -> String.format(Locale.US, "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60)
            else -> String.format(Locale.US, "%02d:%02d", t / 60, t % 60)
        }
        var size = h * 0.56f
        val room = w - 2 * pad
        val wide = measure(ride, size, s.condensed)
        if (wide > room) size *= room / wide
        text(c, ride, w - pad, h * 0.93f, size, s.condensed, WHITE, Paint.Align.RIGHT)
    }

    // ---- the fields of the GTA profile (approved as mockups 27.11.2026: previews/mockups) ------

    /** The row above the map: speed large on the left; the stars above watts and cadence on the right. */
    private fun hud(c: Canvas, w: Float, cellH: Float, v: FieldValues, s: FieldStyle) {
        // Drawn for a row about 3.8 times as wide as tall. A taller cell keeps that row in its middle.
        val h = min(cellH, w / 3.79f)
        c.save()
        c.translate(0f, (cellH - h) / 2)
        val pad = h * 0.11f
        val small = h * 0.19f
        val left = w * 0.445f
        val right = w - pad

        val num = one(v.speed)
        val unit = speedUnit(v)
        val gap = h * 0.04f
        var size = h * 0.74f
        val room = left - pad - h * 0.06f - gap - measure(unit, small, s.condensed)
        val wide = measure(num, size, s.condensed)
        if (wide > room) size *= room / wide
        val base = h * 0.5f + size * 0.36f
        text(c, num, pad, base, size, s.condensed, WHITE)
        text(c, unit, pad + measure(num, size, s.condensed) + gap, base, small, s.condensed, DIM)
        stats(c, left, right, h, v, s)
        c.restore()
    }

    /**
     * The stars above watts and cadence, between [left] and [right] of a row [h] tall. "W" and
     * "RPM" are written only where there is room for them beside numbers of full size: the
     * badges say the same.
     */
    private fun stats(c: Canvas, left: Float, right: Float, h: Float, v: FieldValues, s: FieldStyle) {
        val (lit, maxed) = wantedLevel(v)
        val r = min(h * 0.155f, (right - left) / 11.4f)
        starRow(c, left + (right - left - starsWidth(r)) / 2, h * 0.27f, r, lit, maxed && v.blink)

        // Watts from the left edge, cadence up to the right edge.
        val cy = h * 0.725f
        val br = h * 0.14f
        val us = h * 0.17f
        val g1 = h * 0.05f
        val g2 = h * 0.03f
        val watts = number(v.power)
        val turns = number(v.cadence)
        val wattW = measure("W", us, s.condensed)
        val rpmW = measure("RPM", us, s.condensed)
        var ns = h * 0.35f
        val bare = 4 * br + 2 * g1 + h * 0.06f
        val numbers = measure(watts, ns, s.condensed) + measure(turns, ns, s.condensed)
        val units = bare + 2 * g2 + wattW + rpmW + numbers <= right - left
        if (!units && bare + numbers > right - left) ns *= (right - left - bare) / numbers
        val nb = cy + ns * 0.36f
        badge(c, left + br, cy, br, TEAL, Glyph.BOLT, s)
        val x = left + 2 * br + g1
        text(c, watts, x, nb, ns, s.condensed, WHITE)
        if (units) {
            text(c, "W", x + measure(watts, ns, s.condensed) + g2, nb, us, s.condensed, DIM)
            text(c, "RPM", right, nb, us, s.condensed, DIM, Paint.Align.RIGHT)
        }
        val end = if (units) right - rpmW - g2 else right
        text(c, turns, end, nb, ns, s.condensed, WHITE, Paint.Align.RIGHT)
        badge(c, end - measure(turns, ns, s.condensed) - g1 - br, cy, br, PURPLE, Glyph.SPIN, s)
    }

    /** The top line of the two big fields: a label, then distance and climb as two counters. */
    private fun heading(c: Canvas, w: Float, u: Float, v: FieldValues, s: FieldStyle, label: String, distance: String, climb: String) {
        val cy = 40 * u
        val r = 17 * u
        val size = 31 * u
        text(c, label, 16 * u, cy + 9 * u, 26 * u, s.condensed, DIM)
        var x = w - 14 * u - r
        badge(c, x, cy, r, GREEN, Glyph.PEAK, s)
        x -= r + 7 * u
        text(c, climb, x, cy + size * 0.36f, size, s.chunky, WHITE, Paint.Align.RIGHT)
        x -= measure(climb, size, s.chunky) + 14 * u + r
        badge(c, x, cy, r, PURPLE, Glyph.NONE, s, distanceWord(v))
        x -= r + 7 * u
        text(c, distance, x, cy + size * 0.36f, size, s.chunky, WHITE, Paint.Align.RIGHT)
        rect(c, 0f, 78 * u, w, 80 * u, INK)
    }

    /** The route's elevation: ridden part dim, the rest pink, climbs yellow, the places ahead as icons. */
    private fun profile(c: Canvas, w: Float, h: Float, v: FieldValues, s: FieldStyle) {
        val route = v.route ?: return words(c, w, h / 2, w, h, s, "NO ROUTE")
        val u = w / 478f
        val len = route.length
        val at = (v.routeAt ?: 0.0).coerceIn(0.0, len)
        // A cell too low for the heading and the icons gets the line alone.
        val tall = h >= 330 * u
        // On a climb the cell shows the climb instead of the whole route.
        if (tall && route.hasHeights && len > 0) route.climbAt(at)?.let { return climbView(c, w, h, v, s, route, it, at) }
        if (tall) heading(c, w, u, v, s, "TO FINISH", far(len - at, v), if (route.hasHeights) up(route.climb(at, len), v) else "--")
        val l = 52 * u
        val r = w - 14 * u
        val t = if (tall) 182 * u else 14 * u
        val b = h - (if (tall) 50 else 30) * u
        if (!route.hasHeights || len <= 0) return words(c, w, (t + b) / 2, w, b - t, s, "NO ELEVATION")

        val k = if (v.feet) 3.28084 else 1.0
        val (low, high, step) = heightScale(route.lowest * k, route.highest * k)
        fun x(d: Double) = (l + (r - l) * d / len).toFloat()
        fun yOf(shown: Double) = (b - (b - t) * (shown - low) / (high - low)).toFloat()
        fun y(d: Double) = yOf(route.heightAt(d) * k)

        var line = ceil(low / step) * step
        while (line < high) {
            rect(c, l, yOf(line) - u, r, yOf(line) + u, CHIP)
            text(c, line.roundToInt().toString(), l - 8 * u, yOf(line) + 6 * u, 19 * u, s.condensed, DIM, Paint.Align.RIGHT)
            line += step
        }

        val pace = len * 2.0 / (r - l).coerceAtLeast(1f) * u          // a point every two pixels
        fun surface(from: Double, to: Double) = Path().apply {
            moveTo(x(from), y(from))
            var d = from + pace
            while (d < to) {
                lineTo(x(d), y(d))
                d += pace
            }
            lineTo(x(to), y(to))
        }
        fun fill(from: Double, to: Double) {
            if (to <= from) return
            c.drawPath(surface(from, to).apply { lineTo(x(to), b); lineTo(x(from), b); close() }, paint)
        }
        fun ridge(from: Double, to: Double, colour: Int, width: Float) {
            if (to <= from) return
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = width
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = colour
            c.drawPath(surface(from, to), paint)
            paint.strokeCap = Paint.Cap.BUTT
            paint.style = Paint.Style.FILL
        }
        paint.style = Paint.Style.FILL
        paint.color = CHIP
        fill(0.0, at)
        ridge(0.0, at, RIDDEN, 3 * u)
        paint.color = WHITE
        paint.shader = LinearGradient(0f, t, 0f, b, 0x9EF27DB6.toInt(), 0x29F27DB6, Shader.TileMode.CLAMP)
        fill(at, len)
        paint.shader = null
        ridge(at, len, PINK, 3.5f * u)
        for (climb in route.climbs) ridge(max(climb.start, at), min(climb.end, len), YELLOW, 4.5f * u)
        rect(c, l, b, r, b + 2 * u, INK)

        val shown = len / if (v.imperial) 1609.344 else 1000.0
        val every = niceStep(shown, 6, doubleArrayOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0))
        var mark = 0.0
        while (mark <= shown + 1e-6) {
            text(c, mark.roundToInt().toString(), x(len * mark / shown), b + 27 * u, 19 * u, s.condensed, DIM, Paint.Align.CENTER)
            mark += every
        }
        text(c, distanceWord(v), l - 8 * u, b + 27 * u, 19 * u, s.condensed, DIM, Paint.Align.RIGHT)

        if (tall) {
            // The places ahead: icons in two rows, the second for an icon that would touch the one before.
            val size = 30 * u
            val rows = floatArrayOf(94 * u, 128 * u)
            val last = floatArrayOf(-1e6f, -1e6f)
            v.ahead.forEachIndexed { i, place ->
                if (place.at <= at || place.at > len) return@forEachIndexed
                val px = x(place.at)
                val row = if (px - last[0] >= size + 2 * u) 0 else if (px - last[1] >= size + 2 * u) 1 else return@forEachIndexed
                last[row] = px
                rect(c, px - u, rows[row] + size - 2 * u, px + u, y(place.at) - 3 * u, if (i == 0) WHITE else 0x8CA3A6C2.toInt())
                icon(c, s, place.icon, px - size / 2, rows[row], size)
            }
        }
        val px = x(at)
        val py = y(at)
        rect(c, px - 1.5f * u, py, px + 1.5f * u, b, WHITE)
        arrow(c, px, py - 2 * u, 13 * u, u)
    }

    /** The steps of a climb's steepness in per cent, in our own colours.  */
    private fun steep(percent: Double) = when {
        percent < 3 -> TEAL
        percent < 5 -> GREEN
        percent < 7 -> YELLOW
        percent < 9 -> ORANGE
        percent < 12 -> PINK
        else -> RED
    }

    /** A colour seen through: [alpha] 1 is solid. */
    private fun faded(colour: Int, alpha: Float) = (colour and 0x00FFFFFF) or ((255 * alpha).roundToInt() shl 24)

    /**
     * The climb the rider is on: how far and how much to the top, then the climb cut in pieces,
     * each in the colour of its steepness with the per cent under it. The ridden part is dim.
     */
    private fun climbView(c: Canvas, w: Float, h: Float, v: FieldValues, s: FieldStyle, route: Route, index: Int, at: Double) {
        val u = w / 478f
        val from = route.climbs[index].start
        val to = min(route.climbs[index].end, route.length)
        if (to - from < 1) return
        val here = at.coerceIn(from, to)
        heading(c, w, u, v, s, "TO TOP", far(to - here, v), up(route.climb(here, to), v))

        // "CLIMB 2/3" in a box, like the game's lap counter.
        text(c, "CLIMB", 16 * u, 113 * u, 24 * u, s.condensed, WHITE)
        val count = "${index + 1}/${route.climbs.size}"
        val boxL = 16 * u + measure("CLIMB", 24 * u, s.condensed) + 8 * u
        val boxW = measure(count, 25 * u, s.condensed) + 16 * u
        rect(c, boxL, 92 * u, boxL + boxW, 120 * u, CHIP)
        text(c, count, boxL + boxW / 2, 114 * u, 25 * u, s.condensed, WHITE, Paint.Align.CENTER)
        val average = (route.heightAt(to) - route.heightAt(from)) / (to - from) * 100
        text(c, "AVG ${one(average)} %", w - 16 * u, 113 * u, 24 * u, s.condensed, DIM, Paint.Align.RIGHT)

        val l = 14 * u
        val r = w - 14 * u
        val t = 150 * u
        val b = h - 70 * u
        val pace = (to - from) / 200
        var lowest = Double.MAX_VALUE
        var highest = -Double.MAX_VALUE
        for (i in 0..200) {
            val e = route.heightAt(from + i * pace)
            lowest = min(lowest, e)
            highest = max(highest, e)
        }
        val span = max(highest - lowest, 10.0)
        val low = lowest - span * 0.08
        val high = highest + span * 0.03
        fun x(d: Double) = (l + (r - l) * (d - from) / (to - from)).toFloat()
        fun y(d: Double) = (b - (b - t) * (route.heightAt(d) - low) / (high - low)).toFloat()
        fun shape(a: Double, e: Double) = Path().apply {
            moveTo(x(a), b)
            var d = a
            while (d < e) {
                lineTo(x(d), y(d))
                d += pace
            }
            lineTo(x(e), y(e))
            lineTo(x(e), b)
            close()
        }

        val parts = ((to - from) / 1000).roundToInt().coerceIn(5, 9)
        val each = (to - from) / parts
        for (i in 0 until parts) {
            val a = from + i * each
            val e = a + each
            val percent = (route.heightAt(e) - route.heightAt(a)) / each * 100
            val colour = steep(percent)
            val done = e <= here + 1e-6
            paint.style = Paint.Style.FILL
            paint.color = if (done) faded(colour, 0.28f) else colour
            c.drawPath(shape(a, e), paint)
            if (!done && a < here) {
                paint.color = faded(PANEL, 0.72f)
                c.drawPath(shape(a, here), paint)
            }
            rect(c, x(a) - u, y(a), x(a) + u, b, PANEL)
            rect(c, x(a) + u, b + 8 * u, x(e) - u, b + 54 * u, if (done) faded(colour, 0.4f) else colour)
            val label = one(percent)
            var size = 29 * u
            val room = x(e) - x(a) - 8 * u
            val wide = measure(label, size, s.condensed)
            if (wide > room && wide > 0) size *= room / wide
            text(c, label, (x(a) + x(e)) / 2, b + 31 * u + size * 0.36f, size, s.condensed, if (done) DIM else INK, Paint.Align.CENTER)
        }
        val px = x(here)
        rect(c, px - 1.5f * u, t - 8 * u, px + 1.5f * u, b, WHITE)
        arrow(c, px, y(here) - 2 * u, 14 * u, u)
    }

    /** The first place ahead, for the two cells under the elevation. */
    private fun next(v: FieldValues) = v.ahead.firstOrNull { it.at > (v.routeAt ?: 0.0) }

    /** How far to the next place: its icon and name above, the distance as a counter below. */
    private fun nextPlace(c: Canvas, w: Float, h: Float, v: FieldValues, s: FieldStyle) {
        if (v.route == null) return words(c, w, h / 2, w, h, s, "NO ROUTE")
        val place = next(v)
        val u = min(w / 238f, h / 148f)
        val top = (h - 148 * u) / 2
        val pad = 13 * u
        if (place != null) icon(c, s, place.icon, pad, top + 14 * u, 44 * u)
        text(c, "NEXT", pad + 54 * u, top + 33 * u, 19 * u, s.condensed, DIM)
        fitted(c, place?.name ?: "NOTHING AHEAD", pad + 54 * u, top + 57 * u, 27 * u, w - 2 * pad - 54 * u, s.condensed, WHITE)
        counter(c, w, top + 103 * u, u, s, place?.let { far(it.at - (v.routeAt ?: 0.0), v) } ?: "--", PURPLE, Glyph.NONE, distanceWord(v))
    }

    /** How much climbing to the next place. */
    private fun nextClimb(c: Canvas, w: Float, h: Float, v: FieldValues, s: FieldStyle) {
        val route = v.route ?: return words(c, w, h / 2, w, h, s, "NO ROUTE")
        val place = next(v)
        val u = min(w / 238f, h / 148f)
        val top = (h - 148 * u) / 2
        val pad = 13 * u
        text(c, "CLIMB", pad, top + 33 * u, 19 * u, s.condensed, DIM)
        text(c, "TO NEXT", pad, top + 57 * u, 27 * u, s.condensed, WHITE)
        val value = if (place == null || !route.hasHeights) "--" else up(route.climb(v.routeAt ?: 0.0, place.at), v)
        counter(c, w, top + 103 * u, u, s, value, GREEN, Glyph.PEAK, "")
    }

    /** One large counter at the right of a cell 238 wide: the number, then its round badge. */
    private fun counter(c: Canvas, w: Float, cy: Float, u: Float, s: FieldStyle, value: String, colour: Int, glyph: Glyph, word: String) {
        val r = 23 * u
        val cx = w - 13 * u - r
        badge(c, cx, cy, r, colour, glyph, s, word)
        var size = 50 * u
        val room = cx - r - 9 * u - 13 * u
        val wide = measure(value, size, s.chunky)
        if (wide > room) size *= room / wide
        text(c, value, cx - r - 9 * u, cy + size * 0.36f, size, s.chunky, WHITE, Paint.Align.RIGHT)
    }

    /** The places ahead as a list: icon, what it is, how far and how much climbing. For a cell that fills the page. */
    private fun placeList(c: Canvas, w: Float, h: Float, v: FieldValues, s: FieldStyle) {
        val u = w / 478f
        val route = v.route
        val at = v.routeAt ?: 0.0
        rect(c, 0f, 0f, w, 5 * u, PINK)
        text(c, "UP AHEAD", 16 * u, 50 * u, 36 * u, s.condensed, WHITE)
        val top = 68 * u
        if (route == null) return words(c, w, (top + h) / 2, w, h - top, s, "NO ROUTE")
        text(c, "FINISH ${far(route.length - at, v)} ${distanceWord(v)}", w - 16 * u, 48 * u, 22 * u, s.condensed, DIM, Paint.Align.RIGHT)
        val rows = ((h - top) / (72 * u)).toInt().coerceIn(1, 12)
        val rowH = (h - top) / rows
        val places = v.ahead.filter { it.at > at }
        for (i in 0 until rows) {
            val y = top + i * rowH
            val cy = y + rowH / 2
            val place = places.getOrNull(i)
            if (i == 0 && place != null) {
                rect(c, 0f, y, w, y + rowH, 0xFF1D1C3C.toInt())
                rect(c, 0f, y, 6 * u, y + rowH, PINK)
            }
            rect(c, 0f, y - u, w, y + u, INK)
            if (place == null) continue
            icon(c, s, place.icon, 18 * u, cy - 26 * u, 52 * u)
            fitted(c, place.name, 84 * u, cy + u, 30 * u, 172 * u, s.condensed, WHITE)
            text(c, aside(place, v), 84 * u, cy + 25 * u, 19 * u, s.condensed, DIM)
            val r = 15 * u
            var x = w - 14 * u - r
            badge(c, x, cy, r, GREEN, Glyph.PEAK, s)
            x -= r + 6 * u
            text(c, if (route.hasHeights) up(route.climb(at, place.at), v) else "--", x, cy + 9 * u, 25 * u, s.chunky, WHITE, Paint.Align.RIGHT)
            x = w - 124 * u
            badge(c, x, cy, r, PURPLE, Glyph.NONE, s, distanceWord(v))
            x -= r + 6 * u
            text(c, far(place.at - at, v), x, cy + 11 * u, 30 * u, s.chunky, WHITE, Paint.Align.RIGHT)
        }
        if (places.isEmpty()) words(c, w, top + rowH / 2, w, rowH, s, "NOTHING AHEAD")
    }

    /** The small line under a place's name: whose place it is, or how far off the route. */
    private fun aside(place: RoutePlace, v: FieldValues) = when {
        place.own -> "FROM YOUR ROUTE"
        place.off < Along.ON_ROUTE -> "ON ROUTE"
        v.imperial -> "${(place.off * 3.28084 / 50).roundToInt() * 50} FT OFF ROUTE"
        else -> "${(place.off / 10).roundToInt() * 10} M OFF ROUTE"
    }

    // ---- the parts -----------------------------------------------------------------------

    enum class Glyph { NONE, HEART, BOLT, SPIN, PEAK, SPEEDO }

    private fun measure(s: String, size: Float, face: Typeface): Float {
        paint.typeface = face
        paint.textSize = size
        return paint.measureText(s)
    }

    private fun text(c: Canvas, s: String, x: Float, base: Float, size: Float, face: Typeface, colour: Int,
                     align: Paint.Align = Paint.Align.LEFT) {
        paint.style = Paint.Style.FILL
        paint.typeface = face
        paint.textSize = size
        paint.textAlign = align
        paint.color = colour
        c.drawText(s, x, base, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun rect(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, colour: Int) {
        if (x1 <= x0 || y1 <= y0) return
        paint.style = Paint.Style.FILL
        paint.color = colour
        c.drawRect(x0, y0, x1, y1, paint)
    }

    /** Text from [x], made smaller if it is wider than [room]. */
    private fun fitted(c: Canvas, s: String, x: Float, base: Float, size: Float, room: Float, face: Typeface, colour: Int) {
        val wide = measure(s, size, face)
        text(c, s, x, base, if (wide > room && wide > 0) size * room / wide else size, face, colour)
    }

    /** A few quiet words in the middle of a space: there is nothing to show. */
    private fun words(c: Canvas, w: Float, cy: Float, roomW: Float, roomH: Float, s: FieldStyle, words: String) {
        var size = min(roomH * 0.26f, w * 0.075f)
        val wide = measure(words, size, s.condensed)
        if (wide > roomW * 0.9f) size *= roomW * 0.9f / wide
        text(c, words, w / 2, cy + size * 0.36f, size, s.condensed, DIM, Paint.Align.CENTER)
    }

    /** One of the map icons, [size] wide and high, its top left corner at [x], [y]. */
    private fun icon(c: Canvas, s: FieldStyle, name: String, x: Float, y: Float, size: Float) {
        val drawable = s.icon(name) ?: return
        drawable.setBounds(x.roundToInt(), y.roundToInt(), (x + size).roundToInt(), (y + size).roundToInt())
        drawable.draw(c)
    }

    /** The rider: the map's yellow arrow, pointing along the route. */
    private fun arrow(c: Canvas, cx: Float, cy: Float, size: Float, u: Float) {
        val path = Path().apply {
            moveTo(cx + size, cy)
            lineTo(cx - size * 0.8f, cy - size * 0.78f)
            lineTo(cx - size * 0.35f, cy)
            lineTo(cx - size * 0.8f, cy + size * 0.78f)
            close()
        }
        paint.style = Paint.Style.FILL
        paint.color = ARROW
        c.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * u
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = INK
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    /** A five-pointed star, fat like a wanted star, with a dark edge. */
    private fun star(c: Canvas, cx: Float, cy: Float, r: Float, fill: Int) {
        val path = Path()
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) r else r * 0.5f
            val angle = Math.toRadians(-90.0 + i * 36.0)
            val x = cx + radius * cos(angle).toFloat()
            val y = cy + radius * sin(angle).toFloat() + r * 0.06f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = fill
        c.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.16f
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = INK
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    /** A round dark badge with a coloured sign or a short word in it. */
    private fun badge(c: Canvas, cx: Float, cy: Float, r: Float, colour: Int, glyph: Glyph, s: FieldStyle, word: String = "") {
        paint.style = Paint.Style.FILL
        paint.color = CHIP
        c.drawCircle(cx, cy, r, paint)
        paint.color = colour
        val u = r * 0.56f                              // the sign is drawn on a square from -1 to 1
        fun p(x: Float, y: Float) = Pair(cx + x * u, cy + y * u)
        val path = Path()
        when (glyph) {
            Glyph.HEART -> {
                val (x0, y0) = p(0f, 0.85f)
                path.moveTo(x0, y0)
                path.cubicTo(cx - 1.35f * u, cy - 0.05f * u, cx - 0.62f * u, cy - 1.12f * u, cx, cy - 0.38f * u)
                path.cubicTo(cx + 0.62f * u, cy - 1.12f * u, cx + 1.35f * u, cy - 0.05f * u, x0, y0)
                path.close()
                c.drawPath(path, paint)
            }
            Glyph.BOLT -> {
                val pts = listOf(0.25f to -1f, -0.6f to 0.14f, -0.04f to 0.14f, -0.25f to 1f, 0.6f to -0.14f, 0.04f to -0.14f)
                pts.forEachIndexed { i, (x, y) -> val (px, py) = p(x, y); if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
                path.close()
                c.drawPath(path, paint)
            }
            Glyph.SPIN -> {
                // Most of a ring with an arrow head: turning pedals.
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = u * 0.34f
                paint.strokeCap = Paint.Cap.ROUND
                val ring = u * 0.72f
                c.drawArc(RectF(cx - ring, cy - ring, cx + ring, cy + ring), 50f, 250f, false, paint)
                paint.strokeCap = Paint.Cap.BUTT
                paint.style = Paint.Style.FILL
                val a = Math.toRadians(300.0)
                val px = cx + ring * cos(a).toFloat()
                val py = cy + ring * sin(a).toFloat()
                val tx = -sin(a).toFloat()
                val ty = cos(a).toFloat()
                val rx = cos(a).toFloat()
                val ry = sin(a).toFloat()
                path.moveTo(px + tx * u * 0.75f, py + ty * u * 0.75f)
                path.lineTo(px + rx * u * 0.5f, py + ry * u * 0.5f)
                path.lineTo(px - rx * u * 0.5f, py - ry * u * 0.5f)
                path.close()
                c.drawPath(path, paint)
            }
            Glyph.PEAK -> {
                val pts = listOf(-1f to 0.7f, -0.15f to -0.75f, 0.2f to -0.15f, 0.45f to -0.5f, 1f to 0.7f)
                pts.forEachIndexed { i, (x, y) -> val (px, py) = p(x, y); if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
                path.close()
                c.drawPath(path, paint)
            }
            Glyph.SPEEDO -> {
                // A dial open at the bottom, with its needle up and to the right.
                val oy = cy + u * 0.22f
                val ring = u * 0.84f
                val a = Math.toRadians(308.0)
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = u * 0.32f
                c.drawArc(RectF(cx - ring, oy - ring, cx + ring, oy + ring), 162f, 216f, false, paint)
                paint.strokeWidth = u * 0.28f
                c.drawLine(cx, oy, cx + ring * 0.62f * cos(a).toFloat(), oy + ring * 0.62f * sin(a).toFloat(), paint)
                paint.strokeCap = Paint.Cap.BUTT
                paint.style = Paint.Style.FILL
                c.drawCircle(cx, oy, u * 0.2f, paint)
            }
            Glyph.NONE -> text(c, word, cx, cy + r * 0.30f, r * 0.86f, s.condensed, colour, Paint.Align.CENTER)
        }
    }
}
