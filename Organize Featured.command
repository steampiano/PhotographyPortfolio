#!/bin/bash
# Double-click to launch the Featured-order tool with photo uploading enabled.
# Starts the local helper server and opens the tool in your browser.
# Close this Terminal window (or press Ctrl-C) when you're done.

cd "/Users/kev/Claude/photography-portfolio" || { echo "Project folder not found."; read -n 1 -s -r -p "Press any key to close."; exit 1; }

# Open the tool once the server has had a moment to start.
( sleep 1; open "http://localhost:8000/organize-featured.html" ) &

echo "Starting the Featured-order tool..."
python3 tools/organize-server.py

echo ""
read -n 1 -s -r -p "Server stopped. Press any key to close this window."
echo ""
