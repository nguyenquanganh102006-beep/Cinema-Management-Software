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
  return url ? <img src={url} alt="Mã QR vé" width={180} height={180} /> : null;
}
export function TicketView({ booking }: { booking: Booking }) {
  return (
    <section className="receipt">
      <h2>{booking.bookingCode}</h2>
      <p>
        {booking.status} · Tổng {money(booking.totalAmount)}
      </p>
      {booking.tickets.map((t) => (
        <article className="ticket" key={t.ticketId}>
          <h3>{t.movieTitle}</h3>
          <p>
            {t.auditoriumName} · {t.seatLabel} · {dateTime(t.startTime)}
          </p>
          <p>
            {t.ticketCode} · {t.status} · {money(t.priceSnapshot)}
          </p>
          <Qr payload={t.qrPayload} />
        </article>
      ))}
      {booking.concessions.map((c) => (
        <p key={c.itemId}>
          {c.name} × {c.quantity} · {money(c.unitPriceSnapshot)}
        </p>
      ))}
      {booking.tickets.length > 0 && (
        <button className="no-print" onClick={() => window.print()}>
          In vé / hóa đơn
        </button>
      )}
    </section>
  );
}
