# Zepp App Store listing (owner submits; 2026-11-19)

Package: `npx zeus build` -> `dist/1137121-KidGuard-<ver>-<stamp>.zab` (appId 1137121 = the store app created in the owner's Zepp Console 2026-11-19; 22062 was a dev-only id, an installed dev build is a separate app).
Bump `app.json` version code + name AND `APP_VER` in `page/index.js` before every upload: the store refuses an equal code.
Each uploaded build is also a GitHub prerelease `watch-zepp-<ver>` (`--prerelease --latest=false`: the phone self-updater reads the latest release, it must stay vX.Y.Z). dist/ stays gitignored.

## What to upload (console.zepp.com -> the KidGuard project -> new version)
1. The `.zab` above.
2. Name: **KidGuard**. Category: Tools (or Health/Lifestyle if Tools is missing).
3. Short + long description: below (English is the default listing, Vietnamese as a second language).
4. Screenshots of the watch: connect screen, a kid's summary, requests, map ("Xem thử" / "Try demo" shows all of them
   without a real kid, so the reviewer can see every screen).
5. Privacy policy URL: `https://github.com/HoangTran0410/kidguard/blob/main/PRIVACY.md` (has a "Parent watch app" row).
6. Review notes for the tester (below).

## Short description
- en: Your child's Android phone on your wrist: time left, requests, lock, ring, location.
- vi: Điện thoại Android của con trên cổ tay bố mẹ: giờ còn lại, xin thêm giờ, khoá máy, đổ chuông, vị trí.

## Long description (en)
KidGuard is a free, open-source parental control for Android. This watch app is for the parent: it shows each child's
phone at a glance and lets you act from the wrist.
- Time left today and the app in use
- Answer "more time" requests from your child
- Lock the phone, ring it, send a quick message, block an app
- See where the phone is on a map
Needs the KidGuard app on the child's Android phone with the relay turned on. Connect with a 6-digit code: on the watch
tap Connect, then enter the code on the parent page (search "watch"). No account, no ads, no analytics.
Guide: https://kidguard.asia/en/guide#watch

## Long description (vi)
KidGuard là app quản lý điện thoại Android cho trẻ, miễn phí và mã nguồn mở. App đồng hồ này dành cho bố mẹ: xem nhanh
điện thoại của từng con và thao tác ngay trên cổ tay.
- Giờ còn lại hôm nay, app con đang dùng
- Duyệt yêu cầu xin thêm giờ của con
- Khoá máy, đổ chuông, gửi tin nhắn nhanh, chặn app
- Xem vị trí điện thoại trên bản đồ
Cần cài KidGuard trên điện thoại Android của con và bật relay. Kết nối bằng mã 6 số: trên đồng hồ bấm Kết nối, rồi
nhập mã trên trang phụ huynh (tìm "đồng hồ"). Không tài khoản, không quảng cáo, không theo dõi.
Hướng dẫn: https://kidguard.asia/guide#watch

## Review notes
No account is needed. Tap "Try demo" on the first screen to see every screen with sample data (commands do nothing
there). A real connection needs the KidGuard app on an Android phone (https://kidguard.asia) and a 6-digit code.
