package com.cinema.cms.controller;

import com.cinema.cms.dto.*;
import com.cinema.cms.service.StaffService;
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
@RequestMapping(value = "/api/v1/staff", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('MANAGER')")
@Tag(name = "Staff", description = "Quản lý tài khoản nhân viên (vai trò STAFF). Chỉ Manager.")
@SecurityRequirement(name = "bearerAuth")
public class StaffController {
    private final StaffService staff;

    public StaffController(StaffService staff) { this.staff = staff; }

    @GetMapping
    @Operation(summary = "Danh sách nhân viên, tìm theo tên hoặc tên đăng nhập, lọc trạng thái, phân trang")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Trang nhân viên"),
            @ApiResponse(responseCode = "400", description = "status, page hoặc size không hợp lệ (size tối đa 100)"),
            @ApiResponse(responseCode = "401", description = "Chưa đăng nhập"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED: không phải Manager")})
    public com.cinema.cms.dto.ApiResponse<PageResponse<StaffResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return new com.cinema.cms.dto.ApiResponse<>(staff.list(keyword, status, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết một nhân viên")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Thông tin nhân viên, không có mật khẩu"),
            @ApiResponse(responseCode = "401", description = "Chưa đăng nhập"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED: không phải Manager"),
            @ApiResponse(responseCode = "404", description = "STAFF_NOT_FOUND, kể cả khi id thuộc Manager hoặc khách hàng")})
    public com.cinema.cms.dto.ApiResponse<StaffResponse> get(@PathVariable Long id) {
        return new com.cinema.cms.dto.ApiResponse<>(staff.get(id));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo nhân viên (vai trò luôn là STAFF, trạng thái ACTIVE)")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Đã tạo nhân viên"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: sai trường, mật khẩu quá ngắn hoặc quá 72 byte, có trường lạ như role"),
            @ApiResponse(responseCode = "401", description = "Chưa đăng nhập"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED: không phải Manager"),
            @ApiResponse(responseCode = "409", description = "USERNAME_ALREADY_EXISTS, EMAIL_ALREADY_EXISTS hoặc PHONE_ALREADY_EXISTS")})
    public com.cinema.cms.dto.ApiResponse<StaffResponse> create(@Valid @RequestBody CreateStaffRequest request) {
        return new com.cinema.cms.dto.ApiResponse<>(staff.create(request));
    }

    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Cập nhật họ tên, email, số điện thoại (không đổi username, vai trò, mật khẩu, trạng thái)")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Nhân viên sau khi cập nhật"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
            @ApiResponse(responseCode = "401", description = "Chưa đăng nhập"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED: không phải Manager"),
            @ApiResponse(responseCode = "404", description = "STAFF_NOT_FOUND"),
            @ApiResponse(responseCode = "409", description = "EMAIL_ALREADY_EXISTS hoặc PHONE_ALREADY_EXISTS")})
    public com.cinema.cms.dto.ApiResponse<StaffResponse> update(@PathVariable Long id, @Valid @RequestBody UpdateStaffRequest request) {
        return new com.cinema.cms.dto.ApiResponse<>(staff.update(id, request));
    }

    @PatchMapping(value = "/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Kích hoạt hoặc ngừng hoạt động tài khoản nhân viên")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Nhân viên sau khi đổi trạng thái"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: thiếu trường active"),
            @ApiResponse(responseCode = "401", description = "Chưa đăng nhập"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED: không phải Manager"),
            @ApiResponse(responseCode = "404", description = "STAFF_NOT_FOUND")})
    public com.cinema.cms.dto.ApiResponse<StaffResponse> changeStatus(@PathVariable Long id, @Valid @RequestBody StaffStatusRequest request) {
        return new com.cinema.cms.dto.ApiResponse<>(staff.changeStatus(id, request));
    }
}