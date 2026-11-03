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

    public MovieService(MovieRepository movies) { this.movies = movies; }

    @Transactional(readOnly = true)
    public PageResponse<MovieResponse> list(String keyword, Boolean active, int page, int size, UserRole viewer) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(400, "VALIDATION_ERROR", "page phải từ 0 trở lên và size từ 1 đến " + MAX_PAGE_SIZE);
        }
        Boolean effectiveActive = viewer == UserRole.MANAGER ? active : Boolean.TRUE;
        Specification<Movie> spec = (root, query, cb) -> cb.conjunction();
        if (keyword != null && !keyword.isBlank()) spec = spec.and(titleContains(keyword.strip()));
        if (effectiveActive != null) spec = spec.and(hasActive(effectiveActive));
        var sort = Sort.by(Sort.Order.asc("title"), Sort.Order.asc("id"));
        return PageResponse.from(movies.findAll(spec, PageRequest.of(page, size, sort)), MovieResponse::from);
    }

    @Transactional(readOnly = true)
    public MovieResponse get(Long id, UserRole viewer) {
        Movie movie = find(id);
        if (viewer != UserRole.MANAGER && !movie.isActive()) throw notFound();
        return MovieResponse.from(movie);
    }

    @Transactional
    public MovieResponse create(MovieRequest r) {
        Movie movie = new Movie(r.title(), r.durationMinutes(), r.ageRating(), r.releaseDate(), r.description(),
                r.genre(), r.country(), r.language(), r.director(), r.poster(), r.trailer());
        return MovieResponse.from(movies.save(movie));
    }

    @Transactional
    public MovieResponse update(Long id, MovieRequest r) {
        Movie movie = find(id);
        movie.update(r.title(), r.durationMinutes(), r.ageRating(), r.releaseDate(), r.description(),
                r.genre(), r.country(), r.language(), r.director(), r.poster(), r.trailer());
        return MovieResponse.from(movie);
    }

    @Transactional
    public MovieResponse changeStatus(Long id, MovieStatusRequest r) {
        Movie movie = find(id);
        movie.changeActive(r.active());
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
}