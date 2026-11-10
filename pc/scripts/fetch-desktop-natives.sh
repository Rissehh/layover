#!/usr/bin/env bash
# Downloads the desktop video-player libraries (libmpv and friends) that are too big to keep in this repo.
# They come from the upstream NuvioDesktop project at the commit this code was built against.
# Run once from the phone-and-pc folder before building the PC app:  ./scripts/fetch-desktop-natives.sh
# The phone (Android) build does not need them.
set -euo pipefail

COMMIT="09bbaeb7c32ced60c45428d88bf58bf6f7694495"
BASE="https://media.githubusercontent.com/media/NuvioMedia/NuvioDesktop/$COMMIT"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

while IFS= read -r rel; do
  rel="${rel%$'\r'}"
  [ -z "$rel" ] && continue
  dest="$ROOT/$rel"
  [ -f "$dest" ] && continue
  mkdir -p "$(dirname "$dest")"
  echo "$rel"
  curl -fsSL "$BASE/$rel" -o "$dest"
done < "$ROOT/scripts/desktop-natives.txt"
echo "Done."
