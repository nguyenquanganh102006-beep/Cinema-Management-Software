package com.cinema.cms.service;

import com.cinema.cms.dto.PricingQuoteResponse;
import com.cinema.cms.dto.TicketPrice;
import com.cinema.cms.entity.*;
import com.cinema.cms.repository.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Computes per-seat ticket prices from the showtime snapshot plus the pricing rule table. */
@Service
public class PricingService {
    static final String TIME_TARGET = "WEEKEND_OR_AFTER_18H";
    private static final LocalTime EVENING = LocalTime.of(18, 0);
    private static final int MAX_SEATS = 100;

    private final ShowtimeRepository showtimes;
    private final AuditoriumRepository auditoriums;
    private final SeatRepository seats;
    private final PricingRuleRepository rules;

    public PricingService(
            ShowtimeRepository showtimes,
            AuditoriumRepository auditoriums,
            SeatRepository seats,
            PricingRuleRepository rules) {
        this.showtimes = showtimes;
        this.auditoriums = auditoriums;
        this.seats = seats;
        this.rules = rules;
    }

    @Transactional(readOnly = true)
    public PricingQuoteResponse quote(Long showtimeId, Collection<Long> seatIds) {
        SeatHoldService.validateShowtimeId(showtimeId);
        List<Long> ids = validatedSeatIds(seatIds);

        Showtime showtime =
                showtimes
                        .findById(showtimeId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                404, "SHOWTIME_NOT_FOUND", "Không tìm thấy suất chiếu"));
        Auditorium room =
                auditoriums
                        .findById(showtime.getAuditoriumId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                404, "AUDITORIUM_NOT_FOUND", "Không tìm thấy phòng chiếu"));

        Map<Long, Seat> physical = new HashMap<>();
        seats.findByAuditoriumIdOrderBySeatRowAscSeatNumberAsc(room.getId())
                .forEach(seat -> physical.put(seat.getId(), seat));

        Map<RuleKey, BigDecimal> amounts = ruleAmounts();
        BigDecimal basePrice = money(showtime.getBasePrice());
        BigDecimal roomSurcharge =
                ruleAmount(amounts, PricingRuleType.ROOM_SURCHARGE, room.getType().value());
        BigDecimal timeSurcharge =
                timeSurchargeApplies(showtime.getStartTime())
                        ? ruleAmount(amounts, PricingRuleType.TIME_SURCHARGE, TIME_TARGET)
                        : zero();

        List<TicketPrice> prices = new ArrayList<>(ids.size());
        BigDecimal ticketTotal = zero();
        for (Long seatId : ids) {
            Seat seat = physical.get(seatId);
            if (seat == null)
                throw new ApiException(400, "INVALID_SEAT", "Ghế không thuộc phòng chiếu");
            BigDecimal seatSurcharge =
                    ruleAmount(amounts, PricingRuleType.SEAT_SURCHARGE, seat.getSeatType().name());
            BigDecimal finalPrice =
                    money(
                            basePrice
                                    .add(roomSurcharge)
                                    .add(seatSurcharge)
                                    .add(timeSurcharge));
            prices.add(
                    new TicketPrice(
                            seatId,
                            basePrice,
                            roomSurcharge,
                            seatSurcharge,
                            timeSurcharge,
                            finalPrice));
            ticketTotal = money(ticketTotal.add(finalPrice));
        }
        return new PricingQuoteResponse(showtimeId, prices, ticketTotal);
    }

    private static List<Long> validatedSeatIds(Collection<Long> seatIds) {
        if (seatIds == null
                || seatIds.isEmpty()
                || seatIds.size() > MAX_SEATS
                || seatIds.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(seatIds).size() != seatIds.size())
            throw new ApiException(400, "VALIDATION_ERROR", "Danh sách ghế không hợp lệ");
        return new TreeSet<>(seatIds).stream().toList();
    }

    private Map<RuleKey, BigDecimal> ruleAmounts() {
        Map<RuleKey, BigDecimal> amounts = new HashMap<>();
        for (PricingRule rule : rules.findAllByOrderByRuleTypeAscTargetRefAsc()) {
            amounts.put(new RuleKey(rule.getRuleType(), rule.getTargetRef()), money(rule.getAmount()));
        }
        return amounts;
    }

    private static BigDecimal ruleAmount(
            Map<RuleKey, BigDecimal> amounts, PricingRuleType type, String targetRef) {
        BigDecimal amount = amounts.get(new RuleKey(type, targetRef));
        if (amount == null)
            throw new ApiException(
                    422,
                    "PRICING_RULE_MISSING",
                    "Chưa cấu hình giá cho " + type + " / " + targetRef);
        return amount;
    }

    /**
     * Friday, Saturday, Sunday or after 18:00 in the cinema business zone. The surcharge is a
     * single amount even when both conditions match.
     */
    private static boolean timeSurchargeApplies(Instant start) {
        ZonedDateTime local = start.atZone(ShowtimeTimeService.BUSINESS_ZONE);
        DayOfWeek day = local.getDayOfWeek();
        return day == DayOfWeek.FRIDAY
                || day == DayOfWeek.SATURDAY
                || day == DayOfWeek.SUNDAY
                || local.toLocalTime().isAfter(EVENING);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    private record RuleKey(PricingRuleType type, String targetRef) {}
}
