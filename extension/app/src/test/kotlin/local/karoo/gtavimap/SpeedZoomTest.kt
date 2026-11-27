package local.karoo.gtavimap

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedZoomTest {
    @Test
    fun `each speed band has its zoom`() {
        assertEquals(17, SpeedZoom().onSpeed(0.0))
        assertEquals(16, SpeedZoom().onSpeed(12.0))
        assertEquals(15, SpeedZoom().onSpeed(23.0))
        assertEquals(14, SpeedZoom().onSpeed(35.0))
        assertEquals(13, SpeedZoom().onSpeed(55.0))
    }

    @Test
    fun `a band is only left when the speed is clearly past its edge`() {
        val zoom = SpeedZoom()
        assertEquals(17, zoom.onSpeed(3.0))
        assertEquals("just over 8 km/h is not enough", 17, zoom.onSpeed(9.0))
        assertEquals(16, zoom.onSpeed(10.5))
        assertEquals("just under 8 km/h is not enough to go back", 16, zoom.onSpeed(7.0))
        assertEquals(17, zoom.onSpeed(5.5))
    }

    @Test
    fun `riding right on an edge does not flip the map`() {
        val zoom = SpeedZoom()
        zoom.onSpeed(20.0)
        val seen = listOf(18.5, 17.6, 18.9, 17.2, 19.4, 16.8, 18.1).map { zoom.onSpeed(it) }.toSet()
        assertEquals(setOf(15), seen)
    }

    @Test
    fun `a sudden stop goes straight to the closest zoom`() {
        val zoom = SpeedZoom()
        assertEquals(13, zoom.onSpeed(48.0))
        assertEquals(17, zoom.onSpeed(0.0))
    }

    @Test
    fun `nonsense speeds are treated as standing still`() {
        assertEquals(17, SpeedZoom().onSpeed(-4.0))
    }
}
