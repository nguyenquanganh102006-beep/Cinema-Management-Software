import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError } from "../api";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { catalogApi } from "../api/catalog";
import { holdsApi } from "../api/holds";
import { bookingsApi } from "../api/bookings";
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
import { CheckoutRecovery } from "./CheckoutRecovery";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { dateTime, money, today } from "../utils/format";
import type { Booking, Showtime } from "../types";
export function MovieListPage() {
  const { session } = useAuth();
  const [keyword, setKeyword] = useState(""),
    [page, setPage] = useState(0);
  const result = useQuery(`movies:${keyword}:${page}`, (s) =>
    catalogApi.movies(session.accessToken, { keyword, active: true, page }, s),
  );
  return (
    <section>
      <h2>Khám phá phim</h2>
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
      <div className="cards">
        {result.data?.data.map((movie) => (
          <article className="card" key={movie.id}>
            {movie.poster && (
              <img className="poster" src={movie.poster} alt={`Áp phích ${movie.title}`} onError={(e) => { e.currentTarget.style.display = "none"; }} />
            )}
            <div className="poster-fallback" aria-hidden="true">CINEMA · {movie.ageRating}</div>
            <h3>
              <Link to={`/movies/${movie.id}`}>{movie.title}</Link>
            </h3>
            <p>
              {movie.ageRating} · {movie.durationMinutes} phút · {movie.genre}
            </p>
            <p>{movie.description}</p>
          </article>
        ))}
      </div>
      {result.data && <Pagination meta={result.data.meta} onPage={setPage} />}
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
  const shows = useQuery(`movie-shows:${id}:${date}`, (s) =>
    catalogApi.showtimes(session.accessToken, { movieId: id, date }, s),
  );
  return (
    <section>
      <Feedback {...movie} retry={movie.refetch} />
      {movie.data && (
        <>
          <h2>{movie.data.title}</h2>
          <p>{movie.data.description}</p>
          <p>
            {movie.data.ageRating} · {movie.data.durationMinutes} phút ·{" "}
            {movie.data.director} · {movie.data.language}
          </p>
          {movie.data.trailer && (
            <a href={movie.data.trailer} target="_blank" rel="noreferrer">
              Xem trailer
            </a>
          )}
        </>
      )}
      <label>
        Ngày chiếu
        <input
          type="date"
          value={date}
          onChange={(e) => setDate(e.target.value)}
        />
      </label>
      <Feedback
        {...shows}
        empty={shows.data?.length === 0}
        retry={shows.refetch}
      />
      {shows.data?.map((show) => (
        <ShowtimeCard key={show.id} show={show} />
      ))}
    </section>
  );
}
function ShowtimeCard({ show }: { show: Showtime }) {
  const { session } = useAuth();
  const { state, dispatch } = useBookingFlow();
  const [confirm, setConfirm] = useState(false);
  const navigate = useNavigate();
  const [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const pos = session.user.role === "STAFF";
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
    <article className="card">
      <h3>
        {show.movieTitle} · {show.ageRating}
      </h3>
      <p>
        {show.auditoriumName} · {show.auditoriumType}
      </p>
      <p>
        {dateTime(show.startTime)} → {dateTime(show.endTime)}
      </p>
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
  const result = useQuery(`shows:${date}:${movieId}`, (s) =>
    catalogApi.showtimes(
      session.accessToken,
      { date, movieId: movieId ? Number(movieId) : undefined },
      s,
    ),
  );
  return (
    <section>
      <h2>Chọn phim và suất</h2>
      <div className="toolbar">
        <label>
          Ngày chiếu
          <input
            type="date"
            value={date}
            onChange={(e) => setDate(e.target.value)}
          />
        </label>
        <label>
          Phim
          <select value={movieId} onChange={(e) => setMovieId(e.target.value)}>
            <option value="">Tất cả</option>
            {movies.data?.map((m) => (
              <option key={m.id} value={m.id}>
                {m.title}
              </option>
            ))}
          </select>
        </label>
      </div>
      <Feedback
        {...result}
        empty={result.data?.length === 0}
        retry={result.refetch}
      />
      <div className="cards">
        {result.data?.map((show) => (
          <ShowtimeCard key={show.id} show={show} />
        ))}
      </div>
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
  const [quoteError, setQuoteError] = useState("");
  const [quoteVersion, setQuoteVersion] = useState(0);
  const pos = session.user.role === "STAFF";
  useEffect(() => {
    if (show.data) dispatch({ type: "SHOWTIME", value: show.data });
  }, [show.data, dispatch]);
  const targetSeats = seatHold.hold?.seatIds ?? state.selected;
  const seatIdsKey = targetSeats.join(",");
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
      <ol className="booking-steps" aria-label="Tiến trình đặt vé"><li>1. Chọn suất</li><li aria-current="step">2. Ghế và F&B</li><li>3. Thanh toán</li><li>4. Nhận vé</li></ol>
      <h2>
        {show.data?.movieTitle ?? "Chọn ghế"} · {show.data?.ageRating}
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
          {seatHold.map && (
            <SeatMap
              seats={seatHold.map.seats}
              selected={state.selected}
              locked={!!seatHold.hold || seatHold.busy || !!state.creation}
              onSelect={(value) => dispatch({ type: "SELECT", value })}
            />
          )}
          <Feedback {...items} retry={items.refetch} />
          {items.data && (
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
            items={items.data}
            cart={state.cart}
          />
          <div className="booking-actions">
          <p className="booking-action-hint">{targetSeats.length} ghế · {state.quote ? `Tạm tính ${money(estimatedTotal)}` : "Chọn ghế để xem giá"}</p>
          {seatHold.hold ? (
            <>
              <button disabled={seatHold.busy || !!state.creation} onClick={() => setConfirm(true)}>
                Đổi ghế
              </button>
              {seatHold.seconds > 0 && (
                <Link
                  className="button primary"
                  to={pos ? "/pos/checkout" : "/checkout"}
                >
                  Tiếp tục thanh toán
                </Link>
              )}
            </>
          ) : (
            <button
              className="primary"
              disabled={seatHold.busy || !state.selected.length || !!state.creation}
              onClick={seatHold.create}
            >
              Giữ các ghế đã chọn
            </button>
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
  return (
    <section>
      <ol className="booking-steps" aria-label="Tiến trình đặt vé"><li>1. Chọn suất</li><li>2. Ghế và F&B</li><li aria-current="step">3. Thanh toán</li><li>4. Nhận vé</li></ol>
      <h2>Thanh toán {pos ? "tại quầy" : "Online"}</h2>
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
      <OrderSummary booking={booking} quote={state.quote} cart={state.cart} items={concessions.data} />
      {!booking && <Link to={pos ? `/pos/showtimes/${state.showtime!.id}` : `/showtimes/${state.showtime!.id}/book`}>Quay lại chọn ghế và F&B</Link>}
      {booking?.status === "PENDING" && <p>Đơn đã được tạo. Bạn có thể thanh toán hoặc đọc lại trạng thái; ghế sẽ tự giải phóng nếu hết hạn.</p>}
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
          {state.creation ? "Đọc lại kết quả tạo đơn" : "Tạo đơn · lấy tổng thanh toán"}
        </button>
      ) : (
        <>
          {booking.status === "PAID" ? (
            <TicketView booking={booking} />
          ) : booking.status === "EXCEPTION_PENDING_DECISION" ? (
            <p role="alert">
              Thanh toán cần xử lý thủ công: {booking.exceptionReason}. Liên hệ
              quầy vé.
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
                      <p>
                        {booking.payment.bankName} ·{" "}
                        {booking.payment.bankAccount} ·{" "}
                        {booking.payment.transferContent}
                      </p>
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
                  <p>Giữ tab này mở. Kết quả chỉ được xác nhận từ máy chủ.</p>
                </>
              )}
              <div className="toolbar">
                <button disabled={busy} onClick={() => action(reload)}>
                  Đọc lại trạng thái đơn
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
        retry={result.refetch}
      />
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Mã đơn</th>
              <th>Trạng thái</th>
              <th>Thanh toán</th>
              <th>Tổng</th>
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
                <td>{b.status}</td>
                <td>{b.payment?.paymentMethod ?? "Chưa thanh toán"}</td>
                <td>{money(b.totalAmount)}</td>
                <td>{b.refundStatus ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {result.data && <Pagination meta={result.data.meta} onPage={setPage} />}
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
      <h2>Chi tiết giao dịch</h2>
      <Feedback {...result} retry={result.refetch} />
      {result.data && (
        <>
          <OrderSummary booking={result.data} />
          {result.data.refundStatus && <p>Hoàn vé: {result.data.refundStatus}</p>}
          {result.data.status === "PENDING" && (
            <p>
              Đang chờ thanh toán. <Link to={`${pos ? "/pos/checkout" : "/checkout"}?booking=${id}`}>Tiếp tục thanh toán</Link>
            </p>
          )}
          {result.data.status === "EXCEPTION_PENDING_DECISION" && (
            <p role="alert">
              Cần xử lý thủ công: {result.data.exceptionReason}
            </p>
          )}
          <TicketView booking={result.data} />
          {pos && <Link to={`/refunds?bookingCode=${encodeURIComponent(result.data.bookingCode)}`}>Yêu cầu hoàn vé tại quầy</Link>}
          {result.data.refunds?.map((r) => (
            <p key={r.refundId}>
              Đã hoàn {money(r.refundAmount)} · Vé {r.ticketIds.join(", ")}
            </p>
          ))}
        </>
      )}
    </section>
  );
}
