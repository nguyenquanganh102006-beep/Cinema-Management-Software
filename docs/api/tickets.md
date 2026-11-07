# Đọc vé và soát vé tại cổng — Sprint E

Base `/api/v1`, JWT Bearer. Thành công bọc `data`; lỗi dùng
`timestamp/status/code/message/details/path`. Không endpoint nào trả Entity.

| Method | Path | Quyền | Thành công |
|---|---|---|---|
| GET | `/tickets/{ticketCode}` | STAFF, hoặc CUSTOMER sở hữu Booking | 200 TicketResponse |
| GET | `/bookings/{bookingId}/tickets` | STAFF, hoặc CUSTOMER sở hữu Booking | 200 `[TicketResponse]` |
| POST | `/tickets/validate` | STAFF | 200 TicketValidationResponse |

Ticket chỉ tồn tại sau khi Payment `PAID`. Sweetbox phát hành hai Ticket độc lập, mỗi
ghế vật lý một vé.

## Đọc vé

```http
GET /api/v1/tickets/TK-AbCdEf123
Authorization: Bearer <staff-or-customer-jwt>
```

```json
{
  "data": {
    "ticketId": 91,
    "ticketCode": "TK-AbCdEf123",
    "priceSnapshot": 100000.00,
    "status": "VALID",
    "issuedAt": "2030-01-04T10:00:00Z",
    "usedAt": null,
    "bookingId": 41,
    "bookingCode": "BK-300104-0041",
    "bookingStatus": "PAID",
    "bookingChannel": "ONLINE",
    "showtimeId": 10,
    "startTime": "2030-01-04T17:00:00+07:00",
    "endTime": "2030-01-04T19:15:00+07:00",
    "movieId": 3,
    "movieTitle": "Movie",
    "ageRating": "T16",
    "auditoriumId": 2,
    "auditoriumName": "Room 2",
    "seatId": 101,
    "seatRow": "A",
    "seatNumber": 1,
    "seatType": "STANDARD"
  }
}
```

`GET /api/v1/bookings/{bookingId}/tickets` trả mảng cùng cấu trúc, sắp xếp theo
`ticket_id`. STAFF đọc được vé của mọi Booking; CUSTOMER chỉ đọc vé thuộc Booking có
`user_id` của mình. Ticket không tồn tại hoặc không thuộc quyền đọc đều trả
404 `TICKET_NOT_FOUND`; danh sách sai quyền trả 404 `BOOKING_NOT_FOUND` để không lộ
sự tồn tại của dữ liệu người khác.

## Soát vé

```http
POST /api/v1/tickets/validate
Authorization: Bearer <staff-jwt>
Content-Type: application/json

{"ticketCode":"TK-AbCdEf123","ageDocumentChecked":true}
```

```json
{
  "data": {
    "ticketId": 91,
    "ticketCode": "TK-AbCdEf123",
    "status": "USED",
    "bookingId": 41,
    "bookingCode": "BK-300104-0041",
    "showtimeId": 10,
    "movieId": 3,
    "movieTitle": "Movie",
    "ageRating": "T16",
    "seatId": 101,
    "seatRow": "A",
    "seatNumber": 1,
    "usedAt": "2030-01-04T16:55:12.123456Z"
  }
}
```

### Khung giờ soát vé

Quy tắc đã chọn:

- Mở cổng **60 phút trước** `showtimes.start_time`.
- Đóng cổng **30 phút sau** `showtimes.end_time`.
- `showtimes.end_time` đã gồm 15 phút buffer dọn phòng, nên thời gian đóng cổng thực tế
  là sau khi phim kết thúc 15 phút buffer cộng thêm 30 phút.
- Ngoài khoảng này trả 422 `TICKET_OUTSIDE_SHOWTIME`; vé vẫn `VALID`.

### Kiểm tra tuổi tại cổng

`ageRating` luôn đọc từ Movie của Showtime trong DB, không lấy từ client.

- Phim `P`: `ageDocumentChecked` có thể bỏ qua; không ghi `age_verifications`.
- Phim `T13/T16/T18`: bắt buộc `ageDocumentChecked: true`, nếu thiếu hoặc `false` trả
  422 `AGE_VERIFICATION_REQUIRED`; vé vẫn `VALID` và chưa ghi audit.
- Khi hợp lệ, ghi một dòng `age_verifications` với `verification_stage = 'GATE'`,
  `ticket_id`, `verified_by_staff_id` lấy từ JWT, `verified_at` từ bean `Clock`.
  Không lưu số giấy tờ, ảnh giấy tờ hay PIN.

### Trạng thái và khóa đồng thời

Service khóa Ticket bằng `TicketRepository.findByTicketCodeForUpdate`
(`PESSIMISTIC_WRITE`) **trước khi** đọc trạng thái, sau đó:

- `VALID` → `USED`, ghi `used_at` từ `Clock` (`Ticket.markUsed`).
- `USED` → 409 `TICKET_ALREADY_USED`.
- `CANCELLED` → 422 `TICKET_NOT_VALID`.

Hai lần quét song song cùng một vé: request thứ hai chờ khóa, đọc lại trạng thái sau
khi request đầu commit và nhận 409; chỉ một lần soát thành công. Transaction dùng
`READ_COMMITTED`, không `REQUIRES_NEW`.

## Lỗi nghiệp vụ

| HTTP | Code | Khi nào |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Thiếu `ticketCode`, body sai, JSON lạ |
| 403 | `ACCESS_DENIED` | CUSTOMER gọi endpoint soát vé |
| 404 | `TICKET_NOT_FOUND` | Không có vé hoặc CUSTOMER không sở hữu vé |
| 404 | `BOOKING_NOT_FOUND` | Không có Booking hoặc CUSTOMER không sở hữu Booking |
| 409 | `TICKET_ALREADY_USED` | Vé đã được soát |
| 422 | `TICKET_NOT_VALID` | Vé `CANCELLED` |
| 422 | `TICKET_OUTSIDE_SHOWTIME` | Ngoài khung giờ soát vé |
| 422 | `AGE_VERIFICATION_REQUIRED` | Phim hạn chế tuổi chưa xác nhận giấy tờ |

## Audit log

Mỗi lần soát thành công ghi logger `AUDIT_TICKET`:

```text
action=TICKET_VALIDATED result=USED ticketId=... ticketCode=... bookingId=...
showtimeId=... staffId=... ageRating=...
```

Không log JWT, hold token, PIN hay VNPay secret.
