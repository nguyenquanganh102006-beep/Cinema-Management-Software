import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError } from "../api";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { catalogApi } from "../api/catalog";
import { holdsApi } from "../api/holds";
import { bookingsApi } from "../api/bookings";
import { testPaymentsApi } from "../api/testPayments";
import { posApi } from "../api/pos";
import { quote } from "../api/pricing";
import { useAuth } from "../auth/AuthProvider";
import { useBookingFlow } from "../booking/BookingFlowProvider";
import { useQuery } from "../hooks/useQuery";
import { useServerCountdown } from "../hooks/useServerCountdown";
import { useSeatHold } from "../hooks/useSeatHold";
import { Feedback } from "../components/Feedback";
import { Pagination } from "../components/Pagination";
import { SeatMap } from "../components/SeatMap";
import { ConcessionPicker } from "../components/ConcessionPicker";
import { OrderSummary } from "../components/OrderSummary";
import { HoldCountdown } from "../components/HoldCountdown";
import { AgeConfirmation } from "../components/AgeConfirmation";
import { TicketView, Qr } from "../components/TicketView";
import { DateRail, showtimeDateKey } from "../components/DateRail";
import { MoviePicker } from "../components/MoviePicker";
import { CheckoutRecovery } from "./CheckoutRecovery";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { dateTime, money, today } from "../utils/format";
import type { Booking, Showtime } from "../types";

const apiOrigin = new URL(
  import.meta.env.VITE_API_URL ?? "http://localhost:8081/api/v1",
).origin;

function posterUrl(poster?: string) {
  if (!poster) return undefined;
  try {
    const url = new URL(poster, apiOrigin);
    if (url.pathname.startsWith("/posters/")) return `${apiOrigin}${url.pathname}`;
    return url.href;
  } catch {
    return poster;
  }
}

function displayDate(value: string) {
  const [year, month, day] = value.split("-");
  return year && month && day ? `${day}/${month}/${year}` : value;
}

function displayTime(value: string) {
  return new Intl.DateTimeFormat("vi-VN", {
    timeZone: "Asia/Ho_Chi_Minh",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}

const bookingStatusLabels: Record<string, string> = {
  PENDING: "Chờ thanh toán",
  PAID: "Đã thanh toán",
  CANCELLED: "Đã kết thúc",
  EXCEPTION_PENDING_DECISION: "Chờ rạp hỗ trợ",
};
const paymentMethodLabels: Record<string, string> = {
  CASH: "Tiền mặt",
  VIETQR: "VietQR",
  GATEWAY_ONLINE: "VNPay",
};
const refundStatusLabels: Record<string, string> = {
  NONE: "—",
  PARTIAL: "Hoàn một phần",
  FULL: "Đã hoàn",
};

function bookingStatusLabel(status: string) {
  return bookingStatusLabels[status] ?? "Đang xử lý";
}

function paymentMethodLabel(method?: string) {
  if (!method) return "Chưa thanh toán";
  return paymentMethodLabels[method] ?? method;
}

function refundStatusLabel(status?: string) {
  return refundStatusLabels[status ?? "NONE"] ?? "—";
}

export function MovieListPage() {
  const { session } = useAuth();
  const [keyword, setKeyword] = useState(""),
    [page, setPage] = useState(0);
  const result = useQuery(`movies:${keyword}:${page}`, (s) =>
    catalogApi.movies(session.accessToken, { keyword, page }, s),
  );
  const schedules = useQuery("movie-list-schedules", (s) =>
    catalogApi.showtimes(session.accessToken, {}, s),
  );
  const upcomingMovieIds = new Set(
    (schedules.data ?? [])
      .filter((show) => Date.parse(show.startTime) > Date.now())
      .map((show) => show.movieId),
  );
  return (
    <section>
      <label>
        Tìm phim
        <input
          value={keyword}
          onChange={(e) => {
            setKeyword(e.target.value);
            setPage(0);
          }}
        />
      </label>
      <Feedback
        {...result}
        empty={result.data?.data.length === 0}
        retry={result.refetch}
      />
      {Boolean(result.data?.data.length) && (
        <div className="cards movie-grid">
          {result.data?.data.map((movie) => {
            const metadata = [movie.ageRating, `${movie.durationMinutes} phút`, movie.genre]
              .filter(Boolean)
              .join(", ");
            const status = !movie.active
              ? { label: "Hết chiếu", tone: "ended" }
              : movie.releaseDate > today()
                ? { label: "Sắp chiếu", tone: "upcoming" }
                : schedules.data
                  ? upcomingMovieIds.has(movie.id)
                    ? { label: "Đang chiếu", tone: "showing" }
                    : { label: "Hết chiếu", tone: "ended" }
                  : { label: "Đang cập nhật", tone: "loading" };
            return (
              <Link className="movie-card-link" to={`/movies/${movie.id}`} key={movie.id}>
                <article className="card movie-card">
                  <div className="movie-poster-frame">
                    {movie.poster && (
                      <img className="poster" src={posterUrl(movie.poster)} alt={`Áp phích ${movie.title}`} loading="lazy" onError={(e) => { e.currentTarget.style.display = "none"; }} />
                    )}
                    <div className="poster-fallback" aria-hidden="true">CINEMA</div>
                  </div>
                  <span className={`movie-status ${status.tone}`}>{status.label}</span>
                  <h3>{movie.title}</h3>
                  {metadata && <p className="movie-card-meta">{metadata}</p>}
                  {movie.description && <p className="movie-card-description">{movie.description}</p>}
                </article>
              </Link>
            );
          })}
        </div>
      )}
      {result.data && result.data.meta.totalElements > 0 && (
        <Pagination meta={result.data.meta} onPage={setPage} />
      )}
    </section>
  );
}
export function MovieDetailPage() {
  const id = Number(useParams().id),
    { session } = useAuth();
  const [date, setDate] = useState(today());
  const movie = useQuery("movie:" + id, (s) =>
    catalogApi.movie(id, session.accessToken, s),
  );
  const calendar = useQuery(`movie-calendar:${id}`, (s) =>
    catalogApi.showtimes(session.accessToken, { movieId: id }, s),
  );
  const shows = useQuery(`movie-shows:${id}:${date}`, (s) =>
    catalogApi.showtimes(session.accessToken, { movieId: id, date }, s),
  );
  const showtimeCounts = (calendar.data ?? []).reduce<Record<string, number>>((counts, show) => {
    if (Date.parse(show.startTime) > Date.now()) {
      const key = showtimeDateKey(show.startTime);
      counts[key] = (counts[key] ?? 0) + 1;
    }
    return counts;
  }, {});
  const details = movie.data;
  const metadata = details
    ? [details.ageRating, `${details.durationMinutes} phút`, details.genre, details.director, details.language, details.country]
        .filter(Boolean)
        .join(", ")
    : "";
  return (
    <section>
      <Feedback {...movie} retry={movie.refetch} />
      {movie.data && (
        <div className="movie-detail-hero">
          {movie.data.poster && (
            <>
              <img
                className="movie-detail-poster"
                src={posterUrl(movie.data.poster)}
                alt={`Áp phích ${movie.data.title}`}
                onError={(e) => { e.currentTarget.style.display = "none"; }}
              />
              <div className="movie-detail-poster-fallback" aria-hidden="true">
                CINEMA
              </div>
            </>
          )}
          <div className="movie-detail-copy">
            <h2>{movie.data.title}</h2>
            {movie.data.description && <p>{movie.data.description}</p>}
            {metadata && <p>{metadata}</p>}
            <p>Khởi chiếu: {displayDate(movie.data.releaseDate)}</p>
            {movie.data.trailer && (
              <a href={movie.data.trailer} target="_blank" rel="noreferrer">
                Xem trailer
              </a>
            )}
          </div>
        </div>
      )}
      {movie.data?.active && <DateRail value={date} onChange={setDate} showtimeCounts={showtimeCounts} />}
      <Feedback
        {...shows}
        empty={shows.data?.length === 0}
        emptyTitle="Chưa có suất chiếu"
        emptyMessage={movie.data && !movie.data.active
          ? "Phim đã hết chiếu và hiện không còn suất bán vé."
          : movie.data && date < movie.data.releaseDate
            ? `Phim dự kiến khởi chiếu ngày ${displayDate(movie.data.releaseDate)}. Chọn ngày đó hoặc một ngày sau để xem lịch chiếu.`
            : `Hiện chưa có suất chiếu ngày ${displayDate(date)}. Hãy chọn ngày khác để xem lịch.`}
        retry={shows.refetch}
      />
      {Boolean(shows.data?.length) && <h3>Suất chiếu</h3>}
      {Boolean(shows.data?.length) && (
        <div className="cards">
          {shows.data?.map((show) => (
            <ShowtimeCard key={show.id} show={show} compact />
          ))}
        </div>
      )}
    </section>
  );
}
function ShowtimeCard({ show, compact = false }: { show: Showtime; compact?: boolean }) {
  const { session } = useAuth();
  const { state, dispatch } = useBookingFlow();
  const [confirm, setConfirm] = useState(false);
  const navigate = useNavigate();
  const [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const pos = session.user.role === "STAFF";
  const auditoriumType = show.auditoriumType === "STANDARD_2D"
    ? "2D"
    : show.auditoriumType;
  async function select() {
    setBusy(true);
    try {
      if (state.hold) {
        await holdsApi.release(state.hold.holdToken, session.accessToken);
        dispatch({ type: "RESET" });
      }
      navigate(
        pos ? `/pos/showtimes/${show.id}` : `/showtimes/${show.id}/book`,
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <article className="card showtime-card">
      <h3>{compact ? displayTime(show.startTime) : show.movieTitle}</h3>
      <dl className="showtime-facts">
        {!compact && <div><dt>Giờ chiếu</dt><dd>{dateTime(show.startTime)}</dd></div>}
        <div><dt>Phòng chiếu</dt><dd>{show.auditoriumName}</dd></div>
        <div><dt>Định dạng</dt><dd>{auditoriumType}</dd></div>
        <div><dt>Độ tuổi</dt><dd>{show.ageRating}</dd></div>
        <div><dt>Kết thúc</dt><dd>{dateTime(show.endTime)}</dd></div>
      </dl>
      <button disabled={Date.parse(show.startTime) <= Date.now()} onClick={() => (state.hold ? setConfirm(true) : void select())}>
        Chọn suất
      </button>
      <Feedback error={error} />
      {confirm && (
        <ConfirmDialog
          title="Đổi suất chiếu?"
          onClose={() => setConfirm(false)}
        >
          <p>Giải phóng ghế đang giữ và bắt đầu lại.</p>
          <button disabled={busy} onClick={select}>
            Xác nhận đổi suất
          </button>
        </ConfirmDialog>
      )}
    </article>
  );
}
export function ShowtimeSearchPage() {
  const { session } = useAuth();
  const [date, setDate] = useState(today()),
    [movieId, setMovieId] = useState("");
  const movies = useQuery("pos-movies", (s) =>
    catalogApi.movieOptions(session.accessToken, s),
  );
  const calendar = useQuery(`pos-movie-calendar:${movieId}`, (s) =>
    movieId
      ? catalogApi.showtimes(session.accessToken, { movieId: Number(movieId) }, s)
      : Promise.resolve([]),
  );
  const result = useQuery(`shows:${date}:${movieId}`, (s) =>
    movieId
      ? catalogApi.showtimes(
          session.accessToken,
          { date, movieId: Number(movieId) },
          s,
        )
      : Promise.resolve([]),
  );
  const showtimeCounts = (calendar.data ?? []).reduce<Record<string, number>>((counts, show) => {
    if (Date.parse(show.startTime) > Date.now()) {
      const key = showtimeDateKey(show.startTime);
      counts[key] = (counts[key] ?? 0) + 1;
    }
    return counts;
  }, {});
  return (
    <section>
      <h2>Chọn phim và suất chiếu</h2>
      <p className="staff-flow-hint">Chọn phim, chọn ngày có suất rồi chọn giờ chiếu.</p>
      <Feedback {...movies} retry={movies.refetch} />
      <MoviePicker movies={movies.data ?? []} value={movieId} onChange={setMovieId} />
      {!movieId ? (
        <p className="empty-state">Chọn một phim ở trên để tiếp tục đặt vé.</p>
      ) : (
        <>
          <DateRail value={date} onChange={setDate} showtimeCounts={showtimeCounts} />
          <h3 className="staff-showtime-heading">Suất chiếu trong ngày đã chọn</h3>
          <Feedback
            {...result}
            empty={result.data?.length === 0}
            emptyTitle="Chưa có suất chiếu"
            emptyMessage="Phim này chưa có suất chiếu trong ngày đã chọn. Hãy thử ngày khác."
            retry={result.refetch}
          />
          <div className="cards">
            {result.data?.map((show) => (
              <ShowtimeCard key={show.id} show={show} />
            ))}
          </div>
        </>
      )}
    </section>
  );
}
export function BookingPage() {
  const id = Number(useParams().id),
    { session } = useAuth();
  const { state, dispatch } = useBookingFlow();
  const show = useQuery("show:" + id, (s) =>
    catalogApi.showtime(id, session.accessToken, s),
  );
  const items = useQuery("concessions", (s) =>
    catalogApi.concessions(session.accessToken, s),
  );
  const seatHold = useSeatHold(id);
  const [confirm, setConfirm] = useState(false);
  const [bookingStep, setBookingStep] = useState<1 | 2>(1);
  const [quoteError, setQuoteError] = useState("");
  const [quoteVersion, setQuoteVersion] = useState(0);
  const pos = session.user.role === "STAFF";
  useEffect(() => {
    if (show.data) dispatch({ type: "SHOWTIME", value: show.data });
  }, [show.data, dispatch]);
  useEffect(() => {
    setBookingStep(seatHold.hold ? 2 : 1);
  }, [seatHold.hold?.holdToken]);
  const targetSeats = seatHold.hold?.seatIds ?? state.selected;
  const seatIdsKey = targetSeats.join(",");
  const seatLabels = new Map<number, string>(
    (seatHold.map?.seats ?? []).map((seat) => [seat.seatId, `${seat.row}${seat.number}`] as const),
  );
  const estimatedTotal = (state.quote?.total ?? 0) + state.cart.reduce((sum, line) =>
    sum + (items.data?.find((item) => item.id === line.itemId)?.price ?? 0) * line.quantity, 0);
  useEffect(() => {
    if (!targetSeats.length) return;
    const controller = new AbortController();
    const seatIds = seatIdsKey.split(",").map(Number);
    setQuoteError("");
    quote(id, seatIds, session.accessToken, controller.signal)
      .then((value) => dispatch({ type: "QUOTE", value }))
      .catch((error) => {
        if (!controller.signal.aborted) setQuoteError(error.message);
      });
    return () => controller.abort();
  }, [
    seatIdsKey,
    id,
    session.accessToken,
    dispatch,
    quoteVersion,
  ]);
  return (
    <section className="booking-page">
      <ol className="booking-steps" aria-label="Tiến trình đặt vé">
        <li>1. Chọn suất</li>
        <li aria-current={bookingStep === 1 ? "step" : undefined}>2. Chọn ghế</li>
        <li aria-current={bookingStep === 2 ? "step" : undefined}>3. Bắp nước</li>
        <li>4. Thanh toán</li>
        <li>5. Nhận vé</li>
      </ol>
      <h2>
        {show.data?.movieTitle ?? "Chọn ghế"}, {show.data?.ageRating}
      </h2>
      <Feedback {...show} retry={show.refetch} />
      <Feedback
        loading={seatHold.loading}
        error={seatHold.error}
        retry={seatHold.refetch}
      />
      {seatHold.hold && (
        <HoldCountdown
          seconds={seatHold.seconds}
          pos={pos}
          extensionUsed={seatHold.hold.extensionUsed}
          waiting={false}
          onExtend={seatHold.extend}
        />
      )}
      <Feedback
        error={quoteError}
        retry={() => setQuoteVersion((v) => v + 1)}
      />
      <div className="booking-grid">
        <div>
          {bookingStep === 1 && seatHold.map && (
            <SeatMap
              seats={seatHold.map.seats}
              selected={state.selected}
              locked={!!seatHold.hold || seatHold.busy || !!state.creation}
              onSelect={(value) => dispatch({ type: "SELECT", value })}
            />
          )}
          {bookingStep === 2 && <Feedback {...items} retry={items.refetch} />}
          {bookingStep === 2 && items.data && (
            <ConcessionPicker
              items={items.data}
              cart={state.cart}
              disabled={!!state.creation}
              onChange={(value) => dispatch({ type: "CART", value })}
            />
          )}
        </div>
        <aside>
          <OrderSummary
            quote={state.quote}
            seatLabels={seatLabels}
            items={items.data}
            cart={state.cart}
          />
          <div className="booking-actions">
          <p className="booking-action-hint">{targetSeats.length} ghế, {state.quote ? `Tạm tính ${money(estimatedTotal)}` : "Chọn ghế để xem giá"}</p>
          {bookingStep === 1 && seatHold.hold ? (
            <>
              <p role="status">Ghế đã được giữ. Tiếp tục chọn bắp nước để hoàn tất đơn.</p>
              <button className="primary" onClick={() => setBookingStep(2)}>Tiếp tục chọn bắp nước</button>
            </>
          ) : bookingStep === 1 ? (
            <button
              className="primary"
              disabled={seatHold.busy || !state.selected.length || !state.quote || !!state.creation}
              onClick={async () => {
                const held = await seatHold.create();
                if (held) setBookingStep(2);
              }}
            >
              Tiếp tục chọn bắp nước
            </button>
          ) : (
            <>
              <button disabled={seatHold.busy || !!state.creation} onClick={async () => {
                await seatHold.release();
                setBookingStep(1);
              }}>
                Quay lại chọn ghế
              </button>
              {seatHold.seconds > 0 ? (
                <Link className="button primary" to={pos ? "/pos/checkout" : "/checkout"}>
                  Tiếp tục thanh toán
                </Link>
              ) : (
                <button className="primary" disabled>Phiên giữ ghế đã hết hạn</button>
              )}
            </>
          )}
          </div>
        </aside>
      </div>
      {confirm && (
        <ConfirmDialog title="Đổi ghế?" onClose={() => setConfirm(false)}>
          <p>Giải phóng toàn bộ ghế trước khi chọn lại.</p>
          <button
            disabled={seatHold.busy}
            onClick={async () => {
              await seatHold.release();
              setConfirm(false);
            }}
          >
            Giải phóng ghế
          </button>
        </ConfirmDialog>
      )}
    </section>
  );
}
export function CheckoutPage() {
  const { state } = useBookingFlow();
  return state.showtime && (state.hold || state.booking || state.creation) ? (
    <CheckoutFlow />
  ) : (
    <CheckoutRecovery />
  );
}
function CheckoutFlow() {
  const { session } = useAuth(),
    { state, dispatch } = useBookingFlow();
  const navigate = useNavigate();
  const pos = session.user.role === "STAFF";
  const hold = useSeatHold(state.showtime!.id);
  const [age, setAge] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [cash, setCash] = useState(""),
    [received, setReceived] = useState(false),
    [url, setUrl] = useState(""),
    [uncertain, setUncertain] = useState(false);
  const writing = useRef(false);
  const booking = state.booking;
  const bookingSeconds = useServerCountdown(booking?.serverTime, booking?.holdUntil);
  const seconds = hold.hold ? hold.seconds : bookingSeconds;
  const canPay = state.showtime!.ageRating === "P" || age;
  const concessions = useQuery("checkout-concessions", (s) =>
    catalogApi.concessions(session.accessToken, s),
  );
  const testPaymentMode = useQuery(`test-payment-mode:${pos}`, (s) =>
    !pos && import.meta.env.DEV && import.meta.env.VITE_USE_MOCKS !== "true"
      ? testPaymentsApi.mode(session.accessToken, s)
      : Promise.resolve({ enabled: false }),
  );
  async function reload() {
    if (!booking) return;
    const basic = await bookingsApi.read(booking.bookingId, session.accessToken, undefined, booking);
    const value = basic.status === "PAID" ? await bookingsApi.get(booking.bookingId, session.accessToken, pos, undefined, basic) : basic;
    dispatch({ type: "BOOKING", value });
    setUncertain(false);
    if (pos && value.status === "PAID")
      navigate(`/pos/bookings/${value.bookingId}/receipt`);
  }
  useEffect(() => {
    if (pos || !booking || booking.status !== "PENDING") return;
    let active = true;
    let running = false;
    let controller: AbortController | undefined;
    const read = async () => {
      if (running || document.visibilityState === "hidden") return;
      running = true;
      controller = new AbortController();
      try {
        const basic = await bookingsApi.read(booking.bookingId, session.accessToken, controller.signal, booking);
        const value = basic.status === "PAID" ? await bookingsApi.get(booking.bookingId, session.accessToken, false, controller.signal, basic) : basic;
        if (active) dispatch({ type: "BOOKING", value });
      } catch (e) {
        if (active && !controller.signal.aborted)
          setError((e as Error).message);
      } finally {
        running = false;
      }
    };
    void read();
    const timer = setInterval(read, 3000);
    window.addEventListener("focus", read);
    document.addEventListener("visibilitychange", read);
    return () => {
      active = false;
      controller?.abort();
      clearInterval(timer);
      window.removeEventListener("focus", read);
      document.removeEventListener("visibilitychange", read);
    };
  }, [
    booking?.bookingId,
    booking?.status,
    url,
    pos,
    session.accessToken,
    dispatch,
  ]);
  async function action(work: () => Promise<void>, payment = false) {
    if (writing.current) return;
    writing.current = true;
    setBusy(true);
    setError("");
    try {
      await work();
    } catch (e) {
      setError((e as Error).message);
      if (!booking && e instanceof ApiError && e.status >= 400 && e.status < 500 && e.code !== "BOOKING_ALREADY_EXISTS")
        dispatch({ type: "CREATE_INTENT", value: null });
      if (payment && booking) {
        setUncertain(true);
        try {
          await reload();
        } catch {
          setError(
            "Chưa xác nhận kết quả thanh toán. Đọc lại đơn trước khi thử tiếp.",
          );
        }
      }
    } finally {
      writing.current = false;
      setBusy(false);
    }
  }
  async function pay(kind: "cash" | "vietqr" | "confirm" | "vnpay") {
    if (!booking) return;
    await action(
      async () => {
        let updatedBooking = booking;
        if (kind === "cash") {
          const payment = await posApi.cash(
            booking.bookingId,
            Number(cash),
            age,
            session.accessToken,
          );
          updatedBooking = { ...booking, payment, status: "PAID" };
          dispatch({ type: "BOOKING", value: updatedBooking });
        }
        if (kind === "vietqr") {
          const payment = await posApi.vietqr(
            booking.bookingId,
            age,
            session.accessToken,
          );
          updatedBooking = { ...booking, payment };
          dispatch({ type: "BOOKING", value: updatedBooking });
        }
        if (kind === "confirm") {
          const payment = await posApi.confirm(
            booking.bookingId,
            session.accessToken,
          );
          updatedBooking = { ...booking, payment, status: "PAID" };
          dispatch({ type: "BOOKING", value: updatedBooking });
        }
        if (kind === "vnpay") {
          const value = await bookingsApi.vnpay(
            booking.bookingId,
            session.accessToken,
          );
          const link = new URL(value.paymentUrl, location.origin);
          if (link.protocol !== "https:" && link.origin !== location.origin)
            throw new Error("Địa chỉ thanh toán không hợp lệ");
          setUrl(link.href);
        }
        const basic = await bookingsApi.read(booking.bookingId, session.accessToken, undefined, updatedBooking);
        const value = basic.status === "PAID" ? await bookingsApi.get(booking.bookingId, session.accessToken, pos, undefined, basic) : basic;
        dispatch({ type: "BOOKING", value });
        setUncertain(false);
        if (pos && value.status === "PAID")
          navigate(`/pos/bookings/${value.bookingId}/receipt`);
      },
      kind === "cash" || kind === "confirm",
    );
  }
  const pending = !booking || booking.status === "PENDING";
  const receivingTicket = booking?.status === "PAID" && !pos;
  return (
    <section>
      <ol className="booking-steps" aria-label="Tiến trình đặt vé"><li>1. Chọn suất</li><li>2. Chọn ghế</li><li>3. Bắp nước</li><li aria-current={!receivingTicket ? "step" : undefined}>4. Thanh toán</li><li aria-current={receivingTicket ? "step" : undefined}>5. Nhận vé</li></ol>
      <h2>{receivingTicket ? "Nhận vé điện tử" : `Thanh toán ${pos ? "tại quầy" : "trực tuyến"}`}</h2>
      <Feedback error={error || hold.error} />
      {(hold.hold || booking?.holdUntil) && pending && (
        <HoldCountdown
          seconds={seconds}
          pos={pos && Boolean(hold.hold)}
          extensionUsed={hold.hold?.extensionUsed ?? true}
          waiting={booking?.payment?.paymentMethod === "VIETQR"}
          busy={hold.busy}
          onExtend={hold.extend}
        />
      )}
      {!receivingTicket && <OrderSummary booking={booking} quote={state.quote} cart={state.cart} items={concessions.data} />}
      {!booking && <Link to={pos ? `/pos/showtimes/${state.showtime!.id}` : `/showtimes/${state.showtime!.id}/book`}>Quay lại chọn ghế và F&B</Link>}
      {booking?.status === "PENDING" && <p>Đơn đã tạo. Hoàn tất thanh toán trước khi thời gian giữ ghế kết thúc.</p>}
      {pending && (
        <AgeConfirmation
          rating={state.showtime!.ageRating}
          pos={pos}
          value={age}
          onChange={setAge}
        />
      )}
      {!booking ? (
        <button
          className="primary"
          disabled={busy || (!state.creation && (seconds === 0 || !canPay))}
          onClick={() =>
            action(async () => {
              const seatLabels = new Map(
                (hold.map?.seats ?? []).map((s) => [s.seatId, `${s.row}${s.number}`]),
              );
              const intent = state.creation ?? {
                showtimeId: state.showtime!.id,
                holdToken: state.hold!.holdToken,
                concessions: state.cart,
                ageConfirmed: pos ? undefined : age,
              };
              dispatch({ type: "CREATE_INTENT", value: intent });
              const value = await bookingsApi.create(
                intent,
                session.accessToken,
                {
                  channel: pos ? "POS" : "ONLINE",
                  showtime: state.showtime!,
                  holdUntil: state.hold?.holdUntil,
                  plannedSeats: (state.quote?.tickets ?? []).map((t) => ({
                    seatId: t.seatId,
                    seatLabel: seatLabels.get(t.seatId) ?? `Ghế ${t.seatId}`,
                    price: t.finalPrice,
                  })),
                  concessions: state.cart.map((c) => {
                    const item = concessions.data?.find((i) => i.id === c.itemId);
                    return {
                      itemId: c.itemId,
                      name: item?.name ?? `Món #${c.itemId}`,
                      quantity: c.quantity,
                      unitPriceSnapshot: item?.price ?? 0,
                    };
                  }),
                },
              );
              dispatch({ type: "BOOKING", value });
              sessionStorage.setItem(`cinema:pending:${session.user.id}`, String(value.bookingId));
              navigate(`${pos ? "/pos/checkout" : "/checkout"}?booking=${value.bookingId}`, { replace: true });
            })
          }
        >
          {state.creation ? "Kiểm tra kết quả tạo đơn" : "Tạo đơn và xem tổng"}
        </button>
      ) : (
        <>
          {booking.status === "PAID" ? (
            <>
              <p className="receipt-success">Thanh toán đã hoàn tất. Vé điện tử đã sẵn sàng để quét tại rạp.</p>
              <TicketView booking={booking} />
              {!pos && <div className="receipt-actions"><Link className="button primary" to="/bookings">Xem lịch sử đặt vé</Link><Link className="button" to="/movies">Đặt vé khác</Link></div>}
            </>
          ) : booking.status === "EXCEPTION_PENDING_DECISION" ? (
            <p role="alert">
              Hệ thống đã ghi nhận thanh toán nhưng chưa phát hành vé. Đơn đã được chuyển tới nhân viên hỗ trợ; vui lòng liên hệ quầy vé.
            </p>
          ) : booking.status === "CANCELLED" ? (
            <p role="status">Đơn đã đóng. Chọn lại suất chiếu để đặt vé.</p>
          ) : (
            <>
              {pos ? (
                <>
                  <label>
                    Tiền khách đưa
                    <input
                      type="number"
                      min={booking.totalAmount}
                      value={cash}
                      onChange={(e) => setCash(e.target.value)}
                    />
                  </label>
                  <p>
                    Tiền thừa dự kiến:{" "}
                    {money(Math.max(0, Number(cash) - booking.totalAmount))}
                  </p>
                  <button
                    disabled={
                      busy ||
                      uncertain ||
                      !canPay ||
                      seconds === 0 ||
                      Number(cash) < booking.totalAmount
                    }
                    onClick={() => pay("cash")}
                  >
                    Thu tiền mặt
                  </button>
                  <button
                    disabled={
                      busy || uncertain || !canPay || seconds === 0
                    }
                    onClick={() => pay("vietqr")}
                  >
                    Tạo VietQR
                  </button>
                  {booking.payment?.qrPayload && (
                    <>
                      <dl className="payment-transfer-info">
                        <div><dt>Ngân hàng</dt><dd>{booking.payment.bankName}</dd></div>
                        <div><dt>Tài khoản nhận</dt><dd>{booking.payment.bankAccount}</dd></div>
                        <div><dt>Nội dung chuyển khoản</dt><dd>{booking.payment.transferContent}</dd></div>
                      </dl>
                      <Qr payload={booking.payment.qrPayload} />

                      <label className="checkbox">
                        <input
                          type="checkbox"
                          checked={received}
                          onChange={(e) => setReceived(e.target.checked)}
                        />
                        Đã kiểm tra tiền vào tài khoản
                      </label>
                      <button
                        disabled={
                          busy || uncertain || !received || seconds === 0
                        }
                        onClick={() => pay("confirm")}
                      >
                        Xác nhận đã nhận tiền
                      </button>
                    </>
                  )}
                </>
              ) : (
                <>
                  <button
                    disabled={busy || !canPay || seconds === 0 || !!url}
                    onClick={() => pay("vnpay")}
                  >
                    Tạo liên kết VNPay
                  </button>
                  {url && (
                    <a
                      className="button primary"
                      href={url}
                      target="_blank"
                      rel="noreferrer"
                    >
                      Mở VNPay
                    </a>
                  )}
                  {testPaymentMode.data?.enabled && (
                    <div className="test-payment-box">
                      <p>Chế độ thử nghiệm trên máy này. Nút dưới đây chỉ mô phỏng kết quả thanh toán để kiểm tra phát hành vé.</p>
                      <button
                        disabled={busy || uncertain || !canPay || seconds === 0}
                        onClick={() => action(async () => {
                          await testPaymentsApi.complete(booking.bookingId, session.accessToken);
                          await reload();
                        }, true)}
                      >
                        Mô phỏng thanh toán thành công
                      </button>
                    </div>
                  )}
                    <p>Giữ trang này mở. Vé sẽ hiện sau khi VNPay xác nhận thanh toán.</p>
                </>
              )}
              <div className="toolbar">
                <button disabled={busy} onClick={() => action(reload)}>
                  Cập nhật trạng thái
                </button>
              </div>
            </>
          )}
        </>
      )}
    </section>
  );
}
export { PaymentReturnPage } from "./PaymentReturnPage";
export function BookingHistoryPage() {
  const { session } = useAuth();
  const pos = session.user.role === "STAFF";
  const [params, setParams] = useSearchParams();
  const [page, setPage] = useState(Number(params.get("page") ?? 0)),
    [code, setCode] = useState(params.get("bookingCode") ?? ""),
    [date, setDate] = useState(params.get("date") ?? ""),
    [method, setMethod] = useState(params.get("paymentMethod") ?? "");
  useEffect(() => { setParams({ page: String(page), ...(pos ? { bookingCode: code, date, paymentMethod: method } : {}) }, { replace: true }); }, [page, code, date, method, pos, setParams]);
  const result = useQuery(
    `history:${pos}:${page}:${code}:${date}:${method}`,
    (s) =>
      bookingsApi.list(
        session.accessToken,
        pos,
        { page, bookingCode: code, date, paymentMethod: method },
        s,
      ),
  );
  return (
    <section>
      <h2>{pos ? "Giao dịch POS" : "Lịch sử đặt vé"}</h2>
      {pos && (
        <div className="toolbar">
          <label>
            Mã đơn
            <input
              value={code}
              onChange={(e) => {
                setCode(e.target.value);
                setPage(0);
              }}
            />
          </label>
          <label>
            Ngày
            <input
              type="date"
              value={date}
              onChange={(e) => {
                setDate(e.target.value);
                setPage(0);
              }}
            />
          </label>
          <label>
            Thanh toán
            <select
              value={method}
              onChange={(e) => {
                setMethod(e.target.value);
                setPage(0);
              }}
            >
              <option value="">Tất cả</option>
              <option>CASH</option>
              <option>VIETQR</option>
            </select>
          </label>
        </div>
      )}
      <Feedback
        {...result}
        empty={result.data?.data.length === 0}
        emptyTitle={pos ? undefined : "Chưa có đơn đặt vé"}
        emptyMessage={pos ? undefined : "Đơn và vé của bạn sẽ xuất hiện ở đây sau khi đặt thành công."}
        retry={result.refetch}
      />
      {Boolean(result.data?.data.length) && (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Mã đơn</th>
                <th>Phim</th>
                <th>Suất chiếu</th>
                <th>Trạng thái</th>
                <th>Thanh toán</th>
                <th>Tổng</th>
                <th>Hoàn tiền</th>
              </tr>
            </thead>
            <tbody>
              {result.data?.data.map((b) => (
                <tr key={b.bookingId}>
                  <td>
                    <Link
                      to={`${pos ? "/transactions" : "/bookings"}/${b.bookingId}`}
                    >
                      {b.bookingCode}
                    </Link>
                  </td>
                  <td>{b.showtime.movieTitle || "—"}</td>
                  <td>{b.showtime.startTime ? dateTime(b.showtime.startTime) : "—"}</td>
                  <td>{bookingStatusLabel(b.status)}</td>
                  <td>{paymentMethodLabel(b.payment?.paymentMethod)}</td>
                  <td>{money(b.totalAmount)}</td>
                  <td>{refundStatusLabel(b.refundStatus)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {result.data && result.data.meta.totalElements > 0 && (
        <Pagination meta={result.data.meta} onPage={setPage} />
      )}
    </section>
  );
}
export function BookingDetailPage() {
  const id = Number(useParams().id),
    { session } = useAuth();
  const pos = session.user.role === "STAFF";
  const result = useQuery("booking:" + id, (s) =>
    bookingsApi.get(id, session.accessToken, pos, s),
  );
  return (
    <section>
      <Feedback {...result} retry={result.refetch} />
      {result.data && (
        <>
          <OrderSummary booking={result.data} />
          {result.data.refundStatus && result.data.refundStatus !== "NONE" && (
            <p>Hoàn tiền: {refundStatusLabel(result.data.refundStatus)}</p>
          )}
          {result.data.status === "PENDING" && (
            <p>
              Đang chờ thanh toán. <Link to={`${pos ? "/pos/checkout" : "/checkout"}?booking=${id}`}>Tiếp tục thanh toán</Link>
            </p>
          )}
          {result.data.status === "EXCEPTION_PENDING_DECISION" && (
            <p role="alert">
              Thanh toán đã được ghi nhận nhưng vé chưa được phát hành. Đơn cần Quản lý rạp và nhân viên hỗ trợ xử lý tại quầy.
            </p>
          )}
          <TicketView booking={result.data} />
          {pos && <Link to={`/refunds?bookingCode=${encodeURIComponent(result.data.bookingCode)}`}>Yêu cầu hoàn vé tại quầy</Link>}
          {result.data.refunds?.map((r) => (
            <p key={r.refundId}>
              Đã hoàn {money(r.refundAmount)} cho vé {r.ticketIds.join(", ")}
            </p>
          ))}
        </>
      )}
    </section>
  );
}
