# Kiểm thử Sprint E — Checkout, Payment và Ticket

Phạm vi: báo giá, tạo Booking, checkout transaction, thanh toán POS/VNPay, phát hành
Ticket, soát vé, lịch sử CUSTOMER và lịch sử POS. Test dùng PostgreSQL thật
(Testcontainers hoặc `DB_TEST_URL`), không dùng H2; HTTP đi qua MockMvc với JWT thật,
không coi đây là nghiệm thu giao diện.

## Chạy kiểm thử

```bash
cd backend
bash mvnw -B -ntp verify
```

Mặc định Testcontainers PostgreSQL 18.1. Hoặc dùng database test riêng, rỗng ở lần đầu:

```bash
export DB_TEST_URL=jdbc:postgresql://127.0.0.1:5432/cinema_sprint_e_test
export DB_TEST_USERNAME=cinema_test
export DB_TEST_PASSWORD='your-test-database-password'
export SPRING_PROFILES_ACTIVE=test
cd backend
bash mvnw -B -ntp verify
```

Không trỏ `DB_TEST_URL` vào database phát triển/production. Test dùng chung một Spring
context và một database Testcontainers giữa các class, nên fixture phải có ngày/phòng/
mã riêng; những test có dữ liệu dễ đụng (POS history) dùng ngày 2040 và phòng riêng.

## Bảng test case

Tổng: **68** test trong 8 class Sprint E. Mã TC chỉ dùng để truy vết; tên method là
nguồn chính xác trong mã.

| TC | Class / method | Hành vi phải đúng |
|---|---|---|
| TC-E-001 | BookingCreationIntegrationTest — `pricesTimeSurchargeExactlyOnceForFridayWeekendOrAfter18h` | Phụ phí thời gian chỉ cộng một lần khi vừa cuối tuần vừa sau 18:00 |
| TC-E-002 | BookingCreationIntegrationTest — `pricesRoomAndSeatSurchargesPerPhysicalSeat` | Phụ phí phòng/loại ghế tính riêng từng ghế vật lý |
| TC-E-003 | BookingCreationIntegrationTest — `quoteEndpointReturnsBreakdownAndRejectsSeatsOutsideTheRoom` | Quote trả breakdown đúng; ghế ngoài phòng trả `INVALID_SEAT` |
| TC-E-004 | BookingCreationIntegrationTest — `sweetboxBookingCreatesTwoIndependentTicketsAndQuotedPrices` | Sweetbox tạo hai dòng vé và sau thanh toán có hai Ticket độc lập |
| TC-E-005 | BookingCreationIntegrationTest — `createsBookingWithConcessionSnapshotWithoutDeductingStock` | Booking lưu snapshot F&B nhưng không trừ kho |
| TC-E-006 | BookingCreationIntegrationTest — `rejectsHoldOwnedByAnotherSession` | Hold của phiên khác bị từ chối |
| TC-E-007 | BookingCreationIntegrationTest — `rejectsExpiredHold` | Hold hết hạn bị từ chối |
| TC-E-008 | BookingCreationIntegrationTest — `rejectsClientSuppliedPricesAsUnknownFields` | Client gửi `price`/`totalAmount` bị từ chối do field lạ |
| TC-E-009 | BookingCreationIntegrationTest — `validatesConcessionItemsBeforeAnyStockWork` | F&B sai/ngừng bán/trùng bị chặn trước khi chạm kho |
| TC-E-010 | BookingCreationIntegrationTest — `requiresAgeConfirmationForCustomerOnT16ButNotStaff` | CUSTOMER phải xác nhận tuổi với T16; STAFF không gửi cờ này |
| TC-E-011 | BookingCreationIntegrationTest — `secondBookingForTheSameHoldReturnsConflict` | Hold thứ hai trả `BOOKING_ALREADY_EXISTS` |
| TC-E-012 | BookingCreationIntegrationTest — `concurrentBookingsForTheSameHoldCreateExactlyOneBooking` | Hai request đồng thời cùng token chỉ tạo một Booking |
| TC-E-013 | BookingCreationIntegrationTest — `returnsBookingToOwnerAndHidesItFromOthers` | Chủ sở hữu đọc được; người khác nhận 404 |
| TC-E-014 | BookingFoundationIntegrationTest — `migrationV6IsApplied` | Migration V6 (Booking/Payment/Ticket) đã áp dụng |
| TC-E-015 | BookingFoundationIntegrationTest — `generatesBookingCodesFromTheDailyCounterInBusinessZone` | Mã Booking sinh từ counter ngày theo `Asia/Ho_Chi_Minh` |
| TC-E-016 | BookingFoundationIntegrationTest — `persistsBookingPlannedSeatsTicketPaymentAndAgeVerification` | Lưu đúng booking_seats/ticket/payment/age_verification |
| TC-E-017 | BookingFoundationIntegrationTest — `uniqueActiveTicketIndexBlocksSecondValidTicketForSameShowtimeSeat` | Unique index chặn Ticket VALID thứ hai cho cùng ghế/suất |
| TC-E-018 | CheckoutSeatIntegrationTest — `requiresCallerTransactionAndRollsBackWithCaller` | `CheckoutSeatService` bắt buộc transaction caller và rollback theo caller |
| TC-E-019 | CheckoutSeatIntegrationTest — `expiredOrWrongOwnerCannotBook` | Hold hết hạn hoặc sai owner không mark BOOKED |
| TC-E-020 | CheckoutSeatIntegrationTest — `requiresExactShowtimeAndSeatMembershipIncludingLostRows` | Phải khớp đúng showtime và toàn bộ expected seat IDs, kể cả membership bị mất |
| TC-E-021 | CheckoutSeatIntegrationTest — `transitionRechecksExpiryAfterEarlierValidation` | `markBooked` kiểm lại hạn ngay trước khi chuyển trạng thái |
| TC-E-022 | CheckoutServiceIntegrationTest — `finalizesPaidBookingWithTicketsStockSalesAndSnapshots` | Checkout phát hành Ticket, trừ kho, ghi SALE và snapshot đúng |
| TC-E-023 | CheckoutServiceIntegrationTest — `insufficientStockMidwayRollsBackSeatsStockTicketsAndPayment` | Thiếu kho giữa chừng rollback ghế/kho/Ticket/Payment |
| TC-E-024 | CheckoutServiceIntegrationTest — `concurrentFinalizeOfTheSameBookingSucceedsExactlyOnce` | Hai checkout đồng thời cùng Booking chỉ một lần thành công |
| TC-E-025 | CheckoutServiceIntegrationTest — `expiredHoldIsRejectedWithoutTicketsOrPayment` | Hold hết hạn không tạo Ticket/Payment |
| TC-E-026 | CheckoutServiceIntegrationTest — `twoBookingsCompetingForTheLastUnitLeaveStockNonNegative` | Hai Booking tranh đơn vị F&B cuối cùng không làm kho âm |
| TC-E-027 | CheckoutServiceIntegrationTest — `changedPriceRollsBackAndDoesNotIssueTickets` | Giá đổi trả `PRICE_CHANGED`, không phát hành Ticket |
| TC-E-028 | CheckoutServiceIntegrationTest — `expectedAmountMustMatchThePendingPayment` | Số tiền checkout phải khớp Payment PENDING |
| TC-E-029 | CheckoutServiceIntegrationTest — `failedPaymentCannotBeReused` | Payment FAILED không được dùng lại |
| TC-E-030 | CheckoutServiceIntegrationTest — `cancelledBookingCannotBeFinalized` | Booking CANCELLED không checkout được |
| TC-E-031 | PaymentDomainTest — `markPaidAndMarkFailedOnlyWorkFromPending` | Chỉ PENDING mới chuyển PAID/FAILED |
| TC-E-032 | PaymentDomainTest — `recordCashRequiresCashMethodAndEnoughMoney` | `recordCash` chỉ cho CASH và đủ tiền |
| TC-E-033 | PaymentDomainTest — `confirmByStaffRequiresAPositiveStaffId` | `confirmByStaff` bắt buộc staff ID dương |
| TC-E-034 | PaymentDomainTest — `gatewayDetailsOnlyWorkForGatewayOnline` | Chi tiết gateway chỉ dùng cho `GATEWAY_ONLINE` |
| TC-E-035 | PosPaymentIntegrationTest — `cashPaymentUsesTotalAmountAndReturnsChange` | Cash dùng `totalAmount`, trả tiền thừa đúng |
| TC-E-036 | PosPaymentIntegrationTest — `cashPaymentRejectsShortAmountWithoutCreatingPayment` | Tiền mặt thiếu bị từ chối, không tạo Payment |
| TC-E-037 | PosPaymentIntegrationTest — `cashPaymentValidationRejectsMissingCashReceived` | Thiếu `cashReceived` trả 400 |
| TC-E-038 | PosPaymentIntegrationTest — `cashPaymentRequiresAgeDocumentForRestrictedMovieAndRecordsIt` | Phim hạn chế tuổi bắt buộc age check và ghi stage POS |
| TC-E-039 | PosPaymentIntegrationTest — `vietQrCreateIsPendingAndConfirmIssuesTicketsOnce` | VietQR tạo PENDING; confirm phát hành Ticket đúng một lần |
| TC-E-040 | PosPaymentIntegrationTest — `vietQrCreateIsIdempotentForTheSamePendingPayment` | Tạo VietQR lặp dùng lại cùng Payment |
| TC-E-041 | PosPaymentIntegrationTest — `cashCannotUseABookingThatAlreadyHasAVietQrPayment` | Cash không dùng Booking đã có Payment VietQR |
| TC-E-042 | PosPaymentIntegrationTest — `vietQrCreateRequiresAgeDocumentForRestrictedMovie` | Tạo VietQR cho phim hạn chế tuổi phải có age check |
| TC-E-043 | PosPaymentIntegrationTest — `vietQrConfirmRequiresAnExistingPosAgeAudit` | Confirm đòi age_verifications stage POS đã có |
| TC-E-044 | PosPaymentIntegrationTest — `vietQrConfirmWithoutAPaymentReturnsNotFound` | Confirm khi chưa tạo Payment trả `PAYMENT_NOT_FOUND` |
| TC-E-045 | PosPaymentIntegrationTest — `customerCannotCallPosPaymentEndpoints` | CUSTOMER gọi endpoint POS nhận 403 |
| TC-E-046 | PosPaymentIntegrationTest — `anotherStaffCanCompleteAPosPaymentAndIsRecorded` | STAFF khác thanh toán được và được ghi `confirmed_by_staff_id` |
| TC-E-047 | PosPaymentIntegrationTest — `onlineBookingCannotBePaidAtTheCounter` | Booking ONLINE bị từ chối tại quầy |
| TC-E-048 | PosPaymentIntegrationTest — `expiredHoldRollsBackThePendingPayment` | Hold hết hạn rollback cả Payment PENDING |
| TC-E-049 | PosPaymentIntegrationTest — `concurrentCashRequestsSerializeOnTheBookingAndIssueOneTicket` | Hai cash đồng thời tuần tự hóa, chỉ phát một bộ Ticket |
| TC-E-050 | TicketPosHistoryIntegrationTest — `customerCanReadOnlyTheirOwnTicketAndBookingTickets` | CUSTOMER A không đọc được vé/Booking của CUSTOMER B |
| TC-E-051 | TicketPosHistoryIntegrationTest — `meBookingsAreScopedToJwtUserAndPaginated` | `/me/bookings` chỉ trả dữ liệu JWT user và phân trang đúng |
| TC-E-052 | TicketPosHistoryIntegrationTest — `posHistoryExcludesOnlineBookingsAndSupportsFilters` | POS history loại Booking ONLINE và lọc code/date/method đúng |
| TC-E-053 | TicketPosHistoryIntegrationTest — `gateRequiresAgeDocumentForRestrictedMovieAndRecordsGateAudit` | Gate bắt buộc age check và ghi `age_verifications` stage GATE |
| TC-E-054 | TicketPosHistoryIntegrationTest — `gateRejectsCancelledTicketsAndTicketsOutsideTheShowtimeWindow` | Vé CANCELLED và ngoài khung giờ bị từ chối |
| TC-E-055 | TicketPosHistoryIntegrationTest — `concurrentScansAdmitOnlyOne` | Hai lần quét song song chỉ một lần thành công |
| TC-E-056 | VnPayPaymentIntegrationTest — `createsPaymentUrlForTheCustomersOnlineBooking` | Tạo URL/tham số/chữ ký VNPay đúng |
| TC-E-057 | VnPayPaymentIntegrationTest — `retryWhilePendingReusesThePaymentRowAndRotatesTheTxnRef` | Retry khi PENDING dùng lại Payment và xoay `vnp_txn_ref` |
| TC-E-058 | VnPayPaymentIntegrationTest — `ipnSuccessIssuesTicketsExactlyOnce` | IPN thành công phát hành đúng một bộ Ticket |
| TC-E-059 | VnPayPaymentIntegrationTest — `parallelIpnDeliversOneTicketSet` | Hai IPN song song chỉ một bộ Ticket |
| TC-E-060 | VnPayPaymentIntegrationTest — `returnUrlOnlyShowsStateAndNeverIssuesTickets` | Return URL không finalize, không phát hành Ticket |
| TC-E-061 | VnPayPaymentIntegrationTest — `invalidSignatureIsRejectedWith97` | Sai chữ ký trả `"97"` |
| TC-E-062 | VnPayPaymentIntegrationTest — `wrongAmountIsRejectedWith04` | Sai số tiền trả `"04"` |
| TC-E-063 | VnPayPaymentIntegrationTest — `failedGatewayResponseMarksPaymentFailedAndBlocksRetry` | Gateway trả lỗi ghi Payment FAILED và chặn retry |
| TC-E-064 | VnPayPaymentIntegrationTest — `successfulIpnAfterHoldExpiryParksBookingForManualDecision` | IPN thành công sau khi hold hết hạn → `EXCEPTION_PENDING_DECISION`, trả `"00"` |
| TC-E-065 | VnPayPaymentIntegrationTest — `priceChangedAfterBookingParksBookingForManualDecision` | Giá đổi sau Booking → `EXCEPTION_PENDING_DECISION`, trả `"00"` |
| TC-E-066 | VnPayPaymentIntegrationTest — `anotherCustomerCannotCreatePaymentForTheBooking` | CUSTOMER khác nhận 404 |
| TC-E-067 | VnPayPaymentIntegrationTest — `staffCannotCallTheCustomerPaymentEndpoint` | STAFF gọi endpoint CUSTOMER nhận 403 |
| TC-E-068 | VnPayPaymentIntegrationTest — `restrictedMovieWithoutAgeConfirmationIsRejected` | Phim hạn chế tuổi thiếu xác nhận trả 422 |

## Test chậm và flaky

- Không có test E nào bị flaky trong các lần chạy liên tiếp; test concurrency dùng
  executor/latch và khóa DB thật, không dùng `Thread.sleep` để kết luận.
- Các class chậm nhất thường là class đầu tiên khởi động Spring context/Testcontainers
  (AuditoriumSeat ~27–29 s, Staff ~20–22 s, Showtime ~19 s, Foundation ~18 s). Trong E,
  `BookingCreationIntegrationTest` ~7–8,5 s, `VnPayPaymentIntegrationTest` ~3,9–4,5 s,
  `PosPaymentIntegrationTest` ~3,2–3,3 s, `TicketPosHistoryIntegrationTest` ~1,2–2 s;
  phần còn lại dưới 3,5 s.
- Test POST history dùng ngày 2040 và phòng riêng để tránh dữ liệu tích lũy từ các
  class dùng chung database Testcontainers.

## Kết quả thực chạy

Ngày 07/11/2026 (Asia/Ho_Chi_Minh), chạy `.\mvnw.cmd -B -ntp verify` ba lần liên tiếp
trên cùng máy, Testcontainers PostgreSQL 18.1, JDK 21:

| Lần | Kết quả | Thời gian |
|---|---|---|
| 1 | BUILD SUCCESS — 216 tests, 0 failures, 0 errors, 0 skipped | 03:00 |
| 2 | BUILD SUCCESS — 216 tests, 0 failures, 0 errors, 0 skipped | 02:47 |
| 3 | BUILD SUCCESS — 216 tests, 0 failures, 0 errors, 0 skipped | 02:42 |

Không có test nào bị flaky trong ba lần chạy liên tiếp. Log từng lần nằm trong
`backend/target/verify-e/run-1.log` … `run-3.log` (build output, không commit). Báo cáo
máy sinh: `backend/target/surefire-reports`.
