package com.cinema.cms.service;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.Auditorium;
import com.cinema.cms.entity.Seat;
import com.cinema.cms.entity.SeatType;
import com.cinema.cms.repository.AuditoriumRepository;
import com.cinema.cms.repository.SeatRepository;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SeatService {
    private final AuditoriumRepository auditoriums;
    private final SeatRepository seats;

    public SeatService(AuditoriumRepository auditoriums, SeatRepository seats) { this.auditoriums = auditoriums; this.seats = seats; }

    @Transactional(readOnly = true)
    public List<SeatResponse> list(Integer auditoriumId) {
        if (!auditoriums.existsById(auditoriumId)) throw AuditoriumService.notFound();
        return seats.findByAuditoriumIdOrderBySeatRowAscSeatNumberAsc(auditoriumId).stream().map(SeatResponse::from).toList();
    }

    @Transactional
    public List<SeatResponse> create(Integer auditoriumId, CreateSeatsRequest request) {
        Auditorium room = auditoriums.findByIdForUpdate(auditoriumId).orElseThrow(AuditoriumService::notFound);
        Set<String> requested = new HashSet<>();
        for (SeatRequest s : request.seats()) {
            if (!requested.add(key(s.row(), s.number()))) throw new ApiException(400, "VALIDATION_ERROR", "Ghế " + s.row() + s.number() + " bị lặp trong yêu cầu");
        }
        List<Seat> existing = seats.findByAuditoriumIdOrderBySeatRowAscSeatNumberAsc(auditoriumId);
        Set<String> taken = existing.stream().map(s -> key(s.getSeatRow(), s.getSeatNumber())).collect(Collectors.toSet());
        List<String> clashes = requested.stream().filter(taken::contains).sorted().limit(10).toList();
        if (!clashes.isEmpty()) throw new ApiException(409, "SEAT_POSITION_TAKEN", "Vị trí ghế đã có trong phòng: " + String.join(", ", clashes));
        if (existing.size() + request.seats().size() > room.getTotalSeats()) {
            throw new ApiException(409, "AUDITORIUM_CAPACITY_EXCEEDED", "Vượt sức chứa của phòng (" + room.getTotalSeats() + " ghế, hiện có " + existing.size() + ")");
        }
        List<Seat> created = request.seats().stream().map(s -> new Seat(auditoriumId, s.row(), s.number(), s.type())).toList();
        try {
            return seats.saveAllAndFlush(created).stream().map(SeatResponse::from)
                    .sorted(Comparator.comparing(SeatResponse::row).thenComparingInt(SeatResponse::number)).toList();
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Transactional
    public SeatResponse update(Long seatId, SeatRequest r) {
        Integer auditoriumId = seats.findAuditoriumIdBySeatId(seatId).orElseThrow(SeatService::seatNotFound);
        auditoriums.findByIdForUpdate(auditoriumId).orElseThrow(AuditoriumService::notFound);
        Seat seat = seats.findById(seatId).orElseThrow(SeatService::seatNotFound);
        boolean changed = !seat.getSeatRow().equals(r.row()) || seat.getSeatNumber() != r.number() || seat.getSeatType() != r.type();
        if (changed && (seat.getPairSeatId() != null || seats.existsByPairSeatId(seatId))) {
            throw new ApiException(409, "SEAT_IS_PAIRED", "Ghế đang ghép cặp Sweetbox, hãy gỡ cặp trước khi đổi vị trí hoặc loại ghế");
        }
        if (seats.existsByAuditoriumIdAndSeatRowAndSeatNumberAndIdNot(seat.getAuditoriumId(), r.row(), r.number(), seatId)) {
            throw new ApiException(409, "SEAT_POSITION_TAKEN", "Vị trí ghế đã có trong phòng: " + r.row() + r.number());
        }
        seat.update(r.row(), r.number(), r.type());
        try {
            return SeatResponse.from(seats.saveAndFlush(seat));
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    private static ApiException seatNotFound() { return new ApiException(404, "SEAT_NOT_FOUND", "Không tìm thấy ghế"); }
    private static RuntimeException translate(DataIntegrityViolationException e) {
        return ConstraintViolations.violates(e, "uq_auditorium_seat") ? new ApiException(409, "SEAT_POSITION_TAKEN", "Vị trí ghế vừa được tạo bởi thao tác khác") : e;
    }
    private static String key(String row, int number) { return row + number; }
}