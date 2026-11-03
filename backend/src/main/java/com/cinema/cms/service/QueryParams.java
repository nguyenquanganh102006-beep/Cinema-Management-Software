package com.cinema.cms.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;

final class QueryParams {
    private QueryParams() {}

    static Boolean bool(String value, String name) {
        if (value == null || value.isBlank()) return null;
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw invalid(name + " phải là true hoặc false");
    }
    static Integer integer(String value, String name) {
        if (value == null || value.isBlank()) return null;
        try { return Integer.valueOf(value.strip()); } catch (NumberFormatException e) { throw invalid(name + " phải là số nguyên"); }
    }
    static Long longValue(String value, String name) {
        if (value == null || value.isBlank()) return null;
        try { return Long.valueOf(value.strip()); } catch (NumberFormatException e) { throw invalid(name + " phải là số nguyên"); }
    }
    static Instant instant(String value, String name) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value.strip()); } catch (DateTimeParseException e) { throw invalid(name + " phải theo định dạng ISO-8601, ví dụ 2026-11-01T00:00:00Z"); }
    }
    static void checkPaging(int page, int size, int maxSize) {
        if (page < 0 || size < 1 || size > maxSize) throw invalid("page phải từ 0 trở lên và size từ 1 đến " + maxSize);
    }
    private static ApiException invalid(String message) { return new ApiException(400, "VALIDATION_ERROR", message); }
}