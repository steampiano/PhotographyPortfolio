#!/usr/bin/env python3
"""Scans photos/ (including subfolders) and writes posts.json. No dependencies
beyond the standard library.

Add a new post: drop an image in photos/ (or any subfolder within it),
optionally add a same-named .txt file with the caption, then run
`python3 build.py` (or let the host run it).

Photos placed directly in photos/highlights/ are also flagged as highlights
and shown in the carousel at the top of the gallery page.

Thumbnails
----------
For each photo, a smaller web-sized version is generated into thumbs/ (mirroring
the photos/ layout) using macOS's built-in `sips`. The gallery and carousel load
these lightweight thumbnails; the full-resolution original loads only on a
photo's own page. If sips isn't available (e.g. on the deploy server) the
already-committed thumbnail is reused, and if none exists the full image is used
as a fallback so nothing ever breaks.
"""

import json
import os
import shutil
import subprocess
from datetime import datetime, timezone

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PHOTOS_DIR = os.path.join(BASE_DIR, "photos")
THUMBS_DIR = os.path.join(BASE_DIR, "thumbs")
OUTPUT_FILE = os.path.join(BASE_DIR, "posts.json")
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg"}

# Longest-edge size (px) and JPEG quality for generated thumbnails.
THUMB_MAX_PX = 1200
THUMB_QUALITY = 70

SIPS = shutil.which("sips")


def make_thumbnail(image_path, thumb_path):
    """Generate a thumbnail with sips. Returns True on success."""
    if SIPS is None:
        return False
    os.makedirs(os.path.dirname(thumb_path), exist_ok=True)
    result = subprocess.run(
        [SIPS, "-Z", str(THUMB_MAX_PX), "-s", "formatOptions", str(THUMB_QUALITY),
         image_path, "--out", thumb_path],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    return result.returncode == 0 and os.path.exists(thumb_path)


def thumb_for(image_path, rel_path, ext):
    """Return the web path to use as this photo's thumbnail.

    Generates the thumbnail if sips is available and it's missing or stale;
    reuses an existing thumbnail otherwise; falls back to the full image if no
    thumbnail can be produced or found.
    """
    # SVGs are already tiny and don't resize well via sips — use as-is.
    if ext == ".svg":
        return rel_path

    rel_from_photos = os.path.relpath(image_path, PHOTOS_DIR)
    thumb_path = os.path.join(THUMBS_DIR, rel_from_photos)
    thumb_rel = os.path.join("thumbs", rel_from_photos).replace(os.sep, "/")

    needs_build = (
        not os.path.exists(thumb_path)
        or os.path.getmtime(image_path) > os.path.getmtime(thumb_path)
    )
    if needs_build:
        make_thumbnail(image_path, thumb_path)

    if os.path.exists(thumb_path):
        return thumb_rel
    return rel_path  # fallback: full image


def main():
    posts = []

    for root, _dirs, filenames in os.walk(PHOTOS_DIR):
        for filename in filenames:
            base, ext = os.path.splitext(filename)
            ext = ext.lower()
            if ext not in IMAGE_EXTS:
                continue

            image_path = os.path.join(root, filename)
            caption_path = os.path.join(root, base + ".txt")

            caption = ""
            if os.path.exists(caption_path):
                with open(caption_path, "r", encoding="utf-8") as f:
                    caption = f.read().strip()

            mtime = os.path.getmtime(image_path)
            date = datetime.fromtimestamp(mtime, tz=timezone.utc).isoformat()

            rel_path = os.path.relpath(image_path, os.path.dirname(PHOTOS_DIR))
            rel_path = rel_path.replace(os.sep, "/")

            path_parts = os.path.relpath(root, PHOTOS_DIR).split(os.sep)
            highlight = path_parts[0].lower() == "highlights"

            thumb = thumb_for(image_path, rel_path, ext)

            posts.append({
                "image": rel_path,
                "thumb": thumb,
                "caption": caption,
                "date": date,
                "highlight": highlight,
            })

    posts.sort(key=lambda p: p["date"], reverse=True)

    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        json.dump(posts, f, indent=2)

    print(f"Wrote {len(posts)} post(s) to posts.json")


if __name__ == "__main__":
    main()
