package com.cinema.cms.controller;

import com.cinema.cms.dto.ApiErrorResponse;
import com.cinema.cms.service.ApiException;
import com.cinema.cms.service.ErrorResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.ErrorResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ErrorResponses errors;
    public GlobalExceptionHandler(ErrorResponses errors) { this.errors = errors; }
    private ResponseEntity<ApiErrorResponse> response(int status, String code, String message, Map<String,String> details, HttpServletRequest req) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(errors.body(status, code, message, details, req.getRequestURI()));
    }
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> api(ApiException e, HttpServletRequest req) {
        return response(e.getStatus(), e.getCode(), e.getMessage(), Map.of(), req);
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException e, HttpServletRequest req) {
        Map<String,String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        return response(400, "VALIDATION_ERROR", "Dữ liệu không hợp lệ", fields, req);
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> json(HttpServletRequest req) { return response(400,"VALIDATION_ERROR","JSON hoặc trường dữ liệu không hợp lệ",Map.of(),req); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorResponse> conflict(HttpServletRequest req) { return response(409,"RESOURCE_CONFLICT","Dữ liệu đã tồn tại hoặc vi phạm ràng buộc",Map.of(),req); }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> denied(HttpServletRequest req) { return response(403,"ACCESS_DENIED","Không đủ quyền truy cập",Map.of(),req); }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiErrorResponse> notFound(HttpServletRequest req) { return response(404,"RESOURCE_NOT_FOUND","Không tìm thấy tài nguyên",Map.of(),req); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> unexpected(Exception e, HttpServletRequest req) {
        if (e instanceof ErrorResponse httpError) {
            int status = httpError.getStatusCode().value();
            String code = switch (status) {
                case 405 -> "METHOD_NOT_ALLOWED";
                case 415 -> "UNSUPPORTED_MEDIA_TYPE";
                case 406 -> "NOT_ACCEPTABLE";
                default -> "HTTP_REQUEST_ERROR";
            };
            return ResponseEntity.status(status).headers(httpError.getHeaders()).contentType(MediaType.APPLICATION_JSON).body(
                    errors.body(status, code, "Yêu cầu HTTP không hợp lệ", Map.of(), req.getRequestURI()));
        }
        // Log only the exception class: request values and SQL can contain credentials.
        log.error("Unhandled failure type={} path={}", e.getClass().getSimpleName(), req.getRequestURI());
        return response(500,"INTERNAL_ERROR","Hệ thống gặp lỗi, vui lòng thử lại",Map.of(),req);
    }
}
