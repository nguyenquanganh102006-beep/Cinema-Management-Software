package com.cinema.cms.repository;

import com.cinema.cms.dto.ReportResponses.OccupancyLine;
import com.cinema.cms.dto.ReportResponses.PaymentException;
import com.cinema.cms.dto.ReportResponses.TimeSeriesPoint;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReportingRepository {
    public record Sales(Long movieId,String title,String channel,BigDecimal tickets,BigDecimal concessions,BigDecimal refunds,long activeCount,long grossCount) {}
    private final JdbcTemplate jdbc;
    public ReportingRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    // Aggregate independent one-to-many tables BEFORE joining to avoid multiplying monetary amounts.
    public List<Sales> sales(Instant start,Instant end,Instant asOf) {
        return jdbc.query("""
            with cohort as (
                select b.booking_id,b.channel,s.movie_id from bookings b
                join payments p on p.booking_id=b.booking_id
                join showtimes s on s.showtime_id=b.showtime_id
                where b.status='PAID' and p.status='PAID' and p.paid_at>=? and p.paid_at<? and p.paid_at<=?
            ), ticket_totals as (
                select t.booking_id,sum(t.price_snapshot) amount,count(*) gross_count,
                    count(*) filter(where t.status in ('VALID','USED')) active_count
                from tickets t join cohort c on c.booking_id=t.booking_id group by t.booking_id
            ), food_totals as (
                select f.booking_id,sum(f.quantity*f.unit_price_snapshot) amount
                from booking_concessions f join cohort c on c.booking_id=f.booking_id group by f.booking_id
            ), refund_totals as (
                select r.booking_id,sum(r.refund_amount) amount from refund_records r
                join cohort c on c.booking_id=r.booking_id where r.created_at<=? group by r.booking_id
            )
            select c.movie_id,m.title,c.channel::text channel,
                coalesce(sum(t.amount),0) tickets,coalesce(sum(f.amount),0) concessions,
                coalesce(sum(r.amount),0) refunds,coalesce(sum(t.active_count),0) active_count,
                coalesce(sum(t.gross_count),0) gross_count
            from cohort c join movies m on m.movie_id=c.movie_id
            left join ticket_totals t on t.booking_id=c.booking_id
            left join food_totals f on f.booking_id=c.booking_id
            left join refund_totals r on r.booking_id=c.booking_id
            group by c.movie_id,m.title,c.channel
            """,(r,n) -> new Sales(r.getLong("movie_id"),r.getString("title"),r.getString("channel"),
                r.getBigDecimal("tickets"),r.getBigDecimal("concessions"),r.getBigDecimal("refunds"),r.getLong("active_count"),r.getLong("gross_count")),
            Timestamp.from(start),Timestamp.from(end),Timestamp.from(asOf),Timestamp.from(asOf));
    }
    public List<TimeSeriesPoint> timeSeries(LocalDate from,LocalDate to,String interval,String timezone,
            Instant start,Instant end) {
        String unit=switch (interval) {
            case "HOUR" -> "hour";
            case "DAY" -> "day";
            case "WEEK" -> "week";
            case "MONTH" -> "month";
            default -> throw new IllegalArgumentException("Unsupported time-series interval");
        };
        String sql=String.format(Locale.ROOT,"""
            with buckets as (
                select generate_series(date_trunc('%s', ?::timestamp),date_trunc('%s', ?::timestamp),interval '1 %s') bucket
            ), paid as (
                select p.payment_id,p.booking_id,p.amount,date_trunc('%s',p.paid_at at time zone ?) bucket
                from payments p join bookings b on b.booking_id=p.booking_id
                where p.status='PAID' and b.status='PAID' and p.paid_at>=? and p.paid_at<?
            ), paid_totals as (
                select bucket,sum(amount) gross,count(*) transactions from paid group by bucket
            ), ticket_totals as (
                select p.bucket,count(t.ticket_id) ticket_count from paid p
                left join tickets t on t.booking_id=p.booking_id group by p.bucket
            ), refunds as (
                select date_trunc('%s',r.created_at at time zone ?) bucket,
                    sum(r.refund_amount) amount,count(*) refunds
                from refund_records r where r.created_at>=? and r.created_at<? group by 1
            )
            select buckets.bucket::timestamp bucket,
                coalesce(p.gross,0) gross_revenue,coalesce(r.amount,0) refund_amount,
                coalesce(p.gross,0)-coalesce(r.amount,0) net_revenue,
                coalesce(p.transactions,0) transactions,coalesce(t.ticket_count,0) ticket_count,
                coalesce(r.refunds,0) refunds
            from buckets left join paid_totals p on p.bucket=buckets.bucket
                left join ticket_totals t on t.bucket=buckets.bucket
                left join refunds r on r.bucket=buckets.bucket
            order by buckets.bucket
            """,unit,unit,unit,unit,unit);
        return jdbc.query(sql,(r,n) -> new TimeSeriesPoint(
                r.getTimestamp("bucket").toLocalDateTime(),r.getBigDecimal("gross_revenue"),
                r.getBigDecimal("refund_amount"),r.getBigDecimal("net_revenue"),r.getLong("transactions"),
                r.getLong("ticket_count"),r.getLong("refunds")),
            Timestamp.valueOf(from.atStartOfDay()),Timestamp.valueOf(to.atTime(23,0)),timezone,
            Timestamp.from(start),Timestamp.from(end),timezone,Timestamp.from(start),Timestamp.from(end));
    }
    public BigDecimal exceptionPaid(Instant start,Instant end,Instant asOf) {
        return jdbc.queryForObject("""
            select coalesce(sum(p.amount),0) from payments p join bookings b on b.booking_id=p.booking_id
            where p.status='PAID' and b.status='EXCEPTION_PENDING_DECISION' and p.paid_at>=? and p.paid_at<? and p.paid_at<=?
            """,BigDecimal.class,Timestamp.from(start),Timestamp.from(end),Timestamp.from(asOf));
    }
    public List<PaymentException> paymentExceptions(int limit) {
        return jdbc.query("""
            select b.booking_id,b.booking_code,s.movie_title_snapshot,s.auditorium_name_snapshot,
                s.start_time,p.amount,p.paid_at,
                coalesce(exception.reason_code,'MANUAL_REVIEW_REQUIRED') reason_code
            from bookings b
            join payments p on p.booking_id=b.booking_id
            join showtimes s on s.showtime_id=b.showtime_id
            left join lateral (
                select substring(ae.details from 'reason=([A-Z0-9_]+)') reason_code
                from audit_events ae
                where ae.action='VNPAY_IPN' and ae.outcome='EXCEPTION_PENDING_DECISION'
                    and ae.subject_type='PAYMENT' and ae.subject_id=p.payment_id::text
                order by ae.occurred_at desc,ae.audit_event_id desc
                limit 1
            ) exception on true
            where b.status='EXCEPTION_PENDING_DECISION' and p.status='PAID'
            order by p.paid_at,b.booking_id
            limit ?
            """,(r,n) -> new PaymentException(r.getLong("booking_id"),r.getString("booking_code"),
                r.getString("movie_title_snapshot"),r.getString("auditorium_name_snapshot"),
                r.getTimestamp("start_time").toInstant(),r.getBigDecimal("amount"),
                r.getTimestamp("paid_at").toInstant(),r.getString("reason_code")),limit);
    }
    public List<OccupancyLine> occupancy(Instant start,Instant end) {
        return jdbc.query("""
            select s.showtime_id,s.movie_id,s.start_time,count(ss.showtime_seat_id) total,
                count(ss.showtime_seat_id) filter(where ss.status='BOOKED') sold
            from showtimes s left join showtime_seats ss on ss.showtime_id=s.showtime_id
            where s.start_time>=? and s.start_time<? group by s.showtime_id,s.movie_id,s.start_time
            order by s.start_time,s.showtime_id
            """,(r,n) -> new OccupancyLine(r.getLong("showtime_id"),r.getLong("movie_id"),r.getTimestamp("start_time").toInstant(),
                r.getLong("sold"),r.getLong("total"),percentage(r.getLong("sold"),r.getLong("total"))),Timestamp.from(start),Timestamp.from(end));
    }
    public static BigDecimal percentage(long sold,long total) {
        return total==0?BigDecimal.ZERO.setScale(2):BigDecimal.valueOf(sold).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(total),2,java.math.RoundingMode.HALF_UP);
    }
}
