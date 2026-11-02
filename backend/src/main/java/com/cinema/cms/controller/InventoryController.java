package com.cinema.cms.controller;

import com.cinema.cms.dto.*;
import com.cinema.cms.service.InventoryService;
import com.cinema.cms.service.SessionPrincipal;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/inventory", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('MANAGER')")
@Tag(name = "Inventory", description = "Tồn kho và nhập hàng. Chỉ Manager, riêng cảnh báo tồn kho mở cho cả Staff.")
@SecurityRequirement(name = "bearerAuth")
public class InventoryController {
    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) { this.inventory = inventory; }

    @GetMapping
    @Operation(summary = "Tồn kho của các món lẻ, tìm theo tên, lọc theo tình trạng, phân trang")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Trang tồn kho"), @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: status, page hoặc size không hợp lệ"),
            @ApiResponse(responseCode = "401", description = "Chưa đăng nhập"), @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")})
    public com.cinema.cms.dto.ApiResponse<PageResponse<InventoryResponse>> list(@RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
                                                                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return new com.cinema.cms.dto.ApiResponse<>(inventory.list(keyword, status, page, size));
    }

    @GetMapping("/alerts")
    @PreAuthorize("hasAnyRole('MANAGER','STAFF')")
    @Operation(summary = "Món đang bán có tồn kho bằng hoặc thấp hơn ngưỡng cảnh báo. Manager và Staff")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Danh sách cảnh báo, ít hàng nhất trước"), @ApiResponse(responseCode = "401", description = "Chưa đăng nhập"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED: khách hàng không xem được")})
    public com.cinema.cms.dto.ApiResponse<List<InventoryResponse>> alerts() { return new com.cinema.cms.dto.ApiResponse<>(inventory.alerts()); }

    @PostMapping(value = "/{itemId}/restocks", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Nhập hàng cho một món lẻ. Ghi tồn trước và sau, người nhập lấy từ token")
    @ApiResponses({@ApiResponse(responseCode = "201", description = "Lần nhập vừa ghi"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR hoặc INVALID_STOCK_QUANTITY: số lượng không dương, không phải số nguyên, hoặc làm tồn kho vượt giới hạn"),
            @ApiResponse(responseCode = "401", description = "Chưa đăng nhập"), @ApiResponse(responseCode = "403", description = "ACCESS_DENIED"),
            @ApiResponse(responseCode = "404", description = "CONCESSION_ITEM_NOT_FOUND, kể cả khi id là combo")})
    public com.cinema.cms.dto.ApiResponse<InventoryMovementResponse> restock(@PathVariable Integer itemId, @Valid @RequestBody RestockRequest request, @AuthenticationPrincipal SessionPrincipal user) {
        return new com.cinema.cms.dto.ApiResponse<>(inventory.restock(itemId, request, user.userId()));
    }

    @GetMapping("/restocks")
    @Operation(summary = "Lịch sử nhập kho toàn hệ thống, mới nhất trước. Lọc theo món, người nhập và khoảng thời gian (ISO-8601)")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Trang lịch sử nhập kho"), @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: from sau to, sai định dạng, page hoặc size sai (size tối đa 100)"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED")})
    public com.cinema.cms.dto.ApiResponse<PageResponse<InventoryMovementResponse>> restocks(
            @RequestParam(required = false) String itemId, @RequestParam(required = false) String managerId, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return new com.cinema.cms.dto.ApiResponse<>(inventory.restocks(itemId, managerId, from, to, page, size));
    }

    @GetMapping("/{itemId}/history")
    @Operation(summary = "Lịch sử biến động của một món, mới nhất trước")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Trang lịch sử"), @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: page hoặc size sai"),
            @ApiResponse(responseCode = "403", description = "ACCESS_DENIED"), @ApiResponse(responseCode = "404", description = "CONCESSION_ITEM_NOT_FOUND")})
    public com.cinema.cms.dto.ApiResponse<PageResponse<InventoryMovementResponse>> history(@PathVariable Integer itemId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return new com.cinema.cms.dto.ApiResponse<>(inventory.history(itemId, page, size));
    }
}