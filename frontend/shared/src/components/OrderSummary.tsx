import type { Quote, Booking, Concession, CartLine } from "../types";
import { money } from "../utils/format";
export function OrderSummary({
  quote,
  booking,
  items = [],
  cart = [],
}: {
  quote?: Quote | null;
  booking?: Booking | null;
  items?: Concession[];
  cart?: CartLine[];
}) {
  const fnbTotal = cart.reduce((sum, c) => {
    const item = items.find((i) => i.id === c.itemId);
    return sum + (item ? item.price * c.quantity : 0);
  }, 0);
  const estimatedTotal = (quote?.total ?? 0) + fnbTotal;
  return (
    <section className="order-summary">
      <h2>{booking ? "Đơn " + booking.bookingCode : "Tạm tính"}</h2>
      {booking ? (
        <>
          <p>Trạng thái: {booking.status}</p>
          {booking.plannedSeats.map((s) => (
            <p key={s.seatId}>
              {s.seatLabel} · {money(s.price)}
            </p>
          ))}
          {booking.concessions.map((c) => (
            <p key={c.itemId}>
              {c.name} × {c.quantity} · {money(c.unitPriceSnapshot)}
            </p>
          ))}
          <strong>Tổng: {money(booking.totalAmount)}</strong>
        </>
      ) : (
        <>
          {quote?.tickets.map((t) => (
            <p key={t.seatId}>
              Ghế {t.seatId} · {money(t.finalPrice)}
            </p>
          ))}
          <p>Vé: {quote ? money(quote.total) : "Chọn và giữ ghế để lấy giá"}</p>
          {cart.map((c) => {
            const item = items.find((i) => i.id === c.itemId);
            return (
              <p key={c.itemId}>
                {item?.name} × {c.quantity}
                {item ? ` · ${money(item.price * c.quantity)}` : ""}
              </p>
            );
          })}
          {(quote || fnbTotal > 0) && (
            <p>
              <strong>Tạm tính: {money(estimatedTotal)}</strong>
            </p>
          )}
          <p className="muted">
            Tổng thanh toán được máy chủ xác nhận khi tạo đơn.
          </p>
        </>
      )}
    </section>
  );
}
