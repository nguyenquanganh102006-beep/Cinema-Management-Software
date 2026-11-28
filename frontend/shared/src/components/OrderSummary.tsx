import type { Quote, Booking, Concession, CartLine } from "../types";
import { money } from "../utils/format";

function seatName(label: string | undefined, seatId: number) {
  const value = (label ?? String(seatId))
    .replace(/^ghế\s*/i, "")
    .replace(/^#/, "")
    .trim();
  return `Ghế ${value || seatId}`;
}

export function OrderSummary({
  quote,
  booking,
  seatLabels,
  items = [],
  cart = [],
}: {
  quote?: Quote | null;
  booking?: Booking | null;
  seatLabels?: Map<number, string>;
  items?: Concession[];
  cart?: CartLine[];
}) {
  const fnbTotal = cart.reduce((sum, c) => {
    const item = items.find((i) => i.id === c.itemId);
    return sum + (item ? item.price * c.quantity : 0);
  }, 0);
  const estimatedTotal = (quote?.total ?? 0) + fnbTotal;
  const bookingStatus = booking && {
    PENDING: "Chờ thanh toán",
    PAID: "Đã thanh toán",
    CANCELLED: "Đã kết thúc",
    EXCEPTION_PENDING_DECISION: "Chờ rạp hỗ trợ",
  }[booking.status];
  return (
    <section className="order-summary">
      <h2>{booking ? "Đơn " + booking.bookingCode : "Tạm tính"}</h2>
      {booking ? (
        <>
          <p>Trạng thái: {bookingStatus}</p>
          <ul className="order-lines">
            {booking.plannedSeats.map((s) => (
              <li key={s.seatId}>
                <span>{seatName(s.seatLabel, s.seatId)}</span>
                <strong>{money(s.price)}</strong>
              </li>
            ))}
            {booking.concessions.map((c) => (
              <li key={c.itemId}>
                <span>{c.name} × {c.quantity}</span>
                <strong>{money(c.unitPriceSnapshot * c.quantity)}</strong>
              </li>
            ))}
          </ul>
          <strong>Tổng: {money(booking.totalAmount)}</strong>
        </>
      ) : (
        <>
          <ul className="order-lines">
            {quote?.tickets.map((t) => (
              <li key={t.seatId}>
                <span>{seatName(seatLabels?.get(t.seatId), t.seatId)}</span>
                <strong>{money(t.finalPrice)}</strong>
              </li>
            ))}
            {cart.map((c) => {
              const item = items.find((i) => i.id === c.itemId);
              return (
                <li key={c.itemId}>
                  <span>{item?.name ?? "Món ăn"} × {c.quantity}</span>
                  <strong>{item ? money(item.price * c.quantity) : "—"}</strong>
                </li>
              );
            })}
          </ul>
          <p>Vé: {quote ? money(quote.total) : "Chọn ghế để xem giá"}</p>
          {(quote || fnbTotal > 0) && (
            <p>
              <strong>Tạm tính: {money(estimatedTotal)}</strong>
            </p>
          )}
          <p className="muted">
            Tổng cuối cùng sẽ hiện sau khi tạo đơn.
          </p>
        </>
      )}
    </section>
  );
}
