# ADR 007: Phục hồi booking sau mất response tạo đơn

Ngày: 23/11/2026. Trạng thái: Accepted trong phạm vi F15.

## Bối cảnh

POST `/bookings` có thể đã commit nhưng client mất response. Backend đã bảo vệ không tạo hai đơn từ cùng hold; lần gọi tiếp theo trả 409 `BOOKING_ALREADY_EXISTS`. POS history chỉ có đơn PAID nên Staff chưa có cách biết ID của đơn PENDING vừa tạo để đọc lại. Countdown hết hạn còn có thể xóa state trước khi client phục hồi.

## Quyết định

- Giữ HTTP 409 và mã lỗi của duplicate; không biến POST trùng thành một lần ghi thành công khác.
- Thêm `details.bookingId` dạng chuỗi ID dương canonical vào lỗi **chỉ khi** booking thuộc user/staff đang đăng nhập và holdOwner khớp đúng phiên JWT. Phiên khác, kể cả cùng tài khoản, không nhận ID.
- Không thêm endpoint chứa hold token trong URL. Client giữ nguyên body lần tạo trong memory, không đưa token vào storage.
- Sau lỗi mạng/5xx, người dùng chọn “Đọc lại kết quả tạo đơn” để gửi lại cùng body. Nếu lần đầu chưa commit và hold còn hợp lệ, thao tác có thể tạo đơn đúng ý định gốc; nếu đã commit, 409 dẫn tới GET booking. Không tự gửi lại trong nền.
- Giữ intent qua EXPIRE nhưng xóa khi đổi suất/reset, lỗi từ chối xác định hoặc nhận booking thành công. Khóa thay đổi ghế/giỏ khi chưa biết kết quả.
- GET có quyền đọc riêng. Client lấy showtime, map ghế và tên món theo dữ liệu server, giữ nguyên số lượng/đơn giá/tổng snapshot. Đơn đã PAID đọc detail/ticket thay vì dựng vé từ planned seats.

## Hệ quả và giới hạn

Contract cũ vẫn giữ 201 lần đầu và 409 lần trùng. Error details của lỗi khác mặc định rỗng; không thay kiểu `Record<string,string>`. Mất auth hoặc reload trước khi nhận ID sẽ làm mất intent trong memory; không lưu hold token để khôi phục xuyên phiên. Khi đã nhận ID, recovery hiện có dùng URL/sessionStorage rồi backend xác nhận quyền sau login.

Nếu hold đã hết và lần đầu chưa tạo đơn, server từ chối; UI cho chọn lại thay vì tự tạo hold mới. Backend vẫn kiểm quyền/tuổi/hold ở lần tạo thực sự. Lỗi duplicate với body đã đổi cũng chỉ đưa tới đơn gốc, không cập nhật snapshot.

## Kiểm chứng

`BookingCreationIntegrationTest` giữ test 201/409/concurrency, bổ sung ID cho đúng phiên, không lộ cho Customer/phiên Staff khác và recovery sau deadline. `api/contracts.test.ts` kiểm snapshot/metadata, ID không hợp lệ, đơn đã PAID; `bookingReducer.test.ts` kiểm giữ intent qua expiry; `pos.mock.spec.ts` mô phỏng mất response sau commit và readback sau expiry. Không dùng mock để chứng minh khóa/transaction PostgreSQL.
