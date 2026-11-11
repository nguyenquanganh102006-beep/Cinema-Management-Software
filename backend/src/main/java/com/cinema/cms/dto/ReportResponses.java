package com.cinema.cms.dto;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/** Revenue reports use payment-date cohorts, adjusted for refunds recorded by asOf. */
public final class ReportResponses {
    private ReportResponses() {}
    public record Period(LocalDate from,LocalDate to,String timezone,String basis,Instant asOf) {}
    public record Revenue(Period period,BigDecimal totalRevenue,BigDecimal refundAmount,BigDecimal netRevenue,BigDecimal exceptionPaidAmount) {}
    public record Breakdown(Period period,BigDecimal ticketRevenue,BigDecimal concessionRevenue,BigDecimal ticketRefundAmount,BigDecimal netTicketRevenue,BigDecimal netRevenue) {}
    public record Channel(String channel,BigDecimal grossRevenue,BigDecimal refundAmount,BigDecimal netRevenue) {}
    public record ByChannel(Period period,BigDecimal posRevenue,BigDecimal onlineRevenue,List<Channel> channels) {}
    public record TicketCount(Period period,long ticketCount,long grossTicketCount,long cancelledTicketCount) {}
    public record OccupancyLine(Long showtimeId,Long movieId,Instant startsAt,long seatsSold,long totalSeats,BigDecimal occupancyRate) {}
    public record Occupancy(Period period,long seatsSold,long totalSeats,BigDecimal occupancyRate,List<OccupancyLine> showtimes) {}
    public record MovieLine(Long movieId,String title,long ticketCount,BigDecimal totalRevenue,BigDecimal refundAmount,BigDecimal revenue) {}
    public record Ranking(Period period,String direction,List<MovieLine> movies) {}
    public record InventoryAlert(Integer itemId,String itemName,int stockQuantity,int lowStockThreshold,String status) {}
}
