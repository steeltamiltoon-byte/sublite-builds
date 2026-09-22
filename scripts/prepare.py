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

# launcher icon
icon = job.get("icon") or ""
target = os.path.join(main, "res", "mipmap-xxxhdpi", "ic_launcher.png")
made = False
if "," in icon and icon.startswith("data:"):
    try:
        raw = base64.b64decode(icon.split(",", 1)[1])
        open("icon.src", "wb").write(raw)
        subprocess.check_call(["convert", "icon.src[0]", "-resize", "192x192!", target])
        made = True
    except Exception as exc:
        print("icon conversion failed:", exc)
if not made:
    letter = (name.strip()[:1] or "S").upper()
    subprocess.check_call([
        "convert", "-size", "192x192", "xc:#0d0f0d", "-fill", "#9BE15D",
        "-pointsize", "120", "-gravity", "center", "-annotate", "0", letter, target,
    ])

print("prepared", name, package_id, start_url)
