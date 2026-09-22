#!/usr/bin/env python3
"""Turn jobs/<id>.json into a buildable Android project in work/."""
import base64, json, os, re, shutil, subprocess, sys
from xml.sax.saxutils import escape

job_id = sys.argv[1]
job = json.load(open(os.path.join("jobs", job_id + ".json")))

name = job.get("name") or "My App"
package_id = job.get("packageId") or "app.sublite.myapp"
if not re.match(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$", package_id):
    package_id = "app.sublite.myapp"

shutil.rmtree("work", ignore_errors=True)
shutil.copytree("template", "work")

main = os.path.join("work", "app", "src", "main")
os.makedirs(os.path.join(main, "assets", "www"), exist_ok=True)
os.makedirs(os.path.join(main, "res", "mipmap-xxxhdpi"), exist_ok=True)
os.makedirs(os.path.join(main, "res", "values"), exist_ok=True)

if job.get("sourceType") == "url":
    start_url = (job.get("sourceValue") or "https://example.com").strip()
    if not start_url.startswith("http"):
        start_url = "https://" + start_url
else:
    html = job.get("sourceValue") or "<h1>Hello from Sublite</h1>"
    with open(os.path.join(main, "assets", "www", "index.html"), "w") as f:
        f.write(html)
    start_url = "file:///android_asset/www/index.html"

# applicationId + start url
gradle_path = os.path.join("work", "app", "build.gradle")
gradle = open(gradle_path).read().replace("__PACKAGE_ID__", package_id)
open(gradle_path, "w").write(gradle)

activity = os.path.join(main, "java", "app", "sublite", "wrapper", "MainActivity.java")
src = open(activity).read().replace("__START_URL__", start_url.replace('"', '%22'))
open(activity, "w").write(src)

with open(os.path.join(main, "res", "values", "strings.xml"), "w") as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources><string name="app_name">'
            + escape(name) + "</string></resources>\n")

# launcher icon — write every density so every launcher picks it up
DENSITIES = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]
for suffix, _px in DENSITIES:
    os.makedirs(os.path.join(main, "res", "mipmap-" + suffix), exist_ok=True)

def targets():
    return [(os.path.join(main, "res", "mipmap-" + s, "ic_launcher.png"), px)
            for s, px in DENSITIES]

icon = job.get("icon") or ""
made = False
if icon.startswith("data:") and "," in icon:
    try:
        raw = base64.b64decode(icon.split(",", 1)[1])
        open("icon.src", "wb").write(raw)
        tool = shutil.which("magick") or shutil.which("convert")
        if tool:
            for path, px in targets():
                subprocess.check_call([
                    tool, "icon.src[0]", "-background", "none",
                    "-resize", "%dx%d^" % (px, px),
                    "-gravity", "center", "-extent", "%dx%d" % (px, px),
                    "PNG32:" + path,
                ])
            made = True
            print("icon rendered with", tool)
        else:
            print("no imagemagick available")
    except Exception as exc:
        print("icon conversion failed:", exc)

if not made:
    # pure-python fallback icon: dark square with a lime rounded block
    import struct, zlib

    def solid_png(size):
        rows = bytearray()
        for y in range(size):
            rows.append(0)
            pad = max(4, size // 7)
            for x in range(size):
                inner = pad <= x < size - pad and pad <= y < size - pad
                rows += bytes((155, 225, 93) if inner else (13, 15, 13))

        def chunk(tag, data):
            return (struct.pack(">I", len(data)) + tag + data
                    + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

        return (b"\x89PNG\r\n\x1a\n"
                + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(bytes(rows), 9))
                + chunk(b"IEND", b""))

    for path, px in targets():
        open(path, "wb").write(solid_png(px))

print("prepared", name, package_id, start_url)
