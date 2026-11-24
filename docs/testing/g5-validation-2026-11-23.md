# Bằng chứng kiểm thử G5 ngày 23/11/2026

Đối chiếu working tree `feature/frontend`; chưa tạo commit. Đây là checkpoint triển khai, không phải tuyên bố nghiệm thu hoàn toàn 27/27 màn. Ảnh đại diện và luồng thành công không thay thế toàn bộ matrix lỗi/biên.

## Môi trường và phương pháp

- PostgreSQL 16.15 tách biệt trong `/tmp/cinema-g5-resume/pg`, port 55439. Verify cuối trên database rỗng `cinema_g5_verify_final`; live cuối trên `cinema_g5_live_final`. Không dùng `.env` hoặc database dự án.
- Backend artifact sau verify, profile local, credential test ngẫu nhiên; VNPay callback ký bằng secret test qua bộ xác thực bình thường, không gọi gateway ngoài.
- Node 22.22.3, Java 21.0.13.1, Playwright 1.64.1; browser version và kết quả chạy được ghi dưới đây sau khi suite hoàn tất. Không chạy hai suite Playwright đồng thời cùng cổng/output.
- Live G5 có ảnh desktop và ảnh viewport suffix 320/390/768/1024/1440; mock QA kiểm keyboard/dialog/print/reduced motion và contrast control đại diện.

## Kết quả

- Backend verify sạch: **242 tests pass, 0 failure/error/skip**.
- Unit/component: **9 files, 34 tests pass**. Build/typecheck ba portal pass, có cảnh báo bundler bỏ qua `use client` của React Router.
- Mock/Edge/live cuối: đang chạy, chưa lấy checkpoint cũ làm pass cuối.
- Mutation check: đảo điều kiện ID recovery trong adapter làm contract tests fail; đã khôi phục source và chạy lại unit xanh.

## Traceability 27 màn

Cột bằng chứng là test thực sự liên quan; các phần còn thiếu được ghi rõ. Ảnh base nằm ở `screenshots/g5/`; ảnh `<ID>-<width>.png` cho năm viewport đi kèm nếu capture đã hoàn tất. Manifest `live-evidence.json` ghi capture/time/timing/console/network và đối soát, không phải kết quả tải/SLO production.

| ID | Màn | Source | Test API thật | Ảnh | Phạm vi/giới hạn |
|---|---|---|---|---|---|
| C01 | Customer đăng nhập | [code](../../frontend/shared/src/auth/AuthScreen.tsx) | [test](../../frontend/tests/auth.spec.ts) | [ảnh](screenshots/g5/C01.png) | Login/logout/credential/session/transfer; matrix LOCKED còn cần review UI. |
| C02 | Đăng ký | [code](../../frontend/shared/src/auth/AuthScreen.tsx) | [test](../../frontend/tests/auth.spec.ts) | [ảnh](screenshots/g5/C02.png) | Đăng ký và login thật; còn rà đầy đủ lỗi duplicate/field trên UI. |
| C03 | Danh sách phim | [code](../../frontend/shared/src/pages/BookingPages.tsx) | [test](../../frontend/tests/g5.live.spec.ts) | [ảnh](screenshots/g5/C03.png) | Tìm phim, mở detail; còn kiểm chứng toàn bộ pagination/back context. |
| C04 | Chi tiết phim/suất | [code](../../frontend/shared/src/pages/BookingPages.tsx) | [test](../../frontend/tests/g5.live.spec.ts) | [ảnh](screenshots/g5/C04.png) | Metadata/endTime thật, chọn suất; còn matrix 404/hết hạn. |
| C05 | Ghế và F&B | [code](../../frontend/shared/src/pages/BookingPages.tsx) | [test](../../frontend/tests/g5.live.spec.ts) | [ảnh](screenshots/g5/C05.png) | Sweetbox cả cặp, F&B availability; tranh ghế API trong booking.live.spec.ts. |
| C06 | Checkout online | [code](../../frontend/shared/src/pages/BookingPages.tsx) | [test](../../frontend/tests/g5.live.spec.ts) | [ảnh](screenshots/g5/C06.png) | Đơn/giá server, tạo URL VNPay; không có gia hạn Customer. |
| C07 | Payment return | [code](../../frontend/shared/src/pages/PaymentReturnPage.tsx) | [test](../../frontend/tests/g5.live.spec.ts) | [ảnh](screenshots/g5/C07.png) | Return ký hợp lệ chỉ đọc; pending/failed/invalid cần rà đủ UI. |
| C08 | Chi tiết vé | [code](../../frontend/shared/src/components/TicketView.tsx) | [test](../../frontend/tests/g5.live.spec.ts) | [ảnh](screenshots/g5/C08.png) | Vé/QR/snapshot thật; mock kiểm print CSS, chưa kiểm máy in vật lý. |
| C09 | Lịch sử Customer | [code](../../frontend/shared/src/pages/BookingPages.tsx) | [test](../../frontend/tests/g5.live.spec.ts) | [ảnh](screenshots/g5/C09.png) | Mở đơn đã PAID từ history; còn matrix back/pagination/error. |
| S01 | Staff đăng nhập | [code](../../frontend/shared/src/auth/AuthScreen.tsx) | [test](../../frontend/tests/auth.spec.ts) | [ảnh](screenshots/g5/S01.png) | Login/logout/session thật; management.live.spec.ts thêm inactive/reset. |
| S02 | Chọn phim/suất | [code](../../frontend/shared/src/pages/BookingPages.tsx) | [test](../../frontend/tests/g5.live.spec.ts) | [ảnh](screenshots/g5/S02.png) | Lọc phim và chọn suất thật, ngày Việt Nam. |
| S03 | Ghế và F&B POS | [code](../../frontend/shared/src/pages/BookingPages.tsx) | [test](../../frontend/tests/g5.live.spec.ts) | [ảnh](screenshots/g5/S03.png) | Hai ghế/F&B cùng trang; còn race stock/hold response UI đầy đủ. |
| S04 | Thanh toán POS | [code](../../frontend/shared/src/pages/BookingPages.tsx) | [test](../../frontend/tests/g5.live.spec.ts) | [ảnh](screenshots/g5/S04.png) | Cash/reload và VietQR API; mock mất response create/expiry; còn extension UI matrix. |
| S05 | Vé/receipt | [code](../../frontend/shared/src/components/TicketView.tsx) | [test](../../frontend/tests/g5.live.spec.ts) | [ảnh](screenshots/g5/S05.png) | Hai QR vé đã PAID; print CSS kiểm bằng mock, chưa máy in/PDF paging đầy đủ. |
| S06 | Lịch sử POS | [code](../../frontend/shared/src/pages/BookingPages.tsx) | [test](../../frontend/tests/g5.live.spec.ts) | [ảnh](screenshots/g5/S06.png) | Tìm Booking Code, PARTIAL readback; còn matrix filter/pagination. |
| S07 | Chi tiết giao dịch | [code](../../frontend/shared/src/pages/BookingPages.tsx) | [test](../../frontend/tests/g5.live.spec.ts) | [ảnh](screenshots/g5/S07.png) | Read-only detail và link refund giữ mã. |
| S08 | Hoàn vé | [code](../../frontend/shared/src/pages/StaffPages.tsx) | [test](../../frontend/tests/g5.live.spec.ts) | [ảnh](screenshots/g5/S08.png) | Partial refund thật; mock PIN sai/giữ phiên; expiry/lost response cần matrix UI thêm. |
| S09 | Soát vé | [code](../../frontend/shared/src/pages/StaffPages.tsx) | [test](../../frontend/tests/g5.live.spec.ts) | [ảnh](screenshots/g5/S09.png) | Booking Code có vé CANCELLED/VALID, soát USED; mismatch/hai cửa mở ở backend; chưa USB vật lý. |
| M01 | Manager đăng nhập | [code](../../frontend/shared/src/auth/AuthScreen.tsx) | [test](../../frontend/tests/auth.spec.ts) | [ảnh](screenshots/g5/M01.png) | Login/logout/transfer/session thật. |
| M02 | Báo cáo | [code](../../frontend/shared/src/pages/reports/ReportsPage.tsx) | [test](../../frontend/tests/g5.live.spec.ts) | [ảnh](screenshots/g5/M02.png) | Đối soát gross/refund/net/breakdown/channel/count/ranking/occupancy sau hoàn; UI các tổng revenue. |
| M03 | Quản lý phim | [code](../../frontend/shared/src/pages/ManagementPages.tsx) | [test](../../frontend/tests/booking.live.spec.ts) | [ảnh](screenshots/g5/M03.png) | Tạo phim thật và đọc danh sách; conflict/status-partial được unit/mock, chưa đủ UI matrix. |
| M04 | Phòng/ghế | [code](../../frontend/shared/src/pages/ManagementPages.tsx) | [test](../../frontend/tests/booking.live.spec.ts) | [ảnh](screenshots/g5/M04.png) | Tạo phòng/ghế thường, layout thật; pair/unpair/guard backend đã test, UI pair còn mở. |
| M05 | Lịch chiếu | [code](../../frontend/shared/src/pages/ManagementPages.tsx) | [test](../../frontend/tests/booking.live.spec.ts) | [ảnh](screenshots/g5/M05.png) | Tạo suất từ phim/phòng; mock conflict giữ form; options nhiều page cần live thêm. |
| M06 | Quy tắc giá | [code](../../frontend/shared/src/pages/pricing/PricingPage.tsx) | [test](../../frontend/tests/g5.live.spec.ts) | [ảnh](screenshots/g5/M06.png) | Render dữ liệu thật; giá/snapshot có integration, quote preview và đổi rule UI chưa nghiệm thu đủ. |
| M07 | F&B/combo | [code](../../frontend/shared/src/pages/ManagementPages.tsx) | [test](../../frontend/tests/booking.live.spec.ts) | [ảnh](screenshots/g5/M07.png) | Tạo món, availability response mutation/read nhất quán; UI combo đầy đủ còn mở. |
| M08 | Tồn/lịch sử nhập | [code](../../frontend/shared/src/pages/ManagementPages.tsx) | [test](../../frontend/tests/booking.live.spec.ts) | [ảnh](screenshots/g5/M08.png) | Nhập kho thật, mock history; unit/API biên +07:00, chưa hai biên ngày trên UI thật. |
| M09 | Nhân viên | [code](../../frontend/shared/src/pages/ManagementPages.tsx) | [test](../../frontend/tests/management.live.spec.ts) | [ảnh](screenshots/g5/M09.png) | Create/edit/status/reset/revocation/inactive và >20 record; còn đầy đủ field-error matrix. |

## Những việc còn mở

- Chưa đọc được Figma; chưa có kết luận fidelity. Máy quét USB/máy in và touch device vật lý chưa được kiểm trực tiếp.
- Contrast test kiểm control đại diện và trạng thái có ký hiệu, không tự chứng nhận WCAG toàn ứng dụng. Print test kiểm CSS/QR, chưa chứng minh mọi phân trang máy in.
- Các matrix nghiệp vụ chưa đủ trong cột cuối vẫn nằm trong `tasks/todo.md`; không đánh pass chỉ bằng mock. VNPay dùng callback test, chưa qua gateway sandbox bên ngoài.
- NFR timing là số đo request local trong manifest, không gồm thời gian chờ người dùng/gateway và không đại diện tải đồng thời production.

## Tài liệu đọc code

[Tổng quan walkthrough](../code-walkthrough/README.md), [frontend](../code-walkthrough/frontend.md), [backend](../code-walkthrough/backend.md), [ADR recovery](../decisions/007-booking-create-recovery.md).
