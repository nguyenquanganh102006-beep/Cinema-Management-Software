import { useEffect } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useBookingFlow } from "../booking/BookingFlowProvider";
import { useQuery } from "../hooks/useQuery";
import { bookingsApi } from "../api/bookings";
import { catalogApi } from "../api/catalog";
import { holdsApi } from "../api/holds";
import { Feedback } from "../components/Feedback";
export function CheckoutRecovery() {
  const { session } = useAuth();
  const { dispatch } = useBookingFlow();
  const navigate = useNavigate();
  const pos = session.user.role === "STAFF";
  const rawId = new URLSearchParams(location.search).get("booking") ?? sessionStorage.getItem(`cinema:pending:${session.user.id}`);
  const id = Number(rawId);
  const result = useQuery(`recover:${id}`, async (signal) => {
    if (!id || !Number.isSafeInteger(id) || id <= 0) return null;
    const booking = await bookingsApi.read(id, session.accessToken, signal);
    if (booking.status !== "PENDING") return { booking, showtime: null, hold: null };
    const [showtime, map] = await Promise.all([
      catalogApi.showtime(booking.showtime.id, session.accessToken, signal),
      holdsApi.map(booking.showtime.id, session.accessToken, signal),
    ]);
    const ids = booking.plannedSeats.map((s) => s.seatId);
    const hold = map.ownHolds.find((h) => ids.length === h.seatIds.length && ids.every((id) => h.seatIds.includes(id)));
    return { booking: { ...booking, showtime, serverTime: map.serverTime, holdUntil: hold?.holdUntil ?? booking.holdUntil }, showtime, hold };
  });
  useEffect(() => {
    const data = result.data;
    if (!data) return;
    if (data.booking.status === "PAID") {
      navigate(pos ? `/pos/bookings/${id}/receipt` : `/bookings/${id}`, { replace: true });
    } else if (data.showtime && data.booking.holdUntil && Date.parse(data.booking.holdUntil) > Date.parse(data.booking.serverTime)) {
      dispatch({ type: "SHOWTIME", value: data.showtime });
      if (data.hold) dispatch({ type: "HOLD", value: data.hold });
      dispatch({ type: "BOOKING", value: data.booking });
    }
  }, [result.data, dispatch, id, navigate, pos]);
  return <section><h2>Khôi phục thanh toán</h2><Feedback {...result} retry={result.refetch} />{!result.loading && !result.error && <>
    <p>{id ? "Đơn không còn phiên giữ ghế để tiếp tục. Kiểm tra trạng thái và vé trong lịch sử, hoặc chọn suất khác." : "Chọn suất chiếu và giữ ghế để tiếp tục."}</p>
    <Link className="button" to={pos ? "/pos" : "/movies"}>Chọn suất chiếu</Link>{" · "}<Link to={pos ? "/transactions" : "/bookings"}>Xem lịch sử đặt vé</Link>
  </>}</section>;
}
