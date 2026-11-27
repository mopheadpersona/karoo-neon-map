package local.karoo.gtavimap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnMapZoomLevel
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import io.hammerhead.karooext.models.ZoomPage
import timber.log.Timber
import kotlin.math.abs

/** What the extension last saw, for its settings screen. Same process, so a plain object will do. */
object Status {
    @Volatile var connected = false
    @Volatile var karooLib: String? = null
    @Volatile var speedKmh: Double? = null
    @Volatile var zoom: Double? = null
    @Volatile var targetZoom: Int? = null
    @Volatile var rideActive = false
    @Volatile var mapPage: Boolean? = null
    @Volatile var note = ""

    // Map icons
    @Volatile var placeCount: Int? = null
    @Volatile var iconsShown = 0
    @Volatile var mapsOpen = 0
    @Volatile var position: String? = null
    @Volatile var iconNote = ""

    /** The settings screen calls this after a switch changed, so the service acts on it at once. */
    @Volatile var settingsChanged: (() -> Unit)? = null
}

/**
 * The Karoo starts this service because the manifest marks it as a Karoo extension.
 *
 * It does three things:
 *  1. While a ride is recording and the map page is showing, it zooms the map to suit the speed.
 *  2. It keeps the ride app's map theme file as the GTA VI day or night theme, by the Karoo's
 *     Dark Mode or as set on the settings screen.
 *  3. It puts icons for shelters, shops, gas stations and the like on the map ([MapIcons]).
 *  4. It offers its own data fields, drawn in the look of the game's displays ([FieldArt]).
 *  5. It keeps the route being followed, and the places along it, for the fields about the
 *     route ([Route], [Along]).
 */
class GtaExtension : KarooExtension("gtavimap", "0.4") {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var karoo: KarooSystemService
    lateinit var prefs: Prefs
        private set
    lateinit var hub: Hub
        private set
    private lateinit var themes: ThemeSwitcher
    private lateinit var icons: MapIcons
    private val speedZoom = SpeedZoom()

    private var zoom: Double? = null
    private var rideRecording = false
    private var rideActive = false
    private var mapPage: Boolean? = null
    private var liveSpeedKmh: Double? = null

    private var lastStepAt = 0L             // when a zoom step was last asked for
    private var lastStepIn = false
    private var lastStepFromDiff = 0.0
    private var settledFor: Int? = null     // stepping stopped for this target: one more step would overshoot
    private var stepsWithoutEffect = 0
    private var holdUntil = 0L              // the rider zoomed by hand: leave the map alone until then

    private var testSpeedKmh: Double? = null
    private var testSpeedUntil = 0L
    private var testPlace = false           // a pretend position is set: ignore the real one
    private var ticks = 0

    private var navState: OnNavigationState.NavigationState = OnNavigationState.NavigationState.Idle
    private var navKey = ""                 // which route hub.nav was worked out for
    private var navJob = 0

    override fun onCreate() {
        super.onCreate()
        if (Timber.treeCount == 0) Timber.plant(Timber.DebugTree())
        prefs = Prefs(this)
        themes = ThemeSwitcher(this)
        icons = MapIcons(this, prefs)
        icons.onPlaces = { navKey = ""; onNavigation(navState) }
        icons.start()
        Status.settingsChanged = { main.post { icons.settingsChanged() } }

        karoo = KarooSystemService(this)
        hub = Hub(karoo)
        karoo.connect { connected ->
            main.post {
                Status.connected = connected
                Status.karooLib = karoo.libVersion
                Timber.i("%s: Karoo system connected=%s, its library is %s", TAG, connected, karoo.libVersion)
            }
        }
        karoo.addConsumer<RideState> { state ->
            main.post {
                rideRecording = state is RideState.Recording
                rideActive = state !is RideState.Idle
                Status.rideActive = rideActive
                if (!rideRecording) speedZoom.reset()
                Timber.i("%s: ride state %s", TAG, state)
            }
        }
        karoo.addConsumer<OnMapZoomLevel> { event ->
            main.post {
                onZoom(event.zoomLevel)
                icons.onZoom(event.zoomLevel)
            }
        }
        karoo.addConsumer<UserProfile> { profile ->
            main.post {
                hub.profile = profile
                Timber.i("%s: rider has %d heart-rate zones and %d power zones", TAG, profile.heartRateZones.size, profile.powerZones.size)
            }
        }
        karoo.addConsumer<OnLocationChanged> { event ->
            main.post { if (!testPlace) onPlace(event.lat, event.lng) }
        }
        karoo.addConsumer<OnNavigationState> { event ->
            main.post { onNavigation(event.state) }
        }
        karoo.addConsumer<ActiveRidePage> { event ->
            main.post {
                mapPage = event.page.mapPage
                Status.mapPage = mapPage
            }
        }
        karoo.addConsumer(OnStreamState.StartStreaming(DataType.Type.SMOOTHED_3S_AVERAGE_SPEED)) { event: OnStreamState ->
            main.post {
                // The Karoo gives speed in metres per second.
                liveSpeedKmh = (event.state as? StreamState.Streaming)?.dataPoint?.singleValue?.times(3.6)
            }
        }

        contentResolver.registerContentObserver(Settings.Secure.getUriFor("ui_night_mode"), false, darkModeObserver)
        val tests = IntentFilter(ACTION_TEST_SPEED).apply {
            addAction(ACTION_TEST_PLACE)
            addAction(ACTION_TEST_ICONS)
            addAction(ACTION_TEST_FIELDS)
            addAction(ACTION_TEST_ROUTE)
        }
        ContextCompat.registerReceiver(this, testReceiver, tests, ContextCompat.RECEIVER_EXPORTED)
        applyTheme("start")
        main.postDelayed(tick, TICK_MS)
        Timber.i("%s: started", TAG)
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        contentResolver.unregisterContentObserver(darkModeObserver)
        unregisterReceiver(testReceiver)
        Status.settingsChanged = null
        karoo.disconnect()
        super.onDestroy()
    }

    /** Our data fields. The Karoo asks one for its picture when a data page shows it. */
    override val types by lazy { FieldKind.entries.map { GtaField(this, it) } }

    /** The ride app opened a map and asks for our layer: the place icons. */
    override fun startMap(emitter: Emitter<MapEffect>) {
        main.post { icons.attach(emitter) }
        emitter.setCancellable { main.post { icons.detach(emitter) } }
    }

    private fun onPlace(lat: Double, lon: Double) {
        Status.position = "%.4f, %.4f".format(lat, lon)
        hub.position = doubleArrayOf(lat, lon)
        icons.onLocation(lat, lon)
    }

    /**
     * The Karoo says what is being followed: a route, the way to a pin, or nothing. The line,
     * its heights and the places along it are worked out once per route, off the main thread.
     */
    private fun onNavigation(state: OnNavigationState.NavigationState) {
        navState = state
        val key = when (state) {
            is OnNavigationState.NavigationState.NavigatingRoute ->
                "route ${state.routeDistance} ${state.routePolyline.hashCode()} ${state.routeElevationPolyline?.length} ${state.pois.size} ${state.climbs.size}"
            is OnNavigationState.NavigationState.NavigatingToDestination ->
                "pin ${state.polyline.hashCode()} ${state.elevationPolyline?.length} ${state.climbs.size}"
            else -> ""
        }
        if (key == navKey && (key.isEmpty() || hub.nav != null)) return
        navKey = key
        val job = ++navJob
        if (key.isEmpty()) {
            hub.nav = null
            Timber.i("%s: no route", TAG)
            return
        }
        val list = icons.places
        Thread {
            try {
                val nav = readNavigation(state, list)
                main.post { if (job == navJob) hub.nav = nav }
            } catch (e: Exception) {
                Timber.w(e, "%s: the route could not be read", TAG)
            }
        }.start()
    }

    private fun readNavigation(state: OnNavigationState.NavigationState, list: Places?): Nav? {
        val own = ArrayList<RoutePlace>()
        val route: Route
        when (state) {
            is OnNavigationState.NavigationState.NavigatingRoute -> {
                route = Route(
                    Polyline.decode(state.routePolyline, 5), state.routeDistance,
                    state.routeElevationPolyline?.let { Polyline.decode(it, 1) } ?: emptyList(),
                    state.climbs.map { RouteClimb(it.startDistance, it.startDistance + it.length) })
                for (poi in state.pois) {
                    // The Karoo says where along the route a saved place is; without that, the nearest point of the line.
                    val spots = poi.distancesAlongRoute.ifEmpty {
                        listOfNotNull(route.locate(poi.lat, poi.lng)?.takeIf { it.off <= 1000 }?.at)
                    }
                    val name = (poi.name?.takeIf { it.isNotBlank() } ?: poi.type.replace('_', ' ')).uppercase()
                    for (at in spots) own.add(RoutePlace("pin", name, "own", at, 0.0, true))
                }
                Timber.i("%s: route \"%s\", %.1f km, %d points, heights %s, %d climbs, %d saved places", TAG, state.name,
                    route.length / 1000, route.points, heightsNote(route), route.climbs.size, own.size)
            }
            is OnNavigationState.NavigationState.NavigatingToDestination -> {
                route = Route(
                    Polyline.decode(state.polyline, 5), null,
                    state.elevationPolyline?.let { Polyline.decode(it, 1) } ?: emptyList(),
                    state.climbs.map { RouteClimb(it.startDistance, it.startDistance + it.length) })
                val name = (state.destination.name?.takeIf { it.isNotBlank() } ?: "DESTINATION").uppercase()
                own.add(RoutePlace("pin", name, "own", route.length, 0.0, true))
                Timber.i("%s: way to a pin, %.1f km, %d points, heights %s, %d climbs", TAG,
                    route.length / 1000, route.points, heightsNote(route), route.climbs.size)
            }
            else -> return null
        }
        val found = if (list == null) emptyList() else Along.find(route, list)
        Timber.i("%s: %d places of the list along the route", TAG, found.size)
        return Nav(route, (own + found).sortedBy { it.at })
    }

    private fun heightsNote(route: Route) =
        if (route.hasHeights) "%.0f to %.0f m".format(route.lowest, route.highest) else "none"

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme("display mode changed")
    }

    private val darkModeObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = applyTheme("Dark Mode changed")
    }

    /**
     * For trying things on a desk, over adb:
     *   am broadcast -a local.karoo.gtavimap.TEST_SPEED --ef kmh 30        a pretend speed
     *   am broadcast -a local.karoo.gtavimap.TEST_PLACE --ef lat 50.08 --ef lon 14.42
     *                                                    a pretend position; no numbers: the real one again
     *   am broadcast -a local.karoo.gtavimap.TEST_ICONS --es size l        icon size s, m, l or x
     *   am broadcast -a local.karoo.gtavimap.TEST_FIELDS --ef hr 152 --ef power 230 --ef cadence 88
     *        --ef kmh 27.4 --ef km 42.5 --ef climb 1479 --ef minutes 83.75
     *        --ef avgkmh 30.4 --ef maxkmh 61.8 --ef avghr 148 --ef maxhr 181 --ef avgpower 187 --ef np 204
     *        --ef routekm 12.5                           (how far along the route the rider pretends to be)
     *                                                    pretend values for the data fields; no numbers: the real ones again
     *   am broadcast -a local.karoo.gtavimap.TEST_ROUTE --ez on true       a made-up route with places along it;
     *                                                    --ez on false: the real route again
     */
    private val testReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TEST_FIELDS -> {
                    hub.test.clear()
                    fun take(extra: String, type: String, factor: Double = 1.0) {
                        val value = intent.getFloatExtra(extra, Float.NaN)
                        if (!value.isNaN()) hub.test[type] = value * factor
                    }
                    take("hr", DataType.Type.HEART_RATE)
                    take("power", DataType.Type.SMOOTHED_3S_AVERAGE_POWER)
                    take("cadence", DataType.Type.CADENCE)
                    take("kmh", DataType.Type.SPEED, 1 / 3.6)
                    take("km", DataType.Type.DISTANCE, 1000.0)
                    take("climb", DataType.Type.ELEVATION_GAIN)
                    take("minutes", DataType.Type.ELAPSED_TIME, 60_000.0)
                    take("avgkmh", DataType.Type.AVERAGE_SPEED, 1 / 3.6)
                    take("maxkmh", DataType.Type.MAX_SPEED, 1 / 3.6)
                    take("avghr", DataType.Type.AVERAGE_HR)
                    take("maxhr", DataType.Type.MAX_HR)
                    take("avgpower", DataType.Type.AVERAGE_POWER)
                    take("np", DataType.Type.NORMALIZED_POWER)
                    take("routekm", Hub.TEST_ROUTE_AT, 1000.0)
                    Timber.i("%s: test values for the fields: %s", TAG, hub.test)
                    return
                }
                ACTION_TEST_ROUTE -> {
                    hub.testNav = if (intent.getBooleanExtra("on", false)) Nav(SampleRoute.route, SampleRoute.places) else null
                    Timber.i("%s: made-up route %s", TAG, if (hub.testNav != null) "on" else "off")
                    return
                }
                ACTION_TEST_PLACE -> {
                    val lat = intent.getFloatExtra("lat", Float.NaN).toDouble()
                    val lon = intent.getFloatExtra("lon", Float.NaN).toDouble()
                    testPlace = !lat.isNaN() && !lon.isNaN()
                    if (testPlace) onPlace(lat, lon)
                    Timber.i("%s: test place %s", TAG, if (testPlace) "$lat, $lon" else "off")
                    return
                }
                ACTION_TEST_ICONS -> {
                    val raw = intent.getStringExtra("raw")
                    if (raw != null) {
                        // --es raw show|hide|auto --es id a1 --ef lat .. --ef lon .. --es icon food --es size m
                        icons.manual = raw != "auto"
                        if (raw == "auto") icons.settingsChanged() else icons.raw(
                            raw, intent.getStringExtra("id") ?: "a1",
                            intent.getFloatExtra("lat", 0f).toDouble(), intent.getFloatExtra("lon", 0f).toDouble(),
                            intent.getStringExtra("icon") ?: "food",
                            IconSize.entries.firstOrNull { it.suffix == intent.getStringExtra("size") } ?: IconSize.M)
                        return
                    }
                    IconSize.entries.firstOrNull { it.suffix == intent.getStringExtra("size") }?.let { prefs.iconSize = it }
                    Timber.i("%s: icon size %s", TAG, prefs.iconSize.label)
                    icons.settingsChanged()
                    return
                }
            }
            val kmh = intent.getFloatExtra("kmh", Float.NaN).toDouble()
            if (kmh.isNaN() || kmh < 0) {
                testSpeedKmh = null
            } else {
                testSpeedKmh = kmh
                testSpeedUntil = SystemClock.elapsedRealtime() + TEST_SPEED_MS
                holdUntil = 0                   // a desk test does not wait out a zoom by hand
            }
            Timber.i("%s: test speed %s km/h", TAG, testSpeedKmh)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                zoomBySpeed()
                if (++ticks % 5 == 0) applyTheme("check")
            } catch (e: Exception) {
                Timber.w(e, "%s: tick failed", TAG)
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun onZoom(level: Double) {
        val before = zoom
        zoom = level
        Status.zoom = level
        if (before == null || abs(level - before) < 0.05) return
        stepsWithoutEffect = 0
        val now = SystemClock.elapsedRealtime()
        if (now - lastStepAt > OWN_STEP_WINDOW_MS && abs(level - before) > 0.25) {
            // Nobody asked for this from here: the rider pinched, or the Karoo zoomed by itself.
            holdUntil = now + MANUAL_HOLD_MS
            settledFor = null
            Timber.i("%s: zoom changed by hand to %.1f, pausing for %d s", TAG, level, MANUAL_HOLD_MS / 1000)
        }
    }

    private fun zoomBySpeed() {
        val now = SystemClock.elapsedRealtime()
        val testSpeed = testSpeedKmh?.takeIf { now < testSpeedUntil }
        val speed = testSpeed ?: liveSpeedKmh
        Status.speedKmh = speed

        if (!prefs.autoZoom) return
        if (testSpeed == null && !rideRecording) return
        if (mapPage == false) return
        if (now < holdUntil) return
        val level = zoom ?: return
        val target = speedZoom.onSpeed(speed ?: return)
        Status.targetZoom = target
        if (settledFor != null && settledFor != target) settledFor = null

        val diff = target - level
        if (abs(diff) < CLOSE_ENOUGH || settledFor == target) return
        if (now - lastStepAt < STEP_GAP_MS) return
        if (stepsWithoutEffect >= 4) {
            // The visible page doesn't answer to zoom. Try again later.
            holdUntil = now + 30_000
            stepsWithoutEffect = 0
            return
        }
        val zoomIn = diff > 0
        if (now - lastStepAt < 4 * STEP_GAP_MS && zoomIn != lastStepIn && abs(diff) >= abs(lastStepFromDiff)) {
            // The last step went past the target and this one would only go back: stay here.
            settledFor = target
            return
        }
        if (karoo.dispatch(ZoomPage(zoomIn))) {
            lastStepAt = now
            lastStepIn = zoomIn
            lastStepFromDiff = diff
            stepsWithoutEffect++
            Timber.d("%s: %.0f km/h, zoom %.1f -> %d, step %s", TAG, speed, level, target, if (zoomIn) "in" else "out")
        }
    }

    private fun applyTheme(why: String) {
        val night = when (prefs.themeMode) {
            ThemeMode.OFF -> return
            ThemeMode.DAY -> false
            ThemeMode.NIGHT -> true
            ThemeMode.FOLLOW -> themes.karooIsDark()
        }
        if (themes.put(night)) {
            Status.note = "Theme set to ${if (night) "night" else "day"} ($why). It shows when the Ride screen next opens."
            Timber.i("%s: %s", TAG, Status.note)
        }
    }

    companion object {
        const val TAG = "GtaViMap"
        const val ACTION_TEST_SPEED = "local.karoo.gtavimap.TEST_SPEED"
        const val ACTION_TEST_PLACE = "local.karoo.gtavimap.TEST_PLACE"
        const val ACTION_TEST_ICONS = "local.karoo.gtavimap.TEST_ICONS"
        const val ACTION_TEST_FIELDS = "local.karoo.gtavimap.TEST_FIELDS"
        const val ACTION_TEST_ROUTE = "local.karoo.gtavimap.TEST_ROUTE"
        private const val TICK_MS = 1000L
        private const val STEP_GAP_MS = 1500L           // at most one zoom step in this time
        private const val OWN_STEP_WINDOW_MS = 2500L    // a zoom change this soon after a step is ours
        private const val MANUAL_HOLD_MS = 60_000L
        private const val TEST_SPEED_MS = 90_000L
        private const val CLOSE_ENOUGH = 0.6
    }
}
