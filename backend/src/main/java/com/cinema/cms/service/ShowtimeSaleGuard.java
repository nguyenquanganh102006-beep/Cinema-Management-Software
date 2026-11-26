package com.cinema.cms.service;

import com.cinema.cms.entity.Showtime;
import com.cinema.cms.repository.AuditoriumRepository;
import com.cinema.cms.repository.MovieRepository;
import com.cinema.cms.repository.ShowtimeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowtimeSaleGuard {
    private final ShowtimeRepository showtimes;
    private final MovieRepository movies;
    private final AuditoriumRepository auditoriums;

    public ShowtimeSaleGuard(ShowtimeRepository showtimes, MovieRepository movies,
                             AuditoriumRepository auditoriums) {
        this.showtimes = showtimes;
        this.movies = movies;
        this.auditoriums = auditoriums;
    }

    @Transactional(readOnly = true)
    public Showtime requireSaleable(Long showtimeId) {
        Showtime showtime = showtimes.findById(showtimeId)
                .orElseThrow(() -> new ApiException(404, "SHOWTIME_NOT_FOUND", "Không tìm thấy suất chiếu"));
        boolean movieActive = movies.findById(showtime.getMovieId()).map(movie -> movie.isActive()).orElse(false);
        boolean roomActive = auditoriums.findById(showtime.getAuditoriumId()).map(room -> room.isActive()).orElse(false);
        if (!movieActive || !roomActive) {
            throw new ApiException(409, "SHOWTIME_UNAVAILABLE", "Suất chiếu đang tạm ngừng bán");
        }
        return showtime;
    }

    /** Hold the master rows for write flows so deactivation is serialized against new sales. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Showtime lockSaleable(Long showtimeId) {
        Showtime showtime = showtimes.findById(showtimeId)
                .orElseThrow(() -> new ApiException(404, "SHOWTIME_NOT_FOUND", "Không tìm thấy suất chiếu"));
        boolean movieActive = movies.findByIdForUpdate(showtime.getMovieId())
                .map(movie -> movie.isActive()).orElse(false);
        boolean roomActive = auditoriums.findByIdForUpdate(showtime.getAuditoriumId())
                .map(room -> room.isActive()).orElse(false);
        if (!movieActive || !roomActive) {
            throw new ApiException(409, "SHOWTIME_UNAVAILABLE", "Suất chiếu đang tạm ngừng bán");
        }
        return showtime;
    }
}
