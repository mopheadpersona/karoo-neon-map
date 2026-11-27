package local.karoo.gtavimap

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The extension's one screen: its switches, a way back to Hammerhead's map, and what it sees. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var themes: ThemeSwitcher
    private lateinit var zoomButton: Button
    private lateinit var themeButton: Button
    private lateinit var iconsButton: Button
    private lateinit var sizeButton: Button
    private lateinit var numbersButton: Button
    private lateinit var cashButton: Button
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        themes = ThemeSwitcher(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.parseColor("#0F1536"))
        }
        fun text(size: Float, colour: String) = TextView(this).apply {
            textSize = size
            setTextColor(Color.parseColor(colour))
            setPadding(0, 12, 0, 12)
        }
        fun button(onTap: () -> Unit) = Button(this).apply {
            textSize = 18f
            isAllCaps = false
            setOnClickListener { onTap(); refresh() }
        }

        column.addView(text(26f, "#F06DB5").apply { text = "GTA VI map"; gravity = Gravity.CENTER })
        zoomButton = button { prefs.autoZoom = !prefs.autoZoom }
        column.addView(zoomButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        themeButton = button {
            val modes = ThemeMode.entries
            prefs.themeMode = modes[(modes.indexOf(prefs.themeMode) + 1) % modes.size]
        }
        column.addView(themeButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        iconsButton = button {
            prefs.mapIcons = !prefs.mapIcons
            Status.settingsChanged?.invoke()
        }
        column.addView(iconsButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        sizeButton = button {
            val sizes = IconSize.entries
            prefs.iconSize = sizes[(sizes.indexOf(prefs.iconSize) + 1) % sizes.size]
            Status.settingsChanged?.invoke()
        }
        column.addView(sizeButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        numbersButton = button {
            val fonts = CondensedFont.entries
            prefs.condensedFont = fonts[(fonts.indexOf(prefs.condensedFont) + 1) % fonts.size]
        }
        column.addView(numbersButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        cashButton = button {
            val fonts = ChunkyFont.entries
            prefs.chunkyFont = fonts[(fonts.indexOf(prefs.chunkyFont) + 1) % fonts.size]
        }
        column.addView(cashButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(button {
            prefs.themeMode = ThemeMode.OFF
            Status.note = if (themes.restoreStock()) "Hammerhead's map is back. It shows when the Ride screen next opens."
            else "No backup of Hammerhead's map was found."
        }.apply { text = "Put Hammerhead's map back" }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        status = text(16f, "#F2F4FA")
        column.addView(status)

        setContentView(ScrollView(this).apply { addView(column) }, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        main.post(refresher)
    }

    override fun onPause() {
        main.removeCallbacks(refresher)
        super.onPause()
    }

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 1000)
        }
    }

    private fun refresh() {
        zoomButton.text = "Zoom by speed: " + if (prefs.autoZoom) "on" else "off"
        themeButton.text = "Map theme: " + prefs.themeMode.label
        iconsButton.text = "Map icons: " + if (prefs.mapIcons) "on" else "off"
        sizeButton.text = "Icon size: " + prefs.iconSize.label
        numbersButton.text = "Field numbers: " + prefs.condensedFont.label
        cashButton.text = "Field cash numbers: " + prefs.chunkyFont.label
        val storage = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val onStorage = when (themes.current()) {
            ThemeSwitcher.Kind.DAY -> "GTA VI day"
            ThemeSwitcher.Kind.NIGHT -> "GTA VI night"
            ThemeSwitcher.Kind.STOCK -> "Hammerhead's"
            ThemeSwitcher.Kind.OTHER -> "another theme"
            ThemeSwitcher.Kind.MISSING -> "none"
        }
        status.text = listOf(
            "Karoo system: " + if (Status.connected) "connected (library ${Status.karooLib ?: "?"})" else "not connected",
            "Storage access: " + if (storage) "yes" else "NO: the theme can't be changed",
            "Karoo Dark Mode: " + if (themes.karooIsDark()) "on" else "off",
            "Theme on the storage: $onStorage",
            "Ride: " + if (Status.rideActive) "active" else "not started",
            "Speed: " + (Status.speedKmh?.let { "%.0f km/h".format(it) } ?: "none"),
            "Map zoom: " + (Status.zoom?.let { "%.1f".format(it) } ?: "unknown") +
                (Status.targetZoom?.let { ", aiming for $it" } ?: ""),
            "Map page showing: " + when (Status.mapPage) { true -> "yes"; false -> "no"; null -> "unknown" },
            "Position: " + (Status.position ?: "none yet"),
            "Map icons: ${Status.iconsShown} shown, from a list of " + (Status.placeCount?.let { "%,d places".format(it) } ?: "none") +
                if (Status.mapsOpen == 0) " (no map open)" else "",
            Status.iconNote,
            "",
            "Slow is close, fast is far: under 8 km/h zoom 17, to 18 km/h 16, to 28 km/h 15, to 40 km/h 14, above 13.",
            "If you zoom by hand, the map is left alone for a minute.",
            "Icons: gas stations, supermarkets, shelters and stations from the 2 km scale; water, food, cafes, small shops and bike shops from 500 m; toilets from 200 m.",
            "",
            Status.note,
        ).joinToString("\n")
    }
}
