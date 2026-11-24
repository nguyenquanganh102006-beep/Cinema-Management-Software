> Cập nhật G5 19/11/2026: contract hiện hành nằm trong [frontend-contracts.md](frontend-contracts.md); kiểm thử và bằng chứng nằm tại [frontend.md](../testing/frontend.md). Những mô tả kiến trúc dưới đây được giữ để tham khảo cấu trúc module.

> Cập nhật 23/11/2026: [Code walkthrough](../code-walkthrough/README.md) đối chiếu working tree hiện tại, gồm recovery sau mất response tạo booking, input/output frontend/backend và transaction. Guide chi tiết bên dưới có mô tả reducer trước khi thêm `creation`; ưu tiên walkthrough và source cho luồng này.

# Tài liệu Kiến trúc Chi tiết, Đặc tả Hàm & Hướng dẫn Vận hành Frontend

Tài liệu này mô tả toàn diện kiến trúc kỹ thuật, cấu trúc từng file, đặc tả chi tiết **Input / Output / Kỹ thuật xử lý** của toàn bộ hàm, hook, component trong thư mục `frontend/`, cùng hướng dẫn chi tiết cách khởi chạy và kiểm thử dự án **Cinema Management Software**.

---

## Mục lục

1. [Tổng quan Kiến trúc & Kỹ thuật Cốt lõi](#1-tổng-quan-kiến-trúc--kỹ-thuật-cốt-lõi)
2. [Cấu trúc Thư mục `frontend/`](#2-cấu-trúc-thư-mục-frontend)
3. [Đặc tả Chi tiết Từng File, Hàm, Hook & Component](#3-đặc-tả-chi-tiết-từng-file-hàm-hook--component)
   - [3.1. Cổng khởi chạy 3 Portal (`manager`, `staff`, `customer`)](#31-cổng-khởi-chạy-3-portal-manager-staff-customer)
   - [3.2. Khởi tạo, Định tuyến & Kiểu dữ liệu (`bootstrap.ts`, `App.tsx`, `types.ts`)](#32-khởi-tạo-định-tuyến--kiểu-dữ-liệu-bootstrapts-apptsx-typests)
   - [3.3. Tầng Giao tiếp HTTP & API Clients (`api.ts` và `api/*.ts`)](#33-tầng-giao-tiếp-http--api-clients-apits-và-apits)
   - [3.4. Xác thực & Phân quyền (`auth/AuthProvider.tsx`, `auth/AuthScreen.tsx`)](#34-xác-thực--phân-quyền-authauthprovidertsx-authauthscreentsx)
   - [3.5. Quản lý Trạng thái Đặt vé (`booking/bookingReducer.ts`, `booking/BookingFlowProvider.tsx`)](#35-quản-lý-trạng-thái-đặt-vé-bookingbookingreducerts-bookingbookingflowprovidertsx)
   - [3.6. Custom Hooks (`hooks/useQuery.ts`, `hooks/useServerCountdown.ts`, `hooks/useSeatHold.ts`)](#36-custom-hooks-hooksusequeryts-hooksuseservercountdownts-hooksuseatholdts)
   - [3.7. Tiện ích Định dạng (`utils/format.ts`)](#37-tiện-ích-định-dạng-utilsformatts)
   - [3.8. Bố cục & Component Tái sử dụng (`layout/PortalLayout.tsx`, `components/*.tsx`)](#38-bố-cục--component-tái-sử-dụng-layoutportallayouttsx-componentstsx)
   - [3.9. Các Trang Nghiệp vụ (`pages/BookingPages.tsx`, `pages/StaffPages.tsx`, `pages/ManagementPages.tsx`)](#39-các-trang-nghiệp-vụ-pagesbookingpagestsx-pagesstaffpagestsx-pagesmanagementpagestsx)
   - [3.10. Hệ thống Mock MSW & Bộ Kiểm thử (`mocks/*`, `*.test.tsx`, `tests/*.spec.ts`)](#310-hệ-thống-mock-msw--bộ-kiểm-thử-mocks-testtsx-testsspects)
4. [Hướng dẫn Chạy Dự án & Kiểm thử](#4-hướng-dẫn-chạy-dự-án--kiểm-thử)

---

## 1. Tổng quan Kiến trúc & Kỹ thuật Cốt lõi

### 1.1. Kiến trúc Monorepo 3 Portal
Dự án sử dụng **npm workspaces** (`frontend/package.json`) chia thành 4 gói:
- `@cinema/manager`: Cổng Quản lý rạp chạy độc lập ở port `3001`.
- `@cinema/staff`: Cổng Nhân viên POS & Soát vé chạy độc lập ở port `3002`.
- `@cinema/customer`: Cổng Khách hàng đặt vé online chạy độc lập ở port `3003`.
- `@cinema/shared`: Thư viện lõi chứa toàn bộ trang, component, hook, API client, style và bộ mock MSW dùng chung cho cả 3 cổng.

### 1.2. Các Kỹ thuật Trọng tâm
1. **Bảo mật Phiên trong Bộ nhớ (In-Memory Session Security)**:
   - Không lưu `accessToken` vào `localStorage` hay `sessionStorage`, hạn chế token tồn lại sau reload. XSS đang chạy vẫn có thể thao tác trong phiên; memory storage không thay thế phòng chống XSS.
   - Khi người dùng đăng nhập nhầm cổng (ví dụ tài khoản `MANAGER` đăng nhập ở cổng `3003`), frontend gọi `POST /auth/transfers` để nhận mã một lần (one-time code có hạn 30 giây) qua URL hash `#auth-transfer=<code>`, chuyển hướng sang cổng `3001`, xóa ngay hash khỏi lịch sử trình duyệt bằng `history.replaceState` rồi gọi `POST /auth/transfers/consume` để nhận phiên.
2. **Xác thực Ủy quyền Manager (Manager Override) Không Ghi đè Phiên Staff**:
   - Tại màn hoàn vé POS (`ManagerOverrideDialog`), khi Staff nhập `username` và `PIN` của Manager, frontend gửi token của **Staff** trong header `Authorization` kèm thông tin Manager trong body tới `POST /manager-overrides`, chỉ lưu `overrideToken` ngắn hạn trong state cục bộ của popup và xóa ngay `pin` khỏi state sau khi gửi. Phiên đăng nhập của Staff không bao giờ bị thay thế.
3. **Đồng bộ Đồng hồ Server (Server-Authoritative Countdown)**:
   - Mọi bộ đếm ngược giữ ghế (`useServerCountdown`) tính toán dựa trên hiệu số `new Date(holdUntil).getTime() - new Date(serverTime).getTime()` kết hợp với đồng hồ đơn điệu `performance.now()` của trình duyệt. Kỹ thuật này triệt tiêu hoàn toàn sai số khi đồng hồ máy khách (Client OS clock) bị chỉnh sai giờ.
4. **Quản lý Giữ ghế Chống Tranh chấp & Tự Phục hồi (`useSeatHold`)**:
   - Tự động làm mới sơ đồ ghế mỗi 5 giây (chỉ khi tab đang hiển thị `document.visibilityState !== "hidden"`), kết hợp lắng nghe sự kiện `focus` và `visibilitychange`.
   - Dùng biến đếm thế hệ (`generation.current`) và `AbortController` để hủy các request đọc cũ (stale reads), không cho phép kết quả cũ ghi đè trạng thái mới.
   - Nếu request `POST /showtimes/{id}/holds` đã được Backend commit nhưng mạng bị đứt trước khi trình duyệt nhận phản hồi, lần đọc `GET /showtimes/{id}/seats` kế tiếp sẽ tự động đối chiếu mảng `ownHolds` từ Server để phục hồi trạng thái giữ ghế (`dispatch({ type: "HOLD", value: own })`).
   - Khi component unmount (ví dụ chuyển từ trang chọn ghế sang trang `/checkout`), hook **chỉ hủy bộ đọc** chứ không gọi `DELETE /holds/{token}` để bảo toàn ghế đang giữ trong suốt quá trình thanh toán.
5. **Tương thích Kép (Dual-Mode API Normalization)**:
   - Tầng API (`api.ts`, `api/*.ts`) tự động chuẩn hóa sự khác biệt cấu trúc giữa DTO của Backend thật (`origin/develop` - Sprints A, B, C, D, E) và dữ liệu Mock MSW (`VITE_USE_MOCKS=true`), cho phép dùng chung mã giao diện giữa hai chế độ; việc đối chiếu toàn bộ mock với backend vẫn còn mở.

---

## 2. Cấu trúc Thư mục `frontend/`

```text
frontend/
├── package.json                  # Cấu hình npm workspaces, scripts build/dev/test
├── tsconfig.json                 # Cấu hình TypeScript strict cho toàn bộ workspaces
├── vitest.config.ts              # Cấu hình Vitest (jsdom) cho unit/component tests
├── playwright.config.ts          # Cấu hình Playwright cho 3 trình duyệt mock & chromium-live
├── manager/                      # Cổng Manager (:3001)
│   ├── index.html
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
│       ├── main.tsx              # Điểm vào ứng dụng Manager (role="MANAGER")
│       └── App.tsx               # Re-export App từ @cinema/shared
├── staff/                        # Cổng Staff POS (:3002)
│   ├── index.html
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
│       ├── main.tsx              # Điểm vào ứng dụng Staff (role="STAFF")
│       └── App.tsx               # Re-export App từ @cinema/shared
├── customer/                     # Cổng Customer (:3003)
│   ├── index.html
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
│       ├── main.tsx              # Điểm vào ứng dụng Customer (role="CUSTOMER")
│       └── App.tsx               # Re-export App từ @cinema/shared
├── shared/                       # Gói dùng chung @cinema/shared
│   ├── package.json
│   ├── public/
│   │   └── mockServiceWorker.js  # Service Worker của MSW cho chế độ dev mock
│   └── src/
│       ├── bootstrap.ts          # Khởi tạo MSW worker nếu VITE_USE_MOCKS=true
│       ├── App.tsx               # Bộ định tuyến (Router) phân quyền theo Role
│       ├── api.ts                # HTTP client cốt lõi (timeout, abort, 401, phân trang) & authApi
│       ├── types.ts              # Định nghĩa toàn bộ TypeScript interfaces/types
│       ├── styles.css            # Hệ thống giao diện CSS (Responsive 360px/768px/1280px)
│       ├── print.css             # CSS chuyên biệt khi in vé / hóa đơn (@media print)
│       ├── api/                  # Các module gọi API theo miền nghiệp vụ
│       │   ├── catalog.ts        # Phim, suất chiếu, F&B (kèm làm giàu dữ liệu & tồn kho)
│       │   ├── pricing.ts        # Báo giá vé tạm tính (/pricing/quote)
│       │   ├── holds.ts          # Sơ đồ ghế, giữ ghế, gia hạn, hủy giữ ghế
│       │   ├── bookings.ts       # Tạo/sửa/hủy đơn, đọc lịch sử & vé, tạo link VNPay
│       │   ├── pos.ts            # Thanh toán tiền mặt, VietQR, tra cứu & soát vé tại cổng
│       │   ├── refunds.ts        # Kiểm tra điều kiện hoàn vé, Manager Override, hoàn tiền
│       │   ├── management.ts     # CRUD danh mục, phòng/ghế, lịch chiếu, kho, nhân viên
│       │   └── reports.ts        # Tổng hợp 7 chỉ số báo cáo doanh thu & công suất
│       ├── auth/
│       │   ├── AuthProvider.tsx  # Context quản lý phiên, tự động chuyển cổng, hết hạn token
│       │   └── AuthScreen.tsx    # Giao diện Đăng nhập & Đăng ký (Customer)
│       ├── booking/
│       │   ├── bookingReducer.ts # Pure reducer quản lý luồng chọn suất -> ghế -> F&B -> đơn
│       │   └── BookingFlowProvider.tsx # React Context bọc bookingReducer
│       ├── hooks/
│       │   ├── useQuery.ts       # Hook tải dữ liệu bất đồng bộ có hủy request cũ & refetch
│       │   ├── useServerCountdown.ts # Hook đếm ngược theo giờ chuẩn của Server
│       │   └── useSeatHold.ts    # Hook quản lý sơ đồ ghế, giữ ghế, gia hạn & tự phục hồi
│       ├── layout/
│       │   └── PortalLayout.tsx  # Khung giao diện Sidebar, Header, thông báo toàn cục
│       ├── components/
│       │   ├── SeatMap.tsx       # Sơ đồ ghế tương tác (Standard, VIP, cặp Sweetbox)
│       │   ├── ConcessionPicker.tsx # Bộ chọn số lượng bắp nước & combo theo tồn kho
│       │   ├── OrderSummary.tsx  # Bảng tạm tính giá vé + F&B và chi tiết hóa đơn
│       │   ├── HoldCountdown.tsx # Thanh hiển thị thời gian giữ ghế & nút gia hạn +2 phút
│       │   ├── AgeConfirmation.tsx # Checkbox cam kết/kiểm tra giấy tờ độ tuổi (T13/T16/T18)
│       │   ├── TicketView.tsx    # Hiển thị vé điện tử, sinh ảnh QR (qrcode) & nút in vé
│       │   ├── ConfirmDialog.tsx # Hộp thoại Modal dựa trên phần tử HTML <dialog> chuẩn
│       │   ├── Feedback.tsx      # Hiển thị trạng thái đang tải, lỗi (kèm nút Thử lại), rỗng
│       │   ├── Pagination.tsx    # Thanh điều hướng phân trang
│       │   └── AccountPanel.tsx  # Trang thông tin tài khoản & kiểm tra phiên
│       ├── pages/
│       │   ├── BookingPages.tsx  # Các trang Khám phá phim, Chi tiết phim, POS, Chọn ghế, Checkout, Return, Lịch sử
│       │   ├── StaffPages.tsx    # Các trang Hoàn vé POS (kèm Manager Override) & Soát vé tại cổng
│       │   └── ManagementPages.tsx # 11 trang quản trị của Manager + ResourceEditor đa năng
│       └── mocks/
│           ├── fixtures.ts       # Dữ liệu mẫu khởi tạo cho chế độ Mock
│           ├── core.ts           # Bộ xử lý nghiệp vụ Mock (stateful) dùng chung cho MSW & Playwright
│           ├── handlers.ts       # Định nghĩa MSW HTTP handlers
│           ├── browser.ts        # Khởi tạo MSW SetupWorker cho trình duyệt
│           └── server.ts         # Khởi tạo MSW SetupServer cho Vitest
└── tests/                        # Bộ kiểm thử E2E Playwright
    ├── auth.spec.ts              # 14 kịch bản E2E kiểm thử xác thực, phân quyền, chuyển cổng
    ├── booking.live.spec.ts      # 3 kịch bản E2E chạy trực tiếp với Backend thật + PostgreSQL
    ├── customer.mock.spec.ts     # Kịch bản E2E luồng khách hàng, VNPay tab riêng, responsive 360/768/1280px
    ├── manager.mock.spec.ts      # Kịch bản E2E tạo phim, nhập kho, trùng lịch chiếu, lọc báo cáo
    ├── pos.mock.spec.ts          # Kịch bản E2E bán vé tiền mặt, VietQR, thiếu tồn kho F&B
    └── refund-gate.mock.spec.ts  # Kịch bản E2E sai PIN Manager, hoàn vé tiền mặt, soát vé tại cổng
```

---

## 3. Đặc tả Chi tiết Từng File, Hàm, Hook & Component

### 3.1. Cổng khởi chạy 3 Portal (`manager`, `staff`, `customer`)

- **Files**:
  - `frontend/manager/src/main.tsx` (port `3001`, truyền `role="MANAGER"`)
  - `frontend/staff/src/main.tsx` (port `3002`, truyền `role="STAFF"`)
  - `frontend/customer/src/main.tsx` (port `3003`, truyền `role="CUSTOMER"`)
- **Luồng xử lý**:
  1. Gọi `bootstrap()` từ `@cinema/shared/bootstrap` để khởi tạo Mock Service Worker nếu đang chạy ở chế độ `DEV` và `VITE_USE_MOCKS === "true"`.
  2. Sau khi `bootstrap()` hoàn tất, gắn `<App role="..." />` vào phần tử `#root` trong `<StrictMode>`.
  3. Nếu khởi tạo thất bại, hiển thị thông báo lỗi trực tiếp vào `#root`.

---

### 3.2. Khởi tạo, Định tuyến & Kiểu dữ liệu (`bootstrap.ts`, `App.tsx`, `types.ts`)

#### `frontend/shared/src/bootstrap.ts`
| Hàm | Input | Output | Kỹ thuật & Mô tả |
| :--- | :--- | :--- | :--- |
| `bootstrap()` | Không có | `Promise<void>` | Kiểm tra `import.meta.env.DEV && import.meta.env.VITE_USE_MOCKS === "true"`. Sử dụng **Dynamic Import** (`await import("./mocks/browser")`) để trình đóng gói Vite loại bỏ hoàn toàn mã nguồn mock khỏi bản build production. Khởi chạy `worker.start({ onUnhandledRequest: "bypass" })`. |

#### `frontend/shared/src/App.tsx`
| Hàm / Component | Input (Props) | Output (JSX) | Kỹ thuật & Mô tả |
| :--- | :--- | :--- | :--- |
| `Portal({ role })` | `{ role: Role }` (`"MANAGER" \| "STAFF" \| "CUSTOMER"`) | `JSX.Element` | - Kiểm tra nếu `location.pathname` là `/payment-return` hoặc `/payments/vnpay/return` thì hiển thị ngay `<PaymentReturnPage />` **không cần đăng nhập** (vì VNPay mở ở tab/cửa sổ chuyển hướng độc lập và phiên lưu trong RAM).<br>- Với mọi route khác, bọc trong `<AuthProvider role={role}>` -> `<BookingFlowProvider>` -> `<PortalLayout>` và chỉ đăng ký các `<Route>` tương ứng với `role`. Route không khớp (`*`) tự động `<Navigate replace>` về trang mặc định của từng role (`/dashboard`, `/pos`, `/movies`). |
| `App({ role })` | `{ role: Role }` | `JSX.Element` | Bọc `<Portal role={role} />` bên trong `<BrowserRouter>`. |

#### `frontend/shared/src/types.ts`
Định nghĩa toàn bộ hợp đồng dữ liệu TypeScript xuyên suốt ứng dụng:
- `AgeRating`: `"P" | "T13" | "T16" | "T18"`
- `Movie`, `Auditorium`, `Showtime`, `Seat`, `Hold`, `SeatMap`, `Quote`, `Concession`, `CartLine`, `Ticket`, `Payment`, `Booking`, `BookingInput`, `Refund`, `Eligibility`, `Inventory`, `Restock`, `Staff`, `PricingRule`, `Reports`.

---

### 3.3. Tầng Giao tiếp HTTP & API Clients (`api.ts` và `api/*.ts`)

#### `frontend/shared/src/api.ts`
| Lớp / Hàm | Input | Output | Kỹ thuật & Mô tả |
| :--- | :--- | :--- | :--- |
| `class ApiError` | `(status: number, code: string, message: string, details?: Record<string, string>)` | Thực thể `ApiError extends Error` | Lưu mã HTTP (`status`), mã lỗi nghiệp vụ từ backend (`code`, ví dụ `SEAT_NOT_AVAILABLE`, `SHOWTIME_OVERLAP`), thông điệp tiếng Việt (`message`) và chi tiết lỗi trường (`details`). |
| `envelope<T>(path, options)` *(internal)* | `path: string`, `options: RequestOptions` (`{ method?, body?, token?, signal? }`) | `Promise<T>` | - Thiết lập `AbortController` kết hợp cả `options.signal` từ bên ngoài và bộ hẹn giờ **timeout 15.000ms**.<br>- Tự động đính kèm header `Authorization: Bearer <token>` và `Content-Type: application/json`, đặt `credentials: "omit"`.<br>- Nếu nhận HTTP `401` khi có `token`, phát sự kiện toàn cục `window.dispatchEvent(new Event("cinema:unauthorized"))` để `AuthProvider` tự động xóa phiên.<br>- Phân biệt rõ 3 loại lỗi hạ tầng: `AbortError` (hủy chủ động), `REQUEST_TIMEOUT` (quá 15s), và `NETWORK_ERROR` (mất kết nối). |
| `request<T>(path, options)` | `path: string`, `options?: RequestOptions` | `Promise<T>` | Gọi `envelope<{ data: T }>(path, options)` và trích xuất trường `.data` theo chuẩn gói phản hồi `{ data: ... }` của Backend. |
| `requestPage<T>(path, options)` | `path: string`, `options?: RequestOptions` | `Promise<Page<T>>` (`{ data: T[], meta: PageMeta }`) | Tự động chuẩn hóa cả 3 dạng phản hồi phân trang:<br>1. `{ data: { items, page, size, totalElements, totalPages } }` (`PageResponse<T>` của Backend thật).<br>2. `{ data: T[], meta: PageMeta }` (của Mock server).<br>3. `{ data: T[] }` (mảng phẳng không phân trang). |
| `query(values)` | `values: Record<string, string \| number \| boolean \| undefined>` | `string` (chuỗi query URL) | Tạo `URLSearchParams`, loại bỏ các khóa có giá trị `undefined` hoặc chuỗi rỗng `""`. |
| `authApi.login(username, password)` | `username: string, password: string` | `Promise<Session>` | Gọi `POST /auth/login`. |
| `authApi.register(body)` | `body: RegisterInput` | `Promise<User>` | Gọi `POST /auth/register` để tạo tài khoản `CUSTOMER`. |
| `authApi.me(token)` | `token: string` | `Promise<User>` | Gọi `GET /auth/me` kiểm tra thông tin và vai trò hiện hành từ DB. |
| `authApi.logout(token)` | `token: string` | `Promise<void>` | Gọi `POST /auth/logout` để thu hồi token hiện tại trong PostgreSQL. |
| `authApi.transfer(token)` | `token: string` | `Promise<{ redirectUrl: string }>` | Gọi `POST /auth/transfers` lấy URL chuyển cổng kèm mã dùng 1 lần. |
| `authApi.consumeTransfer(code, role)` | `code: string, role: Role` | `Promise<Session>` | Gọi `POST /auth/transfers/consume` đổi mã 1 lần lấy `Session` tại cổng đích. |

#### `frontend/shared/src/api/catalog.ts`
| Hàm | Input | Output | Kỹ thuật & Mô tả |
| :--- | :--- | :--- | :--- |
| `enrichShowtime(raw, movies, rooms)` *(internal)* | `raw: RawShowtime`, `movies: Map<number, Movie>`, `rooms: Map<number, Auditorium>` | `Showtime` | Hợp nhất dữ liệu suất chiếu từ Backend (`id, movieId, auditoriumId, startTime, endTime, basePrice`) với thông tin phim (`title, ageRating, durationMinutes`) và phòng chiếu (`name, auditoriumType`). |
| `fetchShowtimeLookups(token, signal)` *(internal)* | `token: string, signal?: AbortSignal` | `Promise<{ movies: Map<number, Movie>; rooms: Map<number, Auditorium> }>` | Tải song song (`Promise.all`) danh sách phim `/movies?size=100` và `/auditoriums` (bắt lỗi `.catch(() => [])` an toàn khi tài khoản Customer/Staff không có quyền gọi `/auditoriums`). |
| `catalogApi.movies(token, filters, signal)` | `token: string`, `filters?: { keyword?, active?, page?, size? }`, `signal?: AbortSignal` | `Promise<Page<Movie>>` | Gọi `GET /movies` có phân trang và bộ lọc. |
| `catalogApi.movie(id, token, signal)` | `id: number, token: string, signal?: AbortSignal` | `Promise<Movie>` | Gọi `GET /movies/{id}`. |
| `catalogApi.showtimes(token, filters, signal)` | `token: string`, `filters?: { movieId?, auditoriumId?, date? }`, `signal?: AbortSignal` | `Promise<Showtime[]>` | Gọi `GET /showtimes`, tự động mở gói `PageResponse` và làm giàu dữ liệu qua `enrichShowtime`. |
| `catalogApi.showtime(id, token, signal)` | `id: number, token: string, signal?: AbortSignal` | `Promise<Showtime>` | Gọi `GET /showtimes/{id}`, kết hợp đọc `/movies/{movieId}` và `/auditoriums` để trả về đối tượng `Showtime` đầy đủ. |
| `catalogApi.concessions(token, signal)` | `token: string, signal?: AbortSignal` | `Promise<Concession[]>` | Gọi song song `GET /concessions?size=100` và `GET /inventory/alerts`, ánh xạ `availableQuantity` và cờ `lowStock` cho từng mặt hàng F&B. |

#### `frontend/shared/src/api/pricing.ts`
| Hàm | Input | Output | Kỹ thuật & Mô tả |
| :--- | :--- | :--- | :--- |
| `quote(showtimeId, seatIds, token, signal)` | `showtimeId: number, seatIds: number[], token: string, signal?: AbortSignal` | `Promise<Quote>` | Gọi `POST /pricing/quote` với `{ showtimeId, seatIds }`. Chuẩn hóa phản hồi `{ seats, ticketTotal }` của Backend thật và `{ tickets, total }` của Mock về kiểu `Quote` (`{ tickets, total }`). |

#### `frontend/shared/src/api/holds.ts`
| Hàm | Input | Output | Kỹ thuật & Mô tả |
| :--- | :--- | :--- | :--- |
| `holdsApi.map(id, token, signal)` | `id: number, token: string, signal?: AbortSignal` | `Promise<SeatMap>` | Gọi `GET /showtimes/{id}/seats` lấy trạng thái ghế (`AVAILABLE`, `HOLDING`, `BOOKED`, `heldByMe`) và danh sách `ownHolds` kèm `serverTime`. |
| `holdsApi.hold(id, seatIds, token)` | `id: number, seatIds: number[], token: string` | `Promise<Hold>` | Gọi `POST /showtimes/{id}/holds` với `{ seatIds }` để khóa ghế tạm thời. |
| `holdsApi.extend(holdToken, token)` | `holdToken: string, token: string` | `Promise<Hold>` | Gọi `POST /holds/{holdToken}/extend` với `{ waitingForTransfer: true }` (dành cho Staff chờ khách chuyển khoản VietQR khi còn ≤ 60 giây, cộng thêm 2 phút). |
| `holdsApi.release(holdToken, token)` | `holdToken: string, token: string` | `Promise<void>` | Gọi `DELETE /holds/{holdToken}` để giải phóng nhóm ghế đang giữ. |

#### `frontend/shared/src/api/bookings.ts`
| Hàm | Input | Output | Kỹ thuật & Mô tả |
| :--- | :--- | :--- | :--- |
| `normalizeTicket(t, show)` | `t: RawTicket, show?: Partial<Showtime>` | `Ticket` | Chuẩn hóa đối tượng vé từ Backend (`seatRow` + `seatNumber` -> `seatLabel`, tạo `qrPayload` từ `ticketCode`). |
| `normalizeBooking(raw, fallback)` | `raw: RawBooking, fallback?: Partial<Booking>` | `Booking` | Hợp nhất tất cả dạng DTO đơn hàng của Backend (`BookingResponse`, `MeBookingSummaryResponse`, `MeBookingDetailResponse`, `PosBookingSummaryResponse`, `PosBookingDetailResponse`) với ngữ cảnh `fallback` sẵn có trên client; tự động liên kết nhãn ghế (`A1`, `B2`) giữa `seats` và `tickets`. |
| `bookingsApi.create(body, token, context)` | `body: BookingInput, token: string, context?: Partial<Booking>` | `Promise<Booking>` | Gọi `POST /bookings` tạo đơn `PENDING` từ `holdToken` và giỏ `concessions` (bỏ qua `ageConfirmed` khi tạo từ POS vì POS xác nhận giấy tờ lúc thu tiền). |
| `bookingsApi.get(id, token, pos, signal, fallback)` | `id: number, token: string, pos?: boolean, signal?: AbortSignal, fallback?: Partial<Booking>` | `Promise<Booking>` | Gọi `GET /pos/bookings/{id}` (nếu `pos=true`) hoặc `GET /me/bookings/{id}` (nếu `pos=false`). Nếu đơn đã `PAID` mà danh sách vé chưa đính kèm, tự động gọi bổ sung `GET /bookings/{id}/tickets`. |
| `bookingsApi.list(token, pos, filters, signal)` | `token: string, pos?: boolean, filters?: Record<string, ...>, signal?: AbortSignal` | `Promise<Page<Booking>>` | Gọi `GET /pos/bookings` hoặc `GET /me/bookings` kèm bộ lọc phân trang. |
| `bookingsApi.vnpay(id, token)` | `id: number, token: string` | `Promise<{ paymentUrl: string; expiresAt: string }>` | Gọi `POST /bookings/{id}/payments/vnpay` lấy URL cổng thanh toán VNPay. |

#### `frontend/shared/src/api/pos.ts`
| Hàm | Input | Output | Kỹ thuật & Mô tả |
| :--- | :--- | :--- | :--- |
| `posApi.cash(id, cashReceived, ageDocumentChecked, token)` | `id: number, cashReceived: number, ageDocumentChecked: boolean, token: string` | `Promise<Payment>` | Gọi `POST /bookings/{id}/payments/cash` thu tiền mặt tại quầy, tính tiền thừa (`change`) và xuất vé ngay lập tức. |
| `posApi.vietqr(id, ageDocumentChecked, token)` | `id: number, ageDocumentChecked: boolean, token: string` | `Promise<Payment>` | Gọi `POST /bookings/{id}/payments/vietqr` sinh mã VietQR (`qrPayload`, `bankName`, `bankAccount`, `transferContent`). |
| `posApi.confirm(id, token)` | `id: number, token: string` | `Promise<Payment>` | Gọi `POST /bookings/{id}/payments/vietqr/confirm` xác nhận tiền đã vào tài khoản và phát hành vé. |
| `posApi.lookup(code, token)` | `code: string, token: string` | `Promise<Ticket[]>` | Hỗ trợ tra cứu linh hoạt tại cổng soát vé:<br>1. Thử `/tickets/lookup?code=...` (Mock).<br>2. Gọi `GET /tickets/{ticketCode}` (Backend thật).<br>3. Nếu người dùng nhập mã đơn `BK-...`, tự động tìm qua `GET /pos/bookings?bookingCode=...` rồi lấy danh sách vé từ `GET /bookings/{bookingId}/tickets`. |
| `posApi.validate(ticketCode, showtimeId, ageDocumentChecked, token)` | `ticketCode: string, showtimeId: number, ageDocumentChecked: boolean, token: string` | `Promise<Ticket>` | Gọi `POST /tickets/validate`. Tự động tương thích với cả Mock (`{ ticketCode, showtimeId, ageDocumentChecked }`) và Backend thật (`{ ticketCode, ageDocumentChecked }`). |

#### `frontend/shared/src/api/refunds.ts`
| Hàm | Input | Output | Kỹ thuật & Mô tả |
| :--- | :--- | :--- | :--- |
| `refundsApi.eligibility(code, token)` | `code: string, token: string` | `Promise<Eligibility>` | Gọi `GET /refunds/eligibility?ticketCode=...` kiểm tra từng vé trong đơn có đủ điều kiện hoàn hay không (trước giờ chiếu ≥ 30 phút, vé `VALID`). |
| `refundsApi.override(username, pin, token)` | `username: string, pin: string, token: string` | `Promise<{ overrideToken: string; expiresAt: string }>` | Gọi `POST /manager-overrides` với `scope: "REFUND_APPROVAL"` bằng JWT của Staff hiện tại. |
| `refundsApi.refund(body, token)` | `body: { bookingId, ticketIds, reason, overrideToken, cashConfirmed }, token: string` | `Promise<Refund>` | Gọi `POST /refunds` thực hiện hoàn các vé đã chọn sau khi Manager duyệt và xác nhận đã giao tiền mặt. |

#### `frontend/shared/src/api/management.ts`
| Hàm | Input | Output | Kỹ thuật & Mô tả |
| :--- | :--- | :--- | :--- |
| `normalizeItem<T>(resource, row)` *(internal)* | `resource: string, row: AnyRecord` | `T` | Đồng bộ tên trường giữa Backend và UI (`type` <-> `auditoriumType`, `id` <-> `seatId`, `type` <-> `seatType`, `type` <-> `ruleType`, `name` <-> `itemName`). |
| `managementApi.list<T>(resource, token, signal)` | `resource: string, token: string, signal?: AbortSignal` | `Promise<T[]>` | Đọc danh sách tài nguyên bất kỳ (`auditoriums`, `pricing/rules`, `inventory/alerts`, `staff`, `auditoriums/{id}/seats`), tự động mở gói nếu Backend trả về `PageResponse`. |
| `managementApi.save<T>(resource, body, token, id?)` | `resource: string, body: unknown, token: string, id?: number` | `Promise<T>` | Xử lý lưu (Tạo mới `POST` hoặc Cập nhật `PATCH`) cho từng loại tài nguyên với bộ chuyển đổi payload thông minh:<br>- `movies`: Loại bỏ chuỗi rỗng cho trường tùy chọn, gọi thêm `PATCH /movies/{id}/status` nếu đổi cờ `active`.<br>- `auditoriums`: Gửi `{ name, totalSeats, type }` và đồng bộ trạng thái qua `/auditoriums/{id}/status`.<br>- `auditoriums/{id}/seats`: Đóng gói `{ seats: [{ row, number, type }] }` khi tạo ghế mới.<br>- `showtimes`: Chỉ gửi `{ movieId, auditoriumId, startTime }` khi tạo mới và `{ movieId, startTime }` khi cập nhật.<br>- `concessions`: Tự động định tuyến sang `/concessions/combos` nếu `combo === true` hoặc `/concessions` nếu là món lẻ.<br>- `staff`: Gửi `{ username, fullName, email, phone, password }` khi tạo mới và `{ fullName, email, phone }` khi sửa. |
| `managementApi.action<T>(path, body, token, method?)` | `path: string, body: unknown, token: string, method = "POST"` | `Promise<T>` | Thực thi các hành động chuyên biệt: ghép/tách cặp Sweetbox (`/seats/sweetbox-pairs`), đổi trạng thái ghế/nhân viên/bảng giá (`PATCH .../status`), hủy suất chiếu (`POST /showtimes/{id}/cancel`), nhập kho (`POST /concessions/{id}/restock`). |
| `managementApi.restocks(token, filters, signal)` | `token: string, filters: Record<string, ...>, signal?: AbortSignal` | `Promise<Page<Restock>>` | Gọi `GET /inventory/restocks`, tự động chuyển đổi ngày `YYYY-MM-DD` sang mốc thời gian ISO-8601 (`T00:00:00Z` / `T23:59:59Z`) cho khớp kiểu `Instant` của Backend. |

#### `frontend/shared/src/api/reports.ts`
| Hàm | Input | Output | Kỹ thuật & Mô tả |
| :--- | :--- | :--- | :--- |
| `reports(token, from, to, signal)` | `token: string, from: string, to: string, signal?: AbortSignal` | `Promise<Reports>` | Gọi song song (`Promise.all`) 7 endpoints báo cáo (`/reports/revenue`, `/reports/revenue-breakdown`, `/reports/revenue-by-channel`, `/reports/ticket-count`, `/reports/occupancy`, `/reports/inventory-alerts`, `/reports/movies/ranking`) và gom thành đối tượng `Reports` duy nhất. |

---

### 3.4. Xác thực & Phân quyền (`auth/AuthProvider.tsx`, `auth/AuthScreen.tsx`)

#### `frontend/shared/src/auth/AuthProvider.tsx`
| Hàm / Hook / Component | Input | Output | Kỹ thuật & Mô tả |
| :--- | :--- | :--- | :--- |
| `useAuth()` | Không có | `AuthState` (`{ session, busy, error, notice, logout, refreshProfile }`) | Đọc Context xác thực; ném lỗi nếu gọi ngoài `AuthProvider`. |
| `AuthProvider({ role, children })` | `{ role: Role, children: ReactNode }` | `JSX.Element` | Quản lý vòng đời phiên đăng nhập trong bộ nhớ RAM:<br>1. **Chuyển cổng tự động (`useEffect` #1)**: Phát hiện `#auth-transfer=<code>` trên URL, xóa ngay khỏi URL bằng `window.history.replaceState`, gọi `authApi.consumeTransfer(code, role)` để nhận phiên.<br>2. **Bắt sự kiện 401 (`useEffect` #2)**: Lắng nghe sự kiện `cinema:unauthorized` từ `api.ts` để xóa phiên và báo *"Phiên không còn hiệu lực"*.<br>3. **Hẹn giờ hết hạn JWT (`useEffect` #3)**: Đặt `setTimeout` theo `session.expiresAt - Date.now()` để tự động đăng xuất ngay khi token hết hạn 30 phút.<br>4. **`submit(event)`**: Xử lý form đăng nhập / đăng ký. Kiểm tra độ dài mật khẩu ≤ 72 byte UTF-8 (giới hạn của BCrypt). Nếu đăng nhập thành công nhưng `result.user.role !== role`, tự động gọi `authApi.transfer` và `window.location.replace(transfer.redirectUrl)`.<br>5. **`logout()`**: Gọi `POST /auth/logout`. Nếu lỗi mạng, **giữ nguyên phiên** để người dùng bấm thử lại và cảnh báo chưa xác nhận thu hồi.<br>6. **`refreshProfile()`**: Gọi `GET /auth/me` cập nhật thông tin `user` mà **không** kéo dài `expiresAt` của token. |

#### `frontend/shared/src/auth/AuthScreen.tsx`
| Component | Input (Props) | Output (JSX) | Kỹ thuật & Mô tả |
| :--- | :--- | :--- | :--- |
| `AuthScreen(props)` | `{ role, mode, busy, error, notice, submit, setMode, setError, setNotice }` | `JSX.Element` | Hiển thị giao diện chia 2 cột (Banner thương hiệu + Card đăng nhập/đăng ký). Chỉ hiển thị nút chuyển đổi sang chế độ `"register"` khi `role === "CUSTOMER"`. Áp dụng HTML5 validation (`pattern`, `minLength`, `maxLength`, `required`). |

---

### 3.5. Quản lý Trạng thái Đặt vé (`booking/bookingReducer.ts`, `booking/BookingFlowProvider.tsx`)

#### `frontend/shared/src/booking/bookingReducer.ts`
| Hàm | Input | Output | Kỹ thuật & Mô tả |
| :--- | :--- | :--- | :--- |
| `bookingReducer(state, action)` | `state: BookingState`, `action: BookingAction` | `BookingState` | Hàm reducer thuần (pure function) xử lý 9 loại action:<br>- `SHOWTIME`: Nếu chọn suất chiếu khác, reset toàn bộ trạng thái về `initialBooking` với `showtime` mới.<br>- `SELECT`: Cập nhật danh sách `seatIds` đang chọn (bị khóa nếu đã có `state.hold`).<br>- `CART`: Cập nhật giỏ F&B (`CartLine[]`).<br>- `HOLD`: Lưu đối tượng `Hold`, đồng bộ `selected = action.value.seatIds`, bảo toàn `quote` nếu danh sách ghế không đổi.<br>- `QUOTE`: Lưu kết quả báo giá vé `Quote`.<br>- `BOOKING`: Lưu đơn hàng `Booking`.<br>- `RELEASE`: Xóa `hold, selected, quote, booking` khi nhả ghế.<br>- `EXPIRE`: Xóa `hold, selected, quote, cart` khi hết hạn giữ ghế (nhưng giữ lại `booking` nếu đơn đã `PAID`).<br>- `RESET`: Đưa toàn bộ state về `initialBooking`. |

#### `frontend/shared/src/booking/BookingFlowProvider.tsx`
| Hàm / Component | Input | Output | Kỹ thuật & Mô tả |
| :--- | :--- | :--- | :--- |
| `BookingFlowProvider({ children })` | `{ children: ReactNode }` | `JSX.Element` | Khởi tạo `useReducer(bookingReducer, initialBooking)` và cung cấp `{ state, dispatch }` qua Context cho toàn bộ các trang đặt vé và thanh toán. |
| `useBookingFlow()` | Không có | `{ state: BookingState, dispatch: Dispatch<BookingAction> }` | Hook truy cập trạng thái đặt vé hiện tại. |

---

### 3.6. Custom Hooks (`hooks/useQuery.ts`, `hooks/useServerCountdown.ts`, `hooks/useSeatHold.ts`)

#### `frontend/shared/src/hooks/useQuery.ts`
| Hook | Input | Output | Kỹ thuật & Mô tả |
| :--- | :--- | :--- | :--- |
| `useQuery<T>(key, load)` | `key: string` (khóa phụ thuộc), `load: (signal: AbortSignal) => Promise<T>` | `{ data?: T, loading: boolean, error?: Error, refetch: () => void }` | Tự động chạy lại hàm `load` mỗi khi `key` thay đổi hoặc gọi `refetch()`. Khi `key` đổi hoặc component unmount, gọi `controller.abort()` và đặt `active = false` để hủy request cũ và tránh race condition. |

#### `frontend/shared/src/hooks/useServerCountdown.ts`
| Hook | Input | Output | Kỹ thuật & Mô tả |
| :--- | :--- | :--- | :--- |
| `useServerCountdown(serverTime?, until?)` | `serverTime?: string` (ISO-8601), `until?: string` (ISO-8601) | `number` (số giây còn lại ≥ 0) | Tính khoảng thời gian ban đầu `initial = Date.parse(until) - Date.parse(serverTime)` và ghi mốc `start = performance.now()`. Cập nhật mỗi 250ms theo công thức `Math.max(0, Math.ceil((initial - (performance.now() - start)) / 1000))`. Không phụ thuộc vào đồng hồ hệ thống của máy người dùng. |

#### `frontend/shared/src/hooks/useSeatHold.ts`
| Hook | Input | Output | Kỹ thuật & Mô tả |
| :--- | :--- | :--- | :--- |
| `useSeatHold(showtimeId)` | `showtimeId: number` | `{ map, error, busy, loading, seconds, hold, refetch, create, extend, release }` | Quản lý toàn diện vòng đời sơ đồ ghế và giữ ghế:<br>- Polling `holdsApi.map` mỗi 5 giây + khi `focus` / `visibilitychange`.<br>- Tự động phục hồi `hold` từ `next.ownHolds` nếu mạng bị gián đoạn lúc gọi `POST /holds`.<br>- Theo dõi `useServerCountdown` và tự động `dispatch({ type: "EXPIRE" })` khi hết giờ.<br>- Cung cấp 3 hàm thao tác bất đồng bộ có khóa chống bấm đúp (`submitting.current`): `create()` (giữ ghế), `extend()` (gia hạn +2 phút), `release()` (nhả ghế). |

---

### 3.7. Tiện ích Định dạng (`utils/format.ts`)

| Hàm | Input | Output | Kỹ thuật & Mô tả |
| :--- | :--- | :--- | :--- |
| `money(value)` | `value: number` | `string` | Định dạng tiền tệ Việt Nam (`Intl.NumberFormat("vi-VN", { style: "currency", currency: "VND" })`, ví dụ `"95.000 ₫"`). |
| `dateTime(value)` | `value: string` (ISO-8601) | `string` | Định dạng ngày giờ theo múi giờ cố định `Asia/Ho_Chi_Minh` (`dateStyle: "short", timeStyle: "short"`). |
| `today()` | Không có | `string` (`YYYY-MM-DD`) | Trả về ngày hiện tại theo múi giờ `Asia/Ho_Chi_Minh` bằng locale `"en-CA"` để gán cho `<input type="date">`. |

---

### 3.8. Bố cục & Component Tái sử dụng (`layout/PortalLayout.tsx`, `components/*.tsx`)

| File & Component | Input (Props) | Output (JSX) | Kỹ thuật & Mô tả |
| :--- | :--- | :--- | :--- |
| [`PortalLayout.tsx`](../../frontend/shared/src/layout/PortalLayout.tsx) — `PortalLayout` | `{ children: ReactNode }` | `JSX.Element` | Hiển thị Sidebar điều hướng động theo `session.user.role` (`MANAGER`: 10 mục, `STAFF`: 4 mục, `CUSTOMER`: 2 mục + mục `Tài khoản`), thông tin người dùng, nút Đăng xuất, và nhãn chế độ (`"Dữ liệu mô phỏng"` hoặc `"API thật"`). |
| [`SeatMap.tsx`](../../frontend/shared/src/components/SeatMap.tsx) — `SeatMap` | `{ seats: Seat[], selected: number[], locked: boolean, onSelect: (ids: number[]) => void }` | `JSX.Element` | Sơ đồ ghế chia theo hàng (`row`). Tự động xử lý quy tắc **Sweetbox**: bấm vào 1 ghế `SWEETBOX` sẽ chọn/bỏ chọn đồng thời cả `seat.seatId` và `seat.pairSeatId`; nếu ghế cặp đôi không `AVAILABLE` thì vô hiệu hóa (`disabled`) cả cặp. Hỗ trợ đầy đủ `aria-label` và `aria-pressed` cho khả năng tiếp cận (Accessibility). |
| [`ConcessionPicker.tsx`](../../frontend/shared/src/components/ConcessionPicker.tsx) — `ConcessionPicker` | `{ items: Concession[], cart: CartLine[], onChange: (cart: CartLine[]) => void, disabled?: boolean }` | `JSX.Element` | Hiển thị các món F&B đang `active`, giá bán, cảnh báo `"Sắp hết"` hoặc `"Hết hàng"`. Giới hạn số lượng đặt trong khoảng `[0, item.availableQuantity]`. |
| [`OrderSummary.tsx`](../../frontend/shared/src/components/OrderSummary.tsx) — `OrderSummary` | `{ quote?: Quote \| null, booking?: Booking \| null, items?: Concession[], cart?: CartLine[] }` | `JSX.Element` | - Khi chưa tạo đơn (`!booking`): hiển thị giá từng ghế từ `quote`, từng món F&B trong `cart` và dòng **Tạm tính** (`quote.total + fnbTotal`) ngay lập tức.<br>- Khi đã tạo đơn (`booking`): hiển thị mã đơn `bookingCode`, trạng thái, danh sách ghế, món F&B và **Tổng thanh toán** chính thức từ server. |
| [`HoldCountdown.tsx`](../../frontend/shared/src/components/HoldCountdown.tsx) — `HoldCountdown` | `{ seconds: number, pos: boolean, extensionUsed: boolean, waiting: boolean, onExtend: () => void, busy?: boolean }` | `JSX.Element` | Hiển thị đồng hồ `MM:SS`. Đổi sang màu cảnh báo đỏ khi `seconds <= 60`. Hiển thị nút `"Gia hạn +2 phút"` khi ở POS (`pos`), đang chờ khách chuyển khoản VietQR (`waiting`), chưa gia hạn lần nào (`!extensionUsed`) và `0 < seconds <= 60`. |
| [`AgeConfirmation.tsx`](../../frontend/shared/src/components/AgeConfirmation.tsx) — `AgeConfirmation` | `{ rating: AgeRating, pos: boolean, value: boolean, onChange: (value: boolean) => void }` | `JSX.Element \| null` | Ẩn hoàn toàn nếu phim nhãn `"P"`. Với phim `"T13" / "T16" / "T18"`, hiển thị checkbox bắt buộc: `"Đã kiểm tra giấy tờ, khách đủ tuổi"` (cho Staff) hoặc `"Tôi cam kết đủ tuổi"` (cho Customer). |
| [`TicketView.tsx`](../../frontend/shared/src/components/TicketView.tsx) — `Qr` & `TicketView` | `Qr({ payload })`, `TicketView({ booking })` | `JSX.Element` | - `Qr`: Sinh ảnh Data URL từ chuỗi `payload` bằng thư viện `qrcode` (`QRCode.toDataURL`).<br>- `TicketView`: Hiển thị danh sách vé đã phát hành, mã QR từng ghế, danh sách F&B và nút `"In vé / hóa đơn"` gọi `window.print()`. |
| [`ConfirmDialog.tsx`](../../frontend/shared/src/components/ConfirmDialog.tsx) — `ConfirmDialog` | `{ title: string, children: ReactNode, onClose: () => void }` | `JSX.Element` | Sử dụng phần tử `<dialog>` gốc của trình duyệt (`dialog.showModal()`) để tự động khóa focus (focus trap) và hỗ trợ phím `Escape` (`onCancel`). |
| [`Feedback.tsx`](../../frontend/shared/src/components/Feedback.tsx) — `Feedback` | `{ loading?: boolean, error?: Error \| string, empty?: boolean, retry?: () => void }` | `JSX.Element \| null` | Hiển thị thống nhất 3 trạng thái: đang tải (`"Đang tải…"`), lỗi (`role="alert"` kèm nút `"Thử lại"`), hoặc danh sách rỗng (`"Chưa có dữ liệu."`). |
| [`Pagination.tsx`](../../frontend/shared/src/components/Pagination.tsx) — `Pagination` | `{ meta: PageMeta, onPage: (page: number) => void }` | `JSX.Element` | Hiển thị nút `"Trước"`, `"Trang X / Y · N kết quả"`, và nút `"Sau"`. |
| [`AccountPanel.tsx`](../../frontend/shared/src/components/AccountPanel.tsx) — `AccountPanel` | Không có | `JSX.Element` | Hiển thị thông tin chi tiết của người dùng hiện tại (`username`, `email`, `phone`, `role`) và nút `"Kiểm tra phiên đăng nhập →"` gọi `refreshProfile()`. |

---

### 3.9. Các Trang Nghiệp vụ (`pages/BookingPages.tsx`, `pages/StaffPages.tsx`, `pages/ManagementPages.tsx`)

#### `frontend/shared/src/pages/BookingPages.tsx`
| Component Trang | Route | Mô tả Nghiệp vụ, State & Hàm Xử lý |
| :--- | :--- | :--- |
| `MovieListPage` | Customer `/movies` | Tìm kiếm phim theo `keyword` và phân trang `page`. Hiển thị danh sách thẻ phim đang chiếu (`active: true`). |
| `MovieDetailPage` | Customer `/movies/:id` | Hiển thị thông tin chi tiết phim, link trailer và danh sách các suất chiếu theo ngày (`date`, mặc định `today()`). Bấm `"Chọn ghế"` sẽ lưu `SHOWTIME` vào `BookingFlowProvider` và điều hướng sang `/showtimes/:id/book`. |
| `ShowtimeSearchPage` | Staff `/pos` | Màn hình chọn nhanh suất chiếu tại quầy POS: lọc theo ngày (`date`) và phim (`movieId`). Bấm `"Chọn suất"` điều hướng sang `/pos/showtimes/:id`. |
| `BookingPage` | Customer `/showtimes/:id/book`<br>Staff `/pos/showtimes/:id` | - Tải thông tin suất chiếu (`catalogApi.showtime`), danh sách F&B (`catalogApi.concessions`) và sơ đồ ghế (`useSeatHold`).<br>- Tự động gọi `POST /pricing/quote` mỗi khi danh sách ghế chọn (`state.selected`) hoặc ghế giữ (`hold.hold`) thay đổi để hiển thị giá vé tức thời trong `<OrderSummary />`.<br>- Cung cấp các nút: `"Giữ các ghế đã chọn"`, `"Chọn lại ghế"` (nhả ghế đang giữ), và `"Tiếp tục thanh toán"`. |
| `CheckoutPage` | Customer `/checkout`<br>Staff `/pos/checkout` | - Hiển thị `<HoldCountdown />`, `<OrderSummary />`, và `<AgeConfirmation />`.<br>- Nút `"Tạo đơn · lấy tổng thanh toán"` gọi `bookingsApi.create`.<br>- **Với Staff POS**: Nhập `"Tiền khách đưa"`, tự động tính `"Tiền thừa dự kiến"`, bấm `"Thu tiền mặt"` (`posApi.cash`) hoặc `"Tạo VietQR"` (`posApi.vietqr`) -> hiển thị mã QR chuyển khoản -> tích `"Đã kiểm tra tiền vào tài khoản"` -> bấm `"Xác nhận đã nhận tiền"` (`posApi.confirm`). Khi `PAID`, tự động chuyển sang `/pos/bookings/:id/receipt`.<br>- **Với Customer Online**: Bấm `"Tạo liên kết VNPay"` (`bookingsApi.vnpay`) -> mở link VNPay ở tab mới (`target="_blank"`). Trong lúc chờ, tab hiện tại tự động polling `bookingsApi.get` mỗi 3 giây và lắng nghe `BroadcastChannel("cinema-reference-payments")` để tự động hiển thị vé ngay khi Server xác nhận `PAID`.<br>- Cho phép `"Sửa F&B"` (mở modal `<ConcessionPicker />` gọi `PATCH /bookings/{id}`) hoặc `"Hủy đơn chờ"` (`POST /bookings/{id}/cancel`) trước khi thanh toán. |
| `PaymentReturnPage` | `/payment-return`<br>`/payments/vnpay/return` | Trang đích khi trình duyệt quay về từ VNPay. Nếu URL có `vnp_TxnRef`, gọi `GET /payments/vnpay/return` để hiển thị trạng thái đối chiếu từ Backend. Trong chế độ Dev/Mock (`mockBooking`), cung cấp nút `"Mô phỏng IPN thành công"` phát tín hiệu qua `BroadcastChannel`. |
| `BookingHistoryPage` | Customer `/bookings`<br>Staff `/transactions` | Hiển thị danh sách đơn đặt vé có phân trang. Với Staff POS, hỗ trợ bộ lọc theo `Mã đơn`, `Ngày`, và `Thanh toán` (`CASH` / `VIETQR`). |
| `BookingDetailPage` | Customer `/bookings/:id`<br>Staff `/pos/bookings/:id/receipt`<br>Staff `/transactions/:id` | Hiển thị chi tiết đơn hàng (`<OrderSummary />`), danh sách vé và mã QR (`<TicketView />`), thông tin hoàn tiền (nếu có) và liên kết `"Yêu cầu hoàn vé tại quầy"` cho Staff. |

#### `frontend/shared/src/pages/StaffPages.tsx`
| Component | Route | Mô tả Nghiệp vụ, State & Hàm Xử lý |
| :--- | :--- | :--- |
| `ManagerOverrideDialog` | Modal trong `/refunds` | Nhận `{ bookingId, ticketIds, reason, onClose, onDone }`.<br>- Bước 1 (`approve`): Nhập `Tài khoản Manager` và `PIN Manager`, xóa ngay `pin` khỏi state, gọi `refundsApi.override`.<br>- Bước 2 (`refund`): Hiển thị thời hạn phê duyệt, yêu cầu tích `"Manager đã bàn giao đủ tiền mặt cho khách"` (`cash`), sau đó gọi `refundsApi.refund` và trả số tiền hoàn về `onDone(result.refundAmount)`. |
| `RefundPage` | Staff `/refunds` | Nhập mã Booking hoặc Ticket -> gọi `refundsApi.eligibility` -> hiển thị danh sách vé kèm trạng thái đủ/không đủ điều kiện hoàn -> chọn các vé muốn hoàn -> tính tổng tiền vé hoàn -> nhập lý do hoàn -> mở `ManagerOverrideDialog`. |
| `TicketValidationPage` | Staff `/gate` | Chọn `"Suất chiếu tại cửa"`, nhập/quét `"Mã vé / Booking"` -> gọi `posApi.lookup` -> hiển thị thông tin vé, ghế, giờ chiếu và `<AgeConfirmation pos />` -> bấm `"Chấp nhận vé <Ghế>"` (`posApi.validate`) để chuyển vé sang trạng thái `USED`. |

#### `frontend/shared/src/pages/ManagementPages.tsx`
| Component | Route | Mô tả Nghiệp vụ, State & Hàm Xử lý |
| :--- | :--- | :--- |
| `ResourceEditor` | Modal dùng chung | Form động đa năng nhận danh sách cấu hình trường `fields: Field[]` (`text, number, date, datetime-local, checkbox, password, url, email, select, components`). Tự động ép kiểu số, boolean, mảng thành phần combo, và chuyển đổi `datetime-local` sang chuỗi ISO-8601 có múi giờ (`+07:00`). |
| `DashboardPage` | Manager `/dashboard` | Tổng quan nhanh trong ngày: số lượng phim, suất chiếu hôm nay, nhân viên đang hoạt động, cảnh báo tồn kho thấp và doanh thu trong ngày. |
| `MoviesPage` | Manager `/movies` | Tìm kiếm, phân trang, thêm mới và chỉnh sửa thông tin phim (`catalogApi.movies`, `managementApi.save("movies")`). |
| `AuditoriumsPage` | Manager `/auditoriums` | Danh sách phòng chiếu, thêm/sửa phòng (`STANDARD_2D`, `3D`, `IMAX`), dẫn link sang sơ đồ ghế của từng phòng. |
| `SeatLayoutPage` | Manager `/auditoriums/:id/seats` | Quản lý ghế trong phòng: thêm ghế mới, sửa loại ghế (`STANDARD`, `VIP`, `SWEETBOX`), ghép cặp 2 ghế Sweetbox cùng hàng liền kề (`pair()`), tách cặp (`unpair()`), và khóa/mở ghế (`PATCH /seats/{id}/status`). |
| `ShowtimesPage` | Manager `/showtimes` | Lọc suất chiếu theo ngày, tạo mới / đổi giờ chiếu (tự động bắt lỗi `409 SHOWTIME_OVERLAP` ngay trên form), và hủy suất chiếu (`POST /showtimes/{id}/cancel`). |
| `PricingPage` | Manager `/pricing` | Quản lý bảng quy tắc phụ thu giá (`SEAT_TYPE`, `DAY_TYPE`, `TIME_SLOT`, `AUDITORIUM_TYPE`), thêm/sửa và bật/tắt trạng thái quy tắc. |
| `ConcessionsPage` | Manager `/concessions` | Quản lý danh mục mặt hàng F&B lẻ và Combo (cho phép chọn số lượng từng món thành phần trong Combo). |
| `InventoryPage` | Manager `/inventory` | Theo dõi tồn kho F&B, ngưỡng cảnh báo sắp hết (`lowStockThreshold`) và thực hiện `"Nhập kho"` cộng dồn số lượng kèm ghi chú (`POST /concessions/{id}/restock`). |
| `RestockHistoryPage` | Manager `/inventory/history` | Tra cứu lịch sử nhập kho bất biến (Immutable Restock History) lọc theo mặt hàng và khoảng ngày (`from`, `to`), hiển thị tồn trước/sau nhập và người nhập. |
| `StaffPage` | Manager `/staff` | Danh sách tài khoản nhân viên, tạo nhân viên mới, cập nhật thông tin và Khóa / Mở khóa tài khoản (`PATCH /staff/{id}/status`). |
| `ReportsPage` | Manager `/reports` | Báo cáo thống kê theo khoảng thời gian (`from` -> `to`, kiểm tra chặn `from > to`): Doanh thu gộp, Tiền hoàn, Doanh thu ròng, Doanh thu vé vs F&B, Kênh POS vs Online, Tỷ lệ lấp đầy ghế (Occupancy), Cảnh báo tồn kho và Bảng xếp hạng doanh thu theo phim. |

---

### 3.10. Hệ thống Mock MSW & Bộ Kiểm thử (`mocks/*`, `*.test.tsx`, `tests/*.spec.ts`)

- **`frontend/shared/src/mocks/core.ts` & `fixtures.ts`**:
  - Cung cấp máy trạng thái (stateful mock server) cho các endpoint dùng trong demo frontend; việc đối chiếu toàn bộ contract còn mở; không hỗ trợ endpoint sửa/hủy booking.
- **Unit & Component Tests (Vitest; số lượng và kết quả theo lần chạy)**:
  - `api.test.ts`: Kiểm thử `request`, `requestPage`, `query`, hủy request bằng `AbortController`, timeout 15s và phát sự kiện `cinema:unauthorized` khi gặp 401.
  - `bookingReducer.test.ts`: Kiểm thử tính cô lập của reducer khi đổi suất chiếu, chọn ghế, giữ ghế và hết hạn.
  - `SeatMap.test.tsx`: Kiểm thử chọn/bỏ chọn cặp ghế Sweetbox bằng bàn phím/chuột và khóa cặp khi ghế đối tác không trống.
  - `useServerCountdown.test.tsx`: Kiểm thử đếm ngược chính xác theo `serverTime` bất kể đồng hồ máy khách lệch giờ.
  - `useQuery.test.tsx`: Kiểm thử hủy kết quả cũ (stale response) khi tham số truy vấn thay đổi nhanh.
  - `useSeatHold.test.tsx`: Kiểm thử tự động phục hồi `hold` từ `ownHolds` khi mất phản hồi mạng và đảm bảo unmount không làm mất ghế đang giữ.
- **End-to-End Tests (Playwright)**:
  - **Mock E2E trên Chromium, Firefox, WebKit**: `customer.mock.spec.ts`, `manager.mock.spec.ts`, `pos.mock.spec.ts`, `refund-gate.mock.spec.ts`.
  - **Live E2E trên `chromium-live` kết nối Backend thật + PostgreSQL**: `auth.spec.ts`, `booking.live.spec.ts`, `management.live.spec.ts` và `g5.live.spec.ts`; xem hướng dẫn kiểm thử để biết phạm vi và cấu hình.

---

## 4. Hướng dẫn Chạy Dự án & Kiểm thử

### 4.1. Yêu cầu Môi trường
- **Node.js**: `>= 22.12.0` và **npm**: `11.10.1`
- **JDK**: `JDK 21` (biến môi trường `JAVA_HOME` trỏ tới JDK 21 đầy đủ có `javac`)
- **Cơ sở dữ liệu**: PostgreSQL 16/17/18 (hoặc Docker Compose)

---

### 4.2. Cách 1: Chạy Nhanh Frontend ở Chế độ Mô phỏng (Mock Mode - Không cần Backend/DB)

Chế độ này sử dụng Mock Service Worker (MSW) ngay trên trình duyệt để trải nghiệm đầy đủ tất cả màn hình của cả 3 vai trò:

```bash
cd frontend
npm ci

# Mở 3 terminal riêng biệt trong thư mục frontend:
VITE_USE_MOCKS=true npm run dev:manager   # Chạy tại http://localhost:3001
VITE_USE_MOCKS=true npm run dev:staff     # Chạy tại http://localhost:3002
VITE_USE_MOCKS=true npm run dev:customer  # Chạy tại http://localhost:3003
```

- **Tài khoản đăng nhập ở chế độ Mock**:
  - Manager (`http://localhost:3001`): `manager01` / mật khẩu `Reference123!`, mã PIN duyệt hoàn vé: `123456`.
  - Staff (`http://localhost:3002`): `staff01` / mật khẩu `Reference123!`.
  - Customer (`http://localhost:3003`): `customer01` / mật khẩu `Reference123!`.

---

### 4.3. Cách 2: Chạy Full-Stack với Backend thật + PostgreSQL

#### A. Trên Windows (PowerShell + Docker Compose)
Từ thư mục gốc của dự án:
```powershell
# 1. Sinh file .env với khóa ngẫu nhiên (mở file .env để xem DEMO_PASSWORD và DEMO_MANAGER_PIN)
powershell -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\setup\Initialize-LocalEnvironment.ps1

# 2. Khởi động PostgreSQL tại cổng 5433 qua Docker Compose
docker compose up -d --wait

# 3. Khởi động Backend Spring Boot tại http://localhost:8080 (Swagger: http://localhost:8080/swagger-ui/index.html)
powershell -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\setup\Start-Backend.ps1
```

Sau đó mở 3 cửa sổ terminal khác trong thư mục `frontend/`:
```powershell
cd frontend
npm ci
npm run dev:manager   # http://localhost:3001 (user: manager01)
npm run dev:staff     # http://localhost:3002 (user: staff01)
npm run dev:customer  # http://localhost:3003 (user: customer01)
```

#### B. Trên Linux / macOS
```bash
# 1. Khởi động PostgreSQL (qua Docker hoặc PostgreSQL local trên cổng 5433, tạo database 'cinema_db')
docker compose up -d --wait

# 2. Khởi chạy Backend với profile local
cd backend
export DB_URL="jdbc:postgresql://127.0.0.1:5433/cinema_db"
export DB_USERNAME="cinema"
export DB_PASSWORD="cinema_local_password"
export JWT_SECRET="local-verification-secret-0123456789abcdef"
export SPRING_PROFILES_ACTIVE="local"
export DEMO_PASSWORD="Reference123!"
export DEMO_MANAGER_PIN="123456"
export CINEMA_BANK_ACCOUNT="0123456789"
export CINEMA_BANK_NAME="Vietcombank"
export VNPAY_TMN_CODE="TESTCODE"
export VNPAY_HASH_SECRET="local-test-secret-for-hmac"
export VNPAY_PAY_URL="https://sandbox.vnpayment.vn/paymentv2/vpcpay.html"
export VNPAY_RETURN_URL="http://localhost:3003/payments/vnpay/return"
export VNPAY_IPN_URL="http://localhost:8080/api/v1/payments/vnpay/ipn"
./mvnw spring-boot:run
```

Trong 3 terminal khác, chạy 3 cổng Frontend kết nối trực tiếp vào Backend thật (`http://localhost:8080/api/v1`):
```bash
cd frontend
npm run dev:manager   # http://localhost:3001 (manager01 / Reference123!)
npm run dev:staff     # http://localhost:3002 (staff01 / Reference123!)
npm run dev:customer  # http://localhost:3003 (customer01 / Reference123!)
```

---

### 4.4. Chạy Bộ Kiểm thử Tự động (Build, Unit Test, Mock E2E & Live E2E)

```bash
cd frontend

# 1. Kiểm tra kiểu TypeScript và Build Production cho cả 3 cổng
npm run build

# 2. Chạy toàn bộ Unit & Component Tests (Vitest)
npm test

# 3. Chạy kiểm thử E2E ở chế độ Mock trên cả 3 trình duyệt (Chromium, Firefox, WebKit)
npm run test:e2e:mock

# 4. Chạy kiểm thử E2E trực tiếp với Backend thật + PostgreSQL (sau khi đã bật Backend local và nạp scripts/testing/seed-seat-demo.sql)
DEMO_PASSWORD="Reference123!" DEMO_SEED_PASSWORD="Reference123!" npm run test:e2e:live
```
