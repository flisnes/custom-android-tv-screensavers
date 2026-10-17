#!/usr/bin/env python3
"""Build the landmark names for the Earth close-up scene: peaks, mountain ranges, rivers, lakes, canyons,
deserts, trenches, ridges, reefs, straits, fjords and more (shown by MapLabels.kt).

Sources:
  * Natural Earth 1:10m (public domain), GeoJSON from
    https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/<name>.geojson for
    ne_10m_geography_regions_elevation_points, ne_10m_geography_regions_polys, ne_10m_geography_marine_polys,
    ne_10m_rivers_lake_centerlines, ne_10m_lakes and ne_50m_geography_marine_polys (the seas that
    places.grid already names, skipped here).
  * The undersea features and canyons in CURATED below (Natural Earth has none): approximate axes that are
    snapped to the deepest (trench) or highest (ridge) ETOPO point within SNAP_DEG, so the name lies on the
    feature. Needs app/src/main/assets/earth/etopo_30.bin (tools/earth_data.py).

Output: app/src/main/assets/earth/landmarks.tsv, one landmark per line:
  kind <TAB> rank <TAB> name <TAB> value <TAB> lat,lon;lat,lon;...
rank: 0 = most prominent .. 9; value: elevation in m for peaks and depressions, else the feature's size
(radius of a circle of the same area, degrees; 0 for lines). One point: the name is centred there (areas use
their most inland point). Several points: a line (river, trench, ridge, or the spine of a long, thin area)
that the name is written along.

Usage:  python tools/earth_landmarks.py <dir with the .geojson files>
Needs Pillow and numpy.
"""
import json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "earth")
SNAP_DEG = 0.7

# Region polygon classes kept, and the kind they are written as.
REGION_KINDS = {"Range/mtn": "range", "Plateau": "plateau", "Desert": "desert", "Gorge": "gorge", "Valley": "valley",
                "Delta": "delta", "Wetlands": "wetland", "Basin": "basin", "Depression": "low",
                "Lowland": "plain", "Plain": "plain", "Tundra": "plain", "Foothills": "range", "Peninsula": "peninsula"}
MARINE_KINDS = {"sea": "sea", "gulf": "sea", "bay": "sea", "strait": "strait", "sound": "sea", "channel": "strait",
                "lagoon": "sea", "fjord": "fjord", "inlet": "sea", "reef": "reef"}

# kind, rank, name, axis points (lat, lon). Trenches snap to the deepest point nearby, ridges to the highest.
CURATED = [
    ("trench", 1, "Mariana Trench", [(11.0, 140.5), (11.35, 142.6), (12.8, 145.3), (15.0, 147.4), (18.0, 148.0), (21.0, 147.3), (24.0, 145.0)]),
    ("trench", 2, "Izu-Ogasawara Trench", [(25.0, 143.3), (28.0, 142.9), (31.0, 142.2), (33.8, 141.9)]),
    ("trench", 2, "Japan Trench", [(34.5, 142.0), (37.0, 143.4), (39.5, 144.1), (41.0, 144.4)]),
    ("trench", 1, "Kuril-Kamchatka Trench", [(41.5, 145.3), (44.0, 149.8), (47.0, 154.3), (50.0, 158.0), (52.5, 160.5), (54.5, 163.0)]),
    ("trench", 1, "Aleutian Trench", [(53.5, 166.0), (51.5, 175.0), (50.8, -178.0), (51.2, -172.0), (52.5, -165.0), (54.0, -158.0), (56.5, -153.0)]),
    ("trench", 2, "Philippine Trench", [(6.0, 127.2), (8.0, 127.0), (10.0, 126.7), (12.0, 126.2), (13.5, 125.5)]),
    ("trench", 3, "Ryukyu Trench", [(24.0, 126.0), (25.5, 128.5), (27.5, 130.5), (30.0, 132.0)]),
    ("trench", 1, "Tonga Trench", [(-15.5, -173.4), (-18.0, -172.9), (-20.0, -173.3), (-23.0, -174.5), (-25.5, -175.5)]),
    ("trench", 1, "Kermadec Trench", [(-26.0, -176.0), (-29.0, -176.4), (-32.0, -177.0), (-35.0, -178.0), (-37.0, -178.4)]),
    ("trench", 3, "New Hebrides Trench", [(-11.0, 165.5), (-14.0, 166.0), (-17.0, 167.0), (-20.5, 169.0)]),
    ("trench", 1, "Java Trench", [(-11.0, 118.0), (-10.8, 113.0), (-10.0, 108.0), (-8.0, 103.5), (-5.0, 100.5), (-1.0, 97.5), (3.0, 94.5)]),
    ("trench", 1, "Peru-Chile Trench", [(-5.0, -82.0), (-10.0, -80.0), (-15.0, -76.5), (-20.0, -71.3), (-25.0, -71.3), (-30.0, -72.3), (-35.0, -73.3), (-40.0, -74.6)]),
    ("trench", 2, "Middle America Trench", [(19.0, -106.0), (16.0, -99.0), (14.0, -93.5), (12.0, -89.0), (10.0, -86.5)]),
    ("trench", 2, "Puerto Rico Trench", [(19.8, -68.0), (19.8, -66.0), (19.6, -64.0), (19.0, -62.0)]),
    ("trench", 2, "South Sandwich Trench", [(-55.5, -28.0), (-57.0, -25.0), (-59.0, -24.5), (-60.5, -26.0)]),
    ("trench", 3, "Cayman Trough", [(19.2, -84.0), (19.0, -81.0), (19.3, -78.0)]),
    ("trench", 3, "Hellenic Trench", [(36.8, 20.7), (35.5, 22.5), (34.6, 24.5), (34.8, 26.5)]),
    ("trench", 3, "Manila Trench", [(14.5, 119.3), (17.0, 119.4), (19.5, 120.0)]),
    ("trench", 3, "Yap Trench", [(8.0, 137.5), (10.0, 138.6), (11.5, 139.5)]),
    ("trench", 3, "Romanche Trench", [(-0.6, -20.5), (-0.2, -18.0), (0.0, -15.5)]),
    ("ridge", 1, "Mid-Atlantic Ridge", [(62.0, -25.0), (57.0, -32.5), (52.0, -30.0), (45.0, -28.0), (38.0, -30.5), (30.0, -42.5), (20.0, -45.5),
                                         (10.0, -40.5), (1.0, -27.0), (-10.0, -13.5), (-20.0, -12.5), (-30.0, -13.5), (-40.0, -16.5), (-50.0, -8.0)]),
    ("ridge", 1, "East Pacific Rise", [(20.0, -108.5), (10.0, -104.0), (0.0, -102.5), (-10.0, -110.0), (-20.0, -113.5), (-30.0, -112.0), (-40.0, -111.5), (-50.0, -115.0)]),
    ("ridge", 2, "Southwest Indian Ridge", [(-26.0, 69.0), (-31.0, 60.0), (-37.0, 50.0), (-42.0, 42.0), (-45.0, 35.0), (-52.0, 20.0), (-54.0, 5.0)]),
    ("ridge", 2, "Southeast Indian Ridge", [(-26.0, 70.0), (-33.0, 78.0), (-40.0, 86.0), (-45.0, 98.0), (-50.0, 115.0), (-50.0, 130.0)]),
    ("ridge", 2, "Central Indian Ridge", [(-25.0, 70.0), (-18.0, 66.0), (-10.0, 66.5), (-2.0, 68.0)]),
    ("ridge", 3, "Carlsberg Ridge", [(-2.0, 68.0), (3.0, 64.0), (8.0, 59.0)]),
    ("ridge", 2, "Pacific-Antarctic Ridge", [(-55.0, -125.0), (-60.0, -150.0), (-63.0, -170.0), (-62.0, 160.0)]),
    ("ridge", 3, "Ninety East Ridge", [(5.0, 90.0), (-10.0, 88.0), (-20.0, 87.5), (-30.0, 88.0)]),
    ("ridge", 3, "Emperor Seamounts", [(32.0, 171.5), (40.0, 170.5), (48.0, 168.5), (52.0, 167.5)]),
    ("ridge", 3, "Hawaiian Ridge", [(28.0, -178.0), (25.5, -170.0), (23.0, -163.0), (20.5, -157.0)]),
    ("ridge", 3, "Walvis Ridge", [(-20.0, 10.0), (-25.0, 5.0), (-30.0, 1.0), (-35.0, -5.0)]),
    ("reef", 2, "Mesoamerican Reef", [(21.0, -86.8), (19.0, -87.5), (17.0, -88.0)]),
    ("reef", 3, "New Caledonia Barrier Reef", [(-18.5, 163.2), (-20.5, 164.0), (-22.5, 166.5)]),
    ("reef", 4, "Ningaloo Reef", [(-21.8, 113.9), (-23.3, 113.7)]),
    ("reef", 4, "Florida Reef", [(25.5, -80.15), (24.75, -80.8), (24.55, -81.8)]),
    ("reef", 4, "Andros Barrier Reef", [(25.3, -77.9), (24.3, -77.6)]),
    ("gorge", 3, "Fish River Canyon", [(-27.6, 17.6)]),
    ("gorge", 3, "Colca Canyon", [(-15.6, -71.9)]),
    ("gorge", 4, "Cotahuasi Canyon", [(-15.2, -72.9)]),
    ("gorge", 3, "Copper Canyon", [(27.5, -107.7)]),
    ("gorge", 3, "Kali Gandaki Gorge", [(28.6, 83.6)]),
    ("gorge", 4, "Tara Canyon", [(43.2, 19.2)]),
    ("gorge", 4, "Verdon Gorge", [(43.75, 6.35)]),
    ("gorge", 4, "Blyde River Canyon", [(-24.6, 30.8)]),
    ("gorge", 4, "Hells Canyon", [(45.3, -116.6)]),
    ("gorge", 4, "Iron Gates", [(44.6, 22.3)]),
    ("gorge", 4, "Indus Gorge", [(35.4, 74.6)]),
    ("gorge", 4, "Monterey Canyon", [(36.75, -122.1)]),
    ("gorge", 4, "Congo Canyon", [(-6.0, 11.0)]),
    ("gorge", 4, "Zhemchug Canyon", [(58.6, -175.0)]),
]


def zoom_grid():
    meta = json.load(open(os.path.join(OUT, "earth.json")))["zoom"]
    data = np.fromfile(os.path.join(OUT, meta["file"]), dtype="<i2").reshape(meta["height"], meta["width"])
    return data, meta


def snap(points, kind, grid):
    """Move each point to the extreme (min for trenches, max for ridges) within SNAP_DEG, if it is in the grid."""
    data, m = grid
    ppd = m["ppd"]; r = int(SNAP_DEG * ppd)
    out = []
    for lat, lon in points:
        y = int((m["lat_max"] - lat) * ppd); x = int((lon % 360) * ppd)
        if not (r <= y < m["height"] - r):
            out.append((lat, lon)); continue
        cols = [(x + dx) % m["width"] for dx in range(-r, r + 1)]
        win = data[y - r:y + r + 1][:, cols].astype(np.float32)
        yy, xx = np.mgrid[-r:r + 1, -r:r + 1]
        win[yy * yy + xx * xx > r * r] = np.nan
        i = np.nanargmin(win) if kind == "trench" else np.nanargmax(win)
        dy, dx = np.unravel_index(i, win.shape)
        out.append((m["lat_max"] - (y - r + dy + 0.5) / ppd, lon + (dx - r) / ppd))
    return out


def rdp(pts, tol):
    """Douglas-Peucker simplification of a list of (x, y)."""
    if len(pts) < 3:
        return pts
    p = np.array(pts, dtype=float)
    a, b = p[0], p[-1]
    ab = b - a; n = math.hypot(*ab)
    d = np.abs(ab[0] * (p[:, 1] - a[1]) - ab[1] * (p[:, 0] - a[0])) / n if n > 0 else np.hypot(*(p - a).T)
    i = int(np.argmax(d))
    if d[i] <= tol:
        return [pts[0], pts[-1]]
    return rdp(pts[:i + 1], tol)[:-1] + rdp(pts[i:], tol)


def polygons(geom):
    if geom["type"] == "Polygon":
        return [geom["coordinates"]]
    if geom["type"] == "MultiPolygon":
        return geom["coordinates"]
    return []


def lines(geom):
    if geom["type"] == "LineString":
        return [geom["coordinates"]]
    if geom["type"] == "MultiLineString":
        return geom["coordinates"]
    return []


def ring_area(ring):
    a = 0.0
    for (x0, y0, *_), (x1, y1, *_) in zip(ring, ring[1:] + ring[:1]):
        a += x0 * y1 - x1 * y0
    return a / 2


def area_label(polys, ppd=8):
    """(points, size_deg) for a (multi)polygon: its most inland point, or a spine polyline if it is long and thin."""
    # The largest part only (a range's outliers or an archipelago's islets would pull the label off the main body).
    poly = max(polys, key=lambda p: abs(ring_area(p[0])))
    ring = poly[0]
    lons = np.array([c[0] for c in ring]); lats = np.array([c[1] for c in ring])
    wrap = lons.max() - lons.min() > 180                    # crosses the antimeridian
    if wrap:
        lons = np.where(lons < 0, lons + 360, lons)
    k = math.cos(math.radians((lats.max() + lats.min()) / 2))   # x squeezed by cos(lat): square pixels on the ground
    x0, y1 = lons.min(), lats.max()
    dims = lambda q: (int((lons.max() - x0) * q * k) + 3, int((y1 - lats.min()) * q) + 3)
    w, h = dims(ppd)
    if w * h > 4_000_000:
        ppd = ppd * math.sqrt(4_000_000 / (w * h)); w, h = dims(ppd)
    img = Image.new("L", (w, h), 0); d = ImageDraw.Draw(img)
    to_px = lambda lo, la: ((lo - x0) * ppd * k + 1, (y1 - la) * ppd + 1)
    d.polygon([to_px(lo, la) for lo, la in zip(lons, lats)], fill=1)
    for hole in poly[1:]:
        d.polygon([to_px(c[0] + 360 if wrap and c[0] < 0 else c[0], c[1]) for c in hole], fill=0)
    m = np.asarray(img, dtype=bool)
    if m.sum() < 2:
        return [(float(lats.mean()), float(((lons.mean() + 180) % 360) - 180))], 0.0
    size = math.sqrt(m.sum() / math.pi) / ppd
    back = lambda px, py: (float(y1 - (py - 1) / ppd), float(((px - 1) / (ppd * k) + x0 + 180) % 360 - 180))
    ys, xs = np.nonzero(m)
    ev, evec = np.linalg.eigh(np.cov(np.vstack([xs, ys])))
    if ev[1] > 9 * max(ev[0], 1e-9) and len(xs) > 50:
        # Long and thin (3:1 or more): a spine through the centres of slices across the main axis.
        ax = evec[:, 1]
        t = (xs - xs.mean()) * ax[0] + (ys - ys.mean()) * ax[1]
        n = max(3, min(24, int((t.max() - t.min()) / (2 * math.sqrt(ev[0]) + 1))))
        edges = np.linspace(t.min(), t.max(), n + 1)
        pts = []
        for a, b in zip(edges, edges[1:]):
            s = (t >= a) & (t <= b)
            if s.any():
                pts.append(back(xs[s].mean(), ys[s].mean()))
        return pts, size
    # Most inland point: erode until nothing is left; of the last pixels, the one nearest the centroid.
    cur = m.copy(); last = cur
    while cur.any():
        last = cur
        nxt = cur.copy()
        nxt[1:] &= cur[:-1]; nxt[:-1] &= cur[1:]; nxt[:, 1:] &= cur[:, :-1]; nxt[:, :-1] &= cur[:, 1:]
        cur = nxt
    ly, lx = np.nonzero(last)
    i = int(np.argmin((lx - xs.mean()) ** 2 + (ly - ys.mean()) ** 2))
    return [back(lx[i], ly[i])], size


def clean(s):
    """Collapse whitespace; of "English/Maori"-style double names keep the first."""
    return " ".join(s.split("/")[0].split()) if s else ""


def main(src):
    load = lambda n: json.load(open(os.path.join(src, n + ".geojson"), encoding="utf-8"))["features"]
    out = []   # (kind, rank, name, value, [(lat, lon)])

    for f in load("ne_10m_geography_regions_elevation_points"):
        p = f["properties"]; name = clean(p.get("name_en") or p.get("name"))
        cla = p["featurecla"]
        if not name or cla not in ("mountain", "depression", "spot elevation", "pass"):
            continue
        elev = int(p["elevation"] or 0)
        kind = "pass" if cla == "pass" else "low" if cla == "depression" or elev < 0 else "peak"
        if cla == "spot elevation" and kind == "peak" and elev < 3000:
            continue                         # research stations and the like
        lon, lat = f["geometry"]["coordinates"][:2]
        out.append((kind, int(p["scalerank"]), name, elev, [(lat, lon)]))

    for f in load("ne_10m_geography_regions_polys"):
        p = f["properties"]; kind = REGION_KINDS.get(p["FEATURECLA"]); name = clean(p.get("NAME_EN") or p.get("NAME"))
        if not kind or not name:
            continue
        pts, size = area_label(polygons(f["geometry"]))
        out.append((kind, int(p["SCALERANK"]), name, round(size, 2), pts))

    named_seas = {x["properties"]["name"] for x in load("ne_50m_geography_marine_polys")}
    for f in load("ne_10m_geography_marine_polys"):
        p = f["properties"]; kind = MARINE_KINDS.get(p["featurecla"]); name = clean(p.get("name"))
        if not kind or not name or name in named_seas:
            continue
        name = name.title() if name.isupper() else name
        pts, size = area_label(polygons(f["geometry"]))
        out.append((kind, int(p["scalerank"]), name, round(size, 2), pts))

    for f in load("ne_10m_lakes"):
        p = f["properties"]; name = clean(p.get("name"))          # "Lake Erie" (name_en is just "Erie")
        if not name or p.get("featurecla") not in ("Lake", "Alkaline Lake", "Reservoir") or int(p["scalerank"]) > 7:
            continue
        pts, size = area_label(polygons(f["geometry"]))
        if len(pts) > 1:
            pts = [pts[len(pts) // 2]]       # lakes are named across the middle, not along a spine
        out.append(("lake", int(p["scalerank"]), name, round(size, 2), pts))

    for f in load("ne_10m_rivers_lake_centerlines"):
        p = f["properties"]; name = clean(p.get("name_en") or p.get("name"))
        if p["featurecla"] != "River" or not name or int(p["scalerank"]) > 7:
            continue
        for ln in lines(f["geometry"]):
            pts = [(c[1], c[0]) for c in rdp([tuple(c[:2]) for c in ln], 0.04)]
            if len(pts) >= 2:
                out.append(("river", int(p["scalerank"]), name, 0, pts))

    grid = zoom_grid()
    for kind, rank, name, pts in CURATED:
        out.append((kind, rank, name, 0, snap(pts, kind, grid) if kind in ("trench", "ridge") else pts))

    out.sort(key=lambda r: (r[1], r[0], r[2]))
    with open(os.path.join(OUT, "landmarks.tsv"), "w", encoding="utf-8", newline="\n") as fo:
        for kind, rank, name, value, pts in out:
            coords = ";".join(f"{la:.2f},{lo:.2f}" for la, lo in pts)
            fo.write(f"{kind}\t{rank}\t{name}\t{value}\t{coords}\n")
    kinds = {}
    for r in out:
        kinds[r[0]] = kinds.get(r[0], 0) + 1
    print(len(out), "landmarks:", ", ".join(f"{k} {n}" for k, n in sorted(kinds.items())))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
