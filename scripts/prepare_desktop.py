#!/usr/bin/env python3
"""Turn jobs/<id>.json into an Electron project in desktop-work/."""
import base64, io, json, os, re, shutil, sys

job_id = sys.argv[1]
job = json.load(open(os.path.join("jobs", job_id + ".json"), encoding="utf-8"))
name = (job.get("name") or "My App").strip() or "My App"
package_id = job.get("packageId") or "app.sublite.myapp"

shutil.rmtree("desktop-work", ignore_errors=True)
shutil.copytree("desktop", "desktop-work")
os.makedirs(os.path.join("desktop-work", "app"), exist_ok=True)

if job.get("sourceType") == "url":
    start_url = (job.get("sourceValue") or "https://example.com").strip()
    if not start_url.startswith("http"):
        start_url = "https://" + start_url
else:
    with open(os.path.join("desktop-work", "app", "index.html"), "w", encoding="utf-8") as f:
        f.write(job.get("sourceValue") or "<h1>Hello from Sublite</h1>")
    start_url = ""

json.dump({"name": name, "startUrl": start_url, "features": job.get("features") or {}},
          open(os.path.join("desktop-work", "config.json"), "w", encoding="utf-8"))

pkg_path = os.path.join("desktop-work", "package.json")
pkg = json.load(open(pkg_path, encoding="utf-8"))
safe = re.sub(r"[^A-Za-z0-9 _-]", "", name).strip() or "App"
pkg["productName"] = safe
pkg["build"]["appId"] = package_id
pkg["build"]["productName"] = safe
json.dump(pkg, open(pkg_path, "w", encoding="utf-8"), indent=2)

icon_out = os.path.join("desktop-work", "icon.png")
icon = job.get("icon") or ""
try:
    from PIL import Image
    if icon.startswith("data:") and "," in icon:
        img = Image.open(io.BytesIO(base64.b64decode(icon.split(",", 1)[1]))).convert("RGBA")
    else:
        img = Image.new("RGBA", (256, 256), (13, 15, 13, 255))
        for x in range(40, 216):
            for y in range(40, 216):
                img.putpixel((x, y), (155, 225, 93, 255))
    img = img.resize((256, 256))
    img.save(icon_out)
except Exception as exc:
    print("icon failed:", exc)
    pkg["build"]["win"].pop("icon", None)
    json.dump(pkg, open(pkg_path, "w", encoding="utf-8"), indent=2)

print("prepared desktop", name, start_url or "local html")
