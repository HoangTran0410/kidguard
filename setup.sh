#!/usr/bin/env bash
# Installs KidGuard on the child's phone over ADB and makes it the Device Owner.
#   ./setup.sh [path/to/kidguard.apk]       (default: build/kidguard.apk, else the latest release)
# Before: on the phone, remove every account (Settings > Accounts: Google, Samsung, Xiaomi...), turn on
# USB debugging, plug it in. After setup you can sign in to the accounts again.
# Undo: from the parent's page, Settings > "Gỡ quyền quản lý", then uninstall the app.
set -euo pipefail
cd "$(dirname "$0")"
PKG=dev.kidguard
APK="${1:-build/kidguard.apk}"
if [ ! -f "$APK" ]; then
  echo "Không thấy $APK, tải bản mới nhất…"
  APK=/tmp/kidguard.apk
  curl -fL -o "$APK" https://github.com/HoangTran0410/kidguard/releases/latest/download/kidguard.apk
fi
command -v adb >/dev/null || { echo "Cần adb (Android platform-tools)"; exit 1; }
n=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
[ "$n" = "1" ] || { echo "Cần đúng 1 máy đã cắm và cho phép gỡ lỗi USB (đang thấy: $n)"; adb devices; exit 1; }

echo "1/4 Cài app"
adb install -r -g "$APK" >/dev/null

echo "2/4 Đặt KidGuard làm Device Owner"
out=$(adb shell dpm set-device-owner $PKG/.Admin 2>&1 || true)
if ! adb shell dumpsys device_policy | grep -q "admin=ComponentInfo{$PKG/$PKG.Admin}"; then
  echo "$out"
  cat <<'EOF'

Không đặt được Device Owner. Thường là vì:
  - Máy còn tài khoản: Cài đặt > Tài khoản, xoá hết (Google, Samsung, Xiaomi…), chạy lại.
  - Máy có nhiều người dùng / Không gian thứ hai / Thư mục bảo mật: xoá đi.
  - Xiaomi: bật thêm "Gỡ lỗi USB (Cài đặt bảo mật)" trong Tuỳ chọn nhà phát triển.
  - Đã có app quản lý khác (Device Owner): gỡ nó trước.
EOF
  exit 1
fi

echo "3/4 Cấp quyền xem thời gian dùng app"
adb shell appops set $PKG GET_USAGE_STATS allow
adb shell dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1 || true

echo "4/4 Mở app"
adb shell am start -n $PKG/.MainActivity >/dev/null
sleep 3
ip=$(adb shell ip -4 addr show wlan0 2>/dev/null | awk '/inet /{print $2}' | cut -d/ -f1 | tr -d '\r')
echo
echo "Xong. Trên điện thoại bố mẹ (cùng Wi-Fi), mở: http://${ip:-<IP của máy con>}:8770"
echo "rồi nhập mã 6 số đang hiện trên máy con. Giờ có thể đăng nhập lại tài khoản trên máy con."
