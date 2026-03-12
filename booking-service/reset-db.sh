#!/bin/sh
# Reset the H2 database so the app will recreate Hallam Line (Leeds → Sheffield) trips on next startup.
# Run this while the backend is stopped, then start the backend again.
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -d "$DIR/data" ]; then
  rm -rf "$DIR/data"
  echo "Deleted $DIR/data - restart the backend to seed 4 trains and 12 Leeds → Sheffield trips."
else
  echo "No data directory found - backend will create it on first run."
fi
