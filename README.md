# KidGuard

Quản lý điện thoại Android của con từ xa. Cài 1 APK, chạy 1 lệnh adb, không tài khoản, không máy chủ bắt buộc.

- **Máy con**: app KidGuard làm *Device Owner*. Luật chạy ngay trên máy con, mất mạng vẫn chạy.
- **Bố mẹ**: mở trình duyệt bất kỳ (Android, iPhone, máy tính).
  - Cùng Wi-Fi: `http://<IP máy con>:8770`, đi thẳng, 0 máy chủ.
  - Khác mạng: qua một Cloudflare Worker của bạn (gói miễn phí). Cùng một trang web.

## Làm được gì

| | |
|---|---|
| ⏱ Giới hạn mỗi ngày | Hết giờ: mọi app (trừ "Luôn cho phép") tạm khoá tới 0h. Thêm +15/+30/+60 phút cho hôm nay |
| 📱 Theo từng app | Bình thường / N phút mỗi ngày / Chặn / Luôn cho phép, xem giờ dùng hôm nay |
| 🌙 Giờ đi ngủ | Khung giờ, qua nửa đêm được |
| ⏸ Tạm dừng ngay | 30 phút, 1 giờ, tới 6h sáng |
| 🔒 Lệnh | Khoá màn hình, đổ chuông 20 giây (âm báo thức to nhất), nhắn tin hiện toàn màn hình, lấy vị trí |
| 📍 Vị trí | Tự gửi mỗi 15 phút, xem trên bản đồ |
| 🛡 Chống lách | Chặn gỡ app, chặn Force stop / Xoá dữ liệu, chặn đặt lại máy trong Cài đặt, chặn Safe Mode, chặn tạo người dùng, khoá đổi giờ (chống chỉnh giờ để lách). Tuỳ chọn: chặn cài app mới, chặn ADB |
| ↩️ Gỡ được | Trang bố mẹ > Cài đặt > Gỡ quyền quản lý: mở lại mọi app, bỏ hết, rồi gỡ app như thường |

App bị khoá dùng `setPackagesSuspended` của Android: icon xám, bấm vào hiện "Bố mẹ đang tạm khoá ứng dụng này". Không cần quyền Trợ năng, không có lớp phủ để lách.

## Cài

1. Máy con: **xoá hết tài khoản** (Cài đặt > Tài khoản: Google, Samsung, Xiaomi…), bật **Gỡ lỗi USB**, cắm vào máy tính.
   Android chỉ cho đặt Device Owner khi máy không có tài khoản; cài xong đăng nhập lại bình thường.
2. Máy tính có `adb`:
   ```bash
   ./setup.sh            # dùng build/kidguard.apk, hoặc ./setup.sh duong/dan/kidguard.apk
   ```
3. Máy con hiện mã 6 số. Điện thoại bố mẹ cùng Wi-Fi mở `http://<IP máy con>:8770`, nhập mã.
4. Tab **Cài đặt**: đặt **PIN của bố mẹ** (nếu không, con có thể tự ghép trình duyệt của mình).

Không có máy tính: Android 11+ dùng *Gỡ lỗi không dây* với một app ADB trên điện thoại khác (ví dụ LADB / Shizuku-style), chạy đúng các lệnh trong `setup.sh`.

### Điều khiển khi khác mạng (tuỳ chọn)

```bash
cd worker && npm install && npx wrangler login && npx wrangler deploy
```

Ra địa chỉ `https://kidguard.<tên>.workers.dev`. Ở nhà, trên trang của con: Cài đặt > **Điều khiển khi khác mạng**, dán địa chỉ, bấm Bật, chép link hiện ra và mở trên điện thoại bố mẹ.
Lệnh tới máy con trong ~20 giây (màn hình tắt: ~60 giây). Worker chỉ giữ trạng thái gần nhất, luật và hàng đợi lệnh; token chỉ nằm trong link của bạn.
Chi phí: mỗi máy con ~4.300 request/ngày, gói miễn phí 100.000/ngày.

## Build

```bash
./build.sh                       # build/kidguard.apk, chạy cả unit test
TRUSTSTORE=/path/trust.jks ./build.sh   # sau proxy chặn TLS (cacerts của JDK + CA của proxy)
```

JDK 17, Android SDK 35. Trang web: `web/index.html` (dùng chung cho APK và Worker).

## Code

| File | Việc |
|---|---|
| `app/.../Rules.kt` | Luật + `decide()`: app nào khoá, vì sao (thuần, có test) |
| `app/.../Usage.kt` | Thời gian dùng hôm nay từ UsageEvents |
| `app/.../Policy.kt` | Mọi quyền Device Owner: hạn chế, khoá app, gỡ quản lý |
| `app/.../Guard.kt` | Service chạy nền: 10 giây/lần áp luật, trạng thái, lệnh, vị trí |
| `app/.../Http.kt`, `Api.kt` | Máy chủ HTTP nhỏ trên LAN, ghép bằng mã 6 số |
| `app/.../Relay.kt` | Đồng bộ với Worker |
| `worker/src/index.js` | Worker + Durable Object, mỗi máy con một object |

## Giới hạn đã biết

- Đặt lại máy bằng phím cứng (recovery) vẫn làm được; sau đó KidGuard mất, bố mẹ thấy "mất kết nối".
- Hãng hay tự tắt app nền (Xiaomi, Oppo…): Device Owner + foreground service giúp nhiều, chưa thử hết các hãng.
- Chưa có: xem màn hình từ xa, lọc web, giới hạn theo ngày trong tuần, ghép từ điện thoại bằng Wireless debugging (không cần máy tính).
- LAN là HTTP thường (token trong header). Chỉ dùng trong mạng nhà.
