package com.cinema.cms.dto;

import com.cinema.cms.entity.Auditorium;
import com.cinema.cms.entity.AuditoriumType;
import io.swagger.v3.oas.annotations.media.Schema;

public record AuditoriumResponse(Integer id, String name, int totalSeats, long seatCount,
                                 @Schema(allowableValues = {"STANDARD_2D", "3D", "IMAX"}) AuditoriumType type, boolean active) {
    public static AuditoriumResponse from(Auditorium a, long seatCount) {
        return new AuditoriumResponse(a.getId(), a.getName(), a.getTotalSeats(), seatCount, a.getType(), a.isActive());
    }
}