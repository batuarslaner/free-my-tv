"""Downloads the shelf apps' official logos (SVG, Wikimedia Commons) and renders them for the home screen.
Usage: python3 tools/logos.py [OUT_DIR]   (default app/assets/logos; needs rsvg-convert: apt install librsvg2-bin)

For each app it writes <key>.png (the logo trimmed, 512 px on its long side: what HomeApps measures and the All Apps
card uses) and <key>@<W>x<H>.png, the logo rendered straight from the vector at the exact size the focused shelf tile
shows it (HomeView.crispLogo), once per tile size the 1920 x 1080 shelf can have: drawn pixel for pixel, never resampled.
Logos are trademarks of their owners: they are fetched at build time, not kept in git. The downloads are only data
for rsvg-convert; nothing in them runs. Python 3 standard library only."""
import json
import math
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AGENT = "FreeMyTV-logos/1.0 (https://freemytv.io)"
API = "https://commons.wikimedia.org/w/api.php"

# key -> (file on Wikimedia Commons, color): "" as it is, "white" all white, "light" its black parts white (the
# tiles are dark). Prime Video and Globoplay are turned white by the home screen itself (HomeView.TILE_STYLE).
SOURCES = {
    "netflix": ("Netflix_2015_logo.svg", ""),
    "youtube": ("YouTube_dark_logo_2017.svg", ""),
    "prime": ("Prime_Video_logo_(2024).svg", ""),
    "disney": ("Disney+_logo.svg", "white"),
    "max": ("HBO_Max_May_2025_(Horizontal).svg", "white"),
    "appletv": ("Apple_TV_logo.svg", "white"),
    "hulu": ("Hulu_logo_(2018).svg", ""),
    "paramount": ("Paramount+_logo.svg", "white"),
    "peacock": ("NBCUniversal_Peacock_Logo_(2026).svg", "light"),
    "globoplay": ("Globoplay_logo.svg", ""),
    "spotify": ("Spotify_logo_with_text.svg", ""),
}

# The shelf, from HomeView: tiles 126 px tall, 48 apart, at most 270 wide, across 1760 px; focused, 20% larger.
TILE_H, TILE_GAP, TILE_MAX_W, CONTENT_W, SCREEN_W, FOCUS = 126, 48, 270, 1760, 1920, 1.2
MAX_SHELF = 8
MASTER = 512


def jround(v):
    """Java's Math.round."""
    return int(math.floor(v + 0.5))


def tile_sizes():
    """Width and height of the first tile (as HomeView snaps it) for every number of apps the shelf can hold."""
    out = set()
    for n in range(1, MAX_SHELF + 1):
        tw = min(TILE_MAX_W, (CONTENT_W - (n - 1) * TILE_GAP) / n)
        left = (SCREEN_W - (n * tw + (n - 1) * TILE_GAP)) / 2
        out.add((jround(left + tw) - jround(left), TILE_H))
    return sorted(out)


def scales():
    """Each logo's size tweak, read from HomeApps.SHELF (the last column)."""
    src = open(os.path.join(ROOT, "app", "src", "io", "freemytv", "HomeApps.java"), encoding="utf-8").read()
    rows = re.findall(r'\{"(\w+)", "[^"]*", "[^"]*", "\w*", "\w*", "([\d.]+)"\}', src)
    return {k: float(s) for k, s in rows}


# MARK: - PNG (8-bit RGBA, as rsvg-convert writes it)

def read_png(path):
    data = open(path, "rb").read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    pos, idat, w = 8, b"", 0
    while pos < len(data):
        size, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + size]
        if kind == b"IHDR":
            w, h, depth, color, _, _, interlace = struct.unpack(">IIBBBBB", body)
            if depth != 8 or color != 6 or interlace:
                raise ValueError("unexpected PNG format")
        elif kind == b"IDAT":
            idat += body
        pos += size + 12
    raw, stride, bpp = zlib.decompress(idat), w * 4, 4
    px, prev = bytearray(), bytearray(stride)
    for y in range(h):
        f, row = raw[y * (stride + 1)], bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for i in range(stride):
            a = row[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if f == 1:
                row[i] = (row[i] + a) & 255
            elif f == 2:
                row[i] = (row[i] + b) & 255
            elif f == 3:
                row[i] = (row[i] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                row[i] = (row[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        px += row
        prev = row
    return w, h, px


def write_png(path, w, h, px):
    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)
    raw = b"".join(b"\x00" + bytes(px[y * w * 4:(y + 1) * w * 4]) for y in range(h))
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def trim(w, h, px):
    """Crops the transparent margins (alpha <= 10, like HomeApps.trim)."""
    xs, ys = [], []
    for y in range(h):
        for x in range(w):
            if px[(y * w + x) * 4 + 3] > 10:
                xs.append(x)
                ys.append(y)
    if not xs:
        return w, h, px
    l, r, t, b = min(xs), max(xs), min(ys), max(ys)
    out = bytearray()
    for y in range(t, b + 1):
        out += px[(y * w + l) * 4:(y * w + r + 1) * 4]
    return r - l + 1, b - t + 1, out


def recolor(px, mode):
    for i in range(0, len(px), 4):
        r, g, b = px[i], px[i + 1], px[i + 2]
        if mode == "white" or (mode == "light" and max(r, g, b) < 90 and max(r, g, b) - min(r, g, b) < 40):
            px[i] = px[i + 1] = px[i + 2] = 255


def ink(w, h, px):
    """Same sampling as HomeApps.ink: the fraction of the logo's area with ink."""
    hits = total = 0
    for y in range(0, h, max(1, h // 48)):
        for x in range(0, w, max(1, w // 96)):
            hits += px[(y * w + x) * 4 + 3] > 128
            total += 1
    return 0.4 if total == 0 else max(0.08, hits / total)


# MARK: - Download and render

def fetch(url, limit=4 << 20):
    req = urllib.request.Request(url, headers={"User-Agent": AGENT})
    with urllib.request.urlopen(req, timeout=20) as r:
        data = r.read(limit + 1)
    if len(data) > limit:
        raise ValueError("too large")
    return data


def info(name):
    """The file's address and license on Commons."""
    q = urllib.parse.urlencode({"action": "query", "format": "json", "redirects": 1, "prop": "imageinfo",
                                "iiprop": "url|mime|extmetadata", "iiextmetadatafilter": "LicenseShortName",
                                "titles": "File:" + name})
    page = next(iter(json.loads(fetch(API + "?" + q))["query"]["pages"].values()))
    if "imageinfo" not in page:
        raise ValueError("not on Commons")
    ii = page["imageinfo"][0]
    if ii.get("mime") != "image/svg+xml":
        raise ValueError("not an SVG")
    license = ii.get("extmetadata", {}).get("LicenseShortName", {}).get("value", "?")
    return ii["url"].split("?")[0], page["title"], license


def render(svg, zoom, out):
    subprocess.run(["rsvg-convert", "--zoom", "%.6f" % zoom, "--format", "png", "--output", out, svg],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=60)
    return read_png(out)


def make(key, name, mode, scale, sizes, work, out_dir):
    url, title, license = info(name)
    svg = os.path.join(work, key + ".svg")
    data = fetch(url)
    if b"<svg" not in data[:4096]:
        raise ValueError("not an SVG")
    with open(svg, "wb") as f:
        f.write(data)
    png = os.path.join(work, key + ".png")
    # Where the ink is, measured on a large render; then the master, MASTER px on its long side once trimmed.
    w0, h0, _ = render(svg, 1, png)
    big = 1024 / max(w0, h0)
    tw, th, _ = trim(*render(svg, big, png))
    master = big * MASTER / max(tw, th)
    lw, lh, px = trim(*render(svg, master, png))
    recolor(px, mode)
    write_png(os.path.join(out_dir, key + ".png"), lw, lh, px)
    logo_ink = ink(lw, lh, px)
    made = []
    for bw, bh in sizes:
        # HomeView.crispLogo: the same "amount of ink" for every logo, at most 76% x 58% of the focused tile.
        fw, fh = bw * FOCUS, bh * FOCUS
        s = math.sqrt(fw * fh * 0.085 / (lw * lh * max(0.08, logo_ink))) * scale
        s = min(s, fw * 0.76 / lw, fh * 0.58 / lh)
        w, h, px = trim(*render(svg, master * s, png))
        recolor(px, mode)
        write_png(os.path.join(out_dir, "%s@%dx%d.png" % (key, bw, bh)), w, h, px)
        made.append("%dx%d" % (w, h))
    return "%s: %s (%s), %s; %s" % (key, title, license, url, " ".join(made))


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "app", "assets", "logos")
    if not shutil.which("rsvg-convert"):
        print("logos.py: rsvg-convert not found (Debian/Ubuntu: apt install librsvg2-bin; macOS: brew install librsvg)")
        return 1
    os.makedirs(out_dir, exist_ok=True)
    sizes, tweak, notes = tile_sizes(), scales(), []
    work = tempfile.mkdtemp(prefix="freemytv-logos-")
    try:
        for key, (name, mode) in SOURCES.items():
            try:
                notes.append(make(key, name, mode, tweak.get(key, 1.0), sizes, work, out_dir))
                print(notes[-1])
            except Exception as e:  # one logo missing: the shelf lifts that one from the app's TV art
                for f in os.listdir(out_dir):
                    if f == key + ".png" or f.startswith(key + "@"):
                        os.remove(os.path.join(out_dir, f))
                print("logos.py: %s skipped (%s)" % (key, e))
    finally:
        shutil.rmtree(work, ignore_errors=True)
    if notes:
        with open(os.path.join(out_dir, "SOURCES.txt"), "w", encoding="utf-8") as f:
            f.write("Logos from Wikimedia Commons (tools/logos.py). Trademarks of their owners.\n" + "\n".join(notes) + "\n")
    return 0 if notes else 1


if __name__ == "__main__":
    sys.exit(main())
