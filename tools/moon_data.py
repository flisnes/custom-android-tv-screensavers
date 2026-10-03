#!/usr/bin/env python3
"""Build the moon height-map assets for the contour screensaver scenes.

Source: NASA LRO LOLA Gridded Data Records (public domain), simple-cylindrical, int16 LE,
height = DN * 0.5 m relative to a 1737.4 km sphere.
  https://imbrium.mit.edu/DATA/LOLA_GDR/CYLINDRICAL/IMG/LDEM_16.IMG   (5760 x 2880, 33 MB)
  https://imbrium.mit.edu/DATA/LOLA_GDR/CYLINDRICAL/IMG/LDEM_64.IMG   (23040 x 11520, 530 MB)

Outputs (raw int16 LE, same DN units, row 0 = north, col 0 = lon 0 E, 360 deg wide):
  app/src/main/assets/moon/ldem_16.bin   globe scenes, full sphere, 16 px/deg, gaussian-smoothed by GLOBE_BLUR texels
  app/src/main/assets/moon/ldem_32.bin   zoom scene, lat +-LAT_LIMIT, 32 px/deg (2x2 box mean of LDEM_64)
  app/src/main/assets/moon/moon.json     manifest describing both grids

Usage:  python tools/moon_data.py <dir containing LDEM_16.IMG and LDEM_64.IMG>
"""
import json, os, sys
import numpy as np

LAT_LIMIT = 64          # zoom grid covers -LAT_LIMIT..+LAT_LIMIT degrees
ZOOM_PPD = 32           # pixels per degree of the zoom grid
GLOBE_BLUR = 2.0        # gaussian sigma (texels) baked into the globe grid: at 1000 m contours the raw
                        # 16 px/deg grid is too rough and the lines merge into grey on the highlands

def gaussian_blur(a, sigma):
    """Separable gaussian; longitude wraps, latitude clamps."""
    r = max(1, int(np.ceil(sigma * 2.5)))
    k = np.exp(-np.arange(-r, r + 1) ** 2 / (2 * sigma * sigma)); k /= k.sum()
    a = a.astype(np.float32)
    out = np.zeros_like(a)
    for i, wgt in zip(range(-r, r + 1), k):
        out += np.roll(a, i, axis=1) * wgt
    a, out = out, np.zeros_like(out)
    pad = np.pad(a, ((r, r), (0, 0)), mode="edge")
    for i, wgt in zip(range(2 * r + 1), k):
        out += pad[i:i + a.shape[0]] * wgt
    return out

def main(src):
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "moon")
    os.makedirs(out, exist_ok=True)
    manifest = {"scale_m_per_dn": 0.5}

    g = np.fromfile(os.path.join(src, "LDEM_16.IMG"), dtype="<i2").reshape(2880, 5760)
    if GLOBE_BLUR > 0:
        g = np.rint(gaussian_blur(g, GLOBE_BLUR)).astype("<i2")
    g.tofile(os.path.join(out, "ldem_16.bin"))
    manifest["globe"] = {"file": "ldem_16.bin", "width": 5760, "height": 2880, "ppd": 16, "blur_sigma": GLOBE_BLUR,
                         "lat_min": -90, "lat_max": 90, "min_dn": int(g.min()), "max_dn": int(g.max())}
    print("globe", g.shape, g.min() * 0.5, g.max() * 0.5)

    z = np.fromfile(os.path.join(src, "LDEM_64.IMG"), dtype="<i2").reshape(11520, 23040)
    r0, r1 = (90 - LAT_LIMIT) * 64, (90 + LAT_LIMIT) * 64
    z = z[r0:r1]
    f = 64 // ZOOM_PPD
    h, w = z.shape[0] // f, z.shape[1] // f
    z = z.reshape(h, f, w, f).astype(np.int32).mean(axis=(1, 3))
    z = np.rint(z).astype("<i2")
    z.tofile(os.path.join(out, "ldem_32.bin"))
    manifest["zoom"] = {"file": "ldem_32.bin", "width": int(w), "height": int(h), "ppd": ZOOM_PPD,
                        "lat_min": -LAT_LIMIT, "lat_max": LAT_LIMIT, "min_dn": int(z.min()), "max_dn": int(z.max())}
    print("zoom", z.shape, z.min() * 0.5, z.max() * 0.5)

    with open(os.path.join(out, "moon.json"), "w") as fp:
        json.dump(manifest, fp, indent=2)
    print("wrote", out)

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
