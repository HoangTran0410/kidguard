#!/bin/bash
# Cache-bust the site stylesheet: /css/* is cached for a day (+7 days stale), so a page that links new classes
# must link a new URL. Run after editing site/css (scripts/build.sh calls it too).
set -e
cd "$(dirname "$0")/.."
H=$(shasum -a 256 site/css/site.css | cut -c1-10)
perl -pi -e 's#(href="(?:\.\./)?css/site\.css)(?:\?v=[0-9a-f]+)?"#$1?v='"$H"'"#g' site/index.html site/en/index.html
echo "site.css?v=$H"
