package com.cinema.cms.service;

import java.time.*;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ShowtimeTimeServiceTest {
    private final ShowtimeTimeService time = new ShowtimeTimeService();

    @Test void addsDurationAndBufferAcrossMidnight() {
        Instant start = time.startTime(OffsetDateTime.parse("2026-11-01T23:00:00+07:00"));
        assertThat(time.endTime(start, 120)).isEqualTo(Instant.parse("2026-11-01T18:15:00Z"));
    }

    @Test void preservesTheInstantAcrossOffsetsAndJvmTimezones() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            Instant expected = Instant.parse("2026-11-01T07:00:00Z");
            assertThat(time.startTime(OffsetDateTime.parse("2026-11-01T14:00:00+07:00"))).isEqualTo(expected);
            assertThat(time.startTime(OffsetDateTime.parse("2026-11-01T02:00:00-05:00"))).isEqualTo(expected);
        } finally { TimeZone.setDefault(original); }
    }

    @Test void normalizesToPostgresqlMicrosecondsBeforeConflictChecks() {
        assertThat(time.startTime(OffsetDateTime.parse("2026-11-01T14:00:00.123456789+07:00")))
                .isEqualTo(Instant.parse("2026-11-01T07:00:00.123456Z"));
    }

    @Test void rejectsUnsupportedDatesAndDuration() {
        assertThatThrownBy(() -> time.startTime(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> time.endTime(Instant.now(), 0)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("MOVIE_DURATION_INVALID");
        assertThatThrownBy(() -> time.endTime(Instant.now(), -1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> time.startTime(OffsetDateTime.parse("+10000-01-01T14:00:00+07:00"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> time.endTime(Instant.parse("9999-12-31T16:59:00Z"), 120)).isInstanceOf(ApiException.class);
    }
}
