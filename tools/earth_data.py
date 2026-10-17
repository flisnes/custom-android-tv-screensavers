#!/usr/bin/env python3
"""Build the Earth height-map assets for the contour screensaver scenes (the Earth twins of the moon scenes).

Source: NOAA ETOPO 2022, 60 arc-second global relief, ice surface (public domain), GeoTIFF, float32 metres
relative to mean sea level, land topography and ocean bathymetry in one grid (21600 x 10800, 466 MB):
  https://www.ngdc.noaa.gov/mgg/global/relief/ETOPO2022/data/60s/60s_surface_elev_gtif/ETOPO_2022_v1_60s_N90W180_surface.tif

The source is much finer than the screen can use, so it is box-downsampled to grids no larger than the moon's,
keeping the APK, the Java heap (globe) and the memory-mapped zoom grid at the moon scenes' footprint:

Outputs (raw int16 LE metres, row 0 = north, col 0 = lon 0 E, 360 deg wide; same layout as tools/moon_data.py):
  app/src/main/assets/earth/etopo_12.bin   globe scenes, full sphere, 12 px/deg (5x5 box), gaussian-smoothed
  app/src/main/assets/earth/etopo_30.bin   zoom scene, lat +-LAT_LIMIT, 30 px/deg (2x2 box)
  app/src/main/assets/earth/earth.json     manifest describing both grids

Usage:  python tools/earth_data.py <dir containing ETOPO_2022_v1_60s_N90W180_surface.tif>
Needs numpy and tifffile (pip install tifffile imagecodecs).
"""
import json, os, sys
import numpy as np
import tifffile

SRC_FILE = "ETOPO_2022_v1_60s_N90W180_surface.tif"
SRC_PPD = 60
GLOBE_PPD = 12          # the globe spans ~5 screen px per degree at 1080p; 12 px/deg + blur is plenty
GLOBE_BLUR = 1.5        # gaussian sigma (texels) baked into the globe grid: 0.125 deg, as on the moon
LAT_LIMIT = 72          # zoom grid covers -LAT_LIMIT..+LAT_LIMIT degrees (reaches northern Norway)
ZOOM_PPD = 30           # ~3.7 km per pixel at the equator

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from moon_data import gaussian_blur

def box(a, f):
    h, w = a.shape[0] // f, a.shape[1] // f
    return a[:h * f, :w * f].reshape(h, f, w, f).mean(axis=(1, 3), dtype=np.float64).astype(np.float32)

def main(src):
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "earth")
    os.makedirs(out, exist_ok=True)
    manifest = {"scale_m_per_dn": 1.0, "radius_m": 6371000.0, "source": "NOAA ETOPO 2022 60s surface"}

    a = tifffile.imread(os.path.join(src, SRC_FILE)).astype(np.float32)
    # Grid-registered variants carry an extra row/column (both poles, both +-180): drop the duplicates.
    a = a[:180 * SRC_PPD, :360 * SRC_PPD]
    assert a.shape == (180 * SRC_PPD, 360 * SRC_PPD), a.shape
    a[~np.isfinite(a)] = 0
    a = np.roll(a, -180 * SRC_PPD, axis=1)          # west edge at -180 -> col 0 at lon 0 E
    print("source", a.shape, a.min(), a.max())

    g = box(a, SRC_PPD // GLOBE_PPD)
    if GLOBE_BLUR > 0:
        g = gaussian_blur(g, GLOBE_BLUR)
    g = np.rint(g).astype("<i2")
    g.tofile(os.path.join(out, "etopo_12.bin"))
    manifest["globe"] = {"file": "etopo_12.bin", "width": g.shape[1], "height": g.shape[0], "ppd": GLOBE_PPD,
                         "blur_sigma": GLOBE_BLUR, "lat_min": -90, "lat_max": 90,
                         "min_dn": int(g.min()), "max_dn": int(g.max())}
    print("globe", g.shape, g.min(), g.max())

    r0, r1 = (90 - LAT_LIMIT) * SRC_PPD, (90 + LAT_LIMIT) * SRC_PPD
    z = np.rint(box(a[r0:r1], SRC_PPD // ZOOM_PPD)).astype("<i2")
    z.tofile(os.path.join(out, "etopo_30.bin"))
    manifest["zoom"] = {"file": "etopo_30.bin", "width": z.shape[1], "height": z.shape[0], "ppd": ZOOM_PPD,
                        "lat_min": -LAT_LIMIT, "lat_max": LAT_LIMIT, "min_dn": int(z.min()), "max_dn": int(z.max())}
    print("zoom", z.shape, z.min(), z.max())

    with open(os.path.join(out, "earth.json"), "w") as fp:
        json.dump(manifest, fp, indent=2)
    print("wrote", out)

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
