# API Refund và Reports

Base `/api/v1`, Bearer JWT, response `{ "data": ... }`. Error format theo
ApiErrorResponse hiện tại. Không gửi PIN/overrideToken qua URL hoặc log request body.

## Refund

`GET /refunds/eligibility?bookingCode=...` hoặc `?ticketCode=...` (đúng một khóa),
STAFF/MANAGER. Trả bookingId/code/channel, showtimeId, startsAt, serverTime và
tickets: ticketId/code, seatId/row/seatNumber, status, priceSnapshot, eligible,
reasonCode, refundableAmount. Tra được cả đơn ONLINE, không làm lộ profile/secret.

`POST /manager-overrides`, STAFF đang đăng nhập, xác thực Manager riêng:

```json
{
  "username": "manager01",
  "pin": "<Manager PIN>",
  "scope": "REFUND_APPROVAL",
  "bookingId": 1001,
  "ticketIds": [101, 102],
  "reason": "Khách yêu cầu hoàn vé",
  "cashConfirmed": true
}
```

PIN gồm 6–12 chữ số như cấu hình Manager hiện tại. Response: overrideToken, expiresAt, approvedRefundAmount. TTL 180 giây, ràng buộc
đúng phiên Staff, Booking, vé, lý do, số tiền snapshot. cashConfirmed là Manager
xác nhận đã giao tiền tại POS. Không nhận amount hoặc managerId do client quyết định.

`POST /refunds`, STAFF:

```json
{
  "bookingId": 1001,
  "ticketIds": [101, 102],
  "reason": "Khách yêu cầu hoàn vé",
  "overrideToken": "<token từ bước duyệt>",
  "cashConfirmed": true
}
```

Response: refundId, bookingId, refundAmount, ticketIds, status=COMPLETED,
cashConfirmedAt, refundStatus=NONE/PARTIAL/FULL. Cùng token+payload trả cùng refundId
sau commit; không tạo lần hoàn mới. Payload/phiên khác bị chặn. IDs trùng hoặc
khác Booking bị từ chối. Không hoàn USED/CANCELLED, dưới 30 phút hoặc chưa PAID.
Đúng 30 phút vẫn hợp lệ. F&B giữ nguyên; CASH/VIETQR/GATEWAY_ONLINE đều hoàn cash.

| HTTP | Error code / trường hợp |
|---|---|
| 400 | VALIDATION_ERROR: IDs/input/reason/cash confirmation/scope không hợp lệ |
| 401 | AUTH_TOKEN_INVALID / AUTH_TOKEN_EXPIRED |
| 403 | ACCESS_DENIED, MANAGER_PIN_INVALID, MANAGER_OVERRIDE_REQUIRED, MANAGER_OVERRIDE_EXPIRED, MANAGER_OVERRIDE_MISMATCH |
| 404 | BOOKING_NOT_FOUND |
| 409 | REFUND_NOT_ELIGIBLE (inventory lệch), RESOURCE_CONFLICT (DB constraint), SEAT_UPDATE_CONFLICT (lock conflict) |
| 422 | REFUND_NOT_ELIGIBLE, REFUND_DEADLINE_PASSED, TICKET_ALREADY_CANCELLED, TICKET_NOT_REFUNDABLE |

`/pos/bookings` vẫn chỉ trả POS; summary refundStatus nay là NONE/PARTIAL/FULL,
thay cho null của Sprint E. Chi tiết vé vẫn có status hiện tại.

## Reports

Tất cả chỉ MANAGER. from/to bắt buộc ISO YYYY-MM-DD (inclusive), from<=to;
timezone Asia/Ho_Chi_Minh. Sai/missing ngày trả 400, role sai 403. Trống trả
0 hoặc danh sách rỗng; tiền BigDecimal, tỷ lệ làm tròn 2 chữ số.

Mỗi report theo kỳ có period: from, to, timezone, basis, asOf.

| GET endpoint | Response fields |
|---|---|
| `/reports/revenue?from=2050-01-01&to=2050-01-31` | totalRevenue (gross), refundAmount, netRevenue, exceptionPaidAmount |
| `/reports/revenue-breakdown` | ticketRevenue (gross), concessionRevenue, ticketRefundAmount, netTicketRevenue, netRevenue |
| `/reports/revenue-by-channel` | posRevenue/onlineRevenue (net); channels: channel/grossRevenue/refundAmount/netRevenue |
| `/reports/ticket-count` | ticketCount (VALID+USED), grossTicketCount, cancelledTicketCount |
| `/reports/occupancy` | seatsSold, totalSeats, occupancyRate; showtimes có showtimeId/movieId/startsAt và cùng số liệu |
| `/reports/inventory-alerts` | itemId, itemName, stockQuantity, lowStockThreshold, status; không có from/to |
| `/reports/movies/ranking?from=...&to=...&direction=DESC&limit=5` | direction; movies: movieId/title/ticketCount/totalRevenue (gross)/refundAmount/revenue (net) |

Revenue/count/ranking có basis=PAYMENT_COHORT: chọn payment PAID của Booking PAID
theo paid_at, trừ refund đã ghi đến asOf cho các đơn đó. Không phải cash-flow theo
ngày chi refund. Vé CANCELLED giữ giá trong gross rồi trừ refund một lần. Các phần
Ticket/F&B và POS/Online đối soát với netRevenue. F&B của Booking quy cho phim của
Booking; không nhân tiền theo số vé. Phim không có giao dịch trong kỳ không vào
ranking. direction ASC lấy doanh thu thấp nhất; DESC cao nhất; limit 1..100.

Occupancy có basis=SHOWTIME_START, tính trạng thái hiện tại của inventory suất
chiếu bắt đầu trong kỳ; BOOKED / toàn bộ inventory, không tính HOLDING. Tổng tỷ lệ
lấy tổng BOOKED / tổng ghế. Count/state và cohort cũ có thể thay đổi sau refund.
Stock alerts là tồn kho hiện tại, chỉ món có inventory thật, combo không có dòng
stock riêng. EXCEPTION_PENDING_DECISION đã thu tiền được báo riêng, chưa phải doanh
thu hàng hóa đã giao.

Chi tiết chính sách tại [decision F](../decisions/006-refund-reports.md).
