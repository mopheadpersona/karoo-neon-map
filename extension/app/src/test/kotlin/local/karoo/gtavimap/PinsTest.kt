package local.karoo.gtavimap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The standing icons need the colour overlay to carry a picture for each kind's POI type. */
class PinsTest {
    private val overlay = File("../../overlay")

    @Test
    fun everyKindHasItsPictureInTheOverlay() {
        for (kind in Kind.entries) {
            val file = File(overlay, "res/drawable-anydpi-v24/ic_poi_${Pins.type(kind)}.xml")
            assertTrue("no overlay picture for $kind: $file", file.isFile)
        }
    }

    @Test
    fun kindsWithTheSameIconShareAType() {
        // The overlay has one picture per type, so two kinds on one type must look the same.
        for (a in Kind.entries) for (b in Kind.entries) {
            if (Pins.type(a) == Pins.type(b)) assertEquals("$a and $b", a.icon, b.icon)
        }
    }

    @Test
    fun theOverlayIsNewEnough() {
        val manifest = File(overlay, "AndroidManifest.xml").readText()
        val version = Regex("""android:versionCode="(\d+)"""").find(manifest)!!.groupValues[1].toInt()
        assertTrue("overlay version $version", version >= Pins.OVERLAY_VERSION)
    }
}
