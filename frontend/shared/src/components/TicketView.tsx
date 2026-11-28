import { useEffect, useState } from "react";
import QRCode from "qrcode";
import type { Booking } from "../types";
import { dateTime, money } from "../utils/format";
export function Qr({ payload }: { payload: string }) {
  const [url, setUrl] = useState("");
  useEffect(() => {
    let active = true;
    QRCode.toDataURL(payload, { width: 180 })
      .then((result) => {
        if (active) setUrl(result);
      })
      .catch(() => setUrl(""));
    return () => {
      active = false;
    };
  }, [payload]);
  return url ? <img src={url} alt="Mã QR vé điện tử" width={180} height={180} /> : null;
}
export function TicketView({ booking }: { booking: Booking }) {
  const bookingStatus = booking.status === "PAID" ? "Đã thanh toán"
    : booking.status === "PENDING" ? "Chờ thanh toán"
      : booking.status === "CANCELLED" ? "Đã kết thúc"
        : "Chờ rạp hỗ trợ";
  return (
    <section className="receipt">
      <header className="receipt-header">
        <div><span className="receipt-kicker">CINEMA</span><h2>Vé điện tử</h2></div>
        <span className={`receipt-status ${booking.status.toLowerCase()}`}>{bookingStatus}</span>
      </header>
      <div className="receipt-total"><span>Tổng thanh toán</span><strong>{money(booking.totalAmount)}</strong></div>
      {booking.tickets.map((t) => (
        <article className="ticket" key={t.ticketId}>
          <header className="ticket-heading">
            <div><span className="receipt-kicker">VÉ XEM PHIM</span><h3>{t.movieTitle}</h3></div>
            <span className={`receipt-status ${t.status.toLowerCase()}`}>{ticketStatusLabel(t.status)}</span>
          </header>
          <dl className="ticket-facts">
            <div><dt>Ngày giờ chiếu</dt><dd>{dateTime(t.startTime)}</dd></div>
            <div><dt>Phòng chiếu</dt><dd>{t.auditoriumName}</dd></div>
            <div><dt>Ghế</dt><dd>{t.seatLabel}</dd></div>
            <div><dt>Giá vé</dt><dd>{money(t.priceSnapshot)}</dd></div>
          </dl>
          <div className="ticket-qr-panel"><Qr payload={t.qrPayload} /><p>Đưa mã này cho nhân viên soát vé</p></div>
        </article>
      ))}
      {booking.concessions.length > 0 && <section className="receipt-fnb"><h3>Món đã đặt</h3><ul>{booking.concessions.map((c) => <li key={c.itemId}><span>{c.name}<small>{c.quantity} phần</small></span><strong>{money(c.unitPriceSnapshot * c.quantity)}</strong></li>)}</ul></section>}
      {booking.tickets.length > 0 && (
        <button className="no-print" onClick={() => window.print()}>
          In vé / hóa đơn
        </button>
      )}
    </section>
  );
}

function ticketStatusLabel(status: Booking["tickets"][number]["status"]) {
  return status === "VALID" ? "Còn hiệu lực"
    : status === "USED" ? "Đã sử dụng"
      : "Đã hủy";
}
