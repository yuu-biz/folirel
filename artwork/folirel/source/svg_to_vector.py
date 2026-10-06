#!/usr/bin/env python3
"""Convert the horizontal Folirel logo SVGs into Android VectorDrawables.

The SVG files in artwork/folirel are the source of truth; the two drawables the About screen draws
(app/src/main/res/drawable/folirel_logo.xml and folirel_logo_dark.xml) are generated from them with this script.
It understands only what those SVGs use: <path> with fill / fill-rule, and <g stroke=... stroke-linecap=...>
holding <line> elements. Anything else stops the script instead of being dropped silently.

    python3 artwork/folirel/source/svg_to_vector.py

Run from the repository root; it rewrites the drawables in place (LF line endings, no BOM).
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
ARTWORK = ROOT / "artwork" / "folirel"
DRAWABLES = ROOT / "app" / "src" / "main" / "res" / "drawable"
SVG_NS = "{http://www.w3.org/2000/svg}"
# Shown size of the drawable: the viewport divided by 10, so the aspect ratio stays exact.
DP_PER_UNIT = 0.1

TARGETS = {
    "folirel-logo.svg": "folirel_logo.xml",
    "folirel-logo-dark.svg": "folirel_logo_dark.xml",
}


def argb(color):
    m = re.fullmatch(r"#([0-9A-Fa-f]{6})", color)
    if not m:
        sys.exit(f"unsupported colour {color!r}")
    return "#" + m.group(1).upper()


def fmt(value):
    text = f"{float(value):.3f}".rstrip("0").rstrip(".")
    return text or "0"


def convert(svg_path):
    root = ET.parse(svg_path).getroot()
    min_x, min_y, width, height = (float(v) for v in root.attrib["viewBox"].split())
    paths = []

    def add_fill(el):
        extra = ' android:fillType="evenOdd"' if el.attrib.get("fill-rule") == "evenodd" else ""
        paths.append(
            f'        <path android:fillColor="{argb(el.attrib["fill"])}"{extra}\n'
            f'            android:pathData="{el.attrib["d"]}"/>'
        )

    def add_stroke_group(g):
        color = argb(g.attrib["stroke"])
        cap = g.attrib.get("stroke-linecap", "butt")
        for line in g:
            if line.tag != SVG_NS + "line":
                sys.exit(f"unsupported element in stroke group: {line.tag}")
            a = line.attrib
            alpha = f' android:strokeAlpha="{a["stroke-opacity"]}"' if "stroke-opacity" in a else ""
            paths.append(
                f'        <path android:pathData="M{a["x1"]},{a["y1"]} L{a["x2"]},{a["y2"]}"'
                f' android:strokeColor="{color}" android:strokeWidth="{a["stroke-width"]}"'
                f' android:strokeLineCap="{cap}"{alpha}/>'
            )

    for el in root:
        if el.tag == SVG_NS + "path":
            add_fill(el)
        elif el.tag == SVG_NS + "g" and "stroke" in el.attrib:
            add_stroke_group(el)
        else:
            sys.exit(f"{svg_path.name}: unsupported element {el.tag}")

    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- Generated from artwork/folirel/{svg_path.name} by artwork/folirel/source/svg_to_vector.py; do not edit by hand.\n"
        "     Brand colours are fixed here on purpose: they never follow the user's colour set.\n"
        "     VectorPath is ignored: the outlined lettering is long, and the picture is drawn once on the About page. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    xmlns:tools="http://schemas.android.com/tools"\n'
        '    tools:ignore="VectorPath"\n'
        f'    android:width="{fmt(width * DP_PER_UNIT)}dp"\n'
        f'    android:height="{fmt(height * DP_PER_UNIT)}dp"\n'
        f'    android:viewportWidth="{fmt(width)}"\n'
        f'    android:viewportHeight="{fmt(height)}">\n'
        f'    <group android:translateX="{fmt(-min_x)}" android:translateY="{fmt(-min_y)}">\n'
        + "\n".join(paths)
        + "\n    </group>\n</vector>\n"
    )


def main():
    for svg_name, drawable_name in TARGETS.items():
        (DRAWABLES / drawable_name).write_bytes(convert(ARTWORK / svg_name).encode("utf-8"))
        print("wrote", DRAWABLES / drawable_name)


if __name__ == "__main__":
    main()
