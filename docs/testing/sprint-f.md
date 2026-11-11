# Kiểm thử backend Sprint F

Phạm vi: Refund, Manager Override, Reports, POS refundStatus và migration V8.
Test tự động nằm trong `RefundReportsIntegrationTest`, dùng PostgreSQL thật,
MockMvc/JWT và MutableClock; không phải nghiệm thu giao diện hoặc ngân hàng thật.

## Chạy

Mặc định dùng Testcontainers khi Docker sẵn sàng:

```powershell
cd backend
.\mvnw.cmd -B -ntp '-Dtest=RefundReportsIntegrationTest' test
.\mvnw.cmd -B -ntp verify
```

Hoặc dùng database test riêng với DB_TEST_URL, DB_TEST_USERNAME, DB_TEST_PASSWORD
như README. Không dùng database dự án. Test có ghi dữ liệu, tạo trigger lỗi tạm
để kiểm tra rollback và lưu nhiều fixture có mã/ngày riêng. Tạo database test
mới khi chạy lại suite để không tích lũy dữ liệu report theo ngày từ lần trước.
Collation phải hỗ trợ lower/upper tiếng Việt như môi trường PostgreSQL thông thường;
locale C thuần không đáp ứng test tên F&B không phân biệt hoa/thường của Sprint B.

## Ma trận truy vết

| Requirement | Test method | Kiểm chứng |
|---|---|---|
| FR-55 / UC-17 | eligibilityFindsOnlineBookingAndRejectsAmbiguousLookup | Tìm vé ONLINE từ POS; STAFF/MANAGER lookup; đúng một khóa tìm kiếm |
| FR-56 / BR-31 | exactThirtyMinutesIsEligibleButUsedAndLateTicketsAreNot | Đúng 30 phút được hoàn; dưới mốc bị từ chối |
| FR-56 / E gate | usedTicketCannotGetApproval | Vé USED không được duyệt refund |
| FR-58 / NFR-05/06 | approvalBindsSessionSelectionReasonAndCashConfirmation | Không đổi phiên/tập vé/lý do/cash flag sau duyệt |
| FR-58 | expiryAndRevokedManagerApprovalAreRejected | Token hết 180 giây và Manager bị LOCKED bị chặn |
| FR-58 | managerSecurityVersionChangeInvalidatesUnconsumedApproval | Manager đổi securityVersion làm approval cũ mất hiệu lực |
| FR-58/59 | wrongPinCustomerAndMixedBookingCannotApprove | PIN sai, CUSTOMER, vé Booking khác, IDs trùng bị chặn |
| FR-59 / BR-33 | sweetboxCanRefundOneTicketWithoutCancellingThePartner | Hoàn từng Ticket; không phá quy tắc bán cả Sweetbox |
| FR-60/61 | partialRefundUsesSnapshotPreservesFoodAndReplaysWithoutReleasingResoldSeat | Tiền snapshot, giữ F&B/kho/Payment gốc; retry không nhả ghế đã bán lại |
| NFR-02/07 | sameApprovalInParallelCreatesOneRefundAndTwoReadbacks | Hai request cùng approval chỉ một record, cùng refundId |
| NFR-02 | competingApprovalsAndGateCannotCancelOrAdmitTheTicketTwice | Hai approval tranh cùng vé; refund tranh gate chỉ một thao tác thành công |
| NFR-03 / BR-35 | failureAfterSeatReleaseRollsBackApprovalTicketsSeatsAndRefundRecord | Trigger lỗi tại ghi refund; rollback cả vé/ghế/token/record, retry thành công |
| FR-62–64 | reportsReconcileSnapshotsRefundsChannelsCountsAndOccupancyWithoutJoinMultiplication | Gross/refund/net, Ticket/F&B, ONLINE, count/occupancy/ranking khớp nhau |
| FR-62 / NFR-21 | refundInALaterDayAdjustsTheOriginalPaymentCohortOnce | Refund khác ngày điều chỉnh cohort gốc, không đổi thành cash-flow |
| FR-62/63 | multipleFoodLinesAndRefundsAreAggregatedOnce | Nhiều dòng food và nhiều refund không nhân SUM, full ticket refund giữ F&B revenue |
| NFR-21 | businessDateRangeUsesVietnamMidnightAndExcludesNextMidnight | Inclusive from/to theo UTC+7, biên cuối exclusive |
| FR-65 | currentInventoryAlertsDistinguishLowAndOutOfStock | LOW_STOCK / OUT_OF_STOCK và gỡ alert khi nhập đủ |
| FR-64/66 | rankingSupportsBothDirectionsAndOccupancyWeightsInventoryCounts | ASC/DESC, limit, weighted occupancy theo inventory |
| E exception / FR-62 | exceptionPaidIsSeparateAndEmptyPeriodsAndInvalidDatesAreHandled | Tiền ngoại lệ tách riêng; empty/zero denominator; ngày sai trả lỗi |
| NFR-06 | allReportRoutesEnforceManagerAndRefundRoutesRejectCustomer | Cả 7 route report; role STAFF bị chặn; missing date trả 400 |
| FR-58–61 | httpApprovalAndRefundValidateAndExposeTheExpectedContract | HTTP approve/refund, FULL trong POS history, Bean Validation và quyền POST |

Ngoài test F, cập nhật test Foundation từ 7 lên 8 migration và test POS history
từ refundStatus=null sang NONE để khớp contract F. Query thứ tự lịch sử POS trong
test được lọc đúng ngày fixture 2040-06-06, tránh phụ thuộc các giao dịch của test F.
Các test A–E còn lại giữ nguyên.

## Kiểm tra migration riêng

Đã kiểm tra trên cluster PostgreSQL tạm tách biệt:

- Schema từ V1–V7, có một refund legacy hợp lệ 70.000: áp dụng V8 thành công,
  backfill đúng một refund_ticket_links với snapshot 70.000.
- Bản sao V7 có refund_amount lệch ticket snapshot: V8 từ chối với
  `Legacy refund ticket lists or amounts are inconsistent`; transaction rollback.
- Flyway từ database rỗng được suite backend kiểm chứng khi startup và test
  migration repeatable kiểm tra lần migrate thứ hai không làm lại.

Archive/database người dùng không được restore hoặc thay đổi. Cluster kiểm tra
local chỉ bind loopback, dùng database tạo riêng cho lượt test.

## Kết quả thực chạy

Ngày 09/11/2026, JDK 21.0.10, PostgreSQL 18.6 local riêng:

- Lần tập trung ban đầu: 16 tests, 0 failures/errors/skipped.
- Suite mở rộng cuối cùng: `mvnw.cmd -B -ntp verify` **BUILD SUCCESS**, 237 tests,
  0 failures, 0 errors, 0 skipped; gồm 21 test F. Tổng thời gian 04:23, hoàn tất
  13:32 ngày 09/11/2026 (Asia/Ho_Chi_Minh). Database mới riêng, collation ICU en-US.
- Các lần verify trung gian phát hiện kỳ vọng migration cũ, collation C và fixture
  ngày/thứ tự giao dịch bị chồng nhau; đã điều chỉnh test/môi trường trước lượt pass.

Log lượt pass toàn bộ ở `backend/target/sprint-f-release.log`; log tập trung ban đầu
ở `backend/target/sprint-f-focused.log`,
báo cáo JUnit ở `backend/target/surefire-reports` (build output, không commit).

Giới hạn: không gọi VNPay/ngân hàng thật; không kiểm thử UI hay bàn giao tiền mặt
ngoài hệ thống. Doanh thu dùng cohort thanh toán và trạng thái vé hiện tại; không
phải sổ dòng tiền bất biến theo ngày chi refund. Xem [decision F](../decisions/006-refund-reports.md).
