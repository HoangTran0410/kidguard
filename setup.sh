#!/usr/bin/env bash
# Installs KidGuard on the child's phone over ADB and makes it the Device Owner (macOS / Linux).
#   ./setup.sh [path/to/kidguard.apk]   (default: kidguard.apk or build/kidguard.apk here, else the latest release)
# Before: on the phone, remove every account (Settings > Accounts: Google, Samsung, Xiaomi...), turn on
# USB debugging, plug it in. After setup you can sign in to the accounts again.
# Undo: from the parent's page, Settings > "Gỡ quyền quản lý", then uninstall the app.
# Steps are the same as docs/protocol.md "Setup steps" (setup.ps1 and web/install.html do the same).
set -euo pipefail
cd "$(dirname "$0")"
PKG=dev.kidguard
ACC="$PKG/$PKG.Acc"

# --- APK ---
APK="${1:-}"
if [ -z "$APK" ]; then
  for c in kidguard.apk build/kidguard.apk; do [ -f "$c" ] && APK=$c && break; done
fi
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "Không thấy file kidguard.apk, tải bản mới nhất…"
  APK="$(mktemp -d)/kidguard.apk"
  curl -fL -o "$APK" https://github.com/HoangTran0410/kidguard/releases/latest/download/kidguard.apk
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
    echo "Máy chưa có adb (công cụ Google để nói chuyện với điện thoại, ~10 MB)."
    printf "Tải về %s ? [y/N] " "$TOOLS"
    read -r ans </dev/tty || ans=n
    case "$ans" in y|Y) ;; *) echo "Đã huỷ. Cài adb rồi chạy lại."; exit 1 ;; esac
    mkdir -p "$TOOLS"
    curl -fL -o "$TOOLS/pt.zip" "https://dl.google.com/android/repository/platform-tools-latest-$OS.zip"
    unzip -qo "$TOOLS/pt.zip" -d "$TOOLS"
    rm -f "$TOOLS/pt.zip"
  fi
  export PATH="$TOOLS/platform-tools:$PATH"
fi

n=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
[ "$n" = "1" ] || { echo "Cần đúng 1 máy đã cắm và cho phép gỡ lỗi USB (đang thấy: $n)"; adb devices; exit 1; }

echo "1/8 Cài app"
adb install -r -g "$APK" >/dev/null

echo "2/8 Đặt KidGuard làm Device Owner"
out=$(adb shell dpm set-device-owner $PKG/.Admin 2>&1 || true)
if ! adb shell dumpsys device_policy | grep -q "admin=ComponentInfo{$PKG/$PKG.Admin}"; then
  echo "$out"
  cat <<'EOT'

Không đặt được Device Owner. Thường là vì:
  - Máy còn tài khoản: Cài đặt > Tài khoản, xoá hết (Google, Samsung, Xiaomi…), chạy lại.
  - Máy có nhiều người dùng / Không gian thứ hai / Thư mục bảo mật: xoá đi.
  - Xiaomi: bật thêm "Gỡ lỗi USB (Cài đặt bảo mật)" trong Tuỳ chọn nhà phát triển.
  - Đã có app quản lý khác (Device Owner): gỡ nó trước.
EOT
  exit 1
fi

echo "3/8 Cấp quyền xem thời gian dùng app"
adb shell appops set $PKG GET_USAGE_STATS allow

echo "4/8 Cấp quyền ghi cài đặt bảo mật"
adb shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS

echo "5/8 Bật dịch vụ Trợ năng"
cur=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r' | head -n1)
[ "$cur" = "null" ] && cur=""
case ":$cur:" in
  *":$ACC:"*) ;;
  *) cur="${cur:+$cur:}$ACC" ;;
esac
adb shell settings put secure enabled_accessibility_services "$cur"
adb shell settings put secure accessibility_enabled 1

echo "6/8 Cho chạy nền, không bị tiết kiệm pin"
adb shell dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1 || true

echo "7/8 Mở app"
adb shell am start -n $PKG/.MainActivity >/dev/null
sleep 3

echo "8/8 Địa chỉ cho bố mẹ"
ip=$(adb shell ip -4 addr show wlan0 2>/dev/null | awk '/inet /{print $2}' | cut -d/ -f1 | tr -d '\r')
echo
echo "Xong. Trên điện thoại bố mẹ (cùng Wi-Fi), mở: http://${ip:-<IP của máy con>}:8770"
echo "rồi nhập mã 6 số đang hiện trên máy con. Giờ có thể đăng nhập lại tài khoản trên máy con."
