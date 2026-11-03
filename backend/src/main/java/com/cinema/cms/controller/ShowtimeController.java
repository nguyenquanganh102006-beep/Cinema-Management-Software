package com.cinema.cms.controller;

import com.cinema.cms.dto.*;
import com.cinema.cms.service.ApiException;
import com.cinema.cms.service.ShowtimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/showtimes", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Showtimes", description = "Lịch chiếu, buffer 15 phút và khởi tạo inventory ghế. Ghi: Manager; đọc: mọi tài khoản đã đăng nhập.")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: input/JSON/filter/id không hợp lệ"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Thiếu token hoặc token không hợp lệ"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ACCESS_DENIED: chỉ Manager được ghi"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "SHOWTIME_NOT_FOUND / MOVIE_NOT_FOUND / AUDITORIUM_NOT_FOUND"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "SHOWTIME_CONFLICT / SHOWTIME_CANNOT_BE_MODIFIED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "Phim/phòng inactive, thiếu ghế, Sweetbox hoặc giá không hợp lệ")})
public class ShowtimeController {
    private final ShowtimeService showtimes;
    public ShowtimeController(ShowtimeService showtimes) { this.showtimes = showtimes; }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Lọc lịch theo phim, phòng, ngày bắt đầu tại Việt Nam; phân trang 0-based, size 1–100")
    public ApiResponse<PageResponse<ShowtimeResponse>> list(
            @RequestParam(required = false) String movieId, @RequestParam(required = false) String auditoriumId,
            @RequestParam(required = false) String date, @RequestParam(defaultValue = "0") String page,
            @RequestParam(defaultValue = "20") String size) {
        return new ApiResponse<>(showtimes.list(movieId, auditoriumId, date, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Chi tiết suất chiếu, thời gian trả về với offset Việt Nam")
    public ApiResponse<ShowtimeResponse> get(@PathVariable String id) { return new ApiResponse<>(showtimes.get(id(id))); }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MANAGER')")
    @Operation(summary = "Tạo suất và toàn bộ ghế AVAILABLE trong một transaction; từ chối lịch trùng (409)")
    public ApiResponse<ShowtimeResponse> create(@Valid @RequestBody CreateShowtimeRequest request) {
        return new ApiResponse<>(showtimes.create(request));
    }

    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('MANAGER')")
    @Operation(summary = "Thay movieId/startTime; giữ nguyên phòng, giá và mapping ghế; từ chối khi có vé hoặc ghế bận (409)")
    public ApiResponse<ShowtimeResponse> update(@PathVariable String id, @Valid @RequestBody UpdateShowtimeRequest request) {
        return new ApiResponse<>(showtimes.update(id(id), request));
    }

    // Keep invalid path values in the shared ApiException envelope, without changing Foundation's handler.
    private Long id(String value) {
        try { long id = Long.parseLong(value); if (id > 0) return id; } catch (NumberFormatException ignored) { }
        throw new ApiException(400, "VALIDATION_ERROR", "id phải là số nguyên dương");
    }
}
