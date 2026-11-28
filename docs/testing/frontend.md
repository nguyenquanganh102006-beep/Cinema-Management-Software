# Kiểm thử frontend G5

Chạy từ `frontend/` với Node >=22.12 và `npm ci`:

```bash
npm test
npm run build
npx playwright install chromium firefox webkit
npm run test:e2e:mock
```

Có thể đặt `PLAYWRIGHT_BROWSERS_PATH=/tmp/cinema-g5-browsers` để cài/chạy từ thư mục tạm, tránh thay cache cá nhân. Browser version đi theo package Playwright đã khóa trong package-lock. Không chạy hai suite song song cùng cổng 3001–3003 vì quá trình đầu kết thúc sẽ đóng server mà quá trình sau đang dùng.

## Chạy bằng API thật

Backend local dùng database test mới riêng, không dùng database dự án. Trên Linux có PostgreSQL binaries và Java 21, từ root:

```bash
G5_PG_PORT=55440 PLAYWRIGHT_BROWSERS_PATH=/tmp/cinema-g5-browsers bash scripts/testing/run-g5-local.sh
```

Script tạo cluster và hai database trong `/tmp`, credential test ngẫu nhiên, chạy backend verify, seed demo, frontend unit/build/mock/live rồi dừng process test. Log giữ trong thư mục tạm được in khi thoát. Cổng backend 8080 và ba cổng portal phải đang rảnh. Với PostgreSQL do bạn chuẩn bị riêng, có thể chạy backend như README và tự set `DB_TEST_*`; chỉ trỏ vào database rỗng dành cho kiểm thử.

Chạy live riêng trong `frontend/` khi backend test đã sẵn sàng:

```bash
DEMO_PASSWORD='<test password>' DEMO_MANAGER_PIN='<test PIN>' \
TEST_VNPAY_HASH_SECRET='<same secret as isolated backend>' \
PLAYWRIGHT_BROWSERS_PATH=/tmp/cinema-g5-browsers npm run test:e2e:live
```

`auth.spec.ts` kiểm tra ba role, register, logout/revocation, transfer, lỗi quyền và expiry. `booking.live.spec.ts` kiểm tra tranh ghế và UI tạo dữ liệu/bán/soát. `g5.live.spec.ts` kiểm tra reload pending, cash, partial refund, gate, online Sweetbox/F&B, signed IPN/lặp, return chỉ đọc, history và báo cáo. Test IPN chỉ ký trong mã test với secret của backend test; không sửa logic xác thực production. Không chạy với cấu hình gateway production.

`management.live.spec.ts` bổ sung Staff create/edit/status/reset/revocation, login inactive và pagination trên hơn 20 record. `g5.live.spec.ts` đối soát chênh lệch gross/refund/net, vé phát hành/hủy/ròng, kênh, breakdown, ranking hai chiều và occupancy sau bán/hoàn; UI so sánh các số tổng với API.

## Edge và giao diện

Edge dùng binary test riêng qua `CINEMA_EDGE_EXECUTABLE`, không dùng profile cá nhân. Nếu đã cài Edge có thể dùng executable của nó; trên Linux có thể giải nén gói chính thức vào `/tmp` thay vì cài hệ thống:

```bash
CINEMA_EDGE_EXECUTABLE=/tmp/cinema-g5-edge/opt/microsoft/msedge/msedge npm run test:e2e:edge
```

Kiểm tra viewport 320/390/768/1024/1440px, keyboard/skip link, dialog Escape/focus return, reduced motion, overflow của seat map/bảng, và print. Ảnh G5 mới: `docs/testing/screenshots/g5/`; record API timing/live: `live-evidence.json` trong cùng thư mục. Playwright trace/error context nằm trong `frontend/test-results` và không được xem là pass khi chưa đọc kết quả.

Font Be Vietnam Pro self-host trong `shared/src/assets/fonts` với giấy phép OFL; không cần kết nối Google Fonts để render. Build có thể vẫn in cảnh báo Vite bỏ qua directive `use client` của React Router; không phải lỗi runtime.

## Test mới ngày 23/11/2026

```bash
npm test -- shared/src/api/contracts.test.ts shared/src/mocks/contracts.test.ts shared/src/booking/bookingReducer.test.ts
npm run test:e2e -- tests/accessibility.mock.spec.ts tests/pos.mock.spec.ts
npm run test:e2e:live -- tests/management.live.spec.ts tests/g5.live.spec.ts
```

`accessibility.mock.spec.ts` chạy ba portal ở 320/390/768/1024/1440px, reduced motion, containment, tiêu đề bước ghế, CTA mobile trong viewport, thao tác F&B không bị overlay chặn, keyboard/skip link/dialog/Escape/return focus và print QR. Có đo contrast viền/text control đại diện từ computed style, không phải audit tự động đầy đủ mọi cặp màu. Ảnh viewport ở `screenshots/responsive/<portal>-<width>-<project>.png`.

`pos.mock.spec.ts` mô phỏng response tạo đơn mất sau commit và retry sau EXPIRE đã xóa hold khỏi reducer; chỉ nhìn countdown 0:00 chưa đủ vì seat-map poll 5s mới cập nhật serverTime. `BookingCreationIntegrationTest` xác nhận duplicate vẫn 409, ID chỉ trả cho đúng phiên, concurrent create vẫn một đơn. Đã thử đảo điều kiện kiểm ID trong adapter và xác nhận contract tests bắt được mutation, sau đó khôi phục source.

`refund-gate.mock.spec.ts` kiểm PIN sai vẫn giữ phiên Staff, mất response sau commit retry đúng cùng payload/token, và hết approval quay lại xác thực Manager. `mocks/contracts.test.ts` đối chiếu `ManagerOverrideRequest`, `RefundRequest` và `RefundService`: scope, validation, TTL 180 giây, error code, trim/sort intent, zero refundable amount và idempotent readback quá TTL. Các test này kiểm simulator/UI; suite `RefundReportsIntegrationTest` mới xác minh concurrency/rollback và tiền trên PostgreSQL.

Regression mã vé dài ở 320px kiểm cả receipt và checkbox hoàn vé. Print media phải ẩn cả link thao tác hoàn vé, giữ QR và `break-inside: avoid` cho ticket.

Khi chạy backend verify nhiều lần, dùng database rỗng mới cho mỗi lần. Một số test báo cáo dùng kỳ cố định và không xóa dữ liệu từ lần chạy trước; tái dùng DB khiến aggregate cộng dồn dù implementation không đổi. Script `run-g5-local.sh` tạo cluster/database mới mỗi lần; các DB test không phải database dự án.

Walkthrough theo source: [tổng quan](../code-walkthrough/README.md), [frontend](../code-walkthrough/frontend.md), [backend](../code-walkthrough/backend.md).
