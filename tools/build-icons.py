#!/usr/bin/env python3
"""Adapt the checked-in mcpy Icon Composer layers. Requires rsvg-convert for PNG exports."""
import base64
import hashlib
import json
import math
import re
import subprocess
from pathlib import Path
from xml.etree import ElementTree as ET
from xml.sax.saxutils import quoteattr

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "design/icons/source/mcpy.icon"
RES = ROOT / "app/src/main/res"
OUT = ROOT / "design/icons"
ANDROID = "http://schemas.android.com/apk/res/android"
MARK_SCALE = 0.8  # Optical padding: scale the cube and prompt together about the canvas center.
layers = []
for filename in ("01-cube.svg", "02-prompt.svg"):
    svg = ET.parse(SOURCE / "Assets" / filename).getroot()
    assert svg.get("viewBox") == "0 0 1024 1024"
    layers.append([(p.attrib["d"], p.attrib["fill"]) for p in svg])
paths = layers[0] + layers[1]
vertices = []
for path, _ in layers[0]:
    numbers = [float(v) for v in re.findall(r"[-+]?\d+(?:\.\d+)?", path)]
    vertices.extend(zip(numbers[::2], numbers[1::2]))
safe_radius = max(math.hypot(x-512, y-512) for x,y in vertices) * 108 / 1024 * MARK_SCALE
assert safe_radius <= 33, "Cube exceeds Android adaptive icon safe circle"
config = json.loads((SOURCE / "icon.json").read_text())["fill"]
colors = ["#" + "".join(f"{round(float(v)*255):02X}" for v in c.split(":")[1].split(",")[:3])
          for c in config["linear-gradient"]]
start, stop = config["orientation"]["start"], config["orientation"]["stop"]
outer = "M512 224 L776 376 L776 664 L512 816 L248 664 L248 376 Z"
seams = "M248 368 L512 520 L776 368 L776 384 L520 532 L520 816 L504 816 L504 532 L248 384 Z"
# The source prompt uses overlapping subsegments with nonzero fill. Flatten the
# inner corner before using it as an even-odd cutout, otherwise a tiny filled
# triangle appears in themed icons. This retains the original outer contour.
overlap = "L392.00000 547.00000 L392.00000 562.00000 L380.68092 571.84268"
assert overlap in layers[1][0][0], "Prompt source changed: review monochrome contour adaptation"
prompt_cutout = layers[1][0][0].replace(overlap, "L359.07859 547.00000")
mono = outer + " " + prompt_cutout + " Z " + layers[1][1][0] + " Z " + seams


def write(relative, content):
    target = RES / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content + "\n")


def vector(content, size=108):
    return (f'<vector xmlns:android="{ANDROID}" android:width="{size}dp" android:height="{size}dp" '
            'android:viewportWidth="1024" android:viewportHeight="1024">\n' + content + '\n</vector>')


def vector_paths():
    return (f'    <group android:pivotX="512" android:pivotY="512" android:scaleX="{MARK_SCALE}" android:scaleY="{MARK_SCALE}">\n'
            + "\n".join(f'        <path android:fillColor="{color}" android:pathData={quoteattr(path)}/>' for path, color in paths)
            + '\n    </group>')


write("drawable/ic_launcher_foreground.xml", vector(vector_paths()))
write("drawable/ic_splash.xml", vector(vector_paths(), 288))
write("drawable/ic_launcher_monochrome.xml", vector(
    f'    <group android:pivotX="512" android:pivotY="512" android:scaleX="{MARK_SCALE}" android:scaleY="{MARK_SCALE}"><clip-path android:pathData="{outer}"/>\n'
    f'        <path android:fillColor="#FFFFFFFF" android:fillType="evenOdd" android:pathData={quoteattr(mono)}/>\n'
    '    </group>'))
write("drawable/ic_launcher_background.xml", f'''<vector xmlns:android="{ANDROID}" xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp" android:viewportWidth="1024" android:viewportHeight="1024">
    <path android:pathData="M0 0H1024V1024H0Z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="{start['x']*1024}" android:startY="{start['y']*1024}"
                android:endX="{stop['x']*1024}" android:endY="{stop['y']*1024}"
                android:startColor="{colors[0]}" android:endColor="{colors[1]}"/>
        </aapt:attr>
    </path>
</vector>''')
for api in (26, 33):
    for name in ("ic_launcher", "ic_launcher_round"):
        write(f"mipmap-anydpi-v{api}/{name}.xml", f'''<adaptive-icon xmlns:android="{ANDROID}">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
''' + ('    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n' if api == 33 else '') + '</adaptive-icon>')

gradient = (f'<linearGradient id="bg" x1="{start["x"]}" y1="{start["y"]}" x2="{stop["x"]}" y2="{stop["y"]}">'
            f'<stop stop-color="{colors[0]}"/><stop offset="1" stop-color="{colors[1]}"/></linearGradient>')
svg_paths = "".join(f'<path fill="{color}" d={quoteattr(path)}/>' for path, color in paths)


def svg(body, defs=""):
    return '<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024"><defs>' + gradient + defs + '</defs>' + body + '</svg>'


def render(source, target, size):
    target.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["rsvg-convert", "-w", str(size), "-h", str(size), "-o", str(target)], input=source.encode(), check=True)


# Legacy launcher canvas: 48dp, 2dp padding. Android 8+ selects the adaptive XML instead.
for suffix, shape in (("", '<rect x="43" y="43" width="938" height="938" rx="205" fill="url(#bg)"/>'),
                      ("_round", '<circle cx="512" cy="512" r="469" fill="url(#bg)"/>')):
    artwork = svg(shape + f'<g transform="translate(512 512) scale({1.374*MARK_SCALE}) translate(-512 -512)">' + svg_paths + '</g>')
    for density, size in (("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)):
        render(artwork, RES / f"mipmap-{density}/ic_launcher{suffix}.png", size)

# Store artwork is full bleed, without a baked platform mask or outer shadow.
store = svg(f'<rect width="1024" height="1024" fill="url(#bg)"/><g transform="translate(512 512) scale({1.5*MARK_SCALE}) translate(-512 -512)">' + svg_paths + '</g>')
(OUT / "store-icon.svg").write_text(store + "\n")
render(store, OUT / "store-icon-512.png", 512)

# Preview the actual visible 72dp viewport, including two examples of themed tinting.
shapes = [('<circle cx="512" cy="512" r="512"/>', None),
          ('<rect width="1024" height="1024" rx="240"/>', None),
          ('<path d="M512 0C920 0 1024 104 1024 512S920 1024 512 1024 0 920 0 512 104 0 512 0Z"/>', None),
          ('<circle cx="512" cy="512" r="512"/>', ("#D3E8DE", "#174D3B")),
          ('<circle cx="512" cy="512" r="512"/>', ("#174D3B", "#B7F1D8"))]
for index, (mask, themed) in enumerate(shapes):
    mark = svg_paths if themed is None else (f'<g clip-path="url(#cube)"><path fill="{themed[1]}" fill-rule="evenodd" d={quoteattr(mono)}/></g>')
    bg = "url(#bg)" if themed is None else themed[0]
    art = svg(f'<g clip-path="url(#mask)"><rect width="1024" height="1024" fill="{bg}"/>'
              f'<g transform="translate(512 512) scale({1.5*MARK_SCALE}) translate(-512 -512)">' + mark + '</g></g>',
              '<clipPath id="mask">' + mask + f'</clipPath><clipPath id="cube"><path d="{outer}"/></clipPath>')
    render(art, OUT / f"preview-{index}.png", 256)

preview_images = "".join(
    f'<image x="{20+index*240}" y="20" width="200" height="200" href="data:image/png;base64,'
    + base64.b64encode((OUT / f"preview-{index}.png").read_bytes()).decode() + '"/>' for index in range(5))
preview_sheet = '<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="240"><rect width="1200" height="240" fill="#EEEEEE"/>' + preview_images + '</svg>'
subprocess.run(["rsvg-convert", "-o", str(OUT / "android-previews.png")], input=preview_sheet.encode(), check=True)

(OUT / "source-receipt.json").write_text(json.dumps({
    "source": "EaseCation/mcpywrap/core/mcpywrap_core/ui/assets/mcpy.icon",
    "files": {str(p.relative_to(SOURCE)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(SOURCE.rglob("*")) if p.is_file()},
    "adaptiveLayerDp": 108, "markScale": MARK_SCALE, "safeDiameterDp": 66, "actualCubeRadiusDp": safe_radius, "splashCanvasDp": 288,
    "legacyPixels": [48,72,96,144,192], "storePixels":512,
}, indent=2) + "\n")
print("Generated layered Android vectors, themed icon, legacy density variants, splash and store artwork.")
