# Sprint F Refund và Reports

Ngày triển khai: 09/11/2026, Asia/Ho_Chi_Minh. Phạm vi backend FR-55–66,
UC-17/18, BR-31–40. Các quyết định dưới đây hiện thực hóa phương án trong
[bản chuẩn bị](../architecture/sprint-f-plan.md); giữ chính sách A–E khi không nêu thay đổi.

| Quyết định | Hành vi và lý do |
|---|---|
| F-01 Refund tại POS cho cả hai kênh | Eligibility nhận bookingCode hoặc ticketCode, STAFF/MANAGER đọc được. `/pos/bookings` vẫn chỉ POS. CUSTOMER không tự hoàn tiền. |
| F-02 Override trong phiên Staff | STAFF gọi API, Manager ACTIVE cung cấp username/PIN riêng. Không đổi role/phiên, không cấp Manager JWT. Điều chỉnh role trong ví dụ G4 để đáp ứng popup G1/G2. |
| F-03 Approval có nội dung cụ thể | Scope chỉ REFUND_APPROVAL, token opaque 32 byte ngẫu nhiên, hash SHA-256 trong DB, TTL 180 giây. Bind Staff userId/tokenId, Manager/securityVersion, Booking, ticketIds chuẩn hóa, reason, snapshot amount và cash confirmation. |
| F-04 Xác nhận tiền mặt | Manager xác nhận đã bàn giao tiền khi duyệt; cả approve và refund đòi cashConfirmed=true. Timestamp do Clock tạo. Đây là audit xác nhận ngoài hệ thống, không phải giao dịch ngân hàng; DB rollback không thể lấy lại tiền đã giao thực tế. |
| F-05 Điều kiện hoàn vé | Ticket phải VALID, Booking và Payment PAID, còn >=30 phút. USED/CANCELLED không được hoàn. Kiểm lại sau khi chờ khóa. Số tiền là tổng snapshot các vé, không có F&B; tổng bằng 0 từ chối vì không có tiền mặt để hoàn. |
| F-06 Partial Sweetbox | Hoàn theo Ticket độc lập theo FR-59; không tự hủy thêm vé partner. Hold/bán vẫn đòi đủ cặp AVAILABLE. Hoàn một ghế có thể chưa bán lại được cho tới khi partner cũng AVAILABLE. |
| F-07 Transaction | Khóa approval → Booking → Ticket tăng dần ID → inventory theo showtime/seat ID; recheck rồi cancel/flush Ticket, release/flush ghế, tạo RefundRecord/links, consume token trong cùng transaction READ_COMMITTED. Gate cạnh tranh cùng Ticket lock. Không chờ người dùng/HTTP trong transaction. |
| F-08 Retry | Cùng token + phiên + payload sau commit trả lại RefundRecord cũ, kể cả hết TTL; không release ghế lần hai. Token chưa consume sau rollback dùng lại được nếu còn hạn. Payload/phiên khác bị chặn. Ticket UNIQUE trong refund_ticket_links là lớp bảo vệ DB. |
| F-09 Bảo toàn lịch sử | Giữ Booking/Payment PAID, amount và snapshot gốc. refundStatus NONE/PARTIAL/FULL tính từ vé; POS history cập nhật bằng batch. Không thay enum Payment thành REFUNDED. |
| F-10 Revenue theo cohort thanh toán | from/to là ngày payment.paid_at; gross gồm toàn bộ snapshot vé đã bán (kể cả đã CANCELLED) và F&B. Trừ toàn bộ refund của cohort đến asOf đúng một lần. Đây là doanh thu đơn hàng điều chỉnh, không phải cash-flow chi tiền theo ngày refund. Kết quả kỳ trước có thể đổi sau refund. |
| F-11 Exception payment | PAID + EXCEPTION_PENDING_DECISION được tách thành exceptionPaidAmount; không gán thành doanh thu Ticket/F&B chưa giao, không cho hoàn vé không tồn tại. Chưa triển khai workflow xử lý ngoại lệ tài chính. |
| F-12 Breakdown/ranking | posRevenue/onlineRevenue là NET theo Booking.channel; hoàn cash vé ONLINE vẫn thuộc ONLINE. Ranking gồm Ticket+F&B của Booking theo phim của Showtime, trừ refund; chỉ phim có completed paid bookings trong kỳ, ASC/DESC, mặc định top/bottom 5, tối đa 100, tie-break movieId. |
| F-13 Count/occupancy | Count là VALID/USED hiện tại của cohort thanh toán; occupancy là BOOKED / inventory showtime theo ngày bắt đầu suất, tổng có trọng số. HOLDING không tính; refund rồi bán lại chỉ đếm ghế một lần. |
| F-14 Time/schema | Clock, UTC/TIMESTAMPTZ, ngày nghiệp vụ Asia/Ho_Chi_Minh, khoảng [from 00:00, to+1 00:00). V8 mở rộng bảng refund_records sẵn có, không sửa V1–V7. Legacy refund thiếu/trùng/lệch ticket amount làm migration fail thay vì bỏ audit. |

Định nghĩa revenue sửa sự nhập nhằng của G1: nếu đã loại giá vé CANCELLED khỏi
gross thì không được trừ refund thêm lần nữa. Ví dụ gross vé 140k + F&B30k,
refund70k → net100k. Không ra net30k.

Ngoài phạm vi: frontend, hoàn online tự động, workflow exception tài chính,
retry Payment FAILED, hủy Booking PENDING, webhook ngân hàng VietQR thật,
xuất báo cáo. Các endpoint có Swagger; xem [API F](../api/refund-reports.md).
