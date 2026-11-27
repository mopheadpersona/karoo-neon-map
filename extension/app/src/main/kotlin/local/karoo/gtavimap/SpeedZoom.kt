package local.karoo.gtavimap

/**
 * Which map zoom goes with which speed.
 *
 * Slow means close (zoom 17 is where the Karoo's map shows the 3D houses), fast means far.
 * A band is only left once the speed is clearly past its edge, so the map doesn't flip back
 * and forth while riding at 18 km/h.
 */
class SpeedZoom(
    private val bands: List<Band> = DEFAULT_BANDS,
    private val marginKmh: Double = 2.0,
) {
    /** Speeds below [belowKmh] get [zoom]. The list goes from slow to fast; the last band is open-ended. */
    data class Band(val belowKmh: Double, val zoom: Int)

    /** The zoom the map should have now, or null before the first speed came in. */
    var target: Int? = null
        private set

    private fun zoomFor(kmh: Double): Int = bands.first { kmh < it.belowKmh }.zoom

    /** Takes a speed, returns the zoom to aim for. */
    fun onSpeed(kmh: Double): Int {
        val speed = kmh.coerceAtLeast(0.0)
        val current = target
        if (current == null) {
            return zoomFor(speed).also { target = it }
        }
        // Faster speed, smaller zoom number. Keep the current zoom while it is still a fair
        // answer for a speed anywhere within the margin.
        val closest = zoomFor((speed - marginKmh).coerceAtLeast(0.0))
        val farthest = zoomFor(speed + marginKmh)
        if (current in farthest..closest) return current
        return zoomFor(speed).also { target = it }
    }

    fun reset() {
        target = null
    }

    companion object {
        val DEFAULT_BANDS = listOf(
            Band(8.0, 17),                  // walking pace, traffic lights: houses in 3D
            Band(18.0, 16),                 // slow riding: streets
            Band(28.0, 15),                 // cruising
            Band(40.0, 14),                 // fast
            Band(Double.MAX_VALUE, 13),     // descents
        )
    }
}
