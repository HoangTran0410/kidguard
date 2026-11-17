#!/bin/sh
# Every release key in one file locked with a password (scripts/Keys.java), to keep away from this computer (Drive, USB stick):
#   scripts/keys.sh backup [file]   -> kidguard-keys-<date>.kgkeys (asks a password twice)
#   scripts/keys.sh list <file>     what is in it (asks the password: checks the copy opens)
#   scripts/keys.sh restore <file>  puts each key back in place; a different key already there is kept, the copy written beside it
# The password is never stored: lose it and the file is useless. Never commit the file (.gitignore: *.kgkeys).
# Not in it: the relay's FCM_SA secret (Cloudflare can't give it back; a new Firebase service-account key replaces it).
set -e
cd "$(dirname "$0")/.."
places="signing.jks=android/app/signing.jks kid-google-services.json=android/app/google-services.json parent-google-services.json=android/parent/google-services.json"
cmd=${1:-}; [ $# -gt 0 ] && shift
case "$cmd" in
  backup)  exec java scripts/Keys.java pack "${1:-kidguard-keys-$(date +%Y-%m-%d).kgkeys}" $places ;;
  list)    exec java scripts/Keys.java list "${1:?which file?}" ;;
  restore) exec java scripts/Keys.java unpack "${1:?which file?}" $places ;;
  *) sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
