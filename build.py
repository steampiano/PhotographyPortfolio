#!/usr/bin/env python3
"""Scans photos/ (including subfolders) and writes posts.json. No dependencies
beyond the standard library.

Add a new post: drop an image in photos/ (or any subfolder within it) and add a
same-named .txt file with its caption and metadata, then run `python3 build.py`
(or let the host run it). Folders are only for your own filing — they do NOT
affect the site; all categorization comes from the .txt metadata.

Caption / metadata file format
------------------------------
A photo's .txt file may start with optional `key: value` metadata lines,
followed by a blank line, then the caption text. Example:

    event: TFF 2026
    featured: yes
    tags: fursuit, night

    A quiet moment by the string lights.

Recognized keys: `event` (string), `featured` (yes/no), `tags` (comma-separated).
A plain .txt with no recognized metadata lines is treated entirely as caption,
so older caption files keep working unchanged.

Featured photos appear in the carousel at the top of the gallery; their order
is controlled by photos/featured-order.txt (see load_featured_order).

Profile photo
-------------
A file named exactly `profile.<ext>` (any image extension, any case), placed
anywhere in photos/, is NOT a gallery post — it's excluded from posts.json and
instead used as the About page photo. Its web-sized version is auto-generated
to photos/meta/profile-web.jpg, which about.html references directly. To
change the About page photo, just replace the profile.* file and rebuild.

Thumbnails and previews
-----------------------
For each photo, two smaller web-sized versions are generated using macOS's
built-in `sips`:

- thumbs/ — small (800px), for the gallery grid and carousel, where many load
  at once and speed matters most.
- previews/ — larger (1800px), for the single-photo lightbox, where only one
  image loads at a time so it can afford more bytes for real sharpness.

The full-resolution original loads only on a photo's own dedicated page. If
sips isn't available (e.g. on the deploy server) an already-committed version
is reused, and if none exists the full image is used as a fallback so nothing
ever breaks.

Photo date
----------
Each photo's date comes from its embedded EXIF DateTimeOriginal tag (the true
capture time) when available, NOT the file's filesystem modification time and
NOT the EXIF ModifyDate tag either. Two things this deliberately avoids:

- mtime: Vercel builds from a fresh git checkout, and git checkout resets
  every file's mtime to the moment of checkout — mtime-based dates would make
  every photo show the deploy date instead of when it was actually taken.
- EXIF ModifyDate (0x0132): this is when a tool like Lightroom last exported
  the file, not when the shutter fired. It only matches the real capture time
  if a photo is exported right after shooting — re-editing/re-exporting a
  photo later (e.g. revisiting an older shoot) silently shows the export date
  instead. DateTimeOriginal (0x9003) always reflects the actual capture
  moment regardless of when/how many times the file was later re-processed.

The EXIF APP1 segment is parsed directly (see read_exif) rather than
via `sips -g creation`, because sips's "creation" surfaces ModifyDate, not
DateTimeOriginal. Falls back to file mtime only for images with no EXIF data
at all (e.g. screenshots, graphics).
"""

import json
import os
import re
import shutil
import struct
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import zlib
from datetime import datetime, timezone

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PHOTOS_DIR = os.path.join(BASE_DIR, "photos")
THUMBS_DIR = os.path.join(BASE_DIR, "thumbs")
PREVIEWS_DIR = os.path.join(BASE_DIR, "previews")
AVATARS_DIR = os.path.join(BASE_DIR, "avatars")
# A second, independent source tree for non-fursuit work (landscapes, etc.),
# rendered on its own page (other-work.html) instead of the main gallery.
# Same folder-for-filing-only / .txt-caption rules as photos/, just with no
# Featured row or featured-order.txt support — see scan_photos().
OTHER_PHOTOS_DIR = os.path.join(BASE_DIR, "other-photos")
OTHER_THUMBS_DIR = os.path.join(BASE_DIR, "thumbs-other")
OTHER_PREVIEWS_DIR = os.path.join(BASE_DIR, "previews-other")
OUTPUT_FILE = os.path.join(BASE_DIR, "posts.json")
FEATURED_ORDER_FILE = os.path.join(PHOTOS_DIR, "meta", "featured-order.txt")
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg"}

# Metadata keys recognized at the top of a caption .txt file.
META_KEYS = {"event", "featured", "tags", "people"}
TRUE_VALUES = {"yes", "y", "true", "1", "on"}

# Longest-edge size (px) and JPEG quality for each derivative.
THUMB_MAX_PX = 800      # gallery grid + carousel — many load at once
THUMB_QUALITY = 68
PREVIEW_MAX_PX = 1800   # lightbox — only one loads at a time, can afford more
PREVIEW_QUALITY = 80
PROFILE_MAX_PX = 900    # About page photo (displayed larger, so needs more res)
PROFILE_QUALITY = 82
PROFILE_WEB_PATH = os.path.join(PHOTOS_DIR, "meta", "profile-web.jpg")

SIPS = shutil.which("sips")


def make_derivative(image_path, out_path, max_px, quality):
    """Generate a resized/compressed derivative with sips. Returns True on success."""
    if SIPS is None:
        return False
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    result = subprocess.run(
        [SIPS, "-Z", str(max_px), "-s", "formatOptions", str(quality),
         image_path, "--out", out_path],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    return result.returncode == 0 and os.path.exists(out_path)


def derivative_for(image_path, rel_path, ext, out_dir, dir_name, max_px, quality, source_dir):
    """Return the web path to use for a derivative (thumb or preview) of a photo.

    Generates it if sips is available and it's missing or stale; reuses an
    existing one otherwise; falls back to the full image if none can be
    produced or found.
    """
    # SVGs are already tiny and don't resize well via sips — use as-is.
    if ext == ".svg":
        return rel_path

    rel_from_source = os.path.relpath(image_path, source_dir)
    out_path = os.path.join(out_dir, rel_from_source)
    out_rel = os.path.join(dir_name, rel_from_source).replace(os.sep, "/")

    needs_build = (
        not os.path.exists(out_path)
        or os.path.getmtime(image_path) > os.path.getmtime(out_path)
    )
    if needs_build:
        make_derivative(image_path, out_path, max_px, quality)

    if os.path.exists(out_path):
        return out_rel
    return rel_path  # fallback: full image


EXIF_MAKE = 0x010F            # camera manufacturer, ASCII (IFD0)
EXIF_MODEL = 0x0110           # camera model, ASCII (IFD0)
EXIF_DATETIME_ORIGINAL = 0x9003
EXIF_DATETIME_DIGITIZED = 0x9004
EXIF_SUBIFD_POINTER = 0x8769
EXIF_MODIFY_DATE = 0x0132  # last edited/exported time, NOT capture time
EXIF_EXPOSURE_TIME = 0x829A   # shutter speed, RATIONAL (seconds)
EXIF_FNUMBER = 0x829D         # aperture, RATIONAL
EXIF_ISO = 0x8827             # ISO speed, SHORT
EXIF_FOCAL_LENGTH = 0x920A    # focal length, RATIONAL (mm)

# EXIF Model is often a cryptic internal product code rather than the
# marketing name (e.g. Sony reports "ILCE-7M4" for the Alpha 7 IV) — map
# known codes to a friendly name; falls back to "<Make> <Model>" otherwise.
CAMERA_MODEL_NAMES = {
    "ILCE-7M4": "Sony Alpha 7 IV",
    "ILCE-7M2": "Sony Alpha 7 II",
}


def _format_camera_name(make, model):
    if not model:
        return None
    model = model.strip()
    friendly = CAMERA_MODEL_NAMES.get(model)
    if friendly:
        return friendly
    make = (make or "").strip()
    if make and not model.upper().startswith(make.upper()):
        return f"{make.title()} {model}"
    return model


def _read_ifd(data, offset, byte_order):
    """Parse one EXIF IFD, returning {tag: value} for ASCII/SHORT/LONG/RATIONAL
    entries. RATIONAL values are returned as (numerator, denominator) tuples."""
    fmt = "<" if byte_order == "II" else ">"
    (count,) = struct.unpack_from(fmt + "H", data, offset)
    entries = {}
    pos = offset + 2
    for _ in range(count):
        tag, typ, cnt = struct.unpack_from(fmt + "HHI", data, pos)
        raw_value_field = data[pos + 8:pos + 12]
        if typ == 2:  # ASCII string
            if cnt <= 4:
                raw = raw_value_field[:cnt]
            else:
                (voff,) = struct.unpack_from(fmt + "I", raw_value_field)
                raw = data[voff: voff + cnt]
            entries[tag] = raw.split(b"\x00", 1)[0].decode("ascii", "replace")
        elif typ == 3:  # SHORT (e.g. ISOSpeedRatings) — only the first value
            if cnt * 2 <= 4:
                entries[tag] = struct.unpack_from(fmt + "H", raw_value_field)[0]
            else:
                (voff,) = struct.unpack_from(fmt + "I", raw_value_field)
                entries[tag] = struct.unpack_from(fmt + "H", data, voff)[0]
        elif typ == 4:  # LONG — used for sub-IFD pointers
            entries[tag] = struct.unpack_from(fmt + "I", raw_value_field)[0]
        elif typ == 5:  # RATIONAL — always stored via offset (8 bytes)
            (voff,) = struct.unpack_from(fmt + "I", raw_value_field)
            entries[tag] = struct.unpack_from(fmt + "II", data, voff)
        pos += 12
    return entries


def _format_exif_fields(exif_ifd):
    """Turn raw Exif SubIFD values into human-readable shooting info
    (aperture, shutter speed, ISO, focal length). Omits any field whose tag
    wasn't present or has a zero denominator (as some cameras write when a
    value is unknown)."""
    fields = {}

    fnumber = exif_ifd.get(EXIF_FNUMBER)
    if fnumber and fnumber[1]:
        fields["aperture"] = f"f/{fnumber[0] / fnumber[1]:g}"

    exposure = exif_ifd.get(EXIF_EXPOSURE_TIME)
    if exposure and exposure[1]:
        secs = exposure[0] / exposure[1]
        if secs > 0:
            fields["shutter"] = f"1/{round(1 / secs)}s" if secs < 1 else f"{secs:g}s"

    iso = exif_ifd.get(EXIF_ISO)
    if iso:
        fields["iso"] = f"ISO {iso}"

    focal_length = exif_ifd.get(EXIF_FOCAL_LENGTH)
    if focal_length and focal_length[1]:
        fields["focal_length"] = f"{focal_length[0] / focal_length[1]:g}mm"

    return fields


def read_exif(image_path):
    """Return (date, fields) for a photo's EXIF data:

    - date: the true EXIF *capture* date/time (DateTimeOriginal) as a naive
      ISO string (e.g. "2026-04-23T16:58:07"), or None if unavailable.
      Deliberately naive (no timezone/offset) since EXIF stores local camera
      wall-clock time with no timezone info — treating it as naive means the
      browser displays this exact calendar date/time regardless of the
      viewer's own timezone, rather than shifting it during UTC conversion.
    - fields: a dict of human-readable shooting info (aperture, shutter,
      iso, focal_length) for whichever tags were present — see
      _format_exif_fields. {} if none were found.

    Parses the EXIF APP1 segment directly rather than shelling out to `sips`,
    because `sips -g creation` actually surfaces the ModifyDate tag (0x0132—
    when a tool like Lightroom last exported the file) rather than
    DateTimeOriginal (0x9003 — when the shutter actually fired). Those only
    match if a photo is exported right after shooting; re-editing/re-exporting
    a photo later (common when revisiting older shoots) silently shows the
    re-export date instead of the real capture date.
    """
    try:
        with open(image_path, "rb") as f:
            data = f.read()
    except OSError:
        return None, {}

    if data[0:2] != b"\xff\xd8":
        return None, {}

    exif_data = None
    pos = 2
    while pos < len(data) - 4:
        if data[pos] != 0xFF:
            break
        marker = data[pos + 1]
        if marker == 0xE1:  # APP1
            seg_len = struct.unpack_from(">H", data, pos + 2)[0]
            seg = data[pos + 4: pos + 2 + seg_len]
            if seg[:6] == b"Exif\x00\x00":
                exif_data = seg[6:]
            break
        if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
            pos += 2
            continue
        seg_len = struct.unpack_from(">H", data, pos + 2)[0]
        pos += 2 + seg_len

    if not exif_data or len(exif_data) < 8:
        return None, {}

    try:
        byte_order = exif_data[0:2].decode("ascii")
        if byte_order not in ("II", "MM"):
            return None, {}
        fmt = "<" if byte_order == "II" else ">"
        (ifd0_offset,) = struct.unpack_from(fmt + "I", exif_data, 4)
        ifd0 = _read_ifd(exif_data, ifd0_offset, byte_order)

        value = None
        fields = {}
        camera = _format_camera_name(ifd0.get(EXIF_MAKE), ifd0.get(EXIF_MODEL))
        if camera:
            fields["camera"] = camera
        exif_ifd_ptr = ifd0.get(EXIF_SUBIFD_POINTER)
        if exif_ifd_ptr:
            exif_ifd = _read_ifd(exif_data, exif_ifd_ptr, byte_order)
            value = exif_ifd.get(EXIF_DATETIME_ORIGINAL) or exif_ifd.get(EXIF_DATETIME_DIGITIZED)
            fields.update(_format_exif_fields(exif_ifd))
        if not value:
            # Last resort: ModifyDate is better than nothing (still beats
            # falling back to the filesystem mtime, which git checkouts reset).
            value = ifd0.get(EXIF_MODIFY_DATE)
    except (struct.error, IndexError):
        return None, {}

    if not value:
        return None, fields
    m = re.match(r"^(\d{4}):(\d{2}):(\d{2})\s+(\d{2}):(\d{2}):(\d{2})$", value.strip())
    if not m:
        return None, fields
    year, month, day, hour, minute, second = m.groups()
    return f"{year}-{month}-{day}T{hour}:{minute}:{second}", fields


# Spoofs Instagram's own Android app to reach the same public
# "web_profile_info" endpoint the mobile app itself uses — no login needed,
# and it only returns what's already visible on the person's public profile
# page. Undocumented/unofficial, so treat failures as routine: skip and move
# on rather than breaking the whole build over one handle.
INSTAGRAM_USER_AGENT = (
    "Instagram 337.0.0.0.77 Android (28/9; 420dpi; 1080x1920; samsung; "
    "SM-G611F; on7xreflte; samsungexynos7870; en_US; 493419337)"
)


def fetch_avatar(handle, force=False):
    """Download a handle's Instagram profile picture to avatars/<handle>.jpg.

    Skips the network entirely if already cached, unless force=True (used by
    tools/refresh_avatars.py to pick up profile picture changes). Best-effort
    only: any failure (handle not found, network error, endpoint blocked)
    is silently skipped, since a stale/missing avatar just means the bubble
    falls back to plain text on the site — never worth failing the build.
    """
    clean = handle.strip().lstrip("@").lower()
    if not clean:
        return False

    out_path = os.path.join(AVATARS_DIR, f"{clean}.jpg")
    if os.path.exists(out_path) and not force:
        return False

    info_url = f"https://i.instagram.com/api/v1/users/web_profile_info/?username={clean}"
    try:
        req = urllib.request.Request(info_url, headers={"User-Agent": INSTAGRAM_USER_AGENT})
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read())
        pic_url = data["data"]["user"]["profile_pic_url_hd"]
    except (urllib.error.URLError, TimeoutError, KeyError, TypeError, ValueError, json.JSONDecodeError):
        return False

    try:
        img_req = urllib.request.Request(pic_url, headers={"User-Agent": INSTAGRAM_USER_AGENT})
        with urllib.request.urlopen(img_req, timeout=10) as resp:
            img_data = resp.read()
    except (urllib.error.URLError, TimeoutError):
        return False

    os.makedirs(AVATARS_DIR, exist_ok=True)
    with open(out_path, "wb") as f:
        f.write(img_data)
    return True


def fetch_avatars_for(posts, force=False):
    """Fetch avatars for every unique handle tagged across all posts.
    Returns the count actually (re)downloaded.

    A short pause between requests only (never before the first one) —
    the endpoint is unofficial and starts returning 429 Too Many Requests
    after a handful of rapid-fire calls in testing, so this keeps a normal
    run (a handle or two at a time) comfortably under that threshold.
    """
    handles = sorted({p for post in posts for p in post.get("people", [])})
    updated = 0
    for i, handle in enumerate(handles):
        if i > 0:
            time.sleep(1.5)
        if fetch_avatar(handle, force=force):
            updated += 1
            print(f"  fetched avatar for {handle}")
    return updated


def compute_avatar_color(image_path):
    """Best-effort average color of an avatar image, as an "rgb(r, g, b)"
    CSS color string (or None if anything goes wrong).

    Rather than pull in an imaging library, this asks sips to downsample
    the image to a single pixel — a resize filter's job when shrinking to
    1x1 is, by construction, a weighted average of every source pixel — and
    then reads that one pixel straight out of the resulting PNG. A 1x1 PNG
    is small enough to parse by hand: the IDAT chunk is just a zlib-
    compressed filter byte followed by that pixel's channel bytes, and
    zlib decompression is already in the standard library, so no
    dependency beyond sips itself (already required elsewhere in this
    file) is needed.
    """
    if SIPS is None:
        return None
    with tempfile.TemporaryDirectory() as tmp:
        tiny_path = os.path.join(tmp, "tiny.png")
        result = subprocess.run(
            [SIPS, "-s", "format", "png", "-z", "1", "1", image_path, "--out", tiny_path],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        if result.returncode != 0 or not os.path.exists(tiny_path):
            return None
        try:
            data = open(tiny_path, "rb").read()
            if data[:8] != b"\x89PNG\r\n\x1a\n":
                return None
            pos = 8
            color_type = None
            idat = b""
            while pos + 8 <= len(data):
                length = struct.unpack(">I", data[pos:pos + 4])[0]
                ctype = data[pos + 4:pos + 8]
                chunk = data[pos + 8:pos + 8 + length]
                if ctype == b"IHDR":
                    width, height, bit_depth, color_type = struct.unpack(">IIBB", chunk[:10])
                    if (width, height, bit_depth) != (1, 1, 8):
                        return None
                elif ctype == b"IDAT":
                    idat += chunk
                pos += 8 + length + 4
            raw = zlib.decompress(idat)  # [filter_byte, channel0, channel1, ...]
            if color_type == 2 and len(raw) >= 4:  # RGB
                r, g, b = raw[1], raw[2], raw[3]
            elif color_type == 6 and len(raw) >= 5:  # RGBA
                r, g, b = raw[1], raw[2], raw[3]
            elif color_type == 0 and len(raw) >= 2:  # grayscale
                r = g = b = raw[1]
            else:
                return None
            return f"rgb({r}, {g}, {b})"
        except Exception:
            return None


def write_avatar_colors():
    """Recomputes avatars/colors.json — a {handle: "rgb(r, g, b)"} map of
    each cached avatar's average color, used as that person's handle-pill
    border accent on the site. Covers every .jpg already in avatars/
    (not just ones just (re)fetched this run), so it stays complete even
    if an avatar was added by hand or a color needs to catch up. Cheap
    enough to just redo in full each time — a 1x1 sips resize per file.
    """
    if not os.path.isdir(AVATARS_DIR):
        return
    colors = {}
    for filename in sorted(os.listdir(AVATARS_DIR)):
        if not filename.lower().endswith(".jpg"):
            continue
        handle = filename[:-len(".jpg")]
        color = compute_avatar_color(os.path.join(AVATARS_DIR, filename))
        if color:
            colors[handle] = color
    colors_path = os.path.join(AVATARS_DIR, "colors.json")
    with open(colors_path, "w", encoding="utf-8") as f:
        json.dump(colors, f, indent=2, sort_keys=True)


def find_profile_photo():
    """Find a file named exactly `profile.<ext>` anywhere in photos/.

    If more than one exists, the most recently modified one wins (and the
    others are reported so it's obvious one was ignored).
    """
    candidates = []
    for root, _dirs, filenames in os.walk(PHOTOS_DIR):
        for filename in filenames:
            base, ext = os.path.splitext(filename)
            if base.lower() == "profile" and ext.lower() in IMAGE_EXTS:
                candidates.append(os.path.join(root, filename))

    if not candidates:
        return None
    candidates.sort(key=os.path.getmtime, reverse=True)
    if len(candidates) > 1:
        print(f"Note: multiple profile.* files found; using {candidates[0]}")
        for other in candidates[1:]:
            print(f"  (ignoring {other})")
    return candidates[0]


def parse_caption_file(path):
    """Parse a photo's .txt into (metadata dict, caption string).

    Leading lines of the form `key: value` (for keys in META_KEYS) are treated
    as metadata, up to a blank line or the first non-metadata line; everything
    after is the caption. A file with no recognized metadata lines is returned
    entirely as the caption, so plain older caption files still work.
    """
    if not os.path.exists(path):
        return {}, ""

    with open(path, "r", encoding="utf-8") as f:
        lines = f.read().splitlines()

    meta = {}
    i = 0
    while i < len(lines):
        stripped = lines[i].strip()
        if stripped == "":
            # A blank line ends a metadata block (if we found any).
            if meta:
                i += 1
            break
        m = re.match(r"^(\w+)\s*:\s*(.*)$", stripped)
        if m and m.group(1).lower() in META_KEYS:
            meta[m.group(1).lower()] = m.group(2).strip()
            i += 1
        else:
            break

    caption = "\n".join(lines[i:]).strip()
    return meta, caption


def load_featured_order():
    """Read photos/featured-order.txt into a list of filenames (in order).

    Blank lines and lines starting with '#' are ignored. Returns [] if absent.
    """
    if not os.path.exists(FEATURED_ORDER_FILE):
        return []
    entries = []
    with open(FEATURED_ORDER_FILE, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            entries.append(line)
    return entries


def order_index_for(filename, order_list):
    """Position of a featured photo in order_list, or None if not listed.

    Matches either the exact filename or its name without extension, so a line
    can be written either way (e.g. "sunset.jpg" or "sunset").
    """
    stem = os.path.splitext(filename)[0]
    for i, entry in enumerate(order_list):
        entry_stem = os.path.splitext(entry)[0]
        if filename in (entry, entry_stem) or stem in (entry, entry_stem):
            return i
    return None


def scan_photos(source_dir, thumbs_dir, thumbs_name, previews_dir, previews_name,
                 collection, featured_order):
    """Walk one photos source tree and return its list of post dicts.

    `collection` tags every post (e.g. "fursuit" or "other") so the front end
    knows which page it belongs to. `featured_order` only matters for
    collections that have a Featured row — pass [] for ones that don't.
    """
    posts = []
    if not os.path.isdir(source_dir):
        return posts

    for root, dirs, filenames in os.walk(source_dir):
        # meta/ holds config and generated assets (featured-order.txt,
        # profile-web.jpg, etc.) — never gallery content.
        dirs[:] = [d for d in dirs if d.lower() != "meta"]
        if os.path.basename(root).lower() == "meta":
            continue

        for filename in filenames:
            base, ext = os.path.splitext(filename)
            ext = ext.lower()
            if ext not in IMAGE_EXTS:
                continue
            if base.lower() == "profile":
                continue  # the profile photo is not a gallery post

            image_path = os.path.join(root, filename)
            caption_path = os.path.join(root, base + ".txt")

            meta, caption = parse_caption_file(caption_path)

            event = meta.get("event", "").strip()
            featured = meta.get("featured", "").strip().lower() in TRUE_VALUES
            tags = [t.strip() for t in meta.get("tags", "").split(",") if t.strip()]
            people = [p.strip() for p in meta.get("people", "").split(",") if p.strip()]

            date, exif_fields = read_exif(image_path)
            if date is None:
                # Fallback for images with no EXIF (e.g. screenshots, SVGs).
                # Note: unlike EXIF, this is NOT stable across a fresh git
                # checkout (see module docstring).
                mtime = os.path.getmtime(image_path)
                date = datetime.fromtimestamp(mtime, tz=timezone.utc).isoformat()

            rel_path = os.path.relpath(image_path, BASE_DIR)
            rel_path = rel_path.replace(os.sep, "/")

            thumb = derivative_for(image_path, rel_path, ext, thumbs_dir, thumbs_name,
                                    THUMB_MAX_PX, THUMB_QUALITY, source_dir)
            preview = derivative_for(image_path, rel_path, ext, previews_dir, previews_name,
                                      PREVIEW_MAX_PX, PREVIEW_QUALITY, source_dir)

            post = {
                "image": rel_path,
                "thumb": thumb,
                "preview": preview,
                "caption": caption,
                "date": date,
                "event": event,
                "people": people,
                "tags": tags,
                "featured": featured,
                "collection": collection,
            }
            if exif_fields:
                post["exif"] = exif_fields
            if featured:
                idx = order_index_for(filename, featured_order)
                if idx is not None:
                    post["order"] = idx
            posts.append(post)

    return posts


def main():
    featured_order = load_featured_order()

    profile_source = find_profile_photo()
    if profile_source:
        needs_build = (
            not os.path.exists(PROFILE_WEB_PATH)
            or os.path.getmtime(profile_source) > os.path.getmtime(PROFILE_WEB_PATH)
        )
        if needs_build:
            make_derivative(profile_source, PROFILE_WEB_PATH, PROFILE_MAX_PX, PROFILE_QUALITY)

    posts = scan_photos(PHOTOS_DIR, THUMBS_DIR, "thumbs", PREVIEWS_DIR, "previews",
                        "fursuit", featured_order)
    posts += scan_photos(OTHER_PHOTOS_DIR, OTHER_THUMBS_DIR, "thumbs-other",
                         OTHER_PREVIEWS_DIR, "previews-other", "other", [])

    posts.sort(key=lambda p: p["date"], reverse=True)

    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        json.dump(posts, f, indent=2)

    print(f"Wrote {len(posts)} post(s) to posts.json")

    # Only fetches handles that don't already have a cached avatar (new
    # people you've just tagged) — cheap on every normal publish. To force
    # re-fetching everyone (e.g. someone changed their profile picture),
    # use tools/refresh_avatars.py instead.
    fetch_avatars_for(posts)
    write_avatar_colors()


if __name__ == "__main__":
    main()
