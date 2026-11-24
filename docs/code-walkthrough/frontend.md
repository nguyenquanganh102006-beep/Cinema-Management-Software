# Frontend walkthrough

Đối chiếu working tree ngày 23/11/2026. Các đường dẫn source bên dưới bắt đầu tại repository root qua liên kết tương đối.

## 1. Entry point và router

Mỗi portal có `src/main.tsx` và `src/App.tsx`: [Customer](../../frontend/customer/src/App.tsx), [Staff](../../frontend/staff/src/App.tsx), [Manager](../../frontend/manager/src/App.tsx). Component portal truyền role cố định vào [App dùng chung](../../frontend/shared/src/App.tsx).

[bootstrap.ts](../../frontend/shared/src/bootstrap.ts) chỉ khởi động MSW khi Vite ở DEV và `VITE_USE_MOCKS=true`. Sau bootstrap, React render router. `Portal` cho phép trang payment return hoạt động trước auth; các route còn lại được bọc theo thứ tự `AuthProvider → BookingFlowProvider → PortalLayout`.

| Input | Logic | Output |
|---|---|---|
| `role`, pathname | Chọn tập route theo CUSTOMER/STAFF/MANAGER | Page tương ứng trong layout |
| Path return VNPay | Render `PaymentReturnPage` ngoài AuthProvider | Trạng thái thanh toán công khai theo return đã xác thực |
| Path không khớp | `Navigate replace` về trang đầu theo role | `/movies`, `/pos` hoặc `/dashboard` |

Router hiện chuyển path lạ về trang đầu, chưa phải một màn 404 riêng. Cùng URL `/movies` nhưng Customer và Manager render hai page khác nhau.

## 2. Auth và HTTP boundary

[AuthProvider.tsx](../../frontend/shared/src/auth/AuthProvider.tsx) giữ `Session` trong React state; thêm `expiresAt` một lần lúc login/consume transfer. Refresh profile chỉ thay `user`, không gia hạn deadline. Login role khác tạo mã chuyển portal một lần qua backend; consume mã từ hash rồi xóa hash khỏi URL. Logout chỉ xóa session sau khi backend xác nhận thu hồi hoặc trả 401.

[api.ts](../../frontend/shared/src/api.ts) là boundary chung:

| Hàm | Input | Logic và output |
|---|---|---|
| `request<T>` | Path, method/body/token/signal | Gắn Bearer khi có, JSON encode, bỏ cookie, timeout 15s; lấy `data` hoặc `undefined` với 204 |
| `requestPage<T>` | Path danh sách, options | Chuyển envelope phân trang về `{data: T[], meta}`; shape không hỗ trợ sẽ báo lỗi |
| `requestAll<T>` | Path options và signal | Lặp page size 100 đến `totalPages`; kiểm tra trang trả về để tránh vòng lặp không tiến |
| `query` | Record giá trị query | Loại `undefined`/chuỗi rỗng, giữ `0` và `false` |

401 với authenticated request phát event `cinema:unauthorized`; AuthProvider thu hồi state và yêu cầu login. 403 giữ phiên để người dùng đọc lỗi quyền. Timeout/mạng không tự retry mutation. [AuthScreen](../../frontend/shared/src/auth/AuthScreen.tsx) hiển thị thông báo và `ApiError.details` cạnh field khi có.

## 3. Query state và thời gian

[useQuery](../../frontend/shared/src/hooks/useQuery.ts) nhận `key` và loader có AbortSignal; trả `{data?, loading, error?, refetch}`. Đổi key/refetch bỏ dữ liệu cũ và bắt đầu lượt đọc mới. Cleanup abort request và đánh dấu lượt đọc không còn active, nên response cũ không ghi đè state mới. Đây là query state đơn giản, chưa phải cache chia sẻ toàn ứng dụng.

[useServerCountdown](../../frontend/shared/src/hooks/useServerCountdown.ts) nhận `serverTime` và `until`; tính chênh lệch ban đầu rồi trừ thời gian monotonic từ `performance.now()`, cập nhật mỗi 250ms. Output là số giây không âm. Refresh serverTime giúp hiệu chỉnh countdown; backend luôn quyết định deadline cuối cùng.

[format.ts](../../frontend/shared/src/utils/format.ts) định dạng VND và giờ Việt Nam. Không dùng timezone máy người xem để chọn ngày nghiệp vụ.

## 4. Booking state và chọn ghế

[bookingReducer](../../frontend/shared/src/booking/bookingReducer.ts) nhận state `{showtime, selected, cart, hold, quote, booking, creation}` và action, trả state mới:

| Action | Logic chính | Output/state sau |
|---|---|---|
| `SHOWTIME` | Đổi suất thì xóa state luồng cũ | Suất mới với giỏ/ghế/hold rỗng |
| `SELECT` | Bỏ qua nếu đang có hold hoặc create intent | Selection mới, xóa quote/booking cũ |
| `CART` | Cập nhật giỏ khi không có create intent | CartLine `{itemId, quantity}[]` |
| `HOLD` | Dùng seatIds backend xác nhận | Hold mới, chọn đúng ghế server giữ |
| `QUOTE` / `BOOKING` | Nhận dữ liệu adapter | Giá hoặc đơn do server trả |
| `CREATE_INTENT` | Giữ nguyên BookingInput đang gửi trong memory | Cho readback sau mất response, khóa đổi giỏ/selection; BOOKING xóa intent |
| `RELEASE` | Xóa hold/selection/quote/booking | Có thể chọn lại |
| `EXPIRE` | Xóa hold/ghế/giỏ; chỉ giữ booking PAID và intent chưa rõ kết quả | Dừng thanh toán từ hold đã hết nhưng còn nút đọc lại lần tạo |
| `RESET` | Kết thúc/bắt đầu luồng | Initial state |

[BookingPage](../../frontend/shared/src/pages/BookingPages.tsx) tải suất, concessions và seat map. [SeatMap](../../frontend/shared/src/components/SeatMap.tsx) nhóm ghế theo hàng trong thứ tự response, hiển thị số ghế, trạng thái có nhãn và `aria-pressed`; chọn Sweetbox lấy cả cặp. Component chọn ghế này chưa dựng ô trống theo khoảng số như `PhysicalSeatLayout` ở Manager. [ConcessionPicker](../../frontend/shared/src/components/ConcessionPicker.tsx) khóa món hết hàng hoặc thiếu availability, chặn số lượng vượt mức tại lúc đọc. Nó không giữ tồn kho F&B.

[useSeatHold](../../frontend/shared/src/hooks/useSeatHold.ts) nối map và reducer:

1. Nhận showtimeId, đọc map qua `holdsApi.map`; poll 5s và đọc lại khi focus/visibility thay đổi.
2. `ownHolds` giúp phục hồi nhóm ghế của phiên hiện tại sau khi mất response giữ ghế.
3. `create/extend/release` có ref chặn hai lượt ghi cùng lúc; lỗi hiện thông báo rồi refetch map.
4. Token, holdUntil và serverTime từ backend; đổi generation/unmount bỏ response cũ.

API cụ thể nằm ở [holds.ts](../../frontend/shared/src/api/holds.ts), [pricing.ts](../../frontend/shared/src/api/pricing.ts), [catalog.ts](../../frontend/shared/src/api/catalog.ts). `catalogApi.showtimes/movieOptions/concessions` tải đủ page cho options; danh sách phim Customer dùng page riêng để hiển thị pagination.

## 5. Checkout và khôi phục

[CheckoutPage/CheckoutFlow](../../frontend/shared/src/pages/BookingPages.tsx) dùng state luồng nếu đã có; nếu không, render [CheckoutRecovery](../../frontend/shared/src/pages/CheckoutRecovery.tsx).

| Bước | Input | Logic | Output |
|---|---|---|---|
| Tạo đơn | showtimeId, holdToken, concessions, ageConfirmed khi cần | `bookingsApi.create`; backend chốt snapshot/tổng | Booking PENDING, bookingId được đưa vào URL/sessionStorage |
| Thu cash | bookingId, cashReceived, ageDocumentChecked | `posApi.cash`, sau đó đọc lại booking | PAID và chuyển receipt nếu thành công |
| Tạo VietQR | bookingId, ageDocumentChecked | `posApi.vietqr` | Thông tin ngân hàng/nội dung, Payment PENDING |
| Xác nhận VietQR | bookingId, checkbox kiểm tiền trên UI | `posApi.confirm`, đọc lại | PAID/receipt khi backend finalize thành công |
| Tạo URL VNPay | bookingId | `bookingsApi.vnpay`, kiểm tra protocol URL | Link mở gateway; chưa có vé |
| Kết quả ghi chưa rõ | bookingId | Khóa hành động phụ thuộc, đọc lại trạng thái | Cho tiếp tục theo trạng thái thực tế hoặc yêu cầu đọc lại |

Customer polling 3s khi booking PENDING, có cleanup khi unmount. Staff chỉ được gia hạn một lần khi chờ chuyển khoản và gần hết hạn; backend kiểm tra quyền/thời điểm. Không có PATCH/cancel pending vì backend chưa cung cấp contract đó.

Mất response tạo đơn: state `creation` giữ nguyên body, nút “Đọc lại kết quả tạo đơn” gửi lại body đó theo lựa chọn của người dùng. `BOOKING_ALREADY_EXISTS` chỉ có `details.bookingId` cho đúng phiên; adapter kiểm chuỗi ID canonical/safe integer rồi GET trạng thái. Snapshot đã lưu thắng giỏ đang thử gửi; metadata lấy từ showtimeId thực và PAID đi qua detail/ticket. Intent không ghi storage và vẫn tồn tại khi countdown hết; xem [ADR 007](../decisions/007-booking-create-recovery.md) về giới hạn xuyên phiên.

Recovery lấy ID từ query `booking` hoặc `cinema:pending:<userId>`. Backend xác nhận quyền đọc, sau đó frontend tải showtime và map để tìm own hold đúng tập ghế. Booking PAID điều hướng detail/receipt; booking hết hold hướng về lịch sử/chọn suất. Vì auth ở memory, reload cần đăng nhập lại; ID lưu trong trình duyệt không cấp quyền đọc đơn.

## 6. Adapter booking và vé

[bookings.ts](../../frontend/shared/src/api/bookings.ts) xử lý các DTO read/create/history/detail khác nhau:

- `normalizeBooking(raw, fallback?)` ghép metadata suất, chuyển giá về number, tách `plannedSeats` và `tickets`, giữ payment/refundStatus; fallback cung cấp ngữ cảnh có sẵn khi endpoint cơ bản chỉ trả ID.
- `normalizeTicket` yêu cầu mã, ID, status vé thật; chuyển `seatRow + seatNumber` thành seatLabel và QR payload thành dữ liệu hiển thị.
- `read` dùng `/bookings/{id}` cho trạng thái cơ bản, không suy luận phát hành vé từ dòng ghế dự kiến.
- `get` dùng `/me/bookings/{id}` hoặc `/pos/bookings/{id}`; PAID thiếu tickets thì tải endpoint tickets. Lỗi tải vé được truyền ra.
- `list` giữ metadata pagination và normalize từng booking.

[TicketView](../../frontend/shared/src/components/TicketView.tsx) tạo QR từ vé đã phát hành, hiển thị trạng thái và F&B snapshot. [print.css](../../frontend/shared/src/print.css) ẩn navigation/control khi in. BookingHistoryPage giữ bộ lọc trong URL; BookingDetailPage dùng route id để mở trực tiếp sau login.

## 7. Gate và hoàn vé

[StaffPages.tsx](../../frontend/shared/src/pages/StaffPages.tsx) chứa hai luồng:

| Luồng | Input | Logic | Output |
|---|---|---|---|
| Gate lookup | code, suất cửa | `posApi.lookup`, trả toàn bộ vé của booking hoặc một vé | Card ghế/phim/status; khóa khi khác suất/USED/CANCELLED |
| Gate validate | ticketCode, showtimeId, ageDocumentChecked theo vé | POST validate rồi GET vé đọc lại; không bỏ showtimeId khi lỗi | Vé USED và không cho dùng lại |
| Refund eligibility | Loại mã và code | Gửi đúng một bookingCode/ticketCode, map row/seatNumber/reasonCode | Các vé có eligible/refundableAmount |
| Manager approve | username, PIN, bookingId, ticketIds, reason, cashConfirmed | Dùng phiên Staff gọi `/manager-overrides`, xóa PIN sau submit | Token 64 hex, expiresAt, approvedRefundAmount trong dialog |
| Refund commit | Payload đã duyệt và token | Gửi `/refunds`; giữ token khi mất response để retry cùng yêu cầu | Refund amount hoặc yêu cầu duyệt lại khi token hết hiệu lực |

Modal native ngăn thay lựa chọn ngoài dialog. Đóng modal bỏ approval khỏi frontend. Không đưa PIN/token vào URL/storage. [refunds.ts](../../frontend/shared/src/api/refunds.ts) và [pos.ts](../../frontend/shared/src/api/pos.ts) là contract gọi backend, không fallback sang endpoint khác.

## 8. Manager CRUD, kho và giá

[ManagementPages.tsx](../../frontend/shared/src/pages/ManagementPages.tsx) có `ResourceEditor`, bảng và các page theo tài nguyên. `ResourceEditor` chuyển FormData thành DTO (number/checkbox/ID/datetime +07:00/components); giữ form khi lỗi và hiển thị details. Timeout ghi khóa nút Lưu, hướng đọc lại danh sách.

[management.ts](../../frontend/shared/src/api/management.ts) bỏ field ngoài DTO và dùng endpoint status riêng. Metadata lưu được nhưng status lỗi sẽ ném `PartialSaveError` chứa callback retry **chỉ status**; không gửi lại POST tạo mới. Sau retry phát `cinema:management-changed` để refetch bảng.

| Page | Input | Logic/output đáng chú ý |
|---|---|---|
| Movies/Auditoriums/Showtimes | Bộ lọc, form metadata/status | Bảng phân trang theo API tương ứng; suất lấy endTime từ server; conflict giữ input |
| SeatLayout | auditoriumId, ghế/cặp | [PhysicalSeatLayout](../../frontend/shared/src/components/PhysicalSeatLayout.tsx) theo vị trí; backend chặn đổi layout khi đã có lịch |
| Concessions | Món hoặc combo, components | Payload item/combo riêng; status gọi endpoint riêng |
| Inventory/RestockHistory | Keyword/status/page, quantity/note, item/user/from/to | Nhập kho, readback tồn; history chung hoặc từng món; biên ngày +07:00 |
| Staff | Profile, active, newPassword | Create yêu cầu password; edit không đổi username/password; reset chỉ khi API thành công |
| Pricing | Nhóm rule, amount, suất/ghế preview | [PricingPage](../../frontend/shared/src/pages/pricing/PricingPage.tsx) gọi quote thật; không sửa snapshot booking cũ |

## 9. Báo cáo và primitives

[ReportsPage](../../frontend/shared/src/pages/reports/ReportsPage.tsx) gọi [reportApi](../../frontend/shared/src/api/reports.ts) cho bảy loại endpoint; ranking gọi hai chiều ASC/DESC nên có tám vùng. Mỗi `Region` có useQuery, loading/error/retry độc lập. Kỳ từ ngày/tháng/range được đưa vào key để không tái dùng dữ liệu kỳ cũ. Adapter kiểm tra period và shape mảng cần thiết, không phải schema validator đầy đủ cho mọi field.

Output gồm revenue gross/refund/net/exception, breakdown vé/F&B, channels, ticket count, occupancy từng suất, ranking cao/thấp và inventory alerts hiện tại. Dùng `netRevenue` từ server, không trừ refund lần nữa. Mỗi response mang period/asOf riêng; tám request không có một transaction snapshot chung.

[PortalLayout](../../frontend/shared/src/layout/PortalLayout.tsx) quản lý navigation/title/skip link. [Feedback](../../frontend/shared/src/components/Feedback.tsx) xử lý skeleton/error/retry/empty; [ConfirmDialog](../../frontend/shared/src/components/ConfirmDialog.tsx) dùng native dialog, Escape đóng và trả focus về opener. [styles.css](../../frontend/shared/src/styles.css) chứa token/responsive/reduced motion; [design contract](../ui-ux/frontend-design-contract.md) ghi các quyết định và giới hạn đối chiếu Figma.

## 10. Bản đồ test

- Unit/component ở `shared/src/**/*.test.*`: adapter contracts, reducer, query cancellation, countdown, hold và seat map.
- `tests/*.mock.spec.ts`: Playwright intercept API qua [mocks/core.ts](../../frontend/shared/src/mocks/core.ts); dùng fixture đã biết để tái hiện lỗi.
- Mock approval/refund kiểm scope, token 64 hex, TTL 180 giây, IDs sắp xếp, reason trim và phiên Staff; retry sau commit trả cùng kết quả kể cả quá TTL. Mô phỏng không chứng minh row lock hay transaction PostgreSQL.
- Báo giá mock kiểm role Customer/Staff/Manager và danh sách ghế trước khi tính; ID ghế được sắp xếp và response dùng `showtimeId`, `seats`, `ticketTotal` như backend. Đây chỉ là một phần rà contract Q01, chưa chứng minh toàn bộ logic giá mock khớp backend.
- Adapter quản lý ghế chuyển cả resource `seats` khi sửa và `auditoriums/{id}/seats` sang DTO `row`, `number`, `type`; màn sơ đồ dùng `seats` cho cập nhật một ghế.
- `tests/auth.spec.ts`, `booking.live.spec.ts`, `g5.live.spec.ts`, `management.live.spec.ts`: backend/PostgreSQL thật trong môi trường test; G5 ký IPN local, không phải giao dịch gateway ngoài.
- Lệnh và bằng chứng nằm trong [testing/frontend.md](../testing/frontend.md); source walkthrough không tự chứng minh một màn đã nghiệm thu.
