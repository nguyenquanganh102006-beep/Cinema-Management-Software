package com.cinema.cms.controller;

import com.cinema.cms.dto.ApiResponse;
import com.cinema.cms.dto.AuditEventResponse;
import com.cinema.cms.dto.PageResponse;
import com.cinema.cms.service.AuditEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/audit-events", produces = "application/json")
@PreAuthorize("hasRole('MANAGER')")
@SecurityRequirement(name = "bearerAuth")
public class AuditEventController {
    private final AuditEventService auditEvents;

    public AuditEventController(AuditEventService auditEvents) {
        this.auditEvents = auditEvents;
    }

    @GetMapping
    @Operation(summary = "Read recent persistent audit events (Manager only)")
    public ApiResponse<PageResponse<AuditEventResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return new ApiResponse<>(auditEvents.list(page, size));
    }
}
