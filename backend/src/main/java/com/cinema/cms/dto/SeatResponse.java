package com.cinema.cms.dto;

import com.cinema.cms.entity.Seat;
import com.cinema.cms.entity.SeatType;

public record SeatResponse(Long id, Integer auditoriumId, String row, int number, SeatType type, Long pairSeatId) {
    public static SeatResponse from(Seat s) {
        return new SeatResponse(s.getId(), s.getAuditoriumId(), s.getSeatRow(), s.getSeatNumber(), s.getSeatType(), s.getPairSeatId());
    }
}