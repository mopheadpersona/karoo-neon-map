package local.karoo.gtavimap

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import timber.log.Timber

/**
 * Shows every field with made-up values, drawn by the Karoo with the same code as on a ride.
 * For looking at fonts and layout without touching a ride profile:
 *
 *   am start -n local.karoo.gtavimap/.FieldPreviewActivity --es condensed bebas --es chunky russo
 *
 * The cells are 240 or 480 px wide and 125 px tall, the size of a row of fields on the Karoo 2.
 * With `--es page 1` (or 1b, 2, 3, 3c, 4) it shows one page of the GTA profile instead, each field in
 * the size of its cell on that page, to compare with the mockups (previews/mockups). 3c is page
 * 3 later on the made-up ride, part of the way up its big climb.
 */
class FieldPreviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val condensed = CondensedFont.entries.firstOrNull { it.name.equals(intent.getStringExtra("condensed"), true) }
            ?: prefs.condensedFont
        val chunky = ChunkyFont.entries.firstOrNull { it.name.equals(intent.getStringExtra("chunky"), true) }
            ?: prefs.chunkyFont
        val style = FieldStyle.of(this, condensed, chunky)
        val hr = intent.getFloatExtra("hr", Float.NaN)
        val sample = FieldValues.SAMPLE
        var values = if (hr.isNaN()) sample else sample.copy(hr = hr.toDouble(), blink = intent.getBooleanExtra("blink", false))
        val page = PAGES[intent.getStringExtra("page")]
        if (intent.getStringExtra("page") == "3c") {
            values = values.copy(routeAt = ON_THE_CLIMB, ahead = Along.ahead(SampleRoute.places, ON_THE_CLIMB, Hub.LIST_ROWS))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        if (page == null) column.addView(TextView(this).apply {
            text = "Numbers: ${condensed.label}\nCash: ${chunky.label}"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(12, 8, 12, 8)
        })
        val rows = page ?: listOf(
            Row(HEIGHT, FieldKind.SPEED, FieldKind.WANTED),
            Row(HEIGHT, FieldKind.HEALTH),
            Row(HEIGHT, FieldKind.STAMINA, FieldKind.FOCUS),
            Row(HEIGHT, FieldKind.CASH, FieldKind.TIME),
            Row(HEIGHT, FieldKind.WANTED),
        )
        for (line in rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val gap = 2
            val cellW = (WIDTH - gap * (line.kinds.size - 1)) / line.kinds.size
            line.kinds.forEachIndexed { i, kind ->
                val bitmap = Bitmap.createBitmap(cellW, line.height, Bitmap.Config.ARGB_8888)
                // A field that cannot be drawn stays black: a crash here would take the extension down with it.
                try {
                    FieldArt.draw(kind, Canvas(bitmap), cellW, line.height, values, style)
                } catch (e: Exception) {
                    Timber.w(e, "%s: preview of %s could not be drawn", GtaExtension.TAG, kind.typeId)
                }
                row.addView(ImageView(this).apply { setImageBitmap(bitmap) },
                    LinearLayout.LayoutParams(cellW, line.height).apply { if (i > 0) leftMargin = gap })
            }
            column.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 2 })
        }
        setContentView(column)
    }

    /** One row of cells, all [height] pixels tall, sharing the width of the screen. */
    private class Row(val height: Int, vararg val kinds: FieldKind)

    companion object {
        private const val WIDTH = 480
        private const val HEIGHT = 125
        private const val ON_THE_CLIMB = 69_400.0

        /** The pages of the GTA profile, with the cell heights measured on the Karoo 2. */
        private val PAGES = mapOf(
            "1" to listOf(Row(125, FieldKind.SPEED, FieldKind.HUD_SIDE)),      // two fields: the Karoo's thin row
            "1b" to listOf(Row(191, FieldKind.HUD)),                           // one field: the Karoo makes its row taller
            "2" to listOf(
                Row(126, FieldKind.SPEED_BAR),
                Row(126, FieldKind.STAMINA, FieldKind.HEALTH),
                Row(126, FieldKind.FOCUS, FieldKind.TIME),
                Row(126, FieldKind.SPEED_PAIR, FieldKind.HR_PAIR),
                Row(126, FieldKind.POWER_PAIR, FieldKind.CASH),
            ),
            "3" to listOf(Row(457, FieldKind.PROFILE), Row(148, FieldKind.NEXT_PLACE, FieldKind.NEXT_CLIMB)),
            "3c" to listOf(Row(457, FieldKind.PROFILE), Row(148, FieldKind.NEXT_PLACE, FieldKind.NEXT_CLIMB)),
            "4" to listOf(Row(639, FieldKind.PLACES)),
        )
    }
}
