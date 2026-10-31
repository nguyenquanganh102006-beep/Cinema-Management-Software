package com.cinema.cms.service;

import com.cinema.cms.dto.SeatResponse;
import com.cinema.cms.dto.SweetboxPairRequest;
import com.cinema.cms.dto.SweetboxPairResponse;
import com.cinema.cms.entity.Seat;
import com.cinema.cms.entity.SeatType;
import com.cinema.cms.repository.AuditoriumRepository;
import com.cinema.cms.repository.SeatRepository;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SweetboxService {
    private final AuditoriumRepository auditoriums;
    private final SeatRepository seats;

    public SweetboxService(AuditoriumRepository auditoriums, SeatRepository seats) { this.auditoriums = auditoriums; this.seats = seats; }

    @Transactional
    public SweetboxPairResponse pair(SweetboxPairRequest r) {
        if (r.seatId().equals(r.pairSeatId())) throw invalid("Hai ghế của một cặp phải khác nhau");
        Integer roomId = auditoriumOf(r.seatId());
        if (!roomId.equals(auditoriumOf(r.pairSeatId()))) throw invalid("Hai ghế của một cặp phải thuộc cùng một phòng");
        lockRoom(roomId);
        Seat first = load(r.seatId()), second = load(r.pairSeatId());
        if (first.getSeatType() != SeatType.SWEETBOX || second.getSeatType() != SeatType.SWEETBOX) throw invalid("Cả hai ghế phải thuộc loại SWEETBOX");
        if (isPaired(first) || isPaired(second)) throw new ApiException(409, "SWEETBOX_ALREADY_PAIRED", "Một trong hai ghế đã thuộc một cặp Sweetbox khác");
        first.assignPair(second.getId());
        second.assignPair(first.getId());
        return save(List.of(first, second));
    }

    @Transactional
    public SweetboxPairResponse unpair(Long seatId) {
        Integer roomId = auditoriumOf(seatId);
        lockRoom(roomId);
        Seat seat = load(seatId);
        Set<Seat> linked = new LinkedHashSet<>(List.of(seat));
        if (seat.getPairSeatId() != null) seats.findById(seat.getPairSeatId()).ifPresent(linked::add);
        seats.findByPairSeatId(seatId).ifPresent(linked::add);
        if (linked.size() == 1 && seat.getPairSeatId() == null) throw new ApiException(404, "SWEETBOX_PAIR_NOT_FOUND", "Ghế này chưa thuộc cặp Sweetbox nào");
        linked.forEach(Seat::clearPair);
        return save(new ArrayList<>(linked));
    }

    private SweetboxPairResponse save(List<Seat> changed) {
        try {
            List<SeatResponse> result = seats.saveAllAndFlush(changed).stream().map(SeatResponse::from)
                    .sorted(Comparator.comparing(SeatResponse::row).thenComparingInt(SeatResponse::number)).toList();
            return new SweetboxPairResponse(result);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    private boolean isPaired(Seat seat) { return seat.getPairSeatId() != null || seats.existsByPairSeatId(seat.getId()); }
    private Integer auditoriumOf(Long seatId) { return seats.findAuditoriumIdBySeatId(seatId).orElseThrow(SeatService::seatNotFound); }
    private Seat load(Long seatId) { return seats.findById(seatId).orElseThrow(SeatService::seatNotFound); }
    private void lockRoom(Integer roomId) { auditoriums.findByIdForUpdate(roomId).orElseThrow(AuditoriumService::notFound); }
    private static ApiException invalid(String message) { return new ApiException(400, "SWEETBOX_PAIR_INVALID", message); }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        if (ConstraintViolations.violates(e, "uq_seat_pair_target")) return new ApiException(409, "SWEETBOX_ALREADY_PAIRED", "Một trong hai ghế đã thuộc một cặp Sweetbox khác");
        if (ConstraintViolations.violates(e, "chk_seat_pair_self")) return invalid("Hai ghế của một cặp phải khác nhau");
        return e;
    }
}