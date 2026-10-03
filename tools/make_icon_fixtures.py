# -*- coding: utf-8 -*-
"""Generates the synthetic icon fixtures in fixtures/icons (PNG, ICO with a PNG entry, ICO with a BMP entry, a wide
logo, and two non-images). Nothing here is taken from a real site. Run: python -X utf8 tools/make_icon_fixtures.py"""
import struct
import zlib
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / "fixtures" / "icons"


def pixels(w, h, base):
    """A simple two-colour pattern so a decoder that returns an empty bitmap is noticed."""
    rows = []
    for y in range(h):
        row = []
        for x in range(w):
            on = (x // max(1, w // 4) + y // max(1, h // 4)) % 2 == 0
            r, g, b = base if on else (255, 255, 255)
            row.append((r, g, b, 255))
        rows.append(row)
    return rows


def png(w, h, base):
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    raw = b"".join(b"\x00" + b"".join(bytes(p) for p in row) for row in pixels(w, h, base))
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def ico_png_entry(w, h, base):
    data = png(w, h, base)
    header = struct.pack("<HHH", 0, 1, 1)
    entry = struct.pack("<BBBBHHII", w % 256, h % 256, 0, 0, 1, 32, len(data), 6 + 16)
    return header + entry + data


def ico_bmp_entry(w, h, base):
    # DIB: BITMAPINFOHEADER (height doubled for the AND mask), 32 bpp BGRA bottom-up, then the 1-bpp AND mask.
    rows = pixels(w, h, base)
    xor = b"".join(b"".join(bytes((p[2], p[1], p[0], p[3])) for p in row) for row in reversed(rows))
    mask_row = ((w + 31) // 32) * 4
    mask = b"\x00" * (mask_row * h)
    dib = struct.pack("<IiiHHIIiiII", 40, w, h * 2, 1, 32, 0, len(xor) + len(mask), 0, 0, 0, 0) + xor + mask
    header = struct.pack("<HHH", 0, 1, 1)
    entry = struct.pack("<BBBBHHII", w % 256, h % 256, 0, 0, 1, 32, len(dib), 6 + 16)
    return header + entry + dib


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "icon-32.png").write_bytes(png(32, 32, (31, 78, 121)))
    (OUT / "icon-png-entry.ico").write_bytes(ico_png_entry(32, 32, (180, 60, 40)))
    (OUT / "icon-bmp.ico").write_bytes(ico_bmp_entry(16, 16, (30, 140, 70)))
    (OUT / "banner-300x50.png").write_bytes(png(300, 50, (90, 60, 160)))
    (OUT / "big-512.png").write_bytes(png(512, 512, (20, 20, 20)))
    (OUT / "not-an-image.html").write_text("<!doctype html><html><body>This is a page, not an icon.</body></html>\n", encoding="utf-8", newline="\n")
    (OUT / "vector.svg").write_text('<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32"><rect width="32" height="32" fill="#1f4e79"/></svg>\n', encoding="utf-8", newline="\n")
    for p in sorted(OUT.iterdir()):
        print(p.name, p.stat().st_size)


if __name__ == "__main__":
    main()
