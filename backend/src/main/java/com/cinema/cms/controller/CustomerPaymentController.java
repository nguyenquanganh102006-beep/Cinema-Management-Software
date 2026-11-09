package com.cinema.cms.controller;

import com.cinema.cms.dto.ApiErrorResponse;
import com.cinema.cms.dto.ApiResponse;
import com.cinema.cms.dto.VnPayPaymentResponse;
import com.cinema.cms.service.SessionPrincipal;
import com.cinema.cms.service.VnPayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Customer-only online payment endpoints. Kept separate from the STAFF POS controller. */
@RestController
@RequestMapping(value = "/api/v1/bookings/{bookingId}/payments", produces = "application/json")
@PreAuthorize("hasRole('CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Customer Payments", description = "Thanh toán online VNPay cho Booking ONLINE")
@io.swagger.v3.oas.annotations.responses.ApiResponses({
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "400",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description = "VALIDATION_ERROR"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "401",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description = "AUTH_TOKEN_INVALID / AUTH_TOKEN_EXPIRED"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "403",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description = "ACCESS_DENIED (only CUSTOMER may pay online)"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description =
          "BOOKING_NOT_FOUND (also when the booking belongs to another customer) /"
              + " SHOWTIME_NOT_FOUND / MOVIE_NOT_FOUND"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "409",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description =
          "BOOKING_ALREADY_PAID / BOOKING_INVALID_STATE / PAYMENT_METHOD_CONFLICT /"
              + " PAYMENT_INVALID_STATE / PAYMENT_AMOUNT_MISMATCH / SEAT_HOLD_EXPIRED"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "422",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description = "BOOKING_CHANNEL_INVALID / AGE_CONFIRMATION_REQUIRED"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "503",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description = "PAYMENT_CONFIGURATION_MISSING")
})
public class CustomerPaymentController {
    private final VnPayService vnPay;

    public CustomerPaymentController(VnPayService vnPay) {
        this.vnPay = vnPay;
    }

    @PostMapping("/vnpay")
    @Operation(
            summary = "Create or refresh the VNPay redirect URL for the caller's ONLINE booking",
            description =
                    "Role: CUSTOMER (owner only). Returns a signed VNPay URL; no ticket is issued"
                            + " here. Only a valid VNPay IPN finalizes the payment.")
    public ApiResponse<VnPayPaymentResponse> vnPay(
            @PathVariable Long bookingId,
            @AuthenticationPrincipal SessionPrincipal principal,
            HttpServletRequest request) {
        return new ApiResponse<>(
                vnPay.createPayment(bookingId, principal, request.getRemoteAddr()));
    }
}
