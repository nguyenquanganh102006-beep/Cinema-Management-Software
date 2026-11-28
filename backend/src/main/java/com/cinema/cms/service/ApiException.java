package com.cinema.cms.service;
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final java.util.Map<String, String> details;
    public ApiException(int status, String code, String message) { this(status, code, message, java.util.Map.of()); }
    public ApiException(int status, String code, String message, java.util.Map<String, String> details) {
        super(message); this.status = status; this.code = code; this.details = java.util.Map.copyOf(details);
    }
    public int getStatus() { return status; }
    public String getCode() { return code; }
    public java.util.Map<String, String> getDetails() { return details; }
}
