import { useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../../auth/AuthProvider";
import { useQuery } from "../../hooks/useQuery";
import { reportApi } from "../../api/reports";
import { Feedback } from "../../components/Feedback";
import { money, dateTime, today } from "../../utils/format";
import type { Reports, ReportPeriod } from "../../types";
function Period({ value }: { value: ReportPeriod }) {
  return <p className="report-period">{value.from} – {value.to} · {value.timezone} · {value.basis === "SHOWTIME_START" ? "Theo ngày bắt đầu suất chiếu" : "Theo ngày thanh toán, đã điều chỉnh hoàn vé"} · Chốt lúc {dateTime(value.asOf)}</p>;
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
  const [from, setFrom] = useState(today()), [to, setTo] = useState(today()), [mode, setMode] = useState("range");
  const key = `${from}:${to}`;
  const valid = Boolean(from && to && from <= to);
  const args = { token: session.accessToken, from, to };
  return <section><h2>{compact ? "Doanh thu hôm nay" : "Báo cáo theo kỳ"}</h2>
    {!compact && <div className="toolbar">
      <label>Kỳ báo cáo<select value={mode} onChange={(e) => { setMode(e.target.value); if (e.target.value === "day") setTo(from); }}><option value="range">Khoảng ngày</option><option value="day">Ngày</option><option value="month">Tháng</option></select></label>
      {mode === "month" ? <label>Tháng<input type="month" value={from.slice(0, 7)} onChange={(e) => { const month = e.target.value; if (!month) return; const [year, m] = month.split("-").map(Number); setFrom(month + "-01"); setTo(`${month}-${new Date(Date.UTC(year, m, 0)).getUTCDate()}`); }} /></label> : <>
        <label>Từ ngày<input type="date" value={from} onChange={(e) => { setFrom(e.target.value); if (mode === "day") setTo(e.target.value); }} /></label>
        {mode === "range" && <label>Đến ngày<input type="date" min={from} value={to} onChange={(e) => setTo(e.target.value)} /></label>}
      </>}
    </div>}
    {!valid ? <Feedback error="Ngày bắt đầu phải trước hoặc bằng ngày kết thúc." /> : <div className="reports-grid">
      <Region title="Doanh thu" periodKey={key} load={(signal) => reportApi.revenue(args, signal)}>{(r) => <><Period value={r.period} /><Values values={{ "Doanh thu gộp": money(r.totalRevenue), "Hoàn vé": money(r.refundAmount), "Doanh thu ròng": money(r.netRevenue), "Tiền đang chờ xử lý ngoại lệ": money(r.exceptionPaidAmount) }} /></>}</Region>
      <Region title="Vé và F&B" periodKey={key} load={(signal) => reportApi.breakdown(args, signal)}>{(r) => <><Period value={r.period} /><Values values={{ "Vé gộp": money(r.ticketRevenue), "Hoàn vé": money(r.ticketRefundAmount), "Vé ròng": money(r.netTicketRevenue), "F&B": money(r.concessionRevenue), "Tổng ròng": money(r.netRevenue) }} /></>}</Region>
      <Region title="Kênh bán" periodKey={key} load={(signal) => reportApi.channels(args, signal)}>{(r) => <><Period value={r.period} /><div className="table-scroll"><table><thead><tr><th>Kênh</th><th>Gộp</th><th>Hoàn</th><th>Ròng</th></tr></thead><tbody>{r.channels.map((c) => <tr key={c.channel}><td>{c.channel === "POS" ? "Tại quầy" : "Trực tuyến"}</td><td>{money(c.grossRevenue)}</td><td>{money(c.refundAmount)}</td><td>{money(c.netRevenue)}</td></tr>)}</tbody></table></div></>}</Region>
      <Region title="Số vé" periodKey={key} load={(signal) => reportApi.count(args, signal)}>{(r) => <><Period value={r.period} /><Values values={{ "Đã phát hành": r.grossTicketCount, "Đã hủy": r.cancelledTicketCount, "Vé ròng": r.ticketCount }} /></>}</Region>
      <Region title="Lấp đầy theo suất" periodKey={key} load={(signal) => reportApi.occupancy(args, signal)}>{(r) => <><Period value={r.period} /><Values values={{ "Đã bán / tổng ghế": `${r.seatsSold} / ${r.totalSeats}`, "Tỷ lệ có trọng số": `${r.occupancyRate}%` }} /><Feedback empty={!r.showtimes.length} /><div className="table-scroll"><table><thead><tr><th>Suất</th><th>Bắt đầu</th><th>Ghế đã bán / tổng</th><th>Lấp đầy</th></tr></thead><tbody>{r.showtimes.map((s) => <tr key={s.showtimeId}><td>#{s.showtimeId} · Phim #{s.movieId}</td><td>{dateTime(s.startsAt)}</td><td>{s.seatsSold} / {s.totalSeats}</td><td>{s.occupancyRate}%</td></tr>)}</tbody></table></div></>}</Region>
      <Region title="Top 5 doanh thu" periodKey={key} load={(signal) => reportApi.ranking(args, "DESC", signal)}>{(r) => <Ranking value={r} />}</Region>
      <Region title="5 phim doanh thu thấp nhất" periodKey={key} load={(signal) => reportApi.ranking(args, "ASC", signal)}>{(r) => <Ranking value={r} />}</Region>
      <Region title="Cảnh báo tồn kho hiện tại" periodKey={key} load={(signal) => reportApi.alerts(args, signal)}>{(rows) => <><Feedback empty={!rows.length} />{rows.map((i) => <p key={i.itemId}>{i.status === "OUT_OF_STOCK" ? "Hết hàng" : "Sắp hết"} · {i.itemName}: {i.stockQuantity} / ngưỡng {i.lowStockThreshold} · <Link to={`/inventory?keyword=${encodeURIComponent(i.itemName)}`}>Nhập kho</Link></p>)}</>}</Region>
    </div>}
  </section>;
}
