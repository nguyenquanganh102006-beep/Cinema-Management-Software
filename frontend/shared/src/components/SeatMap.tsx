import type { Seat } from "../types";
export function SeatMap({
  seats,
  selected,
  locked,
  onSelect,
}: {
  seats: Seat[];
  selected: number[];
  locked: boolean;
  onSelect: (ids: number[]) => void;
}) {
  const rows = [...new Set(seats.map((s) => s.row))];
  function toggle(seat: Seat) {
    const pair =
      seat.seatType === "SWEETBOX"
        ? seats.find((s) => s.seatId === seat.pairSeatId)
        : undefined;
    if (seat.seatType === "SWEETBOX" && (!pair || pair.status !== "AVAILABLE"))
      return;
    const ids = pair ? [seat.seatId, pair.seatId] : [seat.seatId];
    onSelect(
      selected.includes(seat.seatId)
        ? selected.filter((i) => !ids.includes(i))
        : [...new Set([...selected, ...ids])],
    );
  }
  return (
    <section aria-label="Sơ đồ ghế">
      <div className="screen">MÀN HÌNH</div>
      <div className="seat-scroll">
        {rows.map((row) => (
          <div className="seat-row" key={row}>
            <strong>{row}</strong>
            {seats
              .filter((s) => s.row === row)
              .map((s) => {
                const status = s.heldByMe
                  ? "Của bạn"
                  : s.status === "BOOKED"
                    ? "Đã bán"
                    : s.status === "HOLDING"
                      ? "Đang giữ"
                      : selected.includes(s.seatId)
                        ? "Đã chọn"
                        : "Trống";
                const pair = seats.find((p) => p.seatId === s.pairSeatId);
                return (
                  <button
                    key={s.seatId}
                    className={`seat ${s.seatType.toLowerCase()} ${s.heldByMe ? "mine" : s.status.toLowerCase()} ${selected.includes(s.seatId) ? "selected" : ""}`}
                    aria-label={`${s.row}${s.number}, ${s.seatType}, ${status}`}
                    aria-pressed={selected.includes(s.seatId)}
                    disabled={
                      locked ||
                      s.status !== "AVAILABLE" ||
                      (s.seatType === "SWEETBOX" &&
                        (!pair || pair.status !== "AVAILABLE"))
                    }
                    onClick={() => toggle(s)}
                  >
                    {s.number}
                    <span aria-hidden="true">{s.heldByMe ? " ✓" : s.status === "BOOKED" ? " ×" : s.status === "HOLDING" ? " …" : selected.includes(s.seatId) ? " ✓" : ""}</span>
                  </button>
                );
              })}
          </div>
        ))}
      </div>
      <ul className="seat-legend" aria-label="Chú giải ghế">
        <li><span className="seat-legend-swatch available" aria-hidden="true" /> Còn trống</li>
        <li><span className="seat-legend-swatch holding" aria-hidden="true" /> Đang giữ</li>
        <li><span className="seat-legend-swatch booked" aria-hidden="true" /> Đã bán</li>
        <li><span className="seat-legend-swatch selected" aria-hidden="true" /> Bạn đã chọn / đang giữ</li>
      </ul>
      <div className="seat-type-legend"><span><i className="vip" aria-hidden="true" /> VIP</span><span><i className="sweetbox" aria-hidden="true" /> Sweetbox, chọn theo cặp</span></div>
    </section>
  );
}
