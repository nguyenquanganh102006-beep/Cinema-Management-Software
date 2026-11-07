# Báo giá vé và tạo Booking — Sprint E

Base `/api/v1`, JWT Bearer Foundation. Thành công bọc `data`; lỗi dùng `timestamp/status/code/message/details/path`. Không nhận `price`, `totalAmount`, `userId`, `role` từ client; JSON field lạ bị từ chối.

| Method | Path | Quyền | Thành công |
|---|---|---|---|
| POST | `/pricing/quote` | STAFF/CUSTOMER | 200 PricingQuoteResponse |
| POST | `/bookings` | STAFF/CUSTOMER | 201 BookingResponse |
| GET | `/bookings/{id}` | STAFF/CUSTOMER sở hữu Booking | 200 BookingResponse |

## Công thức giá vé

Giá mỗi ghế vật lý:

```text
finalPrice = showtimes.base_price
           + ROOM_SURCHARGE(auditorium_type)
           + SEAT_SURCHARGE(seat_type)
           + TIME_SURCHARGE
```

- `showtimes.base_price` là snapshot lúc tạo suất chiếu; không đọc lại rule `BASE_PRICE`.
- `TIME_SURCHARGE` cộng đúng **một lần** nếu suất bắt đầu vào Thứ Sáu, Thứ Bảy, Chủ Nhật **hoặc** sau 18:00 theo `Asia/Ho_Chi_Minh`. Thỏa cả hai điều kiện vẫn chỉ cộng một lần.
- Sweetbox là hai ghế vật lý, mỗi ghế được tính riêng. Hold của Sweetbox đã mở rộng đủ cặp nên Booking nhận hai dòng vé.
- Tất cả tiền dùng `BigDecimal`, scale 2. Thiếu rule giá trả 422 `PRICING_RULE_MISSING`.

## Báo giá

```http
POST /api/v1/pricing/quote
Authorization: Bearer <staff-or-customer-jwt>
Content-Type: application/json

{"showtimeId":10,"seatIds":[101,102]}
```

```json
{
  "data": {
    "showtimeId": 10,
    "seats": [
      {
        "seatId": 101,
        "basePrice": 70000.00,
        "roomSurcharge": 20000.00,
        "seatSurcharge": 0.00,
        "timeSurcharge": 10000.00,
        "finalPrice": 100000.00
      },
      {
        "seatId": 102,
        "basePrice": 70000.00,
        "roomSurcharge": 20000.00,
        "seatSurcharge": 15000.00,
        "timeSurcharge": 10000.00,
        "finalPrice": 115000.00
      }
    ],
    "ticketTotal": 215000.00
  }
}
```

Endpoint chỉ để hiển thị, không giữ ghế và không tạo dữ liệu. 1–100 ID nguyên dương, không null/trùng; ghế phải thuộc đúng phòng của suất chiếu, nếu không trả 400 `INVALID_SEAT`.

## Tạo Booking PENDING

```http
POST /api/v1/bookings
Authorization: Bearer <staff-or-customer-jwt>
Content-Type: application/json

{
  "showtimeId": 10,
  "holdToken": "01234567-89ab-4cde-8fab-0123456789ab",
  "concessions": [{"itemId": 7, "quantity": 2}],
  "ageConfirmed": true
}
```

```json
{
  "data": {
    "bookingId": 41,
    "bookingCode": "BK-261107-0001",
    "status": "PENDING",
    "tickets": [{"seatId":101,"price":100000.00}],
    "concessions": [{"itemId":7,"quantity":2,"unitPrice":45000.00}],
    "totalAmount": 190000.00,
    "holdUntil": "2026-11-07T12:05:00Z"
  }
}
```

Trong một transaction `READ_COMMITTED`:

1. Xác thực hold bằng token và owner lấy từ JWT; ghế dự kiến là các ghế vật lý đang thuộc token. Không sở hữu → 403 `SEAT_HOLD_NOT_OWNED`; hết hạn → 409 `SEAT_HOLD_EXPIRED`; lệch suất/nhóm → 409 `SEAT_HOLD_MISMATCH`.
2. Validate F&B: item tồn tại, đang bán, `quantity > 0`, không trùng `itemId`. **Chưa trừ kho và chưa reserve**; việc trừ kho chỉ xảy ra lúc checkout.
3. Backend tính lại giá vé và giá F&B; client không gửi giá.
4. `channel = POS` cho STAFF, `ONLINE` cho CUSTOMER. `user_id`/`staff_id` lấy từ JWT.
5. Lưu `bookings` PENDING, `booking_seats.quoted_price`, `booking_concessions.unit_price_snapshot`.
6. Sinh `booking_code` bằng counter ngày **ở bước cuối**, ngay trước khi lưu Booking, để không giữ khóa counter trong lúc validate/tính giá.

### Kiểm soát tuổi

- CUSTOMER + phim T13/T16/T18: bắt buộc `ageConfirmed: true`, nếu không trả 422 `AGE_CONFIRMATION_REQUIRED`. Khi hợp lệ lưu `bookings.age_confirmed_at`.
- STAFF (POS) không gửi `ageConfirmed`; kiểm tra giấy tờ thuộc bước thanh toán POS.
- `ageRating` luôn đọc từ Movie của suất chiếu trong DB.

### Một hold chỉ tạo một Booking

Chọn **409 `BOOKING_ALREADY_EXISTS`**. Retry sau khi tạo thành công sẽ nhận 409 thay vì trả lại Booking cũ, để client không tưởng là một giao dịch mới. Kiểm tra tồn tại được chạy trước khi khóa ghế và chạy lại sau khi đã khóa; vì vậy hai request đồng thời cùng token bị tuần tự hóa và chỉ một request tạo được Booking. Client nên dùng `GET /bookings/{id}` hoặc màn hình lịch sử để xem lại.

### Lỗi nghiệp vụ

| HTTP | Code | Khi nào |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Body/ID/quantity sai, trùng `itemId`, token không phải UUID |
| 403 | `SEAT_HOLD_NOT_OWNED` | Hold thuộc phiên JWT khác |
| 404 | `BOOKING_NOT_FOUND` | Không có Booking hoặc không thuộc người gọi |
| 404 | `SHOWTIME_NOT_FOUND` | Suất chiếu không tồn tại |
| 404 | `AUDITORIUM_NOT_FOUND` | Phòng chiếu của suất không tồn tại |
| 404 | `MOVIE_NOT_FOUND` | Phim của suất không tồn tại |
| 404 | `CONCESSION_ITEM_NOT_FOUND` | `itemId` không tồn tại |
| 409 | `BOOKING_ALREADY_EXISTS` | Hold đã tạo Booking |
| 409 | `SEAT_HOLD_EXPIRED` | Hold hết hạn hoặc đã bị release |
| 409 | `SEAT_HOLD_MISMATCH` | Token không thuộc đúng suất/nhóm ghế |
| 422 | `AGE_CONFIRMATION_REQUIRED` | CUSTOMER chưa xác nhận tuổi |
| 422 | `CONCESSION_ITEM_INACTIVE` | Sản phẩm F&B đã ngừng bán |
| 422 | `PRICING_RULE_MISSING` | Thiếu rule giá cần dùng |

## Đọc Booking

`GET /api/v1/bookings/{id}` trả cùng cấu trúc `BookingResponse`. CUSTOMER chỉ đọc Booking có `user_id` của mình; STAFF chỉ đọc Booking POS do mình tạo. Booking của người khác trả 404 `BOOKING_NOT_FOUND` để không lộ sự tồn tại. `holdUntil` là hạn giữ còn hiệu lực nếu hold chưa bị clear; sau checkout có thể là `null`.

## Thanh toán POS

Booking PENDING kênh POS được STAFF thanh toán tại quầy qua cash hoặc VietQR. Xem hợp
đồng chi tiết, quyết định cho STAFF khác và mã lỗi tại
[pos-payments.md](pos-payments.md). Booking ONLINE không dùng các endpoint này và nhận
422 `BOOKING_CHANNEL_INVALID`.

## Thanh toán online VNPay

Booking PENDING kênh ONLINE do CUSTOMER thanh toán qua VNPay. Return URL chỉ hiển thị
trạng thái; Ticket chỉ được phát hành khi IPN hợp lệ. Xem URL thanh toán, contract
IPN, nhánh ngoại lệ và mã lỗi tại [vnpay-payment.md](vnpay-payment.md).
