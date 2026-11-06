# Sprint D — seat holding

Ngày: 06/11/2026, Asia/Ho_Chi_Minh. Phạm vi triển khai lần này là backend Sprint D theo §2 của [PLAN](../PLAN.md), cùng các sửa nền layout/Sweetbox ở §5.3–5.4. Backend Booking/Payment/Ticket của E không thuộc phạm vi D.

## Hiệu lực và các quyết định được bổ sung/thay thế

`001`, `002` và `003` giữ nội dung tại thời điểm bàn giao từng sprint. Khi tích hợp Sprint D, áp dụng các quyết định dưới đây cho những mục được chỉ rõ; các quyết định khác trong ba tài liệu đó tiếp tục có hiệu lực.

**Thay thế** nghĩa là hành vi cũ được đổi hoặc một mục mở đã được chốt. **Bổ sung** nghĩa là cụ thể hóa phần bàn giao cho D, giữ nguyên các quy tắc nền đã có.

| Mã | Quan hệ và nguồn trước D | Quyết định áp dụng khi tích hợp D |
|---|---|---|
| **D-01 — Khóa layout** | **Thay thế** giả định layout/pair còn sửa được sau khi lập lịch trong [003 — Decisions still owned by the team](003-showtime.md#decisions-still-owned-by-the-team); bổ sung giới hạn cho API ghế của [002 — Auditoriums and seats](002-master-data.md#auditoriums-and-seats). | Khi phòng đã có **bất kỳ showtime nào**, kể cả quá khứ, thêm/sửa ghế và pair/unpair trả `409 AUDITORIUM_LAYOUT_LOCKED`. Guard chạy dưới cùng khóa Auditorium với C CREATE. Đổi thông tin/trạng thái phòng vẫn theo B; không đồng bộ hoặc rebuild inventory cũ. PATCH ghế không đổi field cũng bị chặn trong triển khai này. |
| **D-02 — Cấu hình Sweetbox** | **Thay thế** mục mở chưa kiểm tra cùng hàng/liền kề trong [002 — Sweetbox pairs](002-master-data.md#sweetbox-pairs) và mô tả B cho phép pair không liền kề trong [003 — Decisions still owned by the team](003-showtime.md#decisions-still-owned-by-the-team). Đồng thời chốt giữ yêu cầu cả hai ghế thuộc loại SWEETBOX. | B/C/D dùng chung validator: khác ID, cùng phòng/hàng, đều SWEETBOX, số ghế liền kề; layout phải có liên kết hai chiều. B từ chối candidate sai trước khi ghi bằng `400 SWEETBOX_PAIR_INVALID`; C từ chối layout sai bằng 422; D từ chối bằng 409. Giữ `409 SWEETBOX_ALREADY_PAIRED` khi ghế đã thuộc cặp. |
| **D-03 — Hai ghế vật lý của một cặp** | **Thay thế** mục mở một hay hai ghế/Ticket của một cặp trong [002 — Sweetbox pairs](002-master-data.md#sweetbox-pairs). | Một hold Sweetbox chứa **hai seat IDs**, giữ nguyên tử cùng token/owner/deadline. Contract bàn giao yêu cầu E phát hành **hai Ticket độc lập** khi thanh toán thành công. D không triển khai phát hành Ticket; chính sách partial refund vẫn thuộc F. |
| **D-04 — Giao thức khóa C/D/E** | **Bổ sung** cách thực thi cho D/E trong [003 — Transaction and lock contract](003-showtime.md#transaction-and-lock-contract), giữ nguyên thứ tự khóa của C. | C PATCH vẫn khóa Auditorium → Showtime → inventory và recheck trạng thái sau chờ. D đọc scalar room, khóa inventory theo `(showtimeId,seatId)`, rồi đọc lại startTime bằng SELECT thường và lấy giờ server. D không chờ khóa Auditorium/Showtime khi đã giữ inventory. Thêm lock timeout 2 giây tại bước khóa inventory. CheckoutSeatService bắt buộc transaction của E và kiểm đúng toàn bộ expectedSeatIds đã lưu trong Booking. |
| **D-05 — Input path/query sai kiểu** | **Thay thế** mục mở handler chưa xử lý `MethodArgumentTypeMismatchException`, dẫn đến 500, trong [002 — Cross-cutting decisions / Query parameters](002-master-data.md#cross-cutting-decisions). | Handler chung trả `400 VALIDATION_ERROR` cho lỗi chuyển kiểu tham số/path. D kiểm thêm ID suất dương và token UUID. Quyết định này chốt mục xử lý sai kiểu; không khẳng định các lỗi validation khác của B đã được sửa hết. |
| **D-06 — Lỗi cạnh tranh và bảo vệ token** | **Bổ sung** vào [001 — Contract](001-foundation.md#contract), giữ nguyên các trường của ApiErrorResponse. | Lock/version conflict trả `409 SEAT_UPDATE_CONFLICT`. Token trong đường dẫn `/holds/{token}` được thay bằng `[redacted]` trong response lỗi nghiệp vụ/security và log ứng dụng. Các endpoint hiện hữu tiếp tục dùng ApiResponse/ApiErrorResponse của Foundation. |
| **D-07 — Vòng đời hold và bàn giao checkout** | **Bổ sung** phần giữ ghế được [001 — Database](001-foundation.md#database) để lại cho module sau và phần seat map/hold ngoài phạm vi C trong [003 — Sources and traceability](003-showtime.md#sources-and-traceability). | POS/Online dùng chung `showtime_seats`; owner gắn với `role:userId:tokenId`. Hold 300 giây, Sweetbox đủ cặp, retry đúng cả nhóm không reset đồng hồ. Chỉ Staff đang chờ chuyển khoản được gia hạn một lần từ giây 240 đến trước hết hạn, cộng 120 giây vào hạn cũ. Cleanup từng nhóm trong transaction riêng; BOOKED được chuyển trong transaction của E bằng service MANDATORY. Chi tiết và biên lỗi nằm ở các mục tiếp theo. |

**Giới hạn của việc thay thế:** D-01 khiến API unpair/edit/pair thông thường chỉ còn dùng để sửa layout trước khi phòng có lịch. Hướng dẫn trong `003` về việc dùng các API đó để sửa pair legacy không còn áp dụng cho phòng đã có showtime; dữ liệu này cần quy trình xử lý riêng, bảo toàn inventory/Ticket/lịch sử.

Các mục mở về ngừng phim/phòng khi có lịch tương lai, reset mật khẩu/PIN Manager, định nghĩa và áp dụng giá, F&B, vòng đời Ticket, callback thanh toán, refund và báo cáo tiếp tục thuộc phạm vi tương ứng của B/C/E/F. Tài liệu này không chốt thêm chính sách cho các mục đó.

## Tương thích với quyết định A/B/C

| Quyết định | Sprint D giữ nguyên | Điểm được chốt thêm |
|---|---|---|
| [001 — Foundation](001-foundation.md) | Java 21, năm package, constructor injection, JWT hiện có, role/status hiện hành, Clock, ApiResponse, ApiErrorResponse, UTC/TIMESTAMPTZ, Flyway/Hibernate validate | Lỗi lock/version trả 409; path chứa hold token được che trong cả response lỗi security và log ứng dụng. Lỗi chuyển kiểu path trả 400, giải quyết mục mở của B. |
| [002 — Master Data](002-master-data.md) | Dùng lại Seat/Auditorium và repository; khóa phòng trước mọi ghi layout; API quản trị chỉ Manager; ID theo kiểu SQL; không xóa ghế/phòng; không đổi API phim/pricing/F&B | Chốt adjacency và hai ghế vật lý của Sweetbox; guard layout cho phòng đã có bất kỳ lịch nào. DELETE hold chỉ clear trạng thái, không xóa dữ liệu quản trị/lịch sử. |
| [003 — Showtime](003-showtime.md) | Constructor/mapping ShowtimeSeat và `@Version Integer`; inventory chung, khởi tạo ở C; PATCH giữ ID/basePrice/inventory; thứ tự khóa và READ_COMMITTED | D đọc scalar room trước lock, khóa inventory tăng dần rồi đọc lại startTime hiện hành bằng SELECT thường. C recheck trạng thái sau chờ inventory và có timeout 2 giây tại bước này. |

Không sửa migration V1–V5 hoặc POM; không thêm bảng hold, Entity trùng hay CatalogController của reference. `.codex-reference` là nguồn mẫu cho D, không phải source thay thế B/C.

## Owner và vòng đời hold

Owner là `role:userId:tokenId` từ SessionPrincipal đã xác thực, không nhận từ body. Chỉ STAFF và CUSTOMER được dùng API seat map/hold/release; chỉ STAFF được extend. Hai JWT của cùng tài khoản sở hữu hai hold độc lập. OwnHolds chỉ chứa nhóm live của đúng phiên gọi; không trả owner/JWT/token của người khác. E phải lưu owner cùng token, showtime và toàn bộ planned seat IDs trong Booking khi tạo đơn.

`AVAILABLE → HOLDING → BOOKED`; release/cleanup chuyển HOLDING về AVAILABLE. Hold mặc định 300 giây. Tất cả ghế cùng nhóm có chung UUID, owner, deadline và extensionUsed. `now >= holdUntil` là hết hạn. Ghế hết hạn có thể reclaim ngay; GET hiển thị AVAILABLE mà không ghi DB. Release/book clear owner/token/deadline và extensionUsed; JPA tăng version trong cùng transaction.

Request có 1–100 ID nguyên dương, không null/trùng. Mở rộng Sweetbox xong vẫn tối đa 100 ghế. Tạo hold là all-or-nothing. Retry chỉ trả lại hold cũ khi tập ghế sau mở rộng khớp chính xác toàn bộ membership của một nhóm live cùng phiên; không đặt lại đồng hồ. Subset, superset hoặc gộp hai nhóm đều bị từ chối. Không tự retry request ghi ở tầng API.

Gia hạn cần Staff đang chờ chuyển khoản (`waitingForTransfer=true`), đúng phiên, nhóm còn live, chưa gia hạn, và `oldUntil - 60s <= now < oldUntil`. Hạn mới bằng hạn cũ +120 giây; hai request đồng thời chỉ một request thành công. Cờ chuyển khoản là xác nhận nghiệp vụ của Staff, không phải bằng chứng backend đã nhận tiền.

## Khóa và transaction

- C CREATE / ghi layout: khóa Auditorium trước; guard và kiểm tra dữ liệu chạy sau khi có lock. C PATCH: Auditorium → Showtime → inventory theo seatId, rồi kiểm tra lại không có hold/BOOKED/Ticket.
- D: READ_COMMITTED; scalar room → layout đã được guard → inventory theo `(showtimeId,seatId)` → đọc lại startTime → lấy giờ server → kiểm tra và ghi. Không giữ Entity showtime từ trước lúc chờ lock. Không giữ inventory rồi chờ lock Auditorium/Showtime.
- Các thao tác theo token: scalar membership → inventory theo cùng thứ tự → recheck token/status/owner/deadline. Những dòng bị release/reclaim/book trong lúc chờ không còn thuộc token cũ.
- `SET LOCAL lock_timeout='2s'` trước lock inventory. Lock/version contention trở thành 409 `SEAT_UPDATE_CONFLICT`; không retry ghi tự động. Guard layout vẫn dùng cùng cơ chế khóa phòng của B/C, không đổi chính sách active/name/capacity/type của phòng.
- Không bọc D/C trong outer transaction có isolation/snapshot khác. E mở transaction READ_COMMITTED, khóa Booking rồi gọi CheckoutSeatService; không preload Entity inventory trước khi lấy lock.

## Chốt các mục mở về layout và Sweetbox

Sau khi phòng có **bất kỳ showtime nào**, kể cả quá khứ, thêm/sửa ghế hoặc pair/unpair bị từ chối bằng 409 `AUDITORIUM_LAYOUT_LOCKED`. Bản này chọn từ chối cả PATCH ghế không đổi field để giữ guard đơn giản; PLAN cho phép cách làm này. Đổi thông tin/trạng thái phòng vẫn theo B. Guard dùng exists query dưới khóa Auditorium mà C CREATE cũng giữ, nên race chỉ có hai kết quả: layout commit trước thì C lấy layout mới; C commit trước thì layout bị chặn.

SweetboxPairValidator dùng chung ở B/C/D: khác ID, cùng phòng/hàng, cả hai SWEETBOX, số liền kề, pair hai chiều. Pair API từ chối candidate sai bằng 400 `SWEETBOX_PAIR_INVALID`; C từ chối layout legacy sai bằng 422; D từ chối bằng 409. Giữ 409 `SWEETBOX_ALREADY_PAIRED` khi ghế đã thuộc cặp. Một cặp luôn gồm hai seat IDs; E có trách nhiệm phát hành hai Ticket riêng khi thanh toán thành công.

Guard không sửa dữ liệu cũ. [Truy vấn audit](../../scripts/testing/check-showtime-inventory.sql) chỉ SELECT, phát hiện thiếu/thừa mapping và pair sai. Seat map trả 409 nếu tập inventory không khớp physical seats, kể cả số lượng bằng nhau. Không tự xóa/rebuild inventory, Ticket hoặc lịch sử để sửa mismatch. Phòng cũ đã có lịch nhưng pair sai cần quy trình xử lý riêng, không bỏ guard bằng API quản trị thông thường.

## Cleanup và bàn giao Sprint E

Scheduler mặc định mỗi 10 giây, tối đa 100 candidate token. Mỗi nhóm được xử lý qua HoldExpirationService bean riêng, một transaction riêng; khóa/recheck rồi mới clear khi toàn bộ nhóm vẫn HOLDING và đã hết hạn. Một nhóm lỗi không dừng batch hoặc rollback nhóm khác. Log chỉ số lượng/loại lỗi, không token. Scheduler chậm không làm hold hết hạn đủ điều kiện checkout.

CheckoutSeatService có `Propagation.MANDATORY`, nhận `(token, owner, showtimeId, expectedSeatIds)` từ Booking đã lưu. `lockAndValidateHold` và `markBooked` kiểm đúng phiên, đúng suất, đầy đủ tập ghế và thời hạn; markBooked kiểm lại ngay lúc chuyển trạng thái. Không chấp nhận phần membership còn sót lại nhỏ hơn đơn đang thu tiền. E cần dùng cùng transaction cho Payment, stock, Ticket và BOOKED; rollback của E rollback ghế theo. Không gọi HTTP và không REQUIRES_NEW.

Không thêm Booking/Payment/Ticket giả để thử tích hợp. Hold và Booking có vòng đời riêng; D không hủy Booking/Payment, xử lý tiền hoặc tự xuất vé. Callback đến muộn và quyết định ngoại lệ tài chính thuộc E; D chỉ cung cấp contract kiểm tra và chuyển trạng thái ghế trong cùng transaction.
