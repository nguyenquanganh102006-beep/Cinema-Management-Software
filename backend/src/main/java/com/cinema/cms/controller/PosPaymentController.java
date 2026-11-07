package com.cinema.cms.controller;

import com.cinema.cms.dto.*;
import com.cinema.cms.service.PosPaymentService;
import com.cinema.cms.service.SessionPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** POS-only payment endpoints. Kept separate so CUSTOMER cannot share a controller role. */
@RestController
@RequestMapping(value = "/api/v1/bookings/{bookingId}/payments", produces = "application/json")
@PreAuthorize("hasRole('STAFF')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "POS Payments", description = "Thanh toán tại quầy cho Booking kênh POS (STAFF)")
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
      description = "ACCESS_DENIED (only STAFF may use POS payments)"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description =
          "BOOKING_NOT_FOUND / PAYMENT_NOT_FOUND / SHOWTIME_NOT_FOUND / MOVIE_NOT_FOUND"),
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
              + " PAYMENT_INVALID_STATE / PAYMENT_AMOUNT_MISMATCH / PRICE_CHANGED /"
              + " SEAT_HOLD_EXPIRED / SEAT_HOLD_MISMATCH / SEAT_UPDATE_CONFLICT /"
              + " INSUFFICIENT_STOCK / OUT_OF_STOCK"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "422",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description =
          "BOOKING_CHANNEL_INVALID / AGE_VERIFICATION_REQUIRED / PAYMENT_CASH_INSUFFICIENT")
})
public class PosPaymentController {
    private final PosPaymentService payments;

    public PosPaymentController(PosPaymentService payments) {
        this.payments = payments;
    }

    @PostMapping(value = "/cash", consumes = "application/json")
    @Operation(
            summary = "POS: collect cash, record the age check and issue tickets",
            description =
                    "Role: STAFF. The amount charged comes from Booking.totalAmount, never from"
                            + " the request; cashReceived is used only to compute change. Success"
                            + " issues one VALID Ticket per showtime seat (two for a Sweetbox"
                            + " pair) in the same transaction.")
    public ApiResponse<CashPaymentResponse> cash(
            @PathVariable Long bookingId,
            @Valid @RequestBody CashPaymentRequest request,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(payments.payCash(bookingId, request, principal));
    }

    @PostMapping(value = "/vietqr", consumes = "application/json")
    @Operation(
            summary = "POS: create a PENDING VietQR transfer for the booking",
            description =
                    "Role: STAFF. Creates at most one PENDING VIETQR Payment per booking and"
                            + " returns the QR payload; tickets are not issued until the staff"
                            + " confirms the transfer.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            content =
                    @io.swagger.v3.oas.annotations.media.Content(
                        mediaType = "application/json",
                        schema =
                            @io.swagger.v3.oas.annotations.media.Schema(
                                implementation = ApiErrorResponse.class)),
            description = "PAYMENT_CONFIGURATION_MISSING")
    public ApiResponse<VietQrPaymentResponse> vietQr(
            @PathVariable Long bookingId,
            @Valid @RequestBody VietQrPaymentRequest request,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(payments.createVietQr(bookingId, request, principal));
    }

    @PostMapping("/vietqr/confirm")
    @Operation(
            summary = "POS: confirm the transfer was received and issue tickets",
            description =
                    "Role: STAFF. Confirms the existing PENDING VIETQR Payment and runs the same"
                            + " checkout transaction as cash; replaying it on a PAID booking returns"
                            + " BOOKING_ALREADY_PAID.")
    public ApiResponse<VietQrConfirmResponse> confirmVietQr(
            @PathVariable Long bookingId,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return new ApiResponse<>(payments.confirmVietQr(bookingId, principal));
    }
}
