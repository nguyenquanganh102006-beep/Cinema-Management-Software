import type { Seat } from "../types";
export function PhysicalSeatLayout({ seats, onEdit }: { seats: Seat[]; onEdit: (seat: Seat) => void }) {
  const rows = [...new Set(seats.map((s) => s.row))].sort();
  return <section aria-label="Sơ đồ ghế vật lý"><div className="screen">MÀN HÌNH</div><div className="seat-scroll">{rows.map((row) => {
    const members = seats.filter((s) => s.row === row).sort((a, b) => a.number - b.number);
    const byNumber = new Map(members.map((s) => [s.number, s]));
    const count = Math.max(...members.map((s) => s.number));
    return <div className="seat-row" key={row}><strong>{row}</strong>{Array.from({ length: count }, (_, i) => {
      const seat = byNumber.get(i + 1);
      return seat ? <button key={i} className={`seat physical ${seat.seatType.toLowerCase()}`} aria-label={`Sửa ghế ${row}${seat.number} · ${seat.seatType}${seat.pairSeatId ? " · Đã ghép cặp" : ""}`} onClick={() => onEdit(seat)}>{seat.number}{seat.seatType === "VIP" ? "★" : seat.seatType === "SWEETBOX" ? "♥" : ""}</button> : <span className="seat-gap" key={i} aria-hidden="true" />;
    })}</div>;
  })}</div><p className="muted">Ghế vật lý · Thường · ★ VIP · ♥ Sweetbox. Chọn ghế để sửa vị trí hoặc loại; trạng thái bán vé thuộc từng suất.</p></section>;
}
