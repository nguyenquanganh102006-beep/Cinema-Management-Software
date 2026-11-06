package com.cinema.cms.controller;

import com.cinema.cms.dto.*;
import com.cinema.cms.service.SeatService;
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
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('MANAGER')")
@Tag(name = "Seats", description = "Quản lý ghế vật lý của phòng chiếu. Chỉ Manager. Ghép cặp Sweetbox có API riêng.")
@SecurityRequirement(name = "bearerAuth")
public class SeatController {
    private final SeatService seats;

    public SeatController(SeatService seats) { this.seats = seats; }

    @GetMapping("/auditoriums/{auditoriumId}/seats")
    @Operation(summary = "Danh sách ghế của phòng, sắp xếp theo hàng rồi số ghế")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Danh sách ghế"), @ApiResponse(responseCode = "404", description = "AUDITORIUM_NOT_FOUND")})
    public com.cinema.cms.dto.ApiResponse<List<SeatResponse>> list(@PathVariable Integer auditoriumId) {
        return new com.cinema.cms.dto.ApiResponse<>(seats.list(auditoriumId));
    }

    @PostMapping(value = "/auditoriums/{auditoriumId}/seats", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Thêm một hoặc nhiều ghế (tối đa 500 ghế mỗi lần, tất cả hoặc không ghế nào)")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Các ghế đã tạo"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: số ghế không dương, hàng sai định dạng, ghế lặp trong yêu cầu, danh sách rỗng hoặc quá 500"),
            @ApiResponse(responseCode = "404", description = "AUDITORIUM_NOT_FOUND"),
            @ApiResponse(responseCode = "409", description = "AUDITORIUM_LAYOUT_LOCKED hoặc SEAT_POSITION_TAKEN hoặc AUDITORIUM_CAPACITY_EXCEEDED")})
    public com.cinema.cms.dto.ApiResponse<List<SeatResponse>> create(@PathVariable Integer auditoriumId, @Valid @RequestBody CreateSeatsRequest request) {
        return new com.cinema.cms.dto.ApiResponse<>(seats.create(auditoriumId, request));
    }

    @PatchMapping(value = "/seats/{seatId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Sửa vị trí hoặc loại của một ghế (thay toàn bộ). Ghế đang ghép cặp Sweetbox không sửa được")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Ghế sau khi sửa"), @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
            @ApiResponse(responseCode = "404", description = "SEAT_NOT_FOUND"),
            @ApiResponse(responseCode = "409", description = "AUDITORIUM_LAYOUT_LOCKED hoặc SEAT_POSITION_TAKEN hoặc SEAT_IS_PAIRED")})
    public com.cinema.cms.dto.ApiResponse<SeatResponse> update(@PathVariable Long seatId, @Valid @RequestBody SeatRequest request) {
        return new com.cinema.cms.dto.ApiResponse<>(seats.update(seatId, request));
    }
}