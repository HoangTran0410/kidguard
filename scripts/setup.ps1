<#
Installs KidGuard on the child's phone over ADB and makes it the Device Owner (Windows PowerShell 5+).

How to run (right-click setup.ps1 is NOT enough; open PowerShell in this folder and run):
    powershell -ExecutionPolicy Bypass -File setup.ps1 [-Yes] [path\to\kidguard.apk]

Before: on the phone, remove the phone accounts (Settings > Accounts: Google, Samsung, Xiaomi, Oppo...), turn on
USB debugging, plug it in. Accounts of ordinary apps (Zalo, Facebook, Telegram...) stay: this script turns those apps
off for a moment, restarts the phone once, makes KidGuard Device Owner, and turns the apps back on (always, also on error).
After setup you can sign in to the phone accounts again.
Several devices attached: set $env:ANDROID_SERIAL = "<serial>" first.
Undo: from the parent's page, Settings > "Gỡ quyền quản lý", then uninstall the app.
Steps are the same as docs/protocol.md "Setup steps" (setup.sh and ui/install.html do the same).
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
if ($env:ANDROID_SERIAL) { $devs = @($devs | Where-Object { ($_ -split "\s+")[0] -eq $env:ANDROID_SERIAL }) }
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
        Fail
    }
}

# --- app accounts are kept: their apps are turned off during the Device Owner step, then always turned back on ---
$OffFile = Join-Path $env:LOCALAPPDATA "kidguard\apps-off.txt"   # survives a crash / Ctrl-C: the next run turns them on first
$script:Off = @()
function Reenable {
    if ($script:Off.Count -eq 0) { return }
    $list = @($script:Off); $script:Off = @()
    Write-Host "[*] Bật lại các app đã tắt tạm: $($list -join ' ')"
    for ($i = 0; $i -lt 60; $i++) { if ((Adb shell echo ok) -match "ok") { break }; Start-Sleep -Seconds 2 }   # the phone may still be restarting
    foreach ($p in $list) { Write-Host (Adb shell pm enable $p) }
    Remove-Item $OffFile -Force -ErrorAction SilentlyContinue
}
# Fail: stop the script after turning the apps back on. Ctrl-C: the next run turns them on first (apps-off.txt).
function Fail { Reenable; exit 1 }
trap { Reenable; break }   # a terminating error: apps back on first
if (Test-Path $OffFile) {
    $script:Off = @((Get-Content $OffFile -Raw) -split "\s+" | Where-Object { $_ })
    Write-Host "[!] Lần chạy trước còn app bị tắt tạm, bật lại trước."
    Reenable
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
    Fail
}

# Accounts block Device Owner. App accounts (authenticator = ordinary app) are kept: turn that app off (its accounts
# stop counting), restart once (Android caches the check until boot), then set Device Owner and turn the apps back on.
# Accounts of system apps (Google, Xiaomi, Samsung, Oppo...) must be removed by hand.
if ((Adb shell dumpsys device_policy) -notmatch [regex]::Escape("admin=ComponentInfo{$Pkg/$Pkg.Admin}")) {
    $dump = Adb shell dumpsys account
    $accts = @([regex]::Matches($dump, 'Account \{name=([^,}]*), type=([^}]*)\}') | ForEach-Object { [pscustomobject]@{ Name = $_.Groups[1].Value; Type = $_.Groups[2].Value } } | Sort-Object Type, Name -Unique)
    if ($accts.Count -gt 0) {
        $auth = @{}
        foreach ($m in [regex]::Matches($dump, 'AuthenticatorDescription \{type=([^}]*)\}, ComponentInfo\{([^/}]+)/')) { $auth[$m.Groups[1].Value] = $m.Groups[2].Value }
        $third = @((Adb shell pm list packages -3) -split "`n" | ForEach-Object { ($_ -replace "^package:", "").Trim() } | Where-Object { $_ })
        $disable = @(); $remove = @()
        foreach ($a in $accts) {
            $pkg = $auth[$a.Type]
            if (-not $pkg) { continue }   # authenticator not listed: the account is hidden already
            if (($third -contains $pkg) -and ($pkg -match '^[A-Za-z0-9_.]+$')) { if ($disable -notcontains $pkg) { $disable += $pkg } }
            else { $remove += "  - $($a.Name) ($($a.Type))" }
        }
        if ($remove.Count -gt 0) {
            Write-Host "LỖI: Cần xoá các tài khoản của máy trước:"
            $remove | ForEach-Object { Write-Host $_ }
            Write-Host "→ Cài đặt > Mật khẩu và tài khoản, xoá chúng rồi chạy lại. Tài khoản của app (Zalo, Facebook…) cứ để nguyên. Cài xong đăng nhập lại, dữ liệu vẫn còn."
            Fail
        }
        if ($disable.Count -gt 0) {
            Write-Host "[*] Giữ đăng nhập các app: $($disable -join ' ')"
            Write-Host "    Tắt tạm các app này, khởi động lại máy một lần (khoảng 1 phút), rồi cài tiếp. Sẽ tự bật lại các app."
            New-Item -ItemType Directory -Force -Path (Split-Path $OffFile) | Out-Null
            Set-Content -Path $OffFile -Value ($disable -join " ") -NoNewline
            $script:Off = @($disable)
            foreach ($p in $disable) {
                $o = Adb shell pm disable-user --user 0 $p
                if ($o -notmatch "disabled") { Write-Host "LỖI: không tắt được ${p}: $o"; Fail }
            }
            Start-Sleep -Seconds 14; Adb shell sync | Out-Null   # Android writes the app state to disk ~10 s later: rebooting sooner loses it
            Write-Host "[*] Khởi động lại máy…"
            Adb reboot | Out-Null
            Start-Sleep -Seconds 5; Adb wait-for-device | Out-Null
            $booted = $false
            for ($i = 0; $i -lt 90; $i++) { if ((Adb shell getprop sys.boot_completed).Trim() -eq "1") { $booted = $true; break }; Start-Sleep -Seconds 2 }
            if (-not $booted) { Write-Host "LỖI: máy không khởi động xong sau 3 phút (các app sẽ được bật lại khi máy lên)."; Fail }
            Start-Sleep -Seconds 3
        }
    }
}

Write-Host "[2/8] Đặt KidGuard làm Device Owner"
$out = Adb shell dpm set-device-owner "$Pkg/.Admin"
# after our restart Android still says "accounts" for up to ~30 s (it re-checks them in the background): try again
for ($i = 0; $i -lt 24; $i++) {
    if (($script:Off.Count -eq 0) -or ($out -notmatch "accounts")) { break }
    Write-Host "    Máy đang kiểm tra lại tài khoản, thử lại sau 5 giây…"
    Start-Sleep -Seconds 5
    $out = Adb shell dpm set-device-owner "$Pkg/.Admin"
}
if ((Adb shell dumpsys device_policy) -notmatch [regex]::Escape("admin=ComponentInfo{$Pkg/$Pkg.Admin}")) {
    Write-Host $out
    Write-Host @"

Không đặt được Device Owner. Thường là vì:
  - Máy còn tài khoản: Cài đặt > Tài khoản, xoá hết (Google, Samsung, Xiaomi…), chạy lại.
  - Máy có nhiều người dùng / Không gian thứ hai / Thư mục bảo mật: xoá đi.
  - Xiaomi: bật thêm "Gỡ lỗi USB (Cài đặt bảo mật)" trong Tuỳ chọn nhà phát triển.
  - Đã có app quản lý khác (Device Owner): gỡ nó trước.
"@
    Fail
}

Write-Host "[3/8] Cấp quyền xem thời gian dùng app"
Chk appops set $Pkg GET_USAGE_STATS allow

Write-Host "[4/8] Cấp quyền ghi cài đặt bảo mật"
Chk pm grant $Pkg android.permission.WRITE_SECURE_SETTINGS
# optional (remote phone settings): brightness / screen timeout and Do Not Disturb; old Android may not know them
Adb shell appops set $Pkg WRITE_SETTINGS allow | Out-Null
Adb shell cmd notification allow_dnd $Pkg | Out-Null
Adb shell cmd notification allow_listener "$Pkg/$Pkg.NotifDots" | Out-Null
Adb shell appops set $Pkg SYSTEM_ALERT_WINDOW allow | Out-Null   # bank app reopen (basic mode)
Adb shell appops set $Pkg REQUEST_INSTALL_PACKAGES allow | Out-Null   # self-update without asking (basic mode)

Write-Host "[5/8] Bật dịch vụ Trợ năng"
$cur = ((Adb shell settings get secure enabled_accessibility_services) -split "`n")[0].Trim()
if ($cur -eq "null") { $cur = "" }
if ((":$cur:") -notlike "*:${Acc}:*") {
    if ($cur) { $cur = "${cur}:${Acc}" } else { $cur = $Acc }
}
if ($cur.Contains("'")) { Write-Host "LỖI: Danh sách trợ năng có ký tự lạ: $cur"; Fail }
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
Reenable
Write-Host ""
Write-Host "OK Xong. Trên điện thoại bố mẹ (cùng Wi-Fi), mở: http://${ip}:8770"
Write-Host "-> rồi nhập mã 6 số đang hiện trên máy con. Giờ có thể đăng nhập lại tài khoản trên máy con."
# ColorOS: a Recents swipe is a real force-stop (alarms gone, Accessibility turned off)
if ("$(Adb shell 'getprop ro.build.version.opporom; getprop ro.build.version.oplusrom')".Trim()) {
    Write-Host '-> Oppo/Realme/OnePlus (ColorOS): mở Đa nhiệm, kéo thẻ KidGuard xuống để khoá (biểu tượng ổ khoá), và trong Quản lý điện thoại cho KidGuard "Tự khởi chạy" + chạy nền. Vuốt KidGuard khỏi Đa nhiệm trên ColorOS sẽ tắt hẳn app và Trợ năng.'
}
