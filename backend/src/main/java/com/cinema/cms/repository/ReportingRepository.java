package com.cinema.cms.repository;

import com.cinema.cms.dto.ReportResponses.OccupancyLine;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
    public BigDecimal exceptionPaid(Instant start,Instant end,Instant asOf) {
        return jdbc.queryForObject("""
            select coalesce(sum(p.amount),0) from payments p join bookings b on b.booking_id=p.booking_id
            where p.status='PAID' and b.status='EXCEPTION_PENDING_DECISION' and p.paid_at>=? and p.paid_at<? and p.paid_at<=?
            """,BigDecimal.class,Timestamp.from(start),Timestamp.from(end),Timestamp.from(asOf));
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
