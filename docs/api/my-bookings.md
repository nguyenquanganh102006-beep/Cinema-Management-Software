# Lịch sử Booking của CUSTOMER — Sprint E

Base `/api/v1`, JWT Bearer, chỉ CUSTOMER. STAFF gọi nhận 403 `ACCESS_DENIED`.
`userId` luôn lấy từ JWT, không nhận từ query/body.

| Method | Path | Thành công |
|---|---|---|
| GET | `/me/bookings?page=0&size=20` | 200 `PageResponse<MeBookingSummaryResponse>` |
| GET | `/me/bookings/{bookingId}` | 200 MeBookingDetailResponse |

## Danh sách

`page` từ 0, `size` 1–100 (mặc định 20); vượt giới hạn trả 400 `VALIDATION_ERROR`.
Sắp xếp `createdAt DESC, bookingId DESC`. Chỉ trả Booking có `user_id` của CUSTOMER
đang đăng nhập, gồm mọi trạng thái.

```json
{
  "data": {
    "items": [
      {
        "bookingId": 41,
        "bookingCode": "BK-300104-0041",
        "status": "PAID",
        "channel": "ONLINE",
        "totalAmount": 190000.00,
        "createdAt": "2030-01-04T10:00:00Z",
        "paidAt": "2030-01-04T10:01:00Z",
        "showtimeId": 10
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

## Chi tiết

```http
GET /api/v1/me/bookings/41
Authorization: Bearer <customer-jwt>
```

```json
{
  "data": {
    "bookingId": 41,
    "bookingCode": "BK-300104-0041",
    "status": "PAID",
    "channel": "ONLINE",
    "totalAmount": 190000.00,
    "ticketTotal": 180000.00,
    "concessionTotal": 10000.00,
    "createdAt": "2030-01-04T10:00:00Z",
    "paidAt": "2030-01-04T10:01:00Z",
    "showtime": {
      "showtimeId": 10,
      "movieId": 3,
      "movieTitle": "Movie",
      "ageRating": "T16",
      "auditoriumId": 2,
      "auditoriumName": "Room 2",
      "startTime": "2030-01-04T17:00:00+07:00",
      "endTime": "2030-01-04T19:15:00+07:00"
    },
    "tickets": [
      {
        "ticketId": 91,
        "ticketCode": "TK-AbCdEf123",
        "seatId": 101,
        "seatRow": "A",
        "seatNumber": 1,
        "seatType": "STANDARD",
        "priceSnapshot": 90000.00,
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
      "method": "GATEWAY_ONLINE",
      "status": "PAID",
      "amount": 190000.00,
      "paidAt": "2030-01-04T10:01:00Z"
    }
  }
}
```

- Booking của CUSTOMER khác hoặc không tồn tại trả 404 `BOOKING_NOT_FOUND`, không lộ
  sự tồn tại.
- Booking chưa thanh toán có `tickets` rỗng và `payment` có thể `null`.
- Không trả JWT, hold token, PIN, thông tin cổng thanh toán hay secret.
