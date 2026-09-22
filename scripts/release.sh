#!/usr/bin/env bash
set -u
JOB_ID="$1"
TAG="build-$JOB_ID"
OUT=$(find work/app/build/outputs -type f \( -name '*.apk' -o -name '*.aab' \) 2>/dev/null | head -n 1)
mkdir -p dist
if [ -n "$OUT" ]; then
  EXT="${OUT##*.}"
  cp "$OUT" "dist/sublite-$JOB_ID.$EXT"
  gh release create "$TAG" "dist/sublite-$JOB_ID.$EXT" --title "$TAG" --notes "Sublite build $JOB_ID" || \
    gh release upload "$TAG" "dist/sublite-$JOB_ID.$EXT" --clobber
else
  tail -c 60000 build.log > dist/build-failed.log 2>/dev/null || echo "build failed early" > dist/build-failed.log
  gh release create "$TAG" dist/build-failed.log --title "$TAG" --notes "Sublite build $JOB_ID failed" || \
    gh release upload "$TAG" dist/build-failed.log --clobber
fi
