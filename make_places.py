#!/usr/bin/env python3
"""The place list behind the map icons: fetch it from OpenStreetMap, pack it for the extension.

    python3 make_places.py fetch [region ...]   download into downloads/places/ (not in git)
    python3 make_places.py pack                 write extension/places/places.bin from the downloads
    python3 make_places.py stats                count the downloaded places by kind and region

The Karoo's own map files hold too few places (shops and water only, from close zooms), so
the extension carries its own list and puts the icons on the map itself.

The tables below are the source: which regions, which OpenStreetMap tags make which icon.
Data (c) OpenStreetMap contributors, ODbL. Fetched through the public Overpass servers: one
few requests per region, a generic client name, nothing personal in it.

places.bin, little-endian:
    "GTAP", version (1 byte), kinds (1 byte), cell size in millionths of a degree (4 bytes),
    cell count N (4 bytes),
    N x [cell lat (2 bytes, signed), cell lon (2 bytes, signed), first record (4 bytes)],
    record count (4 bytes),
    records x [lat within the cell (2 bytes), lon within the cell (2 bytes), kind (1 byte)]
Cells are sorted by lat, then lon; a cell's records run to the next cell's first record.
"""
import csv
import os
import struct
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "downloads", "places")
OUT = os.path.join(HERE, "extension", "places", "places.bin")

# The regions a place list can be built for, by their ISO code. Add your own the same way.
REGIONS = {
    "czech-republic": '"ISO3166-1"="CZ"',
    "austria": '"ISO3166-1"="AT"',
    "bayern": '"ISO3166-2"="DE-BY"',
    "berlin": '"ISO3166-2"="DE-BE"',
    "brandenburg": '"ISO3166-2"="DE-BB"',
    "sachsen": '"ISO3166-2"="DE-SN"',
    "mecklenburg-vorpommern": '"ISO3166-2"="DE-MV"',
    "slovenia": '"ISO3166-1"="SI"',
    "denmark": '"ISO3166-1"="DK"',
    "norway": '"ISO3166-1"="NO"',
    "sweden": '"ISO3166-1"="SE"',
}

# What is fetched: every point, outline or group with one of these tags. In four requests per
# region, because the public servers often turn a big request away when they are busy.
FETCH = {
    "rest": ['nwr["amenity"="shelter"]', 'nwr["tourism"="wilderness_hut"]', 'nwr["tourism"="alpine_hut"]',
             'nwr["amenity"="drinking_water"]', 'nwr["amenity"="water_point"]',
             'nwr["natural"="spring"]["drinking_water"="yes"]', 'nwr["man_made"="water_tap"]["drinking_water"="yes"]',
             'nwr["amenity"="fountain"]["drinking_water"="yes"]'],
    "shops": ['nwr["shop"="supermarket"]', 'nwr["shop"="convenience"]', 'nwr["shop"="general"]', 'nwr["shop"="grocery"]',
              'nwr["shop"="bakery"]', 'nwr["shop"="bicycle"]', 'nwr["amenity"="bicycle_repair_station"]',
              'nwr["amenity"="fuel"]'],
    "food": ['nwr["amenity"="fast_food"]', 'nwr["amenity"="restaurant"]', 'nwr["amenity"="pub"]',
             'nwr["amenity"="biergarten"]', 'nwr["amenity"="cafe"]'],
    "other": ['nwr["railway"="station"]', 'nwr["railway"="halt"]', 'nwr["amenity"="toilets"]'],
}
COLUMNS = ["amenity", "shop", "tourism", "railway", "natural", "man_made", "shelter_type",
           "drinking_water", "automated", "access", "station"]

# The kinds, in the order of their number in places.bin. The extension has the same list
# (Places.kt): keep the two in step.
KINDS = ["shelter", "water", "supermarket", "shop", "food", "fuel", "coffee", "bike", "train", "toilets"]

# Shelter types that are no use on a ride. A shelter with no type given is kept.
SHELTER_DROP = {"public_transport", "field_shelter", "changing_rooms", "sun_shelter", "smoking_shelter",
                "bicycle_parking", "shopping_cart", "building", "boat_shelter", "bus_stop", "platform"}
CLOSED = {"private", "no"}

SERVERS = ["https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter",
           "https://maps.mail.ru/osm/tools/overpass/api/interpreter"]


def kind_of(row):
    """Which icon a downloaded row gets, or None to leave it out."""
    if row.get("access") in CLOSED:
        return None
    a, shop = row.get("amenity"), row.get("shop")
    if a == "shelter":
        return None if row.get("shelter_type") in SHELTER_DROP else "shelter"
    if row.get("tourism") in ("wilderness_hut", "alpine_hut"):
        return "shelter"
    if a in ("drinking_water", "water_point"):
        return "water"
    if row.get("drinking_water") == "yes" and (a == "fountain" or row.get("natural") == "spring"
                                               or row.get("man_made") == "water_tap"):
        return "water"
    if shop == "supermarket":
        return "supermarket"
    if shop in ("convenience", "general", "grocery"):
        return "shop"
    if a in ("fast_food", "restaurant", "pub", "biergarten"):
        return "food"
    if a == "fuel":
        return None if row.get("automated") == "yes" else "fuel"
    if a == "cafe" or shop == "bakery":
        return "coffee"
    if shop == "bicycle" or a == "bicycle_repair_station":
        return "bike"
    if row.get("railway") in ("station", "halt"):
        return None if row.get("station") in ("subway", "light_rail", "monorail", "funicular", "miniature") else "train"
    if a == "toilets":
        return "toilets"
    return None


def query(region, part):
    head = '[out:csv(::lat,::lon,%s;false;"\\t")][timeout:600][maxsize:1073741824];\n' % ",".join(COLUMNS)
    area = 'area[%s][boundary=administrative]->.a;\n' % REGIONS[region]
    body = "(\n" + "".join("  %s(area.a);\n" % f for f in FETCH[part]) + ");\nout center;\n"
    return head + area + body


def files(region):
    """The downloaded files of a region: one per request, or one for all from the first version."""
    whole = os.path.join(RAW, region + ".tsv")
    if os.path.exists(whole):
        return [whole]
    parts = [os.path.join(RAW, "%s.%s.tsv" % (region, part)) for part in FETCH]
    return parts if all(os.path.exists(f) for f in parts) else []


def fetch(regions):
    os.makedirs(RAW, exist_ok=True)
    for region in regions:
        if files(region):
            print("%s: already here, %.1f MB" % (region, sum(os.path.getsize(f) for f in files(region)) / 1e6), flush=True)
            continue
        for part in FETCH:
            path = os.path.join(RAW, "%s.%s.tsv" % (region, part))
            if os.path.exists(path):
                continue
            done = False
            for attempt in range(30):
                server = SERVERS[attempt % len(SERVERS)]
                tmp = path + ".part"
                r = subprocess.run(["curl", "-s", "-m", "700", "-A", "osm-place-list/1.0", "-o", tmp,
                                    "-w", "%{http_code}", server, "--data-urlencode", "data=" + query(region, part)],
                                   capture_output=True, text=True)
                ok = r.stdout.strip() == "200" and os.path.exists(tmp)
                if ok:
                    with open(tmp, errors="replace") as f:
                        first = f.readline()
                    cells = first.split("\t")
                    try:
                        float(cells[0]), float(cells[1])
                    except (ValueError, IndexError):
                        ok = False
                if ok:
                    os.replace(tmp, path)
                    print("%s, %s: %.1f MB from %s" % (region, part, os.path.getsize(path) / 1e6, server.split("/")[2]), flush=True)
                    done = True
                    break
                print("%s, %s: try %d at %s failed (http %s)" % (region, part, attempt + 1, server.split("/")[2], r.stdout.strip()), flush=True)
                if os.path.exists(tmp):
                    os.remove(tmp)
                time.sleep(20 + 5 * min(attempt, 8))
            if not done:
                print("%s, %s: GAVE UP" % (region, part), flush=True)
            time.sleep(3)


def rows(region):
    for path in files(region):
        with open(path, newline="", errors="replace") as f:
            for rec in csv.reader(f, delimiter="\t", quoting=csv.QUOTE_NONE):
                if len(rec) < 2 + len(COLUMNS):
                    continue
                try:
                    lat, lon = float(rec[0]), float(rec[1])
                except ValueError:
                    continue
                yield lat, lon, dict(zip(COLUMNS, rec[2:]))


def load():
    """{(lat, lon rounded to a few metres, kind)} over all downloaded regions, and counts per region."""
    seen = {}
    per_region = {}
    for region in REGIONS:
        if not files(region):
            print("%s: not downloaded" % region)
            continue
        counts = dict.fromkeys(KINDS, 0)
        for lat, lon, row in rows(region):
            kind = kind_of(row)
            if kind is None:
                continue
            key = (round(lat, 4), round(lon, 4), kind)       # about 10 m: drops doubles on borders
            if key not in seen:
                seen[key] = (lat, lon)
                counts[kind] += 1
        per_region[region] = counts
    return seen, per_region


def stats():
    seen, per_region = load()
    print("%-24s" % "" + "".join("%12s" % k for k in KINDS) + "%12s" % "all")
    total = dict.fromkeys(KINDS, 0)
    for region, counts in per_region.items():
        print("%-24s" % region + "".join("%12d" % counts[k] for k in KINDS) + "%12d" % sum(counts.values()))
        for k in KINDS:
            total[k] += counts[k]
    print("%-24s" % "all" + "".join("%12d" % total[k] for k in KINDS) + "%12d" % len(seen))
    size = sum(os.path.getsize(os.path.join(RAW, f)) for f in os.listdir(RAW) if f.endswith(".tsv"))
    print("downloaded: %.1f MB" % (size / 1e6))


def pack():
    seen, _ = load()
    cell = 100000                                             # 0.1 degree
    cells = {}
    for (_, _, kind), (lat, lon) in seen.items():
        mlat, mlon = int(round(lat * 1e6)), int(round(lon * 1e6))
        clat, clon = mlat // cell, mlon // cell
        dlat = (mlat - clat * cell) * 65536 // cell
        dlon = (mlon - clon * cell) * 65536 // cell
        cells.setdefault((clat, clon), []).append((dlat, dlon, KINDS.index(kind)))
    keys = sorted(cells)
    out = bytearray(b"GTAP")
    out += struct.pack("<BBiI", 1, len(KINDS), cell, len(keys))
    first = 0
    for k in keys:
        out += struct.pack("<hhI", k[0], k[1], first)
        first += len(cells[k])
    out += struct.pack("<I", first)
    for k in keys:
        for rec in sorted(cells[k]):
            out += struct.pack("<HHB", *rec)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as f:
        f.write(out)
    print("wrote %s: %d places in %d cells, %.2f MB" % (os.path.relpath(OUT, HERE), first, len(keys), len(out) / 1e6))


def main():
    what = sys.argv[1] if len(sys.argv) > 1 else ""
    if what == "fetch":
        fetch(sys.argv[2:] or list(REGIONS))
    elif what == "pack":
        pack()
    elif what == "stats":
        stats()
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
