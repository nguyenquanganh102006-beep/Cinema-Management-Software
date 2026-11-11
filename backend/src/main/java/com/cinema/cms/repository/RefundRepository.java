package com.cinema.cms.repository;

import com.cinema.cms.entity.ManagerOverride;
import com.cinema.cms.dto.RefundResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RefundRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public RefundRepository(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    private String json(List<Long> ids) {
        try { return mapper.writeValueAsString(ids); } catch (Exception e) { throw new IllegalStateException("Cannot serialize ticket IDs"); }
    }
    private List<Long> ids(String json) {
        try { return mapper.readValue(json, new TypeReference<List<Long>>() {}); }
        catch (Exception e) { throw new IllegalStateException("Invalid persisted ticket IDs"); }
    }
    public void approve(ManagerOverride a) {
        jdbc.update("""
            insert into manager_overrides(token_hash,staff_id,staff_token_id,manager_id,manager_security_version,
                booking_id,ticket_ids_json,reason,approved_amount,cash_confirmed_at,expires_at)
            values (?,?,?,?,?,?,?::jsonb,?,?,?,?)
            """, a.tokenHash(),a.staffId(),a.staffTokenId(),a.managerId(),a.managerSecurityVersion(),
            a.bookingId(),json(a.ticketIds()),a.reason(),a.approvedAmount(),Timestamp.from(a.cashConfirmedAt()),Timestamp.from(a.expiresAt()));
    }
    public Optional<ManagerOverride> lockApproval(String hash) {
        return jdbc.query("select * from manager_overrides where token_hash=? for update", (r,n) ->
            new ManagerOverride(r.getString("token_hash"),r.getLong("staff_id"),r.getString("staff_token_id"),
            r.getLong("manager_id"),r.getInt("manager_security_version"),r.getLong("booking_id"),
            ids(r.getString("ticket_ids_json")),r.getString("reason"),r.getBigDecimal("approved_amount"),
            r.getTimestamp("cash_confirmed_at").toInstant(),r.getTimestamp("expires_at").toInstant(),
            r.getObject("refund_id",Long.class)),hash).stream().findFirst();
    }
    public long record(ManagerOverride a, Instant now) {
        return jdbc.queryForObject("""
            insert into refund_records(booking_id,ticket_ids_json,manager_id,refund_amount,reason,cash_confirmed_at,created_at,staff_id)
            values (?,?::jsonb,?,?,?,?,?,?) returning refund_id
            """,Long.class,a.bookingId(),json(a.ticketIds()),a.managerId(),a.approvedAmount(),a.reason(),
            Timestamp.from(a.cashConfirmedAt()),Timestamp.from(now),a.staffId());
    }
    public void link(long refundId, long ticketId, BigDecimal price) {
        jdbc.update("insert into refund_ticket_links(refund_id,ticket_id,price_snapshot) values (?,?,?)",refundId,ticketId,price);
    }
    public void consume(String hash, long refundId, Instant now) {
        jdbc.update("update manager_overrides set refund_id=?,consumed_at=? where token_hash=?",refundId,Timestamp.from(now),hash);
    }
    public RefundResponse response(long refundId) {
        return jdbc.queryForObject("select * from refund_records where refund_id=?", (r,n) ->
            new RefundResponse(r.getLong("refund_id"),r.getLong("booking_id"),r.getBigDecimal("refund_amount"),
                ids(r.getString("ticket_ids_json")),"COMPLETED",r.getTimestamp("cash_confirmed_at").toInstant(),
                statuses(List.of(r.getLong("booking_id"))).getOrDefault(r.getLong("booking_id"),"NONE")),refundId);
    }
    public Map<Long,String> statuses(Collection<Long> bookings) {
        if (bookings.isEmpty()) return Map.of();
        String placeholders=String.join(",",Collections.nCopies(bookings.size(),"?"));
        Map<Long,String> result=new HashMap<>();
        jdbc.query("select booking_id,count(*) total,count(*) filter(where status='CANCELLED') cancelled from tickets where booking_id in ("+placeholders+") group by booking_id", r -> {
            long cancelled=r.getLong("cancelled");
            result.put(r.getLong("booking_id"),cancelled==0?"NONE":cancelled==r.getLong("total")?"FULL":"PARTIAL");
        },bookings.toArray());
        return result;
    }
}
