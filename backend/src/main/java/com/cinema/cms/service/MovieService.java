package com.cinema.cms.service;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.Movie;
import com.cinema.cms.entity.UserRole;
import com.cinema.cms.repository.MovieRepository;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MovieService {
    static final int MAX_PAGE_SIZE = 100;
    private final MovieRepository movies;
    private final AuditEventService auditEvents;

    public MovieService(MovieRepository movies, AuditEventService auditEvents) { this.movies = movies; this.auditEvents = auditEvents; }

    @Transactional(readOnly = true)
    public PageResponse<MovieResponse> list(String keyword, Boolean active, int page, int size, UserRole viewer) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(400, "VALIDATION_ERROR", "page phải từ 0 trở lên và size từ 1 đến " + MAX_PAGE_SIZE);
        }
        // Film metadata is public catalog information. Inactive titles stay visible so the
        // customer portal can label them as ended; scheduling and sales still require active rows.
        Boolean effectiveActive = active;
        Specification<Movie> spec = (root, query, cb) -> cb.conjunction();
        if (keyword != null && !keyword.isBlank()) spec = spec.and(titleContains(keyword.strip()));
        if (effectiveActive != null) spec = spec.and(hasActive(effectiveActive));
        // Active catalog titles come first; older release dates put currently showing films
        // ahead of the upcoming active titles, while inactive ended films stay at the bottom.
        var sort = Sort.by(Sort.Order.desc("active"), Sort.Order.asc("releaseDate"),
                Sort.Order.asc("title"), Sort.Order.asc("id"));
        return PageResponse.from(movies.findAll(spec, PageRequest.of(page, size, sort)), MovieResponse::from);
    }

    @Transactional(readOnly = true)
    public MovieResponse get(Long id, UserRole viewer) {
        Movie movie = find(id);
        return MovieResponse.from(movie);
    }

    @Transactional
    public MovieResponse create(MovieRequest r) { return create(r, null); }

    @Transactional
    public MovieResponse create(MovieRequest r, Long actorUserId) {
        Movie movie = new Movie(r.title(), r.durationMinutes(), r.ageRating(), r.releaseDate(), r.description(),
                r.genre(), r.country(), r.language(), r.director(), r.poster(), r.trailer());
        Movie saved = movies.saveAndFlush(movie);
        auditEvents.record(actorUserId, "MOVIE_CREATED", "SUCCESS", "MOVIE", saved.getId(),
                "title=\"" + text(saved.getTitle()) + "\"; ageRating=" + saved.getAgeRating()
                        + "; durationMinutes=" + saved.getDurationMinutes());
        return MovieResponse.from(saved);
    }

    @Transactional
    public MovieResponse update(Long id, MovieRequest r) { return update(id, r, null); }

    @Transactional
    public MovieResponse update(Long id, MovieRequest r, Long actorUserId) {
        Movie movie = movies.findByIdForUpdate(id).orElseThrow(MovieService::notFound);
        String oldTitle = movie.getTitle();
        var oldRating = movie.getAgeRating();
        int oldDuration = movie.getDurationMinutes();
        movie.update(r.title(), r.durationMinutes(), r.ageRating(), r.releaseDate(), r.description(),
                r.genre(), r.country(), r.language(), r.director(), r.poster(), r.trailer());
        auditEvents.record(actorUserId, "MOVIE_UPDATED", "SUCCESS", "MOVIE", id,
                "title=\"" + text(oldTitle) + "\"->\"" + text(movie.getTitle())
                        + "\"; ageRating=" + oldRating + "->" + movie.getAgeRating()
                        + "; durationMinutes=" + oldDuration + "->" + movie.getDurationMinutes());
        return MovieResponse.from(movie);
    }

    @Transactional
    public MovieResponse changeStatus(Long id, MovieStatusRequest r) { return changeStatus(id, r, null); }

    @Transactional
    public MovieResponse changeStatus(Long id, MovieStatusRequest r, Long actorUserId) {
        Movie movie = movies.findByIdForUpdate(id).orElseThrow(MovieService::notFound);
        boolean oldActive = movie.isActive();
        movie.changeActive(r.active());
        auditEvents.record(actorUserId, "MOVIE_STATUS_CHANGED", "SUCCESS", "MOVIE", id,
                "active=" + oldActive + "->" + movie.isActive());
        return MovieResponse.from(movie);
    }

    private Movie find(Long id) { return movies.findById(id).orElseThrow(MovieService::notFound); }
    private static ApiException notFound() { return new ApiException(404, "MOVIE_NOT_FOUND", "Không tìm thấy phim"); }

    private static Specification<Movie> hasActive(boolean active) { return (root, query, cb) -> cb.equal(root.get("active"), active); }
    private static Specification<Movie> titleContains(String keyword) {
        String pattern = "%" + escapeLike(keyword.toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("title")), pattern, '\\');
    }
    private static String escapeLike(String value) { return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }
    private static String text(String value) { return value == null ? "" : value.replaceAll("[\\r\\n\\t]", " "); }
}
