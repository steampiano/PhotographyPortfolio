#!/usr/bin/env python3
"""Scans photos/ (including subfolders) and writes posts.json. No dependencies
beyond the standard library.

Add a new post: drop an image in photos/ (or any subfolder within it),
optionally add a same-named .txt file with the caption, then run
`python3 build.py` (or let the host run it).

Photos placed directly in photos/highlights/ are also flagged as highlights
and shown in the carousel at the top of the gallery page.
"""

import json
import os
from datetime import datetime, timezone

PHOTOS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "photos")
OUTPUT_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "posts.json")
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg"}


def main():
    posts = []

    for root, _dirs, filenames in os.walk(PHOTOS_DIR):
        for filename in filenames:
            base, ext = os.path.splitext(filename)
            if ext.lower() not in IMAGE_EXTS:
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

            posts.append({
                "image": rel_path,
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
