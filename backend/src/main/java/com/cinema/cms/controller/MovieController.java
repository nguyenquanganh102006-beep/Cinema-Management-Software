package com.cinema.cms.controller;

import com.cinema.cms.dto.*;
import com.cinema.cms.service.MovieService;
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
@RequestMapping(value = "/api/v1/movies", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Movies", description = "Quản lý phim. Đọc: mọi tài khoản đã đăng nhập (chỉ Manager thấy phim ngừng chiếu). Ghi: chỉ Manager.")
@SecurityRequirement(name = "bearerAuth")
public class MovieController {
    private final MovieService movies;

    public MovieController(MovieService movies) { this.movies = movies; }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Danh sách phim, có tìm theo tên, lọc trạng thái và phân trang")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Trang phim"),
            @ApiResponse(responseCode = "400", description = "page hoặc size không hợp lệ (size tối đa 100)"),
            @ApiResponse(responseCode = "401", description = "Chưa đăng nhập hoặc token không hợp lệ")})
    public com.cinema.cms.dto.ApiResponse<PageResponse<MovieResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal SessionPrincipal user) {
        return new com.cinema.cms.dto.ApiResponse<>(movies.list(keyword, active, page, size, user.role()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Chi tiết một phim")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Thông tin phim"),
            @ApiResponse(responseCode = "401", description = "Chưa đăng nhập"),
            @ApiResponse(responseCode = "404", description = "MOVIE_NOT_FOUND, kể cả khi phim ngừng chiếu và người xem không phải Manager")})
    public com.cinema.cms.dto.ApiResponse<MovieResponse> get(@PathVariable Long id, @AuthenticationPrincipal SessionPrincipal user) {
        return new com.cinema.cms.dto.ApiResponse<>(movies.get(id, user.role()));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('MANAGER')")
    @Operation(summary = "Tạo phim mới (mặc định đang hoạt động)")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Đã tạo phim"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: thiếu hoặc sai trường, có trường lạ"),
            @ApiResponse(responseCode = "401", description = "Chưa đăng nhập"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED: không phải Manager")})
    public com.cinema.cms.dto.ApiResponse<MovieResponse> create(@Valid @RequestBody MovieRequest request) {
        return new com.cinema.cms.dto.ApiResponse<>(movies.create(request));
    }

    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('MANAGER')")
    @Operation(summary = "Cập nhật thông tin phim (thay toàn bộ các trường, không đổi trạng thái)")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Phim sau khi cập nhật"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
            @ApiResponse(responseCode = "401", description = "Chưa đăng nhập"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED: không phải Manager"),
            @ApiResponse(responseCode = "404", description = "MOVIE_NOT_FOUND")})
    public com.cinema.cms.dto.ApiResponse<MovieResponse> update(@PathVariable Long id, @Valid @RequestBody MovieRequest request) {
        return new com.cinema.cms.dto.ApiResponse<>(movies.update(id, request));
    }

    @PatchMapping(value = "/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('MANAGER')")
    @Operation(summary = "Bật hoặc ngừng chiếu một phim")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Phim sau khi đổi trạng thái"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: thiếu trường active"),
            @ApiResponse(responseCode = "401", description = "Chưa đăng nhập"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED: không phải Manager"),
            @ApiResponse(responseCode = "404", description = "MOVIE_NOT_FOUND")})
    public com.cinema.cms.dto.ApiResponse<MovieResponse> changeStatus(@PathVariable Long id, @Valid @RequestBody MovieStatusRequest request) {
        return new com.cinema.cms.dto.ApiResponse<>(movies.changeStatus(id, request));
    }
}