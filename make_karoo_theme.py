#!/usr/bin/env python3
"""Writes the GTA VI look as a theme for the Karoo 2's OWN map (the ride app's map page).

    python3 make_karoo_theme.py

It writes karoo-native/GTA-VI-day.xml and karoo-native/GTA-VI-night.xml. The colours, the line
classes and the widths come from the tables in make_style.py, so the two maps stay in step.

The Karoo's ride app draws its map with the open-source VTM engine and a Mapsforge-style theme:
/sdcard/offline_v15.xml on the unit, a copy of assets/offline.xml inside the ride app. This script
writes the same dialect and uses only elements and attributes that Hammerhead's own theme uses.

Nothing here touches the Karoo. To try a theme, the unit's file has to be replaced; README.md has
the commands and the way back.
"""

import os

import make_style as ms

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(HERE, "karoo-native")

# Lines are written as VTM "fixed" lines (fix="true"): they keep one pixel width whatever the zoom,
# so each zoom's rule sets the width outright, as the width table in make_style.py means it.
# Ordinary VTM lines grow and shrink with the zoom in steps that were measured on the unit and
# found uneven (a width-1.0 line was 10 px at zoom 16 but 16 px at zoom 15).
# Measured on the unit on 26.11.2026: a fixed line of theme width 4.0 is 13 px wide at zooms 13 to 16.
PX_PER_WIDTH = 13 / 4.0
MIN_ZOOM, MAX_ZOOM = 8, 18          # the zooms rules are written for; 18 also covers closer zooms


# The walls of the 3D houses: the house colour, this much darker. Not a colour from the table;
# the game's map shows roofs and walls in two close shades.
WALL = 0.86


def shade(colour, factor):
    return "#" + "".join("%02X" % round(int(colour[i:i + 2], 16) * factor) for i in (1, 3, 5))


def theme_width(pixels, zoom):
    return max(pixels / PX_PER_WIDTH, 0.3)


def pixels_by_zoom(key):
    """Zoom -> (fill px, edge px on each side) for one line class, from make_style's width table."""
    _, _, _, widths, _, first_zoom = ms.LINES[key]
    out = {}
    for low, high, width, factor in ms.widths_by_zoom(widths, first_zoom):
        fill, edge = width if isinstance(width, tuple) else (width, 0)
        for zoom in range(max(low, MIN_ZOOM), min(high, MAX_ZOOM) + 1):
            # Table values are Edge 1060 pixels; DP * DENSITY turns them into Karoo pixels.
            out[zoom] = (fill * ms.DP * ms.DENSITY * factor, edge * ms.DP * ms.DENSITY * factor)
    return out


def zoom_runs(by_zoom, value):
    """[(zoom from, zoom to, theme width)]: one rule per zoom, the last one open-ended."""
    zooms = sorted(by_zoom)
    runs = []
    for zoom in zooms:
        pixels = value(by_zoom[zoom])
        if pixels > 0:
            runs.append((zoom, None if zoom == zooms[-1] else zoom, theme_width(pixels, zoom)))
    return runs


def line_rules(key, mode, part, pad):
    """The <m zoom..><line/></m> rules of one class. part: 'edge' (drawn first) or 'fill'."""
    _, fill, edge, _, _, _ = ms.LINES[key]
    by_zoom = pixels_by_zoom(key)
    if part == "edge":
        if not edge:
            return []
        colour, runs = edge[mode], zoom_runs(by_zoom, lambda fe: fe[0] + 2 * fe[1] if fe[1] else 0)
    else:
        colour, runs = fill[mode], zoom_runs(by_zoom, lambda fe: fe[0])
    out = []
    for low, high, width in runs:
        zoom = 'zoom-min="%d"' % low + ('' if high is None else ' zoom-max="%d"' % high)
        out.append('%s<m %s><line stroke="%s" cap="round" fix="true" width="%.2f"/></m>' % (pad, zoom, colour, width))
    return out


# Which OSM ways belong to which line class, in the Karoo theme's own matching language.
# (selector, [(inner selector or None, class or None)]): with more than one inner rule, the first
# that matches wins; a class of None draws nothing.
ROUGH = "ground|grass|dirt|earth|mud|sand"
WAYS = [
    ('k="waterway" v="stream"', [(None, "stream")]),
    ('k="waterway" v="river|canal"', [(None, "river")]),
    ('k="highway" v="bridleway"', [(None, "trail")]),
    ('k="highway" v="path"', [('k="bicycle" v="designated"', "gravel"), ('k="~" v="~"', "trail")]),
    ('k="highway" v="footway"', [('k="bicycle" v="designated"', "gravel"), ('k="~" v="~"', None)]),
    ('k="highway" v="cycleway"', [(None, "gravel")]),
    ('k="highway" v="track"', [('k="tracktype" v="grade1|grade2|grade3"', "gravel"),
                              ('k="tracktype" v="grade4|grade5"', "trail"),
                              ('k="surface" v="%s"' % ROUGH, "trail"),
                              ('k="~" v="~"', "gravel")]),
    ('k="aeroway" v="runway"', [(None, "runway")]),
    ('k="aeroway" v="taxiway"', [(None, "taxiway")]),
    ('k="railway" v="rail"', [('k="service" v="yard|siding|spur|crossover"', "rail_yard"), ('k="~" v="~"', "rail")]),
    ('k="highway" v="service"', [('k="service" v="driveway|parking_aisle|drive-through|emergency_access"', None),
                                ('k="~" v="~"', "service")]),
    ('k="highway" v="residential|living_street|road|pedestrian|primary_link|secondary_link|tertiary_link"',
     [(None, "street")]),
    ('k="highway" v="tertiary|unclassified"', [(None, "tertiary")]),
    ('k="highway" v="motorway_link|trunk_link"', [(None, "ramp")]),
    ('k="highway" v="secondary"', [(None, "secondary")]),
    ('k="highway" v="primary"', [(None, "primary")]),
    ('k="highway" v="motorway|trunk"', [(None, "motorway")]),
]

# Areas, bottom to top: (comment, [(key, values)], colour pair, lowest zoom or 0).
AREAS = [
    ("Town ground", [("landuse", "residential|commercial|retail|industrial|railway|construction|garages|"
                                 "brownfield|quarry|military|education|institutional|religious"),
                     ("amenity", "kindergarten|school|college|university|hospital"),
                     ("aeroway", "aerodrome")], ms.TOWN, 0),
    ("Forest", [("landuse|natural", "forest|wood")], ms.FOREST, 0),
    ("Park, garden, cemetery", [("leisure", "park|garden|golf_course|common|green"),
                                ("landuse", "village_green|recreation_ground|cemetery"),
                                ("amenity", "grave_yard")], ms.PARK, 12),
    ("Sand", [("natural", "beach|sand")], ms.SAND, 14),
    ("Wetland", [("natural", "wetland|marsh")], ("#80" + ms.SHALLOW[0][1:], "#80" + ms.SHALLOW[1][1:]), 14),
    ("Water", [("landuse", "reservoir|basin")], ms.WATER, 0),
]


def build(mode):
    """mode 0: day, 1: night."""
    c = lambda pair: pair[mode]
    out = ['<?xml version="1.0" encoding="UTF-8"?>',
           "<!-- Written by make_karoo_theme.py; do not edit by hand. -->",
           "<!-- GTA VI look for the Karoo 2's own map (%s), inspired by the in-game map. -->" % ("day", "night")[mode],
           '<rendertheme xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" map-background="%s"' % c(ms.LAND),
           '    version="1" xmlns="http://mapsforge.org/renderTheme"',
           '    xsi:schemaLocation="http://mapsforge.org/renderTheme '
           'https://raw.githubusercontent.com/mapsforge/vtm/master/resources/rendertheme.xsd">',
           "",
           "    <!-- Land and sea -->",
           '    <m e="way" k="natural" v="issea|sea">',
           '        <area mesh="true" fill="%s"/>' % c(ms.WATER),
           "    </m>",
           '    <m e="way" k="natural" v="nosea">',
           '        <area mesh="true" fill="%s"/>' % c(ms.LAND),
           "    </m>",
           "",
           "    <!-- Areas, bottom to top. Closed ways that are neither roads nor houses. -->",
           '    <m closed="yes" e="way" k="highway|building" v="~">']
    for name, tags, colours, zoom in AREAS:
        out.append("        <!-- %s -->" % name)
        for key, values in tags:
            gate = ' zoom-min="%d"' % zoom if zoom else ""
            out += ['        <m k="%s" v="%s"%s>' % (key, values, gate),
                    '            <area mesh="true" fill="%s"/>' % c(colours),
                    "        </m>"]
    out += ["    </m>",
            "",
            "    <!-- Water: river banks, docks and lakes -->",
            '    <m e="way" k="waterway" v="riverbank|dock">',
            '        <area mesh="false" fill="%s"/>' % c(ms.WATER),
            "    </m>",
            '    <m e="way" closed="yes" k="natural" v="water">',
            '        <area mesh="true" fill="%s"/>' % c(ms.WATER),
            "    </m>",
            "",
            "    <!-- Squares: pedestrian areas -->",
            '    <m e="way" closed="yes" k="highway" v="pedestrian" zoom-min="15">',
            '        <m k="area" v="yes|true">',
            '            <area mesh="true" fill="%s"/>' % c(ms.ROAD),
            "        </m>",
            "    </m>",
            "",
            "    <!-- Houses: a flat footprint, and from zoom 17 the ride app's own 3D blocks. The Karoo",
            "         draws those by itself in near-white; this gives them the house colour. -->",
            '    <m e="way" k="building" v="*" zoom-min="15">',
            '        <area mesh="true" fill="%s"/>' % c(ms.BUILDING),
            "    </m>",
            '    <m e="way" k="building" v="*" zoom-min="17">',
            '        <extrusion line-color="%s" side-color="%s" top-color="%s"/>' % (shade(c(ms.BUILDING), WALL), shade(c(ms.BUILDING), WALL), c(ms.BUILDING)),
            "    </m>",
            ""]
    # Lines: no tunnels. All edges first, then all fills, so that junctions merge.
    for part, title in (("edge", "Line edges (drawn under every fill)"), ("fill", "Line fills")):
        out += ["    <!-- %s -->" % title,
                '    <m e="way" k="tunnel" v="~|false|no">']
        for selector, inner in WAYS:
            body = []
            for inner_selector, key in inner:
                rules = line_rules(key, mode, part, "                " if inner_selector else "            ") if key else []
                if inner_selector is None:
                    body += rules
                elif rules:
                    body += ["            <m %s>" % inner_selector] + rules + ["            </m>"]
                elif part == "fill" or any(k and ms.LINES[k][2] for _, k in inner):
                    # Nothing to draw, but the rule must still match so that the next one is skipped.
                    body += ["            <m %s>" % inner_selector, '                <line width="0.0"/>', "            </m>"]
            if not any("<line stroke" in line for line in body):
                continue
            first = ' select="first"' if len(inner) > 1 else ""
            out += ["        <m %s%s>" % (selector, first)] + body + ["        </m>"]
        out += ["    </m>", ""]
    out += ["    <!-- Place names: no street names, no POIs -->",
            '    <m e="node" k="place">']
    for value, zoom, size, bold, order in ms.PLACES:
        gate = ' zoom-min="%d"' % zoom if zoom > MIN_ZOOM else ""
        style = ' style="bold"' if bold else ""
        halo = ' stroke="%s" stroke-width="2.0"' % ms.HALO_NIGHT if mode else ""
        out += ['        <m v="%s"%s>' % (value, gate),
                '            <caption%s fill="%s" k="name" priority="%d" size="%d" font-family="thin"%s/>'
                % (style, c(ms.LABEL), order // 10, size, halo),
                "        </m>"]
    out += ["    </m>", "", "</rendertheme>", ""]
    return "\n".join(out)


if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)
    for mode, name in ((0, "GTA-VI-day.xml"), (1, "GTA-VI-night.xml")):
        path = os.path.join(OUT_DIR, name)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(build(mode))
        print("Karoo theme: %s" % path)
