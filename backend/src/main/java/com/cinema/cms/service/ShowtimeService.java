package com.cinema.cms.service;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.*;
import com.cinema.cms.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowtimeService {
    private final ShowtimeRepository showtimes;
    private final ShowtimeSeatRepository inventory;
    private final MovieRepository movies;
    private final AuditoriumRepository auditoriums;
    private final SeatRepository seats;
    private final PricingRuleRepository pricing;
    private final ShowtimeTimeService time;
    private final Clock clock;
    private final SweetboxPairValidator pairs;
    private final AuditEventService auditEvents;

    public ShowtimeService(ShowtimeRepository showtimes, ShowtimeSeatRepository inventory, MovieRepository movies,
                           AuditoriumRepository auditoriums, SeatRepository seats, PricingRuleRepository pricing, ShowtimeTimeService time, Clock clock, SweetboxPairValidator pairs,
                           AuditEventService auditEvents) {
        this.showtimes = showtimes; this.inventory = inventory; this.movies = movies;
        this.auditoriums = auditoriums; this.seats = seats; this.pricing = pricing; this.time = time; this.clock = clock; this.pairs = pairs;
        this.auditEvents = auditEvents;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ShowtimeResponse create(CreateShowtimeRequest r) { return create(r, null); }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ShowtimeResponse create(CreateShowtimeRequest r, Long actorUserId) {
        Instant start = time.startTime(r.startTime());
        Movie movie = lockActiveMovie(r.movieId());
        Auditorium room = lockActiveRoom(r.auditoriumId());
        List<Seat> physical = seats.findByAuditoriumIdOrderBySeatRowAscSeatNumberAsc(r.auditoriumId());
        validateSeats(physical);
        Instant end = time.endTime(start, movie.getDurationMinutes());
        checkConflict(r.auditoriumId(), start, end, null);
        Showtime saved = showtimes.saveAndFlush(new Showtime(movie, room, start, end, basePrice()));
        Instant now = clock.instant();
        inventory.saveAllAndFlush(physical.stream().map(s -> new ShowtimeSeat(saved.getId(), s.getId(), now)).toList());
        auditEvents.record(actorUserId, "SHOWTIME_CREATED", "SUCCESS", "SHOWTIME", saved.getId(),
                "movieId=" + movie.getId() + "; auditoriumId=" + r.auditoriumId()
                        + "; startTime=" + start + "; endTime=" + end);
        return response(saved);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ShowtimeResponse update(Long id, UpdateShowtimeRequest r) { return update(id, r, null); }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ShowtimeResponse update(Long id, UpdateShowtimeRequest r, Long actorUserId) {
        // Lock Movie then Auditorium, matching sale writes so concurrent deactivation cannot
        // leave a newly-created schedule pointing at a master row that became inactive mid-write.
        Integer room = showtimes.findAuditoriumId(id).orElseThrow(ShowtimeService::notFound);
        Movie movie = lockActiveMovie(r.movieId());
        Auditorium auditorium = lockActiveRoom(room);
        Showtime show = showtimes.findByIdForUpdate(id).orElseThrow(ShowtimeService::notFound);
        inventory.configureLockTimeout();
        List<ShowtimeSeat> locked = inventory.findByShowtimeIdForUpdate(id);
        if (locked.stream().anyMatch(s -> s.getStatus() != SeatStatus.AVAILABLE) || showtimes.hasAnyTicket(id)) {
            throw new ApiException(409, "SHOWTIME_CANNOT_BE_MODIFIED", "Suất chiếu đã có vé hoặc ghế không còn trống");
        }
        Instant start = time.startTime(r.startTime()), end = time.endTime(start, movie.getDurationMinutes());
        checkConflict(room, start, end, id);
        Long oldMovieId = show.getMovieId();
        Instant oldStart = show.getStartTime();
        Instant oldEnd = show.getEndTime();
        show.update(movie, auditorium, start, end);
        Showtime saved = showtimes.saveAndFlush(show);
        auditEvents.record(actorUserId, "SHOWTIME_UPDATED", "SUCCESS", "SHOWTIME", id,
                "movieId=" + oldMovieId + "->" + movie.getId()
                        + "; startTime=" + oldStart + "->" + start
                        + "; endTime=" + oldEnd + "->" + end);
        return response(saved);
    }

    @Transactional(readOnly = true)
    public ShowtimeResponse get(Long id) { return get(id, UserRole.MANAGER); }

    @Transactional(readOnly = true)
    public ShowtimeResponse get(Long id, UserRole viewer) {
        Showtime show = showtimes.findById(id).orElseThrow(ShowtimeService::notFound);
        Auditorium room = auditoriums.findById(show.getAuditoriumId()).orElseThrow(ShowtimeService::notFound);
        Movie movie = movies.findById(show.getMovieId()).orElseThrow(ShowtimeService::notFound);
        if (viewer != UserRole.MANAGER && (!room.isActive() || !movie.isActive())) throw notFound();
        return ShowtimeResponse.from(show);
    }

    @Transactional(readOnly = true)
    public PageResponse<ShowtimeResponse> list(String movieId, String auditoriumId, String date, String page, String size) {
        return list(movieId, auditoriumId, date, page, size, UserRole.MANAGER);
    }

    @Transactional(readOnly = true)
    public PageResponse<ShowtimeResponse> list(String movieId, String auditoriumId, String date, String page, String size, UserRole viewer) {
        Long movie = QueryParams.longValue(movieId, "movieId");
        Integer room = QueryParams.integer(auditoriumId, "auditoriumId");
        Integer pageNumber = QueryParams.integer(page, "page"), pageSize = QueryParams.integer(size, "size");
        if (pageNumber == null || pageSize == null || (movie != null && movie <= 0) || (room != null && room <= 0)) throw invalid();
        QueryParams.checkPaging(pageNumber, pageSize, 100);
        // JPA setFirstResult accepts an int even though PageRequest calculates a long offset.
        if ((long) pageNumber * pageSize > Integer.MAX_VALUE) throw invalid();
        LocalDate day = null;
        if (date != null) {
            try {
                day = LocalDate.parse(date);
                if (day.getYear() < 1 || day.getYear() > 9999) throw invalid();
            } catch (DateTimeParseException e) { throw invalid(); }
        }
        Instant from = day == null ? null : day.atStartOfDay(ShowtimeTimeService.BUSINESS_ZONE).toInstant();
        Instant until = day == null ? null : day.plusDays(1).atStartOfDay(ShowtimeTimeService.BUSINESS_ZONE).toInstant();
        var found = showtimes.listing(movie, room, from, until, viewer == UserRole.MANAGER, PageRequest.of(pageNumber, pageSize,
                Sort.by(Sort.Order.asc("startTime"), Sort.Order.asc("id"))));
        return PageResponse.from(found, row -> ShowtimeResponse.from(row.getShowtime()));
    }

    private ShowtimeResponse response(Showtime show) {
        return ShowtimeResponse.from(show);
    }

    private Auditorium lockActiveRoom(Integer id) {
        Auditorium room = auditoriums.findByIdForUpdate(id).orElseThrow(() -> new ApiException(404, "AUDITORIUM_NOT_FOUND", "Không tìm thấy phòng chiếu"));
        if (!room.isActive()) throw new ApiException(422, "AUDITORIUM_INACTIVE", "Phòng chiếu đã ngừng hoạt động");
        return room;
    }
    private Movie lockActiveMovie(Long id) {
        Movie movie = movies.findByIdForUpdate(id).orElseThrow(() -> new ApiException(404, "MOVIE_NOT_FOUND", "Không tìm thấy phim"));
        if (!movie.isActive()) throw new ApiException(422, "MOVIE_INACTIVE", "Phim đã ngừng chiếu");
        return movie;
    }
    private BigDecimal basePrice() {
        BigDecimal price = pricing.findByRuleTypeOrderByTargetRefAsc(PricingRuleType.BASE_PRICE).stream()
                .filter(p -> p.getTargetRef().equals("STANDARD_2D")).map(PricingRule::getAmount).findFirst()
                .orElseThrow(() -> new ApiException(422, "PRICING_RULE_MISSING", "Chưa cấu hình giá vé cơ bản"));
        if (price.signum() < 0 || price.scale() > 2 || price.precision() - price.scale() > 8) {
            throw new ApiException(422, "PRICING_RULE_INVALID", "Giá vé cơ bản không hợp lệ");
        }
        return price;
    }
    private void checkConflict(Integer room, Instant start, Instant end, Long excluded) {
        if (showtimes.countOverlaps(room, start, end, excluded) > 0) {
            throw new ApiException(409, "SHOWTIME_CONFLICT", "Phòng chiếu đã có suất trong khoảng thời gian này");
        }
    }
    private void validateSeats(List<Seat> physical) {
        if (physical.isEmpty()) throw new ApiException(422, "AUDITORIUM_HAS_NO_SEATS", "Phòng chiếu chưa có ghế");
        if (!pairs.validateLayout(physical)) {
            throw new ApiException(422, "SWEETBOX_PAIR_INVALID", "Sweetbox phải là hai ghế liền kề cùng phòng, liên kết đối xứng");
        }
    }
    private static ApiException invalid() { return new ApiException(400, "VALIDATION_ERROR", "Bộ lọc suất chiếu không hợp lệ"); }
    private static ApiException notFound() { return new ApiException(404, "SHOWTIME_NOT_FOUND", "Không tìm thấy suất chiếu"); }
}
