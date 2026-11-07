package com.cinema.cms.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Generates BK-yyMMdd-NNNN from the per-day counter in the caller's transaction. */
@Service
public class BookingCodeGenerator {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyMMdd");
    private static final String COUNTER_SQL =
            "insert into booking_code_counters(booking_date, last_number) values (?, 1) "
                    + "on conflict (booking_date) do update set last_number = booking_code_counters.last_number + 1 "
                    + "returning last_number";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public BookingCodeGenerator(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next() {
        LocalDate day = LocalDate.ofInstant(clock.instant(), ShowtimeTimeService.BUSINESS_ZONE);
        Integer sequence = jdbc.queryForObject(COUNTER_SQL, Integer.class, java.sql.Date.valueOf(day));
        if (sequence == null || sequence <= 0)
            throw new IllegalStateException("Booking code counter did not return a positive value");
        // %04d grows naturally beyond 9999 and still fits bookings.booking_code VARCHAR(30).
        return "BK-" + day.format(DATE_FORMAT) + "-" + String.format(Locale.ROOT, "%04d", sequence);
    }
}
