#!/usr/bin/env python3
"""Scans photos/ and writes posts.json. No dependencies beyond the standard library.

Add a new post: drop an image in photos/, optionally add a same-named .txt
file with the caption, then run `python3 build.py` (or let the host run it).
"""

import json
import os
from datetime import datetime, timezone

PHOTOS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "photos")
OUTPUT_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "posts.json")
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg"}


def main():
    posts = []

    for filename in os.listdir(PHOTOS_DIR):
        base, ext = os.path.splitext(filename)
        if ext.lower() not in IMAGE_EXTS:
            continue

        image_path = os.path.join(PHOTOS_DIR, filename)
        caption_path = os.path.join(PHOTOS_DIR, base + ".txt")

        caption = ""
        if os.path.exists(caption_path):
            with open(caption_path, "r", encoding="utf-8") as f:
                caption = f.read().strip()

        mtime = os.path.getmtime(image_path)
        date = datetime.fromtimestamp(mtime, tz=timezone.utc).isoformat()

        posts.append({
            "image": "photos/" + filename,
            "caption": caption,
            "date": date,
        })

    posts.sort(key=lambda p: p["date"], reverse=True)

    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        json.dump(posts, f, indent=2)

    print(f"Wrote {len(posts)} post(s) to posts.json")


if __name__ == "__main__":
    main()
