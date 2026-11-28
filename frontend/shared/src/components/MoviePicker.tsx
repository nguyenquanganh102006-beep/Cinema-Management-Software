import { useMemo, useState } from "react";
import type { Movie } from "../types";

const apiOrigin = new URL(import.meta.env.VITE_API_URL ?? "http://localhost:8081/api/v1").origin;

function moviePoster(value?: string) {
  if (!value) return undefined;
  try {
    const url = new URL(value, apiOrigin);
    return url.pathname.startsWith("/posters/") ? `${apiOrigin}${url.pathname}` : url.href;
  } catch {
    return value;
  }
}

export function MoviePicker({
  movies,
  value,
  onChange,
}: {
  movies: Movie[];
  value: string;
  onChange: (movieId: string) => void;
}) {
  const [search, setSearch] = useState("");
  const [open, setOpen] = useState(!value);
  const selected = movies.find((movie) => String(movie.id) === value);
  const filtered = useMemo(() => {
    const keyword = search.trim().toLocaleLowerCase("vi-VN");
    return keyword ? movies.filter((movie) => movie.title.toLocaleLowerCase("vi-VN").includes(keyword)) : movies;
  }, [movies, search]);

  function choose(movie: Movie) {
    onChange(String(movie.id));
    setOpen(false);
    setSearch("");
  }

  return (
    <section className="movie-picker" aria-label="Chọn phim">
      {selected && !open ? (
        <article className="movie-picker-current">
          <div className="movie-picker-poster">
            {selected.poster ? <img src={moviePoster(selected.poster)} alt={`Áp phích ${selected.title}`} onError={(event) => { event.currentTarget.style.display = "none"; }} /> : <span aria-hidden="true">CINEMA</span>}
          </div>
          <div className="movie-picker-current-copy">
            <span className="movie-picker-caption">PHIM ĐANG CHỌN</span>
            <h3>{selected.title}</h3>
            <p className="movie-picker-meta"><span>{selected.ageRating}</span><span>{selected.durationMinutes} phút</span></p>
          </div>
          <button type="button" className="movie-picker-change" onClick={() => setOpen(true)}>Đổi phim</button>
        </article>
      ) : (
        <>
          <div className="movie-picker-heading">
            <div>
              <span className="movie-picker-caption">BƯỚC 1</span>
              <h3>Chọn phim</h3>
              <p>Chọn phim để xem lịch và suất còn bán.</p>
            </div>
            {selected && <button type="button" className="movie-picker-change" onClick={() => setOpen(false)}>Đóng</button>}
          </div>
          <label className="movie-picker-search">
            Tìm phim
            <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Nhập tên phim…" />
          </label>
          {filtered.length ? (
            <div className="movie-picker-grid">
              {filtered.map((movie) => (
                <button
                  className={`movie-choice-card${String(movie.id) === value ? " selected" : ""}`}
                  key={movie.id}
                  type="button"
                  aria-pressed={String(movie.id) === value}
                  onClick={() => choose(movie)}
                >
                  <span className="movie-choice-poster">
                    {movie.poster ? <img src={moviePoster(movie.poster)} alt="" loading="lazy" onError={(event) => { event.currentTarget.style.display = "none"; }} /> : <span aria-hidden="true">CINEMA</span>}
                    <i>{movie.ageRating}</i>
                  </span>
                  <span className="movie-choice-title">{movie.title}</span>
                  <span className="movie-choice-meta">{movie.durationMinutes} phút</span>
                </button>
              ))}
            </div>
          ) : (
            <p className="empty-state">Không tìm thấy phim phù hợp.</p>
          )}
        </>
      )}
    </section>
  );
}
