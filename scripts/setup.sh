#!/usr/bin/env bash
# Installs KidGuard on the child's phone over ADB and makes it the Device Owner (macOS / Linux).
#   ./setup.sh [-y|--yes] [path/to/kidguard.apk]   (default: kidguard.apk next to this script or ../build/kidguard.apk, else the latest release)
# Before: on the phone, remove the phone accounts (Settings > Accounts: Google, Samsung, Xiaomi, Oppo...), turn on
# USB debugging, plug it in. Accounts of ordinary apps (Zalo, Facebook, Telegram...) stay: this script turns those apps
# off for a moment, restarts the phone once, makes KidGuard Device Owner, and turns the apps back on (always, also on error).
# After setup you can sign in to the phone accounts again.
# Several devices attached: export ANDROID_SERIAL=<serial> first.
# Downloaded APK is checked against kidguard.apk.sha256 and refused if that cannot be fetched (KG_ALLOW_UNVERIFIED=1 skips the check).
# Undo: from the parent's page, Settings > "Gỡ quyền quản lý", then uninstall the app.
# Steps are the same as docs/protocol.md "Setup steps" (setup.ps1 and ui/install.html do the same).
set -euo pipefail
# Language: Vietnamese when $LC_ALL / $LANG starts with "vi", else English. Override: KG_LANG=vi|en ./setup.sh
case "${KG_LANG:-${LC_ALL:-${LANG:-}}}" in vi*) KG_LANG=vi ;; *) KG_LANG=en ;; esac
T() { if [ "$KG_LANG" = vi ]; then printf '%s' "$1"; else printf '%s' "$2"; fi; }   # T "vi" "en"
L() { T "$1" "$2"; echo; }
# An APK path given on the command line is resolved against where you ran the script, before we cd next to it.
ARG_APK=""
for a in "$@"; do case "$a" in -y|--yes) ;; *) ARG_APK="$a" ;; esac; done
if [ -n "$ARG_APK" ]; then
  [ -f "$ARG_APK" ] || { L "LỖI: Không thấy file APK: $ARG_APK" "ERROR: APK file not found: $ARG_APK"; exit 1; }
  ARG_APK="$(cd "$(dirname "$ARG_APK")" && pwd)/$(basename "$ARG_APK")"
fi
cd "$(dirname "$0")"
PKG=dev.kidguard
ACC="$PKG/$PKG.Acc"

YES=0
if [ "${1:-}" = "-y" ] || [ "${1:-}" = "--yes" ]; then YES=1; shift; fi

# --- APK ---
APK="$ARG_APK"
if [ -z "$APK" ]; then
  for c in kidguard.apk ../build/kidguard.apk; do [ -f "$c" ] && APK=$c && break; done
fi
if [ -z "$APK" ]; then
  echo "[..] Không thấy file kidguard.apk, tải bản mới nhất…"
  REL=https://github.com/HoangTran0410/kidguard/releases/latest/download
  APK="$(mktemp -d)/kidguard.apk"
  curl -fL -o "$APK" "$REL/kidguard.apk"
  # fail closed: no checksum = stop, unless KG_ALLOW_UNVERIFIED=1 (you accept installing an unverified APK)
  if SUM=$(curl -fsL "$REL/kidguard.apk.sha256" 2>/dev/null) && [ -n "$SUM" ]; then
    want=$(printf '%s' "$SUM" | awk '{print tolower($1)}')
    if command -v sha256sum >/dev/null 2>&1; then got=$(sha256sum "$APK" | awk '{print $1}'); else got=$(shasum -a 256 "$APK" | awk '{print $1}'); fi
    if [ "$want" != "$got" ]; then echo "LỖI: Sai mã kiểm tra SHA-256 của kidguard.apk (muốn $want, được $got). Dừng."; exit 1; fi
    echo "OK Đã kiểm tra SHA-256 của kidguard.apk"
  elif [ "${KG_ALLOW_UNVERIFIED:-}" = "1" ]; then
    echo "[!] Không tải được kidguard.apk.sha256, bỏ qua kiểm tra vì KG_ALLOW_UNVERIFIED=1."
  else
    echo "LỖI: Không tải được kidguard.apk.sha256 nên không kiểm tra được tính toàn vẹn. Dừng."
    echo "→ Thử lại khi có mạng, hoặc truyền file APK đã tải: ./setup.sh kidguard.apk. Chấp nhận rủi ro: KG_ALLOW_UNVERIFIED=1 ./setup.sh"
    exit 1
  fi
fi

# --- adb (offer to download platform-tools if missing) ---
if ! command -v adb >/dev/null 2>&1; then
  TOOLS="$HOME/.kidguard/platform-tools"
  if [ ! -x "$TOOLS/platform-tools/adb" ]; then
    case "$(uname -s)" in
      Darwin) OS=darwin ;;
      Linux) OS=linux ;;
      *) echo "Cần adb (Android platform-tools): https://developer.android.com/tools/releases/platform-tools"; exit 1 ;;
    esac
    L "Máy chưa có adb (công cụ Google để nói chuyện với điện thoại, ~10 MB)." "adb (Google tool to talk to the phone, ~10 MB) is not installed."
    printf "$(T "Tải về %s ? [y/N] " "Download to %s ? [y/N] ")" "$TOOLS"
    read -r ans </dev/tty || ans=n
    case "$ans" in y|Y) ;; *) L "Đã huỷ. Cài adb rồi chạy lại." "Cancelled. Install adb and run again."; exit 1 ;; esac
    mkdir -p "$TOOLS"
    curl -fL -o "$TOOLS/pt.zip" "https://dl.google.com/android/repository/platform-tools-latest-$OS.zip"
    unzip -qo "$TOOLS/pt.zip" -d "$TOOLS"
    rm -f "$TOOLS/pt.zip"
  fi
  export PATH="$TOOLS/platform-tools:$PATH"
fi

devs=$(adb devices | tr -d '\r')
if printf '%s\n' "$devs" | awk 'NR>1 && $2=="unauthorized"{f=1} END{exit !f}'; then
  L "Máy đã cắm nhưng chưa cho phép gỡ lỗi USB: nhìn màn hình điện thoại, bấm \"Cho phép\" (tick \"Luôn cho phép\"), rồi chạy lại." "Phone is plugged in but USB debugging is not allowed: look at the phone screen, tap \"Allow\" (tick \"Always allow\"), then run again."
  printf '%s\n' "$devs"; exit 1
fi
if [ -n "${ANDROID_SERIAL:-}" ]; then
  adb devices | awk 'NR>1 && $2=="device"{print $1}' | grep -qxF "$ANDROID_SERIAL" || { L "Không thấy máy $ANDROID_SERIAL (ANDROID_SERIAL)" "Phone $ANDROID_SERIAL (ANDROID_SERIAL) not found"; adb devices; exit 1; }
else
  n=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
  if [ "$n" = "0" ]; then
    L "Không thấy máy nào. Kiểm tra lần lượt:" "No phone found. Check, one by one:"
    L "  1. Cáp: dùng cáp có truyền dữ liệu (không phải cáp chỉ sạc), cắm thẳng vào máy tính." "  1. Cable: use a data cable (not charge-only), plugged straight into the computer."
    L "  2. Trên máy con: kéo thông báo USB xuống, chọn \"Truyền tệp\" (File transfer), không chọn \"Chỉ sạc\"." "  2. On the phone: pull down the USB notification and pick \"File transfer\", not \"Charging only\"."
    L "  3. Mở khoá màn hình máy con, giữ màn hình sáng." "  3. Unlock the phone screen and keep it on."
    L "  4. Bật Gỡ lỗi USB (Tuỳ chọn nhà phát triển) và bấm \"Cho phép\" khi máy hỏi." "  4. Turn on USB debugging (Developer options) and tap \"Allow\" when the phone asks."
    L "  5. Windows: cài driver USB của hãng máy (Samsung, Xiaomi, Oppo…). macOS / Linux: không cần driver." "  5. Windows: install the phone maker's USB driver (Samsung, Xiaomi, Oppo...). macOS / Linux: no driver needed."
    exit 1
  fi
  [ "$n" = "1" ] || { L "Cần đúng 1 máy đã cắm và cho phép gỡ lỗi USB (đang thấy: $n). Nhiều máy: export ANDROID_SERIAL=<serial>." "Exactly 1 phone with USB debugging allowed is needed (found: $n). Several phones: export ANDROID_SERIAL=<serial>."; adb devices; exit 1; }
fi

model=$(adb shell getprop ro.product.model | tr -d '\r')
ver=$(adb shell getprop ro.build.version.release | tr -d '\r')
L "[*] Máy đang cắm: $model (Android $ver)" "[*] Phone plugged in: $model (Android $ver)"
if [ "$YES" != "1" ]; then
  printf "$(T "Đây là máy của con? [y/N] " "Is this your child's phone? [y/N] ")"
  read -r ans </dev/tty || ans=n
  case "$ans" in y|Y) ;; *) L "Đã huỷ." "Cancelled."; exit 1 ;; esac
fi

# true when KidGuard is Device Owner (captured first, no `| grep -q`: with pipefail an early-closed pipe reads as failure)
is_do() { local d; d=$(adb shell dumpsys device_policy 2>/dev/null || true); case "$d" in *"admin=ComponentInfo{$PKG/$PKG.Admin}"*) return 0 ;; *) return 1 ;; esac; }

# adb shell often exits 0 on failure: run a command, show its output, stop if it looks like an error.
chk() {
  local out
  out=$(adb shell "$@" 2>&1 | tr -d '\r') || true
  if printf '%s' "$out" | grep -Eqi 'Error|Exception|denied|Failure'; then
    echo "LỖI: $out"
    # ColorOS blocks adb from granting permissions until "Disable permission monitoring" is on
    if printf '%s' "$out" | grep -Eq 'UPDATE_APP_OPS_STATS|GRANT_RUNTIME_PERMISSIONS|WRITE_SECURE_SETTINGS'; then
      echo '→ Oppo/Realme/OnePlus (ColorOS): Tuỳ chọn nhà phát triển > bật "Tắt giám sát quyền" (Disable permission monitoring), rồi chạy lại.'
    fi
    exit 1
  fi
}

# --- app accounts are kept: their apps are turned off during the Device Owner step, then always turned back on ---
OFFFILE="$HOME/.kidguard/apps-off.txt"   # survives a crash / Ctrl-C: the next run turns them on first
OFF=""; REENABLE_FAILED=0
reenable() {
  local list="$OFF" p i out left=""
  [ -n "$list" ] || return 0
  OFF=""
  echo "[*] Bật lại các app đã tắt tạm:$list"
  for i in $(seq 1 60); do adb shell true >/dev/null 2>&1 && break; sleep 2; done   # the phone may still be restarting
  for p in $list; do
    out=$(adb shell pm enable "$p" 2>&1 | tr -d '\r' || true); echo "$out"
    case "$out" in *enabled*) ;; *) left="$left $p" ;; esac
  done
  if [ -n "$left" ]; then   # keep the record of the ones that failed: the next run turns them on first
    mkdir -p "$(dirname "$OFFFILE")"; printf '%s' "${left# }" > "$OFFFILE"
    echo "[!] Chưa bật lại được:$left. Cắm máy rồi chạy lại script này (sẽ bật lại trước), hoặc trên máy: Cài đặt > Ứng dụng, bật các app đó."
    REENABLE_FAILED=1
  else
    rm -f "$OFFFILE"
  fi
}
trap reenable EXIT
trap 'exit 130' INT TERM
if [ -s "$OFFFILE" ]; then OFF=$(cat "$OFFFILE"); echo "[!] Lần chạy trước còn app bị tắt tạm, bật lại trước."; reenable; [ "$REENABLE_FAILED" = 0 ] || exit 1; fi

echo "[1/8] Cài app"
if ! out=$(adb install -r -g "$APK" 2>&1) || [[ "$out" != *Success* ]]; then
  echo "LỖI: $out"
  case "$out" in
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*) echo "→ Máy đã có bản KidGuard ký khác. Gỡ app cũ trước (Cài đặt > Ứng dụng > KidGuard > Gỡ), rồi chạy lại." ;;
    *INSTALL_FAILED_USER_RESTRICTED*|*INSTALL_FAILED_ABORTED*) echo "→ Máy chặn cài qua USB. Xiaomi: bật \"Cài qua USB\" và \"Gỡ lỗi USB (Cài đặt bảo mật)\"; rồi bấm Cho phép trên máy." ;;
    *INSTALL_FAILED_OLDER_SDK*) echo "→ Android của máy quá cũ cho bản KidGuard này." ;;
    *INSTALL_FAILED_INSUFFICIENT_STORAGE*) echo "→ Máy hết bộ nhớ. Xoá bớt ứng dụng/ảnh rồi chạy lại." ;;
    *INSTALL_FAILED_VERSION_DOWNGRADE*) echo "→ Máy có bản KidGuard mới hơn. Gỡ app cũ trước rồi chạy lại." ;;
  esac
  exit 1
fi

# Accounts block Device Owner. App accounts (authenticator = ordinary app) are kept: turn that app off (its accounts
# stop counting), restart once (Android caches the check until boot), then set Device Owner and turn the apps back on.
# Accounts of system apps (Google, Xiaomi, Samsung, Oppo...) must be removed by hand.
if ! is_do; then
  dump=$(adb shell dumpsys account | tr -d '\r')
  accts=$(printf '%s\n' "$dump" | sed -n 's/.*Account {name=\([^,}]*\), type=\([^}]*\)}.*/\2|\1/p' | sort -u)
  if [ -n "$accts" ]; then
    auth=$(printf '%s\n' "$dump" | sed -n 's/.*AuthenticatorDescription {type=\([^}]*\)}, ComponentInfo{\([^/}]*\)\/.*/\1|\2/p')
    third=$(adb shell pm list packages -3 | tr -d '\r' | sed 's/^package://')
    disable=""; remove=""
    while IFS='|' read -r type name; do
      pkg=$(printf '%s\n' "$auth" | awk -F'|' -v t="$type" '$1==t{print $2; exit}')
      [ -n "$pkg" ] || continue   # authenticator not listed: the account is hidden already
      if grep -qxF "$pkg" <<<"$third" && [[ "$pkg" =~ ^[A-Za-z0-9_.]+$ ]]; then
        case " $disable " in *" $pkg "*) ;; *) disable="$disable $pkg" ;; esac
      else
        remove="$remove
  - $name ($type)"
      fi
    done <<EOA
$accts
EOA
    if [ -n "$remove" ]; then
      echo "LỖI: Cần xoá các tài khoản của máy trước:$remove"
      echo "→ Cài đặt > Mật khẩu và tài khoản, xoá chúng rồi chạy lại. Tài khoản của app (Zalo, Facebook…) cứ để nguyên. Cài xong đăng nhập lại, dữ liệu vẫn còn."
      exit 1
    fi
    if [ -n "$disable" ]; then
      echo "[*] Giữ đăng nhập các app:$disable"
      echo "    Tắt tạm các app này, khởi động lại máy một lần (khoảng 1 phút), rồi cài tiếp. Sẽ tự bật lại các app."
      mkdir -p "$(dirname "$OFFFILE")"; printf '%s' "${disable# }" > "$OFFFILE"
      OFF="${disable# }"
      for p in $OFF; do
        out=$(adb shell pm disable-user --user 0 "$p" 2>&1 | tr -d '\r' || true)
        case "$out" in *disabled*) ;; *) echo "LỖI: không tắt được $p: $out"; exit 1 ;; esac
      done
      sleep 14; adb shell sync   # Android writes the app state to disk ~10 s later: rebooting sooner loses it
      echo "[*] Khởi động lại máy…"
      adb reboot
      sleep 5; adb wait-for-device
      for i in $(seq 1 90); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 2; done
      [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] || { echo "LỖI: máy không khởi động xong sau 3 phút (các app sẽ được bật lại khi máy lên)."; exit 1; }
      sleep 3
    fi
  fi
fi

echo "[2/8] Đặt KidGuard làm Device Owner"
out=$(adb shell dpm set-device-owner $PKG/.Admin 2>&1 || true)
# after our restart Android still says "accounts" for up to ~30 s (it re-checks them in the background): try again
for i in $(seq 1 24); do
  [ -n "$OFF" ] || break
  case "$out" in *[Aa]ccounts*) ;; *) break ;; esac
  echo "    Máy đang kiểm tra lại tài khoản, thử lại sau 5 giây…"; sleep 5
  out=$(adb shell dpm set-device-owner $PKG/.Admin 2>&1 || true)
done
if ! is_do; then
  echo "$out"
  cat <<'EOT'

Không đặt được Device Owner. Thường là vì:
  - Máy còn tài khoản của máy: Cài đặt > Tài khoản, xoá hết (Google, Samsung, Xiaomi…), chạy lại.
  - Máy có nhiều người dùng / Không gian thứ hai / Thư mục bảo mật: xoá đi.
  - Xiaomi: bật thêm "Gỡ lỗi USB (Cài đặt bảo mật)" trong Tuỳ chọn nhà phát triển.
  - Đã có app quản lý khác (Device Owner): gỡ nó trước.
EOT
  exit 1
fi

echo "[3/8] Cấp quyền xem thời gian dùng app"
chk appops set $PKG GET_USAGE_STATS allow

echo "[4/8] Cấp quyền ghi cài đặt bảo mật"
chk pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
# optional (remote phone settings): brightness / screen timeout and Do Not Disturb; old Android may not know them
adb shell appops set $PKG WRITE_SETTINGS allow >/dev/null 2>&1 || true
adb shell cmd notification allow_dnd $PKG >/dev/null 2>&1 || true
adb shell cmd notification allow_listener $PKG/$PKG.NotifDots >/dev/null 2>&1 || true
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true   # bank app reopen (basic mode)
adb shell appops set $PKG REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1 || true   # self-update without asking (basic mode)

echo "[5/8] Bật dịch vụ Trợ năng"
cur=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r' | head -n1)
[ "$cur" = "null" ] && cur=""
case ":$cur:" in
  *":$ACC:"*) ;;
  *) cur="${cur:+$cur:}$ACC" ;;
esac
# adb shell re-parses the line on the phone: single-quote the value so "$" in old entries is not expanded
case "$cur" in *"'"*) echo "LỖI: Danh sách trợ năng có ký tự lạ: $cur"; exit 1 ;; esac
chk "settings put secure enabled_accessibility_services '$cur'"
chk settings put secure accessibility_enabled 1

echo "[6/8] Cho chạy nền, không bị tiết kiệm pin"
chk dumpsys deviceidle whitelist +$PKG

echo "[7/8] Mở app"
chk am start -n $PKG/.MainActivity
sleep 3

echo "[8/8] Địa chỉ cho bố mẹ"
ip=$(adb shell ip -4 addr show wlan0 2>/dev/null | awk '/inet /{print $2}' | cut -d/ -f1 | tr -d '\r')
[ -n "$ip" ] || ip=$(adb shell ip route get 1.1.1.1 2>/dev/null | tr -d '\r' | awk '{for(i=1;i<NF;i++) if($i=="src"){print $(i+1); exit}}')
reenable
echo
L "OK Xong. Trên điện thoại bố mẹ (cùng Wi-Fi), mở: http://${ip:-<IP của máy con>}:8770" "OK Done. On the parent's phone (same Wi-Fi), open: http://${ip:-<child phone IP>}:8770"
L "-> rồi nhập mã ghép 6 số: màn hình cuối trên máy con hiện mã trong 10 phút (sau đó: giữ nút \"Bố mẹ: hiện mã ghép\" 5 giây), và đặt PIN trong form ghép." "-> then type the 6-digit pairing code: the last screen on the child's phone shows it for 10 minutes (later: hold \"Bố mẹ: hiện mã ghép\" for 5 seconds), and set a PIN in the pairing form."
L "-> Giờ có thể đăng nhập lại tài khoản của máy (Google, Samsung…) trên máy con." "-> You can now sign in to the phone accounts (Google, Samsung...) on the child's phone again."
# ColorOS: a Recents swipe is a real force-stop (alarms gone, Accessibility turned off)
if [ -n "$(adb shell 'getprop ro.build.version.opporom; getprop ro.build.version.oplusrom' | tr -d '[:space:]')" ]; then
  echo '-> Oppo/Realme/OnePlus (ColorOS): mở Đa nhiệm, kéo thẻ KidGuard xuống để khoá (biểu tượng ổ khoá), và trong Quản lý điện thoại cho KidGuard "Tự khởi chạy" + chạy nền. Vuốt KidGuard khỏi Đa nhiệm trên ColorOS sẽ tắt hẳn app và Trợ năng.'
fi
