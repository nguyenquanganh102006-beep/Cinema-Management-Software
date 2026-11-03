package com.cinema.cms.controller;

import com.cinema.cms.dto.*;
import com.cinema.cms.service.ConcessionService;
import com.cinema.cms.service.SessionPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/concessions", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Concessions", description = "Danh mục món lẻ và combo. Đọc: mọi tài khoản đã đăng nhập (chỉ Manager thấy phần ngừng bán). Ghi: chỉ Manager.")
@SecurityRequirement(name = "bearerAuth")
public class ConcessionController {
    private final ConcessionService concessions;

    public ConcessionController(ConcessionService concessions) { this.concessions = concessions; }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Danh sách món lẻ và combo, tìm theo tên, lọc theo trạng thái và loại, phân trang")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Trang món, combo kèm thành phần"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: active, combo, page hoặc size không hợp lệ (size tối đa 100)"),
            @ApiResponse(responseCode = "401", description = "Chưa đăng nhập")})
    public com.cinema.cms.dto.ApiResponse<PageResponse<ConcessionResponse>> list(
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String active, @RequestParam(required = false) String combo,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal SessionPrincipal user) {
        return new com.cinema.cms.dto.ApiResponse<>(concessions.list(keyword, active, combo, page, size, user.role()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Chi tiết một món hoặc combo")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Thông tin món"), @ApiResponse(responseCode = "401", description = "Chưa đăng nhập"),
            @ApiResponse(responseCode = "404", description = "CONCESSION_ITEM_NOT_FOUND, kể cả khi món ngừng bán và người xem không phải Manager")})
    public com.cinema.cms.dto.ApiResponse<ConcessionResponse> get(@PathVariable Integer id, @AuthenticationPrincipal SessionPrincipal user) {
        return new com.cinema.cms.dto.ApiResponse<>(concessions.get(id, user.role()));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MANAGER')")
    @Operation(summary = "Tạo món lẻ (mặc định đang bán, tồn kho ban đầu bằng 0)")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Đã tạo món và dòng tồn kho"), @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED: không phải Manager"), @ApiResponse(responseCode = "409", description = "CONCESSION_NAME_EXISTS")})
    public com.cinema.cms.dto.ApiResponse<ConcessionResponse> createItem(@Valid @RequestBody ConcessionRequest request) {
        return new com.cinema.cms.dto.ApiResponse<>(concessions.createItem(request));
    }

    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('MANAGER')")
    @Operation(summary = "Sửa tên và giá của món lẻ (thay toàn bộ, không đổi trạng thái). Combo dùng endpoint riêng")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Món sau khi sửa"), @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED"), @ApiResponse(responseCode = "404", description = "CONCESSION_ITEM_NOT_FOUND, kể cả khi id là combo"),
            @ApiResponse(responseCode = "409", description = "CONCESSION_NAME_EXISTS")})
    public com.cinema.cms.dto.ApiResponse<ConcessionResponse> updateItem(@PathVariable Integer id, @Valid @RequestBody ConcessionRequest request) {
        return new com.cinema.cms.dto.ApiResponse<>(concessions.updateItem(id, request));
    }

    @PatchMapping(value = "/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('MANAGER')")
    @Operation(summary = "Bật hoặc ngừng bán một món hoặc combo")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Món sau khi đổi trạng thái"), @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED"), @ApiResponse(responseCode = "404", description = "CONCESSION_ITEM_NOT_FOUND"),
            @ApiResponse(responseCode = "409", description = "CONCESSION_ITEM_IN_ACTIVE_COMBO hoặc CONCESSION_COMBO_COMPONENT_INACTIVE")})
    public com.cinema.cms.dto.ApiResponse<ConcessionResponse> changeStatus(@PathVariable Integer id, @Valid @RequestBody ConcessionStatusRequest request) {
        return new com.cinema.cms.dto.ApiResponse<>(concessions.changeStatus(id, request));
    }

    @PostMapping(value = "/combos", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MANAGER')")
    @Operation(summary = "Tạo combo từ các món lẻ đang bán")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Đã tạo combo"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR hoặc CONCESSION_COMBO_INVALID: thành phần lặp, không tồn tại, là combo hoặc đang ngừng bán"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED"), @ApiResponse(responseCode = "409", description = "CONCESSION_NAME_EXISTS")})
    public com.cinema.cms.dto.ApiResponse<ConcessionResponse> createCombo(@Valid @RequestBody ComboRequest request) {
        return new com.cinema.cms.dto.ApiResponse<>(concessions.createCombo(request));
    }

    @PatchMapping(value = "/combos/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('MANAGER')")
    @Operation(summary = "Sửa combo (thay toàn bộ kể cả danh sách thành phần, không đổi trạng thái)")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Combo sau khi sửa"), @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR hoặc CONCESSION_COMBO_INVALID"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED"), @ApiResponse(responseCode = "404", description = "CONCESSION_ITEM_NOT_FOUND, kể cả khi id là món lẻ"),
            @ApiResponse(responseCode = "409", description = "CONCESSION_NAME_EXISTS")})
    public com.cinema.cms.dto.ApiResponse<ConcessionResponse> updateCombo(@PathVariable Integer id, @Valid @RequestBody ComboRequest request) {
        return new com.cinema.cms.dto.ApiResponse<>(concessions.updateCombo(id, request));
    }
}