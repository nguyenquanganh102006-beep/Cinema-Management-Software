package com.cinema.cms.controller;

import com.cinema.cms.dto.SweetboxPairRequest;
import com.cinema.cms.dto.SweetboxPairResponse;
import com.cinema.cms.service.SweetboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/seats/sweetbox-pairs", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('MANAGER')")
@Tag(name = "Sweetbox", description = "Ghép và gỡ cặp ghế Sweetbox. Chỉ Manager.")
@SecurityRequirement(name = "bearerAuth")
public class SweetboxController {
    private final SweetboxService sweetbox;

    public SweetboxController(SweetboxService sweetbox) { this.sweetbox = sweetbox; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Ghép hai ghế Sweetbox thành một cặp (ghi cả hai chiều)")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Hai ghế của cặp, mỗi ghế trỏ tới ghế kia"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR hoặc SWEETBOX_PAIR_INVALID: hai ghế trùng nhau, khác phòng, khác hàng, không liền kề hoặc không phải loại SWEETBOX"),
            @ApiResponse(responseCode = "401", description = "Chưa đăng nhập"), @ApiResponse(responseCode = "403", description = "ACCESS_DENIED"),
            @ApiResponse(responseCode = "404", description = "SEAT_NOT_FOUND"),
            @ApiResponse(responseCode = "409", description = "AUDITORIUM_LAYOUT_LOCKED hoặc SWEETBOX_ALREADY_PAIRED: một trong hai ghế đã thuộc cặp khác")})
    public com.cinema.cms.dto.ApiResponse<SweetboxPairResponse> pair(@Valid @RequestBody SweetboxPairRequest request) {
        return new com.cinema.cms.dto.ApiResponse<>(sweetbox.pair(request));
    }

    @DeleteMapping("/{seatId}")
    @Operation(summary = "Gỡ cặp Sweetbox chứa ghế này (dùng id của bất kỳ ghế nào trong cặp)")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Các ghế vừa được gỡ cặp"),
            @ApiResponse(responseCode = "401", description = "Chưa đăng nhập"), @ApiResponse(responseCode = "403", description = "ACCESS_DENIED"),
            @ApiResponse(responseCode = "409", description = "AUDITORIUM_LAYOUT_LOCKED"),
            @ApiResponse(responseCode = "404", description = "SEAT_NOT_FOUND hoặc SWEETBOX_PAIR_NOT_FOUND")})
    public com.cinema.cms.dto.ApiResponse<SweetboxPairResponse> unpair(@PathVariable Long seatId) {
        return new com.cinema.cms.dto.ApiResponse<>(sweetbox.unpair(seatId));
    }
}