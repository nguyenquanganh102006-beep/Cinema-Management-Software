package com.cinema.cms.service;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.Auditorium;
import com.cinema.cms.repository.AuditoriumRepository;
import com.cinema.cms.repository.SeatRepository;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditoriumService {
    private final AuditoriumRepository auditoriums;
    private final SeatRepository seats;

    public AuditoriumService(AuditoriumRepository auditoriums, SeatRepository seats) { this.auditoriums = auditoriums; this.seats = seats; }

    @Transactional(readOnly = true)
    public List<AuditoriumResponse> list(Boolean active) {
        Sort sort = Sort.by("name");
        List<Auditorium> rooms = active == null ? auditoriums.findAll(sort) : auditoriums.findByActive(active, sort);
        Map<Integer, Long> counts = rooms.isEmpty() ? Map.of() : seats.countByAuditoriumIds(rooms.stream().map(Auditorium::getId).toList())
                .stream().collect(Collectors.toMap(SeatRepository.AuditoriumSeatCount::getAuditoriumId, SeatRepository.AuditoriumSeatCount::getSeatCount));
        return rooms.stream().map(a -> AuditoriumResponse.from(a, counts.getOrDefault(a.getId(), 0L))).toList();
    }

    @Transactional(readOnly = true)
    public AuditoriumResponse get(Integer id) { return toResponse(find(id)); }

    @Transactional
    public AuditoriumResponse create(AuditoriumRequest r) {
        if (auditoriums.existsByNameLower(r.name())) throw nameTaken();
        try {
            return toResponse(auditoriums.saveAndFlush(new Auditorium(r.name(), r.totalSeats(), r.type())));
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Transactional
    public AuditoriumResponse update(Integer id, AuditoriumRequest r) {
        Auditorium room = auditoriums.findByIdForUpdate(id).orElseThrow(AuditoriumService::notFound);
        if (auditoriums.existsByNameLowerAndIdNot(r.name(), id)) throw nameTaken();
        long seatCount = seats.countByAuditoriumId(id);
        if (r.totalSeats() < seatCount) {
            throw new ApiException(409, "AUDITORIUM_CAPACITY_BELOW_SEATS", "Sức chứa không được nhỏ hơn số ghế hiện có (" + seatCount + ")");
        }
        room.update(r.name(), r.totalSeats(), r.type());
        try {
            return AuditoriumResponse.from(auditoriums.saveAndFlush(room), seatCount);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Transactional
    public AuditoriumResponse changeStatus(Integer id, AuditoriumStatusRequest r) {
        Auditorium room = find(id);
        room.changeActive(r.active());
        return toResponse(room);
    }

    private Auditorium find(Integer id) { return auditoriums.findById(id).orElseThrow(AuditoriumService::notFound); }
    private AuditoriumResponse toResponse(Auditorium a) { return AuditoriumResponse.from(a, seats.countByAuditoriumId(a.getId())); }
    static ApiException notFound() { return new ApiException(404, "AUDITORIUM_NOT_FOUND", "Không tìm thấy phòng chiếu"); }
    private static RuntimeException translate(DataIntegrityViolationException e) {
        return ConstraintViolations.violates(e, "uq_auditoriums_name_ci", "auditoriums_name_key") ? nameTaken() : e;
    }
    private static ApiException nameTaken() { return new ApiException(409, "AUDITORIUM_NAME_EXISTS", "Tên phòng chiếu đã tồn tại"); }
}