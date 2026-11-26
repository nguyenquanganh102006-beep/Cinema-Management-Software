# Thanh toán online VNPay — Sprint E

Base `/api/v1`, JWT Bearer Foundation. Thành công bọc `data`; lỗi dùng
`timestamp/status/code/message/details/path`. Thanh toán online chỉ dành cho
`CUSTOMER`; hai callback của VNPay là endpoint công khai, xác thực bằng chữ ký HMAC.

| Method | Path | Quyền | Thành công |
|---|---|---|---|
| POST | `/bookings/{bookingId}/payments/vnpay` | CUSTOMER | 200 `VnPayPaymentResponse` |
| GET | `/payments/vnpay/return` | Công khai | 200 `VnPayReturnResponse` (chỉ đọc trạng thái) |
| GET | `/payments/vnpay/ipn` | Công khai | 200 `VnPayIpnResponse` đúng contract VNPay |

`POST` nằm trong `CustomerPaymentController` (class-level `hasRole('CUSTOMER')`), tách
khỏi controller POS. Hai callback nằm trong `VnPayController`, không có
`@PreAuthorize`, không dựa vào JWT. Không endpoint nào nhận `price`, `totalAmount`,
`userId`, `role`, `ageRating` hay `paymentStatus` từ client.

## Cấu hình

Đọc từ biến môi trường, không hard-code secret:

| Biến | Ý nghĩa |
|---|---|
| `VNPAY_TMN_CODE` | Mã website tại VNPay |
| `VNPAY_HASH_SECRET` | Secret ký HMAC SHA512, **không log** |
| `VNPAY_PAY_URL` | Dùng đúng URL thanh toán VNPay cấp cho merchant. Tài khoản test hiện tại dùng `https://sandbox.vnpaytest.vn/paymentv2/vpcpay.html`; tài liệu Sandbox chuẩn cũng công bố `https://sandbox.vnpayment.vn/paymentv2/vpcpay.html`. |
| `VNPAY_RETURN_URL` | URL portal CUSTOMER nhận redirect sau khi thanh toán |
| `VNPAY_IPN_URL` | URL IPN đã đăng ký với VNPay; VNPay gọi trực tiếp, backend không tự gọi |

`VNPAY_RETURN_URL` là trang trong trình duyệt khách hàng; môi trường local có thể dùng
`http://localhost:3003/payments/vnpay/return`. `VNPAY_IPN_URL` là callback server-to-server,
phải truy cập công khai và khớp URL đã đăng ký tại VNPay. `localhost` không thể nhận IPN từ VNPay;
khi thử sandbox trên máy local, dùng tunnel HTTPS trỏ tới cổng backend đang chạy.

Thiếu cấu hình → `POST` trả 503 `PAYMENT_CONFIGURATION_MISSING`. IPN thiếu cấu hình
trả `RspCode` `"99"`. `VNPAY_IPN_URL` là thông tin đăng ký phía VNPay; nó không được
gắn vào URL thanh toán (VNPay dùng URL đã đăng ký trong merchant portal).

### Lấy thông tin sandbox

Đăng ký website trên [VNPay Sandbox](https://sandbox.vnpayment.vn/devreg/) và dùng
[hướng dẫn tích hợp chính thức](https://sandbox.vnpayment.vn/apis/docs/gioi-thieu/).
VNPay cấp `TMN_CODE` và `HashSecret`; đặt chúng trong `.env` ở máy chạy backend, không commit
secret vào Git. Backend trả `PAYMENT_CONFIGURATION_MISSING` nếu thiếu một trong năm biến trên.

## Tạo Payment và URL thanh toán

```http
POST /api/v1/bookings/123/payments/vnpay
Authorization: Bearer <customer-jwt>
```

Không có body. Điều kiện:

1. Booking tồn tại và `user_id` là CUSTOMER đang đăng nhập. Booking người khác trả
   404 `BOOKING_NOT_FOUND` để không lộ sự tồn tại.
2. `channel = ONLINE`, `status = PENDING`.
3. Phim T13/T16/T18 phải có `bookings.age_confirmed_at`; thiếu → 422
   `AGE_CONFIRMATION_REQUIRED`. `ageRating` đọc từ Movie của Showtime trong DB.
4. Hold còn hiệu lực. Hold online **không được gia hạn** ở bất kỳ bước nào.

Backend khóa Booking bằng `findByIdForUpdate` trước khi tạo/cập nhật Payment. Vì
`payments.booking_id` là UNIQUE, mỗi Booking chỉ có đúng một Payment:

- Chưa có Payment → tạo mới `PENDING`, `method = GATEWAY_ONLINE`,
  `amount = bookings.total_amount`, `expires_at = holdUntil`.
- Đã có Payment `PENDING` cùng phương thức → cấp `vnp_txn_ref` mới và cập nhật chính
  dòng đó, **không tạo dòng thứ hai**. URL cũ không còn hiệu lực.
- Payment `FAILED` → 409 `PAYMENT_INVALID_STATE`. Đây là trạng thái kết thúc; muốn thử
  lại khách phải giữ ghế và tạo Booking mới.
- Payment `PAID` → 409 `BOOKING_ALREADY_PAID` (Booking cũng đã PAID).
- Booking đã có Payment phương thức khác → 409 `PAYMENT_METHOD_CONFLICT`.

`vnp_txn_ref` duy nhất, sinh từ booking ID + epoch millis + hậu tố ngẫu nhiên, có kiểm
tra trùng trong DB trước khi lưu.

```json
{
  "data": {
    "paymentUrl": "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_Amount=10000000&...&vnp_SecureHash=...",
    "expiresAt": "2026-11-07T12:05:00Z"
  }
}
```

### Tham số URL

| Tham số | Giá trị |
|---|---|
| `vnp_Version` | `2.1.0` |
| `vnp_Command` | `pay` |
| `vnp_TmnCode` | `VNPAY_TMN_CODE` |
| `vnp_Amount` | `total_amount × 100` (VND, không thập phân) |
| `vnp_CurrCode` | `VND` |
| `vnp_TxnRef` | mã giao dịch duy nhất của merchant |
| `vnp_OrderInfo` | `Thanh toan ve phim <bookingCode>`, không dấu |
| `vnp_OrderType` | `other` |
| `vnp_Locale` | `vn` |
| `vnp_ReturnUrl` | `VNPAY_RETURN_URL` |
| `vnp_IpAddr` | IP request, chuẩn hóa IPv6 loopback |
| `vnp_CreateDate` | thời điểm tạo, `yyyyMMddHHmmss` tại `Asia/Ho_Chi_Minh` |
| `vnp_ExpireDate` | `holdUntil`, cùng định dạng/múi giờ; **không vượt quá hold** |

Chữ ký: lọc các tham số `vnp_`, bỏ rỗng, sắp xếp theo key, URL-encode value bằng
UTF-8, nối `key=value` bằng `&`, ký HMAC SHA512 bằng `VNPAY_HASH_SECRET`, hex
lowercase. `vnp_SecureHash` được nối vào cuối query.

## Return URL — chỉ hiển thị, không finalize

```http
GET /api/v1/payments/vnpay/return?vnp_TxnRef=...&vnp_ResponseCode=00&vnp_SecureHash=...
```

Backend verify chữ ký và `vnp_TmnCode`, tìm Payment theo `vnp_TxnRef`, rồi trả trạng
thái **hiện tại** của Payment/Booking. Endpoint này **không** gọi
`CheckoutService.finalizePaid`, **không** đổi Payment, **không** phát hành Ticket.
Khách quay lại từ VNPay có thể thấy `PENDING` trong vài giây cho tới khi IPN về.

```json
{
  "data": {
    "paymentId": 9,
    "bookingId": 123,
    "bookingCode": "BK-261107-0001",
    "vnpTxnRef": "VNP123T...",
    "vnpResponseCode": "00",
    "vnpTransactionNo": "12345678",
    "amount": 100000.00,
    "paymentStatus": "PENDING",
    "bookingStatus": "PENDING"
  }
}
```

Chữ ký sai → 400 `VNPAY_INVALID_SIGNATURE`. Thiếu `vnp_TxnRef` → 400
`VALIDATION_ERROR`. Không tìm thấy Payment → 404 `PAYMENT_NOT_FOUND`.

## IPN — nơi duy nhất được finalize

VNPay gọi `GET /api/v1/payments/vnpay/ipn` kèm query params. Endpoint công khai, không
JWT; chữ ký là cơ chế xác thực. Thứ tự xử lý:

1. **Verify chữ ký** (và `vnp_TmnCode`). Sai → `"97"`.
2. **Tìm Payment** theo `vnp_TxnRef`. Không thấy → `"01"`.
3. **Tìm Booking** của Payment; kiểm tra method `GATEWAY_ONLINE`, channel `ONLINE`.
4. **Verify amount**: `vnp_Amount` phải bằng `payments.amount × 100`. Sai → `"04"`.
5. **State/idempotency**: Payment/Booking đã `PAID` → `"02"` và không xử lý lại.
6. Chỉ finalize khi cả `vnp_ResponseCode = "00"` và `vnp_TransactionStatus = "00"`.
   VNPay yêu cầu kiểm tra cả hai mã kết quả [theo đặc tả PAY](https://sandbox.vnpayment.vn/apis/docs/thanh-toan-pay/pay.html).
7. `vnp_TransactionStatus = "01"` là giao dịch chưa hoàn tất: giữ Payment `PENDING`, trả
   `"99"` để VNPay có thể gửi lại IPN.
8. `vnp_ResponseCode = "07"`, `vnp_TransactionStatus = "07"`, `vnp_TransactionStatus = "04"`,
   hoặc status thành công nhưng response code mâu thuẫn: không phát hành vé; Payment → `PAID`,
   Booking → `EXCEPTION_PENDING_DECISION`, ghi audit và trả `"00"` để dừng retry. Các trạng thái
   này có thể liên quan đến khoản tiền đã bị trừ, bị nghi ngờ hoặc giao dịch đảo; Manager cần đối soát.
9. `vnp_TransactionStatus = "02"` là giao dịch thất bại: Payment `FAILED`, Booking vẫn `PENDING`,
   trả `"00"` để VNPay dừng retry.

Payment được khóa bằng `findByVnpTxnRefForUpdate` trong một transaction chuẩn bị riêng.
`CheckoutService.finalizePaid` chạy ở transaction riêng của nó (không lồng, không
`REQUIRES_NEW`), nhờ vậy lỗi checkout không làm hỏng transaction đã ghi nhận kết quả
cổng. Không gọi HTTP trong transaction.

### Contract trả về

| `RspCode` | `Message` | Khi nào |
|---|---|---|
| `00` | `Confirm Success` | Đã finalize, đã ghi nhận FAILED, hoặc đã đưa vào nhánh ngoại lệ |
| `01` | `Order not found` | Không có Payment theo `vnp_TxnRef`, hoặc thiếu `vnp_TxnRef` |
| `02` | `Order already confirmed` | Payment/Booking đã PAID (IPN lặp) |
| `04` | `Invalid amount` | `vnp_Amount` khác `payments.amount × 100` |
| `97` | `Invalid signature` | Sai chữ ký hoặc sai `vnp_TmnCode` |
| `99` | `Unknown error` | Thiếu cấu hình/kết quả, giao dịch chưa hoàn tất hoặc trạng thái không xác định, Booking/Payment không hợp lệ, lỗi khác |

HTTP status luôn 200 với các mã trên; VNPay đọc `RspCode` trong body.

### Nhánh ngoại lệ

IPN hợp lệ với cả `vnp_ResponseCode = "00"` và `vnp_TransactionStatus = "00"`, khi hold đã hết hạn sẽ khóa lại các ghế của
Booking. Nếu mọi ghế vẫn `AVAILABLE` hoặc chỉ còn hold đã hết hạn, checkout chiếm ghế
nguyên tử và tiếp tục phát hành vé. Nếu một ghế đã được bán hoặc đang có hold còn hiệu
lực, không phát hành vé; không lấy ghế của giao dịch khác.

Các lỗi nghiệp vụ không thể hoàn tất như ghế đã bị lấy, kho F&B không đủ, giá đổi hay
liên kết dữ liệu không hợp lệ sẽ vào nhánh exception:

- Không phát hành Ticket; không trừ kho; giữ nguyên ghế mà booking khác đang sở hữu.
- Booking → `EXCEPTION_PENDING_DECISION` (chỉ từ `PENDING`, qua `Booking.markException`).
- Payment → `PAID` và giữ `gateway_transaction_code`, ghi audit event bền vững để
  Manager xử lý.
- Đưa booking vào `GET /api/v1/reports/payment-exceptions` và hiển thị trong Manager
  dashboard; khách nhận thông báo rõ là đội hỗ trợ đang xử lý.
- Vẫn trả `"00"` để VNPay dừng retry.

Mọi `ApiException` khác từ `finalizePaid` — `PRICE_CHANGED`, `SEAT_HOLD_MISMATCH`,
`SEAT_HOLD_INVALID`, `CONCESSION_ITEM_NOT_FOUND`, `BOOKING_INVALID_STATE`, ... — cũng
vào nhánh ngoại lệ và trả `"00"`, vì tiền đã được VNPay xác nhận. Hai ngoại lệ:
`BOOKING_ALREADY_PAID` trả `"02"` nếu Payment đã PAID; `SEAT_UPDATE_CONFLICT` (xung
đột khóa tạm thời) trả `"99"` để VNPay gọi lại. Các `RuntimeException` khác (lỗi DB)
vẫn trả `"99"`.

### Idempotency và gọi song song

- Payment bị khóa khi chuẩn bị IPN; trạng thái `PAID` là terminal.
- `payments.gateway_transaction_code` (`vnp_TransactionNo`) có UNIQUE index; lần ghi
  trùng bị phát hiện trước khi finalize.
- Booking bị khóa bên trong `finalizePaid`, nên hai IPN đồng thời chỉ một bên phát
  hành Ticket. Bên còn lại nhận `BOOKING_ALREADY_PAID` và trả `"02"`.
- Kết quả test song song: đúng một `"00"`, một `"02"`, một bộ Ticket.

## Bảo mật và log

- Không log JWT, hold token, `VNPAY_HASH_SECRET`, `vnp_SecureHash` đầy đủ.
- Audit log chỉ ghi `bookingId`, `paymentId`, `txnRef`, `transactionNo`, `amount`,
  `responseCode`, `ticketCount`, `reason`.
- So sánh chữ ký constant-time, không phân biệt hoa/thường.
- `vnp_SecureHash` và `vnp_SecureHashType` bị loại khỏi dữ liệu ký/verify.

## Lỗi nghiệp vụ của `POST`

| HTTP | Code | Khi nào |
|---|---|---|
| 403 | `ACCESS_DENIED` | Không phải CUSTOMER |
| 404 | `BOOKING_NOT_FOUND` | Booking không tồn tại hoặc của người khác |
| 404 | `PAYMENT_NOT_FOUND` | Return URL không tìm thấy Payment |
| 409 | `BOOKING_ALREADY_PAID` | Booking/Payment đã PAID |
| 409 | `BOOKING_INVALID_STATE` | Booking không `PENDING` (ví dụ đã hủy/ngoại lệ) |
| 409 | `SEAT_HOLD_EXPIRED` | Hold hết hạn hoặc đã bị release |
| 409 | `PAYMENT_INVALID_STATE` | Payment `FAILED` (hoặc không `PENDING`) |
| 409 | `PAYMENT_METHOD_CONFLICT` | Booking đã có Payment phương thức khác |
| 409 | `PAYMENT_AMOUNT_MISMATCH` | `payments.amount` khác `bookings.total_amount` |
| 422 | `BOOKING_CHANNEL_INVALID` | Booking kênh POS |
| 422 | `AGE_CONFIRMATION_REQUIRED` | Phim hạn chế tuổi chưa xác nhận |
| 503 | `PAYMENT_CONFIGURATION_MISSING` | Thiếu cấu hình VNPay |

## Kiểm thử

`VnPayPaymentIntegrationTest` tự ký chữ ký bằng secret test (không gọi VNPay thật) và
kiểm tra: URL/tham số/chữ ký, retry xoay `vnp_txn_ref` trên cùng Payment, IPN thành
công phát hành đúng một Ticket, IPN lặp trả `"02"`, hai IPN song song chỉ một bộ
Ticket, Return URL không finalize, chữ ký sai `"97"`, sai số tiền `"04"`, Payment
FAILED chặn retry; IPN đến sau khi hold hết hạn vẫn phát hành vé nếu ghế còn trống,
còn ghế đã được lấy đưa Booking vào `EXCEPTION_PENDING_DECISION` (IPN lặp trả `"02"`).
Giá vé thay đổi sau khi đặt cũng vào exception. CUSTOMER khác nhận 404, STAFF nhận 403,
và thiếu xác nhận tuổi nhận 422.

## Thanh toán mô phỏng local

Profile `local` có thể bật `PAYMENT_DEMO_ENABLED=true` để hiện nút **Mô phỏng thanh toán thành công**
trong trang thanh toán CUSTOMER. Nút gọi `POST /api/v1/bookings/{bookingId}/payments/test-complete`;
backend kiểm tra chủ đơn, dùng cùng luồng finalize và phát hành Ticket trong cơ sở dữ liệu local.
`GET /api/v1/payments/test-mode` cho frontend biết cờ này đang bật hay tắt. Controller chỉ được tạo
trong profile `local`, giá trị mặc định là `false`; không dùng route này để thay callback VNPay hoặc
để kiểm thử trên môi trường production.
