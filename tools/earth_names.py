#!/usr/bin/env python3
"""Build the place-name lookup for the Earth close-up scene (the country or sea the view is over).

Source: Natural Earth 1:50m (public domain), admin-0 countries and marine polygons, GeoJSON:
  https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_admin_0_countries.geojson
  https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_geography_marine_polys.geojson

Outputs (small, so they are committed):
  app/src/main/assets/earth/places.tsv      line i = place i (1-based): kind ("land" / "sea") <TAB> name
  app/src/main/assets/earth/places.grid   gzip of a uint16 LE grid, PPD px/deg, row 0 = 90 N, col 0 = lon 0 E,
                                            360 x 180 deg; each cell holds the place index (0 = unnamed)
(Gzip-compressed but not named .gz: aapt drops .gz assets from the APK.)
Countries are painted over seas, and smaller polygons over larger ones (enclaves, seas inside oceans).

Usage:  python tools/earth_names.py <dir containing the two .geojson files>
Needs Pillow and numpy.
"""
import gzip, json, os, sys
import numpy as np
from PIL import Image, ImageDraw

PPD = 4                 # 0.25 deg cells: the close-up shows 10-18 deg, so 40+ cells across the view
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "earth")

def polygons(geom):
    if geom["type"] == "Polygon":
        return [geom["coordinates"]]
    if geom["type"] == "MultiPolygon":
        return geom["coordinates"]
    return []

def ring_area(ring):
    a = 0.0
    for (x0, y0), (x1, y1) in zip(ring, ring[1:] + ring[:1]):
        a += x0 * y1 - x1 * y0
    return abs(a) / 2

def country_name(p):
    # NAME_LONG where it is the shorter common form ("United States", "China"), else NAME_EN ("Cape Verde").
    long, en = p["NAME_LONG"], p["NAME_EN"]
    return long if len(long) <= len(en) and "/" not in long else en

def sea_name(p):
    n = p["name"]
    return n.title() if n.isupper() else n

def main(src):
    countries = json.load(open(os.path.join(src, "ne_50m_admin_0_countries.geojson"), encoding="utf-8"))
    marine = json.load(open(os.path.join(src, "ne_50m_geography_marine_polys.geojson"), encoding="utf-8"))
    places, index = [], {}
    def place(kind, name):
        if (kind, name) not in index:
            places.append((kind, name)); index[(kind, name)] = len(places)
        return index[(kind, name)]

    shapes = []   # (layer, -area, place index, outer ring)
    for f in marine["features"]:
        if f["properties"]["featurecla"] == "river":
            continue
        i = place("sea", sea_name(f["properties"]))
        for poly in polygons(f["geometry"]):
            shapes.append((0, -ring_area(poly[0]), i, poly[0]))
    for f in countries["features"]:
        i = place("land", country_name(f["properties"]))
        for poly in polygons(f["geometry"]):
            shapes.append((1, -ring_area(poly[0]), i, poly[0]))
    shapes.sort(key=lambda s: (s[0], s[1]))

    img = Image.new("I", (360 * PPD, 180 * PPD), 0)
    draw = ImageDraw.Draw(img)
    for _, _, i, ring in shapes:
        pts = [((lon + 180) * PPD, (90 - lat) * PPD) for lon, lat in ring]
        draw.polygon(pts, fill=i)
    grid = np.roll(np.asarray(img, dtype=np.int32), -180 * PPD, axis=1).astype("<u2")   # col 0 = lon 0 E

    with gzip.open(os.path.join(OUT, "places.grid"), "wb", compresslevel=9) as f:
        f.write(grid.tobytes())
    with open(os.path.join(OUT, "places.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for kind, name in places:
            f.write(f"{kind}\t{name}\n")
    land = sum(1 for k, _ in places if k == "land")
    print(f"{len(places)} places ({land} countries), grid {grid.shape[1]}x{grid.shape[0]}, "
          f"{np.count_nonzero(grid == 0) / grid.size:.1%} unnamed")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
