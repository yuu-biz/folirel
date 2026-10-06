"""Folirel E1 / E5 icon + logo SVG generator."""
import re

GREEN = "#1A7548"
LOGO_GREEN = "#146A3F"
PALE = "#E2EEE5"      # left leaf on icon
LOGO_PALE = "#6FAA86"  # left leaf on logo

# Shapes are traced in a 470-unit reference space, origin at icon top-left.
def m(x, y, ox=15, oy=12, s=512 / 470):
    return f"{(x - ox) * s:.1f} {(y - oy) * s:.1f}"

def path(cmds):
    out = []
    for c in cmds:
        if isinstance(c, str):
            out.append(c)
        else:
            out.append(m(*c))
    return " ".join(out)

RIGHT_LEAF = path([
    "M", (212, 402),
    "C", (212, 390), (212, 330), (212, 260),
    "C", (212, 175), (290, 120), (410, 97),
    "C", (420, 95), (418, 110), (418, 120),
    "C", (420, 275), (375, 340), (300, 362),
    "C", (275, 372), (248, 377), (232, 384),
    "C", (224, 388), (220, 396), (216, 402),
    "C", (215, 405), (213, 405), (212, 402), "Z",
])

LEFT_LEAF = path([
    "M", (92, 170),
    "C", (150, 182), (184, 220), (184, 275),
    "C", (184, 315), (184, 345), (184, 358),
    "C", (120, 342), (86, 305), (86, 245),
    "C", (85, 205), (85, 185), (86, 177),
    "C", (87, 171), (89, 169), (92, 170), "Z",
])

VEIN = path(["M", (121, 236), "C", (118, 292), (142, 328), (196, 374)])

VEIN_W = 11 * 512 / 470

BARS_SLANT = [((268, 223), (354, 190)), ((265, 269), (354, 236)), ((262, 314), (350, 282))]
# 3rd line: 2/3 length (left end fixed) and lighter
BARS_FADE = [((268, 223), (354, 190)), ((265, 269), (354, 236)),
             ((262, 314), (262 + 88 * 2 / 3, 314 - 32 * 2 / 3), 21, 0.45)]
BARS_FLAT = [((266, 207), (352, 207)), ((266, 253), (352, 253)), ((266, 299), (352, 299))]
BARS = BARS_SLANT
BAR_W = 21 * 512 / 470

# Hierarchy variant: headline line heavier, body lines lighter, last line short (ragged end)
BARS_HIER = [((268, 222), (354, 189), 25), ((265, 269), (345, 238.3), 18.5), ((262, 313), (314, 293), 18.5)]

def bars(color):
    lines = []
    for bar in BARS:
        a, b = bar[0], bar[1]
        w = (bar[2] if len(bar) > 2 else 21) * 512 / 470
        (x1, y1), (x2, y2) = _pt(a), _pt(b)
        op = f' stroke-opacity="{bar[3]}"' if len(bar) > 3 else ""
        lines.append(f'    <line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" stroke-width="{w:.1f}"{op}/>')
    return (f'  <g stroke="{color}" stroke-linecap="round">\n'
            + "\n".join(lines) + "\n  </g>")

# Article card glyph (unused), laid out flat then skewed to follow the leaf (same slope as the old bars).
ART_CX, ART_CY, ART_SKEW = 323, 272, -21
ART = [
    # x0, y0, x1, y1, rx
    (-54, -58, 54, -36, 11),     # headline
    (-54, -24, 2, -11, 6.5),     # text line 1
    (-54, -2, -10, 11, 6.5),     # text line 2
    (12, -24, 54, 11, 6),        # thumbnail
    (-54, 23, 18, 41, 9),        # footer block
]

def article(color):
    rects = "\n".join(
        f'    <rect x="{x0}" y="{y0}" width="{x1 - x0}" height="{y1 - y0}" rx="{r}"/>' for x0, y0, x1, y1, r in ART
    )
    return (f'  <g fill="{color}" transform="translate({ART_CX} {ART_CY}) skewY({ART_SKEW}) scale(1.14)">\n'
            + rects + "\n  </g>")

# --- E5 vein as clean geometry: the vein runs into the leaf tip and the thin
# slivers it would leave beside the tip are absorbed into the vein.
from shapely.geometry import Polygon, LineString

def _bez(p0, p1, p2, p3, n=40):
    return [tuple((1-t)**3*a + 3*(1-t)**2*t*b + 3*(1-t)*t**2*c + t**3*d for a, b, c, d in zip(p0, p1, p2, p3))
            for t in (i / n for i in range(1, n + 1))]

def _pt(p):
    x, y = p
    return ((x - 15) * 512 / 470, (y - 12) * 512 / 470)

def _poly_from(cmds):
    pts = [_pt(cmds[0])]
    for seg in cmds[1:]:
        pts += _bez(pts[-1], *map(_pt, seg))
    return pts

LEFT_LEAF_GEOM = Polygon(_poly_from([(92, 170),
    [(150, 182), (184, 220), (184, 275)], [(184, 315), (184, 345), (184, 358)],
    [(120, 342), (86, 305), (86, 245)], [(85, 205), (85, 185), (86, 177)], [(87, 171), (89, 169), (92, 170)]]))
VEIN_GEOM = LineString(_poly_from([(121, 236), [(118, 292), (142, 328), (196, 374)]])).buffer(VEIN_W / 2, quad_segs=16)
_cut = LEFT_LEAF_GEOM.difference(VEIN_GEOM)
PALE_PARTS = _cut.buffer(-3.5, join_style="mitre").buffer(3.5, quad_segs=8, join_style="round")
PALE_PARTS = PALE_PARTS.intersection(LEFT_LEAF_GEOM)
VEIN_AREA = LEFT_LEAF_GEOM.difference(PALE_PARTS)

def _d(geom):
    out = []
    for p in getattr(geom, "geoms", [geom]):
        if p.area < 1:
            continue
        for r in [p.exterior, *p.interiors]:
            pts = list(r.simplify(0.15).coords)[:-1]
            out.append("M" + "L".join(f"{x:.1f} {y:.1f}" for x, y in pts) + "Z")
    return "".join(out)

from shapely.affinity import translate as _tr
RIGHT_LEAF_GEOM = Polygon(_poly_from([(212, 402),
    [(212, 390), (212, 330), (212, 260)], [(212, 175), (290, 120), (410, 97)],
    [(420, 95), (418, 110), (418, 120)], [(420, 275), (375, 340), (300, 362)],
    [(275, 372), (248, 377), (232, 384)], [(224, 388), (220, 396), (216, 402)],
    [(215, 405), (213, 405), (212, 402)]]))
OVERLAP_DX = 36 * 512 / 470          # tuck the pale leaf behind the white one
OVERLAP_GAP = 7                       # knockout between the two leaves (works on any background)
PALE_OVERLAP = _tr(LEFT_LEAF_GEOM, OVERLAP_DX, 0).difference(RIGHT_LEAF_GEOM.buffer(OVERLAP_GAP, quad_segs=16))
OVERLAP = False

# ---- "article list" content variants -------------------------------------
import math as _m
CONTENT = "bars"
LIST_C = (321.0, 262.0)          # block centre (512 space) = centre of the original bars
LIST_K = _m.tan(_m.radians(-21))  # same slope as the original bars
ROW_H = 23.0                      # line thickness (= original bar)
ROWS = (-50.0, 0.0, 50.0)         # row centres, original spacing

def _sk(x, y):
    """local list frame -> icon space (skewY like the original bars)"""
    return (LIST_C[0] + x, LIST_C[1] + y + LIST_K * x)

def _line(x0, x1, y, w=ROW_H):
    (a, b), (c, d) = _sk(x0, y), _sk(x1, y)
    return f'    <line x1="{a:.1f}" y1="{b:.1f}" x2="{c:.1f}" y2="{d:.1f}" stroke-width="{w:.1f}"/>'

def _para(x0, y0, x1, y1, r):
    """rounded parallelogram (skewed rounded rect) as a path"""
    pts = []
    for cx, cy, a0 in ((x1 - r, y0 + r, -90), (x1 - r, y1 - r, 0), (x0 + r, y1 - r, 90), (x0 + r, y0 + r, 180)):
        for i in range(7):
            a = _m.radians(a0 + 15 * i)
            pts.append(_sk(cx + r * _m.cos(a), cy + r * _m.sin(a)))
    return "M" + "L".join(f"{x:.1f} {y:.1f}" for x, y in pts) + "Z"

def content(color):
    if CONTENT == "bars":
        return bars(color)
    if CONTENT == "none":
        return ""
    if CONTENT == "thumbhead":    # headline row = thumbnail + title, then body lines (3rd faded)
        (ax, ay), (bx, by) = _pt(BARS_FADE[0][0]), _pt(BARS_FADE[0][1])
        k = (by - ay) / (bx - ax)
        half = ROW_H / 2
        T, gap = 36.0, 13.0
        x0 = ax - half                      # thumbnail's left edge = old line's left cap
        tcx = x0 + T / 2
        tcy = ay + k * (tcx - ax)
        pts = []
        r = 7.5
        for cx, cy, a0 in ((T/2 - r, -T/2 + r, -90), (T/2 - r, T/2 - r, 0), (-T/2 + r, T/2 - r, 90), (-T/2 + r, -T/2 + r, 180)):
            for i in range(7):
                a = _m.radians(a0 + 15 * i)
                u, v = cx + r * _m.cos(a), cy + r * _m.sin(a)
                pts.append((tcx + u, tcy + v + k * u))
        thumb = "M" + "L".join(f"{x:.1f} {y:.1f}" for x, y in pts) + "Z"
        sx = x0 + T + gap + half
        sy = ay + k * (sx - ax)
        line1 = f'    <line x1="{sx:.1f}" y1="{sy:.1f}" x2="{bx:.1f}" y2="{by:.1f}" stroke-width="{ROW_H:.1f}"/>'
        global BARS
        saved, BARS = BARS, BARS_FADE[1:]
        rest = bars(color)
        BARS = saved
        rest = rest.replace('stroke-linecap="round">\n', 'stroke-linecap="round">\n' + line1 + "\n", 1)
        return f'  <path fill="{color}" d="{thumb}"/>\n' + rest
    if CONTENT == "thumbs":       # thumbnail + title, x3  (article cards)
        T = 32
        thumbs = "".join(_para(-58, y - T / 2, -58 + T, y + T / 2, 7) for y in ROWS)
        lines = "\n".join(_line(-58 + T + 14 + ROW_H / 2, 56, y) for y in ROWS)
        return (f'  <path fill="{color}" d="{thumbs}"/>\n'
                f'  <g stroke="{color}" stroke-linecap="round">\n{lines}\n  </g>')
    if CONTENT == "dots":         # bullet list, first item flagged unread (bigger dot)
        out = []
        for i, y in enumerate(ROWS):
            cx, cy = _sk(-48, y)
            out.append(f'  <circle cx="{cx:.1f}" cy="{cy:.1f}" r="{15.5 if i == 0 else 11.5}" fill="{color}"/>')
        lines = "\n".join(_line(-20, 56 if i < 2 else 26, y) for i, y in enumerate(ROWS))
        return "\n".join(out) + f'\n  <g stroke="{color}" stroke-linecap="round">\n{lines}\n  </g>'
    if CONTENT == "rss":          # feed symbol, opening toward the leaf tip
        ox, oy = 276.0, 312.0
        w = ROW_H
        arcs = []
        for r in (52.0, 100.0):
            arcs.append(f'    <path d="M{ox:.1f} {oy - r:.1f}A{r} {r} 0 0 1 {ox + r:.1f} {oy:.1f}"/>')
        return (f'  <circle cx="{ox + 1:.1f}" cy="{oy - 1:.1f}" r="16" fill="{color}"/>\n'
                f'  <g fill="none" stroke="{color}" stroke-width="{w:.1f}" stroke-linecap="round">\n'
                + "\n".join(arcs) + "\n  </g>")
    raise ValueError(CONTENT)

def symbol(leaf, pale, bar_color, vein, vein_color, shift=0.0):
    if OVERLAP:
        s = [f'  <g transform="translate({shift:.1f} 0)">', f'  <path fill="{pale}" d="{_d(PALE_OVERLAP)}"/>']
    elif vein:
        s = [f'  <path fill="{pale}" d="{_d(PALE_PARTS)}"/>',
             f'  <path fill="{vein_color}" d="{_d(VEIN_AREA)}"/>']
    else:
        s = [f'  <path fill="{pale}" d="{LEFT_LEAF}"/>']
    s.append(f'  <path fill="{leaf}" d="{RIGHT_LEAF}"/>')
    s.append(content(bar_color))
    if OVERLAP:
        s.append("  </g>")
    return "\n".join(s)

def icon(vein, variant="green"):
    bg, leaf, pale, barc = {
        "green": (GREEN, "#FFFFFF", PALE, GREEN),
        "black": ("#111111", "#FFFFFF", "#D9D9D9", "#111111"),
    }[variant]
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512" height="512">\n'
        f'  <rect width="512" height="512" rx="96" fill="{bg}"/>\n'
        + symbol(leaf, pale, barc, vein, bg, -OVERLAP_DX / 2) + "\n</svg>\n"
    )

# --- wordmark: original letterforms (wordmark.py) ---
import wordmark
WM, WM_W = wordmark.build("Folirel")

def word_path(x0, baseline, cap_px):
    sc = cap_px / wordmark.CAP
    return wordmark.to_path(WM, x0, baseline, sc), x0 + WM_W * sc

# Symbol bounding box in 512 space: x≈77..440, y≈78..427 -> crop to it
SYM_BOX = (70, 70, 380, 365)

def logo(vein, variant="color"):
    leaf, pale, barc, textc, bg = {
        "color": (LOGO_GREEN, LOGO_PALE, "#FFFFFF", LOGO_GREEN, None),
        "white": ("#FFFFFF", "#FFFFFF", LOGO_GREEN, "#FFFFFF", LOGO_GREEN),
        "black": ("#111111", "#8A8A8A", "#FFFFFF", "#111111", None),
        "dark": ("#2E9A62", "#8CC7A3", "#FFFFFF", "#FFFFFF", None),
    }[variant]
    if variant == "white":
        pale_op = ' fill-opacity="0.6"'
    else:
        pale_op = ""
    sx, sy, sw, sh = SYM_BOX
    if OVERLAP:
        sw -= OVERLAP_DX
    H = sh
    baseline = sy + sh * 0.735
    tx = sx + sw + H * 0.12
    d, end = word_path(tx, baseline, H * 0.43)
    W = end - sx + 20
    pad = 40 if bg else 0
    vb = f"{sx - pad:.0f} {sy - pad:.0f} {W + 2 * pad:.0f} {sh + 2 * pad:.0f}"
    sym = symbol(leaf, pale, barc, vein, leaf if variant != "white" else bg, -OVERLAP_DX)
    sym = sym.replace(f'fill="{pale}" d=', f'fill="{pale}"{pale_op} d=', 1)
    bgrect = (f'  <rect x="{sx - pad}" y="{sy - pad}" width="{W + 2 * pad:.0f}" height="{sh + 2 * pad}" rx="36" fill="{bg}"/>\n'
              if bg else "")
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb}">\n'
        + bgrect + sym + f'\n  <path fill="{textc}" fill-rule="evenodd" d="{d}"/>\n</svg>\n'
    )

import os
os.makedirs("out", exist_ok=True)
VARIANTS = {
    "E1": "bars",
    "E1-thumbs": "thumbs",
    "E1-list": "dots",
    "E1-rss": "rss",
    "E1-plain": "none",
    "E1-fade": "bars",
    "E1-fade-thumb": "thumbhead",
}
for name, mode in VARIANTS.items():
    BARS, OVERLAP, CONTENT = (BARS_FADE if name == "E1-fade" else BARS_SLANT), False, mode
    vein = False
    open(f"out/folirel-{name}-icon.svg", "w").write(icon(vein))
    open(f"out/folirel-{name}-logo.svg", "w").write(logo(vein))
    open(f"out/folirel-{name}-logo-white.svg", "w").write(logo(vein, "white"))
    open(f"out/folirel-{name}-logo-black.svg", "w").write(logo(vein, "black"))
    open(f"out/folirel-{name}-logo-dark.svg", "w").write(logo(vein, "dark"))
print(sorted(os.listdir("out")))
