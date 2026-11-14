# UI text rules (Vietnamese + English)

Every string a person sees (phone `res/values*/strings*.xml`, page `ui/assets/i18n-*.js`, toasts, notifications,
popups, website). Goal: **understood at a glance**, by a parent in a hurry or a child, without re-reading.

## Rules
1. **Outcome first.** The first 3–6 words say what happens / what to do. Reason and details after, or in a second line.
   - ✗ "App này không chạy khi các mục dưới đang bật, nên KidGuard sẽ tắt chúng trước:"
   - ✓ "Mở app này sẽ tạm tắt:"
2. **One idea per sentence.** No "vì … nên …", "khi … thì … nếu …" chains. Split into two short lines or use `X: Y`.
3. **Short.** Title ≤ 6 words. Body sentence ≤ 15 words (VI ≤ 20 tiếng). Toast ≤ 50 chars (ToastsTest). Button ≤ 3 words.
4. **Words people use, not internals.** "Trợ năng", "gỡ lỗi USB", "cổng adb" — not "accessibility service", "adbd",
   "service.adb.tcp.port", "DPM". Package names only where the parent needs them (lists).
5. **Same thing, same word** everywhere (see glossary). Don't alternate "tạm dừng / tạm tắt / dừng".
6. **Buttons = verb (+ object)**: "Mở", "Bật lại ngay", "Gửi lại". Never "OK" when a verb fits. Cancel = "Huỷ" / "Cancel".
7. **Say how it comes back** when something is turned off or blocked: "Tự bật lại khi rời app", "Mở lại khi có Wi-Fi".
8. **Numbers as digits**, units short: "10 phút", "5555", "3 lần".
9. **Status text = real state** reported by the phone, never the switch alone (docs/decisions/page.md 2026-11-13).
10. **VI and EN say the same thing**, each natural in its language (not word-for-word). Both always exist.
11. Emphasis sparingly: one bold/CAPS word per message at most, only for the surprising part ("KHÔNG tự mở lại").

## Glossary (VI / EN)
| VI | EN | not |
|---|---|---|
| con (parent-facing); "bạn"/no subject (kid-facing screens) | your child / the kid; "you" (kid-facing) | bé, trẻ, đứa trẻ |
| máy con | the child's phone / kid's phone | điện thoại trẻ, thiết bị con |
| trang quản lý | parent page | web admin, bảng điều khiển |
| Trợ năng | Accessibility | dịch vụ trợ năng KidGuard |
| gỡ lỗi USB | USB debugging | debug, chế độ gỡ lỗi |
| cổng adb 5555 | adb port 5555 | adb tcp, adb qua mạng (in titles) |
| tạm khoá (an app the kid can't open) | paused (app) | tạm dừng |
| tạm tắt | paused / turned off for now | tạm dừng (for features), dừng |
| tự bật lại | comes back by itself | tự khôi phục |
| chặn | block | cấm, khoá (khoá = lock screen/PIN) |
| app | app | ứng dụng (except formal headings) |
