# Chuẩn bị backend Sprint F Refund và Reports

Ngày rà soát: 09/11/2026, Asia/Ho_Chi_Minh. Người dùng xác nhận “sprint-7” là phần backend còn lại Refund / Reports (Sprint F). Tài liệu này chuẩn bị triển khai, chưa phải kết quả nghiệm thu hay quyết định nghiệp vụ đã được nhóm phê duyệt. Các mục ghi **đề xuất** là phần làm rõ hoặc bổ sung contract nguồn.

## 1. Nguồn và phạm vi rà soát

Đường dẫn dưới đây tính từ thư mục workspace cha của repository.

| Nhóm | Nguồn đã đọc/kiểm tra | Thông tin dùng cho F |
|---|---|---|
| G1 | `docs-drive/G1/` gồm BUSINESS RULES, FUNCTIONAL REQUIREMENTS, NON-FUNCTIONAL REQUIREMENTS, Mô tả bài toán, Tổng quan | 66 FR, 40 BR, 22 NFR; trọng tâm FR-55–66, BR-31–40, bảo mật, transaction, concurrency, audit và thời gian |
| G2 | Hai DOCX use case/specification; 5 activity diagrams, 5 sequence diagrams, 3 state diagrams | UC-17, UC-18, SUC-12/13; xác nhận giao tiền mặt trước commit; refund trả ghế về AVAILABLE |
| G3 | Mô tả DOCX, `script_cms.sql`, ERD `rela_diag_cms.png`; đọc mục lục archive `backup_cms.backup` bằng `pg_restore --list` | Schema refund có sẵn, snapshot, UNIQUE vé active, FK và lịch sử giao dịch |
| G4 | `Cinema_Backend_5_Muc_Layered_Architecture_v3.docx` và `Cinema_Backend_Layered_Architecture_Plan_v3.md` | 5 layer, REST contract, error codes, 7 report endpoint, definition of done |
| G5 | Màn hình bắt buộc, Wireframe link DOCX | POS tìm đơn/vé, partial refund, popup username/PIN; Manager dashboard/report fields |
| G6 | Quy trình git DOCX | Feature branch từ develop, test, PR, review rồi merge develop |
| Tiến độ | `docs-drive/Tiến độ, phân công.xlsx` | A–F, F giao Quang Anh; bảng trạng thái chưa cập nhật với code |
| Code | Entity/repository/service/controller liên quan A–E, migration V1–V7, decision 005 và test E | Các điểm nối thực tế và chính sách E đã triển khai |

Giới hạn: Figma trong G5 không truy cập được qua công cụ web; chưa xác minh trực quan 27 màn hình bên ngoài. Đã đọc đặc tả màn hình local. Archive G3 chỉ kiểm tra mục lục, không restore hay dùng làm database chạy thử. Tài liệu nguồn và database không bị sửa. Đây là rà soát tĩnh, chưa chạy lại test.

## 2. Phạm vi cần triển khai

| Phần việc | Truy vết | Kết quả cần có |
|---|---|---|
| Tra cứu refund | FR-55, UC-17 | Tìm bằng bookingCode hoặc ticketCode; trả các vé, giá snapshot, suất/ghế, điều kiện và lý do từ chối; hỗ trợ booking POS và ONLINE |
| Eligibility | FR-56, BR-31 | Ticket VALID, chưa dùng/hủy, Payment PAID; còn ít nhất 30 phút; kiểm lại ở lúc commit |
| Override | FR-57/58, BR-32 | Staff giữ nguyên phiên; Manager ACTIVE nhập username/PIN; backend ghi đúng người duyệt |
| Partial refund | FR-59–61, BR-33–36 | Một/nhiều vé cùng Booking; chỉ tiền vé snapshot; tiền mặt mọi phương thức gốc; xác nhận bàn giao; transaction và chống lặp |
| Revenue | FR-62/63, BR-37 | Gross/refund/net; Ticket/F&B; POS/ONLINE; các tổng đối soát được |
| Ticket count / occupancy | FR-64, BR-38 | Vé VALID/USED theo kỳ thanh toán; BOOKED / inventory vật lý theo kỳ suất chiếu |
| Stock alerts | FR-65, BR-26 | Tồn hiện tại, low stock và out of stock; tái sử dụng inventory service |
| Movie ranking | FR-66, BR-39 | Top 5 và nhóm doanh thu thấp; cùng định nghĩa revenue và khoảng ngày |

Không tự mở rộng F thành frontend, voucher, BOM, ngân hàng hoàn tiền tự động, webhook VietQR thật hay workflow xử lý ngoại lệ tài chính phức tạp. Các phần đó không thuộc đầu việc người dùng vừa xác nhận.

## 3. Những gì code đã có

- `refund_records` đã tồn tại trong V1, có booking_id, ticket_ids_json JSONB, manager_id, refund_amount, reason, cash_confirmed_at, created_at. **Không tạo lại bảng cùng tên.** V1 đã dùng TIMESTAMPTZ cho các timestamp liên quan.
- `User.managerPinHash` và seed PIN đã có từ Foundation. Hiện chỉ có setter, cần bổ sung cách service kiểm tra hash mà không đưa hash vào DTO/log.
- `Ticket` có VALID/USED/CANCELLED, `markUsed`, `usedAt`; chưa có hành vi cancel cho refund. Gate khóa Ticket bằng PESSIMISTIC_WRITE.
- `ShowtimeSeat.release` xóa thông tin hold và trả AVAILABLE; cần service kiểm tra BOOKED trước khi dùng cho refund.
- `BookingRepository.findByIdForUpdate` có sẵn; `ShowtimeSeatRepository.lockSeats` khóa theo showtime_id/seat_id, có lock_timeout 2 giây.
- `TicketRepository` có khóa theo ticketCode, chưa có khóa toàn bộ tập ticketIds theo thứ tự xác định.
- Checkout E khóa Booking trước rồi ghế; không gọi HTTP hay dùng REQUIRES_NEW trong transaction nghiệp vụ.
- Booking/Payment giữ số tiền gốc và paidAt; Ticket/F&B có snapshot. Payment chỉ PENDING → PAID/FAILED.
- `PosBookingService` chỉ trả channel POS; `refundStatus` trong summary đang null. Không dùng endpoint này làm lối duy nhất để refund vé ONLINE.
- `InventoryService.alerts` và `/inventory/alerts` đã có. Combo không có stock riêng, không cộng thêm dòng tồn kho giả cho combo.
- `Clock`, timezone `ShowtimeTimeService.BUSINESS_ZONE`, ApiResponse/ApiErrorResponse, JWT/RBAC, PostgreSQL/Testcontainers, MutableClock và fixture E có thể tái sử dụng.

## 4. Xung đột và hướng xử lý đề xuất

| Vấn đề | Căn cứ / hiện trạng | Hướng triển khai đề xuất |
|---|---|---|
| Revenue trừ refund hai lần | G1 viết valid_ticket_revenue + F&B - refund nhưng đồng thời yêu cầu chỉ trừ refund một lần | Gross phải gồm vé từng bán, kể cả vé đã CANCELLED; net = gross - refund. Không lọc CANCELLED khỏi gross rồi trừ refund lần nữa |
| Ngày ghi nhận refund | G4 nói revenue theo kỳ thanh toán, chưa định nghĩa rõ refund khác kỳ | Đề xuất mặc định theo cohort payment.paid_at: chọn các đơn thanh toán trong kỳ và trừ các refund của các đơn đó đến thời điểm truy vấn. Ghi rõ đây là doanh thu đơn hàng đã điều chỉnh, có thể thay đổi sau refund; không gọi là dòng tiền thu/chi trong kỳ. Nếu cần cash-flow, dùng payment.paid_at cho thu và cash_confirmed_at cho chi trong một chế độ riêng, không trộn hai cách |
| Phiên gọi Override | G4 gán role MANAGER nhưng G1/G2 yêu cầu popup ngay trong phiên STAFF | Endpoint yêu cầu STAFF đang đăng nhập; service xác thực riêng Manager username/PIN ACTIVE. Không cấp Manager JWT hoặc đổi phiên Staff. Đây là điều chỉnh contract để thực hiện đúng UC-17 |
| Cash confirmation bị thiếu | G1/G2 yêu cầu Manager xác nhận đã giao tiền; JSON G4 không có cờ | Bổ sung cashConfirmed bắt buộc true vào ý định được Manager duyệt; backend timestamp; token ràng buộc nội dung. Không mặc định true khi thiếu |
| Ticket status | G1/SMD chỉ VALID/CANCELLED; DB/G5/E có USED | Giữ VALID/USED/CANCELLED của E. USED không được refund, kể cả còn hơn 30 phút trước giờ chiếu |
| Sweetbox refund | BR-04 cấm bán nửa cặp; BR-33 cho partial theo từng vé; decision E để mở | Đề xuất cho refund từng Ticket theo FR-59, không tự chọn thêm vé ngoài yêu cầu. Một ghế hoàn lẻ có thể chưa bán lại được vì hold yêu cầu cả pair AVAILABLE. Phải ghi rõ giới hạn này; nếu nhóm muốn hoàn cả cặp thì chốt riêng và đổi acceptance, không ngầm suy diễn từ rule bán |
| Booking sau refund | E coi PAID là terminal; F chưa có trạng thái PARTIALLY_REFUNDED | Giữ Booking/Payment PAID và số tiền gốc; tính refundStatus riêng NONE/PARTIAL/FULL từ record/vé. Không dùng Booking CANCELLED để biểu diễn đã hoàn vé vì còn F&B và lịch sử thu tiền |
| VNPay đã thu tiền, chưa có vé | E có Payment PAID + Booking EXCEPTION_PENDING_DECISION | Không cho dùng ticket refund khi không có Ticket hợp lệ. Reports tách exceptionPaidAmount, không ghi thành doanh thu vé/F&B đã giao. Workflow xử lý tài chính riêng nằm ngoài core F |
| Ranking | G4 chỉ desc; G1 yêu cầu cả top và thấp | Hỗ trợ direction ASC/DESC và limit (mặc định 5, có giới hạn), tie-breaker movieId; nói rõ tập phim có giao dịch hay tất cả phim |
| Diagram lỗi sao chép | SUC-01 DOCX ghi hậu điều kiện refund, không khớp hold; sequence diagram để business trong controller | Dùng FR/BR và 5-layer G4 làm căn cứ nghiệp vụ/kiến trúc; diagram là mô tả flow, không sao chép lỗi sang code |
| Chính sách E khác G2 | G2 cho retry sau FAILED và late success tự book nếu ghế trống; E giữ FAILED terminal và park exception | Giữ hành vi E, không đổi chính sách payment trong Sprint F nếu chưa có yêu cầu riêng |

Các đề xuất trên phải hiện thành quyết định F khi triển khai, cùng test minh họa; không mô tả chúng là điều đã được nguồn chốt.

## 5. Contract API đề xuất

Base `/api/v1`, wrapper `data` như hiện tại; không trả entity.

| API | Quyền | Contract cần hoàn thiện |
|---|---|---|
| GET `/refunds/eligibility` | STAFF, MANAGER | Chính xác một trong bookingCode/ticketCode; trả bookingId/channel, showtime/time, toàn bộ ticket lines, eligible/reasonCode, snapshot/refundableAmount và serverTime. Không giới hạn kênh POS |
| POST `/manager-overrides` | STAFF + xác thực Manager riêng | username, pin, scope=REFUND_APPROVAL, bookingId, ticketIds, reason, cashConfirmed=true. Trả overrideToken/expiresAt và approvedRefundAmount do backend tính |
| POST `/refunds` | STAFF + override hợp lệ | bookingId, ticketIds, reason, overrideToken, cashConfirmed=true; phải khớp ý định đã duyệt. Trả refundId, bookingId, refundAmount, ticketIds, status=COMPLETED, cashConfirmedAt, refundStatus |
| GET `/reports/revenue` | MANAGER | from/to; totalRevenue (gross giao dịch hoàn tất), refundAmount, netRevenue; exceptionPaidAmount riêng nếu có |
| GET `/reports/revenue-breakdown` | MANAGER | ticketRevenue, concessionRevenue là gross; bổ sung ticketRefundAmount/netTicketRevenue/netRevenue để không nhập nhằng |
| GET `/reports/revenue-by-channel` | MANAGER | posRevenue/onlineRevenue phải ghi rõ gross/net; đề xuất trả thêm gross/refund/net theo từng channel, hai net cộng bằng netRevenue |
| GET `/reports/ticket-count` | MANAGER | ticketCount = VALID + USED của đơn đã thanh toán trong kỳ; có thể thêm grossTicketCount/cancelledTicketCount để đối chiếu |
| GET `/reports/occupancy` | MANAGER | kỳ theo showtimes.start_time; từng showtime có seatsSold, totalSeats, occupancyRate; tổng = sum BOOKED / sum inventory, không lấy trung bình các phần trăm |
| GET `/reports/inventory-alerts` | MANAGER | Không cần from/to; itemId, itemName, stockQuantity, lowStockThreshold, status |
| GET `/reports/movies/ranking` | MANAGER | from/to, direction, limit; movieId/title/ticketCount/revenue; revenue dùng cùng gross/refund/net convention |

Ngày report là ISO YYYY-MM-DD trong Asia/Ho_Chi_Minh. Require cả from/to cho các report theo kỳ; from > to hoặc ngày sai trả 400. Chuyển thành [đầu ngày from, đầu ngày to+1) bằng timezone nghiệp vụ. Không tái dùng parser Instant của inventory history cho input LocalDate này. Empty report trả 0/danh sách rỗng, không chia 0. Số tiền dùng BigDecimal.

## 6. Override và transaction

Đề xuất token opaque ngẫu nhiên đủ mạnh, TTL 3 phút (nằm trong khoảng 2–5 phút của G4), một lần dùng; chỉ lưu SHA-256 hash. Không dùng JWT đăng nhập làm overrideToken. Binding gồm Staff userId/tokenId, Manager id/securityVersion, scope, bookingId, danh sách ticketIds đã chuẩn hóa, reason, cashConfirmed và số tiền backend duyệt. Không log PIN/token/request body.

Lúc cấp token phải kiểm tra Manager role/status/PIN, Ticket/Payment và thời gian; kiểm lại Manager ACTIVE/securityVersion, phiên Staff và điều kiện vé khi consume. Token chưa dùng có thể retry sau rollback; token đã commit không tạo refund mới. Nếu response bị mất, trả record đã hoàn cho cùng token + cùng nội dung, không ghi thêm; token dùng với payload khác bị từ chối. Lưu consumed refundId để readback bền qua restart.

Luồng refund một transaction READ_COMMITTED:

1. Kiểm tra format, trùng ticketIds, reason độ dài <=255, cashConfirmed.
2. Khóa bản ghi override, kiểm binding/expiry; nếu đã hoàn thì trả đúng kết quả cũ khi payload khớp.
3. Khóa Booking; khóa các Ticket tăng dần ticketId; lấy lock trực tiếp, không preload entity trước lock.
4. Đảm bảo tất cả Ticket thuộc Booking, đủ số lượng, VALID; Payment PAID, Booking PAID.
5. Khóa các showtime_seats theo thứ tự showtimeId/seatId như D/E; kiểm ghế BOOKED và đúng liên kết Ticket. Gate chỉ khóa Ticket nên refund/gate phải tranh chấp trên cùng khóa Ticket.
6. Lấy Clock sau khi đã chờ lock; kiểm lại startTime - now >= 30 phút (đúng 30 phút vẫn hợp lệ), account/token và số tiền snapshot.
7. Ticket → CANCELLED rồi flush trước khi ghế có thể được bán lại; Seat → AVAILABLE; ghi refund_records + ticket links/audit; consume override trong cùng transaction.
8. Commit. Lỗi bất kỳ bước nào rollback cả vé/ghế/record/token. Không giữ transaction trong lúc Manager đếm tiền, nhập PIN hay chờ thao tác người dùng; không có HTTP trong transaction.

Tổng refund lấy sum(Ticket.priceSnapshot) của tập vé, không đọc giá hiện hành, không nhận final amount hay managerId từ client. Không sửa snapshot/Payment.amount/Booking.totalAmount, không hồi F&B hoặc stock. Bảo vệ vé cũ CANCELLED để request lặp không release nhầm ghế đã bán lại.

Cash handover là sự kiện thực tế không thể rollback bằng DB. API chỉ ghi nhận xác nhận đã bàn giao; nếu commit thất bại sau bàn giao, phải cho retry/readback và báo lỗi rõ để đối soát, không tuyên bố transaction bảo đảm atomic cả tiền mặt ngoài hệ thống.

## 7. Migration và mã nguồn cần bổ sung

Tạo migration mới tiếp theo sau V7, dự kiến `V8__refund_override_support.sql`; kiểm tra version trống trước khi code. Không sửa V1–V7.

- Mở rộng refund_records nếu cần staff_id/override reference và index `(booking_id, created_at)`, thời điểm hoàn.
- Thêm bảng manager_overrides: token_hash UNIQUE, actor/session, manager/securityVersion, scope, nội dung duyệt, expires_at, consumed_at, refund_id. Không lưu PIN/raw token.
- Đề xuất bảng refund_ticket_links với ticket_id UNIQUE, refund_id FK, giá snapshot để DB chặn hoàn cùng vé hai lần. Giữ ticket_ids_json tương thích schema nguồn; ghi hai biểu diễn trong cùng transaction và kiểm nhất quán.
- Trước constraint/backfill phải kiểm tra record legacy trùng ticket, JSON sai, amount lệch; không tự bỏ dữ liệu audit để migration pass. Fresh V1 không seed refund; DB import cũ vẫn cần kiểm riêng.
- Nếu cần ghi cash_confirmed=true thì dùng timestamp và audit trong record; không có API sửa/xóa refund thường.
- Chỉ thêm index phục vụ query report đã xác định (paid_at, refund booking/time, showtime start_time); tránh index theo phỏng đoán.

Giữ đúng 5 package: controller, service, dto, repository, entity. Dự kiến ManagerOverrideController/Service, RefundController/Service, RefundRecord/Repository, ManagerOverride/Repository, ReportingController/Service/Repository và DTO riêng cho từng request/response. Thêm Ticket.cancel có guard VALID; tái sử dụng inventory alerts; cập nhật POS refundStatus bằng aggregate batch để tránh N+1. Không mở rộng `/pos/bookings` sang ONLINE.

Report aggregate riêng từng nguồn trước khi join. Không join thẳng Ticket × booking_concessions × refund_records rồi SUM vì nhân số dòng và tăng sai doanh thu. Gross Ticket lấy snapshot cả vé đã hủy của đơn đã hoàn tất; F&B lấy quantity × unit_price_snapshot đúng một lần; refund đọc record. Channel lấy Booking.channel. Movie lấy từ Booking.showtimeId → Showtime.movieId, không gắn một payment nhiều lần theo số vé. Nếu ranking gồm F&B thì quy toàn bộ F&B của Booking cho phim của chính Booking, ghi rõ công thức; không chia tùy ý giữa vé.

## 8. Kế hoạch kiểm chứng

| Nhóm test | Các ca bắt buộc |
|---|---|
| Migration | Database mới từ V1 đến V8 và nâng từ V7; constraint/token/ticket UNIQUE; refund_records có sẵn không bị tạo lại |
| Eligibility | POS/ONLINE; bookingCode/ticketCode; code không tồn tại; input thiếu/thừa; đúng 30 phút, dưới 30 phút; USED/CANCELLED; Payment PENDING/FAILED |
| Override | PIN sai, Manager LOCKED/INACTIVE/không phải Manager/chưa có PIN; scope sai; hết hạn; phiên Staff khác; Manager bị khóa sau cấp; payload/amount bị đổi; không lộ hash/PIN/token |
| Refund | Partial/full; Cash/VietQR/Online đều chi cash; snapshot không đổi sau tăng giá; không refund F&B; cashConfirmed thiếu/false; vé lẫn Booking, duplicate/empty IDs; reason không hợp lệ |
| Atomicity | Ép lỗi sau cancel/release/before record; rollback cả Ticket/Seat/record/token; retry hợp lệ sau rollback |
| Concurrency | Hai refund cùng vé; chồng một phần tập vé; một token dùng đồng thời; refund và gate scan; refund rồi bán lại, request refund cũ không nhả ghế mới |
| Sweetbox | Hai vé độc lập; ca hoàn một nửa theo chính sách được chọn; khả năng hold/bán lại sau refund không bypass pair rule |
| Revenue | Gross 2 vé ×70k + F&B30k =170k; refund 1 vé70k → net100k; không thành30k. Hai dòng F&B và nhiều refund không nhân SUM; partial/full; khác kỳ; refund tại POS của đơn ONLINE vẫn trừ channel ONLINE |
| Reports | VALID/USED/CANCELLED; exclude PENDING/FAILED; exception paid tách riêng; from/to cùng ngày và biên UTC+7; empty/zero denominator; ranking top/bottom/tie; breakdown cộng khớp tổng |
| Occupancy | Refund giảm BOOKED, resell không đếm vé cũ; HOLDING không tính; mẫu số inventory thực của từng showtime; tổng có trọng số |
| RBAC/history | CUSTOMER bị chặn mọi API F; STAFF không đọc reports; không bypass approval; POS history vẫn chỉ POS; refundStatus NONE/PARTIAL/FULL |

Dùng PostgreSQL thật/Testcontainers, MockMvc + JWT thật và MutableClock như suite E; không dùng H2 hoặc sleep để chứng minh concurrency. Khi code xong, chạy test F tập trung rồi `backend/mvnw.cmd -B -ntp verify`; chạy lại suite toàn bộ khi có thay đổi/failure liên quan. Chưa chạy các test này trong lượt chuẩn bị.

## 9. Thứ tự thực hiện và bàn giao

1. Ghi decision F cho những đề xuất ở mục 4, đặc biệt cohort/kỳ refund, Override trong phiên Staff, Sweetbox và ranking.
2. Theo G6 tạo `feature/refund-reports` từ develop sau khi kiểm tra working tree; hiện develop có thay đổi sẵn ở `docs/api/auth.md`, phải giữ nguyên và không đưa nhầm vào commit F.
3. Migration + entity/repository + test schema; Override + test auth/binding/replay.
4. Eligibility + refund transaction + concurrency/rollback + đọc refundStatus lịch sử POS.
5. Report aggregate + fixtures đối soát; unit/integration test theo ma trận trên.
6. Swagger/API docs, decision 006, testing/sprint-f, README/demo; build/verify và PR để review theo G6.

Definition of done: đủ controller/DTO/validation/RBAC/service/query/transaction/error code/Swagger/audit/test; mapping FR/BR/UC → endpoint → test truy được; không trả entity hoặc tin giá/actor từ frontend. Phân biệt backend đã hoàn thành với frontend chưa nghiệm thu.
