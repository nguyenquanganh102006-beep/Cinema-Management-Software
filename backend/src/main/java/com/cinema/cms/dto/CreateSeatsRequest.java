package com.cinema.cms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateSeatsRequest(@NotEmpty @Size(max = CreateSeatsRequest.MAX_SEATS) List<@Valid SeatRequest> seats) {
    public static final int MAX_SEATS = 500;
}