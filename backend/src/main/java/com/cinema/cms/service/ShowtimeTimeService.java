package com.cinema.cms.service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;

@Service
public class ShowtimeTimeService {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final int BUFFER_MINUTES = 15;

    public Instant startTime(OffsetDateTime value) {
        if (value == null) throw invalid();
        Instant start = value.toInstant().truncatedTo(ChronoUnit.MICROS);
        checkRange(start);
        return start;
    }

    public Instant endTime(Instant start, int durationMinutes) {
        if (start == null) throw invalid();
        if (durationMinutes <= 0) throw new ApiException(422, "MOVIE_DURATION_INVALID", "Thời lượng phim phải lớn hơn 0");
        try {
            checkRange(start);
            Instant end = start.plus((long) durationMinutes + BUFFER_MINUTES, ChronoUnit.MINUTES);
            checkRange(end);
            return end;
        } catch (DateTimeException | ArithmeticException e) { throw invalid(); }
    }

    private void checkRange(Instant time) {
        try {
            int year = time.atZone(BUSINESS_ZONE).getYear();
            if (year < 1 || year > 9999) throw invalid();
        } catch (DateTimeException e) { throw invalid(); }
    }
    private ApiException invalid() { return new ApiException(400, "VALIDATION_ERROR", "Thời gian phải có offset và nằm trong khoảng năm 0001–9999"); }
}
