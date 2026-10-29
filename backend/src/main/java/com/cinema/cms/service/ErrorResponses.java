package com.cinema.cms.service;
import com.cinema.cms.dto.ApiErrorResponse;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ErrorResponses {
    private final Clock clock;
    public ErrorResponses(Clock clock) { this.clock = clock; }
    public ApiErrorResponse body(int status, String code, String message, Map<String, String> details, String path) {
        return new ApiErrorResponse(OffsetDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Ho_Chi_Minh")),
                status, code, message, details, path);
    }
}
