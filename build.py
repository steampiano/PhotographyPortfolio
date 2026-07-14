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
from datetime import datetime, timezone

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PHOTOS_DIR = os.path.join(BASE_DIR, "photos")
THUMBS_DIR = os.path.join(BASE_DIR, "thumbs")
PREVIEWS_DIR = os.path.join(BASE_DIR, "previews")
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


def derivative_for(image_path, rel_path, ext, out_dir, dir_name, max_px, quality):
    """Return the web path to use for a derivative (thumb or preview) of a photo.

    Generates it if sips is available and it's missing or stale; reuses an
    existing one otherwise; falls back to the full image if none can be
    produced or found.
    """
    # SVGs are already tiny and don't resize well via sips — use as-is.
    if ext == ".svg":
        return rel_path

    rel_from_photos = os.path.relpath(image_path, PHOTOS_DIR)
    out_path = os.path.join(out_dir, rel_from_photos)
    out_rel = os.path.join(dir_name, rel_from_photos).replace(os.sep, "/")

    needs_build = (
        not os.path.exists(out_path)
        or os.path.getmtime(image_path) > os.path.getmtime(out_path)
    )
    if needs_build:
        make_derivative(image_path, out_path, max_px, quality)

    if os.path.exists(out_path):
        return out_rel
    return rel_path  # fallback: full image


EXIF_DATETIME_ORIGINAL = 0x9003
EXIF_DATETIME_DIGITIZED = 0x9004
EXIF_SUBIFD_POINTER = 0x8769
EXIF_MODIFY_DATE = 0x0132  # last edited/exported time, NOT capture time
EXIF_EXPOSURE_TIME = 0x829A   # shutter speed, RATIONAL (seconds)
EXIF_FNUMBER = 0x829D         # aperture, RATIONAL
EXIF_ISO = 0x8827             # ISO speed, SHORT
EXIF_FOCAL_LENGTH = 0x920A    # focal length, RATIONAL (mm)


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
        exif_ifd_ptr = ifd0.get(EXIF_SUBIFD_POINTER)
        if exif_ifd_ptr:
            exif_ifd = _read_ifd(exif_data, exif_ifd_ptr, byte_order)
            value = exif_ifd.get(EXIF_DATETIME_ORIGINAL) or exif_ifd.get(EXIF_DATETIME_DIGITIZED)
            fields = _format_exif_fields(exif_ifd)
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


def main():
    posts = []
    featured_order = load_featured_order()

    profile_source = find_profile_photo()
    if profile_source:
        needs_build = (
            not os.path.exists(PROFILE_WEB_PATH)
            or os.path.getmtime(profile_source) > os.path.getmtime(PROFILE_WEB_PATH)
        )
        if needs_build:
            make_derivative(profile_source, PROFILE_WEB_PATH, PROFILE_MAX_PX, PROFILE_QUALITY)

    for root, dirs, filenames in os.walk(PHOTOS_DIR):
        # photos/meta/ holds config and generated assets (featured-order.txt,
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

            rel_path = os.path.relpath(image_path, os.path.dirname(PHOTOS_DIR))
            rel_path = rel_path.replace(os.sep, "/")

            thumb = derivative_for(image_path, rel_path, ext, THUMBS_DIR, "thumbs",
                                    THUMB_MAX_PX, THUMB_QUALITY)
            preview = derivative_for(image_path, rel_path, ext, PREVIEWS_DIR, "previews",
                                      PREVIEW_MAX_PX, PREVIEW_QUALITY)

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
            }
            if exif_fields:
                post["exif"] = exif_fields
            if featured:
                idx = order_index_for(filename, featured_order)
                if idx is not None:
                    post["order"] = idx
            posts.append(post)

    posts.sort(key=lambda p: p["date"], reverse=True)

    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        json.dump(posts, f, indent=2)

    print(f"Wrote {len(posts)} post(s) to posts.json")


if __name__ == "__main__":
    main()
