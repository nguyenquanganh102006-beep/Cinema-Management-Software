# Backend walkthrough

Đối chiếu source ngày 23/11/2026. Source chính ở [`backend/src/main/java/com/cinema/cms`](../../backend/src/main/java/com/cinema/cms); API chi tiết nằm tại [`docs/api`](../api).

## 1. Các lớp và một request

| Lớp | Input | Trách nhiệm | Output |
|---|---|---|---|
| `SecurityConfig`, `JwtAuthenticationFilter` | Authorization Bearer, path/method | Xác thực JWT, phiên đã thu hồi, trạng thái/securityVersion user; kiểm tra role | `SessionPrincipal` hoặc 401/403 |
| `controller/*Controller` | Path/query/body và principal | Parse HTTP, validate DTO, gọi service | HTTP status và envelope DTO |
| `dto/*Request` | JSON từ client | Giới hạn field/type/length/enum; validation ở boundary | Request hợp lệ hoặc lỗi validation |
| `service/*Service` | Request đã parse, principal | Quyền theo tài nguyên, trạng thái, business rules và transaction | Response DTO hoặc `ApiException` |
| `repository/*Repository` | ID/filter/lock criteria | JPA/SQL, paging, row lock, aggregate | Entity/projection/page |
| `entity/*` | Dữ liệu domain | Mapping và chuyển trạng thái | Entity lưu trong PostgreSQL |
| `ErrorResponses` | Exception | Chuẩn hóa lỗi API | `{code, message, details}` khi áp dụng |

Flyway quản lý schema qua V1–V8, Hibernate validate. Không sửa migration đã chia sẻ hoặc restore backup để chạy test. Integration tests nhận `DB_TEST_*` hoặc khởi tạo PostgreSQL qua Testcontainers.

## 2. Auth và quản lý Staff

[AuthService](../../backend/src/main/java/com/cinema/cms/service/AuthService.java) nhận RegisterRequest/LoginRequest. Register tạo CUSTOMER và hash password; login chuẩn hóa identity rồi kiểm password và trạng thái. Output login là accessToken, expiresIn, user response không chứa hash. Logout thu hồi tokenId đến expiresAt qua `TokenRevocationService`.

[AuthTransferService](../../backend/src/main/java/com/cinema/cms/service/AuthTransferService.java) chuyển phiên sang portal đúng role bằng mã dùng một lần. Không truyền password trong URL. [JwtService](../../backend/src/main/java/com/cinema/cms/service/JwtService.java) và filter kiểm tra securityVersion để reset/ngừng hoạt động có thể vô hiệu hóa token cũ.

[StaffService](../../backend/src/main/java/com/cinema/cms/service/StaffService.java):

| Hàm | Input | Logic | Output |
|---|---|---|---|
| `list` | keyword/status/page/size | Chỉ STAFF, keyword escape LIKE, sort fullName/id | `PageResponse<StaffResponse>` |
| `create` | username/fullName/email/phone/password | Kiểm trùng, hash, ép role STAFF | Profile ACTIVE |
| `update` | id, fullName/email/phone | Khóa dòng Staff, kiểm trùng, thay profile | Profile cập nhật |
| `changeStatus` | id, `{active}` | Khóa cùng dòng, ACTIVE/INACTIVE; tăng securityVersion khi ngừng hoạt động | Trạng thái thật |
| `resetPassword` | id, `{newPassword}` | Khóa dòng, hash password và tăng securityVersion | 204, không echo password |

Các thao tác ghi Staff dùng cùng khóa `UserRepository.lockByIdAndRole`. Điều này ngăn hai reset đọc cùng version và ngăn update profile/status ghi đè password/version từ entity cũ. Không mở rộng quyền target sang MANAGER/CUSTOMER.

## 3. Master data và lịch chiếu

| Service | Input | Logic/output |
|---|---|---|
| `MovieService` | Metadata phim, active, bộ lọc | CRUD phim và trang danh sách; ràng buộc thời lượng/tuổi/ngày theo DTO |
| `AuditoriumService` | Tên/loại phòng, totalSeats, active | Quản lý phòng; chưa phải tạo tự động toàn bộ ghế vật lý |
| `SeatService` | auditoriumId, hàng/số/type/active | Thêm/sửa ghế vật lý; layout guard bảo vệ phòng đã có lịch |
| `SweetboxService` | Hai seatId hoặc seatId để unpair | Kiểm cặp cùng phòng/hàng/liền kề, không dùng lại ghế đã ghép |
| `ShowtimeService` | movieId/auditoriumId/startTime | Kiểm tài nguyên và overlap; tạo suất kèm inventory ghế |
| `ShowtimeTimeService` | Start và duration | Tính endTime gồm buffer; server sở hữu thời gian kết thúc |
| `PricingService` | showtimeId, seatIds | Áp base/phòng/loại ghế/thời gian; trả từng giá và tổng |
| `PricingRuleService` | Rule/amount/status | Sửa cấu hình cho quote mới; booking/ticket snapshot cũ giữ nguyên |

ShowtimeResponse trả metadata phim/phòng và endTime để frontend không cần đọc tài nguyên quản trị trái quyền hoặc tự cộng thời lượng. Seat vật lý và ShowtimeSeat có trạng thái khác nhau; BOOKED của một suất không được dùng làm trạng thái ghế phòng.

## 4. Giữ ghế

[SeatHoldService](../../backend/src/main/java/com/cinema/cms/service/SeatHoldService.java) dùng READ_COMMITTED và khóa các dòng ShowtimeSeat trong thứ tự ổn định. [HoldOwnershipService](../../backend/src/main/java/com/cinema/cms/service/HoldOwnershipService.java) gắn hold với phiên đăng nhập, không chỉ userId.

| Thao tác | Input | Logic | Output |
|---|---|---|---|
| `hold` | showtimeId, seatIds, principal | Kiểm suất/cặp Sweetbox, khóa và kiểm toàn bộ nhóm; không giữ một phần | holdToken, seatIds, holdUntil, serverTime, thông tin gia hạn |
| `extend` | token, waitingForTransfer, principal | Chỉ Staff chờ chuyển khoản; từ phút 4, một lần, cộng 2 phút vào hạn cũ | Hold cùng nhóm với hạn mới |
| `release` | token, principal | Đúng chủ phiên, giải phóng nhóm theo quy tắc | 204 |
| `SeatMapService` đọc | showtimeId, principal | Map AVAILABLE/HELD/BOOKED và ownHolds | Dữ liệu UI, không thay quyền giữ/thu tiền |

Hold thường có hạn 5 phút. `HoldExpirationService`/scheduler giải phóng hết hạn; đường đọc/ghi cũng kiểm deadline. Countdown frontend không phải nguồn xác định hold còn hiệu lực.

## 5. Tạo booking và snapshot

[BookingService.create](../../backend/src/main/java/com/cinema/cms/service/BookingService.java) nhận `{showtimeId, holdToken, concessions?, ageConfirmed?}` và principal:

1. Kiểm ID/token và booking đã dùng hold. Đọc projection seat IDs, sau đó `CheckoutSeatService.lockAndValidateHold` khóa và xác nhận owner/nhóm/hạn giữ.
2. Kiểm lại booking cùng hold sau khi có khóa, tránh hai request tạo trùng.
3. Customer phim giới hạn tuổi cần ageConfirmed; tải quote server và validate các dòng F&B không trùng, còn active.
4. Tính tổng bằng BigDecimal; tạo booking PENDING với channel theo role, userId hoặc staffId phù hợp.
5. Lưu BookingSeat và BookingConcession snapshot. Trả BookingResponse: bookingId/code/status, dòng giá, totalAmount, holdUntil/showtimeId/serverTime/channel.

Dòng `tickets` trong BookingResponse cơ bản là giá ghế dự kiến, chưa có ticketCode và chưa phải Ticket. Frontend chuyển các dòng này thành plannedSeats. `get` kiểm ownership; không cho một ID trong URL tự cấp quyền truy cập.

Tạo booking không giữ hoặc trừ stock F&B. Khi thanh toán, tồn kho vẫn có thể thay đổi và cần kiểm lại.

`rejectExisting` vẫn trả 409 `BOOKING_ALREADY_EXISTS`. Nếu user/staff sở hữu booking và holdOwner khớp chính xác phiên hiện tại, lỗi có `details.bookingId` dạng chuỗi để client GET readback. Phiên khác không nhận ID. Check này chạy trước kiểm deadline và sau khóa inventory, nên vẫn tìm được đơn đã commit khi hold hết hạn, không tạo booking thứ hai. [ADR 007](../decisions/007-booking-create-recovery.md) ghi contract và giới hạn phục hồi.

## 6. Thanh toán và finalize

[CheckoutService.finalizePaid](../../backend/src/main/java/com/cinema/cms/service/CheckoutService.java) là điểm dùng chung để commit bán hàng: kiểm booking/payment, khóa hold và stock, phân rã combo thành nhu cầu từng món, trừ tồn và ghi inventory movement, chuyển ghế sang BOOKED, phát hành Ticket với priceSnapshot. Các thay đổi cùng transaction; lỗi stock/hold không được để lại một phần vé/tồn kho.

[PosPaymentService](../../backend/src/main/java/com/cinema/cms/service/PosPaymentService.java):

- `payCash(bookingId, request, principal)`: chỉ Staff/POS, kiểm tuổi và cashReceived đủ, finalize; response có tổng, tiền nhận, tiền thừa và PAID.
- `createVietQr`: tạo Payment PENDING và thông tin ngân hàng/nội dung; chưa phát vé.
- `confirmVietQr`: Staff xác nhận tiền đã nhận rồi finalize; UI có checkbox kiểm tiền trước khi gọi.

[VnPayService](../../backend/src/main/java/com/cinema/cms/service/VnPayService.java):

| Hàm | Input | Logic/output |
|---|---|---|
| `createPayment` | bookingId, principal | Kiểm đơn online, tạo URL có chữ ký và expiresAt |
| `readReturn` | Query VNPay | Xác thực chữ ký, chỉ đọc payment/booking; không finalize |
| `handleIpn` | Query callback | Kiểm chữ ký, merchant, txnRef, amount, response/status; trả contract RspCode provider |
| Finalize IPN | Callback đã kiểm | Ghi nhận tiền trước, finalize trong transaction riêng; nếu không giao được vé thì ghi EXCEPTION_PENDING_DECISION |

Tách ghi nhận tiền và finalize bảo đảm không mất dấu tiền gateway đã nhận khi hold/stock không còn đáp ứng. IPN lặp có kiểm trạng thái/idempotency. Fixture test tự ký chỉ dùng secret môi trường test; production vẫn xác thực callback đầy đủ.

## 7. Catalog F&B và tồn kho

[ConcessionService](../../backend/src/main/java/com/cinema/cms/service/ConcessionService.java) cung cấp list/get/create/update/status cho món và combo. `stockFor` gom ID món cần đọc, `withAvailability` dùng cùng logic cho response đọc và ghi:

- Món active: availableQuantity bằng stock thực; lowStock khi stock ≤ threshold.
- Combo active: `min(floor(stock_i / requiredQuantity_i))`; component thiếu/inactive làm khả năng bán bằng 0; lowStock dựa trên ngưỡng từng component.
- Item/combo inactive: availableQuantity bằng 0. Customer/Staff chỉ đọc catalog active; Manager quản lý cả hai trạng thái.
- Combo không có dòng stock riêng. Availability là ảnh chụp tại lúc đọc, checkout vẫn khóa và recheck stock.

[InventoryService](../../backend/src/main/java/com/cinema/cms/service/InventoryService.java) nhận itemId/quantity/note để nhập kho, khóa dòng tồn và ghi movement trước/sau/người nhập. History có filter, paging và biên instant; inventory write/history là quyền Manager. Catalog không cấp quyền xem toàn bộ kho cho Customer/Staff.

## 8. Lookup và soát vé

[TicketService](../../backend/src/main/java/com/cinema/cms/service/TicketService.java):

1. `lookup(code, principal)` chỉ Staff; thử Ticket Code trước, sau đó Booking Code; lấy vé cả ONLINE/POS, không thay trạng thái. Không tìm thấy trả lỗi, booking chưa phát vé trả danh sách rỗng.
2. `validate({ticketCode, showtimeId, ageDocumentChecked}, principal)` khóa ticket rồi kiểm trạng thái, đúng suất cửa, cửa sổ thời gian và tuổi.
3. Cửa mở từ 60 phút trước startTime đến 30 phút sau endTime (endTime đã gồm buffer dọn phòng). Sai suất trả `TICKET_WRONG_SHOWTIME`, giữ VALID.
4. Thành công chuyển USED, ghi usedAt/age verification khi cần; request thứ hai không scan lại được. Response validate là DTO kết quả scan; frontend đọc GET ticket để có đủ dữ liệu hiển thị.

`getByCode/listForBooking` vẫn kiểm quyền đọc theo booking; route lookup mới không cho Customer tra tùy ý booking người khác.

## 9. Hoàn vé có Manager duyệt

[RefundService](../../backend/src/main/java/com/cinema/cms/service/RefundService.java) chia ba bước:

| Bước | Input | Logic | Output |
|---|---|---|---|
| `eligibility` | Đúng một bookingCode/ticketCode | Đọc booking đã trả tiền và từng Ticket; VALID, còn ít nhất 30 phút | Mỗi vé: eligible/reasonCode/refundableAmount, vị trí ghế, serverTime |
| `approve` | STAFF session, username/PIN Manager, scope, bookingId, ticketIds, reason, cashConfirmed | Kiểm Manager ACTIVE và PIN, kiểm selection/tiền, khóa dữ liệu cần thiết | Token 32 random byte → 64 hex, TTL 180s, amount |
| `refund` | Token cùng booking/tập vé/lý do/tiền mặt/Staff session | Khóa approval; kiểm bind, quyền Manager/securityVersion, expiry và điều kiện hiện tại | RefundResponse và vé CANCELLED, ghế được release |

DB lưu hash token, không lưu raw token. Ticket IDs được sắp xếp để so sánh tập lựa chọn nhất quán. Nếu approval đã có refundId, cùng token/payload trả kết quả đã ghi, kể cả sau expiry; request khác nội dung/phiên bị từ chối. Refund chỉ tính priceSnapshot vé chọn, không hoàn F&B và không hồi tồn.

## 10. Lịch sử và báo cáo

[CustomerBookingQueryService](../../backend/src/main/java/com/cinema/cms/service/CustomerBookingQueryService.java) trả lịch sử đúng customer; [PosBookingService](../../backend/src/main/java/com/cinema/cms/service/PosBookingService.java) trả POS history/detail với bộ lọc và paging. Detail ghép snapshot, ticket, payment và refund; không mở rộng POS history sang đơn online vì gate/refund có endpoint riêng.

[ReportingService](../../backend/src/main/java/com/cinema/cms/service/ReportingService.java) dùng transaction read-only REPEATABLE_READ cho từng lần gọi. Input `from/to` là ngày Việt Nam; output period có timezone/basis/asOf. Revenue lấy booking đã thanh toán trong kỳ và refund liên quan đến asOf, nên báo cáo kỳ cũ có thể đổi sau hoàn vé. Occupancy theo ngày bắt đầu suất; inventory alerts theo tồn hiện tại.

| Output | Ý nghĩa |
|---|---|
| Revenue | totalRevenue, refundAmount, netRevenue, exceptionPaidAmount |
| Breakdown | Ticket gross/refund/net, concessionRevenue, tổng net |
| Channels | Gross/refund/net mỗi kênh POS/ONLINE |
| Ticket count | Phát hành, hủy và số vé ròng |
| Occupancy | seatsSold/totalSeats/rate tổng có trọng số và từng suất |
| Ranking | Object `{period, direction, movies}`, ASC/DESC, limit |
| Inventory alerts | LOW_STOCK/OUT_OF_STOCK, lượng thực và threshold |

## 11. Đọc test để hiểu invariant

Các test trong [`backend/src/test/java/com/cinema/cms`](../../backend/src/test/java/com/cinema/cms) dùng PostgreSQL thật cho khóa/rollback/concurrency. `ConcessionIntegrationTest` bảo vệ availability và quyền catalog; `StaffIntegrationTest` bảo vệ role/hash/revocation/concurrent reset; `TicketPosHistoryIntegrationTest` bảo vệ lookup/gate/history; `RefundReportsIntegrationTest` bảo vệ approval, idempotency và aggregate sau refund. Test hold/checkout/VNPay kiểm atomicity, thời gian và callback.

Một test pass xác nhận các tình huống test đó; không tự chứng minh mọi trạng thái UI đã đúng. Lệnh và giới hạn nghiệm thu được ghi trong [testing/frontend.md](../testing/frontend.md) và các guide sprint D/E/F.
