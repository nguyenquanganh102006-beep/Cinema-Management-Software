package com.cinema.cms.service;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.*;
import com.cinema.cms.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;
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

    public ShowtimeService(ShowtimeRepository showtimes, ShowtimeSeatRepository inventory, MovieRepository movies,
                           AuditoriumRepository auditoriums, SeatRepository seats, PricingRuleRepository pricing, ShowtimeTimeService time, Clock clock) {
        this.showtimes = showtimes; this.inventory = inventory; this.movies = movies;
        this.auditoriums = auditoriums; this.seats = seats; this.pricing = pricing; this.time = time; this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ShowtimeResponse create(CreateShowtimeRequest r) {
        Instant start = time.startTime(r.startTime());
        lockActiveRoom(r.auditoriumId());
        Movie movie = activeMovie(r.movieId());
        List<Seat> physical = seats.findByAuditoriumIdOrderBySeatRowAscSeatNumberAsc(r.auditoriumId());
        validateSeats(physical);
        Instant end = time.endTime(start, movie.getDurationMinutes());
        checkConflict(r.auditoriumId(), start, end, null);
        Showtime saved = showtimes.saveAndFlush(new Showtime(movie.getId(), r.auditoriumId(), start, end, basePrice()));
        Instant now = clock.instant();
        inventory.saveAllAndFlush(physical.stream().map(s -> new ShowtimeSeat(saved.getId(), s.getId(), now)).toList());
        return ShowtimeResponse.from(saved);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ShowtimeResponse update(Long id, UpdateShowtimeRequest r) {
        // Room is immutable. Lock order shared with CREATE and Master Data seat-layout writers.
        Integer room = showtimes.findAuditoriumId(id).orElseThrow(ShowtimeService::notFound);
        lockActiveRoom(room);
        Showtime show = showtimes.findByIdForUpdate(id).orElseThrow(ShowtimeService::notFound);
        List<ShowtimeSeat> locked = inventory.findByShowtimeIdForUpdate(id);
        if (locked.stream().anyMatch(s -> s.getStatus() != SeatStatus.AVAILABLE) || showtimes.hasAnyTicket(id)) {
            throw new ApiException(409, "SHOWTIME_CANNOT_BE_MODIFIED", "Suất chiếu đã có vé hoặc ghế không còn trống");
        }
        Movie movie = activeMovie(r.movieId());
        Instant start = time.startTime(r.startTime()), end = time.endTime(start, movie.getDurationMinutes());
        checkConflict(room, start, end, id);
        show.update(movie.getId(), start, end);
        return ShowtimeResponse.from(showtimes.saveAndFlush(show));
    }

    @Transactional(readOnly = true)
    public ShowtimeResponse get(Long id) {
        return ShowtimeResponse.from(showtimes.findById(id).orElseThrow(ShowtimeService::notFound));
    }

    @Transactional(readOnly = true)
    public PageResponse<ShowtimeResponse> list(String movieId, String auditoriumId, String date, String page, String size) {
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
        Specification<Showtime> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> p = new ArrayList<>();
            if (movie != null) p.add(cb.equal(root.get("movieId"), movie));
            if (room != null) p.add(cb.equal(root.get("auditoriumId"), room));
            if (from != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("startTime"), from));
                p.add(cb.lessThan(root.get("startTime"), until));
            }
            return cb.and(p.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
        return PageResponse.from(showtimes.findAll(spec, PageRequest.of(pageNumber, pageSize,
                Sort.by(Sort.Order.asc("startTime"), Sort.Order.asc("id")))), ShowtimeResponse::from);
    }

    private void lockActiveRoom(Integer id) {
        Auditorium room = auditoriums.findByIdForUpdate(id).orElseThrow(() -> new ApiException(404, "AUDITORIUM_NOT_FOUND", "Không tìm thấy phòng chiếu"));
        if (!room.isActive()) throw new ApiException(422, "AUDITORIUM_INACTIVE", "Phòng chiếu đã ngừng hoạt động");
    }
    private Movie activeMovie(Long id) {
        Movie movie = movies.findById(id).orElseThrow(() -> new ApiException(404, "MOVIE_NOT_FOUND", "Không tìm thấy phim"));
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
        Map<Long, Seat> byId = physical.stream().collect(Collectors.toMap(Seat::getId, s -> s));
        for (Seat seat : physical) {
            Seat pair = byId.get(seat.getPairSeatId());
            if (seat.getSeatType() == SeatType.SWEETBOX) {
                if (pair == null || pair.getSeatType() != SeatType.SWEETBOX || !seat.getId().equals(pair.getPairSeatId())
                        || !seat.getSeatRow().equals(pair.getSeatRow()) || Math.abs((long) seat.getSeatNumber() - pair.getSeatNumber()) != 1) {
                    throw new ApiException(422, "SWEETBOX_PAIR_INVALID", "Sweetbox phải là hai ghế liền kề cùng phòng, liên kết đối xứng");
                }
            } else if (seat.getPairSeatId() != null) {
                throw new ApiException(422, "SWEETBOX_PAIR_INVALID", "Chỉ ghế Sweetbox được ghép cặp");
            }
        }
    }
    private static ApiException invalid() { return new ApiException(400, "VALIDATION_ERROR", "Bộ lọc suất chiếu không hợp lệ"); }
    private static ApiException notFound() { return new ApiException(404, "SHOWTIME_NOT_FOUND", "Không tìm thấy suất chiếu"); }
}
