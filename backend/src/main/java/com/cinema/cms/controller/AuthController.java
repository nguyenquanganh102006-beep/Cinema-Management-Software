package com.cinema.cms.controller;

import com.cinema.cms.dto.*;
import com.cinema.cms.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/auth", produces = "application/json")
@Tag(name = "Authentication", description = "FR-01–04: Customer registration and three-role authentication")
@ApiResponses({
    @ApiResponse(responseCode="400", description="VALIDATION_ERROR", content=@Content(schema=@Schema(implementation=ApiErrorResponse.class))),
    @ApiResponse(responseCode="401", description="AUTH_INVALID_CREDENTIALS / ACCOUNT_LOCKED / ACCOUNT_INACTIVE / AUTH_TOKEN_INVALID / AUTH_TOKEN_EXPIRED / AUTH_TRANSFER_INVALID", content=@Content(schema=@Schema(implementation=ApiErrorResponse.class))),
    @ApiResponse(responseCode="403", description="ACCESS_DENIED / CORS_ORIGIN_DENIED", content=@Content(schema=@Schema(implementation=ApiErrorResponse.class))),
    @ApiResponse(responseCode="405", description="METHOD_NOT_ALLOWED", content=@Content(schema=@Schema(implementation=ApiErrorResponse.class))),
    @ApiResponse(responseCode="406", description="NOT_ACCEPTABLE", content=@Content(schema=@Schema(implementation=ApiErrorResponse.class))),
    @ApiResponse(responseCode="415", description="UNSUPPORTED_MEDIA_TYPE", content=@Content(schema=@Schema(implementation=ApiErrorResponse.class))),
    @ApiResponse(responseCode="500", description="INTERNAL_ERROR", content=@Content(schema=@Schema(implementation=ApiErrorResponse.class))),
    @ApiResponse(responseCode="503", description="SERVICE_UNAVAILABLE", content=@Content(schema=@Schema(implementation=ApiErrorResponse.class)))
})
public class AuthController {
    private final AuthService auth;
    private final AuthTransferService transfers;
    public AuthController(AuthService auth, AuthTransferService transfers) { this.auth = auth; this.transfers = transfers; }
    @PostMapping("/register")
    @Operation(summary = "Register a Customer", description = "Public. Always CUSTOMER/ACTIVE. 400 VALIDATION_ERROR; 409 RESOURCE_CONFLICT.")
    @ApiResponse(responseCode = "201", description = "Customer created")
    @ApiResponse(responseCode = "409", description = "RESOURCE_CONFLICT", content=@Content(schema=@Schema(implementation=ApiErrorResponse.class)))
    public ResponseEntity<com.cinema.cms.dto.ApiResponse<UserResponse>> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.status(201).body(new com.cinema.cms.dto.ApiResponse<>(auth.register(req)));
    }
    @PostMapping("/login")
    @Operation(summary = "Login with username or email", description = "Public. 401 AUTH_INVALID_CREDENTIALS, ACCOUNT_LOCKED or ACCOUNT_INACTIVE. Access token expires in expiresIn seconds.")
    @ApiResponse(responseCode="200", description="Logged in")
    public com.cinema.cms.dto.ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req) { return new com.cinema.cms.dto.ApiResponse<>(auth.login(req)); }
    @GetMapping("/me") @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get current user", description = "All authenticated roles. 401 AUTH_TOKEN_INVALID or AUTH_TOKEN_EXPIRED.")
    @ApiResponse(responseCode="200", description="Current user")
    public com.cinema.cms.dto.ApiResponse<UserResponse> me(@AuthenticationPrincipal SessionPrincipal user) { return new com.cinema.cms.dto.ApiResponse<>(auth.me(user)); }
    @PostMapping("/logout") @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Revoke current access token", description = "All authenticated roles. Persistent revoke until expiry; other sessions remain valid.")
    @ApiResponse(responseCode = "204", description = "Current token revoked")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal SessionPrincipal user) { auth.logout(user); return ResponseEntity.noContent().build(); }

    @PostMapping("/transfers") @SecurityRequirement(name="bearerAuth")
    @Operation(summary="Transfer login to the user's role portal", description="Authenticated. Revoke source JWT and issue a single-use, 30-second transfer code. Destination comes from server configuration, never client input.")
    @ApiResponse(responseCode="200",description="Configured destination URL with one-time fragment code, never JWT")
    public com.cinema.cms.dto.ApiResponse<AuthTransferResponse> transfer(@AuthenticationPrincipal SessionPrincipal user) {
        return new com.cinema.cms.dto.ApiResponse<>(transfers.create(user));
    }
    @PostMapping("/transfers/consume")
    @Operation(summary="Consume portal transfer code",description="Public. Valid code and matching destination role required; one successful exchange only. FR-02 role dispatch.")
    @ApiResponse(responseCode="200",description="Destination session established")
    public com.cinema.cms.dto.ApiResponse<LoginResponse> consume(@Valid @RequestBody AuthTransferRequest req) {
        return new com.cinema.cms.dto.ApiResponse<>(transfers.consume(req));
    }
}
