#!/usr/bin/env python3
"""Draws the map icons as SVG files into icons/, and an overview sheet.

    python3 make_icons.py            write icons/*.svg, the sheets, and the extension's drawables
    python3 make_icons.py sheet      also render previews/icons/sheet-*.png with Chrome

The extension places the icons on the Karoo's map itself, so each icon it uses is also written
as an Android vector drawable into extension/app/src/main/res/drawable/, once per size
(poi_<name>_<size>.xml). The ride app draws a drawable at its own size, so the size on the
screen is the size written here.

The tables below are the source: change a table, run the script. Don't edit icons/*.svg by hand.

The look is "inspired by" the GTA VI map legend: a rounded-square
badge with a dark outline, a pale tint per kind and one bold dark glyph. Every glyph is drawn
here from scratch; nothing is copied from the game or from a website.

Only plain SVG is used: rectangles, circles, paths, flat fills, plain strokes. No gradients,
filters, text or transforms, because what the Karoo's SVG reader takes is not known yet.

These are DRAFTS (27.11.2026): drawn on the Mac, not yet seen on the Karoo.
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DARK = "#11132B"          # outline and glyph: a near-black navy 

# Tint per kind .
TINTS = {
    "rest": "#CDB4F6",    # lilac: shelter, camp site, picnic table
    "water": "#A9D3F5",   # blue: drinking water, toilets
    "shop": "#9BE0A0",    # green: groceries, pharmacy, cash, vending machine
    "eat": "#F6DC7A",     # yellow: burger, cup
    "road": "#E3EAF6",    # white-blue: fuel, train
    "bike": "#7FDCCB",    # teal: bike shop, repair stand
    "view": "#F6CFCB",    # pink: viewpoint
    "route": "#F7A8D0",   # the route's pink: a place saved in the route itself
}

# Glyphs on a 32 x 32 grid. D = dark, T = the badge's tint (for cut-outs).
GLYPHS = {
    "shelter": """
<path d="M16 6.5 L27 16 H24 V25 H8 V16 H5 Z" fill="D" stroke="D" stroke-width="1.5" stroke-linejoin="round"/>
<rect x="13.6" y="18" width="4.8" height="7.8" fill="T"/>""",
    "water": """
<path d="M16 5.5 C16 5.5 8.5 14 8.5 19 A7.5 7.5 0 0 0 23.5 19 C23.5 14 16 5.5 16 5.5 Z" fill="D"/>
<path d="M12.2 19.2 A3.9 3.9 0 0 0 15.6 23" stroke="T" stroke-width="1.8" fill="none" stroke-linecap="round"/>""",
    "groceries": """
<path d="M11 14 L15 7.5 M21 14 L17 7.5" stroke="D" stroke-width="2.6" fill="none" stroke-linecap="round"/>
<path d="M5.5 13 H26.5 V15.5 H25.2 L23.5 25 H8.5 L6.8 15.5 H5.5 Z" fill="D" stroke="D" stroke-width="1" stroke-linejoin="round"/>
<path d="M12 17.8 V22.3 M16 17.8 V22.3 M20 17.8 V22.3" stroke="T" stroke-width="1.8" fill="none" stroke-linecap="round"/>""",
    "burger": """
<path d="M6.5 14.5 C6.5 9.5 10.5 7 16 7 C21.5 7 25.5 9.5 25.5 14.5 Z" fill="D"/>
<circle cx="12.4" cy="11.2" r="0.9" fill="T"/>
<circle cx="16" cy="9.8" r="0.9" fill="T"/>
<circle cx="19.6" cy="11.2" r="0.9" fill="T"/>
<rect x="5.5" y="16.3" width="21" height="3.4" rx="1.7" fill="D"/>
<path d="M6.5 21.5 H25.5 V22.5 A3 3 0 0 1 22.5 25.5 H9.5 A3 3 0 0 1 6.5 22.5 Z" fill="D"/>""",
    "fuel": """
<rect x="7.5" y="6.5" width="11.5" height="19" rx="1.8" fill="D"/>
<rect x="10" y="9.3" width="6.5" height="5.2" rx="0.8" fill="T"/>
<rect x="6" y="24" width="14.5" height="2.2" rx="1" fill="D"/>
<path d="M19 15 H20.6 A1.6 1.6 0 0 1 22.2 16.6 V21.4 A1.7 1.7 0 0 0 25.6 21.4 V12.6 L22.6 9.4" stroke="D" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round"/>""",
    "cup": """
<path d="M11.5 5.2 C10.5 6.6 12.5 7.2 11.5 8.6 M16.5 5.2 C15.5 6.6 17.5 7.2 16.5 8.6" stroke="D" stroke-width="1.6" fill="none" stroke-linecap="round"/>
<path d="M7.5 11 H21 V16.5 A6.75 6.75 0 0 1 7.5 16.5 Z" fill="D"/>
<path d="M21 12.6 H22.6 A2.9 2.9 0 0 1 22.6 18.4 H20.4" stroke="D" stroke-width="2.2" fill="none"/>
<rect x="6.5" y="24.6" width="15.5" height="2.2" rx="1.1" fill="D"/>""",
    "bike": """
<circle cx="9.5" cy="20" r="4.6" stroke="D" stroke-width="2.2" fill="none"/>
<circle cx="22.5" cy="20" r="4.6" stroke="D" stroke-width="2.2" fill="none"/>
<path d="M9.5 20 L13.5 11.5 H20 L22.5 20 M13.5 11.5 L17 20 H9.5 M20 11.5 L17 20 M11.5 9.3 H15 M20 11.5 L19.2 8.6 H22" stroke="D" stroke-width="1.9" fill="none" stroke-linecap="round" stroke-linejoin="round"/>""",
    "train": """
<rect x="8.5" y="5.5" width="15" height="17" rx="3.5" fill="D"/>
<rect x="11" y="8.3" width="10" height="6" rx="1.2" fill="T"/>
<circle cx="12.6" cy="18.3" r="1.4" fill="T"/>
<circle cx="19.4" cy="18.3" r="1.4" fill="T"/>
<path d="M11.5 22.5 L8.5 26.5 M20.5 22.5 L23.5 26.5" stroke="D" stroke-width="2.2" fill="none" stroke-linecap="round"/>""",
    "toilets": """
<path d="M4.3 11 L6.8 21 L9.6 13.5 L12.4 21 L14.9 11" stroke="D" stroke-width="2.3" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
<path d="M27 13.6 A4.8 4.8 0 1 0 27 18.4" stroke="D" stroke-width="2.3" fill="none" stroke-linecap="round"/>""",
    "tent": """
<path d="M16 6.5 L27 25 H5 Z" fill="D" stroke="D" stroke-width="1.5" stroke-linejoin="round"/>
<path d="M16 14.5 L20.3 25.8 H11.7 Z" fill="T"/>""",
    "picnic": """
<rect x="6" y="10.5" width="20" height="3" rx="1.2" fill="D"/>
<rect x="3.8" y="17" width="24.4" height="2.8" rx="1.2" fill="D"/>
<path d="M11.5 13 L7.5 25.5 M20.5 13 L24.5 25.5" stroke="D" stroke-width="2.5" fill="none" stroke-linecap="round"/>""",
    "viewpoint": """
<path d="M3.8 25 L12 9.5 L16.6 18 L19.8 13 L28.2 25 Z" fill="D" stroke="D" stroke-width="1.2" stroke-linejoin="round"/>
<path d="M12 9.7 L14.3 14 L12 12.8 L9.8 13.9 Z" fill="T"/>""",
    "pharmacy": """
<path d="M13 6.5 H19 V13 H25.5 V19 H19 V25.5 H13 V19 H6.5 V13 H13 Z" fill="D"/>""",
    "pin": """
<path d="M16 5 A8 8 0 0 1 24 13 C24 18.5 16 27.5 16 27.5 C16 27.5 8 18.5 8 13 A8 8 0 0 1 16 5 Z" fill="D"/>
<circle cx="16" cy="13" r="3" fill="T"/>""",
    "cash": """
<rect x="4.5" y="9.5" width="23" height="13" rx="2" fill="D"/>
<circle cx="16" cy="16" r="3.2" fill="T"/>
<circle cx="8.3" cy="16" r="1.1" fill="T"/>
<circle cx="23.7" cy="16" r="1.1" fill="T"/>""",
    "vending": """
<rect x="8.5" y="5" width="15" height="22" rx="2" fill="D"/>
<rect x="10.8" y="7.5" width="7" height="10.5" rx="0.8" fill="T"/>
<rect x="19.4" y="7.5" width="2" height="4" rx="0.6" fill="T"/>
<rect x="10.8" y="21" width="10.6" height="2.8" rx="0.8" fill="T"/>""",
}

# The icons: file name -> (glyph, tint, name on the sheet, what it marks, where the places come
# from, from which scale it could show). "map" = already in the Karoo's own map files (points
# only, never before the 200 m scale); "list" = needs a place list from OpenStreetMap.
ICONS = {
    # the first five
    "shelter":   ("shelter", "rest", "Shelter", "Lean-tos, huts, weather and picnic shelters. Bus-stop shelters left out.", "list", "far"),
    "water":     ("water", "water", "Drinking water", "Taps and fountains; springs marked drinkable.", "both", "mid"),
    "groceries": ("groceries", "shop", "Groceries", "Supermarkets and small food shops.", "both", "far"),
    "food":      ("burger", "eat", "Food", "Fast food, restaurants, pubs and beer gardens.", "list", "mid"),
    "fuel":      ("fuel", "road", "Gas station", "Open when the shops are shut. Unmanned pumps left out where marked.", "list", "far"),
    # suggested additions
    "coffee":    ("cup", "eat", "Cafe and bakery", "Coffee and something sweet. Bakeries open on Sunday mornings.", "both", "mid"),
    "bike":      ("bike", "bike", "Bike shop, repair stand", "Shops, public pumps and tool stands.", "both", "mid"),
    "train":     ("train", "road", "Train station", "The way home when the weather or the bike gives up.", "list", "far"),
    "toilets":   ("toilets", "water", "Toilets", "Public toilets.", "both", "close"),
    # could come later
    "camp":      ("tent", "rest", "Camp site", "For overnight trips.", "both", "mid"),
    "picnic":    ("picnic", "rest", "Picnic table", "A place to sit down and eat.", "both", "close"),
    "viewpoint": ("viewpoint", "view", "Viewpoint", "Worth a stop.", "both", "close"),
    "vending":   ("vending", "shop", "Vending machine", "Food, drinks and bike-tube machines: open 24 hours.", "list", "close"),
    "pharmacy":  ("pharmacy", "shop", "Pharmacy", "", "both", "close"),
    "cash":      ("cash", "shop", "Cash machine", "For the hut that takes cash only.", "both", "close"),
    # not on the map: the data fields draw it for a place saved in the route itself
    "pin":       ("pin", "route", "Place from your route", "A place you saved in the route.", "list", "far"),
}

GROUPS = [
    ("Your five", "The ones you asked for.", ["shelter", "water", "groceries", "food", "fuel"]),
    ("I would add these four", "Few, and each one answers a real question on a ride.", ["coffee", "bike", "train", "toilets"]),
    ("Could come later", "Useful sometimes. Each one makes the map a little busier.", ["camp", "picnic", "viewpoint", "vending", "pharmacy", "cash"]),
]

SOURCE = {
    "map": "Already in the Karoo's map",
    "both": "In the Karoo's map (close zooms, points only); complete with a place list",
    "list": "Not in the Karoo's map: needs a place list",
}
RANGE = {
    "far": "From far: the 2 km scale",
    "mid": "Middle: from the 500 m scale",
    "close": "Close: from the 200 m scale",
}

# The icons the extension places on the map, and the sizes it offers (settings screen, "Icon
# size"). Sizes are in dp; the Karoo 2 has 1.875 pixels per dp, so 24 dp is 45 px on its screen.
EXTENSION_ICONS = ["shelter", "water", "groceries", "food", "fuel", "coffee", "bike", "train", "toilets"]
# Icons only the data fields draw (FieldArt.kt): written once, in the size the fields load.
FIELD_ICONS = ["pin"]
SIZES_DP = {"s": 20, "m": 24, "l": 28, "x": 32}
DRAWABLES = os.path.join(HERE, "extension", "app", "src", "main", "res", "drawable")

# Ground colours of the two themes (make_style.py: LAND, TOWN), to check that a badge reads on all.
GROUNDS = [("Day, land", "#6D9985"), ("Day, town", "#9EA5AC"), ("Night, land", "#215C5F"), ("Night, town", "#0F1536")]


def svg(name, size=None):
    glyph, tint = ICONS[name][0], TINTS[ICONS[name][1]]
    body = GLYPHS[glyph].strip().replace('"D"', '"%s"' % DARK).replace('"T"', '"%s"' % tint)
    wh = ' width="%d" height="%d"' % (size, size) if size else ' width="38" height="38"'
    return ('<svg xmlns="http://www.w3.org/2000/svg"%s viewBox="-3 -3 38 38">\n'
            '<rect x="-1.6" y="-1.6" width="35.2" height="35.2" rx="8.5" fill="%s" stroke="%s" stroke-width="2.8"/>\n'
            '%s\n</svg>\n' % (wh, tint, DARK, body))


def num(v):
    return ("%.3f" % v).rstrip("0").rstrip(".")


def path_of(el):
    """SVG rect, circle or path as Android path data."""
    tag = el.tag.split("}")[-1]
    g = lambda k, d=0.0: float(el.get(k, d))
    if tag == "path":
        return el.get("d")
    if tag == "circle":
        cx, cy, r = g("cx"), g("cy"), g("r")
        return "M%s,%s a%s,%s 0 1,0 %s,0 a%s,%s 0 1,0 %s,0 z" % (
            num(cx - r), num(cy), num(r), num(r), num(2 * r), num(r), num(r), num(-2 * r))
    if tag == "rect":
        x, y, w, h, r = g("x"), g("y"), g("width"), g("height"), g("rx")
        if r == 0:
            return "M%s,%s h%s v%s h%s z" % (num(x), num(y), num(w), num(h), num(-w))
        a = "a%s,%s 0 0,1 " % (num(r), num(r))
        return ("M%s,%s h%s %s%s,%s v%s %s%s,%s h%s %s%s,%s v%s %s%s,%s z" % (
            num(x + r), num(y), num(w - 2 * r), a, num(r), num(r), num(h - 2 * r), a, num(-r), num(r),
            num(-(w - 2 * r)), a, num(-r), num(-r), num(-(h - 2 * r)), a, num(r), num(-r)))
    raise ValueError("no Android form for <%s>" % tag)


def vector(name, dp):
    """One icon as an Android vector drawable of dp x dp."""
    import xml.etree.ElementTree as ET
    root = ET.fromstring(svg(name))
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- Written by make_icons.py: do not edit. Our own drawing, no game artwork. -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="%ddp" android:height="%ddp"' % (dp, dp),
           '    android:viewportWidth="38" android:viewportHeight="38">',
           '    <group android:translateX="3" android:translateY="3">']
    for el in root:
        attrs = ['android:pathData="%s"' % path_of(el)]
        if el.get("fill", "#000000") != "none":
            attrs.append('android:fillColor="%s"' % el.get("fill", "#000000"))
        if el.get("stroke"):
            attrs.append('android:strokeColor="%s"' % el.get("stroke"))
            attrs.append('android:strokeWidth="%s"' % el.get("stroke-width", "1"))
            if el.get("stroke-linecap"):
                attrs.append('android:strokeLineCap="%s"' % el.get("stroke-linecap"))
            if el.get("stroke-linejoin"):
                attrs.append('android:strokeLineJoin="%s"' % el.get("stroke-linejoin"))
        out.append("        <path " + " ".join(attrs) + " />")
    out += ["    </group>", "</vector>", ""]
    return "\n".join(out)


CSS = """
* { box-sizing: border-box; margin: 0; padding: 0; }
body { width: 540px; background: #171A33; color: #F2F4FA; padding: 22px 20px 26px;
       font: 15px/1.35 -apple-system, "Helvetica Neue", Arial, sans-serif; }
h1 { font-size: 25px; line-height: 1.15; }
.sub { color: #F6DC7A; font-weight: 600; margin: 6px 0 4px; }
h2 { font-size: 19px; margin: 24px 0 2px; }
.why { color: #AEB4CF; margin-bottom: 10px; }
.card { display: flex; gap: 14px; align-items: flex-start; background: #20244A; border-radius: 12px;
        padding: 12px 14px; margin-top: 8px; }
.card svg { flex: none; }
.name { font-size: 17px; font-weight: 700; }
.what { color: #D5D9EC; }
.tags { margin-top: 5px; font-size: 12.5px; color: #AEB4CF; }
.tags b { color: #F2F4FA; font-weight: 600; }
table { border-collapse: collapse; width: 100%; margin-top: 8px; font-size: 13.5px; }
th, td { text-align: left; padding: 7px 8px; border-bottom: 1px solid #333863; vertical-align: top; }
th { color: #AEB4CF; font-weight: 600; font-size: 12.5px; }
.note { color: #AEB4CF; font-size: 13px; margin-top: 10px; }
.left { background: #20244A; border-radius: 12px; padding: 12px 14px; margin-top: 8px; color: #D5D9EC; }
.swatches { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-top: 8px; }
.sw { border-radius: 10px; padding: 10px 6px 8px; text-align: center; font-size: 12px; }
.sw div { display: flex; flex-wrap: wrap; gap: 6px; justify-content: center; margin-bottom: 6px; }
"""


def page(title, sub, body):
    return ('<!doctype html><html><head><meta charset="utf-8"><title>%s</title><style>%s</style></head>'
            '<body><h1>%s</h1><p class="sub">%s</p>%s</body></html>' % (title, CSS, title, sub, body))


def sheet_icons():
    out = []
    for head, why, names in GROUPS:
        out.append('<h2>%s</h2><p class="why">%s</p>' % (head, why))
        for n in names:
            _, _, name, what, source, rng = ICONS[n]
            out.append('<div class="card">%s<div><div class="name">%s</div><div class="what">%s</div>'
                       '<div class="tags"><b>%s.</b> %s.</div></div></div>'
                       % (svg(n, 62), name, what, RANGE[rng], SOURCE[source]))
    out.append('<h2>In the Karoo\'s map, but I would leave them out</h2>'
               '<div class="left">Banks, post boxes, hairdressers, laundries, libraries, schools and universities, '
               'police, hospitals, hostels, malls, playgrounds, car parks, graveyards, traffic lights.</div>')
    return page("Map icons: first draft",
                "Drawn on the Mac. Not on the Karoo yet: size and look on the unit still to be tested.",
                "".join(out))


def sheet_zoom():
    # Scale and metres per pixel read from the unit's screenshots (previews/extension/2*.png):
    # at zoom 16, 100 m on the scale is about 130 px, so the 800 px screen is about 610 m tall
    # and the position arrow, in the middle, has about 300 m of map ahead of it.
    rows = [
        ("100 m", "17", "150 m", "under 8 km/h", "all"),
        ("200 m, long bar", "16", "300 m", "8 to 18 km/h", "all"),
        ("200 m, short bar", "15", "600 m", "18 to 28 km/h", "all"),
        ("500 m", "14", "1.2 km", "28 to 40 km/h", "far and middle"),
        ("1 km", "13", "2.5 km", "above 40 km/h", "far"),
        ("2 km", "12", "5 km", "by pinch only", "far"),
    ]
    t = ['<h2>From how far away</h2><p class="why">Scale as the Karoo shows it on the map, read from your own screenshots.</p>'
         '<table><tr><th>Scale on the Karoo</th><th>Zoom</th><th>You see ahead, about</th><th>Zoom by speed picks it</th><th>Icons shown</th></tr>']
    for r in rows:
        t.append("<tr>" + "".join("<td>%s</td>" % c for c in r) + "</tr>")
    t.append("</table>")
    t.append('<p class="note">The Karoo\'s own map holds shops, cafes and water from zoom 15 only, and only the ones drawn '
             'as a point. The last three rows can only be filled from a place list.</p>')
    t.append('<h2>On your map colours</h2><p class="why">The badges on the ground colours of the day and night themes. '
             'Flat colour only, not a map.</p><div class="swatches">')
    for label, colour in GROUNDS:
        light = colour in ("#215C5F", "#0F1536")
        icons = "".join(svg(n, 40) for n in ["shelter", "water", "groceries", "food", "fuel", "coffee"])
        t.append('<div class="sw" style="background:%s;color:%s"><div>%s</div>%s</div>'
                 % (colour, "#F2F4FA" if light else "#11132B", icons, label))
    t.append("</div>")
    return page("Map icons: zoom and colours", "A suggestion to react to. Nothing is built yet.", "".join(t))


def render(html_path, png_path):
    """HTML to PNG with Chrome, at twice the size for a sharp picture on a phone."""
    chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
    probe = html_path.replace(".html", "-probe.html")
    with open(html_path) as f:
        html = f.read()
    with open(probe, "w") as f:
        f.write(html.replace("</body>", "<script>document.title='H='+document.documentElement.scrollHeight</script></body>"))
    dom = subprocess.run([chrome, "--headless=new", "--disable-gpu", "--window-size=540,800", "--dump-dom",
                          "file://" + probe], capture_output=True, text=True, timeout=120).stdout
    os.remove(probe)
    height = int(dom.split("<title>H=")[1].split("<")[0])
    subprocess.run([chrome, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=2",
                    "--window-size=540,%d" % height, "--screenshot=" + png_path, "file://" + html_path],
                   capture_output=True, timeout=120)
    print("wrote", os.path.relpath(png_path, HERE), "540 x", height, "at 2x")


def main():
    icons = os.path.join(HERE, "icons")
    os.makedirs(icons, exist_ok=True)
    for name in ICONS:
        with open(os.path.join(icons, name + ".svg"), "w") as f:
            f.write(svg(name))
    sheets = {"sheet-icons": sheet_icons(), "sheet-zoom": sheet_zoom()}
    for name, html in sheets.items():
        with open(os.path.join(icons, name + ".html"), "w") as f:
            f.write(html)
    print("wrote %d icons and %d sheets into icons/" % (len(ICONS), len(sheets)))
    for old in os.listdir(DRAWABLES):
        if old.startswith("poi_"):
            os.remove(os.path.join(DRAWABLES, old))
    for name in EXTENSION_ICONS:
        for size, dp in SIZES_DP.items():
            with open(os.path.join(DRAWABLES, "poi_%s_%s.xml" % (name, size)), "w") as f:
                f.write(vector(name, dp))
    for name in FIELD_ICONS:
        with open(os.path.join(DRAWABLES, "poi_%s_l.xml" % name), "w") as f:
            f.write(vector(name, SIZES_DP["l"]))
    print("wrote %d drawables into extension/app/src/main/res/drawable/" % (len(EXTENSION_ICONS) * len(SIZES_DP) + len(FIELD_ICONS)))
    if "sheet" in sys.argv[1:]:
        out = os.path.join(HERE, "previews", "icons")
        os.makedirs(out, exist_ok=True)
        for name in sheets:
            render(os.path.join(icons, name + ".html"), os.path.join(out, name + ".png"))


if __name__ == "__main__":
    main()
