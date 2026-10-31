package com.cinema.cms.controller;

import com.cinema.cms.dto.*;
import com.cinema.cms.service.AuditoriumService;
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
@RequestMapping(value = "/api/v1/auditoriums", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('MANAGER')")
@Tag(name = "Auditoriums", description = "Quản lý phòng chiếu. Chỉ Manager.")
@SecurityRequirement(name = "bearerAuth")
public class AuditoriumController {
    private final AuditoriumService auditoriums;

    public AuditoriumController(AuditoriumService auditoriums) { this.auditoriums = auditoriums; }

    @GetMapping
    @Operation(summary = "Danh sách phòng chiếu (không phân trang), lọc theo trạng thái, sắp xếp theo tên")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Danh sách phòng kèm số ghế hiện có"),
            @ApiResponse(responseCode = "401", description = "Chưa đăng nhập"), @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")})
    public com.cinema.cms.dto.ApiResponse<List<AuditoriumResponse>> list(@RequestParam(required = false) Boolean active) {
        return new com.cinema.cms.dto.ApiResponse<>(auditoriums.list(active));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết một phòng chiếu")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Thông tin phòng"), @ApiResponse(responseCode = "404", description = "AUDITORIUM_NOT_FOUND")})
    public com.cinema.cms.dto.ApiResponse<AuditoriumResponse> get(@PathVariable Integer id) {
        return new com.cinema.cms.dto.ApiResponse<>(auditoriums.get(id));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo phòng chiếu (mặc định đang hoạt động, chưa có ghế)")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Đã tạo phòng"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: thiếu hoặc sai trường, loại phòng không hợp lệ"),
            @ApiResponse(responseCode = "409", description = "AUDITORIUM_NAME_EXISTS")})
    public com.cinema.cms.dto.ApiResponse<AuditoriumResponse> create(@Valid @RequestBody AuditoriumRequest request) {
        return new com.cinema.cms.dto.ApiResponse<>(auditoriums.create(request));
    }

    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Cập nhật tên, sức chứa, loại phòng (thay toàn bộ, không đổi trạng thái)")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Phòng sau khi cập nhật"), @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
            @ApiResponse(responseCode = "404", description = "AUDITORIUM_NOT_FOUND"),
            @ApiResponse(responseCode = "409", description = "AUDITORIUM_NAME_EXISTS hoặc AUDITORIUM_CAPACITY_BELOW_SEATS")})
    public com.cinema.cms.dto.ApiResponse<AuditoriumResponse> update(@PathVariable Integer id, @Valid @RequestBody AuditoriumRequest request) {
        return new com.cinema.cms.dto.ApiResponse<>(auditoriums.update(id, request));
    }

    @PatchMapping(value = "/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Bật hoặc tạm ngưng phòng chiếu")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Phòng sau khi đổi trạng thái"), @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
            @ApiResponse(responseCode = "404", description = "AUDITORIUM_NOT_FOUND")})
    public com.cinema.cms.dto.ApiResponse<AuditoriumResponse> changeStatus(@PathVariable Integer id, @Valid @RequestBody AuditoriumStatusRequest request) {
        return new com.cinema.cms.dto.ApiResponse<>(auditoriums.changeStatus(id, request));
    }
}