# Kiểm thử Sprint D — Seat Holding

Phạm vi: API seat map/hold/extend/release, cleanup, transaction bàn giao E, guard layout và Sweetbox chung B/C/D. Dùng code mẫu từ `.codex-reference` có điều chỉnh trên Entity/Repository và contract hiện hành; không chạy backend reference như bằng chứng cho backend đã tích hợp.

## Chạy kiểm thử

Cần JDK 21 đầy đủ và PostgreSQL thật. Với Docker đang chạy:

```bash
cd backend
bash mvnw -B -ntp verify
```

Mặc định test dùng Testcontainers PostgreSQL 18.1. Hoặc cấu hình database test riêng, rỗng ở lần đầu:

```bash
export DB_TEST_URL=jdbc:postgresql://127.0.0.1:5432/cinema_seat_test
export DB_TEST_USERNAME=cinema_test
export DB_TEST_PASSWORD='your-test-database-password'
export SPRING_PROFILES_ACTIVE=test
cd backend
bash mvnw -B -ntp verify
```

Test ghi dữ liệu có tên riêng; một số fixture cố tình tạo pair/inventory sai để kiểm tra từ chối. Không trỏ DB_TEST_URL vào database phát triển hoặc production. Không thay bằng H2. Mỗi integration test dùng transaction thật/PostgreSQL, concurrency dùng executor/latch; MutableClock điều khiển biên thời gian mà không chờ 5–7 phút thật. Scheduler trong fixture D có delay một giờ; test gọi cleanup/expiration trực tiếp để kiểm soát race.

## Các ca kiểm chứng

| Bộ test | Hành vi phải đúng |
|---|---|
| SeatHoldIntegrationTest | Giữ một/nhiều ghế, Sweetbox đủ cặp, rollback cả nhóm khi một ghế bận/BOOKED, inventory độc lập giữa suất, retry không reset đồng hồ, session ownership, hết hạn không cần scheduler, JWT/RBAC/validation/privacy, suất đã bắt đầu |
| SeatHoldContractIntegrationTest | Retry subset/superset/gộp nhóm bị từ chối; biên 239/240/299/300/420 giây; release cũ không clear hold mới; clear metadata/version; GET không ghi DB; mapping sai dù cùng số dòng; ID nguyên nghiêm ngặt/null/field lạ; giới hạn sau mở rộng; error path che token; độ chính xác microsecond của PostgreSQL; chạy script audit với fixture đúng/sai |
| SeatHoldConcurrencyTest | Staff/Customer tranh ghế chỉ một bên thắng; không partial hold; chỉ gia hạn một lần; cleanup không xóa hold vừa extend/BOOKED; lock timeout HTTP 409; D đọc giờ suất đã được C PATCH trong lúc chờ; C recheck trạng thái sau chờ D; candidate cleanup cũ recheck hạn mới; release cũ recheck token sau reclaim |
| CheckoutSeatIntegrationTest | MANDATORY caller transaction; rollback lỗi E kéo BOOKED về HOLDING; sai owner/expired không book; khớp chính xác showtime và expectedSeatIds, kể cả membership bị mất; markBooked kiểm lại hạn sau validation trước đó |
| SeatLayoutGuardIntegrationTest | HTTP create/update/pair/unpair bị khóa kể cả lịch quá khứ; DB không đổi; active phòng vẫn đổi được; null list element trả 400 không partial insert; race layout/C CREATE giữ đúng tập seatId inventory |
| SweetboxPairValidatorTest và SweetboxIntegrationTest | Candidate cùng hàng/liền kề/cùng phòng/đúng loại; đối xứng; từ chối pair sai trước write; hai pair candidate hợp lệ tranh chung một ghế chỉ một cặp đối xứng thắng |
| HoldExpirationSchedulerTest | Candidate bị lock timeout không ngăn xử lý candidate sau |
| Hồi quy A/B/C | Auth/revocation/transfer/RBAC, Master Data, stock/pricing, Showtime CREATE/PATCH/time/locking vẫn giữ contract |

Race C/D dùng pg_stat_activity để xác nhận request thực sự đang chờ lock trước khi cho transaction giữ lock commit; không lấy việc hai request cùng bắt đầu làm bằng chứng rằng đã kiểm tra recheck sau chờ.

## Audit inventory trước tích hợp

```bash
psql "$AUDIT_DATABASE_URL" -v ON_ERROR_STOP=1 -f scripts/testing/check-showtime-inventory.sql
```

Đặt AUDIT_DATABASE_URL cho database cần kiểm tra, dùng tài khoản chỉ đọc nếu có. Script chỉ SELECT, gồm hai result set: suất thiếu/thừa mapping theo ID và các physical pair sai. Hai result set rỗng nghĩa là không phát hiện lỗi trong database đó. Kết quả test fixture không phải kết luận dữ liệu phát triển/production đã sạch. Guard ngăn lỗi mới, không tự sửa lỗi cũ. Không DELETE/rebuild inventory/Ticket để làm audit qua.

## Kết quả thực chạy

Ngày 06/11/2026 (Asia/Ho_Chi_Minh), backend tích hợp trong repository, PostgreSQL 16.15 test riêng tại localhost:55450/database seat_holding_test, JDK Temurin 21.0.13+9 và Maven Wrapper 3.9.12. Dùng dependency đã cache, Flyway V1–V5 và Hibernate validate. Lệnh thực chạy tương đương Maven verify ở trên, có `-o -B -ntp` và đường dẫn cache local.

**Maven verify: BUILD SUCCESS; 152 tests, 0 failures, 0 errors, 0 skipped.** Bao gồm regression A/B/C và các ca D/guard/validator/scheduler, không cộng kết quả test của backend reference. Báo cáo máy sinh: `backend/target/surefire-reports`; log local: `.cache/seat-holding-20261106/verify.log` (không đưa cache/build outputs vào commit).

Chưa chạy Testcontainers PostgreSQL 18.1 trong lần xác minh này, chưa audit database phát triển/production và chưa demo qua hai trình duyệt. Các ca HTTP dùng JWT thật/MockMvc và PostgreSQL thật; không coi đó là nghiệm thu giao diện bán vé hoặc backend Booking/Payment/Ticket của E.
