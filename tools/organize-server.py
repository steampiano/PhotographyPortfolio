#!/usr/bin/env python3
"""Local helper server for the photography portfolio's authoring tools.

Serves the project statically (like `python3 -m http.server`) AND adds two
POST endpoints used by organize-featured.html:

  POST /api/upload      Add a new photo: the image is saved into photos/
                        (into an event-named subfolder if an event is given,
                        else photos/ root), a matching .txt with its metadata
                        is written, and build.py regenerates thumbnails,
                        previews, and posts.json.

  POST /api/save-order  Write photos/meta/featured-order.txt directly from the
                        order in the tool (no download-and-move step), then
                        rebuild so posts.json reflects it.

Both accept a JSON body (the image is sent base64-encoded) so no third-party
multipart-parsing dependency is needed — the stdlib `cgi` module was removed
in Python 3.13, so JSON+base64 is the portable path.

Run it via the "Organize Featured.command" launcher, or directly:
    python3 tools/organize-server.py
Then open  http://localhost:8000/organize-featured.html

Localhost-only (binds 127.0.0.1); this is a personal authoring tool, never
deployed. Nothing here is part of the live site.
"""

import base64
import json
import os
import subprocess
import sys
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PHOTOS_DIR = os.path.join(BASE_DIR, "photos")
META_DIR = os.path.join(PHOTOS_DIR, "meta")
BUILD_SCRIPT = os.path.join(BASE_DIR, "build.py")
FEATURED_ORDER_FILE = os.path.join(META_DIR, "featured-order.txt")
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".gif"}
PORT = 8000

FEATURED_ORDER_HEADER = (
    "# Featured carousel order.\n"
    "# List photo filenames (one per line) in the order you want them shown in the\n"
    '# "Featured" carousel. A photo is featured by putting `featured: yes` in its\n'
    "# .txt file; this file only controls the ORDER of those featured photos.\n"
    "# Lines starting with # are ignored. The extension is optional. Photos not\n"
    "# listed here appear after the listed ones, newest first.\n\n"
)


def run_build():
    """Regenerate thumbnails/previews/posts.json. Returns (ok, detail)."""
    result = subprocess.run(
        [sys.executable, BUILD_SCRIPT],
        capture_output=True, text=True,
    )
    return result.returncode == 0, (result.stderr or result.stdout)[-800:]


class Handler(SimpleHTTPRequestHandler):
    def _send_json(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        length = int(self.headers.get("Content-Length", 0))
        return json.loads(self.rfile.read(length))

    def do_POST(self):
        try:
            if self.path == "/api/upload":
                return self.handle_upload()
            if self.path == "/api/save-order":
                return self.handle_save_order()
            self._send_json(404, {"error": "unknown endpoint"})
        except Exception as e:  # noqa: BLE001 - surface any error to the tool
            self._send_json(500, {"error": str(e)})

    def handle_upload(self):
        data = self._read_json()

        # basename() strips any path components → prevents path traversal.
        filename = os.path.basename((data.get("filename") or "").strip())
        base, ext = os.path.splitext(filename)
        if not base or ext.lower() not in IMAGE_EXTS:
            return self._send_json(400, {"error": f"Unsupported or missing file type: {filename!r}"})

        event = (data.get("event") or "").strip()
        caption = (data.get("caption") or "").strip()
        featured = bool(data.get("featured", True))

        # Normalize each handle to exactly one leading @, matching the
        # Add Photo Info app so handles are consistent site-wide.
        raw_people = (data.get("people") or "").strip()
        handles = ["@" + h.strip().lstrip("@") for h in raw_people.split(",") if h.strip()]
        people = ", ".join(handles)

        dest_dir = os.path.join(PHOTOS_DIR, os.path.basename(event)) if event else PHOTOS_DIR
        os.makedirs(dest_dir, exist_ok=True)

        image_path = os.path.join(dest_dir, filename)
        if os.path.exists(image_path):
            return self._send_json(409, {"error": f"{filename} already exists — rename it or remove the existing one first."})

        b64 = data.get("imageBase64") or ""
        if "," in b64:  # strip a data:...;base64, prefix if present
            b64 = b64.split(",", 1)[1]
        image_bytes = base64.b64decode(b64)
        with open(image_path, "wb") as f:
            f.write(image_bytes)

        # Metadata .txt, matching build.py's expected format.
        meta = ""
        if event:
            meta += f"event: {event}\n"
        meta += f"featured: {'yes' if featured else 'no'}\n"
        if people:
            meta += f"people: {people}\n"
        text = meta + ("\n" + caption + "\n" if caption else "")
        with open(os.path.join(dest_dir, base + ".txt"), "w", encoding="utf-8") as f:
            f.write(text)

        ok, detail = run_build()
        if not ok:
            return self._send_json(500, {"error": "build.py failed", "detail": detail})

        rel = os.path.relpath(image_path, BASE_DIR).replace(os.sep, "/")
        self._send_json(200, {"ok": True, "image": rel})

    def handle_save_order(self):
        data = self._read_json()
        lines = [str(x).strip() for x in data.get("lines", []) if str(x).strip()]
        os.makedirs(META_DIR, exist_ok=True)
        with open(FEATURED_ORDER_FILE, "w", encoding="utf-8") as f:
            f.write(FEATURED_ORDER_HEADER + "\n".join(lines) + "\n")
        ok, detail = run_build()
        if not ok:
            return self._send_json(500, {"error": "build.py failed", "detail": detail})
        self._send_json(200, {"ok": True})

    def log_message(self, fmt, *args):
        # Quieter logging: only show API calls, not every static asset request.
        if isinstance(args[0], str) and "/api/" in args[0]:
            super().log_message(fmt, *args)


def main():
    os.chdir(BASE_DIR)
    handler = partial(Handler, directory=BASE_DIR)
    try:
        httpd = ThreadingHTTPServer(("127.0.0.1", PORT), handler)
    except OSError as e:
        print(f"Could not start on port {PORT}: {e}")
        print("If another server is already running on 8000, stop it first.")
        sys.exit(1)
    print(f"Serving {BASE_DIR}")
    print(f"Open  http://localhost:{PORT}/organize-featured.html")
    print("Press Ctrl-C to stop.")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
