<#
Installs KidGuard on the child's phone over ADB and makes it the Device Owner (Windows PowerShell 5+).

How to run (right-click setup.ps1 is NOT enough; open PowerShell in this folder and run):
    powershell -ExecutionPolicy Bypass -File setup.ps1 [path\to\kidguard.apk]

Before: on the phone, remove every account (Settings > Accounts: Google, Samsung, Xiaomi...), turn on
USB debugging, plug it in. After setup you can sign in to the accounts again.
Undo: from the parent's page, Settings > "Gỡ quyền quản lý", then uninstall the app.
Steps are the same as docs/protocol.md "Setup steps" (setup.sh and web/install.html do the same).
#>
param([string]$Apk = "")
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Set-Location -Path $PSScriptRoot
$Pkg = "dev.kidguard"
$Acc = "$Pkg/$Pkg.Acc"

# --- APK ---
if (-not $Apk) {
    foreach ($c in @("kidguard.apk", "build\kidguard.apk")) { if (Test-Path $c) { $Apk = $c; break } }
}
if (-not $Apk -or -not (Test-Path $Apk)) {
    Write-Host "Không thấy file kidguard.apk, tải bản mới nhất…"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $Apk = Join-Path $env:TEMP "kidguard.apk"
    Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/HoangTran0410/kidguard/releases/latest/download/kidguard.apk" -OutFile $Apk
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
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
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

Write-Host "1/8 Cài app"
$r = Adb install -r -g $Apk
if ($r -notmatch "Success") { Write-Host $r; exit 1 }

Write-Host "2/8 Đặt KidGuard làm Device Owner"
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

Write-Host "3/8 Cấp quyền xem thời gian dùng app"
Adb shell appops set $Pkg GET_USAGE_STATS allow | Out-Null

Write-Host "4/8 Cấp quyền ghi cài đặt bảo mật"
Adb shell pm grant $Pkg android.permission.WRITE_SECURE_SETTINGS | Out-Null

Write-Host "5/8 Bật dịch vụ Trợ năng"
$cur = ((Adb shell settings get secure enabled_accessibility_services) -split "`n")[0].Trim()
if ($cur -eq "null") { $cur = "" }
if ((":$cur:") -notlike "*:${Acc}:*") {
    if ($cur) { $cur = "${cur}:${Acc}" } else { $cur = $Acc }
}
Adb shell settings put secure enabled_accessibility_services $cur | Out-Null
Adb shell settings put secure accessibility_enabled 1 | Out-Null

Write-Host "6/8 Cho chạy nền, không bị tiết kiệm pin"
Adb shell dumpsys deviceidle whitelist +$Pkg | Out-Null

Write-Host "7/8 Mở app"
Adb shell am start -n "$Pkg/.MainActivity" | Out-Null
Start-Sleep -Seconds 3

Write-Host "8/8 Địa chỉ cho bố mẹ"
$ip = ""
if ((Adb shell ip -4 addr show wlan0) -match "inet (\d+\.\d+\.\d+\.\d+)") { $ip = $Matches[1] }
if (-not $ip) { $ip = "<IP của máy con>" }
Write-Host ""
Write-Host "Xong. Trên điện thoại bố mẹ (cùng Wi-Fi), mở: http://${ip}:8770"
Write-Host "rồi nhập mã 6 số đang hiện trên máy con. Giờ có thể đăng nhập lại tài khoản trên máy con."
