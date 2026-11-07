<#
Installs KidGuard on the child's phone over ADB and makes it the Device Owner (Windows PowerShell 5+).

How to run (right-click setup.ps1 is NOT enough; open PowerShell in this folder and run):
    powershell -ExecutionPolicy Bypass -File setup.ps1 [-Yes] [path\to\kidguard.apk]

Before: on the phone, remove every account (Settings > Accounts: Google, Samsung, Xiaomi...), turn on
USB debugging, plug it in. After setup you can sign in to the accounts again.
Undo: from the parent's page, Settings > "Gỡ quyền quản lý", then uninstall the app.
Steps are the same as docs/protocol.md "Setup steps" (setup.sh and web/install.html do the same).
#>
param([string]$Apk = "", [Alias("y")][switch]$Yes)
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
if ($PSScriptRoot) { Set-Location -Path $PSScriptRoot } else { Set-Location -Path (Get-Location).Path }
$Pkg = "dev.kidguard"
$Acc = "$Pkg/$Pkg.Acc"

# --- APK ---
if (-not $Apk) {
    foreach ($c in @("kidguard.apk", "..\build\kidguard.apk")) { if (Test-Path $c) { $Apk = $c; break } }
}
if (-not $Apk -or -not (Test-Path $Apk)) {
    Write-Host "[..] Không thấy file kidguard.apk, tải bản mới nhất…"
    $rel = "https://github.com/HoangTran0410/kidguard/releases/latest/download"
    $Apk = Join-Path $env:TEMP "kidguard.apk"
    Invoke-WebRequest -UseBasicParsing -Uri "$rel/kidguard.apk" -OutFile $Apk
    $want = $null
    try { $want = ((Invoke-WebRequest -UseBasicParsing -Uri "$rel/kidguard.apk.sha256").Content -split "\s+")[0].Trim().ToLower() } catch { }
    if ($want) {
        $got = (Get-FileHash -Algorithm SHA256 -Path $Apk).Hash.ToLower()
        if ($got -ne $want) { Write-Host "LỖI: Sai mã kiểm tra SHA-256 của kidguard.apk (muốn $want, được $got). Dừng."; exit 1 }
        Write-Host "OK Đã kiểm tra SHA-256 của kidguard.apk"
    } else {
        Write-Host "[!] Không có kidguard.apk.sha256 trên bản phát hành, không kiểm tra được tính toàn vẹn."
    }
}

# --- adb (offer to download platform-tools if missing) ---
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    $dir = Join-Path $env:LOCALAPPDATA "kidguard"
    $local = Join-Path $dir "platform-tools\adb.exe"
    if (-not (Test-Path $local)) {
        Write-Host "Máy chưa có adb (công cụ Google để nói chuyện với điện thoại, ~10 MB)."
        $ans = Read-Host "Tải về $dir ? [y/N]"
        if ($ans -notmatch '^[yY]') { Write-Host "Đã huỷ. Cài adb rồi chạy lại."; exit 1 }
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $zip = Join-Path $dir "pt.zip"
        Invoke-WebRequest -UseBasicParsing -Uri "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -OutFile $zip
        Expand-Archive -Path $zip -DestinationPath $dir -Force
        Remove-Item $zip -Force
    }
    $adb = $local
}

# Runs adb and returns its output as text (stderr merged, never throws on non-zero exit).
function Adb {
    $old = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    try { $o = & $adb @args 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $old }
    return ($o -join "`n").Replace("`r", "")
}

$devs = @((Adb devices) -split "`n" | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" })
if ($devs.Count -ne 1) {
    Write-Host "Cần đúng 1 máy đã cắm và cho phép gỡ lỗi USB (đang thấy: $($devs.Count))"
    Write-Host (Adb devices)
    exit 1
}

$model = (Adb shell getprop ro.product.model).Trim()
$ver = (Adb shell getprop ro.build.version.release).Trim()
Write-Host "[*] Máy đang cắm: $model (Android $ver)"
if (-not $Yes) {
    $ans = Read-Host "Đây là máy của con? [y/N]"
    if ($ans -notmatch '^[yY]') { Write-Host "Đã huỷ."; exit 1 }
}

# adb shell often exits 0 on failure: run a command, stop if the output looks like an error.
function Chk {
    $o = Adb shell @args
    if ($o -match "Error|Exception|denied|Failure") {
        Write-Host "LỖI: $o"
        # ColorOS blocks adb from granting permissions until "Disable permission monitoring" is on
        if ($o -match "UPDATE_APP_OPS_STATS|GRANT_RUNTIME_PERMISSIONS|WRITE_SECURE_SETTINGS") { Write-Host '→ Oppo/Realme/OnePlus (ColorOS): Tuỳ chọn nhà phát triển > bật "Tắt giám sát quyền" (Disable permission monitoring), rồi chạy lại.' }
        exit 1
    }
}

Write-Host "[1/8] Cài app"
$r = Adb install -r -g $Apk
if ($r -notmatch "Success") {
    Write-Host "LỖI: $r"
    if ($r -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") { Write-Host "→ Máy đã có bản KidGuard ký khác. Gỡ app cũ trước (Cài đặt > Ứng dụng > KidGuard > Gỡ), rồi chạy lại." }
    elseif ($r -match "INSTALL_FAILED_USER_RESTRICTED|INSTALL_FAILED_ABORTED") { Write-Host "→ Máy chặn cài qua USB. Xiaomi: bật `"Cài qua USB`" và `"Gỡ lỗi USB (Cài đặt bảo mật)`"; rồi bấm Cho phép trên máy." }
    elseif ($r -match "INSTALL_FAILED_OLDER_SDK") { Write-Host "→ Android của máy quá cũ cho bản KidGuard này." }
    elseif ($r -match "INSTALL_FAILED_INSUFFICIENT_STORAGE") { Write-Host "→ Máy hết bộ nhớ. Xoá bớt ứng dụng/ảnh rồi chạy lại." }
    elseif ($r -match "INSTALL_FAILED_VERSION_DOWNGRADE") { Write-Host "→ Máy có bản KidGuard mới hơn. Gỡ app cũ trước rồi chạy lại." }
    exit 1
}

Write-Host "[2/8] Đặt KidGuard làm Device Owner"
$out = Adb shell dpm set-device-owner "$Pkg/.Admin"
if ((Adb shell dumpsys device_policy) -notmatch [regex]::Escape("admin=ComponentInfo{$Pkg/$Pkg.Admin}")) {
    Write-Host $out
    Write-Host @"

Không đặt được Device Owner. Thường là vì:
  - Máy còn tài khoản: Cài đặt > Tài khoản, xoá hết (Google, Samsung, Xiaomi…), chạy lại.
  - Máy có nhiều người dùng / Không gian thứ hai / Thư mục bảo mật: xoá đi.
  - Xiaomi: bật thêm "Gỡ lỗi USB (Cài đặt bảo mật)" trong Tuỳ chọn nhà phát triển.
  - Đã có app quản lý khác (Device Owner): gỡ nó trước.
"@
    exit 1
}

Write-Host "[3/8] Cấp quyền xem thời gian dùng app"
Chk appops set $Pkg GET_USAGE_STATS allow

Write-Host "[4/8] Cấp quyền ghi cài đặt bảo mật"
Chk pm grant $Pkg android.permission.WRITE_SECURE_SETTINGS
# optional (remote phone settings): brightness / screen timeout and Do Not Disturb; old Android may not know them
Adb shell appops set $Pkg WRITE_SETTINGS allow | Out-Null
Adb shell cmd notification allow_dnd $Pkg | Out-Null

Write-Host "[5/8] Bật dịch vụ Trợ năng"
$cur = ((Adb shell settings get secure enabled_accessibility_services) -split "`n")[0].Trim()
if ($cur -eq "null") { $cur = "" }
if ((":$cur:") -notlike "*:${Acc}:*") {
    if ($cur) { $cur = "${cur}:${Acc}" } else { $cur = $Acc }
}
if ($cur.Contains("'")) { Write-Host "LỖI: Danh sách trợ năng có ký tự lạ: $cur"; exit 1 }
# adb shell re-parses the line on the phone: single-quote the value so "$" in old entries is not expanded
Chk "settings put secure enabled_accessibility_services '$cur'"
Chk settings put secure accessibility_enabled 1

Write-Host "[6/8] Cho chạy nền, không bị tiết kiệm pin"
Chk dumpsys deviceidle whitelist "+$Pkg"

Write-Host "[7/8] Mở app"
Chk am start -n "$Pkg/.MainActivity"
Start-Sleep -Seconds 3

Write-Host "[8/8] Địa chỉ cho bố mẹ"
$ip = ""
if ((Adb shell ip -4 addr show wlan0) -match "inet (\d+\.\d+\.\d+\.\d+)") { $ip = $Matches[1] }
if (-not $ip -and (Adb shell ip route get 1.1.1.1) -match "\bsrc (\d+\.\d+\.\d+\.\d+)") { $ip = $Matches[1] }
if (-not $ip) { $ip = "<IP của máy con>" }
Write-Host ""
Write-Host "OK Xong. Trên điện thoại bố mẹ (cùng Wi-Fi), mở: http://${ip}:8770"
Write-Host "-> rồi nhập mã 6 số đang hiện trên máy con. Giờ có thể đăng nhập lại tài khoản trên máy con."
# ColorOS: a Recents swipe is a real force-stop (alarms gone, Accessibility turned off)
if ("$(Adb shell 'getprop ro.build.version.opporom; getprop ro.build.version.oplusrom')".Trim()) {
    Write-Host '-> Oppo/Realme/OnePlus (ColorOS): mở Đa nhiệm, kéo thẻ KidGuard xuống để khoá (biểu tượng ổ khoá), và trong Quản lý điện thoại cho KidGuard "Tự khởi chạy" + chạy nền. Vuốt KidGuard khỏi Đa nhiệm trên ColorOS sẽ tắt hẳn app và Trợ năng.'
}
