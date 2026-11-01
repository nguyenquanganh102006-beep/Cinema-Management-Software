package com.cinema.cms.controller;

import com.cinema.cms.dto.*;
import com.cinema.cms.service.PricingRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/pricing-rules", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('MANAGER')")
@Tag(name = "Pricing rules", description = "Xem và quản lý quy tắc giá. Chỉ Manager. Giá một ghế = giá cơ bản + phụ thu phòng + phụ thu loại ghế + phụ thu khung giờ (chỉ tính một lần).")
@SecurityRequirement(name = "bearerAuth")
public class PricingRuleController {
    private final PricingRuleService rules;

    public PricingRuleController(PricingRuleService rules) { this.rules = rules; }

    @GetMapping
    @Operation(summary = "Danh sách quy tắc giá (không phân trang), sắp xếp theo loại rồi đối tượng, lọc được theo loại")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Danh sách quy tắc"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: type không hợp lệ"),
            @ApiResponse(responseCode = "401", description = "Chưa đăng nhập"), @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")})
    public com.cinema.cms.dto.ApiResponse<List<PricingRuleResponse>> list(@RequestParam(required = false) String type) {
        return new com.cinema.cms.dto.ApiResponse<>(rules.list(type));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết một quy tắc giá")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Thông tin quy tắc"), @ApiResponse(responseCode = "404", description = "PRICING_RULE_NOT_FOUND")})
    public com.cinema.cms.dto.ApiResponse<PricingRuleResponse> get(@PathVariable Integer id) {
        return new com.cinema.cms.dto.ApiResponse<>(rules.get(id));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo quy tắc giá cho một cặp (loại, đối tượng) chưa có quy tắc")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Đã tạo quy tắc"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR hoặc PRICING_RULE_INVALID: đối tượng không thuộc danh mục của loại"),
            @ApiResponse(responseCode = "409", description = "PRICING_RULE_CODE_EXISTS hoặc PRICING_RULE_EXISTS")})
    public com.cinema.cms.dto.ApiResponse<PricingRuleResponse> create(@Valid @RequestBody CreatePricingRuleRequest request) {
        return new com.cinema.cms.dto.ApiResponse<>(rules.create(request));
    }

    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Đổi số tiền và mô tả của quy tắc (không đổi mã, loại, đối tượng)")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Quy tắc sau khi cập nhật"), @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: số tiền âm, quá lớn hoặc quá 2 chữ số thập phân, có trường lạ"),
            @ApiResponse(responseCode = "404", description = "PRICING_RULE_NOT_FOUND")})
    public com.cinema.cms.dto.ApiResponse<PricingRuleResponse> update(@PathVariable Integer id, @Valid @RequestBody UpdatePricingRuleRequest request) {
        return new com.cinema.cms.dto.ApiResponse<>(rules.update(id, request));
    }
}