package com.cinema.cms.controller;
import com.cinema.cms.dto.*;
import com.cinema.cms.dto.ReportResponses.*;
import com.cinema.cms.service.ReportingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/api/v1/reports",produces="application/json")
@PreAuthorize("hasRole('MANAGER')")
@SecurityRequirement(name="bearerAuth")
@io.swagger.v3.oas.annotations.responses.ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400", description="VALIDATION_ERROR", content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=ApiErrorResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="AUTH_TOKEN_INVALID / AUTH_TOKEN_EXPIRED", content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=ApiErrorResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403", description="ACCESS_DENIED / manager approval errors", content=@io.swagger.v3.oas.annotations.media.Content(schema=@io.swagger.v3.oas.annotations.media.Schema(implementation=ApiErrorResponse.class)))
})
public class ReportingController {
    private final ReportingService reports;
    public ReportingController(ReportingService reports) { this.reports=reports; }
    @GetMapping("/revenue")
    @Operation(summary="Gross, refunds and net revenue for a payment-date cohort",description="MANAGER. Required inclusive from/to YYYY-MM-DD in Asia/Ho_Chi_Minh. Refunds through asOf; cancelled ticket prices stay in gross. Exception payments are separate.")
    public ApiResponse<Revenue> revenue(@RequestParam String from,@RequestParam String to) { return new ApiResponse<>(reports.revenue(from,to)); }
    @GetMapping("/revenue-breakdown")
    @Operation(summary="Ticket and F&B gross revenue, ticket refunds and net revenue",description="MANAGER. Required from/to dates; payment cohort, not cash-flow by refund date.")
    public ApiResponse<Breakdown> breakdown(@RequestParam String from,@RequestParam String to) { return new ApiResponse<>(reports.breakdown(from,to)); }
    @GetMapping("/revenue-by-channel")
    @Operation(summary="Net POS and ONLINE revenue with gross/refund breakdown",description="MANAGER. Channel comes from Booking, including ONLINE tickets refunded in cash at POS.")
    public ApiResponse<ByChannel> channels(@RequestParam String from,@RequestParam String to) { return new ApiResponse<>(reports.channels(from,to)); }
    @GetMapping("/ticket-count")
    @Operation(summary="Current VALID/USED, gross and cancelled ticket counts",description="MANAGER. Payment-date cohort from/to; current state, not an immutable historical snapshot.")
    public ApiResponse<TicketCount> count(@RequestParam String from,@RequestParam String to) { return new ApiResponse<>(reports.ticketCount(from,to)); }
    @GetMapping("/occupancy")
    @Operation(summary="BOOKED seats divided by showtime seat inventory",description="MANAGER. Range is showtime start dates. Total percentage is weighted by seat count. HOLDING seats are excluded.")
    public ApiResponse<Occupancy> occupancy(@RequestParam String from,@RequestParam String to) { return new ApiResponse<>(reports.occupancy(from,to)); }
    @GetMapping("/inventory-alerts")
    @Operation(summary="Current F&B stock at or below threshold",description="MANAGER. No date range; zero stock is OUT_OF_STOCK.")
    public ApiResponse<List<InventoryAlert>> alerts() { return new ApiResponse<>(reports.alerts()); }
    @GetMapping("/movies/ranking")
    @Operation(summary="Top or bottom movies by net Ticket plus F&B revenue",description="MANAGER. Payment cohort. Only movies with completed paid bookings in range; ASC/DESC, default limit 5, max 100; movie ID breaks ties.")
    public ApiResponse<Ranking> ranking(@RequestParam String from,@RequestParam String to,@RequestParam(defaultValue="DESC") String direction,@RequestParam(defaultValue="5") int limit) {
        return new ApiResponse<>(reports.ranking(from,to,direction,limit));
    }
}
