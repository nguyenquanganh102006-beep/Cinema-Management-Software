# Bối cảnh triển khai Foundation

Ngày tổng hợp: 29/10/2026. Đây là hồ sơ thu thập yêu cầu để chuẩn bị code, chưa phải quyết định thay thế yêu cầu của nhóm.

## 1. Nguồn tài liệu và hiện trạng

Thư mục nguồn:

```text
C:\Users\GIGABYTE\OneDrive\Documents\document\2026.1\cnpm\Cinema management system -20261029T081552Z-1-001\Cinema management system
```

Repo hiện tại: `Cinema-Management-Software`, nhánh `feature/foundation`. Trước khi lập hồ sơ này, Git chỉ theo dõi `README.md`, commit `1aae6e4 Initial commit`; chưa có backend, frontend, migration hoặc cấu hình build.

Các nguồn chính, đường dẫn tính từ thư mục nguồn:

| Nguồn | Vai trò với Foundation |
|---|---|
| `G1/FUNCTIONAL REQUIREMENTS.docx` | FR-01 đăng ký, FR-02 login, FR-03 RBAC/điều hướng, FR-04 logout; FR-05 quản lý Staff là chức năng phụ thuộc Auth |
| `G1/NON-FUNCTIONAL REQUIREMENTS.docx` | Security, authentication, authorization, integrity, error, audit, testability, recoverability, timezone, documentation |
| `G1/BUSINESS RULES.docx` | Ràng buộc nghiệp vụ và dữ liệu cần giữ khi dựng schema/seed |
| `G1/Tổng quan.docx`, `G1/Mô tả bài toán.docx` | Phạm vi v1.0, ma trận actor và toàn cảnh hệ thống |
| `G2/use case_.docx` | UC-01 Register, UC-02 Login, UC-03 Logout, UC-04 Manage Staff; mapping FR/UC |
| `G2/Specification (UC-12, 13, 17, SUC-01, 09).docx` | Các luồng sau Foundation; ownership, transaction, timeout và Manager Override |
| `G2/diagram/SMD/*.png` | Trạng thái seat/payment/ticket; đã đối chiếu các hình này với schema |
| `G3/script_cms.sql`, `G3/Mô tả.docx`, `G3/rela_diag_cms.png` | Schema SQL, data dictionary, ERD và seed mẫu |
| `G3/backup_cms.backup` | Đã đọc danh mục archive bằng `pg_restore --list`; bản dump CUSTOM của `cinema_db`, PostgreSQL 17.11, tạo 04/10/2026; chưa restore vào database |
| `G4/Cinema_Backend_Layered_Architecture_Plan_v3.md` | Nguồn chi tiết nhất về stack, layer, contract, JWT, lỗi, migration, môi trường và tuần Foundation |
| `G4/Cinema_Backend_5_Muc_Layered_Architecture_v3.docx` | Bản kiến trúc rút gọn theo 5 layer, API và security |
| `G5/Màn hình bắt buộc_.docx`, `G5/Wireframe link_.docx` | Login dùng chung component, register Customer, giao diện theo actor, link wireframe |
| `G6/Quy trình git.docx` | Cấu trúc repo, nhánh và workflow PR |
| `Tiến độ, phân công.xlsx` | Sprint A Foundation: Quang Anh, trạng thái chưa bắt đầu; frontend: Chính Minh |
| `plan ban đầu/Ke_hoach_trien_khai_Cinema_Management_System.docx` | Mục 9.1 nêu scope Sprint A cả frontend/backend, migration/seed, exception, auth/guard, layout/navigation |
| `plan ban đầu/Phân tích bài toán.docx` và 3 file PPTX | Bối cảnh, roadmap cũ và thay đổi phạm vi; không mặc định lấy nội dung cũ ghi đè G1/G4 |

Nội dung text của Word, PowerPoint và workbook đã được trích xuất để tra cứu trong `%TEMP%\cinema-foundation-context`. Đây là bản đọc tạm, không phải nguồn chuẩn để commit. Bảng Excel đã đọc cả 6 sheet; ngày dạng serial chưa được dùng để suy ra deadline Foundation vì các ô lịch Sprint A đang trống.

Giới hạn đọc: chưa truy cập wireframe Figma bên ngoài, chưa mở nội dung các link Drive trong bảng phân công, chưa rà từng hình sequence/activity của các sprint sau. Các tài liệu đó không được xem là đã kiểm chứng ngoài bản local. Không chạy SQL nguồn và không restore backup.

## 2. Phạm vi hệ thống hiện hành

- Backend Java Spring Boot, PostgreSQL, REST/OpenAPI; kiến trúc Layered Monolith.
- Ba UI React: Manager Dashboard, Staff POS, Customer Portal; Customer phải đăng nhập để booking.
- Ba role `MANAGER`, `STAFF`, `CUSTOMER`; Payment Gateway là actor tích hợp, không phải role đăng nhập.
- v1.0 hiện hành có F&B/combo/tồn kho, POS Cash/VietQR, Online VNPay Sandbox, soát vé, partial refund tại POS và reports.
- Ngoài v1.0: voucher, nguyên vật liệu/BOM chuyên sâu, tự động hoàn tiền online.
- Tài liệu G4 giả định 4 backend developer trong 6 tuần; bảng phân công thực tế chia theo Sprint A–F. Dùng bảng phân công để xác định owner, không tự thay bằng mô hình 4 người trong G4.

## 3. Phạm vi module 1 Foundation

Yêu cầu trực tiếp từ Sprint A và tuần 1 G4:

1. Khởi tạo frontend/backend và cấu trúc repository.
2. Khởi tạo Spring Boot, kết nối PostgreSQL.
3. Flyway migration dựng được database rỗng, seed tối thiểu phục vụ đăng nhập/role.
4. Format lỗi chung, Bean Validation và GlobalExceptionHandler.
5. Authentication, JWT Access Token, BCrypt và RBAC backend.
6. Auth/role guard frontend, layout chung và navigation theo role.
7. Swagger/OpenAPI chạy được, có Bearer authentication.
8. Base audit fields, cấu hình môi trường và hướng dẫn setup.
9. Ghi nhận/xử lý các xung đột requirement và schema liên quan Foundation.

Các API Auth cần có theo G4: register, login, logout, me. CRUD Staff được xếp vào tuần 2 Master Data của G4; Foundation cung cấp User/role/security để sprint sau triển khai. Manager Override đầy đủ thuộc luồng Refund; Foundation cần dự trù PIN hash và cách phân biệt override token với access token, chưa mặc định phải code toàn bộ luồng duyệt hoàn tiền.

Không tính Movie CRUD, room/seat CRUD, showtime, hold, checkout, VNPay, refund hoặc báo cáo nghiệp vụ là phần hoàn tất của Foundation. Schema cho các phần này có thể được chuẩn bị trước nhưng không đồng nghĩa đã triển khai module.

## 4. Kiến trúc và cấu trúc repository

Theo G6:

```text
backend/src/main/
backend/src/test/
frontend/customer/
frontend/staff/
frontend/manager/
docs/requirements/
docs/use-cases/
docs/database/
docs/architecture/
docs/api/
docs/ui-ux/
docs/decisions/
scripts/setup/
scripts/testing/
compose.yaml
.env.example
.gitignore
README.md
```

Base package: `com.cinema.cms`; main class: `CinemaApplication`.

G4 quy định 5 package chính: `controller`, `service`, `dto`, `repository`, `entity`; không chia package theo feature. `SecurityConfig`, `JwtService`, scheduler/integration đặt trong service; `GlobalExceptionHandler` đặt trong controller. Controller nhận request/validate/lấy user/gọi Service/trả DTO; Service sở hữu business rule và transaction; Repository chỉ persistence/query/locking. Không trả Entity trực tiếp.

G1 NFR có liệt kê thêm security/exception/config; G4 là phương án kiến trúc cụ thể hơn. Đề xuất theo 5 package G4 khi code và ghi quyết định nếu đổi.

## 5. Authentication và phân quyền

| Method | Endpoint đầy đủ | Quyền | Kết quả cần có |
|---|---|---|---|
| POST | `/api/v1/auth/register` | Public | Tạo Customer, hash password, chặn trùng định danh |
| POST | `/api/v1/auth/login` | Public | Kiểm tra credentials/status, cấp access token và user/role |
| POST | `/api/v1/auth/logout` | Authenticated | Body rỗng, trả 204; cơ chế thu hồi đang có xung đột |
| GET | `/api/v1/auth/me` | Authenticated | Thông tin user hiện tại, không lộ hash/password/PIN |

FR-01: username/email, họ tên, password, phone nếu yêu cầu; tự gán CUSTOMER/ACTIVE. Client không được tự chọn role đặc quyền.

FR-02: cho login bằng username hoặc email; từ chối LOCKED/INACTIVE; sai credentials không cấp token. Ví dụ request G4 chỉ có `username` và `password`, nên cần quy ước field `username` có nhận email hay đổi sang một field đăng nhập thống nhất.

JWT tối thiểu có `sub` = user ID, `role`, `iat`, `exp`; TTL được đề xuất 15–30 phút, ví dụ `expiresIn=1800`. Chỉ Access Token, không mặc định refresh token. Secret lấy từ môi trường; không có password/PIN trong token/log. TTL và signing algorithm chưa có giá trị bắt buộc trong tài liệu.

Ví dụ LoginResponse G4: `accessToken`, `expiresIn`, `user: {id, username, role}`. Ví dụ này không có wrapper `data`, trong khi phần success convention có wrapper: cần thống nhất khi code.

RBAC phải kiểm tra ở backend, ví dụ `@PreAuthorize("hasRole('MANAGER')")`. Không chỉ ẩn menu frontend. User ID/role của nghiệp vụ phải lấy từ security context, không tin client.

| Role | Phạm vi |
|---|---|
| MANAGER | Master data, lịch chiếu, pricing, Staff, F&B/inventory, approve refund, reports |
| STAFF | POS, hold, Cash/VietQR, lịch sử POS, in/soát vé, tiếp nhận refund |
| CUSTOMER | Booking online của mình, hold của mình, payment online, e-ticket/history của mình |

Đề xuất Foundation: kiểm tra trạng thái tài khoản khi xác thực request để tài khoản vừa bị khóa không tiếp tục dùng JWT cũ; đây là chi tiết triển khai cần ghi rõ, tài liệu chưa mô tả cách thực hiện.

## 6. Contract dùng chung

Base API: `/api/v1`. Tên resource, JSON camelCase; không dùng `/createMovie`, `/getMovies`.

Error DTO bắt buộc theo G4:

```json
{
  "timestamp": "2026-10-29T15:00:00+07:00",
  "status": 400,
  "code": "VALIDATION_ERROR",
  "message": "Dữ liệu không hợp lệ",
  "details": {},
  "path": "/api/v1/auth/register"
}
```

`VALIDATION_ERROR` ở ví dụ trên là tên đề xuất; G4 chưa chốt tên code validation. Code auth đã có: `AUTH_INVALID_CREDENTIALS`, `AUTH_TOKEN_EXPIRED`, `AUTH_TOKEN_INVALID`, `ACCOUNT_INACTIVE`, `ACCOUNT_LOCKED`, `ACCESS_DENIED`.

Status: 200 đọc/cập nhật, 201 tạo resource, 204 không body, 400 validation, 401 thiếu/sai/hết hạn token, 403 thiếu quyền, 404 không tồn tại, 409 conflict, 422 business rule, 500 lỗi bất ngờ. 429 chỉ nếu triển khai rate limit. 502/503 cho phụ thuộc bị lỗi.

Validation: Bean Validation (`@NotBlank`, `@NotNull`, `@Email`, `@Size`, `@Positive`); tách lỗi format và lỗi nghiệp vụ. Security entry point/access denied handler cũng cần cùng format lỗi; ControllerAdvice đơn lẻ không đủ xử lý mọi lỗi trong security filter.

Success convention đề xuất G4: object `{data: ...}`; list `{data: [], meta: {page, size, totalElements, totalPages}}`. Trang mặc định 0, size ví dụ 20; max 100 được ghi cụ thể cho lịch sử nhập kho, chưa phải quy tắc đã chốt cho mọi endpoint.

Swagger: `/swagger-ui/index.html`; OpenAPI: `/v3/api-docs`; dùng springdoc-openapi. Mỗi endpoint mô tả summary, role, request/response schema, status/error code và business rule.

## 7. Database và migration

SQL hiện có 15 bảng: `users`, `auditoriums`, `seats`, `movies`, `pricing_rules`, `showtimes`, `showtime_seats`, `concession_items`, `combo_items`, `concession_inventory`, `bookings`, `booking_concessions`, `tickets`, `payments`, `refund_records`.

User hiện có:

| Cột | Kiểu/ràng buộc |
|---|---|
| user_id | BIGSERIAL PK |
| username | VARCHAR(50), NOT NULL, UNIQUE |
| full_name | VARCHAR(100), NOT NULL |
| email | VARCHAR(150), NOT NULL, UNIQUE |
| phone | VARCHAR(20), NOT NULL, UNIQUE |
| password_hash | VARCHAR(255), NOT NULL |
| role | PostgreSQL enum user_role: CUSTOMER, STAFF, MANAGER |
| status | enum account_status: ACTIVE, INACTIVE, LOCKED |
| created_at | TIMESTAMP, default CURRENT_TIMESTAMP, chưa NOT NULL |

Chưa có `manager_pin_hash`, `updated_at` và trường thu hồi JWT. Không có bảng roles riêng: seed role hiện là enum, không tự thêm bảng role/permission nếu không cần.

Flyway tại `backend/src/main/resources/db/migration`. G4 gợi ý tách enum/users/movies/room-seat/showtime/pricing/concessions/booking-payment-ticket/refund/indexes/inventory-history. Với migration đã chạy ở môi trường chung, bổ sung migration mới, không sửa lịch sử.

SQL bắt đầu bằng `DROP SCHEMA IF EXISTS public CASCADE`: không đưa nguyên dòng này vào migration khởi động. Tách DDL, cấu hình giá tối thiểu và demo fixtures. Không tự chạy script vào database người dùng.

Các thiếu hụt cần lập migration/decision:

- `movies`: thiếu description, genre, country, language, director, poster, trailer theo FR.
- `auditoriums`: thiếu trạng thái active/inactive.
- `users`: thiếu Manager PIN hash và audit fields theo nhu cầu đã nêu.
- Thiếu bảng lịch sử nhập kho `inventory_transactions` hoặc bảng tương đương; cần quantity, before/after, item, manager, type, note, server time.
- Thiếu lưu age confirmation/verification audit ở Booking/POS/Gate.
- Booking có channel POS/ONLINE và Payment đã có paid_at: không cần tạo trùng field chỉ vì G4 dùng tên Java khác.
- DB payment method là `GATEWAY_ONLINE`, G4 checklist dùng `VNPAY`; cần mapping/decision rõ.
- Ghế thường DB là `STANDARD`, FR gọi Normal; phòng `3D` không dùng trực tiếp làm identifier enum Java, cần mapping.
- Cần bổ sung/check các constraint còn thiếu như duration > 0, giá không âm, seat number/seat count hợp lệ khi chốt schema.
- `showtimes` có index theo movie/time nhưng cần xem thêm auditorium/time cho conflict query.

Seed nguồn: 2 Manager, 3 Staff, 10 Customer (có inactive/locked), 12 phòng x 10 ghế, 15 phim, 20 suất, pricing, F&B/combo, booking/payment/ticket/refund mẫu. Tất cả user dùng cùng một BCrypt string, chưa xác minh password rõ tương ứng; Manager PIN chưa có. Phải tạo tài khoản demo có credential đã kiểm chứng khi code.

Seed giao dịch có dấu hiệu không nhất quán: Ticket CANCELLED còn seat BOOKED; một số Ticket VALID chưa được đánh BOOKED; thông tin mã vé gợi ý suất/ghế khác FK; dữ liệu Cash/VietQR và channel cần rà lại. Không dùng nguyên seed này làm bằng chứng checkout/refund đúng.

## 8. Giá chuẩn cần bảo vệ khi seed

G1 FR-15 và BR-01–06: base 70.000; phòng 3D +20.000, IMAX +40.000; VIP/Sweetbox +15.000 mỗi ghế; cuối tuần T6–CN HOẶC sau 18:00 +10.000 đúng một lần. Sweetbox hai ghế, tổng phụ thu 30.000/cặp.

SQL seed khác: IMAX base 120.000; Sweetbox +20.000/ghế; weekend +15.000; có holiday +20.000 ngoài rule G1. Ngoài ra có cả base riêng cho 3D/IMAX và room surcharge, dễ cộng trùng. Foundation chưa cần code PricingService nhưng seed/migration phải tránh đóng cứng các giá sai này.

## 9. Frontend và môi trường

Sprint A có login, Customer register, role guard, auth state, logout, layout/navigation theo role; loading/error/empty state tối thiểu. G5 yêu cầu dùng chung component login giữa ba UI. Chưa có quyết định dùng npm workspace/shared package hay copy component; đề xuất dùng phần auth/UI chia sẻ để không lệch behavior.

Local theo G4:

| Thành phần | Cổng |
|---|---|
| Manager React | 3001 |
| Staff React | 3002 |
| Customer React | 3003 |
| Spring Boot | 8080 |
| PostgreSQL | 5432 |

React + Vite xuất hiện trong kế hoạch ban đầu như đề xuất; G4 chốt React, chưa chốt JS/TypeScript, package manager hoặc version. Java, Spring Boot, PostgreSQL, Maven/Gradle version cũng chưa chốt. Không gọi một lựa chọn version là yêu cầu tài liệu.

Env G4: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`; phần sau dùng `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET`, `VNPAY_PAY_URL`, `VNPAY_RETURN_URL`, `VNPAY_IPN_URL`, `CINEMA_BANK_ACCOUNT`, `CINEMA_BANK_NAME`. Có `application.yml`, `application-local.yml`, `.env.example`; không commit secret. Spring Boot cần cơ chế load env rõ, không mặc định đọc file `.env` chỉ vì file tồn tại.

Backend/server time là nguồn thời gian nghiệp vụ. Đề xuất timezone nghiệp vụ `Asia/Ho_Chi_Minh`, định dạng API ISO 8601 có offset; SQL hiện là TIMESTAMP không timezone, cần chốt quy ước lưu trữ trước migration.

CORS cần cho đúng ba origin frontend local. Cách lưu token phía frontend, CSRF policy, token signing, độ dài password/PIN, username normalization và case sensitivity chưa được tài liệu quyết định.

Khảo sát máy: có `C:\Program Files\PostgreSQL\18`, đã dùng binary pg_restore tại đây để đọc archive; command lookup hiện không tìm thấy java/mvn/docker/node/npm/psql/pg_restore trong PATH. Điều này không chứng minh máy chưa cài các runtime ở nơi khác. Chưa cài đặt hoặc thay đổi cấu hình máy.

## 10. Các điểm cần quyết định trước hoặc trong lúc code

| Điểm | Bằng chứng/xung đột | Đề xuất, chưa được coi là nhóm đã chốt |
|---|---|---|
| Logout | FR-04 cấm dùng lại phiên; G4 stateless chỉ client xóa token | Để đáp ứng FR-04 đầy đủ, dùng denylist persistent theo jti/token hash tới exp; nếu chọn stateless phải ghi rõ phạm vi được chấp nhận |
| Frontend scope | Sprint A có frontend/layout; G4 tuần 1 chỉ backend; bảng giao frontend cho Chính Minh | Xác định phần skeleton/auth dùng chung thuộc bàn giao Foundation và phối hợp owner frontend |
| Source precedence | Plan cũ loại F&B/VNPay khỏi v1, G1/G4 đưa vào v1 | Dùng G1 cho nghiệp vụ và G4 v3 cho kỹ thuật làm baseline đề xuất; không suy đoán mọi chỗ mới hơn đều đúng |
| Success wrapper | G4 cho phép bỏ wrapper, login example không wrapper | Chốt một kiểu cho Auth và các sprint sau |
| Phone | FR-01 tùy cấu hình; SQL NOT NULL UNIQUE | Chốt bắt buộc/optional và response validation tương ứng |
| Token/account changes | JWT cũ có thể còn role/status cũ | Xác định kiểm tra account trong request và chính sách đổi password/khóa tài khoản |
| Ticket state | BR/diagram VALID/CANCELLED; SQL/G5 có USED | Đề xuất VALID/USED/CANCELLED để ngăn quét lại, ghi decision trước module Ticket |
| Late online payment | SUC-09 cho bán nếu ghế còn trống sau expiry; G1 timeout không phát hành | Chốt exception workflow trước Payment; enum hiện có EXCEPTION_PENDING_DECISION |
| Net revenue | G1 ghi valid ticket revenue + F&B - refund, có nguy cơ trừ hoàn hai lần | Chốt gross/refund/net trước Reports; luôn dựa snapshot/payment |
| Manager Override | Popup Staff cho Manager nhập PIN; API role ghi MANAGER | Cần flow xác thực credential Manager trong session Staff, không yêu cầu Staff logout/đổi access token |
| UML | SUC-01 text hậu điều kiện copy từ refund; seat diagram ghi timeout tổng 7 phút nhưng chú thích sau gia hạn 4 phút; Ticket diagram không có USED | Không tự chuyển các lỗi biên tập thành yêu cầu code |

## 11. Checklist nghiệm thu Foundation

- [ ] Checkout repo từ máy khác, setup theo README, backend và ba UI khung chạy được.
- [ ] Database rỗng được dựng bằng Flyway; chạy lại không áp migration/seed trùng.
- [ ] Hibernate validate mapping với PostgreSQL, không dùng ddl-auto update làm nguồn schema.
- [ ] Seed login được ít nhất Manager/Staff/Customer với password đã biết; hash không plaintext.
- [ ] Register chỉ tạo CUSTOMER, chặn trùng và dữ liệu lỗi, không lộ hash.
- [ ] Login username/email đúng; password sai và account LOCKED/INACTIVE bị từ chối.
- [ ] `/auth/me` dùng JWT hợp lệ; token sai signature/hết hạn/thiếu bị 401.
- [ ] Role sai vào API hạn chế bị 403; frontend guard điều hướng đúng ba UI.
- [ ] Logout 204, frontend clear auth; kiểm chứng behavior reuse token đúng quyết định đã chọn.
- [ ] Lỗi validation/security/business/unexpected có chung format và không lộ stack trace/secret.
- [ ] Swagger/OpenAPI mở được, dùng Bearer thử API.
- [ ] CORS hoạt động cho ba UI, cấu hình qua env rõ ràng, không commit secret.
- [ ] Có test tích hợp PostgreSQL cho migration/auth/unique constraint; test JWT và role thật, không chỉ mock toàn bộ security.
- [ ] Có docs setup, contract và decision; các limitation được ghi đúng.

Các kiểm thử hold/stock/payment/refund concurrency nằm ở sprint sau, không dùng để tuyên bố Foundation đã hoàn tất toàn bộ hệ thống.

## 12. Thứ tự triển khai đề xuất

1. Chốt các lựa chọn trực tiếp ảnh hưởng Auth/schema: logout, phone, response wrapper, runtime/build version.
2. Dựng repo skeleton, env và PostgreSQL local.
3. Chuyển schema thành migration có version, sửa các sai lệch đã xác định; tách demo seed.
4. User entity/repository, audit/time, DTO/validation/error chung.
5. BCrypt, JWT, authentication filter, RBAC và bốn Auth API.
6. Swagger và kiểm thử API/migration/auth trên PostgreSQL.
7. React skeleton/login/register/auth guard/layout/navigation cho ba role.
8. Kiểm tra setup từ đầu, cập nhật README, bàn giao contract cho Master Data và frontend owner.

Workflow G6: cập nhật từ develop, code feature/foundation, test và mở PR, người khác review/build/test, merge develop. Chưa thực hiện fetch/pull/merge/commit/push trong đợt thu thập này.
