package com.cinema.cms;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.cinema.cms.dto.*;
import com.cinema.cms.entity.*;
import com.cinema.cms.service.*;
import com.cinema.cms.support.SeatHoldTestSupport;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

class RefundReportsIntegrationTest extends SeatHoldTestSupport {
    @Autowired RefundService refunds;
    @Autowired ReportingService reports;
    @Autowired BookingService bookings;
    @Autowired CheckoutService checkoutService;
    @Autowired TicketService ticketService;
    @Autowired PasswordEncoder encoder;
    private static final AtomicInteger day=new AtomicInteger();
    private User manager;
    private String managerJwt,date;
    private Instant paidAt;

    @BeforeEach void refundFixture() {
        paidAt=LocalDate.of(2050,1,1).plusDays(day.incrementAndGet()*10L).atTime(10,0).toInstant(ZoneOffset.UTC);
        clock.set(paidAt);date=paidAt.atZone(ShowtimeTimeService.BUSINESS_ZONE).toLocalDate().toString();
        staffJwt=jwt.issue(users.findById(staff.userId()).orElseThrow());
        customerJwt=jwt.issue(users.findById(customer.userId()).orElseThrow());
        staff=principal(users.findById(staff.userId()).orElseThrow(),staffJwt);
        customer=principal(users.findById(customer.userId()).orElseThrow(),customerJwt);
        otherSession=principal(users.findById(staff.userId()).orElseThrow(),jwt.issue(users.findById(staff.userId()).orElseThrow()));
        jdbc.update("update showtimes set start_time=?,end_time=? where showtime_id=?",Timestamp.from(paidAt.plusSeconds(3600)),Timestamp.from(paidAt.plusSeconds(11700)),show);
        String suffix=UUID.randomUUID().toString().substring(0,8);
        manager=new User("mgr_"+suffix,"Manager",suffix+"m@test.invalid","+87"+Math.abs(UUID.randomUUID().getMostSignificantBits()%10000000000L),"unused",UserRole.MANAGER);
        manager.setManagerPinHash(encoder.encode("123456"));manager=users.saveAndFlush(manager);managerJwt=jwt.issue(manager);
    }
    private BookingResponse paid(List<Long> seats,boolean online,List<CreateBookingRequest.ConcessionItem> food) {
        SessionPrincipal actor=online?customer:staff;
        var hold=holds.hold(show,new HoldSeatsRequest(seats),actor);
        var b=bookings.create(new CreateBookingRequest(show,hold.holdToken(),food,null),actor);
        checkoutService.finalizePaid(b.bookingId(),online?PaymentMethod.GATEWAY_ONLINE:PaymentMethod.CASH,b.totalAmount());
        return b;
    }
    private List<Long> ids(long booking) { return jdbc.queryForList("select ticket_id from tickets where booking_id=? order by ticket_id",Long.class,booking); }
    private String code(long ticket) { return jdbc.queryForObject("select ticket_code from tickets where ticket_id=?",String.class,ticket); }
    private ManagerOverrideRequest approval(long booking,List<Long> ids) { return new ManagerOverrideRequest(manager.getUsername(),"123456","REFUND_APPROVAL",booking,ids,"Customer request",true); }
    private RefundRequest request(long booking,List<Long> ids) {
        return new RefundRequest(booking,ids,"Customer request",refunds.approve(approval(booking,ids),staff).overrideToken(),true);
    }
    private String ticketStatus(long ticket) { return jdbc.queryForObject("select status::text from tickets where ticket_id=?",String.class,ticket); }
    private int records(long booking) { return jdbc.queryForObject("select count(*) from refund_records where booking_id=?",Integer.class,booking); }

    @Test void partialRefundUsesSnapshotPreservesFoodAndReplaysWithoutReleasingResoldSeat() {
        int item=jdbc.queryForObject("insert into concession_items(name,price) values (?,30000) returning item_id",Integer.class,"Food "+UUID.randomUUID());
        jdbc.update("insert into concession_inventory(item_id,stock_quantity) values (?,10)",item);
        var b=paid(List.of(a1,a2),true,List.of(new CreateBookingRequest.ConcessionItem(item,1)));
        var selected=List.of(ids(b.bookingId()).getFirst());
        var req=request(b.bookingId(),selected);
        jdbc.update("update concession_items set price=90000 where item_id=?",item);
        var result=refunds.refund(req,staff);
        assertThat(result.refundAmount()).isEqualByComparingTo(b.tickets().getFirst().price());
        assertThat(result.refundStatus()).isEqualTo("PARTIAL");
        assertThat(ticketStatus(selected.getFirst())).isEqualTo("CANCELLED");assertThat(seatStatus(a1)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("select stock_quantity from concession_inventory where item_id=?",Integer.class,item)).isEqualTo(9);
        var resold=paid(List.of(a1),false,null);
        assertThat(refunds.refund(req,staff).refundId()).isEqualTo(result.refundId());
        assertThat(seatStatus(a1)).isEqualTo("BOOKED");assertThat(records(b.bookingId())).isEqualTo(1);
        assertThat(ticketStatus(ids(resold.bookingId()).getFirst())).isEqualTo("VALID");
        assertThat(jdbc.queryForObject("select amount from payments where booking_id=?",BigDecimal.class,b.bookingId())).isEqualByComparingTo(b.totalAmount());
    }
    @Test void eligibilityFindsOnlineBookingAndRejectsAmbiguousLookup() {
        var b=paid(List.of(a1),true,null);var line=refunds.eligibility(null,code(ids(b.bookingId()).getFirst()),staff);
        assertThat(line.channel()).isEqualTo("ONLINE");assertThat(line.tickets().getFirst().eligible()).isTrue();
        assertThat(refunds.eligibility(b.bookingCode(),null,principal(manager,managerJwt)).bookingId()).isEqualTo(b.bookingId());
        assertThatThrownBy(() -> refunds.eligibility(b.bookingCode(),"x",staff)).isInstanceOf(ApiException.class);
    }
    @Test void exactThirtyMinutesIsEligibleButUsedAndLateTicketsAreNot() {
        var b=paid(List.of(a1,a2),false,null);var ids=ids(b.bookingId());
        clock.advance(1800);
        assertThat(refunds.eligibility(b.bookingCode(),null,staff).tickets()).allMatch(RefundEligibilityResponse.TicketLine::eligible);
        var req=request(b.bookingId(),List.of(ids.getFirst()));refunds.refund(req,staff);
        var late=request(b.bookingId(),List.of(ids.get(1)));
        clock.advance(1);
        assertThat(refunds.eligibility(b.bookingCode(),null,staff).tickets().get(1).reasonCode()).isEqualTo("REFUND_DEADLINE_PASSED");
        assertThatThrownBy(() -> refunds.refund(late,staff)).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getCode()).isEqualTo("REFUND_DEADLINE_PASSED"));
    }
    @Test void usedTicketCannotGetApproval() {
        var b=paid(List.of(a1),false,null);long id=ids(b.bookingId()).getFirst();
        ticketService.validate(new TicketValidationRequest(code(id),null),staff);
        assertThatThrownBy(() -> refunds.approve(approval(b.bookingId(),List.of(id)),staff)).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getCode()).isEqualTo("TICKET_NOT_REFUNDABLE"));
    }
    @Test void approvalBindsSessionSelectionReasonAndCashConfirmation() {
        var b=paid(List.of(a1,a2),false,null);var ids=ids(b.bookingId());var req=request(b.bookingId(),List.of(ids.getFirst()));
        assertThatThrownBy(() -> refunds.refund(req,otherSession)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> refunds.refund(new RefundRequest(b.bookingId(),List.of(ids.get(1)),req.reason(),req.overrideToken(),true),staff)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> refunds.refund(new RefundRequest(b.bookingId(),req.ticketIds(),"Changed",req.overrideToken(),true),staff)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> refunds.refund(new RefundRequest(b.bookingId(),req.ticketIds(),req.reason(),req.overrideToken(),false),staff)).isInstanceOf(ApiException.class);
        assertThat(records(b.bookingId())).isZero();assertThat(ticketStatus(ids.getFirst())).isEqualTo("VALID");
    }
    @Test void expiryAndRevokedManagerApprovalAreRejected() {
        var b=paid(List.of(a1),false,null);var req=request(b.bookingId(),ids(b.bookingId()));clock.advance(180);
        assertThatThrownBy(() -> refunds.refund(req,staff)).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getCode()).isEqualTo("MANAGER_OVERRIDE_EXPIRED"));
        var fresh=request(b.bookingId(),ids(b.bookingId()));manager.changeStatus(AccountStatus.LOCKED);users.saveAndFlush(manager);
        assertThatThrownBy(() -> refunds.refund(fresh,staff)).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getCode()).isEqualTo("MANAGER_PIN_INVALID"));
        assertThat(records(b.bookingId())).isZero();
    }
    @Test void wrongPinCustomerAndMixedBookingCannotApprove() {
        var b=paid(List.of(a1),false,null);var ids=ids(b.bookingId());
        var bad=new ManagerOverrideRequest(manager.getUsername(),"000000","REFUND_APPROVAL",b.bookingId(),ids,"Customer request",true);
        assertThatThrownBy(() -> refunds.approve(bad,staff)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> refunds.approve(new ManagerOverrideRequest(staff.username(),"123456","REFUND_APPROVAL",b.bookingId(),ids,"Customer request",true),staff)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> refunds.approve(new ManagerOverrideRequest(manager.getUsername(),"invalid","REFUND_APPROVAL",b.bookingId(),ids,"Customer request",true),staff)).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getStatus()).isEqualTo(400));
        assertThatThrownBy(() -> refunds.approve(approval(b.bookingId(),ids),customer)).isInstanceOf(ApiException.class);
        var other=paid(List.of(a2),false,null);
        assertThatThrownBy(() -> refunds.approve(approval(b.bookingId(),ids(other.bookingId())),staff)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> refunds.approve(approval(b.bookingId(),List.of(ids.getFirst(),ids.getFirst())),staff)).isInstanceOf(ApiException.class);
    }
    @Test void sweetboxCanRefundOneTicketWithoutCancellingThePartner() {
        var b=paid(List.of(sweet1),false,null);var ids=ids(b.bookingId());
        assertThat(ids).hasSize(2);refunds.refund(request(b.bookingId(),List.of(ids.getFirst())),staff);
        assertThat(ticketStatus(ids.getFirst())).isEqualTo("CANCELLED");assertThat(ticketStatus(ids.get(1))).isEqualTo("VALID");
        assertThatThrownBy(() -> holds.hold(show,new HoldSeatsRequest(List.of(sweet1)),customer)).isInstanceOf(ApiException.class);
    }
    @Test void sameApprovalInParallelCreatesOneRefundAndTwoReadbacks() throws Exception {
        var b=paid(List.of(a1),false,null);var req=request(b.bookingId(),ids(b.bookingId()));
        var results=parallel(() -> refunds.refund(req,staff),() -> refunds.refund(req,staff));
        assertThat(results).allMatch(RefundResponse.class::isInstance);
        assertThat(((RefundResponse)results.get(0)).refundId()).isEqualTo(((RefundResponse)results.get(1)).refundId());
        assertThat(records(b.bookingId())).isEqualTo(1);
    }
    @Test void competingApprovalsAndGateCannotCancelOrAdmitTheTicketTwice() throws Exception {
        var b=paid(List.of(a1),false,null);var ids=ids(b.bookingId());var first=request(b.bookingId(),ids);var second=request(b.bookingId(),ids);
        var results=parallel(() -> refunds.refund(first,staff),() -> refunds.refund(second,staff));
        assertThat(results.stream().filter(RefundResponse.class::isInstance)).hasSize(1);assertThat(records(b.bookingId())).isEqualTo(1);
        var other=paid(List.of(a2),false,null);long t=ids(other.bookingId()).getFirst();var req=request(other.bookingId(),List.of(t));
        var scan=parallel(() -> refunds.refund(req,staff),() -> ticketService.validate(new TicketValidationRequest(code(t),null),staff));
        assertThat(scan.stream().filter(r -> !(r instanceof ApiException))).hasSize(1);
        assertThat(ticketStatus(t)).isIn("USED","CANCELLED");
    }
    @Test void failureAfterSeatReleaseRollsBackApprovalTicketsSeatsAndRefundRecord() {
        var b=paid(List.of(a1),false,null);var req=request(b.bookingId(),ids(b.bookingId()));
        jdbc.execute("create function reject_f_refund() returns trigger language plpgsql as $$ begin if NEW.booking_id="+b.bookingId()+" then raise exception 'injected test failure'; end if; return NEW; end $$");
        jdbc.execute("create trigger reject_f_refund before insert on refund_records for each row execute function reject_f_refund()");
        try { assertThatThrownBy(() -> refunds.refund(req,staff)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("drop trigger reject_f_refund on refund_records");jdbc.execute("drop function reject_f_refund()"); }
        assertThat(ticketStatus(req.ticketIds().getFirst())).isEqualTo("VALID");assertThat(seatStatus(a1)).isEqualTo("BOOKED");assertThat(records(b.bookingId())).isZero();
        assertThat(refunds.refund(req,staff).refundStatus()).isEqualTo("FULL");
    }
    @Test void reportsReconcileSnapshotsRefundsChannelsCountsAndOccupancyWithoutJoinMultiplication() {
        int item=jdbc.queryForObject("insert into concession_items(name,price) values (?,30000) returning item_id",Integer.class,"Food "+UUID.randomUUID());
        jdbc.update("insert into concession_inventory(item_id,stock_quantity) values (?,10)",item);
        var b=paid(List.of(a1,a2),true,List.of(new CreateBookingRequest.ConcessionItem(item,1)));
        var first=List.of(ids(b.bookingId()).getFirst());refunds.refund(request(b.bookingId(),first),staff);
        var revenue=reports.revenue(date,date);var breakdown=reports.breakdown(date,date);var channels=reports.channels(date,date);
        assertThat(revenue.totalRevenue()).isEqualByComparingTo(b.totalAmount());
        assertThat(revenue.netRevenue()).isEqualByComparingTo(b.totalAmount().subtract(b.tickets().getFirst().price()));
        assertThat(breakdown.netRevenue()).isEqualByComparingTo(revenue.netRevenue());
        assertThat(channels.onlineRevenue()).isEqualByComparingTo(revenue.netRevenue());assertThat(channels.posRevenue()).isZero();
        assertThat(reports.ticketCount(date,date).ticketCount()).isEqualTo(1);
        var line=reports.occupancy(date,date).showtimes().stream().filter(s -> s.showtimeId().equals(show)).findFirst().orElseThrow();
        assertThat(line.seatsSold()).isEqualTo(1);assertThat(line.totalSeats()).isEqualTo(5);assertThat(line.occupancyRate()).isEqualByComparingTo("20");
        assertThat(reports.ranking(date,date,"ASC",5).movies().getFirst().revenue()).isEqualByComparingTo(revenue.netRevenue());
    }
    @Test void refundInALaterDayAdjustsTheOriginalPaymentCohortOnce() {
        jdbc.update("update showtimes set start_time=?,end_time=? where showtime_id=?",Timestamp.from(paidAt.plusSeconds(172800)),Timestamp.from(paidAt.plusSeconds(180900)),show);
        var b=paid(List.of(a1),true,null);clock.advance(86400);refunds.refund(request(b.bookingId(),ids(b.bookingId())),staff);
        assertThat(reports.revenue(date,date).netRevenue()).isZero();
        assertThat(reports.revenue(LocalDate.parse(date).plusDays(1).toString(),LocalDate.parse(date).plusDays(1).toString()).totalRevenue()).isZero();
    }
    @Test void multipleFoodLinesAndRefundsAreAggregatedOnce() {
        List<CreateBookingRequest.ConcessionItem> food=new ArrayList<>();
        for (int price:List.of(10000,20000)) {
            int item=jdbc.queryForObject("insert into concession_items(name,price) values (?,?) returning item_id",Integer.class,"Food "+UUID.randomUUID(),price);
            jdbc.update("insert into concession_inventory(item_id,stock_quantity) values (?,10)",item);
            food.add(new CreateBookingRequest.ConcessionItem(item,2));
        }
        var b=paid(List.of(a1,a2),false,food);var tickets=ids(b.bookingId());
        for (long id:tickets) refunds.refund(request(b.bookingId(),List.of(id)),staff);
        var revenue=reports.revenue(date,date);
        assertThat(revenue.totalRevenue()).isEqualByComparingTo(b.totalAmount());
        assertThat(revenue.netRevenue()).isEqualByComparingTo("60000");
        assertThat(reports.breakdown(date,date).concessionRevenue()).isEqualByComparingTo("60000");
        assertThat(reports.ticketCount(date,date).cancelledTicketCount()).isEqualTo(2);
        assertThat(reports.channels(date,date).posRevenue()).isEqualByComparingTo("60000");
    }
    @Test void businessDateRangeUsesVietnamMidnightAndExcludesNextMidnight() {
        var b=paid(List.of(a1),true,null);
        Instant start=LocalDate.parse(date).atStartOfDay(ShowtimeTimeService.BUSINESS_ZONE).toInstant();
        jdbc.update("update payments set paid_at=? where booking_id=?",Timestamp.from(start),b.bookingId());
        assertThat(reports.revenue(date,date).totalRevenue()).isEqualByComparingTo(b.totalAmount());
        jdbc.update("update payments set paid_at=? where booking_id=?",Timestamp.from(start.minusNanos(1000)),b.bookingId());
        assertThat(reports.revenue(date,date).totalRevenue()).isZero();
        clock.set(start.plusSeconds(86400));
        jdbc.update("update payments set paid_at=? where booking_id=?",Timestamp.from(clock.instant()),b.bookingId());
        assertThat(reports.revenue(date,date).totalRevenue()).isZero();
    }
    @Test void currentInventoryAlertsDistinguishLowAndOutOfStock() {
        int item=jdbc.queryForObject("insert into concession_items(name,price) values (?,10000) returning item_id",Integer.class,"Alert "+UUID.randomUUID());
        jdbc.update("insert into concession_inventory(item_id,stock_quantity,low_stock_threshold) values (?,2,2)",item);
        assertThat(reports.alerts().stream().filter(i -> i.itemId()==item).findFirst().orElseThrow().status()).isEqualTo("LOW_STOCK");
        jdbc.update("update concession_inventory set stock_quantity=0 where item_id=?",item);
        assertThat(reports.alerts().stream().filter(i -> i.itemId()==item).findFirst().orElseThrow().status()).isEqualTo("OUT_OF_STOCK");
        jdbc.update("update concession_inventory set stock_quantity=3 where item_id=?",item);
        assertThat(reports.alerts()).noneMatch(i -> i.itemId()==item);
    }
    @Test void rankingSupportsBothDirectionsAndOccupancyWeightsInventoryCounts() {
        var first=paid(List.of(a1),false,null);
        long originalMovie=movie,originalShow=show;
        long nextMovie=jdbc.queryForObject("insert into movies(title,duration_minutes,age_rating,release_date) values (?,120,'P','2050-01-01') returning movie_id",Long.class,"Ranking "+UUID.randomUUID());
        jdbc.update("update showtimes set movie_id=?,start_time=?,end_time=? where showtime_id=?",nextMovie,Timestamp.from(paidAt.plusSeconds(14400)),Timestamp.from(paidAt.plusSeconds(22500)),secondShow);
        show=secondShow;paid(List.of(a1,a2,vip),true,null);show=originalShow;
        assertThat(reports.ranking(date,date,"ASC",1).movies().getFirst().movieId()).isEqualTo(originalMovie);
        assertThat(reports.ranking(date,date,"DESC",1).movies().getFirst().movieId()).isEqualTo(nextMovie);
        // One show has five physical seats, the other four; metadata alone is not the denominator.
        jdbc.update("delete from showtime_seats where showtime_id=? and seat_id=?",secondShow,sweet2);
        var occupancy=reports.occupancy(date,date);
        assertThat(occupancy.seatsSold()).isEqualTo(4);assertThat(occupancy.totalSeats()).isEqualTo(9);
        assertThat(occupancy.occupancyRate()).isEqualByComparingTo("44.44");
        assertThatThrownBy(() -> reports.ranking(date,date,"SIDEWAYS",5)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> reports.ranking(date,date,"ASC",0)).isInstanceOf(ApiException.class);
    }
    @Test void managerSecurityVersionChangeInvalidatesUnconsumedApproval() {
        var b=paid(List.of(a1),false,null);var req=request(b.bookingId(),ids(b.bookingId()));
        manager.invalidateSessions();users.saveAndFlush(manager);
        assertThatThrownBy(() -> refunds.refund(req,staff)).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getCode()).isEqualTo("MANAGER_PIN_INVALID"));
        assertThat(records(b.bookingId())).isZero();
    }
    @Test void exceptionPaidIsSeparateAndEmptyPeriodsAndInvalidDatesAreHandled() {
        var h=holds.hold(show,new HoldSeatsRequest(List.of(a1)),customer);
        var b=bookings.create(new CreateBookingRequest(show,h.holdToken(),null,null),customer);
        jdbc.update("update bookings set status='EXCEPTION_PENDING_DECISION' where booking_id=?",b.bookingId());
        jdbc.update("insert into payments(booking_id,payment_method,amount,status,paid_at) values (?,'GATEWAY_ONLINE',?,'PAID',?)",b.bookingId(),b.totalAmount(),Timestamp.from(clock.instant()));
        assertThat(reports.revenue(date,date).totalRevenue()).isZero();assertThat(reports.revenue(date,date).exceptionPaidAmount()).isEqualByComparingTo(b.totalAmount());
        assertThat(reports.occupancy("2200-01-01","2200-01-01").occupancyRate()).isZero();
        assertThat(reports.ranking("2200-01-01","2200-01-01","DESC",5).movies()).isEmpty();
        assertThatThrownBy(() -> reports.revenue(date,"bad")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> reports.revenue("2050-02-01","2050-01-01")).isInstanceOf(ApiException.class);
    }
    @Test void allReportRoutesEnforceManagerAndRefundRoutesRejectCustomer() throws Exception {
        for (String route:List.of("revenue","revenue-breakdown","revenue-by-channel","ticket-count","occupancy","inventory-alerts","movies/ranking")) {
            mvc.perform(get("/api/v1/reports/"+route).param("from",date).param("to",date).header("Authorization","Bearer "+staffJwt)).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/reports/"+route).param("from",date).param("to",date).header("Authorization","Bearer "+managerJwt)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/refunds/eligibility").param("bookingCode","x").header("Authorization","Bearer "+customerJwt)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/reports/revenue").param("from",date).header("Authorization","Bearer "+managerJwt)).andExpect(status().isBadRequest());
    }
    @Test void httpApprovalAndRefundValidateAndExposeTheExpectedContract() throws Exception {
        var b=paid(List.of(a1),false,null);var ids=ids(b.bookingId());
        String body=mvc.perform(post("/api/v1/manager-overrides").header("Authorization","Bearer "+staffJwt).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(approval(b.bookingId(),ids))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token=mapper.readTree(body).path("data").path("overrideToken").asText();
        mvc.perform(post("/api/v1/refunds").header("Authorization","Bearer "+staffJwt).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(new RefundRequest(b.bookingId(),ids,"Customer request",token,true))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.refundStatus").value("FULL"));
        mvc.perform(get("/api/v1/pos/bookings").param("bookingCode",b.bookingCode()).header("Authorization","Bearer "+staffJwt))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].refundStatus").value("FULL"));
        mvc.perform(post("/api/v1/manager-overrides").header("Authorization","Bearer "+staffJwt).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        for (String deniedJwt:List.of(customerJwt,managerJwt)) {
            mvc.perform(post("/api/v1/manager-overrides").header("Authorization","Bearer "+deniedJwt).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(approval(b.bookingId(),ids))))
                .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/refunds").header("Authorization","Bearer "+deniedJwt).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new RefundRequest(b.bookingId(),ids,"Customer request",token,true))))
                .andExpect(status().isForbidden());
        }
    }
    private List<Object> parallel(Callable<Object> a,Callable<Object> b) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        try {
            List<Future<Object>> fs=new ArrayList<>();
            for (Callable<Object> call:List.of(a,b)) fs.add(pool.submit(() -> { ready.countDown();if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("timeout");try{return call.call();}catch(ApiException e){return e;} }));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();return List.of(fs.get(0).get(30,TimeUnit.SECONDS),fs.get(1).get(30,TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
}
