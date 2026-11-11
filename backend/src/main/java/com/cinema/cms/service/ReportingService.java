package com.cinema.cms.service;

import com.cinema.cms.dto.ReportResponses.*;
import com.cinema.cms.dto.ReportResponses.Period;
import com.cinema.cms.repository.ReportingRepository;
import com.cinema.cms.repository.ReportingRepository.Sales;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class ReportingService {
    private final ReportingRepository reports;
    private final InventoryService inventory;
    private final Clock clock;
    public ReportingService(ReportingRepository reports,InventoryService inventory,Clock clock) { this.reports=reports;this.inventory=inventory;this.clock=clock; }
    public Revenue revenue(String from,String to) {
        Period p=period(from,to,"PAYMENT_COHORT"); List<Sales> rows=sales(p);
        BigDecimal gross=sum(rows,r -> r.tickets().add(r.concessions())), refunds=sum(rows,Sales::refunds);
        return new Revenue(p,gross,refunds,gross.subtract(refunds),reports.exceptionPaid(start(p),end(p),p.asOf()));
    }
    public Breakdown breakdown(String from,String to) {
        Period p=period(from,to,"PAYMENT_COHORT");List<Sales> rows=sales(p);
        BigDecimal ticket=sum(rows,Sales::tickets),food=sum(rows,Sales::concessions),refund=sum(rows,Sales::refunds);
        return new Breakdown(p,ticket,food,refund,ticket.subtract(refund),ticket.add(food).subtract(refund));
    }
    public ByChannel channels(String from,String to) {
        Period p=period(from,to,"PAYMENT_COHORT");List<Sales> rows=sales(p);
        List<Channel> channels=List.of(channel("POS",rows),channel("ONLINE",rows));
        return new ByChannel(p,channels.get(0).netRevenue(),channels.get(1).netRevenue(),channels);
    }
    public TicketCount ticketCount(String from,String to) {
        Period p=period(from,to,"PAYMENT_COHORT");List<Sales> rows=sales(p);
        long active=rows.stream().mapToLong(Sales::activeCount).sum(),gross=rows.stream().mapToLong(Sales::grossCount).sum();
        return new TicketCount(p,active,gross,gross-active);
    }
    public Occupancy occupancy(String from,String to) {
        Period p=period(from,to,"SHOWTIME_START");List<OccupancyLine> lines=reports.occupancy(start(p),end(p));
        long sold=lines.stream().mapToLong(OccupancyLine::seatsSold).sum(),total=lines.stream().mapToLong(OccupancyLine::totalSeats).sum();
        return new Occupancy(p,sold,total,ReportingRepository.percentage(sold,total),lines);
    }
    public List<InventoryAlert> alerts() {
        return inventory.alerts().stream().map(i -> new InventoryAlert(i.itemId(),i.name(),i.stockQuantity(),i.lowStockThreshold(),i.status().name())).toList();
    }
    public Ranking ranking(String from,String to,String direction,int limit) {
        if (!("ASC".equalsIgnoreCase(direction)||"DESC".equalsIgnoreCase(direction))||limit<1||limit>100) throw invalid();
        Period p=period(from,to,"PAYMENT_COHORT");
        Map<Long,List<Sales>> grouped=sales(p).stream().collect(Collectors.groupingBy(Sales::movieId));
        List<MovieLine> lines=new ArrayList<>();
        grouped.forEach((id,rows) -> {
            BigDecimal gross=sum(rows,r -> r.tickets().add(r.concessions())),refund=sum(rows,Sales::refunds);
            lines.add(new MovieLine(id,rows.getFirst().title(),rows.stream().mapToLong(Sales::activeCount).sum(),gross,refund,gross.subtract(refund)));
        });
        Comparator<MovieLine> order=Comparator.comparing(MovieLine::revenue);
        if ("DESC".equalsIgnoreCase(direction)) order=order.reversed();
        return new Ranking(p,direction.toUpperCase(Locale.ROOT),lines.stream().sorted(order.thenComparing(MovieLine::movieId)).limit(limit).toList());
    }
    private static Channel channel(String channel,List<Sales> rows) {
        List<Sales> selected=rows.stream().filter(r -> channel.equals(r.channel())).toList();
        BigDecimal gross=sum(selected,r -> r.tickets().add(r.concessions())),refund=sum(selected,Sales::refunds);
        return new Channel(channel,gross,refund,gross.subtract(refund));
    }
    private List<Sales> sales(Period p) { return reports.sales(start(p),end(p),p.asOf()); }
    private static BigDecimal sum(List<Sales> rows,Function<Sales,BigDecimal> value) { return rows.stream().map(value).reduce(BigDecimal.ZERO,BigDecimal::add); }
    private Period period(String from,String to,String basis) {
        try {
            if (from==null||to==null) throw invalid();
            LocalDate a=LocalDate.parse(from),b=LocalDate.parse(to);
            if (a.isAfter(b)||a.getYear()<1||b.getYear()>9998) throw invalid();
            return new Period(a,b,ShowtimeTimeService.BUSINESS_ZONE.getId(),basis,clock.instant());
        } catch (DateTimeParseException e) { throw invalid(); }
    }
    private static Instant start(Period p) { return p.from().atStartOfDay(ShowtimeTimeService.BUSINESS_ZONE).toInstant(); }
    private static Instant end(Period p) { return p.to().plusDays(1).atStartOfDay(ShowtimeTimeService.BUSINESS_ZONE).toInstant(); }
    private static ApiException invalid() { return new ApiException(400,"VALIDATION_ERROR","Use valid from/to dates (yyyy-MM-dd), from <= to, direction ASC/DESC, limit 1..100"); }
}
