#!/usr/bin/env python3
"""Build the crater-name list for the moon close-up scene's labels.

Source: IAU Gazetteer of Planetary Nomenclature (USGS Astrogeology, public domain), Moon centre points:
  https://asc-planetarynames-data.s3.us-west-2.amazonaws.com/MOON_nomenclature_center_pts.zip

Output: app/src/main/assets/moon/craters.tsv   name <TAB> lat (deg N) <TAB> lon (deg E, 0..360) <TAB> diameter (km)
Only named craters (type "Crater, craters"); lettered satellite craters ("Copernicus A") are left out.
Sorted largest first. Small (~50 KB), so it is committed.

Usage:  python tools/moon_names.py <dir containing MOON_nomenclature_center_pts.dbf (unzipped)>
"""
import os, struct, sys

def read_dbf(path):
    with open(path, "rb") as f:
        hdr = f.read(32)
        n, hl, rl = struct.unpack("<IHH", hdr[4:12])
        fields = []
        while True:
            d = f.read(32)
            if d[0] == 0x0D:
                break
            fields.append((d[:11].split(b"\0")[0].decode(), d[16]))
        f.seek(hl)
        for _ in range(n):
            r = f.read(rl)
            if r[0:1] == b"*":          # deleted record
                continue
            o, rec = 1, {}
            for name, length in fields:
                rec[name] = r[o:o + length].decode("utf-8", "replace").strip(); o += length
            yield rec

def main(src):
    rows = []
    for r in read_dbf(os.path.join(src, "MOON_nomenclature_center_pts.dbf")):
        if not r["type"].startswith("Crater"):
            continue
        rows.append((r["name"], float(r["center_lat"]), float(r["center_lon"]) % 360.0, float(r["diameter"])))
    rows.sort(key=lambda x: -x[3])
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "moon", "craters.tsv")
    with open(out, "w", encoding="utf-8", newline="\n") as fp:
        for name, lat, lon, d in rows:
            fp.write(f"{name}\t{lat:.3f}\t{lon:.3f}\t{d:.1f}\n")
    print(len(rows), "craters ->", out)

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
