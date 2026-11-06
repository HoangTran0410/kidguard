#!/usr/bin/env bash
# Rebuilds vendor/tango.iife.js (Tango ADB libs at pinned versions, one copy of shared deps) and inlines it into
# install.html between the TANGO-BEGIN / TANGO-END markers. The worker only serves the html as text, so it is inlined.
set -euo pipefail
cd "$(dirname "$0")"
T=$(mktemp -d)
cp entry.js "$T/"
(cd "$T" && npm init -y >/dev/null && npm i @yume-chan/adb@2.6.4 @yume-chan/adb-daemon-webusb@2.3.2 @yume-chan/adb-credential-web@2.1.0 esbuild >/dev/null \
  && npx esbuild entry.js --bundle --format=iife --global-name=Tango --minify --target=es2022 --outfile=tango.iife.js)
cp "$T/tango.iife.js" tango.iife.js
python3 - <<'P'
import re
h=open('../install.html',encoding='utf-8').read(); j=open('tango.iife.js',encoding='utf-8').read()
h=re.sub(r'(<!--TANGO-BEGIN-->).*?(<!--TANGO-END-->)',lambda m:m.group(1)+'<script>'+j+'</script>'+m.group(2),h,flags=re.S)
open('../install.html','w',encoding='utf-8').write(h)
P
