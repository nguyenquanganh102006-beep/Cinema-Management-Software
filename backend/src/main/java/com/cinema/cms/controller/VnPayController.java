package com.cinema.cms.controller;

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
    @Operation(summary = "Verify the signature and show the current payment state (never finalizes)")
    public ApiResponse<VnPayReturnResponse> returnUrl(@RequestParam Map<String, String> params) {
        return new ApiResponse<>(vnPay.readReturn(params));
    }

    @GetMapping("/ipn")
    @Operation(summary = "VNPay IPN; only this endpoint may finalize an online payment")
    public VnPayIpnResponse ipn(@RequestParam Map<String, String> params) {
        return vnPay.handleIpn(params);
    }
}
