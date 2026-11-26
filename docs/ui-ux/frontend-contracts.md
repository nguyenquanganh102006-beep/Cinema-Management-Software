# Frontend contracts and integration

Cập nhật 25/11/2026. Phạm vi triển khai và bằng chứng nghiệm thu hiện có trong [checkpoint G5](../testing/g5-validation-2026-11-23.md) và [hướng dẫn kiểm thử](../testing/frontend.md). Contract thiết kế: [frontend-design-contract.md](frontend-design-contract.md).

Frontend giữ React/TypeScript/Vite và ba portal Manager:3001, Staff:3002, Customer:3003. Backend local dùng `http://localhost:8081/api/v1`; thay bằng `VITE_API_URL` trước khi khởi động Vite nếu đổi cổng.

| Chức năng | Contract thật |
|---|---|
| Auth | `/auth/*`, JWT chỉ ở memory; reload yêu cầu đăng nhập, profile refresh không đổi deadline |
| Phim/suất | `/movies`, `/showtimes`; page response có items/page/size/totalElements/totalPages. Showtime có tên phim/phòng/age/endTime trực tiếp, không cần Customer gọi API phòng Manager |
| F&B | `/concessions`; `availableQuantity` của combo là min(floor(stock/thành phần)). `lowStock` từ tồn thật. Thiếu availability khóa input; không dùng tồn mặc định |
| Ghế/hold | `/showtimes/{id}/seats`, `/showtimes/{id}/holds`, `/holds/{token}` và `/extend`; hold gắn JWT, không lưu token vào storage |
| Pending | POST/GET `/bookings[/{id}]`; GET trả showtimeId, serverTime, channel, holdUntil và giá snapshot. Phục hồi bằng booking ID công khai sau đăng nhập; không chuyển quyền sở hữu hold cũ |
| POS | `/bookings/{id}/payments/cash`, `/vietqr`, `/vietqr/confirm`; cash/change do server xác nhận, VietQR chỉ xác nhận sau kiểm tra tiền vào tài khoản |
| Online | `/bookings/{id}/payments/vnpay`; `/payments/vnpay/return` chỉ đọc; IPN có chữ ký được backend kiểm tra trước khi phát hành vé. Hold hết hạn thì khóa/recheck ghế; chỉ cấp vé nếu ghế vẫn chưa bị lấy |
| Vé/history | `/tickets/{ticketCode}`, `/bookings/{id}/tickets`, `/me/bookings`, `/pos/bookings`; history POS chỉ đơn PAID. Không tự tạo vé từ planned seats |
| Gate | STAFF GET `/tickets/lookup?code=...` nhận Ticket/Booking Code cả POS/ONLINE; validate bắt buộc `ticketCode`, `showtimeId`, `ageDocumentChecked` cho phim hạn tuổi |
| Refund | GET `/refunds/eligibility` với đúng một `bookingCode`/`ticketCode`; Manager PIN duyệt trước, sau khi giao tiền mới gọi `/manager-overrides/cash-confirmation`, rồi POST `/refunds`. Giữ nguyên phiên Staff |
| Reports | Bảy báo cáo số liệu tại `/reports/*` và queue `/reports/payment-exceptions`; ranking object `{period,direction,movies}`; Dashboard Manager hiển thị đơn đã thu tiền nhưng chưa cấp vé. Mỗi vùng có loading/error/retry, giữ tổng net server, không trừ refund lần nữa |
| Staff | Manager-only `/staff`, PATCH status `{active}`, POST `/{id}/reset-password` với `{newPassword}` trả 204, hash password và tăng security version |
| Inventory | Manager list/history/restock; filter ngày chuyển biên `+07:00`, history per item `/inventory/{id}/history`; Customer không được đọc inventory quản trị |
| Master data | Một endpoint/payload đã biết cho mỗi lần ghi; status lỗi sau save metadata hiển thị partial success và chỉ retry status, không tạo trùng dữ liệu |
| Pricing | `/pricing-rules` CRUD theo contract, preview `/pricing/quote` read-only cho Manager/Staff/Customer; giá cuối luôn từ server |

Không có PATCH/cancel booking trong phạm vi G5. Giao diện cho sửa ghế/F&B trước tạo đơn; sau tạo chỉ thanh toán, đọc lại hoặc chờ deadline giải phóng. Không retry mutation bằng payload khác.

Approval hoàn vé sống 180 giây, token 64 hex, gắn staff session và nội dung hoàn. Manager xác nhận bàn giao sau khi khách nhận tiền; nếu xác nhận trong TTL, refund commit có thể retry cùng token/payload sau expiry. PIN không lưu storage; lỗi PIN không logout Staff. Refund chỉ gồm giá snapshot vé, không hoàn F&B. Payment exception queue không tự quyết định hoàn/đổi suất hoặc phát hành vé.

Cập nhật 23/11/2026: response mutation F&B dùng availability thật như list/get; ghi Staff khóa cùng dòng trước khi đổi profile/status/password. Mất response tạo booking giữ nguyên intent trong memory; người dùng đọc lại bằng cùng body, nhận 409 `BOOKING_ALREADY_EXISTS` kèm `details.bookingId` dạng chuỗi chỉ cho đúng chủ phiên hold, rồi GET dữ liệu đã lưu. PAID hydrate detail/ticket, không dựng vé từ plannedSeats; intent qua expiry nhưng không qua reload/session mới. Chi tiết tại [walkthrough](../code-walkthrough/README.md) và [ADR 007](../decisions/007-booking-create-recovery.md).

## Mock development

Trong `frontend/`: `VITE_USE_MOCKS=true npm run dev:customer` (hoặc `dev:staff`, `dev:manager`). Tài khoản synthetic `customer01/staff01/manager01`, password `Reference123!`, Manager PIN `123456`. Badge hiển thị dữ liệu mô phỏng. Mock state không chứng minh kết quả tài chính; dữ liệu reset khi reload browser mock.

Playwright mock dùng `context.route` với MSW service worker bị block. E2E live dùng database PostgreSQL riêng, bao gồm callback test có chữ ký thật gửi qua bộ xác thực production; không thêm bypass vào backend.
