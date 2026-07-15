#!/usr/bin/env python3
"""Force-refetches every tagged person's Instagram avatar, overwriting
whatever's cached in avatars/ — use this when someone's changed their
profile picture and you want the site to pick it up.

Normal publishing (build.py, via Publish.app) only fetches avatars for
*new* handles it hasn't seen before, so it never re-checks someone whose
picture already exists locally. This script is the deliberate, separate
step for refreshing existing ones. Run it directly, or via
"Refresh Avatars.app" if you've compiled one (see tools/refresh_avatars.applescript).
"""

import json
import os
import sys

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, BASE_DIR)

import build  # noqa: E402


def main():
    posts_path = os.path.join(BASE_DIR, "posts.json")
    if not os.path.exists(posts_path):
        print("posts.json not found — run build.py first.")
        return

    with open(posts_path, "r", encoding="utf-8") as f:
        posts = json.load(f)

    handles = sorted({p for post in posts for p in post.get("people", [])})
    print(f"Refreshing {len(handles)} avatar(s)...")
    updated = build.fetch_avatars_for(posts, force=True)
    build.write_avatar_colors()
    print(f"Done: refreshed {updated}/{len(handles)} avatar(s).")


if __name__ == "__main__":
    main()
