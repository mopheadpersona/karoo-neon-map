#!/usr/bin/env python3
"""Writes GTA-VI.render.xml: the GTA VI map look as an OsmAnd map style, day and night.

    python3 make_style.py [target]

Everything about the look is in the tables below: colours (day, night), line classes with their
OSM tags, widths and zooms, areas, place names and the route colour. Change a table, run the
script, and copy the XML to OsmAnd. The tables below are the whole look.

This is a standalone copy of karoo/osmand/make_style.py from the garmin-map project (25.11.2026).
There the colours and widths come from the Garmin GTA VI map; here they are written out, so this
folder needs nothing else. The output is the same file.

Widths are given in pixels of the Garmin Edge 1060 (267 ppi), the map this look was first made for,
one value per OsmAnd zoom 16, 15, 14, 13, 12, 11. DP turns them into OsmAnd dp for the Karoo 2, so
a line is the same size on the glass. The zooms were matched by how much ground the screen shows,
assuming density 1.5. The unit turned out to have density 1.875, so each zoom shows about 20 % less
ground than that match assumed. Still to settle with pictures from the unit.
"""

import os
import sys

# --------------------------------------------------------------------------
# Colours: (day, night). "estimated" means no sample from the game; guessed.
# Day colours were measured from the light GTA VI map (gtabase.com), night colours from the
# dark in-game pause map. Some colours were picked by eye; those are marked.
# Night LAND, TOWN and PARK were measured on 26.11.2026 from a dark in-game screenshot. Before that they were #0E142C, #2C3B5E and #16293C,
# and FOREST was #122036.
# --------------------------------------------------------------------------
LAND = ("#6D9985", "#215C5F")         # the map background: fields, meadows, everything not listed; night: teal
TOWN = ("#9EA5AC", "#0F1536")         # town ground: residential, industrial, schools...; night: navy
FOREST = ("#5F8C70", "#004C47")       # night: no sample in the screenshot, so the park's green (a guess)
PARK = ("#50866D", "#004C47")         # night: the green area in the screenshot
BUILDING = ("#9DACBF", "#46567B")
WATER = ("#49A2BA", "#474A80")
SHALLOW = ("#51ABC3", "#3E4274")      # wetland water; night estimated
SAND = ("#E3D5A6", "#3C3F55")         # day from an in-game screenshot, night estimated
ROAD = ("#D1D2DC", "#9094AA")         # every road; night: the game's #6A6E84 brightened for the screen
LANE = ("#E3D6AE", "#C2B993")         # lanes of primary and secondary roads; night estimated
GOLD = ("#BCA36B", "#A38B57")         # lanes of motorways (picked by eye); night estimated
GRAVEL = ("#BFBFC7", "#6E7288")       # gravel roads and cycleways (picked by eye); night estimated
TRAIL = ("#A3A3AD", "#555A72")        # trails and rough tracks: darker than gravel 
RAIL = ("#A5B4CA", "#5A6688")         # night estimated
RAIL_EDGE = ("#8C9AB2", "#3E4A6A")    # estimated
LABEL = ("#000000", "#F2F4FA")        # place names: black by day , white at night
HALO_NIGHT = TOWN[1]                  # the halo round night names: the town navy, also over teal land
ROUTE = "#F06DB5"                     # the GTA waypoint pink: OsmAnd's navigation route line

# OsmAnd's old renderer ("Version 1", the one the Karoo 2 runs) draws the map in 16-bit colour:
# it cuts red and blue to 5 bits and green to 6, always downwards. #D1D2DC comes out as #D6D2DE.
# With this switch on, every colour is written as the nearest 16-bit colour instead (at most 4
# away per channel), so the screen shows exactly what the XML says. The tables keep the picks.
SNAP_16BIT = True

# Edge 1060 pixel -> OsmAnd dp on the Karoo 2: same size on the glass.
# Edge 1060: 267 ppi. Karoo 2: 291 ppi (3.2", 480x800), Android density 300, so 1 dp is 1.875
# pixels. Read from the unit on 26.11.2026 (adb shell wm size; adb shell wm density).
DENSITY = 300 / 160
DP = 291 / 267 / DENSITY

# The zoom of each width column.
LEVEL_ZOOM = (16, 15, 14, 13, 12, 11)
# Closer than zoom 16 the lines grow a little: zoom 17 x1.3, zoom 18 and closer x1.7.
CLOSE = ((17, 1.3), (18, 1.7))

# --------------------------------------------------------------------------
# Line classes: key -> (name, fill, edge or None, widths, draw order, lowest zoom).
# Widths: Edge 1060 pixels at zoom 16, 15, 14, 13, 12, 11. A number is a plain line; a pair
# (fill, edge) is a line with an edge of that width on each side; None: not drawn at that zoom
# (the next drawn column is used below it, down to the lowest zoom).
# --------------------------------------------------------------------------
LINES = {
    "stream": ("Stream", WATER, None, (3, 2, None, None, None, None), 10, 15),
    "river": ("River, canal", WATER, None, (9, 7, 6, 5, 4, 3), 11, 9),
    "trail": ("Trail, rough track", TRAIL, None, (6, None, None, None, None, None), 20, 16),
    "gravel": ("Gravel road, cycleway", GRAVEL, None, (6, 4, 3, None, None, None), 21, 14),
    "runway": ("Runway", ROAD, None, (26, 16, 11, 7, None, None), 22, 13),
    "taxiway": ("Taxiway", ROAD, None, (8, 5, None, None, None, None), 23, 15),
    "rail_yard": ("Railway yard", RAIL, RAIL_EDGE, ((2, 1), (1, 1), None, None, None, None), 25, 15),
    "rail": ("Railway", RAIL, RAIL_EDGE, ((3, 1), (2, 1), (2, 1), (1, 1), (1, 1), 2), 26, 9),
    "service": ("Service road", ROAD, None, (7, 5, None, None, None, None), 30, 15),
    "street": ("Street", ROAD, None, (12, 8, 5, 3, None, None), 31, 13),
    "tertiary": ("Tertiary road", ROAD, None, (14, 10, 7, 5, 3, None), 32, 12),
    "ramp": ("Motorway ramp", GOLD, ROAD, ((5, 3), (4, 2), (3, 2), (2, 1), None, None), 35, 13),
    "secondary": ("Secondary road", LANE, ROAD, ((6, 5), (4, 4), (3, 3), (2, 2), (2, 1), (1, 1)), 36, 11),
    "primary": ("Primary road", LANE, ROAD, ((7, 5), (4, 4), (3, 3), (3, 2), (3, 1), (2, 1)), 37, 7),
    "motorway": ("Motorway, trunk road", GOLD, ROAD, ((10, 4), (7, 3), (5, 3), (4, 2), (3, 2), (3, 1)), 38, 7),
}

# OSM tags of each line class, in the order OsmAnd tests them: for one tag and value, the first
# rule that matches wins, so the narrow rules (with an additional tag) come before the wide ones.
# A rule is (tag, value, additional or None). Tunnels and culverts are dropped separately.
DROP_LINES = [
    ("highway", "service", "service=driveway"), ("highway", "service", "service=parking_aisle"),
    ("highway", "service", "service=drive-through"), ("highway", "service", "service=emergency_access"),
    ("highway", "footway", "footway=sidewalk"), ("highway", "footway", "footway=crossing"),
]
LINE_RULES = [
    ("motorway", [("highway", "motorway", None), ("highway", "trunk", None)]),
    ("ramp", [("highway", "motorway_link", None), ("highway", "trunk_link", None)]),
    ("primary", [("highway", "primary", None)]),
    ("secondary", [("highway", "secondary", None)]),
    ("tertiary", [("highway", "tertiary", None), ("highway", "unclassified", None)]),
    ("street", [("highway", v, None) for v in ("primary_link", "secondary_link", "tertiary_link", "residential",
                                               "living_street", "road", "pedestrian")]),
    ("service", [("highway", "service", None)]),
    # Gravel roads and cycleways; tracks of grade 1-3 are gravel whatever their surface.
    ("gravel", [("highway", "cycleway", None), ("highway", "path", "bicycle=designated"),
                ("highway", "footway", "bicycle=designated"),
                ("highway", "track", "tracktype=grade1"), ("highway", "track", "tracktype=grade2"),
                ("highway", "track", "tracktype=grade3")]),
    # Trails and rough tracks. Footways are left out: OsmAnd can't tell a footway in town from one
    # in the woods (the Garmin style keeps those outside residential areas).
    ("trail", [("highway", "track", "tracktype=grade4"), ("highway", "track", "tracktype=grade5")]
              + [("highway", "track", "surface=" + s) for s in ("ground", "grass", "dirt", "earth", "mud", "sand")]
              + [("highway", "path", None), ("highway", "bridleway", None)]),
    # Every other track is gravel.
    ("gravel", [("highway", "track", None)]),
    ("rail_yard", [("railway", "rail", "service=" + s) for s in ("yard", "siding", "spur", "crossover")]),
    ("rail", [("railway", "rail", None)]),
    ("runway", [("aeroway", "runway", None)]),
    ("taxiway", [("aeroway", "taxiway", None)]),
    ("river", [("waterway", "river", None), ("waterway", "canal", None)]),
    ("stream", [("waterway", "stream", None)]),
]

# --------------------------------------------------------------------------
# Areas, bottom to top: name -> (OSM tags, colours, draw order, lowest zoom; 0 = always).
# Wetland is shallow water laid half transparent over the land.
# --------------------------------------------------------------------------
TOWN_TAGS = [("landuse", v) for v in ("residential", "commercial", "retail", "industrial", "railway", "construction",
                                      "garages", "brownfield", "quarry", "military", "education", "institutional",
                                      "religious")] \
    + [("aeroway", "aerodrome"), ("amenity", "school"), ("amenity", "university"), ("amenity", "college"),
       ("amenity", "hospital")]
AREAS = {
    "Town ground": (TOWN_TAGS, TOWN, 3, 0),
    "Forest": ([("landuse", "forest"), ("natural", "wood")], FOREST, 4, 0),
    "Park, garden, cemetery": ([("leisure", "park"), ("leisure", "garden"), ("leisure", "golf_course"),
                                ("landuse", "village_green"), ("landuse", "recreation_ground"),
                                ("landuse", "cemetery"), ("amenity", "grave_yard")], PARK, 5, 12),
    "Sand": ([("natural", "beach"), ("natural", "sand")], SAND, 5, 14),
    "Wetland": ([("natural", "wetland")], ("#80" + SHALLOW[0][1:], "#80" + SHALLOW[1][1:]), 6, 14),
    "Water": ([("natural", "water"), ("waterway", "riverbank"), ("landuse", "reservoir"), ("landuse", "basin")],
              WATER, 7, 0),
    # OsmAnd's maps store highway=pedestrian with area=yes as area:highway=pedestrian (from zoom 16),
    # so highway=pedestrian itself is always a street here.
    "Square, pedestrian area": ([("area:highway", "pedestrian"), ("place", "square")], ROAD, 8, 15),
    "House": ([("building", "")], BUILDING, 10, 15),
}

# Place names: OSM place value -> (lowest zoom, text size in dp, bold, text order)
PLACES = [("city", 6, 17, True, 20), ("town", 9, 15, True, 30), ("village", 12, 13, False, 40),
          ("suburb", 14, 13, False, 45), ("hamlet", 14, 11, False, 50)]


def num(value):
    return ("%.2f" % value).rstrip("0").rstrip(".")


def drawn(colour):
    """The nearest colour a 16-bit picture can hold; '#RRGGBB' or '#AARRGGBB' (alpha is kept)."""
    body = colour.lstrip("#")
    alpha, rgb = (body[:2], body[2:]) if len(body) == 8 else ("", body)
    out = ""
    for index, bits in enumerate((5, 6, 5)):
        top = (1 << bits) - 1
        level = round(int(rgb[index * 2:index * 2 + 2], 16) * top / 255)
        out += "%02X" % round(level * 255 / top)
    return "#" + alpha + out


def snap_all(xml):
    """Replaces every map colour in the finished XML by the one the 16-bit renderer shows exactly.

    The route and GPX lines are left alone: OsmAnd draws those itself, over the map, in full colour.
    """
    import re
    out, own_layer = [], False
    for line in xml.split("\n"):
        if '<renderingAttribute name="route">' in line or '<renderingAttribute name="gpx">' in line:
            own_layer = True
        if not own_layer:
            line = re.sub(r"#(?:[0-9A-Fa-f]{8}|[0-9A-Fa-f]{6})\b", lambda match: drawn(match.group(0)), line)
        if "</renderingAttribute>" in line:
            own_layer = False
        out.append(line)
    return "\n".join(out)


def tags_attr(tag, value, additional):
    out = 'tag="%s" value="%s"' % (tag, value)
    if additional:
        out += ' additional="%s"' % additional
    return out


def widths_by_zoom(widths, first_zoom):
    """Widths per column -> [(minzoom, maxzoom, width, factor)] from first_zoom up; lines grow past zoom 16."""
    present = [(zoom, widths[level]) for level, zoom in enumerate(LEVEL_ZOOM) if widths[level] is not None]
    out = []
    for k, (zoom, width) in enumerate(present):
        if zoom < first_zoom:
            continue
        # The least detailed column present also covers every zoom below it, down to first_zoom.
        low = first_zoom if k == len(present) - 1 else zoom
        out.append((max(low, first_zoom), zoom, width, 1.0))
        if zoom == LEVEL_ZOOM[0]:
            for close_zoom, factor in CLOSE:
                out.append((close_zoom, 21 if close_zoom == CLOSE[-1][0] else close_zoom, width, factor))
    return sorted(out, key=lambda entry: entry[0])


def line_rule(rules, fill, edge, widths, first_zoom, indent):
    """One <case> per tag with the width per zoom; edges are solid shadows (drawn under all lines)."""
    pad = "\t" * indent
    lines = []
    for tag, value, additional in rules:
        lines.append('%s<case %s/>' % (pad, tags_attr(tag, value, additional)))
    lines.append("%s<apply>" % pad)
    for low, high, width, factor in widths_by_zoom(widths, first_zoom):
        if isinstance(width, tuple):
            attrs = 'strokeWidth="%s" shadowRadius="%s"' % (num(width[0] * DP * factor), num(width[1] * DP * factor))
        else:
            attrs = 'strokeWidth="%s"' % num(width * DP * factor)
        lines.append('%s\t<case minzoom="%d" maxzoom="%d" %s/>' % (pad, low, high, attrs))
    lines.append("%s</apply>" % pad)
    day = 'color="%s"' % fill[0]
    night = 'color="%s"' % fill[1]
    if edge:
        day += ' shadowColor="%s"' % edge[0]
        night += ' shadowColor="%s"' % edge[1]
    lines.append('%s<apply %s cap="ROUND"/>' % (pad, day))
    lines.append('%s<apply_if nightMode="true" %s/>' % (pad, night))
    return lines


def build():
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           "<!-- Written by make_style.py; do not edit by hand. -->",
           "<!-- GTA VI look for OsmAnd, inspired by the in-game map: day and night (Configure map, Map mode). -->",
           '<renderingStyle name="GTA VI" depends="" defaultColor="%s" version="1">' % LAND[0],
           "",
           '\t<renderingProperty attr="appMode" name="Rendering mode" description="Map optimization for respective User Profile"',
           '\t\ttype="string" possibleValues=""/>',
           '\t<renderingProperty attr="baseAppMode" name="Default Rendering mode" description="Map optimization for respective User Profile based on base (parent) profile"',
           '\t\ttype="string" possibleValues="default,car,bicycle,pedestrian,public_transport,boat,ski"/>',
           '\t<renderingProperty attr="engine_v1" type="boolean"/>',
           "\t<!-- 3D buildings (OpenGL renderer, Configure map, 3D buildings): the houses' own colour. -->",
           '\t<renderingProperty attr="show3DbuildingParts" name="Building parts" description="Show building parts"',
           '\t\ttype="boolean" possibleValues="" category="3D Buildings"/>',
           '\t<renderingProperty attr="useDefaultBuildingColor" name="Use default buildings color" description="Use default buildings color"',
           '\t\ttype="boolean" possibleValues="" category="3D Buildings"/>',
           '\t<renderingAttribute name="base3DBuildingsColor">',
           '\t\t<case attrColorValue="%s">' % BUILDING[0],
           '\t\t\t<apply_if nightMode="true" attrColorValue="%s"/>' % BUILDING[1],
           "\t\t</case>",
           "\t</renderingAttribute>",
           '\t<renderingConstant name="default3DBuildingHeight" value="3.0"/>',
           '\t<renderingConstant name="default3DBuildingLevelHeight" value="3.0"/>',
           "",
           '\t<renderingAttribute name="defaultColor">',
           '\t\t<case attrColorValue="%s">' % LAND[0],
           '\t\t\t<apply_if nightMode="true" attrColorValue="%s"/>' % LAND[1],
           "\t\t</case>",
           "\t</renderingAttribute>",
           "",
           "\t<!-- 3 = solid shadow: the grey edges of motorways, primary and secondary roads and the edges of",
           "\t     the railway are drawn as shadows, under every line, so junctions stay clean. -->",
           '\t<renderingAttribute name="shadowRendering">',
           '\t\t<case attrIntValue="3" shadowColor="%s"/>' % ROAD[0],
           "\t</renderingAttribute>",
           '\t<renderingAttribute name="polygonMinSizeToDisplay">',
           '\t\t<case attrIntValue="6"/>',
           "\t</renderingAttribute>",
           '\t<renderingAttribute name="roadDensityZoomTile">',
           '\t\t<case minzoom="15" attrIntValue="4"/>',
           '\t\t<case attrIntValue="3"/>',
           "\t</renderingAttribute>",
           '\t<renderingAttribute name="roadsDensityLimitPerTile">',
           '\t\t<case attrIntValue="40"/>',
           "\t</renderingAttribute>",
           '\t<renderingAttribute name="waterwayArrows">',
           '\t\t<case attrIntValue="0"/>',
           "\t</renderingAttribute>",
           "\t<!-- From zoom 16 OsmAnd draws one-way arrows on its own; this turns them off. -->",
           '\t<renderingAttribute name="noHighwayOnewayArrows">',
           '\t\t<case attrIntValue="1"/>',
           "\t</renderingAttribute>",
           "",
           "\t<!-- Navigation route: the GTA waypoint pink; white direction and turn arrows. -->",
           '\t<renderingAttribute name="route">',
           '\t\t<case color="%s" strokeWidth="6:4" color_2="#FFFFFF" color_3="#FFFFFF" strokeWidth_3="5:7"/>' % ROUTE,
           "\t</renderingAttribute>",
           '\t<renderingAttribute name="gpx">',
           '\t\t<case color="#CC%s" strokeWidth="4:3"/>' % ROUTE[1:],
           "\t</renderingAttribute>",
           "",
           "\t<order>",
           "\t\t<switch>",
           "\t\t\t<!-- Points: place names only -->",
           '\t\t\t<switch area="false" point="true" objectType="1" order="128">']
    for value, *_ in PLACES:
        out.append('\t\t\t\t<case tag="place" value="%s"/>' % value)
    out.append("\t\t\t</switch>")
    # An OsmAnd map marks an object as "area" only if OSM tags it area=yes or it is a multipolygon.
    # A plain closed outline (most houses, all town ground) is a "cycle". Both are filled.
    for test in ("cycle", "area"):
        out += ["\t\t\t<!-- Areas: %s -->" % ("closed outlines" if test == "cycle" else "tagged as areas"),
                '\t\t\t<switch %s="true" point="false" objectType="3">' % test,
                '\t\t\t\t<case tag="building" value="no" order="-1"/>']
        for name, (tags, _, order, _) in AREAS.items():
            out.append("\t\t\t\t<!-- %s -->" % name)
            for tag, value in tags:
                out.append('\t\t\t\t<case tag="%s" value="%s" order="%d"/>' % (tag, value, order))
        out.append("\t\t\t</switch>")
    out += [
            "\t\t\t<!-- Lines: dropped first, then one draw order per class -->",
            '\t\t\t<switch area="false" objectType="2">']
    for tag, value, additional in DROP_LINES:
        out.append('\t\t\t\t<case %s order="-1"/>' % tags_attr(tag, value, additional))
    for cls, rules in LINE_RULES:
        name, _, _, _, order, first_zoom = LINES[cls]
        out.append("\t\t\t\t<!-- %s -->" % name)
        for tag, value, additional in rules:
            # Below its zoom a line is dropped here, before a wider rule of the same tag could take it.
            out.append('\t\t\t\t<case maxzoom="%d" %s order="-1"/>' % (first_zoom - 1, tags_attr(tag, value, additional)))
            out.append('\t\t\t\t<case %s order="%d"/>' % (tags_attr(tag, value, additional), order))
    out += ['\t\t\t\t<apply_if additional="tunnel=yes" order="-1"/>',
            '\t\t\t\t<apply_if additional="tunnel=culvert" order="-1"/>',
            "\t\t\t</switch>",
            "\t\t</switch>",
            "\t</order>",
            "",
            "\t<text>",
            "\t\t<switch>"]
    for value, zoom, size, bold, order in PLACES:
        out.append('\t\t\t<case minzoom="%d" tag="place" value="%s" nameTag="" textSize="%d" textBold="%s" '
                   'textOrder="%d" textWrapWidth="20" intersectionMargin="10"/>'
                   % (zoom, value, size, "true" if bold else "false", order))
    out += ['\t\t\t<apply textColor="%s" textHaloColor="#00000000" textHaloRadius="0"/>' % LABEL[0],
            '\t\t\t<apply_if nightMode="true" textColor="%s" textHaloColor="%s" textHaloRadius="2"/>'
            % (LABEL[1], HALO_NIGHT),
            "\t\t</switch>",
            "\t</text>",
            "",
            "\t<!-- No icons: no POIs, no shields, no arrows. -->",
            "\t<point>",
            "\t</point>",
            "",
            "\t<polygon>",
            "\t\t<switch>"]
    for name, (tags, (day, night), _, zoom) in AREAS.items():
        out.append("\t\t\t<switch>")
        out.append("\t\t\t\t<!-- %s -->" % name)
        for tag, value in tags:
            attrs = 'tag="%s" value="%s"' % (tag, value)
            if zoom:
                attrs = 'minzoom="%d" ' % zoom + attrs
            out.append("\t\t\t\t<case %s/>" % attrs)
        out.append('\t\t\t\t<apply color="%s"/>' % day)
        out.append('\t\t\t\t<apply_if nightMode="true" color="%s"/>' % night)
        out.append("\t\t\t</switch>")
    out += ["\t\t</switch>",
            "\t</polygon>",
            "",
            "\t<line>",
            "\t\t<switch>"]
    for cls, rules in LINE_RULES:
        name, fill, edge, widths, _, first_zoom = LINES[cls]
        out.append("\t\t\t<!-- %s -->" % name)
        out.append("\t\t\t<switch>")
        out += line_rule(rules, fill, edge, widths, first_zoom, 4)
        out.append("\t\t\t</switch>")
    out += ["\t\t</switch>",
            "\t</line>",
            "</renderingStyle>",
            ""]
    xml = "\n".join(out)
    return snap_all(xml) if SNAP_16BIT else xml


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                                 "GTA-VI.render.xml")
    with open(target, "w", encoding="utf-8") as handle:
        handle.write(build())
    print("OsmAnd style: %s" % target)
