import { useState, type FormEvent } from "react";
import { useAuth } from "../../auth/AuthProvider";
import { useQuery } from "../../hooks/useQuery";
import { managementApi } from "../../api/management";
import { catalogApi } from "../../api/catalog";
import { quote } from "../../api/pricing";
import { Feedback } from "../../components/Feedback";
import { ConfirmDialog } from "../../components/ConfirmDialog";
import { dateTime, money } from "../../utils/format";
import type { PricingRule, Quote, Seat } from "../../types";
const groups = {
  BASE_PRICE: { label: "Giá cơ bản", targets: ["STANDARD_2D"] },
  ROOM_SURCHARGE: { label: "Phụ thu phòng", targets: ["STANDARD_2D", "3D", "IMAX"] },
  SEAT_SURCHARGE: { label: "Phụ thu ghế", targets: ["STANDARD", "VIP", "SWEETBOX"] },
  TIME_SURCHARGE: { label: "Phụ thu thời gian", targets: ["WEEKEND_OR_AFTER_18H"] },
};
export function PricingPage() {
  const { session } = useAuth();
  const rules = useQuery("pricing-rules", (signal) => managementApi.list<PricingRule>("pricing-rules", session.accessToken, signal));
  const [edit, setEdit] = useState<Partial<PricingRule> | null>(null), [type, setType] = useState<keyof typeof groups>("BASE_PRICE"), [busy, setBusy] = useState(false), [error, setError] = useState("");
  async function save(e: FormEvent<HTMLFormElement>) {
    e.preventDefault(); if (busy || !edit) return;
    const fields = Object.fromEntries(new FormData(e.currentTarget));
    setBusy(true); setError("");
    try {
      const body = { amount: Number(fields.amount), description: fields.description, ...(edit.id ? {} : { ruleCode: fields.ruleCode, type, targetRef: fields.targetRef }) };
      await managementApi.save("pricing-rules", body, session.accessToken, edit.id);
      setEdit(null); rules.refetch();
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  return <section><h2>Quy tắc giá vé</h2><p>Thứ 6–Chủ nhật hoặc từ 18:00 tính phụ thu thời gian một lần. Giá các vé đã phát hành được giữ nguyên theo snapshot.</p><button onClick={() => { setType("BASE_PRICE"); setEdit({}); setError(""); }}>Thêm quy tắc giá</button><Feedback {...rules} retry={rules.refetch} />
    {Object.entries(groups).map(([kind, group]) => <section key={kind}><h3>{group.label}</h3><div className="table-scroll"><table><thead><tr><th>Mã</th><th>Đối tượng</th><th>Số tiền</th><th>Mô tả</th><th>Thao tác</th></tr></thead><tbody>{rules.data?.filter((r) => r.ruleType === kind).map((r) => <tr key={r.id}><td>{r.ruleCode}</td><td>{r.targetRef}</td><td>{money(r.amount)}</td><td>{r.description}</td><td><button onClick={() => { setEdit(r); setError(""); }}>Sửa giá {r.ruleCode}</button></td></tr>)}</tbody></table></div></section>)}
    {edit && <ConfirmDialog title={edit.id ? "Xác nhận thay đổi giá" : "Thêm quy tắc giá"} onClose={() => setEdit(null)}><Feedback error={error} /><form onSubmit={save}>
      {!edit.id && <><label>Mã quy tắc<input name="ruleCode" required pattern="[A-Z0-9_]{3,50}" /></label><label>Nhóm<select value={type} onChange={(e) => setType(e.target.value as keyof typeof groups)}>{Object.entries(groups).map(([k, g]) => <option key={k} value={k}>{g.label}</option>)}</select></label><label>Đối tượng<select name="targetRef" key={type}>{groups[type].targets.map((v) => <option key={v}>{v}</option>)}</select></label></>}
      <label>Giá mới<input type="number" name="amount" min="0" max="99999999.99" step="0.01" required defaultValue={edit.amount} /></label><label>Mô tả<input name="description" maxLength={255} defaultValue={edit.description} /></label><label className="checkbox"><input type="checkbox" required />Tôi xác nhận áp dụng giá mới cho các giao dịch tiếp theo</label><button disabled={busy}>Lưu quy tắc</button>
    </form></ConfirmDialog>}
    <QuotePreview />
  </section>;
}
function QuotePreview() {
  const { session } = useAuth();
  const shows = useQuery("pricing-shows", (signal) => catalogApi.showtimes(session.accessToken, {}, signal));
  const [showId, setShowId] = useState(""), [ids, setIds] = useState<number[]>([]), [result, setResult] = useState<Quote>(), [busy, setBusy] = useState(false), [error, setError] = useState("");
  const show = shows.data?.find((s) => s.id === Number(showId));
  const seats = useQuery("pricing-seats:" + show?.auditoriumId, (signal) => show ? managementApi.list<Seat>(`auditoriums/${show.auditoriumId}/seats`, session.accessToken, signal) : Promise.resolve([]));
  return <section><h3>Kiểm chứng giá từ máy chủ</h3><p>Chọn suất ngày thường, cuối tuần hoặc sau 18:00 và loại ghế để đối chiếu từng phụ thu.</p><Feedback {...shows} retry={shows.refetch} /><label>Suất chiếu kiểm giá<select value={showId} onChange={(e) => { setShowId(e.target.value); setIds([]); setResult(undefined); }}><option value="">Chọn suất chiếu</option>{shows.data?.map((s) => <option key={s.id} value={s.id}>{s.movieTitle}, {s.auditoriumName}, {dateTime(s.startTime)}</option>)}</select></label><Feedback {...seats} retry={seats.refetch} />
    <div className="toolbar">{seats.data?.map((seat) => <label className="checkbox" key={seat.seatId}><input type="checkbox" checked={ids.includes(seat.seatId)} onChange={(e) => { setIds(e.target.checked ? [...ids, seat.seatId] : ids.filter((id) => id !== seat.seatId)); setResult(undefined); }} />{seat.row}{seat.number}, {seat.seatType}</label>)}</div>
    <Feedback error={error} /><button disabled={!show || !ids.length || busy} onClick={async () => { setBusy(true); setError(""); try { setResult(await quote(Number(showId), ids, session.accessToken)); } catch (e) { setError((e as Error).message); } finally { setBusy(false); } }}>Lấy giá từ máy chủ</button>
    {result && <div className="table-scroll"><table><thead><tr><th>Ghế</th><th>Cơ bản</th><th>Phòng</th><th>Ghế</th><th>Thời gian</th><th>Tổng</th></tr></thead><tbody>{result.tickets.map((r) => <tr key={r.seatId}><td>#{r.seatId}</td><td>{money(r.basePrice)}</td><td>{money(r.roomSurcharge)}</td><td>{money(r.seatSurcharge)}</td><td>{money(r.timeSurcharge)}</td><td>{money(r.finalPrice)}</td></tr>)}</tbody></table><strong>Tổng {money(result.total)}</strong></div>}
  </section>;
}
