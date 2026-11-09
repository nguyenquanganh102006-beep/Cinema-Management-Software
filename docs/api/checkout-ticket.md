# Checkout, Payment và Ticket — Sprint E

Tài liệu này là hợp đồng tổng hợp cho toàn bộ API Sprint E: báo giá, tạo/đọc Booking,
thanh toán POS và VNPay, đọc vé, soát vé tại cổng, lịch sử CUSTOMER và lịch sử POS.
Base `/api/v1`, JWT Bearer của Foundation. Thành công bọc trong `data`; lỗi dùng
`timestamp/status/code/message/details/path`.

Các tài liệu chi tiết theo chủ đề:

- [Báo giá và tạo Booking](bookings.md)
- [Thanh toán POS](pos-payments.md)
- [Thanh toán VNPay](vnpay-payment.md)
- [Đọc vé và soát vé](tickets.md)
- [Lịch sử Booking của CUSTOMER](my-bookings.md)
- [Lịch sử bán vé tại quầy](pos-history.md)

## Danh sách endpoint

| Method | Path | Quyền | Thành công |
|---|---|---|---|
| POST | `/pricing/quote` | STAFF, CUSTOMER | 200 `PricingQuoteResponse` |
| POST | `/bookings` | STAFF, CUSTOMER | 201 `BookingResponse` |
| GET | `/bookings/{id}` | STAFF (Booking của mình), CUSTOMER (chủ sở hữu) | 200 `BookingResponse` |
| GET | `/bookings/{bookingId}/tickets` | STAFF, CUSTOMER (chủ sở hữu) | 200 `[TicketResponse]` |
| GET | `/me/bookings` | CUSTOMER | 200 `PageResponse<MeBookingSummaryResponse>` |
| GET | `/me/bookings/{bookingId}` | CUSTOMER (chủ sở hữu) | 200 `MeBookingDetailResponse` |
| POST | `/bookings/{bookingId}/payments/cash` | STAFF | 200 `CashPaymentResponse` |
| POST | `/bookings/{bookingId}/payments/vietqr` | STAFF | 200 `VietQrPaymentResponse` (PENDING) |
| POST | `/bookings/{bookingId}/payments/vietqr/confirm` | STAFF | 200 `VietQrConfirmResponse` (PAID) |
| POST | `/bookings/{bookingId}/payments/vnpay` | CUSTOMER (chủ sở hữu) | 200 `VnPayPaymentResponse` |
| GET | `/payments/vnpay/return` | Công khai, xác thực bằng chữ ký | 200 `VnPayReturnResponse` |
| GET | `/payments/vnpay/ipn` | Công khai, xác thực bằng chữ ký | 200 `VnPayIpnResponse` |
| GET | `/tickets/{ticketCode}` | STAFF, CUSTOMER (chủ sở hữu) | 200 `TicketResponse` |
| POST | `/tickets/validate` | STAFF | 200 `TicketValidationResponse` |
| GET | `/pos/bookings` | STAFF | 200 `PageResponse<PosBookingSummaryResponse>` |
| GET | `/pos/bookings/{bookingId}` | STAFF | 200 `PosBookingDetailResponse` |

Controller được tách theo role: POS là `hasRole('STAFF')`, luồng CUSTOMER là
`hasRole('CUSTOMER')`, callback VNPay không có `@PreAuthorize`. Không endpoint nào nhận
`price`, `totalAmount`, `role`, `userId`, `staffId`, `ageRating` hay `paymentStatus` từ
client.

## Trạng thái và luồng tiền

```text
Hold D: HOLDING
  → POST /bookings                 Booking PENDING, ghế vẫn HOLDING
  → POS cash / VietQR confirm      \  một transaction:
  → VNPay IPN hợp lệ               /  lock Booking → markBooked → re-price
                                       → lock/re-check F&B → trừ kho + SALE
                                       → Payment PAID → Ticket VALID
                                       → Booking PAID
```

- Chỉ Payment `PAID` mới phát hành Ticket.
- Mỗi ghế vật lý có một Ticket; Sweetbox phát hành hai Ticket độc lập.
- F&B không được giữ hay trừ kho lúc tạo Booking; chỉ kiểm tra và trừ ở bước checkout.
- Lỗi ở bất kỳ bước checkout nào rollback toàn bộ: Booking vẫn `PENDING`, ghế vẫn
  `HOLDING`, không Payment/Ticket/SALE.

## Mã lỗi chung

Mọi endpoint có JWT (trừ hai callback VNPay) có thể trả:

| HTTP | Code | Khi nào |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Body/query/path sai kiểu, thiếu trường, JSON lạ, phân trang ngoài giới hạn |
| 401 | `AUTH_TOKEN_INVALID` / `AUTH_TOKEN_EXPIRED` | JWT thiếu, sai hoặc hết hạn |
| 403 | `ACCESS_DENIED` | Sai role hoặc không đủ quyền |
| 409 | `SEAT_UPDATE_CONFLICT` | Xung đột khóa/phiên bản tạm thời khi giành ghế |
| 500 | `INTERNAL_ERROR` | Lỗi không lường trước; không trả chi tiết nội bộ |

Các mã nghiệp vụ riêng được liệt kê theo từng nhóm bên dưới.

## Báo giá vé

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
      }
    ],
    "ticketTotal": 100000.00
  }
}
```

Backend tính giá từ `showtimes.base_price` và các rule hiện hành. Endpoint chỉ hiển
thị, không giữ ghế và không ghi dữ liệu. Tối đa 100 seat ID nguyên dương, không
null/trùng; ghế phải thuộc đúng phòng của suất chiếu.

| HTTP | Code | Khi nào |
|---|---|---|
| 400 | `INVALID_SEAT` | Ghế không thuộc phòng của suất chiếu |
| 404 | `SHOWTIME_NOT_FOUND` / `AUDITORIUM_NOT_FOUND` | Không tìm thấy suất chiếu/phòng |
| 422 | `PRICING_RULE_MISSING` | Thiếu rule giá cần dùng |

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

Quy tắc:

1. Caller phải sở hữu hold token; expected seat IDs lấy từ chính các ghế đang thuộc
   token, không tin danh sách ghế từ client.
2. Mỗi hold chỉ tạo được **một** Booking. Retry cùng token trả 409
   `BOOKING_ALREADY_EXISTS`; kiểm tra chạy trước và sau khi khóa ghế để hai request
   đồng thời chỉ một bên thắng.
3. `channel = POS` cho STAFF, `ONLINE` cho CUSTOMER. `user_id`/`staff_id` lấy từ JWT.
4. CUSTOMER đặt phim T13/T16/T18 phải gửi `ageConfirmed=true`; backend lưu
   `bookings.age_confirmed_at`. STAFF không gửi cờ này; kiểm tra giấy tờ thuộc bước
   thanh toán POS.
5. Giá vé và giá F&B do backend tính; lưu `booking_seats.quoted_price` và
   `booking_concessions.unit_price_snapshot`.
6. F&B chỉ được kiểm tra tồn tại/đang bán/số lượng, **không** trừ kho, **không** giữ.

| HTTP | Code | Khi nào |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Body/ID/quantity sai, trùng `itemId`, token không phải UUID |
| 403 | `SEAT_HOLD_NOT_OWNED` | Hold thuộc phiên JWT khác |
| 404 | `SHOWTIME_NOT_FOUND` / `MOVIE_NOT_FOUND` / `AUDITORIUM_NOT_FOUND` | Thiếu dữ liệu suất chiếu/phòng/phim |
| 404 | `CONCESSION_ITEM_NOT_FOUND` | `itemId` không tồn tại |
| 409 | `BOOKING_ALREADY_EXISTS` | Hold đã tạo Booking |
| 409 | `SEAT_HOLD_EXPIRED` | Hold hết hạn hoặc đã bị release |
| 409 | `SEAT_HOLD_MISMATCH` | Token không thuộc đúng suất/nhóm ghế |
| 422 | `AGE_CONFIRMATION_REQUIRED` | CUSTOMER chưa xác nhận tuổi |
| 422 | `CONCESSION_ITEM_INACTIVE` | Sản phẩm F&B đã ngừng bán |
| 422 | `PRICING_RULE_MISSING` | Thiếu rule giá cần dùng |

## Đọc Booking và vé của Booking

```http
GET /api/v1/bookings/41
Authorization: Bearer <staff-or-customer-jwt>
```

Trả `BookingResponse` như trên. CUSTOMER chỉ đọc Booking có `user_id` của mình; STAFF
chỉ đọc Booking POS do mình tạo. Booking của người khác trả 404 `BOOKING_NOT_FOUND`.

```http
GET /api/v1/bookings/41/tickets
Authorization: Bearer <staff-or-customer-jwt>
```

```json
{
  "data": [
    {
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
  ]
}
```

STAFF đọc được vé của mọi Booking; CUSTOMER chỉ đọc vé thuộc Booking của mình. Danh
sách trả mảng rỗng nếu Booking chưa thanh toán. Booking không tồn tại hoặc không thuộc
quyền đọc trả 404 `BOOKING_NOT_FOUND`.

## Thanh toán POS — tiền mặt

```http
POST /api/v1/bookings/123/payments/cash
Authorization: Bearer <staff-jwt>
Content-Type: application/json

{"cashReceived":500000.00,"ageDocumentChecked":true}
```

```json
{
  "data": {
    "paymentId": 77,
    "bookingId": 123,
    "bookingCode": "BK-301231-0042",
    "totalAmount": 180000.00,
    "cashReceived": 500000.00,
    "change": 320000.00,
    "paymentStatus": "PAID",
    "paidAt": "2030-01-04T10:05:00Z",
    "tickets": [
      {
        "ticketId": 901,
        "ticketCode": "TK-...",
        "showtimeSeatId": 501,
        "price": 90000.00,
        "status": "VALID",
        "issuedAt": "2030-01-04T10:05:00Z"
      }
    ]
  }
}
```

`payments.amount` luôn bằng `bookings.total_amount`; `cashReceived` chỉ dùng để tính
`change`. `cashReceived < total_amount` trả 422 `PAYMENT_CASH_INSUFFICIENT`. Phim
T13/T16/T18 bắt buộc `ageDocumentChecked=true`; khi hợp lệ ghi `age_verifications`
stage `POS`. Bất kỳ STAFF nào cũng thanh toán được Booking POS; người bấm xác nhận
được lưu vào `payments.confirmed_by_staff_id`.

## Thanh toán POS — VietQR

Tạo Payment PENDING:

```http
POST /api/v1/bookings/123/payments/vietqr
Authorization: Bearer <staff-jwt>
Content-Type: application/json

{"ageDocumentChecked":true}
```

```json
{
  "data": {
    "paymentId": 77,
    "bookingId": 123,
    "bookingCode": "BK-301231-0042",
    "amount": 180000.00,
    "bankAccount": "0123456789",
    "bankName": "Vietcombank",
    "transferContent": "CINEMA BK-301231-0042",
    "qrPayload": "vietqr://transfer?bankName=Vietcombank&account=0123456789&amount=180000.00&content=CINEMA%20BK-301231-0042",
    "paymentStatus": "PENDING"
  }
}
```

Xác nhận đã nhận tiền (không body):

```http
POST /api/v1/bookings/123/payments/vietqr/confirm
Authorization: Bearer <staff-jwt>
```

```json
{
  "data": {
    "paymentId": 77,
    "bookingId": 123,
    "bookingCode": "BK-301231-0042",
    "amount": 180000.00,
    "paymentStatus": "PAID",
    "confirmedByStaffId": 12,
    "paidAt": "2030-01-04T10:10:00Z",
    "tickets": [
      {
        "ticketId": 901,
        "ticketCode": "TK-...",
        "showtimeSeatId": 501,
        "price": 90000.00,
        "status": "VALID",
        "issuedAt": "2030-01-04T10:10:00Z"
      }
    ]
  }
}
```

- Tạo VietQR là idempotent khi đã có Payment `VIETQR` PENDING; không tạo dòng thứ hai.
- Confirm lần hai trả 409 `BOOKING_ALREADY_PAID`, không phát hành thêm Ticket.
- Thiếu `CINEMA_BANK_ACCOUNT`/`CINEMA_BANK_NAME` trả 503
  `PAYMENT_CONFIGURATION_MISSING`.
- `qrPayload` hiện là URI nội bộ để portal render QR, chưa phải chuỗi EMVCo.

### Mã lỗi thanh toán POS

| HTTP | Code | Khi nào |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Thiếu/sai `cashReceived`, body sai, JSON lạ |
| 404 | `BOOKING_NOT_FOUND` | Booking không tồn tại |
| 404 | `PAYMENT_NOT_FOUND` | Confirm khi chưa tạo Payment VietQR |
| 404 | `SHOWTIME_NOT_FOUND` / `MOVIE_NOT_FOUND` | Không đọc được tuổi phim |
| 409 | `BOOKING_ALREADY_PAID` | Booking đã thanh toán, kể cả confirm lần hai |
| 409 | `BOOKING_INVALID_STATE` | Booking CANCELLED/EXCEPTION_PENDING_DECISION |
| 409 | `PAYMENT_METHOD_CONFLICT` | Booking đã có Payment khác phương thức |
| 409 | `PAYMENT_INVALID_STATE` | Payment PAID/FAILED, không còn PENDING |
| 409 | `PAYMENT_AMOUNT_MISMATCH` | Payment amount khác `bookings.total_amount` |
| 409 | `PRICE_CHANGED` | Giá tính lại lúc checkout khác giá đã báo |
| 409 | `SEAT_HOLD_EXPIRED` / `SEAT_HOLD_MISMATCH` | Hold hết hạn hoặc lệch nhóm ghế |
| 409 | `INSUFFICIENT_STOCK` / `OUT_OF_STOCK` | Kho F&B không đủ lúc commit |
| 422 | `BOOKING_CHANNEL_INVALID` | Booking kênh ONLINE |
| 422 | `AGE_VERIFICATION_REQUIRED` | Phim hạn chế tuổi chưa xác nhận giấy tờ |
| 422 | `PAYMENT_CASH_INSUFFICIENT` | `cashReceived < total_amount` |
| 503 | `PAYMENT_CONFIGURATION_MISSING` | Thiếu cấu hình tài khoản nhận chuyển khoản |

## Thanh toán online VNPay

`GATEWAY_ONLINE` là phương thức thanh toán của VNPay. Mỗi Booking ONLINE có tối đa một
Payment (`payments.booking_id` UNIQUE). Không có SDK; chữ ký dùng
`javax.crypto.Mac` HmacSHA512.

### Tạo URL thanh toán

```http
POST /api/v1/bookings/123/payments/vnpay
Authorization: Bearer <customer-jwt>
```

```json
{
  "data": {
    "paymentUrl": "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_Amount=18000000&...&vnp_SecureHash=...",
    "expiresAt": "2026-11-07T12:05:00Z"
  }
}
```

Điều kiện: Booking thuộc CUSTOMER đang đăng nhập, `channel=ONLINE`, `status=PENDING`,
hold còn hiệu lực, phim hạn chế tuổi đã có `age_confirmed_at`. Retry khi Payment còn
PENDING cấp `vnp_txn_ref` mới trên **cùng** dòng Payment; URL cũ hết hiệu lực.
`vnp_ExpireDate` không vượt quá hạn hold. Hold online không được gia hạn.

### Return URL — chỉ hiển thị

```http
GET /api/v1/payments/vnpay/return?vnp_TxnRef=...&vnp_ResponseCode=00&vnp_SecureHash=...
```

```json
{
  "data": {
    "paymentId": 9,
    "bookingId": 123,
    "bookingCode": "BK-261107-0001",
    "vnpTxnRef": "VNP123T...",
    "vnpResponseCode": "00",
    "vnpTransactionNo": "12345678",
    "amount": 180000.00,
    "paymentStatus": "PENDING",
    "bookingStatus": "PENDING"
  }
}
```

Endpoint **không** finalize, **không** đổi Payment, **không** phát hành Ticket. Chữ ký
sai trả 400 `VNPAY_INVALID_SIGNATURE`; thiếu `vnp_TxnRef` trả 400 `VALIDATION_ERROR`;
không tìm thấy Payment/Booking trả 404 `PAYMENT_NOT_FOUND`/`BOOKING_NOT_FOUND`; thiếu
cấu hình trả 503 `PAYMENT_CONFIGURATION_MISSING`.

### IPN — nơi duy nhất được finalize

VNPay gọi `GET /api/v1/payments/vnpay/ipn` kèm query params. Thứ tự: verify chữ ký và
`vnp_TmnCode` → tìm Payment theo `vnp_TxnRef` → kiểm tra method/channel → đối chiếu
`vnp_Amount = payments.amount × 100` → kiểm tra trạng thái/idempotency → finalize nếu
`vnp_ResponseCode=00`.

HTTP luôn 200; VNPay đọc `RspCode` trong body:

```json
{"RspCode":"00","Message":"Confirm Success"}
```

| `RspCode` | Khi nào |
|---|---|
| `00` | Finalize thành công, ghi nhận giao dịch thất bại, hoặc đã đưa Booking vào nhánh ngoại lệ |
| `01` | Không có Payment theo `vnp_TxnRef`, hoặc thiếu `vnp_TxnRef` |
| `02` | Payment/Booking đã PAID (IPN lặp) |
| `04` | `vnp_Amount` không khớp `payments.amount × 100` |
| `97` | Sai chữ ký hoặc sai `vnp_TmnCode` |
| `99` | Thiếu cấu hình, thiếu `vnp_ResponseCode`, dữ liệu không hợp lệ, lỗi tạm thời |

Nếu IPN hợp lệ nhưng checkout thất bại vì hold hết hạn, thiếu tồn kho, giá đổi,
nhóm ghế lệch hoặc lỗi nghiệp vụ khác: **không** phát hành Ticket, Booking chuyển
`EXCEPTION_PENDING_DECISION`, Payment ghi nhận `PAID`, ghi audit log và vẫn trả `"00"`
để VNPay dừng retry. Ngoại lệ: `BOOKING_ALREADY_PAID` trả `"02"` nếu Payment đã PAID;
`SEAT_UPDATE_CONFLICT`, lỗi DB tạm thời hoặc trường hợp không thể ghi nhận ngoại lệ trả
`"99"` để VNPay gọi lại.

### Mã lỗi tạo Payment VNPay

| HTTP | Code | Khi nào |
|---|---|---|
| 404 | `BOOKING_NOT_FOUND` | Booking không tồn tại hoặc của CUSTOMER khác |
| 404 | `SHOWTIME_NOT_FOUND` / `MOVIE_NOT_FOUND` | Không đọc được tuổi phim |
| 409 | `BOOKING_ALREADY_PAID` / `BOOKING_INVALID_STATE` | Booking không còn PENDING |
| 409 | `SEAT_HOLD_EXPIRED` | Hold hết hạn hoặc đã bị release |
| 409 | `PAYMENT_INVALID_STATE` | Payment FAILED hoặc không PENDING |
| 409 | `PAYMENT_METHOD_CONFLICT` | Booking đã có Payment khác phương thức |
| 409 | `PAYMENT_AMOUNT_MISMATCH` | Payment amount khác `bookings.total_amount` |
| 422 | `BOOKING_CHANNEL_INVALID` | Booking kênh POS |
| 422 | `AGE_CONFIRMATION_REQUIRED` | Phim hạn chế tuổi chưa xác nhận |
| 503 | `PAYMENT_CONFIGURATION_MISSING` | Thiếu cấu hình VNPay |

## Đọc một vé

```http
GET /api/v1/tickets/TK-AbCdEf123
Authorization: Bearer <staff-or-customer-jwt>
```

Trả `TicketResponse` (cùng cấu trúc trong mục "Đọc Booking và vé"). STAFF đọc được vé
của mọi Booking; CUSTOMER chỉ đọc vé thuộc Booking của mình. Không tồn tại hoặc không
thuộc quyền đọc đều trả 404 `TICKET_NOT_FOUND` để không lộ sự tồn tại.

## Soát vé tại cổng

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

Quy tắc:

- Cổng mở **60 phút trước** `showtimes.start_time` và đóng **30 phút sau**
  `showtimes.end_time` (end đã gồm 15 phút buffer dọn phòng).
- Chỉ Ticket `VALID` được soát; thành công chuyển `USED` và ghi `used_at`.
- Ticket `USED` trả 409 `TICKET_ALREADY_USED`; `CANCELLED` trả 422
  `TICKET_NOT_VALID`; ngoài khung giờ trả 422 `TICKET_OUTSIDE_SHOWTIME`.
- Phim T13/T16/T18 bắt buộc `ageDocumentChecked=true`; thiếu trả 422
  `AGE_VERIFICATION_REQUIRED`. Khi hợp lệ ghi `age_verifications` stage `GATE` với
  `ticket_id`, staff và thời điểm; không lưu số giấy tờ.
- Ticket bị khóa bằng `PESSIMISTIC_WRITE` trước khi đọc trạng thái, nên hai lần quét
  song song chỉ một lần thành công.

| HTTP | Code | Khi nào |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Thiếu `ticketCode` hoặc body sai |
| 404 | `TICKET_NOT_FOUND` | Không có vé |
| 409 | `TICKET_ALREADY_USED` | Vé đã được soát |
| 422 | `TICKET_NOT_VALID` | Vé CANCELLED hoặc không còn VALID |
| 422 | `TICKET_OUTSIDE_SHOWTIME` | Ngoài khung giờ soát vé |
| 422 | `AGE_VERIFICATION_REQUIRED` | Phim hạn chế tuổi chưa xác nhận giấy tờ |

## Lịch sử Booking của CUSTOMER

```http
GET /api/v1/me/bookings?page=0&size=20
Authorization: Bearer <customer-jwt>
```

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

`userId` luôn lấy từ JWT, không nhận từ query/body. `page` từ 0, `size` 1–100 (mặc
định 20), sort `createdAt DESC, bookingId DESC`. STAFF gọi nhận 403 `ACCESS_DENIED`.
Chi tiết `GET /me/bookings/{bookingId}` trả suất chiếu, vé, F&B, Payment; Booking của
người khác trả 404 `BOOKING_NOT_FOUND`.

## Lịch sử bán vé tại quầy

```http
GET /api/v1/pos/bookings?bookingCode=0041&date=2030-01-04&paymentMethod=CASH&page=0&size=20
Authorization: Bearer <staff-jwt>
```

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

- Chỉ `channel=POS` và Booking `PAID` xuất hiện trong danh sách; Booking ONLINE không
  bao giờ bị lộ.
- `date` là `yyyy-MM-dd` theo `Asia/Ho_Chi_Minh`, lọc trên `payments.paid_at`.
- `paymentMethod` chỉ nhận `CASH` hoặc `VIETQR`; `size` 1–100; sort
  `paidAt DESC, bookingId DESC`.
- Chi tiết `GET /pos/bookings/{bookingId}` trả showtime/movie, ghế, Ticket, F&B,
  Payment, `performedBy`; Booking ONLINE hoặc không tồn tại trả 404
  `BOOKING_NOT_FOUND`. `refundStatus` để sẵn cho Sprint F và hiện luôn `null`.

## Bảo mật

- Không log JWT, hold token, PIN, `VNPAY_HASH_SECRET` hay `vnp_SecureHash` đầy đủ.
- Giá, tổng tiền, role, userId, staffId và ageRating luôn do backend/JWT/DB quyết định.
- CUSTOMER chỉ đọc dữ liệu của mình; dữ liệu của người khác trả 404 thay vì 403 để
  không lộ sự tồn tại.
- STAFF chỉ thấy Booking channel POS qua `/pos/bookings`; POS payment từ chối Booking
  ONLINE bằng 422 `BOOKING_CHANNEL_INVALID`.
- Callback VNPay công khai nhưng chỉ tin dữ liệu đã verify HMAC-SHA512; return URL
  không bao giờ finalize.
