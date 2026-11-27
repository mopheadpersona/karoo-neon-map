package local.karoo.gtavimap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.RemoteViews
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.UserProfile
import io.hammerhead.karooext.models.ViewConfig
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * The latest ride values, for the fields. A value is only asked of the Karoo while a field that
 * shows it is on screen. Everything here runs on the main thread.
 */
class Hub(private val karoo: KarooSystemService) {
    private val main = Handler(Looper.getMainLooper())
    private val live = HashMap<String, Double?>()
    private val users = HashMap<String, Int>()
    private val consumers = HashMap<String, String>()
    private val clock = SimpleDateFormat("HH:mm", Locale.US)

    /** Pretend values set from the desk (GtaExtension, TEST_FIELDS), in the Karoo's own units. */
    val test = HashMap<String, Double>()

    /** The rider's zones and units, as the Karoo tells them. */
    var profile: UserProfile? = null

    /** The route being followed and the places along it, or null. Set by the extension. */
    var nav: Nav? = null
        set(value) {
            field = value
            lastAt = Double.NaN
            atTime = 0
        }

    /** A made-up route set from the desk (GtaExtension, TEST_ROUTE): shown instead of the real one. */
    var testNav: Nav? = null

    /** Where the rider is, as latitude and longitude. */
    var position: DoubleArray? = null

    private var lastAt = Double.NaN
    private var atTime = 0L

    /**
     * How far along the route the rider is, in metres: the rider's position, looked up on the
     * route's line. Without a position, the Karoo's own "distance to destination" is used; it
     * cannot come first, because it reads 0 while no ride is recording (seen 27.11.2026: the
     * rider stood at the start of a test route and the fields showed the finish).
     */
    private fun routeAt(nav: Nav): Double? {
        test[TEST_ROUTE_AT]?.let { return it.coerceIn(0.0, nav.route.length) }
        if (nav === testNav) return SampleRoute.AT
        val now = SystemClock.elapsedRealtime()
        if (now - atTime < 400 && !lastAt.isNaN()) return lastAt       // several fields ask in the same second
        val p = position
        val spot = if (p == null) null else {
            // First near where the rider was a moment ago, so a route that crosses itself does not make the position jump.
            val near = if (lastAt.isNaN()) null else nav.route.locate(p[0], p[1], lastAt - 500, lastAt + 3000)?.takeIf { it.off < 150 }
            near ?: nav.route.locate(p[0], p[1])
        }
        val left = get(DataType.Type.DISTANCE_TO_DESTINATION)
        if (lastAt.isNaN()) {
            Timber.i("%s: on the route at %s m, %s m beside it; the Karoo says %s m to go", GtaExtension.TAG,
                spot?.at?.toInt(), spot?.off?.toInt(), left?.toInt())
        }
        val at = spot?.at ?: left?.takeIf { it >= 0 && it <= nav.route.length * 1.02 }?.let { nav.route.length - it } ?: return null
        lastAt = at.coerceIn(0.0, nav.route.length)
        atTime = now
        return lastAt
    }

    fun acquire(type: String) {
        val before = users[type] ?: 0
        users[type] = before + 1
        if (before > 0) return
        consumers[type] = karoo.addConsumer(OnStreamState.StartStreaming(type)) { event: OnStreamState ->
            main.post { live[type] = (event.state as? StreamState.Streaming)?.dataPoint?.singleValue }
        }
    }

    fun release(type: String) {
        val left = (users[type] ?: 0) - 1
        if (left > 0) {
            users[type] = left
            return
        }
        users.remove(type)
        live.remove(type)
        consumers.remove(type)?.let { karoo.removeConsumer(it) }
    }

    private fun get(type: String) = test[type] ?: live[type]

    /** The Karoo gives speed in metres per second, distances in metres and times in milliseconds. */
    fun values(): FieldValues {
        val units = profile?.preferredUnit
        val imperial = units?.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL
        val feet = units?.elevation == UserProfile.PreferredUnit.UnitType.IMPERIAL
        val perSecond = if (imperial) 2.236936 else 3.6
        val nav = testNav ?: this.nav
        val at = nav?.let { routeAt(it) }
        return FieldValues(
            hr = get(DataType.Type.HEART_RATE),
            hrZones = profile?.heartRateZones?.map { it.min..it.max } ?: emptyList(),
            power = get(DataType.Type.SMOOTHED_3S_AVERAGE_POWER),
            powerZones = profile?.powerZones?.map { it.min..it.max } ?: emptyList(),
            cadence = get(DataType.Type.CADENCE),
            speed = get(DataType.Type.SPEED)?.let { it * perSecond },
            distance = get(DataType.Type.DISTANCE)?.let { it / if (imperial) 1609.344 else 1000.0 },
            climb = get(DataType.Type.ELEVATION_GAIN)?.let { it * if (feet) 3.28084 else 1.0 },
            rideSeconds = get(DataType.Type.ELAPSED_TIME)?.let { (it / 1000).toLong() },
            clock = clock.format(Date()),
            imperial = imperial,
            feet = feet,
            blink = SystemClock.elapsedRealtime() / 1000 % 2 == 0L,
            avgSpeed = get(DataType.Type.AVERAGE_SPEED)?.let { it * perSecond },
            maxSpeed = get(DataType.Type.MAX_SPEED)?.let { it * perSecond },
            avgHr = get(DataType.Type.AVERAGE_HR),
            maxHr = get(DataType.Type.MAX_HR),
            avgPower = get(DataType.Type.AVERAGE_POWER),
            np = get(DataType.Type.NORMALIZED_POWER),
            route = nav?.route,
            routeAt = at,
            // Until the rider's place on the route is known, the list starts at the route's start.
            ahead = if (nav != null) Along.ahead(nav.places, at ?: 0.0, LIST_ROWS) else emptyList(),
        )
    }

    companion object {
        /** In [test]: metres along the route, to pretend the rider is somewhere else on it. */
        const val TEST_ROUTE_AT = "routeAt"

        /** The list of places ahead shows this many at most. */
        const val LIST_ROWS = 7
    }
}

/** The route being followed, with every place along it in the order the rider meets them. */
class Nav(val route: Route, val places: List<RoutePlace>)

/** One of our fields, as the Karoo sees it: asked for a picture when a data page shows it. */
class GtaField(private val owner: GtaExtension, private val kind: FieldKind) :
    DataTypeImpl("gtavimap", kind.typeId) {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val view = FieldView(context, kind, config, emitter, owner.hub, owner.prefs)
        view.start()
        emitter.setCancellable { view.stop() }
    }
}

/** A field that is on screen: draws itself once a second, which is as often as the Karoo takes a picture. */
private class FieldView(
    private val context: Context,
    private val kind: FieldKind,
    private val config: ViewConfig,
    private val emitter: ViewEmitter,
    private val hub: Hub,
    private val prefs: Prefs,
) {
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var bitmap = picture(MAX_PIXELS)
    private var refused = 0
    private var previews = 0

    /** A picture the size of the cell, or the same shape with fewer pixels if the cell has more than [limit]. */
    private fun picture(limit: Float): Bitmap {
        var w = config.viewSize.first.takeIf { it > 0 } ?: 240
        var h = config.viewSize.second.takeIf { it > 0 } ?: 125
        val scale = sqrt(limit / (w.toFloat() * h)).coerceAtMost(1f)
        w = (w * scale).toInt().coerceAtLeast(1)
        h = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                draw()
            } catch (e: Exception) {
                Timber.w(e, "%s: field %s could not be drawn", GtaExtension.TAG, kind.typeId)
                // The picture travels to the ride app each second. If a big cell's picture is
                // not taken twice in a row, carry on with a smaller one, stretched to the cell.
                if (++refused == 2 && bitmap.width * bitmap.height > SAFE_PIXELS) {
                    bitmap = picture(SAFE_PIXELS)
                    Timber.w("%s: field %s now drawn at %d x %d", GtaExtension.TAG, kind.typeId, bitmap.width, bitmap.height)
                }
            }
            // In the Karoo's field picker and page editors the values are made up and never
            // change: a few pictures, then the screen is left in peace.
            if (config.preview && ++previews >= PREVIEW_PICTURES) return
            main.postDelayed(this, 1000)
        }
    }

    fun start() = main.post {
        running = true
        kind.streams.forEach(hub::acquire)
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        Timber.i("%s: field %s shown, %d x %d, grid %s, drawn at %d x %d", GtaExtension.TAG, kind.typeId,
            config.viewSize.first, config.viewSize.second, config.gridSize, bitmap.width, bitmap.height)
        tick.run()
    }

    fun stop() = main.post {
        if (!running) return@post
        running = false
        main.removeCallbacks(tick)
        kind.streams.forEach(hub::release)
    }

    private fun draw() {
        val values = if (config.preview) FieldValues.SAMPLE else hub.values()
        val style = FieldStyle.of(context, prefs.condensedFont, prefs.chunkyFont)
        FieldArt.draw(kind, Canvas(bitmap), bitmap.width, bitmap.height, values, style)
        val views = RemoteViews(context.packageName, R.layout.field)
        views.setImageViewBitmap(R.id.field_image, bitmap)
        emitter.updateView(views)
        refused = 0
    }

    companion object {
        /** A cell that fills the page is 478 x 639: drawn pixel for pixel, so its small text stays sharp. */
        private const val MAX_PIXELS = 330_000f

        /** A preview is sent this many times, a second apart, in case the first is not taken. */
        private const val PREVIEW_PICTURES = 3

        /** The size every picture was kept under up to version 0.3, and the one known to arrive. */
        private const val SAFE_PIXELS = 150_000f
    }
}
