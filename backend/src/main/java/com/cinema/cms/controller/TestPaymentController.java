package com.cinema.cms.controller;

import com.cinema.cms.dto.ApiResponse;
import com.cinema.cms.dto.TestPaymentModeResponse;
import com.cinema.cms.dto.TestPaymentResultResponse;
import com.cinema.cms.service.ApiException;
import com.cinema.cms.service.SessionPrincipal;
import com.cinema.cms.service.TestPaymentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exists only with the local profile; additionally disabled unless explicitly opted in. */
@RestController
@Profile("local")
@RequestMapping(value = "/api/v1", produces = "application/json")
@PreAuthorize("hasRole('CUSTOMER')")
public class TestPaymentController {
    private final TestPaymentService testPayments;
    private final boolean enabled;

    public TestPaymentController(
            TestPaymentService testPayments,
            @Value("${app.payments.demo-enabled:false}") boolean enabled) {
        this.testPayments = testPayments;
        this.enabled = enabled;
    }

    @GetMapping("/payments/test-mode")
    public ApiResponse<TestPaymentModeResponse> mode() {
        return new ApiResponse<>(new TestPaymentModeResponse(enabled));
    }

    @PostMapping("/bookings/{bookingId}/payments/test-complete")
    public ApiResponse<TestPaymentResultResponse> complete(
            @PathVariable Long bookingId,
            @AuthenticationPrincipal SessionPrincipal principal) {
        if (!enabled) {
            throw new ApiException(404, "TEST_PAYMENT_DISABLED", "Mô phỏng thanh toán đang tắt");
        }
        return new ApiResponse<>(testPayments.complete(bookingId, principal));
    }
}
