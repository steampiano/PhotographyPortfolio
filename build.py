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
"""

import json
import os
import re
import shutil
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

    for root, _dirs, filenames in os.walk(PHOTOS_DIR):
        for filename in filenames:
            base, ext = os.path.splitext(filename)
            ext = ext.lower()
            if ext not in IMAGE_EXTS:
                continue

            image_path = os.path.join(root, filename)
            caption_path = os.path.join(root, base + ".txt")

            meta, caption = parse_caption_file(caption_path)

            event = meta.get("event", "").strip()
            featured = meta.get("featured", "").strip().lower() in TRUE_VALUES
            tags = [t.strip() for t in meta.get("tags", "").split(",") if t.strip()]
            people = [p.strip() for p in meta.get("people", "").split(",") if p.strip()]

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
