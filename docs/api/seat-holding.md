# Seat map và giữ ghế — Sprint D

Base `/api/v1`, JWT Bearer Foundation. Các lỗi dùng `timestamp/status/code/message/details/path`. Hold token trong path lỗi được thay bằng `[redacted]`, kể cả lỗi authentication/authorization. Không ghi token vào log ứng dụng. Thành công bọc `data`; DELETE trả 204 không body.

| Method | Path | Quyền | Thành công |
|---|---|---|---|
| GET | `/showtimes/{id}/seats` | STAFF/CUSTOMER | 200 SeatMapResponse |
| POST | `/showtimes/{id}/holds` | STAFF/CUSTOMER | 201 HoldSeatsResponse, kể cả exact retry |
| POST | `/holds/{token}/extend` | STAFF | 200 HoldSeatsResponse |
| DELETE | `/holds/{token}` | STAFF/CUSTOMER sở hữu nhóm | 204; token hợp lệ không còn tồn tại cũng 204 |

Manager không dùng các endpoint này. ID suất phải là Long dương, token phải là UUID đầy đủ do server trả. Request không nhận owner, giá hoặc hạn giữ. JSON field lạ bị từ chối.

## Seat map

```json
{
  "data": {
    "showtimeId": 10,
    "serverTime": "2026-11-14T12:00:00Z",
    "seats": [
      {"seatId":101,"row":"A","number":1,"seatType":"STANDARD","pairSeatId":null,"status":"HOLDING","heldByMe":true},
      {"seatId":102,"row":"A","number":2,"seatType":"VIP","pairSeatId":null,"status":"AVAILABLE","heldByMe":false}
    ],
    "ownHolds": [
      {"holdToken":"01234567-89ab-4cde-8fab-0123456789ab","showtimeId":10,"seatIds":[101],"serverTime":"2026-11-14T12:00:00Z","holdUntil":"2026-11-14T12:05:00Z","extensionUsed":false}
    ]
  }
}
```

Sắp xếp theo row rồi number, có STANDARD/VIP/SWEETBOX và AVAILABLE/HOLDING/BOOKED. SELECTED chỉ tồn tại ở UI. Ghế quá hạn hiển thị AVAILABLE ngay cả khi scheduler chưa chạy; GET không cập nhật DB. OwnHolds chỉ thuộc đúng phiên JWT, không chỉ cùng userId. Trạng thái của người khác được hiển thị nhưng owner/token/deadline của họ không xuất hiện. Inventory phải khớp chính xác tập physical seats của phòng; thiếu/thừa mapping trả 409.

## Tạo hold

```http
POST /api/v1/showtimes/10/holds
Authorization: Bearer <staff-or-customer-jwt>
Content-Type: application/json

{"seatIds":[101,102]}
```

```json
{
  "data": {
    "holdToken": "01234567-89ab-4cde-8fab-0123456789ab",
    "showtimeId": 10,
    "seatIds": [101,102],
    "serverTime": "2026-11-14T12:00:00Z",
    "holdUntil": "2026-11-14T12:05:00Z",
    "extensionUsed": false
  }
}
```

1–100 ID JSON nguyên dương, không null/trùng; số thập phân và chuỗi số bị từ chối. Sweetbox mở rộng thành cả hai ghế và nhóm sau mở rộng vẫn không quá 100. Một ghế BOOKED hoặc live HOLDING khiến toàn bộ request thất bại. Không giữ ghế khi suất đã bắt đầu; giờ suất được đọc lại sau khi có lock inventory.

Gửi lại chính xác toàn bộ nhóm của đúng phiên trả token/deadline cũ. Chỉ gửi subset, thêm ghế vào nhóm đang giữ hoặc gộp nhiều nhóm trả 409; hãy release toàn bộ trước khi chọn lại. Có thể reclaim HOLDING quá hạn mà không đợi scheduler. Không tự retry POST khi lỗi mạng; đọc seat map/ownHolds trước khi quyết định gửi lại.

## Gia hạn và release

```http
POST /api/v1/holds/01234567-89ab-4cde-8fab-0123456789ab/extend
Content-Type: application/json

{"waitingForTransfer":true}
```

Chỉ đúng phiên STAFF, từ giây 240 đến trước giây 300 của hold ban đầu, đang chờ chuyển khoản và chưa gia hạn. Cộng 120 giây vào hạn cũ, không cộng từ giờ request. Với ví dụ trên, hạn mới là `12:07:00Z`, `extensionUsed=true`. Request không nhận số phút/deadline do client đặt. `now >= holdUntil` là hết hạn; thời gian backend quyết định, countdown chỉ hiển thị.

DELETE release cả nhóm đang thuộc token và đúng phiên. Gọi lại trả 204. Token cũ không giải phóng ghế đã được phiên khác reclaim hoặc chuyển BOOKED; không xóa dòng showtime_seats/Ticket/Booking.

## Lỗi

| HTTP | Code | Trường hợp |
|---|---|---|
| 400 | VALIDATION_ERROR | JSON/ID/token sai định dạng, null/trùng/quá 100 ghế, không xác nhận chờ chuyển khoản |
| 400 | INVALID_SEAT | Ghế không tồn tại hoặc không thuộc phòng của suất |
| 401 | Foundation auth codes | JWT thiếu/sai/hết hạn/thu hồi |
| 403 | ACCESS_DENIED | Sai role; Customer gia hạn |
| 403 | SEAT_HOLD_NOT_OWNED | Hold còn tồn tại nhưng thuộc phiên khác |
| 404 | SHOWTIME_NOT_FOUND | Không có suất chiếu |
| 409 | SHOWTIME_STARTED | Suất đã bắt đầu sau khi lấy lock |
| 409 | SEAT_INVENTORY_INCOMPLETE | Inventory thiếu/thừa mapping với physical seats |
| 409 | SWEETBOX_PAIR_INVALID | Layout Sweetbox legacy sai |
| 409 | SEAT_NOT_AVAILABLE | Ghế đã bán/live hold, retry chỉ một phần hoặc gộp nhóm |
| 409 | SEAT_UPDATE_CONFLICT | Lock timeout 2 giây hoặc version conflict; làm mới trạng thái |
| 409 | SEAT_HOLD_EXPIRED | Extend/checkout với nhóm không còn live |
| 409 | SEAT_HOLD_TOO_EARLY | Extend trước giây 240 |
| 409 | SEAT_HOLD_ALREADY_EXTENDED | Extend lần hai khi nhóm vẫn live |
| 409 | SEAT_HOLD_INVALID | Metadata nhóm không nhất quán |
| 409 | SEAT_HOLD_MISMATCH | Service checkout thấy khác showtime hoặc tập ghế dự kiến của Booking |

## Hai client tranh ghế

Đăng nhập Staff và Customer bằng hai phiên độc lập, dùng cùng showtime và seatIds `[101]`, gửi POST đồng thời. Một request nhận 201, request còn lại nhận 409 SEAT_NOT_AVAILABLE (hoặc SEAT_UPDATE_CONFLICT nếu chờ lock quá 2 giây). Client thua đọc lại seat map: ghế HOLDING, heldByMe=false, ownHolds không chứa token của client thắng. Cùng ghế vật lý ở showtime khác vẫn giữ được độc lập.

## Chuẩn bị dữ liệu và bàn giao

Manager tạo movie → auditorium → physical seats → pair Sweetbox liền kề → showtime bằng API B/C. Sau khi có bất kỳ lịch nào, layout/pair bị khóa 409 AUDITORIUM_LAYOUT_LOCKED; tạo layout đầy đủ trước khi lập lịch. Chạy [audit SELECT](../../scripts/testing/check-showtime-inventory.sql) để tìm mismatch cũ; không tự rebuild inventory có lịch sử.

E gọi trực tiếp `CheckoutSeatService.lockAndValidateHold(token, storedOwner, showtimeId, expectedSeatIds)` và `markBooked(...)` trong transaction READ_COMMITTED của CheckoutService. Expected seat IDs/owner lấy từ Booking đã lưu, không từ trình duyệt/callback. API này không tạo Booking, Payment, Ticket hoặc quote. Xem [decision 004](../decisions/004-seat-holding.md) và [kiểm thử](../testing/sprint-d.md).
