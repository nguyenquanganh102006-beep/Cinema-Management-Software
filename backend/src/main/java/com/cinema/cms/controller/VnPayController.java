package com.cinema.cms.controller;

import com.cinema.cms.dto.ApiErrorResponse;
import com.cinema.cms.dto.ApiResponse;
import com.cinema.cms.dto.VnPayIpnResponse;
import com.cinema.cms.dto.VnPayReturnResponse;
import com.cinema.cms.service.VnPayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/**
 * Public VNPay callbacks. These endpoints intentionally have no {@code @PreAuthorize} and do not
 * depend on JWT; the HMAC signature is the authentication mechanism.
 */
@RestController
@RequestMapping(value = "/api/v1/payments/vnpay", produces = "application/json")
@Tag(name = "VNPay callbacks", description = "Return URL và IPN công khai của VNPay")
public class VnPayController {
    private final VnPayService vnPay;

    public VnPayController(VnPayService vnPay) {
        this.vnPay = vnPay;
    }

    @GetMapping("/return")
    @Operation(
            summary = "Verify the signature and show the current payment state (never finalizes)",
            description =
                    "Public, no JWT: the HMAC-SHA512 signature is the authentication. This endpoint"
                            + " only reports the Payment/Booking state so a forged browser redirect"
                            + " cannot issue tickets.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
      @io.swagger.v3.oas.annotations.responses.ApiResponse(
          responseCode = "400",
          content =
              @io.swagger.v3.oas.annotations.media.Content(
                  mediaType = "application/json",
                  schema =
                      @io.swagger.v3.oas.annotations.media.Schema(
                          implementation = ApiErrorResponse.class)),
          description = "VNPAY_INVALID_SIGNATURE / VALIDATION_ERROR")
    })
    public ApiResponse<VnPayReturnResponse> returnUrl(@RequestParam Map<String, String> params) {
        return new ApiResponse<>(vnPay.readReturn(params));
    }

    @GetMapping("/ipn")
    @Operation(
            summary = "VNPay IPN; only this endpoint may finalize an online payment",
            description =
                    "Public, no JWT: verifies signature, merchant code, transaction reference,"
                            + " amount, booking and idempotency before finalizing. Always returns"
                            + " VNPay's RspCode contract instead of an HTTP error: 00 success"
                            + " (including EXCEPTION_PENDING_DECISION, to stop retries), 01 order"
                            + " not found, 02 already confirmed, 04 invalid amount, 97 invalid"
                            + " signature, 99 unknown/transient (VNPay may retry).")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            content =
                @io.swagger.v3.oas.annotations.media.Content(
                    mediaType = "application/json",
                    schema =
                        @io.swagger.v3.oas.annotations.media.Schema(
                            implementation = VnPayIpnResponse.class)),
            description =
                "RspCode 00/01/02/04/97/99 as described in the operation; never an HTTP error")
    public VnPayIpnResponse ipn(@RequestParam Map<String, String> params) {
        return vnPay.handleIpn(params);
    }
}
