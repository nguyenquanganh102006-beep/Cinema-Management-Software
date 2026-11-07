# Sprint E — Checkout, Payment và Ticket

Ngày: 07/11/2026, Asia/Ho_Chi_Minh. Phạm vi: backend Sprint E — báo giá, tạo Booking,
thanh toán POS/VNPay, phát hành Ticket, đọc vé, soát vé tại cổng, lịch sử CUSTOMER và
lịch sử POS. Hợp đồng API nằm trong [checkout-ticket](../api/checkout-ticket.md), các
ca kiểm chứng trong [kiểm thử Sprint E](../testing/sprint-e.md).

Tài liệu này bổ sung cho `001`–`004`. Các quyết định A/B/C/D vẫn có hiệu lực; khi có
khác biệt, quyết định dưới đây chỉ áp dụng cho luồng E và được ghi rõ là **bổ sung**
hay **thay thế**.

## Tương thích với quyết định A/B/C/D

| Quyết định | Sprint E giữ nguyên | Điểm E chốt thêm |
|---|---|---|
| [001 — Foundation](001-foundation.md) | Java 21, năm package, constructor injection, JWT/RBAC, `Clock`, `ApiResponse`/`ApiErrorResponse`, UTC/TIMESTAMPTZ, Flyway + Hibernate validate | Giá và tổng tiền do backend tính; không tin `price`/`totalAmount`/`role`/`userId`/`ageRating` từ client. Không log JWT, hold token, PIN hay secret thanh toán. |
| [002 — Master Data](002-master-data.md) | Movie/Auditorium/Seat/Concession và API quản trị hiện có | `ageRating` luôn đọc từ Movie của Showtime trong DB; F&B chỉ đọc giá/tồn kho, không sửa catalog. |
| [003 — Showtime](003-showtime.md) | Inventory `showtime_seats`, `@Version`, thứ tự khóa và READ_COMMITTED | E khóa Booking trước, rồi gọi `CheckoutSeatService` trong cùng transaction; không preload Entity inventory trước khi lấy lock, không `REQUIRES_NEW`, không gọi HTTP trong transaction. |
| [004 — Seat holding](004-seat-holding.md) | Owner `role:userId:tokenId`, hold 300 giây, Sweetbox hai ghế, `markBooked` MANDATORY | Một hold tạo đúng một Booking; Booking lưu planned seat IDs trong `booking_seats`; Sweetbox phát hành hai Ticket độc lập. |

## Bảng quyết định

| Mã | Quyết định | Lý do và hệ quả |
|---|---|---|
| **E-01 — `GATEWAY_ONLINE` là VNPay** | Giữ nguyên tên enum DB `GATEWAY_ONLINE` cho phương thức thanh toán online; không đổi thành `VNPAY`. Mỗi Booking có tối đa một Payment (`payments.booking_id` UNIQUE). | Đổi enum sẽ phải sửa migration/seed/DB đã bàn giao. `GATEWAY_ONLINE` đủ tổng quát nếu sau này thêm cổng khác; VNPay là implementation hiện tại. Retry VNPay cấp `vnp_txn_ref` mới trên cùng dòng Payment, không tạo dòng thứ hai. |
| **E-02 — Một hold, một Booking** | `POST /bookings` chỉ tạo một Booking cho mỗi `holdToken`. Retry cùng token trả **409 `BOOKING_ALREADY_EXISTS`**, không trả lại Booking cũ. Kiểm tra tồn tại chạy trước khi khóa ghế và chạy lại sau khi đã khóa. | Trả lại Booking cũ dễ khiến client tưởng là giao dịch mới. Kiểm tra hai lần dưới khóa ghế tuần tự hóa hai request đồng thời, tránh hai Booking cùng token. Client dùng `GET /bookings/{id}` hoặc lịch sử để xem lại. |
| **E-03 — Giá chốt lúc tạo, tính lại lúc checkout** | Lúc tạo Booking, backend tính từng ghế và lưu `booking_seats.quoted_price`, tổng vào `bookings.total_amount`. Lúc checkout tính lại từ rule hiện hành; nếu tổng khác `bookings.total_amount` trả **409 `PRICE_CHANGED`**, không tự đổi số tiền. `tickets.price_snapshot` bằng giá tính lúc checkout. | Khách đã được báo giá; với VNPay số tiền đã gửi sang cổng. Tự đổi tiền sẽ làm lệch đối soát. F&B lưu `unit_price_snapshot` theo cùng nguyên tắc. |
| **E-04 — Checkout là một transaction** | Một transaction READ_COMMITTED: khóa Booking → `CheckoutSeatService.markBooked` → tính lại giá vé → khóa và re-check stock F&B theo `itemId` tăng dần → trừ stock + ghi `SALE` → Payment `PAID` → Ticket `VALID` (kèm `price_snapshot`) → lưu snapshot F&B → Booking `PAID`. Lỗi bất kỳ bước nào rollback toàn bộ. | Đảm bảo không có tiền mà không có vé, không có vé mà ghế chưa BOOKED, và không trừ kho khi transaction thất bại. Không giữ lock ghế rồi chờ lock không liên quan. |
| **E-05 — F&B không reserve** | Khi thêm F&B vào Booking chỉ kiểm tra item tồn tại, đang bán, số lượng dương, không trùng `itemId`. Không trừ kho, không giữ hàng. Chỉ re-check và trừ ở checkout. | Tránh giữ tồn kho cho các Booking PENDING bị bỏ. Hệ quả: giá/stock có thể đổi giữa lúc báo giá và checkout; khi đó checkout trả `PRICE_CHANGED`, `OUT_OF_STOCK` hoặc `INSUFFICIENT_STOCK`. |
| **E-06 — Thanh toán POS** | Chỉ STAFF. Phương thức `CASH` và `VIETQR`. `payments.amount = bookings.total_amount`; `cashReceived` chỉ dùng tính tiền thừa. Bất kỳ STAFF nào cũng thanh toán được Booking POS; người xác nhận lưu ở `payments.confirmed_by_staff_id`. Phim T13/T16/T18 bắt buộc `ageDocumentChecked=true` và ghi `age_verifications` stage `POS`. | Quầy có thể đổi ca; chặn theo `bookings.staff_id` sẽ làm kẹt giao dịch. Không nhận số tiền/giá từ client. VietQR confirm là thao tác STAFF xác nhận đã nhận tiền, chưa phải webhook ngân hàng. |
| **E-07 — VNPay chỉ finalize từ IPN** | `POST .../vnpay` chỉ tạo URL; Return URL chỉ đọc trạng thái. Chỉ IPN đã verify chữ ký, `vnp_TmnCode`, `vnp_TxnRef`, số tiền và idempotency mới gọi checkout. IPN thành công nhưng hold hết hạn/stock không đủ/lỗi checkout khác: không phát hành Ticket, Booking → `EXCEPTION_PENDING_DECISION`, Payment ghi `PAID`, audit log, trả `"00"`. `BOOKING_ALREADY_PAID` → `"02"` nếu Payment đã PAID; `SEAT_UPDATE_CONFLICT`/lỗi tạm thời → `"99"`. | Return URL do trình duyệt gọi, có thể bị giả mạo hoặc đến trước IPN. Khi VNPay đã xác nhận tiền, trả `"99"` sẽ khiến VNPay retry rồi bỏ, khách mất tiền mà không có bản ghi đối soát. Trả `"00"` để dừng retry và chuyển sang xử lý thủ công là hành vi an toàn hơn. **Điểm này là đề xuất, cần nhóm xác nhận tại mục 29.5/29.6 của plan.** |
| **E-08 — Phát hành Ticket** | Chỉ Payment `PAID` mới phát hành Ticket. Mỗi ghế vật lý một Ticket `VALID`; Sweetbox hai ghế là hai Ticket độc lập. Ticket lưu `price_snapshot`; unique index `uq_active_ticket_showtime_seat` bảo vệ không có hai Ticket active cho cùng một ghế/suất. | Vé là chứng từ riêng cho từng ghế, kể cả Sweetbox. Index DB là lớp bảo vệ cuối cùng nếu có writer bỏ qua service. |
| **E-09 — Quy tắc soát vé** | Cổng mở **60 phút trước** `showtimes.start_time`, đóng **30 phút sau** `showtimes.end_time` (end đã gồm 15 phút buffer dọn phòng). Chỉ `VALID → USED`, ghi `used_at`; `USED` trả 409 `TICKET_ALREADY_USED`; `CANCELLED` trả 422 `TICKET_NOT_VALID`; ngoài khung trả 422 `TICKET_OUTSIDE_SHOWTIME`. Phim T13/T16/T18 bắt buộc `ageDocumentChecked=true`, ghi `age_verifications` stage `GATE`. | Khung 60/30 đủ rộng cho khách vào sớm và xử lý khách ra muộn nhưng vẫn chặn soát vé sai suất. Khóa Ticket bằng `PESSIMISTIC_WRITE` trước khi đọc trạng thái để hai lần quét song song chỉ một lần thành công. |
| **E-10 — Cô lập dữ liệu và role** | CUSTOMER chỉ đọc Booking/Ticket của chính mình; dữ liệu người khác trả 404 thay vì 403. STAFF đọc được vé/Booking POS theo quyền hiện có nhưng `/pos/bookings` chỉ trả channel POS. POS payment từ chối Booking ONLINE bằng 422 `BOOKING_CHANNEL_INVALID`; VNPay payment từ chối Booking POS cùng mã. | 404 không làm lộ sự tồn tại của dữ liệu người khác. Tách controller theo role để không trộn `hasRole('STAFF')` và `hasRole('CUSTOMER')` trong một class. |
| **E-11 — Thời gian và log** | Mọi thời điểm lấy từ bean `Clock`, lưu `TIMESTAMPTZ`; ngày nghiệp vụ tính theo `Asia/Ho_Chi_Minh`. Audit log chỉ ghi ID, mã Booking/Ticket, số tiền, trạng thái, mã lỗi; không ghi JWT, hold token, PIN, `VNPAY_HASH_SECRET`, `vnp_SecureHash` đầy đủ hay toàn bộ request body. | Đồng nhất với A–D, tránh log credential và dữ liệu nhạy cảm. |
| **E-12 — Lịch sử chỉ đọc** | `/me/bookings` và `/pos/bookings` đọc từ `bookings`/`payments`/`tickets`/`booking_seats`/`booking_concessions`, không tạo bảng mới. `refundStatus` để sẵn nhưng luôn `null` cho tới Sprint F. | Sprint E không có nghiệp vụ hoàn tiền; thêm field sẵn để không phá contract khi F bổ sung. |

## Vòng đời và bất biến

```text
Booking: PENDING → PAID
              └─→ EXCEPTION_PENDING_DECISION   (chỉ từ IPN VNPay đã nhận tiền)
              └─→ CANCELLED                    (ngoài phạm vi E)

Payment: PENDING → PAID
              └─→ FAILED                       (VNPay trả mã lỗi)

Ticket:  VALID → USED
              └─→ CANCELLED                    (ngoài phạm vi E)
```

- `payments.booking_id` UNIQUE: một Booking chỉ có một Payment.
- `payments.amount` luôn bằng `bookings.total_amount`; không lưu số tiền khách đưa vào
  `amount`.
- `tickets.price_snapshot` là giá tại checkout; F&B lưu `unit_price_snapshot`.
- Booking `PAID` là terminal trong E; không endpoint E nào sửa tiếp.
- `EXCEPTION_PENDING_DECISION` là terminal trong E; chưa có API giải quyết.

## Điểm còn mở cho Sprint F

Các mục sau **không** được triển khai trong E và cần quyết định ở F:

1. **Hoàn tiền (refund)**: bảng/trạng thái refund, ai được tạo, hoàn toàn phần hay một
   phần, hoàn Sweetbox theo vé hay theo cặp, đối soát VNPay, cập nhật `refundStatus`
   trong POS history. Hiện `refundStatus` luôn `null`.
2. **Giải quyết `EXCEPTION_PENDING_DECISION`**: quy trình đổi suất/hoàn tiền cho khách
   khi VNPay đã thu tiền nhưng hold hết hạn hoặc stock không đủ; ai phê duyệt, có ghi
   `age_verifications`/audit riêng, có mở lại Booking hay không. Cần nhóm xác nhận
   chính sách tại mục 29.5/29.6 của plan.
3. **Hủy Booking PENDING và giải phóng hold**: E chưa có endpoint hủy Booking; khách
   bỏ ngang để hold hết hạn theo Sprint D. Nếu F cần hủy chủ động, phải khóa Booking và
   ghế theo đúng thứ tự.
4. **Webhook ngân hàng thật cho VietQR**: hiện confirm là STAFF bấm xác nhận; chưa có
   callback/IPN ngân hàng, chưa có mã giao dịch ngân hàng để đối soát.
5. **Payment FAILED và retry**: VNPay `FAILED` là terminal, khách phải tạo Booking mới.
   F cần quyết định có cho retry trên cùng Booking hay không.
6. **Báo cáo/đối soát doanh thu**: E chỉ có lịch sử POS và lịch sử CUSTOMER; chưa có
   báo cáo doanh thu, đối soát VNPay, xuất file.
7. **Frontend**: các trang checkout/POS/ticket trong ba portal vẫn là placeholder;
   Sprint E chỉ hoàn thiện backend API.
8. **POS history nâng cao**: chưa có lọc theo staff/auditorium/movie, chưa có tổng
   doanh thu theo kỳ, chưa có phân quyền Manager xem toàn bộ.
9. **Chính sách vé CANCELLED/USED**: E có enum và gate xử lý `CANCELLED`, nhưng chưa có
   API hủy/đổi vé; F cần chốt ai được hủy và hoàn tiền tương ứng.

## Giới hạn kiểm chứng

- Test dùng PostgreSQL thật/Testcontainers, MockMvc và JWT thật; không gọi VNPay
  sandbox hay ngân hàng thật. Chữ ký VNPay được ký bằng secret test.
- Chưa nghiệm thu giao diện checkout/POS/ticket vì ba portal vẫn là placeholder.
- Chưa chạy đối soát trên dữ liệu phát triển/production; các kết luận chỉ áp dụng cho
  database test của bộ test.
- E thêm `V6__booking_checkout_support.sql` và `V7__ticket_gate_check.sql` (thêm
  `tickets.used_at`); V1–V5 và `pom.xml` không bị sửa.
