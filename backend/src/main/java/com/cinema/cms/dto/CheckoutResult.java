package com.cinema.cms.dto;

import com.cinema.cms.entity.Booking;
import com.cinema.cms.entity.Payment;
import com.cinema.cms.entity.Ticket;
import java.util.List;

/** Internal result of a successful checkout. Controllers map this to API DTOs. */
public record CheckoutResult(Booking booking, Payment payment, List<Ticket> tickets) {}
