"""Original geometric wordmark "Folirel" built from primitive shapes (no font used).

Units: font-like, y up, baseline = 0. Cap height 700, x-height 520.
"""
import math
from shapely.geometry import Polygon, box, Point
from shapely.affinity import scale as sscale
from shapely.ops import unary_union

STEM = 130          # vertical stroke
HSTEM = 114         # horizontal / round thinning
CAP = 700
XH = 500
ASC = 712
OV = 12             # overshoot for rounds
RES = 64


def ellipse(cx, cy, rx, ry):
    n = 256
    return Polygon([(cx + rx * math.cos(2 * math.pi * i / n), cy + ry * math.sin(2 * math.pi * i / n)) for i in range(n)])


def ring(cx, cy, rx, ry, sx=STEM, sy=HSTEM):
    return ellipse(cx, cy, rx, ry).difference(ellipse(cx, cy, rx - sx, ry - sy))


def wedge(cx, cy, a0, a1, r=2000):
    """Sector polygon from angle a0 to a1 (degrees, CCW, 0 = +x)."""
    pts = [(cx, cy)]
    n = 48
    for i in range(n + 1):
        a = math.radians(a0 + (a1 - a0) * i / n)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return Polygon(pts)


def glyph_F():
    w = 440
    g = unary_union([
        box(0, 0, STEM, CAP),
        box(0, CAP - HSTEM, w, CAP),
        box(0, 290, w - 56, 290 + HSTEM * 0.95),
    ])
    return g, w


def glyph_o():
    rx, ry = 262, XH / 2 + OV
    cx, cy = rx, XH / 2
    return ring(cx, cy, rx, ry), 2 * rx


def glyph_l():
    return box(0, 0, STEM, ASC), STEM


def glyph_i():
    d = STEM * 1.12
    dot = Point(STEM / 2, XH + 72 + d / 2).buffer(d / 2, resolution=RES)
    return unary_union([box(0, 0, STEM, XH), dot]), STEM


def glyph_r():
    r_out = 218
    cx, cy = r_out, XH + OV - r_out          # arc's left edge lines up with stem's left edge
    arc = ring(cx, cy, r_out, r_out).intersection(wedge(cx, cy, 63, 180))
    arc = arc.intersection(box(-10, cy, 1000, 1000))
    g = unary_union([box(0, 0, STEM, XH), arc, box(0, cy - 1, STEM, XH)])
    xmax = g.bounds[2]
    return g, xmax


def glyph_e():
    rx, ry = 254, XH / 2 + OV
    cx, cy = rx, XH / 2
    bar_lo, bar_hi = cy - 38, cy - 38 + HSTEM * 0.92
    body = ring(cx, cy, rx, ry)
    # aperture: open the lower-right quadrant just below the crossbar
    a_top = math.degrees(math.asin((bar_lo - cy) / ry))
    body = body.difference(wedge(cx, cy, -38, a_top - 0.5))
    bar = box(cx - rx + STEM * 0.5, bar_lo, cx + rx - 2, bar_hi).intersection(ellipse(cx, cy, rx, ry))
    return unary_union([body, bar]), 2 * rx


GLYPHS = {"F": glyph_F, "o": glyph_o, "l": glyph_l, "i": glyph_i, "r": glyph_r, "e": glyph_e}

# Spacing: side bearings follow a reference font's metrics (Figtree Bold, upm 1000,
# cap 700). Only the spacing numbers are borrowed; every outline here is original.
SB = {"F": (76, 29), "o": (30, 30), "l": (57, 57), "i": (55, 55), "r": (57, 13), "e": (30, 20)}
KERN = {("F", "o"): -22}


def gap(a, b):
    return SB[a][1] + SB[b][0] + KERN.get((a, b), 0)


def build(text="Folirel"):
    shapes = []
    x = 0
    prev = None
    for ch in text:
        g, w = GLYPHS[ch]()
        if prev is not None:
            x += gap(prev, ch)
        from shapely.affinity import translate
        shapes.append(translate(g, x - g.bounds[0], 0))
        x += g.bounds[2] - g.bounds[0]
        prev = ch
    return unary_union(shapes), x


def to_path(geom, x0, baseline, s):
    """SVG path data (y flipped) for a (Multi)Polygon."""
    polys = getattr(geom, "geoms", [geom])
    out = []
    for p in polys:
        for ring_ in [p.exterior, *p.interiors]:
            pts = list(ring_.simplify(0.6).coords)[:-1]
            seg = [f"{x0 + px * s:.1f} {baseline - py * s:.1f}" for px, py in pts]
            out.append("M" + "L".join(seg) + "Z")
    return "".join(out)
