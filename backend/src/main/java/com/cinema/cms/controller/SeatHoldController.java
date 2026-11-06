package com.cinema.cms.controller;

import com.cinema.cms.dto.*;
import com.cinema.cms.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1", produces = "application/json")
@PreAuthorize("hasAnyRole('STAFF','CUSTOMER')")
@SecurityRequirement(name = "bearerAuth")
@io.swagger.v3.oas.annotations.responses.ApiResponses({
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "400",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description = "VALIDATION_ERROR / INVALID_SEAT"),
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
      description = "ACCESS_DENIED / SEAT_HOLD_NOT_OWNED"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description = "SHOWTIME_NOT_FOUND"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "409",
      content =
          @io.swagger.v3.oas.annotations.media.Content(
              mediaType = "application/json",
              schema =
                  @io.swagger.v3.oas.annotations.media.Schema(
                      implementation = ApiErrorResponse.class)),
      description =
          "SEAT_NOT_AVAILABLE / SEAT_UPDATE_CONFLICT / SEAT_HOLD_EXPIRED /"
              + " SEAT_HOLD_ALREADY_EXTENDED / SEAT_HOLD_TOO_EARLY / SWEETBOX_PAIR_INVALID")
})
public class SeatHoldController {
  private final SeatHoldService holds;

  public SeatHoldController(SeatHoldService holds) {
    this.holds = holds;
  }

  @PostMapping(value = "/showtimes/{id}/holds", consumes = "application/json")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "201",
      description = "Hold created or exact retry returned")
  @Operation(summary = "Atomically hold selected seats, expanding Sweetbox pairs")
  public ApiResponse<HoldSeatsResponse> hold(
      @PathVariable Long id,
      @Valid @RequestBody HoldSeatsRequest request,
      @AuthenticationPrincipal SessionPrincipal p) {
    return new ApiResponse<>(holds.hold(id, request, p));
  }

  @PostMapping(value = "/holds/{token}/extend", consumes = "application/json")
  @PreAuthorize("hasRole('STAFF')")
  @Operation(summary = "POS: extend once after minute four while waiting for transfer")
  public ApiResponse<HoldSeatsResponse> extend(
      @PathVariable String token,
      @Valid @RequestBody ExtendHoldRequest request,
      @AuthenticationPrincipal SessionPrincipal p) {
    return new ApiResponse<>(holds.extend(token, request, p));
  }

  @DeleteMapping("/holds/{token}")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "204",
      description = "Released or absent")
  @Operation(summary = "Release an owned hold; missing token is idempotent")
  public ResponseEntity<Void> release(
      @PathVariable String token, @AuthenticationPrincipal SessionPrincipal p) {
    holds.release(token, p);
    return ResponseEntity.noContent().build();
  }
}
