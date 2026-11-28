import { useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../../auth/AuthProvider";
import { useQuery } from "../../hooks/useQuery";
import { reportApi } from "../../api/reports";
import { Feedback } from "../../components/Feedback";
import { money, dateTime, today } from "../../utils/format";
import type { Reports, ReportPeriod } from "../../types";
function daysBefore(value: string, days: number) {
  const date = new Date(`${value}T12:00:00Z`);
  date.setUTCDate(date.getUTCDate() - days);
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}-${String(date.getUTCDate()).padStart(2, "0")}`;
}
function bucketLabel(value: string, interval: Reports["timeSeries"]["interval"]) {
  const date = new Date(`${value}+07:00`);
  const options: Intl.DateTimeFormatOptions = interval === "HOUR"
    ? { day: "2-digit", month: "2-digit", hour: "2-digit" }
    : interval === "MONTH"
      ? { month: "short", year: "numeric" }
      : interval === "WEEK"
        ? { day: "2-digit", month: "2-digit" }
        : { day: "2-digit", month: "2-digit" };
  return new Intl.DateTimeFormat("vi-VN", { ...options, timeZone: "Asia/Ho_Chi_Minh" }).format(date);
}
function TimeSeries({ value }: { value: Reports["timeSeries"] }) {
  const max = Math.max(1, ...value.points.map((point) => Math.max(point.grossRevenue, point.refundAmount)));
  const gross = value.points.reduce((sum, point) => sum + point.grossRevenue, 0);
  const refunds = value.points.reduce((sum, point) => sum + point.refundAmount, 0);
  const refundTransactions = value.points.reduce((sum, point) => sum + point.refunds, 0);
  const transactions = value.points.reduce((sum, point) => sum + point.transactions, 0);
  const tickets = value.points.reduce((sum, point) => sum + point.ticketCount, 0);
  const intervalLabel = { HOUR: "theo giờ", DAY: "theo ngày", WEEK: "theo tuần", MONTH: "theo tháng" }[value.interval];
  return <>
    <Period value={value.period} />
    <p>Tiền thu và tiền hoàn được gom {intervalLabel} theo thời điểm giao dịch.</p>
    <Values values={{ "Đã thu": money(gross), "Đã hoàn": money(refunds), "Thu ròng": money(gross - refunds), "Giao dịch": transactions, "Vé đã phát hành": tickets, "Lần hoàn": refundTransactions }} />
    <div className="time-series-legend"><span><i /> Tiền thu</span><span><i className="refund" /> Tiền hoàn</span></div>
    <div className="time-series-chart" role="img" aria-label={`Biểu đồ tiền thu và tiền hoàn ${intervalLabel}`}>
      <div className="time-series-bars">
        {value.points.map((point) => <div className="time-series-bar" key={point.bucket} title={`${bucketLabel(point.bucket, value.interval)}, Thu ${money(point.grossRevenue)}, Hoàn ${money(point.refundAmount)}`}>
          <span className="time-series-bar-stack" aria-hidden="true">
            <i style={{ height: point.grossRevenue ? `${Math.max(2, point.grossRevenue / max * 100)}%` : "0%" }} />
            <i className="refund" style={{ height: point.refundAmount ? `${Math.max(2, point.refundAmount / max * 100)}%` : "0%" }} />
          </span>
          <span className="time-series-bar-label">{bucketLabel(point.bucket, value.interval)}</span>
        </div>)}
      </div>
    </div>
    <div className="table-scroll">
      <table><thead><tr><th>Thời gian</th><th>Giao dịch</th><th>Vé</th><th>Đã thu</th><th>Đã hoàn</th><th>Lần hoàn</th><th>Thu ròng</th></tr></thead>
        <tbody>{value.points.map((point) => <tr key={point.bucket}><td>{bucketLabel(point.bucket, value.interval)}</td><td>{point.transactions}</td><td>{point.ticketCount}</td><td>{money(point.grossRevenue)}</td><td>{money(point.refundAmount)}</td><td>{point.refunds}</td><td>{money(point.netRevenue)}</td></tr>)}</tbody>
      </table>
    </div>
  </>;
}
function Period({ value }: { value: ReportPeriod }) {
  return <div className="report-period" aria-label="Kỳ báo cáo"><span>{value.from} – {value.to}</span><span>{value.timezone}</span><span>{value.basis === "SHOWTIME_START" ? "Theo ngày bắt đầu suất chiếu" : "Theo ngày thanh toán, đã điều chỉnh hoàn vé"}</span><span>Chốt lúc {dateTime(value.asOf)}</span></div>;
}
function Region<T>({ title, load, periodKey, children }: { title: string; load: (signal: AbortSignal) => Promise<T>; periodKey: string; children: (data: T) => ReactNode }) {
  const result = useQuery(periodKey + title, load);
  return <section className="report-region" aria-label={title}><h3>{title}</h3><Feedback {...result} retry={result.refetch} />{result.data !== undefined && children(result.data)}</section>;
}
function Values({ values }: { values: Record<string, string | number> }) {
  return <dl className="metrics">{Object.entries(values).map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>;
}
function Ranking({ value }: { value: Reports["ranking"] }) {
  return <><Period value={value.period} /><Feedback empty={!value.movies.length} /><div className="table-scroll"><table><thead><tr><th>Phim</th><th>Vé ròng</th><th>Gộp</th><th>Hoàn</th><th>Ròng</th></tr></thead><tbody>{value.movies.map((movie) => <tr key={movie.movieId}><td>{movie.title}</td><td>{movie.ticketCount}</td><td>{money(movie.totalRevenue)}</td><td>{money(movie.refundAmount)}</td><td>{money(movie.revenue)}</td></tr>)}</tbody></table></div></>;
}
export function ReportsPage({ compact = false }: { compact?: boolean }) {
  const { session } = useAuth();
  const [from, setFrom] = useState(today), [to, setTo] = useState(today), [mode, setMode] = useState("range");
  function quickRange(days: number) {
    const end = today();
    setFrom(days === 1 ? end : daysBefore(end, days - 1));
    setTo(end);
    setMode("range");
  }
  const key = `${from}:${to}`;
  const valid = Boolean(from && to && from <= to);
  const args = { token: session.accessToken, from, to };
  return <section><h2>{compact ? "Doanh thu hôm nay" : "Báo cáo theo kỳ"}</h2>
    {!compact && <div className="toolbar">
      <div className="report-quick-ranges" aria-label="Chọn nhanh kỳ báo cáo">
        <button type="button" className={from === to && to === today() ? "selected" : ""} onClick={() => quickRange(1)}>Hôm nay</button>
        <button type="button" className={from === daysBefore(today(), 6) && to === today() ? "selected" : ""} onClick={() => quickRange(7)}>7 ngày</button>
        <button type="button" className={from === daysBefore(today(), 29) && to === today() ? "selected" : ""} onClick={() => quickRange(30)}>30 ngày</button>
      </div>
      <label>Kỳ báo cáo<select value={mode} onChange={(e) => { setMode(e.target.value); if (e.target.value === "day") setTo(from); }}><option value="range">Khoảng ngày</option><option value="day">Ngày</option><option value="month">Tháng</option></select></label>
      {mode === "month" ? <label>Tháng<input type="month" value={from.slice(0, 7)} onChange={(e) => { const month = e.target.value; if (!month) return; const [year, m] = month.split("-").map(Number); setFrom(month + "-01"); setTo(`${month}-${new Date(Date.UTC(year, m, 0)).getUTCDate()}`); }} /></label> : <>
        <label>Từ ngày<input type="date" value={from} onChange={(e) => { setFrom(e.target.value); if (mode === "day") setTo(e.target.value); }} /></label>
        {mode === "range" && <label>Đến ngày<input type="date" min={from} value={to} onChange={(e) => setTo(e.target.value)} /></label>}
      </>}
    </div>}
    {!valid ? <Feedback error="Ngày bắt đầu phải trước hoặc bằng ngày kết thúc." /> : <div className="reports-grid">
      {!compact && <Region title="Xu hướng giao dịch theo thời gian" periodKey={key} load={(signal) => reportApi.timeSeries(args, signal)}>{(r) => <TimeSeries value={r} />}</Region>}
      <Region title="Doanh thu" periodKey={key} load={(signal) => reportApi.revenue(args, signal)}>{(r) => <><Period value={r.period} /><Values values={{ "Doanh thu gộp": money(r.totalRevenue), "Hoàn vé": money(r.refundAmount), "Doanh thu ròng": money(r.netRevenue), "Tiền đang chờ xử lý ngoại lệ": money(r.exceptionPaidAmount) }} /></>}</Region>
      <Region title="Vé và F&B" periodKey={key} load={(signal) => reportApi.breakdown(args, signal)}>{(r) => <><Period value={r.period} /><Values values={{ "Vé gộp": money(r.ticketRevenue), "Hoàn vé": money(r.ticketRefundAmount), "Vé ròng": money(r.netTicketRevenue), "F&B": money(r.concessionRevenue), "Tổng ròng": money(r.netRevenue) }} /></>}</Region>
      <Region title="Kênh bán" periodKey={key} load={(signal) => reportApi.channels(args, signal)}>{(r) => <><Period value={r.period} /><div className="table-scroll"><table><thead><tr><th>Kênh</th><th>Gộp</th><th>Hoàn</th><th>Ròng</th></tr></thead><tbody>{r.channels.map((c) => <tr key={c.channel}><td>{c.channel === "POS" ? "Tại quầy" : "Trực tuyến"}</td><td>{money(c.grossRevenue)}</td><td>{money(c.refundAmount)}</td><td>{money(c.netRevenue)}</td></tr>)}</tbody></table></div></>}</Region>
      <Region title="Số vé" periodKey={key} load={(signal) => reportApi.count(args, signal)}>{(r) => <><Period value={r.period} /><Values values={{ "Đã phát hành": r.grossTicketCount, "Đã hủy": r.cancelledTicketCount, "Vé ròng": r.ticketCount }} /></>}</Region>
      <Region title="Lấp đầy theo suất" periodKey={key} load={(signal) => reportApi.occupancy(args, signal)}>{(r) => <><Period value={r.period} /><Values values={{ "Đã bán / tổng ghế": `${r.seatsSold} / ${r.totalSeats}`, "Tỷ lệ có trọng số": `${r.occupancyRate}%` }} /><Feedback empty={!r.showtimes.length} /><div className="table-scroll"><table><thead><tr><th>Suất</th><th>Bắt đầu</th><th>Ghế đã bán / tổng</th><th>Lấp đầy</th></tr></thead><tbody>{r.showtimes.map((s) => <tr key={s.showtimeId}><td>Suất #{s.showtimeId} (phim #{s.movieId})</td><td>{dateTime(s.startsAt)}</td><td>{s.seatsSold} / {s.totalSeats}</td><td>{s.occupancyRate}%</td></tr>)}</tbody></table></div></>}</Region>
      <Region title="Top 5 doanh thu" periodKey={key} load={(signal) => reportApi.ranking(args, "DESC", signal)}>{(r) => <Ranking value={r} />}</Region>
      <Region title="5 phim doanh thu thấp nhất" periodKey={key} load={(signal) => reportApi.ranking(args, "ASC", signal)}>{(r) => <Ranking value={r} />}</Region>
      <Region title="Cảnh báo tồn kho hiện tại" periodKey={key} load={(signal) => reportApi.alerts(args, signal)}>{(rows) => <><Feedback empty={!rows.length} />{rows.map((i) => <p key={i.itemId}>{i.status === "OUT_OF_STOCK" ? "Hết hàng" : "Sắp hết"}: {i.itemName}. Tồn {i.stockQuantity}, ngưỡng {i.lowStockThreshold}. <Link to={`/inventory?keyword=${encodeURIComponent(i.itemName)}`}>Nhập kho</Link></p>)}</>}</Region>
    </div>}
  </section>;
}
