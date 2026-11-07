# Thanh toán POS — Sprint E

Base `/api/v1`, JWT Bearer Foundation. Thành công bọc `data`; lỗi dùng
`timestamp/status/code/message/details/path`. Controller POS tách riêng khỏi
`BookingController` và chỉ cho role `STAFF`; CUSTOMER gọi bất kỳ endpoint nào trong
tài liệu này đều nhận 403 `ACCESS_DENIED`.

| Method | Path | Quyền | Thành công |
|---|---|---|---|
| POST | `/bookings/{bookingId}/payments/cash` | STAFF | 200 `CashPaymentResponse` |
| POST | `/bookings/{bookingId}/payments/vietqr` | STAFF | 200 `VietQrPaymentResponse` (PENDING) |
| POST | `/bookings/{bookingId}/payments/vietqr/confirm` | STAFF | 200 `VietQrConfirmResponse` (PAID) |

Không endpoint nào nhận `amount`, `totalAmount`, `price`, `role`, `userId`, `staffId`,
`ageRating` hay `paymentStatus` từ client. Backend đọc Booking, Showtime, Movie và
tính tiền từ dữ liệu trong DB.

## Quyết định thiết kế

### Booking kênh ONLINE

Chọn **422 `BOOKING_CHANNEL_INVALID`**, không phải 404. Booking tồn tại và STAFF có
quyền gọi endpoint, nhưng endpoint này chỉ dành cho Booking POS; 422 diễn tả đúng
"đúng tài nguyên, sai điều kiện nghiệp vụ". Booking ONLINE phải thanh toán qua luồng
online/VNPay, không dùng các endpoint tại quầy.

### STAFF khác có được thao tác Booking POS không?

**Có.** Bất kỳ STAFF đang đăng nhập được thanh toán một Booking POS, không bắt buộc
`bookings.staff_id` phải trùng người gọi. Lý do: quầy có thể đổi ca hoặc nhân viên
tạo Booking phải rời quầy; chặn theo người tạo sẽ làm kẹt giao dịch. Người thực sự
bấm xác nhận được ghi vào `payments.confirmed_by_staff_id` (và `age_verifications`
khi kiểm tra tuổi).

Điều này **không** thay đổi quy tắc đọc Booking hiện có: `GET /bookings/{id}` vẫn chỉ
cho STAFF tạo Booking đó xem. Nếu sau này cần chặt hơn, có thể thêm kiểm tra
`booking.staff_id == principal.userId()` mà không đổi schema.

### Thứ tự transaction

Cả ba luồng chạy trong một transaction `READ_COMMITTED`:

1. `bookings.findByIdForUpdate(bookingId)` **trước tiên** — khóa Booking trong cùng
   transaction.
2. Kiểm tra channel `POS`, status `PENDING`, tuổi (nếu phim T13/T16/T18).
3. Tạo hoặc dùng lại Payment `PENDING` với `amount = bookings.total_amount`.
4. Gọi `CheckoutService.finalizePaid(...)` để khóa ghế, trừ kho F&B, ghi SALE, phát
   hành Ticket và chuyển Booking sang `PAID`.

Vì Booking bị khóa trước khi tạo Payment, hai request cash đồng thời được tuần tự
hóa. Request thứ hai thấy Booking đã `PAID` và nhận 409 `BOOKING_ALREADY_PAID`, không
rơi vào `UNIQUE(payments.booking_id)` rồi bị map thành 409 `RESOURCE_CONFLICT` chung.
Bất kỳ bước nào lỗi đều rollback toàn bộ: không Payment, không Ticket, không trừ kho,
ghế vẫn `HOLDING`.

HOLD không được tự động gia hạn trong các endpoint này. Nếu hold đã hết hạn, checkout
trả 409 `SEAT_HOLD_EXPIRED`. Chỉ STAFF được gia hạn hold và việc gia hạn vẫn gắn với
owner/phiên đã tạo hold (quy tắc Sprint D), tối đa một lần sau phút thứ tư.

## Thanh toán tiền mặt

```http
POST /api/v1/bookings/123/payments/cash
Authorization: Bearer <staff-jwt>
Content-Type: application/json

{
  "cashReceived": 500000.00,
  "ageDocumentChecked": true
}
```

- `cashReceived` bắt buộc, không âm, tối đa 2 chữ số thập phân.
- `cashReceived < bookings.total_amount` → 422 `PAYMENT_CASH_INSUFFICIENT`.
- `cashReceived >= total_amount` → ghi `payments.cash_received`, `payments.amount` vẫn
  bằng `total_amount`, và trả `change = cashReceived - total_amount`.
- `ageDocumentChecked` bắt buộc `true` với phim T13/T16/T18; bỏ qua với phim P.
- `payments.confirmed_by_staff_id` lưu STAFF đang đăng nhập.

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
      },
      {
        "ticketId": 902,
        "ticketCode": "TK-...",
        "showtimeSeatId": 502,
        "price": 90000.00,
        "status": "VALID",
        "issuedAt": "2030-01-04T10:05:00Z"
      }
    ]
  }
}
```

Nếu Booking đã có Payment `PENDING` cùng method `CASH`, endpoint dùng lại Payment đó
(cập nhật `cash_received`, `confirmed_by_staff_id`) thay vì tạo dòng thứ hai. Payment
cùng `booking_id` nhưng khác method → 409 `PAYMENT_METHOD_CONFLICT`.

## VietQR

### Cấu hình

Thêm vào `.env` (xem `.env.example`):

```env
CINEMA_BANK_ACCOUNT=0123456789
CINEMA_BANK_NAME=Vietcombank
```

`application.yml` đọc qua `app.payments.vietqr.bank-account` và
`app.payments.vietqr.bank-name`. Thiếu một trong hai → 503
`PAYMENT_CONFIGURATION_MISSING`; không tạo Payment.

### Tạo Payment PENDING

```http
POST /api/v1/bookings/123/payments/vietqr
Authorization: Bearer <staff-jwt>
Content-Type: application/json

{"ageDocumentChecked": true}
```

- Tạo Payment method `VIETQR`, status `PENDING`, `amount = bookings.total_amount`.
- Với phim T13/T16/T18, ghi một dòng `age_verifications` stage `POS` nếu Booking chưa
  có; gọi lại không tạo trùng.
- Gọi lại khi đã có Payment `VIETQR` `PENDING` là idempotent: trả cùng `paymentId`,
  cùng số tiền, không tạo Payment mới.
- Payment `PENDING` khác method → 409 `PAYMENT_METHOD_CONFLICT`.
- Payment `PAID`/`FAILED` → 409 `PAYMENT_INVALID_STATE` (Booking status được kiểm tra
  trước, nên Booking đã PAID trả `BOOKING_ALREADY_PAID`).

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

`qrPayload` là URI nội bộ để portal STAFF render thành QR:

```text
vietqr://transfer?bankName=<url-encoded>&account=<url-encoded>&amount=<plain>&content=<url-encoded>
```

Đây **chưa phải** chuỗi EMVCo/VietQR chuẩn ngân hàng vì cấu hình hiện tại không có
bank BIN. Khi bổ sung BIN, chỉ cần thay phần build payload; response và luồng xác nhận
giữ nguyên.

### Xác nhận đã nhận chuyển khoản

```http
POST /api/v1/bookings/123/payments/vietqr/confirm
Authorization: Bearer <staff-jwt>
```

Không có body. Endpoint kiểm tra:

1. Booking POS, `PENDING`, chưa PAID.
2. Phim hạn chế tuổi phải có `age_verifications` stage `POS` cho Booking; thiếu → 422
   `AGE_VERIFICATION_REQUIRED`.
3. Phải có Payment `VIETQR` `PENDING`, đúng số tiền; thiếu → 404 `PAYMENT_NOT_FOUND`,
   sai method → 409 `PAYMENT_METHOD_CONFLICT`, sai trạng thái → 409
   `PAYMENT_INVALID_STATE`.
4. Lưu `confirmed_by_staff_id`, gọi `CheckoutService.finalizePaid(...)`; `paid_at` là
   thời điểm xác nhận/thanh toán.

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

Gọi confirm lần thứ hai: Booking đã `PAID` → 409 `BOOKING_ALREADY_PAID`, không phát
hành thêm Ticket. Đây là endpoint STAFF xác nhận cho Sprint E, **không** phải callback
ngân hàng. Khi tích hợp ngân hàng thật, chỉ finalize từ webhook/IPN đã xác minh chữ ký;
không finalize từ return URL của client.

## Kiểm soát tuổi

- `ageRating` luôn lấy từ `movies` của `showtimes` trong DB, không lấy từ request.
- Phim `P`: `ageDocumentChecked` có thể bỏ qua hoặc `false`; không ghi
  `age_verifications`.
- Phim `T13/T16/T18`:
  - Cash: thiếu `ageDocumentChecked=true` → 422 `AGE_VERIFICATION_REQUIRED`; hợp lệ
    thì ghi `age_verifications` stage `POS` với `booking_id`, `verified_by_staff_id`,
    `verified_at`.
  - VietQR tạo Payment: ghi stage `POS` nếu chưa có.
  - VietQR confirm: bắt buộc đã có stage `POS`; không tạo thêm bản ghi.
- Không lưu số giấy tờ, ảnh giấy tờ hay PIN.

## Audit log

Mỗi endpoint ghi một dòng audit qua logger `AUDIT_PAYMENT`:

- Thành công: `action` (`CASH_PAYMENT`, `VIETQR_CREATED`, `VIETQR_CONFIRMED`),
  `result=PAID|PENDING`, `bookingId`, `bookingCode`, `staffId`, `paymentId`, `amount`,
  `cashReceived`/`change`/`tickets` khi có.
- Bị từ chối: cùng `action`, `result=REJECTED`, `bookingId`, `staffId`, `code`.

Không log JWT, hold token, PIN, VNPay secret hay toàn bộ request body.

## Lỗi nghiệp vụ

| HTTP | Code | Khi nào |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Thiếu/sai `cashReceived`, body sai, JSON lạ |
| 403 | `ACCESS_DENIED` | CUSTOMER gọi endpoint STAFF |
| 404 | `BOOKING_NOT_FOUND` | Booking không tồn tại |
| 404 | `PAYMENT_NOT_FOUND` | Confirm nhưng chưa tạo Payment VietQR |
| 404 | `SHOWTIME_NOT_FOUND` / `MOVIE_NOT_FOUND` | Không đọc được tuổi phim |
| 409 | `BOOKING_ALREADY_PAID` | Booking đã thanh toán (kể cả confirm lần hai) |
| 409 | `BOOKING_INVALID_STATE` | Booking CANCELLED/EXCEPTION_PENDING_DECISION |
| 409 | `PAYMENT_METHOD_CONFLICT` | Booking đã có Payment method khác |
| 409 | `PAYMENT_INVALID_STATE` | Payment PAID/FAILED, không còn PENDING |
| 409 | `PAYMENT_AMOUNT_MISMATCH` | Payment amount khác `bookings.total_amount` |
| 409 | `SEAT_HOLD_EXPIRED` | Hold hết hạn trước khi checkout |
| 409 | `PRICE_CHANGED` | Giá tính lại khác giá đã báo |
| 409 | `INSUFFICIENT_STOCK` / `OUT_OF_STOCK` | Kho F&B không đủ lúc commit |
| 422 | `BOOKING_CHANNEL_INVALID` | Booking kênh ONLINE |
| 422 | `AGE_VERIFICATION_REQUIRED` | Phim hạn chế tuổi chưa xác nhận giấy tờ |
| 422 | `PAYMENT_CASH_INSUFFICIENT` | `cashReceived < total_amount` |
| 503 | `PAYMENT_CONFIGURATION_MISSING` | Thiếu `CINEMA_BANK_ACCOUNT`/`CINEMA_BANK_NAME` |

## Ghi chú nội bộ

`Payment` chỉ cho chuyển trạng thái từ `PENDING`:

- `recordCash(received)` — chỉ method `CASH`, `received >= amount`.
- `confirmByStaff(staffId)` — ghi người xác nhận.
- `attachGateway(txnRef, expiresAt)` / `recordGatewayResult(code, signature)` — chỉ
  method `GATEWAY_ONLINE`, phục vụ VNPay ở bước sau.
- `useIdempotencyKey(key)` — khóa idempotency của provider.
- `markPaid(now)` / `markFailed()` — chỉ từ `PENDING`; service phải kiểm tra trạng thái
  trước khi gọi.

Không có setter chung cho từng cột; mọi thay đổi đi qua các method trên để giữ bất
biến trạng thái.
