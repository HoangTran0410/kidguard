#!/bin/bash
# Cache-bust the site's stylesheet and images: /css/* and /img/* are cached for a day (+7 days stale), so a changed
# file must be linked under a new URL. Appends ?v=<first 10 hex of sha256> to every css/site.css and img/<file> reference
# in both pages (relative, ../ and absolute og:image / JSON-LD URLs alike). Idempotent: an old ?v= is replaced.
# Run after editing website/css or website/img (scripts/build.sh calls it too).
set -e
cd "$(dirname "$0")/.."
PAGES="website/index.html website/en/index.html"
H=$(shasum -a 256 website/css/site.css | cut -c1-10)
perl -pi -e 's#(href="(?:\.\./)?css/site\.css)(?:\?v=[0-9a-f]+)?"#$1?v='"$H"'"#g' $PAGES
echo "site.css?v=$H"
for f in website/img/*; do
  [ -f "$f" ] || continue
  n=$(basename "$f"); h=$(shasum -a 256 "$f" | cut -c1-10)
  N="$n" H="$h" perl -pi -e 's#(img/\Q$ENV{N}\E)(?:\?v=[0-9a-f]+)?(?=")#$1?v=$ENV{H}#g' $PAGES
  echo "img/$n?v=$h"
done
