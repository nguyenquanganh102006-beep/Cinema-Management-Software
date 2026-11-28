import { request, requestPage, requestAll, query } from "../api";
import type { Movie, Showtime, Concession, Auditorium } from "../types";

type RawShowtime = Showtime & {
  id?: number;
  showtimeId?: number;
};

type RawConcession = Concession & {
  availableQuantity?: number;
  lowStock?: boolean;
};

function enrichShowtime(
  raw: RawShowtime,
  movies: Map<number, Movie>,
  rooms: Map<number, Auditorium>,
): Showtime {
  const movie = movies.get(raw.movieId);
  const room = rooms.get(raw.auditoriumId);
  return {
    id: Number(raw.id ?? raw.showtimeId),
    movieId: raw.movieId,
    movieTitle: raw.movieTitle ?? movie?.title ?? `Phim #${raw.movieId}`,
    ageRating: raw.ageRating ?? movie?.ageRating ?? "P",
    durationMinutes: raw.durationMinutes ?? movie?.durationMinutes ?? 0,
    auditoriumId: raw.auditoriumId,
    auditoriumName:
      raw.auditoriumName ?? room?.name ?? `Phòng #${raw.auditoriumId}`,
    auditoriumType:
      raw.auditoriumType ?? room?.auditoriumType ?? "STANDARD_2D",
    startTime: raw.startTime,
    endTime: raw.endTime,
    basePrice: Number(raw.basePrice),
  };
}

export const catalogApi = {
  movieOptions: (token: string, signal?: AbortSignal) => requestAll<Movie>("/movies?active=true", { token, signal }),
  concessionOptions: (token: string, signal?: AbortSignal) => requestAll<Concession>("/concessions?active=true&combo=false", { token, signal }),
  movies: (
    token: string,
    filters: { keyword?: string; active?: boolean; page?: number; size?: number } = {},
    signal?: AbortSignal,
  ) => requestPage<Movie>("/movies?" + query(filters), { token, signal }),
  movie: (id: number, token: string, signal?: AbortSignal) =>
    request<Movie>(`/movies/${id}`, { token, signal }),
  showtimes: async (
    token: string,
    filters: { movieId?: number; auditoriumId?: number; date?: string } = {},
    signal?: AbortSignal,
  ): Promise<Showtime[]> => {
    const rows = await requestAll<RawShowtime>("/showtimes?" + query(filters), { token, signal });
    return rows.map((row) => enrichShowtime(row, new Map(), new Map()));
  },
  showtime: async (id: number, token: string, signal?: AbortSignal): Promise<Showtime> => {
    const raw = await request<RawShowtime>(`/showtimes/${id}`, { token, signal });
    return enrichShowtime(raw, new Map(), new Map());
  },
  concessions: async (
    token: string,
    signal?: AbortSignal,
  ): Promise<Concession[]> => {
    const rows = await requestAll<RawConcession>("/concessions?active=true", { token, signal });
    return rows.map((item) => ({ ...item, price: Number(item.price) }));
  },
};
