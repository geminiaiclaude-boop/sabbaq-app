"""Checks app resources without Gradle.

1. Every R.<type>.<name> in Kotlin and every @<type>/<name> in XML exists in app/src/main/res.
2. values/strings.xml and values-ar/strings.xml have the same keys and the same format
   placeholders (%1$d, %s ...) per key, and the same plural names.
3. aapt2 compiles every resource file (syntax, escaping, unknown tags).
Exit code 0 when all pass; problems are printed one per line.
"""
import glob
import os
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "app", "src", "main", "res")
problems = []


def defined():
    names = {}
    for d in os.listdir(RES):
        kind = d.split("-")[0]
        path = os.path.join(RES, d)
        if kind == "values":
            for f in glob.glob(os.path.join(path, "*.xml")):
                for el in ET.parse(f).getroot():
                    n = el.get("name")
                    if not n:
                        continue
                    t = el.tag
                    if t == "item":
                        t = el.get("type") or "item"
                    if t == "string-array":
                        t = "array"
                    names.setdefault(t, set()).add(n.replace(".", "_"))
        else:
            for f in os.listdir(path):
                names.setdefault(kind, set()).add(f.split(".")[0])
    return names


def strings(path):
    out, plurals = {}, set()
    for el in ET.parse(path).getroot():
        if el.tag == "string":
            out[el.get("name")] = "".join(el.itertext())
        elif el.tag == "plurals":
            plurals.add(el.get("name"))
            for item in el:
                out[el.get("name") + ":" + item.get("quantity")] = "".join(item.itertext())
    return out, plurals


names = defined()
code = glob.glob(os.path.join(ROOT, "app", "src", "main", "java", "**", "*.kt"), recursive=True)
for f in code:
    text = open(f, encoding="utf-8").read()
    for kind, name in re.findall(r"(?<!android\.)\bR\.([a-z]+)\.([A-Za-z0-9_]+)", text):
        if name not in names.get(kind, set()):
            problems.append(f"missing R.{kind}.{name} used in {os.path.relpath(f, ROOT)}")
for f in glob.glob(os.path.join(RES, "**", "*.xml"), recursive=True) + [os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")]:
    text = open(f, encoding="utf-8").read()
    for kind, name in re.findall(r"@(string|drawable|mipmap|font|xml|color|style|raw|plurals|dimen)/([A-Za-z0-9_.]+)", text):
        if kind == "style" or "android" in name:
            continue
        if name.replace(".", "_") not in names.get(kind, set()):
            problems.append(f"missing @{kind}/{name} referenced in {os.path.relpath(f, ROOT)}")

en, en_pl = strings(os.path.join(RES, "values", "strings.xml"))
ar, ar_pl = strings(os.path.join(RES, "values-ar", "strings.xml"))
for k in sorted(set(en) - set(ar)):
    if ":" not in k:
        problems.append(f"string {k} has no Arabic")
for k in sorted(set(ar) - set(en)):
    if ":" not in k:
        problems.append(f"string {k} exists only in Arabic")
for p in sorted(en_pl ^ ar_pl):
    problems.append(f"plural {p} not in both languages")
fmt = re.compile(r"%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXc]")
for k in set(en) & set(ar):
    if ":" in k:
        continue
    a = sorted(m.group(0) for m in fmt.finditer(en[k]))
    b = sorted(m.group(0) for m in fmt.finditer(ar[k]))
    if a != b:
        problems.append(f"string {k}: placeholders differ en={a} ar={b}")

sdk = None
lp = os.path.join(ROOT, "local.properties")
if os.path.exists(lp):
    for line in open(lp, encoding="utf-8"):
        if line.startswith("sdk.dir="):
            sdk = line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "\\")
aapt2 = sorted(glob.glob(os.path.join(sdk or "", "build-tools", "*", "aapt2.exe")))
if not aapt2:
    problems.append("aapt2 not found; resource syntax not compiled")
else:
    with tempfile.TemporaryDirectory() as out:
        r = subprocess.run([aapt2[-1], "compile", "--dir", RES, "-o", os.path.join(out, "res.zip")],
                           capture_output=True, text=True, encoding="utf-8", errors="replace")
        if r.returncode != 0:
            problems += ["aapt2: " + l for l in (r.stderr or r.stdout).splitlines() if l.strip()]

for p in problems:
    print(p)
print(f"resource check: {len(problems)} problem(s); {len(en)} en / {len(ar)} ar strings")
sys.exit(1 if problems else 0)
