# Cinema Management Software

Hệ thống quản lý rạp chiếu phim gồm ba cổng web cho khách hàng, nhân viên và quản lý. Backend Spring Boot cung cấp REST API; PostgreSQL lưu dữ liệu nghiệp vụ; Flyway quản lý thay đổi schema.

README này hướng dẫn cấu trúc mã nguồn, chạy hệ thống local, cấu hình thanh toán, chạy build/test và tìm tài liệu nghiệp vụ.

## Mục lục

- [Chức năng](#chức-năng)
- [Công nghệ và cấu trúc thư mục](#công-nghệ-và-cấu-trúc-thư-mục)
- [Yêu cầu môi trường](#yêu-cầu-môi-trường)
- [Chạy trên Windows](#chạy-trên-windows)
- [Chạy trên Linux và macOS](#chạy-trên-linux-và-macos)
- [Tài khoản và dữ liệu demo](#tài-khoản-và-dữ-liệu-demo)
- [Cấu hình](#cấu-hình)
- [Thanh toán VNPay](#thanh-toán-vnpay)
- [Build và kiểm thử](#build-và-kiểm-thử)
- [Tài liệu dự án](#tài-liệu-dự-án)
- [Xử lý lỗi thường gặp](#xử-lý-lỗi-thường-gặp)

## Chức năng

### Customer — cổng khách hàng

- Tìm phim, xem áp phích và trạng thái đang chiếu, sắp chiếu hoặc hết chiếu.
- Chọn ngày, suất chiếu, ghế và bắp nước; kiểm tra giá do server tính.
- Thanh toán trực tuyến qua VNPay sandbox, xem vé QR và lịch sử đặt vé.

### Staff — cổng nhân viên

- Chọn phim và suất, bán vé tại quầy bằng tiền mặt hoặc VietQR.
- Tra cứu đơn, soát vé tại cổng và xử lý yêu cầu hoàn vé theo quy trình có quản lý xác nhận.

### Manager — cổng quản lý

- Quản lý phim, phòng và sơ đồ ghế, lịch chiếu, bảng giá, nhân viên, F&B và tồn kho.
- Xem doanh thu, hoàn vé, giao dịch theo thời gian, tình trạng ghế và các đơn thanh toán cần xử lý.

Các cổng dùng chung giao diện và API client trong workspace `frontend/shared`. Quyền được kiểm tra tại backend; ẩn nút trong giao diện không thay thế kiểm tra phân quyền ở API.

## Công nghệ và cấu trúc thư mục

- **Backend:** Java 21, Spring Boot 3, Spring Security, Spring Data JPA, Flyway, Maven Wrapper.
- **Database:** PostgreSQL 18 qua Docker Compose; PostgreSQL 17/18 riêng cũng được hỗ trợ.
- **Frontend:** React 19, TypeScript, Vite, npm workspaces, Vitest và Playwright.
- **Thanh toán:** VNPay sandbox; VietQR tại quầy được tạo từ cấu hình ngân hàng của rạp.

```text
.
├── backend/
│   ├── src/main/java/com/cinema/cms/
│   │   ├── controller/       # REST endpoints
│   │   ├── dto/              # request/response API
│   │   ├── entity/           # mô hình dữ liệu
│   │   ├── repository/       # truy vấn PostgreSQL
│   │   └── service/          # nghiệp vụ và kiểm tra invariant
│   ├── src/main/resources/
│   │   ├── db/migration/     # migration Flyway V1...
│   │   └── static/           # poster phim và ảnh F&B local
│   └── src/test/             # kiểm thử backend
├── frontend/
│   ├── manager/              # Vite app :3001
│   ├── staff/                # Vite app :3002
│   ├── customer/             # Vite app :3003
│   ├── shared/src/           # component, API, workflow dùng chung
│   └── tests/                # mock và live browser tests
├── docs/
│   ├── api/                  # API contract theo nghiệp vụ
│   ├── architecture/         # kiến trúc và kế hoạch
│   ├── code-walkthrough/     # giải thích luồng code
│   ├── database/             # schema và quy tắc dữ liệu
│   ├── decisions/            # quyết định kỹ thuật
│   ├── testing/              # cách chạy và bằng chứng nghiệm thu
│   └── ui-ux/                # contract giao diện và nguồn ảnh
├── scripts/
│   ├── setup/                # tạo/load cấu hình và chạy backend
│   └── testing/              # tiện ích dữ liệu và kiểm tra
├── compose.yaml
└── .env.example              # mẫu cấu hình, không chứa credential thật
```

Không commit thư mục sinh ra như `node_modules/`, `target/`, `dist/`, báo cáo Playwright hay file `.env`. Ảnh trong `backend/src/main/resources/static/` là asset runtime được phục vụ bởi backend; không xóa chúng như build output.

## Yêu cầu môi trường

- Git.
- JDK 21; `JAVA_HOME` phải trỏ tới JDK có `bin\javac.exe`.
- Node.js 22.12 trở lên và npm 11.10.1.
- Docker Desktop với Docker Compose, hoặc PostgreSQL 17/18 và một database riêng, rỗng.
- Maven không cần cài riêng; Maven Wrapper nằm trong `backend/`.

Kiểm tra nhanh:

```powershell
java -version
node --version
npm --version
docker compose version
```

## Chạy trên Windows

Mở PowerShell tại thư mục gốc repository.

### 1. Tạo cấu hình local một lần

```powershell
powershell -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\setup\Initialize-LocalEnvironment.ps1
```

Lệnh tạo `.env` với JWT secret, mật khẩu database và mật khẩu demo ngẫu nhiên. Mở `.env` để lấy `DEMO_PASSWORD` khi đăng nhập. `.env` đã được Git ignore; không gửi file này hoặc các secret thanh toán lên repository.

Nếu `.env` đã tồn tại, script sẽ từ chối ghi đè. Giữ file đó và chỉnh các biến cần thiết trực tiếp.

### 2. Khởi động PostgreSQL

```powershell
docker compose up -d --wait
```

Compose tạo container `cinema-management-software-postgres-1`, lắng nghe local ở cổng `5433`, và lưu dữ liệu trong Docker volume `cinema_postgres`.

### 3. Khởi động backend

Mở terminal riêng và chạy từ thư mục gốc:

```powershell
powershell -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\setup\Start-Backend.ps1
```

Backend chạy ở `http://localhost:8081`; Swagger UI ở `http://localhost:8081/swagger-ui/index.html`. Giữ terminal này mở khi dùng ứng dụng.

### 4. Cài và khởi động ba frontend

Mở terminal khác:

```powershell
cd frontend
npm ci
```

Chạy mỗi cổng trong một terminal riêng, từ `frontend/`:

```powershell
npm run dev:manager
```

```powershell
npm run dev:staff
```

```powershell
npm run dev:customer
```

Các trang local:

| Cổng | Portal | Địa chỉ |
|---:|---|---|
| 3001 | Manager | http://localhost:3001 |
| 3002 | Staff | http://localhost:3002 |
| 3003 | Customer | http://localhost:3003 |
| 8081 | Backend API | http://localhost:8081/api/v1 |
| 5433 | PostgreSQL | `localhost:5433/cinema_db` |

## Chạy trên Linux và macOS

Cần cài JDK 21, Node.js 22.12+, npm 11.10.1 và Docker Compose. Tại thư mục gốc:

```bash
cp .env.example .env
```

Đổi toàn bộ giá trị `replace-with-*` trong `.env` thành giá trị local riêng. Sau đó nạp biến vào terminal và khởi động database:

```bash
set -a
. ./.env
set +a
docker compose up -d --wait
```

Trong terminal đã nạp `.env`, mở backend:

```bash
cd backend
./mvnw spring-boot:run
```

Trong terminal khác, nạp `.env`, rồi cài và chạy frontend:

```bash
cd frontend
npm ci
npm run dev:manager
```

Chạy `npm run dev:staff` và `npm run dev:customer` trong hai terminal khác. Spring Boot không đọc `.env` tự động; phải nạp các biến như trên hoặc export chúng bằng cách khác trước khi chạy backend.

## Tài khoản và dữ liệu demo

Profile `local` tạo các tài khoản demo nếu chưa tồn tại:

| Vai trò | Username |
|---|---|
| Manager | `manager01` |
| Staff | `staff01`, `staff02`, `staff03`, `staff04` |
| Customer | `customer01` |

Các tài khoản dùng chung giá trị `DEMO_PASSWORD` trong `.env`. `DEMO_MANAGER_PIN` được dùng cho bước quản lý xác nhận hoàn tiền. Mật khẩu và PIN được hash trước khi lưu. Đây là username dành riêng cho dữ liệu local demo; profile `local` nạp lại hash từ `.env` khi backend khởi động.

Seeder demo trong profile `local` bổ sung catalog phim, lịch chiếu, layout phòng, ảnh F&B và dữ liệu giao dịch để xem dashboard. Mốc hiện tại của bộ dữ liệu demo là khoảng 1.000 vé đã bán và 50 vé hoàn; dữ liệu được ghi vào PostgreSQL, không phải dữ liệu frontend hardcode. Seeder được thiết kế để chạy lặp mà không nhân bản lịch sử đã nạp.

### Database snapshot

Repository có snapshot PostgreSQL đã làm sạch tại [`backend/data/cinema-demo.dump`](backend/data/cinema-demo.dump). Snapshot gồm schema Flyway, 50 phim, 216 suất chiếu, 12 phòng với 240 ghế, 454 booking, 1.048 vé (998 vé đã bán/đã dùng và 50 vé hoàn), cùng dữ liệu F&B mẫu. Snapshot không chứa giao dịch cá nhân từ database phát triển. Hash mật khẩu và PIN trong file snapshot đã bị vô hiệu hóa; khi khởi động backend với profile `local`, sáu tài khoản demo được đặt lại theo `DEMO_PASSWORD` và `DEMO_MANAGER_PIN` trong `.env`.

Để import snapshot vào **một database mới** mà không ghi đè `cinema_db` hiện có:

```powershell
powershell -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\database\Restore-DemoDatabase.ps1 -DatabaseName cinema_demo
```

Sau đó đổi `DB_URL` trong `.env` thành `jdbc:postgresql://localhost:5433/cinema_demo` rồi khởi động backend và frontend như phần trên. Cần dùng PostgreSQL cùng major version với Compose (18). Script từ chối tạo database nếu tên đó đã tồn tại.

Để dừng database mà giữ dữ liệu:

```powershell
docker compose down
```

**Không chạy `docker compose down -v` nếu muốn giữ dữ liệu**; tùy chọn `-v` xóa volume database.

## Cấu hình

`.env.example` là danh sách biến được repository hỗ trợ. Tạo `.env` local bằng script trên Windows hoặc sao chép thủ công trên Linux/macOS.

| Biến | Local mặc định/mẫu | Mục đích |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5433/cinema_db` | JDBC URL của database ứng dụng |
| `DB_USERNAME`, `DB_PASSWORD` | giá trị local | Tài khoản PostgreSQL; không dùng credential production |
| `POSTGRES_PORT` | `5433` | Cổng host của PostgreSQL Compose |
| `SERVER_PORT` | `8081` | Cổng backend |
| `SPRING_PROFILES_ACTIVE` | `local` | `local` bật tài khoản/dữ liệu demo; profile mặc định không seed demo |
| `JWT_SECRET` | random tối thiểu 32 byte | Ký access token |
| `JWT_TTL_SECONDS` | `1800` | Thời hạn access token |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3001,http://localhost:3002,http://localhost:3003` | Origin frontend được phép gọi API |
| `PORTAL_MANAGER_URL` | `http://localhost:3001` | Địa chỉ chuyển người dùng tới portal Manager |
| `PORTAL_STAFF_URL` | `http://localhost:3002` | Địa chỉ chuyển người dùng tới portal Staff |
| `PORTAL_CUSTOMER_URL` | `http://localhost:3003` | Địa chỉ chuyển người dùng tới portal Customer |
| `DEMO_PASSWORD` | tạo ngẫu nhiên bởi script | Mật khẩu tài khoản demo local |
| `DEMO_MANAGER_PIN` | tạo ngẫu nhiên bởi script | PIN Manager local |
| `CINEMA_BANK_BIN` | để trống đến khi cấu hình | Mã BIN dùng tạo VietQR tại quầy |
| `CINEMA_BANK_ACCOUNT` | để trống đến khi cấu hình | Tài khoản nhận chuyển khoản VietQR |
| `CINEMA_BANK_NAME` | để trống đến khi cấu hình | Tên ngân hàng nhận VietQR |
| `VNPAY_TMN_CODE` | merchant sandbox | Mã website/Terminal ID VNPay sandbox |
| `VNPAY_HASH_SECRET` | secret sandbox | Khóa ký checksum VNPay; giữ riêng trong `.env` |
| `VNPAY_PAY_URL` | URL sandbox từ nhà cung cấp | Trang thanh toán VNPay sandbox |
| `VNPAY_RETURN_URL` | Customer portal local | URL trình duyệt quay lại sau thanh toán |
| `VNPAY_IPN_URL` | cần HTTPS public | URL server nhận thông báo IPN từ VNPay |
| `PAYMENT_DEMO_ENABLED` | `false` | Công tắc thanh toán mô phỏng, chỉ dùng cho local demo |
| `JAVA_TOOL_OPTIONS` | `-Duser.timezone=UTC` | Giữ timestamp JVM ở UTC; ngày nghiệp vụ dùng Asia/Ho_Chi_Minh |

Frontend gọi API ở `http://localhost:8081/api/v1` mặc định. Nếu đổi cổng backend, đặt `VITE_API_URL` trong `.env.local` của từng app trước khi chạy Vite rồi restart frontend. Các file `.env.example` trong ba app cho cấu hình mẫu.

## Thanh toán VNPay

VNPay Return URL phục vụ trạng thái hiển thị cho trình duyệt. Backend chỉ xác nhận thanh toán và phát hành vé sau khi nhận, kiểm tra chữ ký và đối chiếu IPN hợp lệ.

1. Đăng ký/kích hoạt merchant sandbox và lấy `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET`, cùng URL thanh toán chính xác từ VNPay.
2. Điền các giá trị đó vào `.env`; không đưa secret vào source code, README, ảnh chụp hoặc URL frontend.
3. Đặt `VNPAY_RETURN_URL` về Customer portal, thường là `http://localhost:3003/payments/vnpay/return` khi thử local.
4. VNPay không gọi được `localhost`. Dùng một HTTPS tunnel/domain public đang hoạt động cho `VNPAY_IPN_URL`, trỏ tới `https://<public-host>/api/v1/payments/vnpay/ipn` và đăng ký đúng URL callback với merchant sandbox.
5. Giữ backend chạy và kiểm tra log/API khi thử thanh toán. Chỉ coi đơn đã thanh toán khi backend cập nhật trạng thái từ IPN hợp lệ.

Thẻ/số dư sandbox do VNPay cung cấp là dữ liệu thử nghiệm; không dùng thẻ ngân hàng thật để kiểm tra sandbox. Không bật `PAYMENT_DEMO_ENABLED` trên môi trường dùng dữ liệu hoặc thanh toán thật.

## Build và kiểm thử

Các lệnh dưới đây chỉ build hoặc kiểm thử; chúng không thay thế database phát triển bằng database test.

### Backend

Từ PowerShell trong `backend/`:

```powershell
.\mvnw.cmd -DskipTests package
```

Chạy toàn bộ kiểm thử backend:

```powershell
.\mvnw.cmd verify
```

Integration tests dùng PostgreSQL Testcontainers và cần Docker. Nếu môi trường không chạy được Testcontainers, cung cấp một database test riêng, rỗng:

```powershell
$env:DB_TEST_URL = 'jdbc:postgresql://localhost:5432/cinema_management_test'
$env:DB_TEST_USERNAME = 'test-user'
$env:DB_TEST_PASSWORD = 'test-password'
.\mvnw.cmd verify
```

Không trỏ `DB_TEST_URL` tới database local đang dùng hoặc production. Các test có thể ghi dữ liệu và thu hồi token trong database được chỉ định.

### Frontend

Từ `frontend/`:

```powershell
npm run build
npm test
```

`npm run build` chạy TypeScript typecheck và build cả ba app. `npm test` chạy Vitest. Chạy browser tests mock:

```powershell
npx playwright install chromium
npm run test:e2e
```

Live E2E cần backend local, database demo và `DEMO_PASSWORD` khớp với tài khoản hiện tại:

```powershell
npm run test:e2e:live
```

Hoặc từ thư mục gốc chạy script dùng cho quy trình G5:

```powershell
powershell -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\testing\Test-Frontend.ps1
```

## Tài liệu dự án

- [Frontend API contracts](docs/ui-ux/frontend-contracts.md) và [hướng dẫn kiến trúc giao diện](docs/ui-ux/frontend-architecture-and-guide.md).
- [Code walkthrough](docs/code-walkthrough/README.md), [backend](docs/code-walkthrough/backend.md), [frontend](docs/code-walkthrough/frontend.md).
- [API docs](docs/api/): auth, catalog, suất chiếu, ghế, booking, VNPay, vé, hoàn vé, báo cáo, tồn kho và audit.
- [Database schema](docs/database/foundation-schema.md); schema local được áp dụng tuần tự bởi Flyway.
- [Architecture decisions](docs/decisions/) và [architecture notes](docs/architecture/).
- [Frontend testing](docs/testing/frontend.md), [Sprint D](docs/testing/sprint-d.md), [Sprint E](docs/testing/sprint-e.md), [Sprint F](docs/testing/sprint-f.md), [G5 validation](docs/testing/g5-validation-2026-11-23.md).
- [Nguồn poster phim](docs/ui-ux/movie-poster-sources.md) và [nguồn ảnh F&B](docs/ui-ux/concession-image-sources.md).

### Quy tắc schema và dữ liệu

- Flyway là nguồn quản lý schema. Hibernate chỉ validate schema khi khởi động.
- Không chạy lại SQL nguồn có thao tác `DROP SCHEMA`; không sửa migration đã chia sẻ. Tạo migration version mới cho thay đổi tiếp theo.
- Chỉ chạy profile `local` trên database local riêng. Database test, demo và production phải tách nhau.
- Không xóa Docker volume, file `.env` hoặc dữ liệu database trong thao tác dọn source code.

## Xử lý lỗi thường gặp

### Trình duyệt báo không kết nối được server

- Kiểm tra database ở cổng `5433`, backend ở `8081`, frontend ở `3001`, `3002`, `3003`.
- Kiểm tra `SERVER_PORT`, `VITE_API_URL`, `CORS_ALLOWED_ORIGINS`; sau khi đổi biến, restart tiến trình tương ứng.
- Backend phải chạy trước khi đăng nhập; HTTP 200 từ Vite chỉ xác nhận frontend hoạt động, không xác nhận API/database.

### Docker không khởi động PostgreSQL

- Mở Docker Desktop và đợi engine sẵn sàng.
- Kiểm tra cổng host `5433` có bị tiến trình khác chiếm không và `.env` có `DB_USERNAME`/`DB_PASSWORD` nhất quán.
- Chạy `docker compose ps` và `docker compose logs postgres` để xem trạng thái. Không dùng `down -v` để sửa lỗi trước khi sao lưu dữ liệu.

### Backend không khởi động

- Kiểm tra JDK 21 và `JAVA_HOME`.
- Xác nhận PostgreSQL healthy, database tồn tại và thông tin `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` chính xác.
- Nếu migration báo lỗi checksum, không tự sửa lịch sử Flyway hoặc xóa schema; xem migration và database đang dùng trước.
- Nếu cổng `8081` bị chiếm, dừng tiến trình cũ hoặc đổi `SERVER_PORT` và đồng bộ `VITE_API_URL`.

### Frontend không gọi được backend

- Mặc định local là `http://localhost:8081/api/v1`.
- Origin portal phải xuất hiện trong `CORS_ALLOWED_ORIGINS`.
- Restart Vite sau khi sửa `.env.local`. Dùng Ctrl+Shift+R để tải lại bundle trong browser.

### VNPay không gửi được callback

- VNPay không thể gọi `localhost` hoặc địa chỉ LAN riêng. Dùng hostname HTTPS public đang hoạt động.
- Kiểm tra đường dẫn callback `/api/v1/payments/vnpay/ipn`, tunnel trỏ đúng backend, và URL IPN đã đăng ký trong merchant sandbox.
- Không cấp vé chỉ dựa vào trang Return URL hoặc ảnh chụp thành công ở sandbox.

### Tài khoản demo không đăng nhập được

- Username nằm trong bảng [tài khoản demo](#tài-khoản-và-dữ-liệu-demo); mật khẩu nằm trong `.env` dưới `DEMO_PASSWORD`.
- Thay `DEMO_PASSWORD` sau khi user đã được tạo không reset password lưu trong database. Dùng quy trình reset password của hệ thống hoặc khởi tạo database demo riêng nếu cần làm mới hoàn toàn.
