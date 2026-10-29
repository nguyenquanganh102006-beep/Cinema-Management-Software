package com.cinema.cms.dto;
import java.time.OffsetDateTime;
import java.util.Map;
public record ApiErrorResponse(OffsetDateTime timestamp, int status, String code, String message,
                               Map<String, String> details, String path) {}
