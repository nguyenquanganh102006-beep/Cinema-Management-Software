# Lịch sử bán vé tại quầy (POS history) — Sprint E

Base `/api/v1`, JWT Bearer. Chỉ STAFF. Dữ liệu chỉ đọc từ `bookings`, `payments`,
`tickets`, `booking_seats`, `booking_concessions`; không tạo bảng mới. Không trả PIN,
secret, JWT hay Entity.

| Method | Path | Quyền | Thành công |
|---|---|---|---|
| GET | `/pos/bookings` | STAFF | 200 `PageResponse<PosBookingSummaryResponse>` |
| GET | `/pos/bookings/{bookingId}` | STAFF | 200 PosBookingDetailResponse |

## Nguyên tắc chung

- Chỉ Booking có `channel = POS`. Booking `ONLINE` bị loại khỏi cả danh sách và chi
  tiết; gọi chi tiết Booking ONLINE trả 404 `BOOKING_NOT_FOUND`.
- Danh sách chỉ trả Booking đã `PAID`. Chi tiết đọc được Booking POS ở mọi trạng thái
  (ví dụ PENDING/VIETQR đang chờ chuyển khoản), miễn là đúng kênh POS.
- `refundStatus` hiện luôn `null`; hoàn tiền thuộc Sprint F.
- Không trả thông tin thẻ, PIN, chữ ký VNPay hay token.

## Danh sách

```http
GET /api/v1/pos/bookings?bookingCode=0041&date=2030-01-04&paymentMethod=CASH&page=0&size=20
Authorization: Bearer <staff-jwt>
```

| Query | Ý nghĩa |
|---|---|
| `bookingCode` | Tìm tương đối, không phân biệt hoa/thường, trên `bookings.booking_code` |
| `date` | `yyyy-MM-dd`, lọc theo `payments.paid_at` trong ngày tại `Asia/Ho_Chi_Minh` |
| `paymentMethod` | `CASH` hoặc `VIETQR`; giá trị khác → 400 `VALIDATION_ERROR` |
| `page` | Từ 0, mặc định 0 |
| `size` | 1–100, mặc định 20; vượt 100 → 400 `VALIDATION_ERROR` |

Sắp xếp `paidAt DESC, bookingId DESC`. Khi lọc `paymentMethod`, backend dùng EXISTS trên
`payments` theo `booking_id`.

```json
{
  "data": {
    "items": [
      {
        "bookingId": 42,
        "bookingCode": "BK-300104-0042",
        "paidAt": "2030-01-04T10:01:00Z",
        "totalAmount": 170000.00,
        "paymentMethod": "CASH",
        "paymentStatus": "PAID",
        "ticketCount": 2,
        "refundStatus": null
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

`paidAt`/`paymentMethod`/`paymentStatus` lấy từ Payment của Booking. `ticketCount` là
số Ticket đã phát hành, đếm theo lô để tránh N+1.

## Chi tiết

```http
GET /api/v1/pos/bookings/42
Authorization: Bearer <staff-jwt>
```

```json
{
  "data": {
    "bookingId": 42,
    "bookingCode": "BK-300104-0042",
    "channel": "POS",
    "status": "PAID",
    "ticketTotal": 160000.00,
    "concessionTotal": 10000.00,
    "totalAmount": 170000.00,
    "createdAt": "2030-01-04T10:00:00Z",
    "paidAt": "2030-01-04T10:01:00Z",
    "showtime": {
      "showtimeId": 10,
      "movieId": 3,
      "movieTitle": "Movie",
      "ageRating": "P",
      "auditoriumId": 2,
      "auditoriumName": "Room 2",
      "startTime": "2030-01-04T17:00:00+07:00",
      "endTime": "2030-01-04T19:15:00+07:00"
    },
    "seats": [
      {
        "seatId": 101,
        "seatRow": "A",
        "seatNumber": 1,
        "seatType": "STANDARD",
        "quotedPrice": 80000.00
      }
    ],
    "tickets": [
      {
        "ticketId": 91,
        "ticketCode": "TK-AbCdEf123",
        "showtimeSeatId": 501,
        "seatId": 101,
        "priceSnapshot": 80000.00,
        "status": "VALID",
        "issuedAt": "2030-01-04T10:00:00Z",
        "usedAt": null
      }
    ],
    "concessions": [
      {
        "itemId": 7,
        "itemName": "Snack",
        "quantity": 1,
        "unitPrice": 10000.00,
        "lineTotal": 10000.00
      }
    ],
    "payment": {
      "paymentId": 42,
      "method": "CASH",
      "status": "PAID",
      "amount": 170000.00,
      "cashReceived": 200000.00,
      "paidAt": "2030-01-04T10:01:00Z",
      "confirmedByStaffId": 5
    },
    "performedBy": {"staffId": 5, "fullName": "Staff"}
  }
}
```

- `seats` lấy `booking_seats.quoted_price`; `concessions` lấy
  `booking_concessions.unit_price_snapshot` và nhân số lượng để ra `lineTotal`.
- `tickets.priceSnapshot` là giá chốt lúc checkout.
- `performedBy` ưu tiên `payments.confirmed_by_staff_id`, nếu chưa có thì
  `bookings.staff_id`; chỉ trả `staffId` và `fullName`.

## Lỗi nghiệp vụ

| HTTP | Code | Khi nào |
|---|---|---|
| 400 | `VALIDATION_ERROR` | `size` ngoài 1–100, `date` sai định dạng, `paymentMethod` không phải CASH/VIETQR |
| 403 | `ACCESS_DENIED` | CUSTOMER gọi endpoint POS |
| 404 | `BOOKING_NOT_FOUND` | Booking không tồn tại hoặc không phải kênh POS |

## Kiểm thử

`TicketPosHistoryIntegrationTest` phủ: CUSTOMER A không đọc được vé/Booking của
CUSTOMER B; `/me/bookings` phân trang và chỉ trả Booking của JWT; `/pos/bookings`
không chứa Booking ONLINE; lọc `bookingCode`/`date`/`paymentMethod`; `paidAt DESC`;
chi tiết POS; soát vé hai lần; phim T18 thiếu age check; vé CANCELLED; ngoài khung giờ;
và hai lần quét song song chỉ một thành công.
