# KidGuard

Quản lý điện thoại Android của con từ xa. Cài 1 APK, cắm USB 1 lần, không tài khoản, không máy chủ bắt buộc.

- **Máy con**: app KidGuard làm *Device Owner*. Luật chạy ngay trên máy con, mất mạng vẫn chạy.
- **Bố mẹ**: mở trình duyệt bất kỳ (Android, iPhone, máy tính).
  - Cùng Wi-Fi: `http://<IP máy con>:8770`, đi thẳng, 0 máy chủ.
  - Khác mạng: qua một Cloudflare Worker của bạn (gói miễn phí). Cùng một trang web.

## Làm được gì

| | |
|---|---|
| ⏱ Giới hạn mỗi ngày | Một số chung, hoặc riêng từng thứ trong tuần. Hết giờ: mọi app (trừ "Luôn cho phép") khoá tới 0h. +15/+30/+60 phút cho hôm nay |
| 🙋 Con xin thêm giờ | Nút trên máy con (15/30/60 phút + lý do), bố mẹ bấm ✅ Đồng ý / ❌ Từ chối |
| 📱 Theo từng app | Bình thường / N phút mỗi ngày / Chặn / Luôn cho phép. **Chọn nhiều app** để làm hàng loạt |
| 🆕 App mới cài | Tự khoá tới khi bố mẹ duyệt (tuỳ chọn) |
| 🌙 Giờ ngủ, 📚 Lịch | Giờ ngủ hằng ngày + các khung giờ theo thứ (ví dụ giờ học T2–T6), qua nửa đêm được |
| ⏸ Tạm dừng ngay | 30 phút, 1 giờ, tới 6h sáng |
| 👀 Xem màn hình | Màn hình máy con trực tiếp trên trang (Android 11+, ~3 hình/giây). Con thấy thông báo "Bố mẹ đang xem màn hình" |
| 🕹 Điều khiển từ xa | Chạm, vuốt, nhấn giữ, Quay lại / Home / Đa nhiệm, gõ chữ vào ô đang chọn |
| 📍 Vị trí | Lấy ngay, bật vị trí bắt buộc (con không tắt được), **lịch sử di chuyển** 7 ngày vẽ trên bản đồ OpenStreetMap |
| 📊 Lịch sử | Giờ dùng 7 ngày, top app mỗi ngày |
| 🛡 Chống lách | Chặn gỡ app, Force stop, đặt lại máy, Safe Mode, tạo người dùng, đổi giờ, chế độ máy bay. Watchdog tự dậy lại nếu bị tắt, Trợ năng bị tắt thì tự bật lại. Tuỳ chọn: DNS lọc web (AdGuard/Cloudflare/CleanBrowsing Family), chặn cài app, chặn ADB |
| 🔒 Lệnh | Khoá màn hình, đổ chuông, nhắn tin toàn màn hình, khởi động lại máy |
| ↩️ Gỡ được | Trang bố mẹ > Cài đặt > Gỡ quyền quản lý: mở lại mọi app, bỏ mọi hạn chế, rồi gỡ app như thường |

App bị khoá dùng `setPackagesSuspended` của Android: icon xám, bấm vào hiện "Bố mẹ đang tạm khoá ứng dụng này".

## Cài (chọn 1 trong 3)

Chuẩn bị máy con: **xoá hết tài khoản** (Cài đặt > Tài khoản), bật **Gỡ lỗi USB**, cắm vào máy tính.
Android chỉ cho đặt Device Owner khi máy không có tài khoản; cài xong đăng nhập lại bình thường.

1. **Trình duyệt (dễ nhất)**: Chrome/Edge trên máy tính mở `https://<worker của bạn>/install` (hoặc mở file `web/install.html`),
   bấm 🔌 Kết nối, cho phép trên máy con, xong. Không cần cài adb.
2. **macOS/Linux**: `./setup.sh` (tự tải adb nếu thiếu; `-y` để bỏ câu hỏi xác nhận).
3. **Windows**: `powershell -ExecutionPolicy Bypass -File setup.ps1`.

Sau đó máy con hiện mã 6 số. Điện thoại bố mẹ cùng Wi-Fi mở `http://<IP máy con>:8770`, nhập mã.
Tab **Cài đặt**: đặt **PIN của bố mẹ** (chưa có PIN thì máy con không cho ghép thêm trình duyệt).

Xiaomi/Oppo/Vivo/Samsung: trên máy con bấm **🔋 Cài đặt pin / tự khởi động** và cho KidGuard chạy nền.

### Điều khiển khi khác mạng (tuỳ chọn)

```bash
cd worker && npm install && npx wrangler login && npx wrangler deploy
```

Ra `https://kidguard.<tên>.workers.dev`. Trên trang (LAN): Cài đặt > **Điều khiển khi khác mạng**, dán địa chỉ, bấm Bật, mở link hiện ra trên điện thoại bố mẹ.
Lệnh tới máy con trong ~20 giây (màn hình tắt: ~60 giây). Xem màn hình qua relay: ảnh chỉ nằm trong bộ nhớ Worker, không lưu.

## iPhone / iPad

Không làm được app kiểu này cho iOS: Apple không cho app bên thứ ba xem màn hình, điều khiển từ xa hay khoá app khác.
Khuyên dùng đồ có sẵn, miễn phí:
- **Thời gian sử dụng (Screen Time) + Chia sẻ trong gia đình**: giới hạn giờ, giờ nghỉ, giới hạn app, chặn web người lớn, duyệt mua app, xin thêm giờ — quản lý từ iPhone của bố mẹ.
- **Tìm (Find My)**: vị trí của con.
- Thêm: hồ sơ DNS lọc (AdGuard Family) — con có thể gỡ nếu không khoá bằng Screen Time.

Tự làm app iOS cần tài khoản Apple Developer 99 USD/năm + quyền Family Controls khó xin, mà vẫn không xem màn hình được. MDM (giám sát) cần xoá máy + máy Mac — không đáng cho gia đình.

## Build

```bash
./build.sh                              # build/kidguard.apk, chạy cả unit test
TRUSTSTORE=/path/trust.jks ./build.sh   # sau proxy chặn TLS
```

JDK 17, Android SDK 35. Trang bố mẹ: `web/index.html`. Trang cài: `web/install.html` (thư viện ADB WebUSB Tango đã đóng gói sẵn, `web/vendor/build.sh` để build lại).
Giao thức giữa các phần: `docs/protocol.md`.

## Giới hạn đã biết

- Đặt lại máy bằng phím cứng (recovery) vẫn làm được; sau đó KidGuard mất, bố mẹ thấy "mất kết nối".
- Xem màn hình / điều khiển cần Android 11+. Android 8–10: con có thể Force stop (tự chạy lại khi khởi động máy).
- Ứng dụng có cờ bảo mật (ngân hàng…) hiện màn hình đen khi xem.
- LAN là HTTP thường (token trong header). Chỉ dùng trong mạng nhà.
