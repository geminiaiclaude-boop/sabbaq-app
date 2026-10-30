#!/usr/bin/env python3
"""Builds Sabbaq's offline city maps and, with --upload, publishes them.

For each city in cities_sa.json: cut a PMTiles archive from the newest Protomaps OpenStreetMap
build (only the city's tiles are fetched, by HTTP range requests), check it, and write
manifest.json next to the archives with each file's size and SHA-256. The app reads the manifest
and verifies every download against it.

    python scripts/maps/build_city_packs.py --out D:/maps --pmtiles C:/Tools/pmtiles/pmtiles.exe
    python scripts/maps/build_city_packs.py --out D:/maps --upload     # needs the two variables below

Upload goes to the public Supabase Storage bucket `maps` (migration 0019) under `sa/`, archives
first and the manifest last, so a phone never reads a manifest naming a file not yet there. It
needs SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY in the environment; the key is never written
anywhere. Old archives are removed after the new manifest is up.
"""
import argparse
import datetime
import hashlib
import json
import os
import subprocess
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
BUILDS = "https://build-metadata.protomaps.dev/builds.json"
BUILD_URL = "https://build.protomaps.com/{key}"
SCHEMA = 1
MAX_BYTES = 50 * 1024 * 1024  # Supabase free plan per-file limit
ATTRIBUTION = "© OpenStreetMap contributors · Protomaps"


def newest_build():
    req = urllib.request.Request(BUILDS, headers={"User-Agent": "sabbaq-map-builder/1"})
    with urllib.request.urlopen(req, timeout=30) as r:
        builds = json.load(r)
    # Version 4 builds match the bundled @protomaps/basemaps style (v5).
    v4 = [b for b in builds if str(b.get("version", "")).startswith("4.")]
    return sorted(v4, key=lambda b: b["key"])[-1]["key"]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def extract(pmtiles, build_key, city, out_dir):
    stamp = build_key.split(".")[0]
    name = f"{city['id']}-{stamp}.pmtiles"
    path = os.path.join(out_dir, name)
    if not os.path.exists(path):
        w, s, e, n = city["bbox"]
        tmp = path + ".part"
        subprocess.run([pmtiles, "extract", BUILD_URL.format(key=build_key), tmp, f"--bbox={w},{s},{e},{n}"],
                       check=True, capture_output=True)
        subprocess.run([pmtiles, "verify", tmp], check=True, capture_output=True)
        os.replace(tmp, path)
    size = os.path.getsize(path)
    if size > MAX_BYTES:
        sys.exit(f"{name} is {size} bytes, above the {MAX_BYTES} storage limit; split the city box")
    return {"id": city["id"], "name_ar": city["name_ar"], "name_en": city["name_en"], "bbox": city["bbox"],
            "file": name, "bytes": size, "sha256": sha256(path)}


def upload(path, object_name, content_type, cache):
    base = os.environ["SUPABASE_URL"].rstrip("/")
    key = os.environ["SUPABASE_SERVICE_ROLE_KEY"]
    with open(path, "rb") as f:
        data = f.read()
    req = urllib.request.Request(f"{base}/storage/v1/object/maps/{object_name}", data=data, method="POST", headers={
        "Authorization": f"Bearer {key}", "apikey": key, "Content-Type": content_type,
        "x-upsert": "true", "Cache-Control": cache})
    with urllib.request.urlopen(req, timeout=600) as r:
        if r.status not in (200, 201):
            sys.exit(f"upload {object_name}: HTTP {r.status}")


def remove_old(keep):
    base = os.environ["SUPABASE_URL"].rstrip("/")
    key = os.environ["SUPABASE_SERVICE_ROLE_KEY"]
    headers = {"Authorization": f"Bearer {key}", "apikey": key, "Content-Type": "application/json"}
    req = urllib.request.Request(f"{base}/storage/v1/object/list/maps", method="POST", headers=headers,
                                 data=json.dumps({"prefix": "sa/", "limit": 1000}).encode())
    with urllib.request.urlopen(req, timeout=60) as r:
        names = [o["name"] for o in json.load(r)]
    old = [f"sa/{n}" for n in names if n.endswith(".pmtiles") and n not in keep]
    if old:
        req = urllib.request.Request(f"{base}/storage/v1/object/maps", method="DELETE", headers=headers,
                                     data=json.dumps({"prefixes": old}).encode())
        urllib.request.urlopen(req, timeout=60).close()
    return old


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--pmtiles", default="pmtiles")
    ap.add_argument("--build", help="Protomaps build key, e.g. 20261030.pmtiles (default: newest v4)")
    ap.add_argument("--only", help="comma-separated city ids")
    ap.add_argument("--upload", action="store_true")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    cities = json.load(open(os.path.join(HERE, "cities_sa.json"), encoding="utf-8"))["cities"]
    if a.only:
        cities = [c for c in cities if c["id"] in a.only.split(",")]
    build = a.build or newest_build()
    packs = []
    for c in cities:
        p = extract(a.pmtiles, build, c, a.out)
        print(f"{p['id']:10} {p['bytes'] / 1e6:6.1f} MB  {p['file']}")
        packs.append(p)
    manifest = {"schema": SCHEMA, "build": build.split(".")[0],
                "generated_at": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                "attribution": ATTRIBUTION, "packs": packs}
    mpath = os.path.join(a.out, "manifest.json")
    with open(mpath, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    print(f"total {sum(p['bytes'] for p in packs) / 1e6:.1f} MB in {len(packs)} packs; manifest {mpath}")
    if a.upload:
        for p in packs:
            upload(os.path.join(a.out, p["file"]), f"sa/{p['file']}", "application/octet-stream", "max-age=31536000")
        upload(mpath, "sa/manifest.json", "application/json", "max-age=300")
        removed = remove_old({p["file"] for p in packs})
        print(f"uploaded {len(packs)} packs and the manifest; removed {len(removed)} old archives")


if __name__ == "__main__":
    main()
