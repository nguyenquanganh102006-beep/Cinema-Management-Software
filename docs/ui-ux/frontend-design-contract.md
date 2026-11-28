# Frontend design contract

Ngày chốt: 19/11/2026. Phạm vi: Customer, Staff POS và Manager.

## Nguồn và quyết định

- Yêu cầu màn hình: `docs/plan_implement/G5/Màn hình bắt buộc_.docx` và phần G1–G4 được dẫn trong `tasks/plan.md`.
- Wireframe Figma chưa đọc được trong phiên này; contract hiện dựa trên yêu cầu G5, giao diện/code hiện tại và ảnh khảo sát local. Không tuyên bố đã khớp Figma.
- Giữ React + TypeScript + Vite, router và cấu trúc ba portal hiện có.
- Giữ xanh rêu/xanh lá hiện tại và Be Vietnam Pro để không đổi nhận diện hoặc phụ thuộc font trước khi có đối chiếu thiết kế. Đây là ngoại lệ có chủ đích so với đề xuất Manrope trong skill landing-page-design.
- Chuẩn hóa màu, chữ, khoảng cách và bán kính qua CSS custom properties khi sửa từng lát; ưu tiên semantic token cho nội dung, viền, trạng thái, focus và hành động.

## Nguyên tắc theo portal

| Portal | Công việc chính | Bố cục và hành động ưu tiên | Responsive |
|---|---|---|---|
| Customer | Tìm phim, chọn suất/ghế/F&B, thanh toán, xem vé | Tiêu đề phim và suất rõ; tổng tiền và bước tiếp theo luôn dễ tìm; không tự xác nhận thanh toán từ return URL | Mobile-first; CTA và summary không bị đẩy khỏi màn hình; ghế cuộn trong vùng riêng |
| Staff | Bán vé, thu tiền, soát vé, hoàn vé | Tối ưu thao tác quầy; booking/ghế và tóm tắt tiền cùng luồng; cảnh báo thời gian và xác nhận tiền mặt bằng nội dung rõ | Desktop-first; vùng ghế/bảng cuộn riêng; không che hành động chính ở màn hẹp |
| Manager | Quản lý dữ liệu, tồn kho, nhân viên, báo cáo | Bộ lọc và phân trang nằm cạnh dữ liệu; phân biệt gross/refund/net; báo cáo có kỳ và nguồn; sơ đồ ghế theo vị trí vật lý | Desktop-first; bảng/sơ đồ cuộn trong vùng riêng; form dùng một cột trên màn hẹp |

## Hành vi dùng chung

- Mỗi trang có tiêu đề mô tả đúng tác vụ, điều hướng chỉ báo mục hiện tại và skip link tới nội dung chính.
- Form có nhãn nhìn thấy được, giữ dữ liệu khi API trả lỗi, khóa nút khi đang gửi và chỉ thông báo thành công sau xác nhận từ API.
- Danh sách có trạng thái tải, rỗng và lỗi kèm hướng xử lý; thao tác nguy hiểm cần xác nhận có thể hủy bằng Escape.
- Focus nhìn thấy rõ; dialog mở focus nội dung, giữ thao tác trong dialog theo native dialog, và trả focus về phần tử mở dialog khi đóng.
- Màu không phải dấu hiệu duy nhất cho trạng thái. Văn bản thường có độ tương phản tối thiểu 4.5:1, chữ lớn và thành phần giao diện tối thiểu 3:1.
- Kiểm tra kích thước 320, 390, 768, 1024 và 1440 CSS px; tôn trọng `prefers-reduced-motion`.
- Ngày nghiệp vụ trình bày theo `Asia/Ho_Chi_Minh`; tiền theo VND; giá, trạng thái, quyền và thời hạn lấy từ backend.

## Trạng thái cần thể hiện

Mỗi luồng đọc/ghi xử lý tải, thành công, không có dữ liệu, lỗi có thể thử lại, quyền bị từ chối và kết quả chưa rõ sau timeout khi phù hợp. Mutation dùng đúng một contract. Khi kết quả ghi không rõ, đọc lại trạng thái hoặc gửi lại cùng idempotency token và payload theo contract; không thử một payload khác.

## Thông số triển khai và review 23/11/2026

| Nhóm | Giá trị/nguồn trong `shared/src/styles.css` |
|---|---|
| Bề mặt | canvas `#f5f7f6`, surface `#fff`, ink `#182222`, muted `#59645f`, border bề mặt `#d6ded9`, viền control `#738579` |
| Hành động | primary `#1c604d`, hover `#164c3e`, focus `#126c52` |
| Phản hồi | error `#8f271e` trên `#fff0ed`, success `#20573b` trên `#edf6ef`, warning `#6b4200` trên `#fff1cf` |
| Chữ | Be Vietnam Pro self-host; scale token 12/14/16/18/20/24/30px; page title 30px desktop, 24px mobile |
| Spacing | Token 4/8/12/16/24/32px; layout reserve/seat target là kích thước chức năng |
| Radius | control 8px, surface 12px |
| Tương tác | Target chính tối thiểu 44px; focus outline 3px; hover/pressed/disabled theo CSS; reduced motion tắt animation |

Giữ ngoại lệ Be Vietnam Pro và bố cục tác vụ G5; không thêm section/animation marketing vào POS/Manager. Các giá trị legacy còn tồn tại trong stylesheet chưa đồng nghĩa toàn bộ CSS đã được chuẩn hóa token.

Customer/Staff chọn ghế trên màn hẹp: summary chi tiết ở trong flow; thanh hành động dưới màn hình chỉ chứa số ghế, tạm tính và CTA. Có vùng đệm dưới nội dung để cuộn tới mọi input, không dùng cả summary cao làm sticky phủ F&B. Desktop dùng summary cạnh nội dung khi đủ rộng. Header bước chọn ghế/thanh toán/receipt mang tên tác vụ cụ thể.

Màn đại diện và job/CTA/state:

| Màn | Mục tiêu/CTA | Trạng thái phục hồi |
|---|---|---|
| Customer catalog/detail | Tìm phim → chọn suất | Skeleton/card rỗng/lỗi retry, poster fallback, suất không hợp lệ có đường quay lại |
| Customer/Staff ghế + F&B | Chọn nhóm ghế, kiểm tạm tính → giữ ghế/tiếp tục | Stock chưa biết/hết hàng khóa input; hold/conflict có refresh map; hết hạn chọn lại |
| Checkout | Kiểm tổng/tuổi → cash, QR hoặc VNPay | Busy chặn gửi lặp; kết quả chưa rõ đọc lại; pending sau reload khôi phục theo booking ID |
| Gate/refund | Kiểm đúng suất/vé hoặc duyệt hoàn có giới hạn | Sai suất/tuổi/PIN/expiry giữ ngữ cảnh; modal Escape/return focus; token không lưu storage |
| Manager list/form | Lọc/chọn tài nguyên → lưu/nhập kho/reset | Empty/error có retry, form giữ input; partial status chỉ retry status |
| Manager reports | Chọn kỳ → đọc số liệu và drill-down kho | Từng vùng loading/error/retry, không trừ refund lại, kỳ/asOf rõ |

Kiểm thử tự động viewport/keyboard/print ở `frontend/tests/accessibility.mock.spec.ts`; ảnh ở `docs/testing/screenshots/responsive/`. Review ảnh đã phát hiện summary che F&B và tiêu đề chung chung, có test tái hiện rồi sửa. Phạm vi test là màn đại diện; nghiệm thu từng trạng thái của đủ 27 màn vẫn theo audit. Chưa đối chiếu Figma.
