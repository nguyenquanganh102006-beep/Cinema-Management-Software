# Cinema Management Software

Hệ thống quản lý và đặt vé rạp chiếu phim: Spring Boot + PostgreSQL, ba giao diện React cho Manager, Staff và Customer.

Foundation có đăng ký Customer, login bằng username/email, JWT/RBAC, logout thu hồi token, Flyway, Swagger và giao diện auth/layout. Các trang nghiệp vụ sau đăng nhập là placeholder; chưa có CRUD phim/phòng, lịch chiếu, bán vé hoặc thanh toán.

## Công cụ

- JDK 21 và JAVA_HOME.
- Node.js 22.12+; npm 11.10.1 (npm 10 gặp lỗi resolve optional peers với dependency hiện tại).
- Docker Compose hoặc PostgreSQL 17/18 với database mới riêng.
- Maven 3.9.12 được wrapper tải tự động.

## Chạy trên Windows PowerShell

Từ thư mục gốc:

```powershell
powershell -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\setup\Initialize-LocalEnvironment.ps1
docker compose up -d --wait
powershell -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\setup\Start-Backend.ps1
```

Script tạo `.env` với credential ngẫu nhiên; mở file để xem DEMO_PASSWORD, không commit. PostgreSQL Compose dùng localhost:5433 để tránh đụng PostgreSQL có sẵn ở 5432. Backend: http://localhost:8080; Swagger: http://localhost:8080/swagger-ui/index.html.

Nếu dùng PostgreSQL cài sẵn, tự tạo database rỗng riêng và sửa DB_URL/DB_USERNAME/DB_PASSWORD trong `.env`; bỏ bước Docker. Không trỏ vào database có dữ liệu đang dùng.

Trong terminal khác:

```powershell
cd frontend
npm ci
npm run dev:manager
```

Mở thêm hai terminal trong frontend để chạy `npm run dev:staff` và `npm run dev:customer`.

| Giao diện | URL | User local |
|---|---|---|
| Manager | http://localhost:3001 | manager01 |
| Staff POS | http://localhost:3002 | staff01 |
| Customer | http://localhost:3003 | customer01 |

Ba user dùng DEMO_PASSWORD trong `.env`, chỉ được tạo khi profile local chạy lần đầu. Thay biến này không tự reset tài khoản đã tồn tại. DEMO_MANAGER_PIN được hash cho Manager, chưa có API Override.

Frontend gọi http://localhost:8080/api/v1. Muốn đổi, copy `.env.example` của từng app thành `.env.local`, chỉnh VITE_API_URL và restart Vite. Backend CORS đọc CORS_ALLOWED_ORIGINS. Địa chỉ chuyển portal được cấu hình tại backend bằng PORTAL_MANAGER_URL, PORTAL_STAFF_URL, PORTAL_CUSTOMER_URL; mặc định localhost:3001/3002/3003. Địa chỉ ngoài loopback phải dùng HTTPS.

Linux/macOS: export các biến trong `.env`, chạy `./mvnw spring-boot:run` trong backend và npm như trên. Spring Boot không tự đọc `.env`; Start-Backend.ps1 thực hiện load biến trên Windows. Các lệnh PowerShell trên chỉ chọn RemoteSigned cho tiến trình đang chạy, không thay đổi execution policy toàn máy. Nếu máy được tổ chức quản lý không cho phép, load biến thủ công và chạy Maven trực tiếp.

## Phiên đăng nhập

- Token chỉ ở memory; reload cần login lại, không có refresh token.
- JWT mặc định 30 phút, mỗi request đọc role/status hiện hành từ DB.
- Logout thu hồi đúng token hiện tại trong PostgreSQL, kể cả sau restart; các phiên khác giữ hiệu lực.
- Logout lỗi mạng giữ phiên để retry và thông báo chưa xác nhận thu hồi.
- Register luôn CUSTOMER/ACTIVE, không nhận role tự chọn. Backend kiểm tra quyền độc lập UI.
- Đăng nhập ở portal khác role sẽ tự chuyển sang portal đúng, không cần login lần hai. Hệ thống dùng mã chuyển phiên một lần có hạn 30 giây; không đưa JWT hoặc password vào URL.
- Default profile không seed demo và tắt Swagger. Chỉ dùng local khi phát triển.

## Build và test

Backend mặc định dùng Testcontainers PostgreSQL, cần Docker đang chạy:

```powershell
cd backend
.\mvnw.cmd verify
```

Không có Docker thì cung cấp database test riêng, rỗng ở lần đầu. Test sẽ ghi user và revoke token:

```powershell
$env:DB_TEST_URL = 'jdbc:postgresql://localhost:5432/cinema_foundation_test'
$env:DB_TEST_USERNAME = 'your-test-db-user'
$env:DB_TEST_PASSWORD = 'your-test-db-password'
.\mvnw.cmd verify
```

Không dùng database phát triển/production cho DB_TEST_URL. Không dùng H2 thay PostgreSQL.

Frontend:

```powershell
cd frontend
npm ci
npm run build
npm test
npx playwright install chromium
```

E2E cần backend local đang chạy và DEMO_PASSWORD tương ứng. Từ thư mục gốc:

```powershell
powershell -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\testing\Test-Frontend.ps1
```

Playwright tự chạy ba Vite server nếu cổng trống. Nếu server đã có sẵn, bảo đảm nó dùng code hiện tại. GitHub Actions có database riêng cho integration/E2E.

## Cấu trúc và tài liệu

```text
backend/              Spring Boot, Maven Wrapper, migration, integration test
frontend/manager/     Manager :3001
frontend/staff/       Staff :3002
frontend/customer/    Customer :3003
frontend/shared/      Auth UI, API client và layout
scripts/setup/        Tạo/load env, chạy backend
docs/                 Bối cảnh, plan, API, schema, decision
```

- [Bối cảnh yêu cầu](docs/architecture/foundation-context.md)
- [Kế hoạch Foundation](docs/architecture/foundation-plan.md)
- [Quyết định triển khai](docs/decisions/001-foundation.md)
- [Auth API](docs/api/auth.md)
- [Schema bàn giao](docs/database/foundation-schema.md)
- [Kết quả build/test và giới hạn kiểm chứng](docs/architecture/foundation-validation.md)

Flyway quản lý schema; Hibernate chỉ validate. Không chạy nguyên SQL nguồn vì có DROP SCHEMA và seed chưa nhất quán. Migration đã chia sẻ phải được giữ nguyên, thêm version mới khi thay đổi.

Nếu khởi động lỗi: kiểm tra JAVA_HOME, database/cổng/credential, JWT_SECRET tối thiểu 32 byte, DEMO_PASSWORD và DEMO_MANAGER_PIN khi profile local. Không bật request/SQL bind logging chứa credential.
