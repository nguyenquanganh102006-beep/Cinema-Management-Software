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
    private final AuditEventService auditEvents;

    public AuditoriumService(AuditoriumRepository auditoriums, SeatRepository seats, AuditEventService auditEvents) { this.auditoriums = auditoriums; this.seats = seats; this.auditEvents = auditEvents; }

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
    public AuditoriumResponse create(AuditoriumRequest r) { return create(r, null); }

    @Transactional
    public AuditoriumResponse create(AuditoriumRequest r, Long actorUserId) {
        if (auditoriums.existsByNameLower(r.name())) throw nameTaken();
        try {
            Auditorium saved = auditoriums.saveAndFlush(new Auditorium(r.name(), r.totalSeats(), r.type()));
            auditEvents.record(actorUserId, "AUDITORIUM_CREATED", "SUCCESS", "AUDITORIUM", saved.getId(),
                    "name=\"" + text(saved.getName()) + "\"; totalSeats=" + saved.getTotalSeats()
                            + "; type=" + saved.getType().value());
            return toResponse(saved);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Transactional
    public AuditoriumResponse update(Integer id, AuditoriumRequest r) { return update(id, r, null); }

    @Transactional
    public AuditoriumResponse update(Integer id, AuditoriumRequest r, Long actorUserId) {
        Auditorium room = auditoriums.findByIdForUpdate(id).orElseThrow(AuditoriumService::notFound);
        String oldName = room.getName();
        int oldCapacity = room.getTotalSeats();
        String oldType = room.getType().value();
        if (auditoriums.existsByNameLowerAndIdNot(r.name(), id)) throw nameTaken();
        long seatCount = seats.countByAuditoriumId(id);
        if (r.totalSeats() < seatCount) {
            throw new ApiException(409, "AUDITORIUM_CAPACITY_BELOW_SEATS", "Sức chứa không được nhỏ hơn số ghế hiện có (" + seatCount + ")");
        }
        room.update(r.name(), r.totalSeats(), r.type());
        try {
            Auditorium saved = auditoriums.saveAndFlush(room);
            auditEvents.record(actorUserId, "AUDITORIUM_UPDATED", "SUCCESS", "AUDITORIUM", id,
                    "name=\"" + text(oldName) + "\"->\"" + text(saved.getName())
                            + "\"; totalSeats=" + oldCapacity + "->" + saved.getTotalSeats()
                            + "; type=" + oldType + "->" + saved.getType().value());
            return AuditoriumResponse.from(saved, seatCount);
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Transactional
    public AuditoriumResponse changeStatus(Integer id, AuditoriumStatusRequest r) { return changeStatus(id, r, null); }

    @Transactional
    public AuditoriumResponse changeStatus(Integer id, AuditoriumStatusRequest r, Long actorUserId) {
        Auditorium room = auditoriums.findByIdForUpdate(id).orElseThrow(AuditoriumService::notFound);
        boolean oldActive = room.isActive();
        room.changeActive(r.active());
        auditEvents.record(actorUserId, "AUDITORIUM_STATUS_CHANGED", "SUCCESS", "AUDITORIUM", id,
                "active=" + oldActive + "->" + room.isActive());
        return toResponse(room);
    }

    private Auditorium find(Integer id) { return auditoriums.findById(id).orElseThrow(AuditoriumService::notFound); }
    private AuditoriumResponse toResponse(Auditorium a) { return AuditoriumResponse.from(a, seats.countByAuditoriumId(a.getId())); }
    static ApiException notFound() { return new ApiException(404, "AUDITORIUM_NOT_FOUND", "Không tìm thấy phòng chiếu"); }
    private static RuntimeException translate(DataIntegrityViolationException e) {
        return ConstraintViolations.violates(e, "uq_auditoriums_name_ci", "auditoriums_name_key") ? nameTaken() : e;
    }
    private static ApiException nameTaken() { return new ApiException(409, "AUDITORIUM_NAME_EXISTS", "Tên phòng chiếu đã tồn tại"); }
    private static String text(String value) { return value == null ? "" : value.replaceAll("[\\r\\n\\t]", " "); }
}
