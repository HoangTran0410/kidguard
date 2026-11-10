# Bảng hỗ trợ: chức năng × kiểu cài đặt × đường kết nối

Tra nhanh "chức năng X chạy được ở máy nào, qua đường nào". Chi tiết kỹ thuật ở `docs/protocol.md` (hợp đồng) và
`guard/Features.kt` (nguồn sự thật của cột cài đặt, cũng là thứ trang "Chức năng & quyền" hiển thị). Sửa chức năng nào
thì sửa dòng tương ứng ở đây trong cùng commit.

Cập nhật: 2026-11-10 (sau 4540ee3 / 82d66e2).

## 1. Kiểu cài đặt (máy con)

| Mã | Tên | Cách có | Ghi chú |
|---|---|---|---|
| **DO** | Device Owner | QR lúc máy mới / `setup.sh` / `setup.ps1` / trang cài web / tự cài trên máy (Android 11+). Cần máy không có tài khoản | Mạnh nhất. Chặn bằng hệ thống (suspend app, user restriction) |
| **CB** | Cơ bản | Cài APK, bật Quản trị thiết bị + Trợ năng + Dữ liệu sử dụng (+ vị trí, thông báo, pin). Không cần máy tính | Chặn bằng Trợ năng (đẩy về Home). Con rành máy vẫn lách được (Safe Mode, tắt Trợ năng) |
| **+WSS** | Cơ bản + quyền ghi cài đặt hệ thống | Cấp 1 lần qua adb: `pm grant dev.kidguard android.permission.WRITE_SECURE_SETTINGS` (setup scripts làm sẵn) | Còn nguyên sau khi tắt Tùy chọn nhà phát triển. `caps.wss` |
| **+adb** | Cơ bản + adb nội bộ | Ghép nối Gỡ lỗi không dây với chính máy 1 lần (Android 11+, Wi-Fi). Có +WSS thì KidGuard tự bật/tắt dev mode quanh mỗi lệnh (`SelfAdb.ensure`) | `caps.adb`. Chạy `pm` như adb shell |
| **Home** | Bản KidGuard Home (`dev.kidguard.home`) | Cài APK riêng | Chỉ launcher, không có phần quản lý |

Kiểm tra trên máy: KidGuard → Thêm → "Tính năng và quyền"; trên trang bố mẹ: Thêm → "Chức năng & quyền".

## 2. Đường kết nối (trang bố mẹ → máy con)

| Đường | Khi nào dùng | Độ trễ lệnh | Giới hạn |
|---|---|---|---|
| **LAN** | Cùng Wi-Fi, trang gọi thẳng máy con `:8770` | Tức thì | Phải cùng mạng. Máy con đổi IP: trang tự thử địa chỉ cũ đã biết (IP mạng nhà, IP Tailscale) rồi hỏi relay IP mới; không tìm được thì xem trạng thái / gửi lệnh / lưu luật qua relay (cần máy con đã bật relay; phần relay chỉ trong app bố mẹ) |
| **Tailscale** | Như LAN nhưng qua IP `100.x` của máy con, ở đâu cũng được | Tức thì | Cả hai đầu phải có Tailscale. Với trang bố mẹ nó **là** LAN (cùng `/api/*`), nên mọi thứ "chỉ LAN" đều chạy qua Tailscale |
| **Relay** | Cloudflare Worker, máy con hỏi định kỳ. **Bật mặc định** (relay chung của tác giả) ngay khi ghép nối; tắt hoặc đổi sang relay tự host trong Cài đặt. Link `/go` luôn mở được: cùng mạng thì vào thẳng máy con, khác mạng đi qua relay, máy offline thì báo lỗi rõ | Có kết nối WebSocket: lệnh tới gần như ngay (relay đẩy xuống). Mất kết nối WebSocket thì quay về hỏi định kỳ: 3 s khi đang bận, 20 s khi màn hình bật, 60 s khi tắt; lỗi mạng lùi tới 5 phút | Lệnh "tức thời" (khoá, định vị, nhắn, đổ chuông, đánh thức, devOn/Off, cài đặt máy) hết hạn sau 10 phút; lệnh khác giữ 24 h; lệnh phá huỷ chỉ giao 1 lần. Trang chỉ báo "đã gửi", không phải "đã chạy" |
| **Trên máy con** | "Bố mẹ quản lý trên máy này" (trang chạy ngay trên máy con) | Tức thì | Như LAN tới `localhost` |
| **App bố mẹ** | WebView bọc trang, proxy tới LAN hoặc Relay | Theo đường bên dưới | Thông báo đẩy (FCM) chỉ cho máy con dùng Relay |

## 3. Chức năng

Ký hiệu: ✅ chạy · ⚠️ chạy có giới hạn (xem ghi chú) · ❌ không · — không áp dụng.
Cột "Relay": chức năng có đi qua Relay được không (LAN / Tailscale / Trên máy con luôn được trừ khi ghi khác).

### 3.1 Chặn và giới hạn

| Chức năng | DO | CB | +WSS | +adb | Relay | Ghi chú |
|---|---|---|---|---|---|---|
| Giới hạn giờ, lịch, giờ ngủ, nhóm app | ✅ | ⚠️ | ⚠️ | ⚠️ | ✅ | CB: đẩy về Home bằng Trợ năng (cần Trợ năng + Dữ liệu sử dụng + Quản trị) |
| Chặn app | ✅ | ⚠️ | ⚠️ | ⚠️ | ✅ | Như trên. DO chặn cả app mới trước lần mở đầu; CB chặn khi mở |
| Tạm dừng nhạc/video của app bị đẩy ra | — | ✅ | ✅ | ✅ | — | Cần quyền đọc thông báo |
| App ngân hàng (tạm tắt Trợ năng, `accPause`) | ✅ | ❌ | ✅ | ✅ | ✅ | Cần WSS để bật lại. CB: lúc tạm tắt, KidGuard xem app đang mở 2 s/lần, mở app bị chặn thì bật lại ngay. Mở lại app ngân hàng từ nền: DO tự được, CB cần quyền "hiển thị trên ứng dụng khác". Mở từ thông báo thì giữ deep link |
| Dọn máy (ẩn app cài sẵn) | ✅ | ❌ | ❌ | ✅ | ✅ | +adb: `pm disable-user`, chỉ trên Wi-Fi |
| Xoá dữ liệu app (`clearData`) | ✅ (API 28+) | ❌ | ❌ | ✅ | ✅ | |
| Chặn cài app, chặn gỡ lỗi USB | ✅ | ❌ | ❌ | ❌ | ✅ | |
| Chống gỡ KidGuard | ✅ | ⚠️ | ⚠️ | ⚠️ | — | CB: Quản trị thiết bị + Trợ năng đóng các trang gỡ/tắt. Safe Mode vẫn lách |

### 3.2 Mạng, vị trí, giờ

| Chức năng | DO | CB | +WSS | +adb | Relay | Ghi chú |
|---|---|---|---|---|---|---|
| DNS an toàn (Private DNS) | ✅ | ❌ | ✅ | ✅ | ✅ | +WSS: ghi `Settings.Global`, tự sửa lại nếu con đổi. Tắt DoH của Chrome/Edge chỉ DO |
| Chặn VPN, chặn chế độ máy bay | ✅ | ❌ | ❌ | ❌ | ✅ | |
| Vị trí, lịch sử, vùng an toàn | ✅ | ✅ | ✅ | ✅ | ✅ | Cần quyền vị trí (cả nền) |
| Giữ vị trí luôn bật | ✅ | ❌ | ⚠️ | ⚠️ | ✅ | +WSS: ghi `location_mode`, chưa kiểm trên Android 10+ |
| Khoá đổi giờ | ✅ | ⚠️ | ✅ | ✅ | — | CB: Trợ năng đóng trang Ngày & giờ. +WSS: bật lại giờ tự động |
| Tailscale tự cài + tự đăng nhập | ✅ | ❌ | ❌ | ❌ | ❌ | **Chỉ LAN**: khoá Tailscale không bao giờ nằm trong hàng đợi Relay. Tắt (`tailscaleOff`) thì qua Relay được. Máy CB tự cài Tailscale bằng tay vẫn dùng đường Tailscale được |

### 3.3 Điều khiển từ xa

| Chức năng | DO | CB | +WSS | +adb | Relay | Ghi chú |
|---|---|---|---|---|---|---|
| Khoá màn hình | ✅ | ✅ | ✅ | ✅ | ✅ | CB dùng force-lock của Quản trị thiết bị |
| Định vị ngay, nhắn tin, đổ chuông, đánh thức | ✅ | ✅ | ✅ | ✅ | ✅ | Relay: hết hạn sau 10 phút |
| Xem màn hình + điều khiển | ✅ | ✅ | ✅ | ✅ | ✅ | Android 11+, cần Trợ năng. Relay ~3 khung/giây, LAN nhanh hơn |
| **Nghe xung quanh** | ✅ | ✅ | ✅ | ✅ | ❌ | **Chỉ LAN / Tailscale** (Relay không có đường âm thanh). Cần quyền micro |
| Chụp ảnh người nhập sai PIN (trang quản lý trên máy) | ✅ | ✅ | ✅ | ✅ | ✅ | DO tự cấp quyền camera; CB: bố mẹ cho phép 1 lần sau khi mở đúng PIN. Không có trong bản Home. Relay: tải qua kênh file |
| Bot Telegram (/status, /bonus, /lock, /block, /locate, /screen, /rec, file…) | ✅ | ✅ | ✅ | ✅ | ⚠️ | Cần internet trên máy con, không cần relay/LAN. /screen cần Trợ năng (Android 11+), /rec cần quyền micro, /lock cần Quản trị thiết bị |
| Gửi/nhận file | ✅ | ✅ | ✅ | ✅ | ⚠️ | LAN tới 200 MB/file; Relay tới 10 MB/file, 8 file / 32 MB, giữ 1 h |
| Khởi động lại máy | ✅ | ❌ | ❌ | ❌ | ✅ | |
| Bật/tắt Tùy chọn nhà phát triển (`devOn`/`devOff`) | ✅ | ❌ | ✅ | ✅ | ✅ | Cần WSS. Không chạy khi bật luật "chặn gỡ lỗi" |
| Cài đặt máy (âm lượng, độ sáng, hẹn giờ tắt màn hình, Không làm phiền) | ✅ | ⚠️ | ⚠️ | ⚠️ | ✅ | Âm lượng luôn được; độ sáng/hẹn giờ cần quyền "sửa cài đặt hệ thống"; Không làm phiền cần quyền DND |
| Tự cập nhật KidGuard | ✅ im lặng | ⚠️ | ⚠️ | ⚠️ | ✅ | CB: tải và kiểm tra sẵn, con phải bấm cài |
| Đổi relay (`relay`) | ✅ | ✅ | ✅ | ✅ | ❌ | **Chỉ LAN**: đổi chính đường relay |
| Ghép nối máy bố mẹ bằng mã | ✅ | ✅ | ✅ | ✅ | ❌ | Mã ghép nối chỉ qua LAN; Relay dùng token có sẵn |
| Gỡ quản lý (`release`), huỷ ghép nối | ✅ | ✅ | ✅ | ✅ | ✅ | Lệnh phá huỷ: giao 1 lần |

### 3.4 Thông báo cho bố mẹ

| Chức năng | LAN | Relay | Ghi chú |
|---|---|---|---|
| SOS, xin thêm giờ (hiện trên trang) | ✅ | ✅ | |
| Đẩy thông báo tức thì (FCM) tới app bố mẹ | ❌ | ✅ | Cần secret `FCM_SA` trên worker |
| Bot Telegram trên máy con (xem, điều khiển, báo) | ✅ | ✅ | Không phụ thuộc LAN/relay: máy con tự nối Telegram. Trang chỉ dùng để dán token + lấy mã liên kết. (Legacy: báo qua bot của worker, `TG_BOT`, trang không dùng nữa: cách cũ, trang không còn dùng) |
| Báo mất kết nối | ⚠️ | ✅ | App bố mẹ kiểm tra định kỳ |

### 3.5 Launcher (màn hình chính của con)

| Chức năng | DO | CB | Home | Ghi chú |
|---|---|---|---|---|
| Có trong danh sách màn hình chính | Khi luật bật | Luôn có | Luôn có | CB: chỉ ẩn khi crash liên tục hoặc đã gỡ quản lý |
| Ép làm màn hình chính, con không đổi được | ✅ | ❌ | ❌ | `launcherLock` |
| Chỉ hiện app được phép, đồng hồ thời gian còn lại | ✅ | ✅ | — | Bản Home không có phần quản lý |
| Chấm thông báo, lịch sử thông báo (chỉ con xem) | ✅ | ✅ | ✅ | Cần quyền đọc thông báo. Lịch sử: 7 ngày / 300 thông báo, không gửi đi đâu |
| Ẩn Tailscale | Khi KidGuard quản lý Tailscale | Khi KidGuard quản lý Tailscale | Không ẩn | |
| Tự bật lại Tailscale khi bị tắt / bị dọn | ✅ (+ VPN luôn bật) | ✅ | Không | Công tắc "Giữ Tailscale luôn bật" (luật `tsKeep`, mặc định tắt; Tailscale do KidGuard cài thì luôn giữ). Gửi lệnh kết nối cho Tailscale; nếu vẫn rớt thì mở Tailscale 1 lần khi đang ở màn home. Bị "buộc dừng" thì phải chờ bước mở. Máy CB cần quyền "hiển thị trên app khác" cho bước mở |

## 4. Tóm tắt "chỉ LAN" (gồm cả Tailscale và Trên máy con)

1. **Nghe xung quanh**: Relay không chuyển âm thanh.
2. **Cài Tailscale** (`tailscale`): khoá đăng nhập không được nằm trên server.
3. **Đổi relay** (`relay`): token mới không đi qua chính relay cũ.
4. **Ghép nối bằng mã** (`/api/pair`).

Mọi thứ khác (luật, trạng thái, lệnh trong danh sách `CMDS` của worker, xem màn hình, file ≤ 10 MB) đi qua Relay được,
chỉ chậm hơn (xem bảng 2).
