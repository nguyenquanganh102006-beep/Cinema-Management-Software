package com.cinema.cms.service;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.*;
import com.cinema.cms.repository.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RefundService {
    private static final Logger audit=LoggerFactory.getLogger("AUDIT_REFUND");
    private final RefundRepository refunds;
    private final BookingRepository bookings;
    private final TicketRepository tickets;
    private final PaymentRepository payments;
    private final ShowtimeRepository showtimes;
    private final ShowtimeSeatRepository inventory;
    private final SeatRepository seats;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    public RefundService(RefundRepository refunds, BookingRepository bookings, TicketRepository tickets,
        PaymentRepository payments, ShowtimeRepository showtimes, ShowtimeSeatRepository inventory,
        SeatRepository seats, UserRepository users, PasswordEncoder encoder, Clock clock) {
        this.refunds=refunds;this.bookings=bookings;this.tickets=tickets;this.payments=payments;
        this.showtimes=showtimes;this.inventory=inventory;this.seats=seats;this.users=users;this.encoder=encoder;this.clock=clock;
    }

    @Transactional(readOnly=true)
    public RefundEligibilityResponse eligibility(String bookingCode, String ticketCode, SessionPrincipal actor) {
        requireActor(actor,true);
        boolean byBooking=bookingCode!=null&&!bookingCode.isBlank(), byTicket=ticketCode!=null&&!ticketCode.isBlank();
        if (byBooking==byTicket) throw invalid("Provide exactly one bookingCode or ticketCode");
        Booking booking=byBooking?bookings.findByBookingCode(bookingCode.strip()).orElseThrow(RefundService::notFound):
            bookings.findById(tickets.findByTicketCode(ticketCode.strip()).orElseThrow(RefundService::notFound).getBookingId()).orElseThrow(RefundService::notFound);
        Showtime show=showtimes.findById(booking.getShowtimeId()).orElseThrow(RefundService::notFound);
        Instant now=clock.instant();
        boolean paid=paid(booking);
        List<RefundEligibilityResponse.TicketLine> lines=tickets.findByBookingIdOrderByIdAsc(booking.getId()).stream().map(t -> {
            ShowtimeSeat ss=inventory.findById(t.getShowtimeSeatId()).orElseThrow(RefundService::notFound);
            Seat seat=seats.findById(ss.getSeatId()).orElseThrow(RefundService::notFound);
            String error=reason(t,show,paid,now);
            return new RefundEligibilityResponse.TicketLine(t.getId(),t.getTicketCode(),seat.getId(),seat.getSeatRow(),seat.getSeatNumber(),
                t.getStatus().name(),t.getPriceSnapshot(),error==null,error,error==null?t.getPriceSnapshot():BigDecimal.ZERO);
        }).toList();
        return new RefundEligibilityResponse(booking.getId(),booking.getBookingCode(),booking.getChannel().name(),show.getId(),show.getStartTime(),now,lines);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public ManagerOverrideResponse approve(ManagerOverrideRequest request, SessionPrincipal actor) {
        requireActor(actor,false);
        if (request==null||!"REFUND_APPROVAL".equals(request.scope())||request.username()==null||request.pin()==null||!request.pin().matches("[0-9]{6,12}"))
            throw invalid("Invalid approval request");
        List<Long> ids=selection(request.bookingId(),request.ticketIds(),request.reason(),request.cashConfirmed());
        inventory.configureLockTimeout();
        String username=request.username().strip().toLowerCase(Locale.ROOT);
        User manager=users.lockManagerByUsername(username).orElseThrow(RefundService::badPin);
        if (manager.getRole()!=UserRole.MANAGER||manager.getStatus()!=AccountStatus.ACTIVE||!manager.matchesManagerPin(request.pin(),encoder)) {
            audit.info("action=REFUND_APPROVAL result=DENIED staffId={}",actor.userId());
            throw badPin();
        }
        Booking booking=bookings.findByIdForUpdate(request.bookingId()).orElseThrow(RefundService::notFound);
        List<Ticket> selected=tickets.lockByIds(ids);
        BigDecimal amount=check(booking,selected,ids,clock.instant());
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        String token=HexFormat.of().formatHex(bytes);
        Instant now=clock.instant(), expires=now.plusSeconds(180);
        refunds.approve(new ManagerOverride(hash(token),actor.userId(),actor.tokenId(),manager.getId(),manager.getSecurityVersion(),
            booking.getId(),ids,request.reason().strip(),amount,now,expires,null));
        audit.info("action=REFUND_APPROVAL result=APPROVED bookingId={} managerId={} staffId={}",booking.getId(),manager.getId(),actor.userId());
        return new ManagerOverrideResponse(token,expires,amount);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public RefundResponse refund(RefundRequest request, SessionPrincipal actor) {
        requireActor(actor,false);
        if (request==null||request.overrideToken()==null||!request.overrideToken().matches("[a-f0-9]{64}"))
            throw new ApiException(403,"MANAGER_OVERRIDE_REQUIRED","A valid manager approval is required");
        List<Long> ids=selection(request.bookingId(),request.ticketIds(),request.reason(),request.cashConfirmed());
        inventory.configureLockTimeout();
        ManagerOverride a=refunds.lockApproval(hash(request.overrideToken())).orElseThrow(() ->
            new ApiException(403,"MANAGER_OVERRIDE_REQUIRED","Manager approval was not found"));
        if (!a.staffId().equals(actor.userId())||!a.staffTokenId().equals(actor.tokenId())||!a.bookingId().equals(request.bookingId())
            ||!a.ticketIds().equals(ids)||!a.reason().equals(request.reason().strip()))
            throw new ApiException(403,"MANAGER_OVERRIDE_MISMATCH","Approval does not match this session and refund");
        if (a.refundId()!=null) return refunds.response(a.refundId()); // durable idempotent readback, even after expiry
        Booking booking=bookings.findByIdForUpdate(a.bookingId()).orElseThrow(RefundService::notFound);
        List<Ticket> selected=tickets.lockByIds(ids);
        check(booking,selected,ids,clock.instant());
        // Read only scalar seat IDs first; avoid stale inventory entities before taking the locks.
        List<Long> physicalIds=inventory.physicalSeatIds(selected.stream().map(Ticket::getShowtimeSeatId).toList());
        List<ShowtimeSeat> locked=physicalIds.isEmpty()?List.of():inventory.lockSeats(booking.getShowtimeId(),physicalIds);
        Set<Long> expected=new HashSet<>(selected.stream().map(Ticket::getShowtimeSeatId).toList());
        if (locked.size()!=expected.size()||locked.stream().anyMatch(s -> !expected.contains(s.getId())||s.getStatus()!=SeatStatus.BOOKED))
            throw new ApiException(409,"REFUND_NOT_ELIGIBLE","Seat inventory no longer matches the tickets");
        Instant now=clock.instant();
        if (!now.isBefore(a.expiresAt())) throw new ApiException(403,"MANAGER_OVERRIDE_EXPIRED","Manager approval has expired");
        User manager=users.lockApprovalManager(a.managerId()).orElseThrow(RefundService::badPin);
        if (manager.getRole()!=UserRole.MANAGER||manager.getStatus()!=AccountStatus.ACTIVE||manager.getSecurityVersion()!=a.managerSecurityVersion()) throw badPin();
        BigDecimal amount=check(booking,selected,ids,now);
        if (amount.compareTo(a.approvedAmount())!=0) throw new ApiException(409,"MANAGER_OVERRIDE_MISMATCH","Approved amount changed");
        selected.forEach(Ticket::cancel);
        tickets.flush();
        locked.forEach(s -> s.release(now));
        inventory.flush();
        long id=refunds.record(a,now);
        selected.forEach(t -> refunds.link(id,t.getId(),t.getPriceSnapshot()));
        refunds.consume(a.tokenHash(),id,now);
        audit.info("action=REFUND result=COMPLETED refundId={} bookingId={} managerId={} staffId={} amount={}",id,booking.getId(),a.managerId(),actor.userId(),amount);
        return refunds.response(id);
    }

    private BigDecimal check(Booking b,List<Ticket> selected,List<Long> ids,Instant now) {
        if (selected.size()!=ids.size()||selected.stream().anyMatch(t -> !t.getBookingId().equals(b.getId()))) throw invalid("Tickets must belong to one Booking");
        Showtime show=showtimes.findById(b.getShowtimeId()).orElseThrow(RefundService::notFound);
        boolean paid=paid(b);
        for (Ticket t:selected) {
            String code=reason(t,show,paid,now);
            if (code!=null) throw new ApiException(422,code,"Ticket is not eligible for refund");
        }
        BigDecimal amount=selected.stream().map(Ticket::getPriceSnapshot).reduce(BigDecimal.ZERO,BigDecimal::add);
        if (amount.signum()<=0) throw new ApiException(422,"REFUND_NOT_ELIGIBLE","No cash amount to refund");
        return amount;
    }
    private boolean paid(Booking b) {
        return b.getStatus()==BookingStatus.PAID&&payments.findByBookingId(b.getId()).map(p -> p.getStatus()==PaymentStatus.PAID).orElse(false);
    }
    private static String reason(Ticket t,Showtime s,boolean paid,Instant now) {
        if (t.getStatus()==TicketStatus.CANCELLED) return "TICKET_ALREADY_CANCELLED";
        if (t.getStatus()==TicketStatus.USED) return "TICKET_NOT_REFUNDABLE";
        if (!paid) return "REFUND_NOT_ELIGIBLE";
        if (now.isAfter(s.getStartTime().minusSeconds(1800))) return "REFUND_DEADLINE_PASSED";
        return null;
    }
    private static List<Long> selection(Long bookingId,List<Long> ids,String reason,Boolean cash) {
        if (bookingId==null||bookingId<=0||ids==null||ids.isEmpty()||ids.size()>200||ids.stream().anyMatch(i -> i==null||i<=0)
            ||new HashSet<>(ids).size()!=ids.size()||reason==null||reason.isBlank()||reason.length()>255||!Boolean.TRUE.equals(cash))
            throw invalid("Positive unique ticket IDs, reason and cash confirmation are required");
        return ids.stream().sorted().toList();
    }
    private static void requireActor(SessionPrincipal p,boolean allowManager) {
        if (p==null||p.userId()==null||p.tokenId()==null||!(p.role()==UserRole.STAFF||allowManager&&p.role()==UserRole.MANAGER))
            throw new ApiException(403,"ACCESS_DENIED","This refund action is not allowed for this role");
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_ERROR",message); }
    private static ApiException notFound() { return new ApiException(404,"BOOKING_NOT_FOUND","Booking or ticket was not found"); }
    private static ApiException badPin() { return new ApiException(403,"MANAGER_PIN_INVALID","Manager credentials are invalid or inactive"); }
}
