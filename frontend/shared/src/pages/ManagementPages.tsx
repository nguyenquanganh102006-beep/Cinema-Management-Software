import { ApiError } from "../api";
import { useState, type FormEvent } from "react";
import { Link, useParams } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useQuery } from "../hooks/useQuery";
import { managementApi, PartialSaveError } from "../api/management";
import { catalogApi } from "../api/catalog";
import { reportApi } from "../api/reports";
import { ReportsPage } from "./reports/ReportsPage";
import { PhysicalSeatLayout } from "../components/PhysicalSeatLayout";
import { Feedback } from "../components/Feedback";
import { Pagination } from "../components/Pagination";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { money, dateTime, today } from "../utils/format";
import type {
  Inventory,
  Concession,
  Auditorium,
  Staff,
  Showtime,
  PricingRule,
  Movie,
  Seat,
} from "../types";
type Row = Record<string, unknown>;
interface Field {
  key: string;
  label: string;
  type?:
    | "number"
    | "date"
    | "datetime-local"
    | "checkbox"
    | "password"
    | "url"
    | "email"
    | "select"
    | "components";
  options?: { value: string; label: string }[];
  min?: number;
  required?: boolean;
}
const commonMovie: Field[] = [
  { key: "title", label: "Tên phim" },
  { key: "description", label: "Mô tả", required: false },
  {
    key: "durationMinutes",
    label: "Thời lượng (phút)",
    type: "number",
    min: 1,
  },
  { key: "genre", label: "Thể loại", required: false },
  { key: "releaseDate", label: "Ngày phát hành", type: "date" },
  { key: "country", label: "Quốc gia", required: false },
  { key: "language", label: "Ngôn ngữ", required: false },
  { key: "director", label: "Đạo diễn", required: false },
  { key: "poster", label: "URL poster", type: "url", required: false },
  { key: "trailer", label: "URL trailer", type: "url", required: false },
  {
    key: "ageRating",
    label: "Nhãn tuổi",
    type: "select",
    options: ["P", "T13", "T16", "T18"].map((value) => ({
      value,
      label: value,
    })),
  },
  { key: "active", label: "Đang phát hành", type: "checkbox" },
];
export function ResourceEditor({
  title,
  fields,
  initial = {},
  onSave,
  onClose,
  items = [],
}: {
  title: string;
  fields: Field[];
  initial?: Row;
  onSave: (data: Row) => Promise<void>;
  onClose: () => void;
  items?: Concession[];
}) {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [partial, setPartial] = useState<PartialSaveError | null>(null),
    [unknownWrite, setUnknownWrite] = useState(false),
    [details, setDetails] = useState<Record<string, string>>({});
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (busy) return;
    const data = new FormData(e.currentTarget);
    const body: Row = {};
    for (const field of fields) {
      const raw = data.get(field.key);
      if (field.type === "components") {
        body[field.key] = items
          .filter((i) => !i.combo)
          .map((i) => ({
            itemId: i.id,
            quantity: Number(data.get("component:" + i.id)),
          }))
          .filter((i) => i.quantity > 0);
      } else if (field.type === "checkbox") body[field.key] = raw === "on";
      else if (field.type === "number") body[field.key] = Number(raw);
      else if (field.type === "datetime-local") {
        body[field.key] = `${String(raw)}:00+07:00`;
      } else if (field.key.endsWith("Id")) body[field.key] = Number(raw);
      else body[field.key] = raw;
    }
    if (fields.some((f) => f.key === "password") && !initial.id && String(body.password ?? "").length < 8) { setError("Mật khẩu tạo mới cần ít nhất 8 ký tự."); return; }
    setBusy(true);
    setError("");
    try {
      await onSave(body);
      onClose();
    } catch (e) {
      if (e instanceof PartialSaveError) setPartial(e);
      if (e instanceof ApiError) { setDetails(e.details); if (e.status === 0) setUnknownWrite(true); }
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <ConfirmDialog title={title} onClose={onClose}>
      <Feedback error={error} />
      {Object.keys(details).length > 0 && <ul>{Object.entries(details).map(([field, text]) => <li key={field}>{fields.find((f) => f.key === field)?.label ?? field}: {text}</li>)}</ul>}
      {unknownWrite && <p role="status">Chưa xác định kết quả ghi. Đóng form và đọc lại danh sách trước khi gửi một yêu cầu mới.</p>}
      {partial && <button disabled={busy} onClick={async () => {
        setBusy(true);
        try { await partial.retryStatus(); window.dispatchEvent(new Event("cinema:management-changed")); onClose(); }
        catch (error) { setError((error as Error).message); }
        finally { setBusy(false); }
      }}>Thử lại đổi trạng thái</button>}
      <form onSubmit={submit}>
        {fields.map((field) => (
          <label key={field.key}>
            {field.label}
            {field.type === "select" ? (
              <select
                key={(field.options ?? []).map((o) => o.value).join(",")}
                name={field.key}
                defaultValue={String(
                  initial[field.key] ?? field.options?.[0]?.value ?? "",
                )}
              >
                {field.options?.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            ) : field.type === "components" ? (
              <span>
                {items
                  .filter((i) => !i.combo && i.id !== initial.id)
                  .map((i) => (
                    <label key={i.id}>
                      {i.name}
                      <input
                        name={"component:" + i.id}
                        type="number"
                        min={0}
                        defaultValue={
                          (
                            initial.components as
                              { itemId: number; quantity: number }[] | undefined
                          )?.find((c) => c.itemId === i.id)?.quantity ?? 0
                        }
                      />
                    </label>
                  ))}
              </span>
            ) : (
              <input
                name={field.key}
                type={field.type ?? "text"}
                required={field.required !== false && field.type !== "checkbox"}
                min={field.min ?? (field.type === "number" ? 0 : undefined)}
                minLength={field.type === "password" ? 8 : undefined}
                defaultChecked={
                  field.type === "checkbox"
                    ? Boolean(
                        initial[field.key] ??
                          (field.key === "active" ? true : false),
                      )
                    : undefined
                }
                defaultValue={
                  field.type === "checkbox"
                    ? undefined
                    : field.type === "datetime-local" && initial[field.key]
                      ? new Date(new Date(String(initial[field.key])).getTime() + 7 * 3600000).toISOString().slice(0, 16)
                      : String(initial[field.key] ?? "")
                }
                disabled={busy || (field.key === "username" && Boolean(initial.id))}
              />
            )}
          </label>
        ))}
        <button className="primary" disabled={busy || Boolean(partial) || unknownWrite} type="submit">
          Lưu
        </button>
      </form>
    </ConfirmDialog>
  );
}
function MasterTable({
  title,
  rows,
  columns,
  fields,
  resource,
  extra,
  items,
}: {
  title: string;
  rows: Row[];
  columns: { key: string; label: string }[];
  fields: Field[];
  resource: string;
  extra?: (row: Row) => React.ReactNode;
  items?: Concession[];
}) {
  const { session } = useAuth();
  const [editor, setEditor] = useState<Row | null>(null),
    [refresh, setRefresh] = useState(0);
  const [error, setError] = useState("");
  // The parent owns reads. Saving reloads the route's data by a local event, never reloads the browser/session.
  return (
    <section data-refresh={refresh}>
      <h2>{title}</h2>
      <button onClick={() => setEditor({})}>Thêm mới</button>
      <Feedback error={error} />
      <Feedback empty={rows.length === 0} />
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              {columns.map((c) => (
                <th key={c.key}>{c.label}</th>
              ))}
              <th>Thao tác</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={String(row.id)}>
                {columns.map((c) => (
                  <td key={c.key}>
                    {typeof row[c.key] === "boolean"
                      ? row[c.key]
                        ? "Có"
                        : "Không"
                      : String(row[c.key] ?? "—")}
                  </td>
                ))}
                <td>
                  <button onClick={() => setEditor(row)}>Sửa</button>
                  {extra?.(row)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {editor && (
        <ResourceEditor
          title={editor.id ? "Sửa " + title : "Thêm " + title}
          fields={fields}
          initial={editor}
          items={items}
          onClose={() => setEditor(null)}
          onSave={async (body) => {
            try {
              await managementApi.save(
                resource,
                body,
                session.accessToken,
                editor.id as number | undefined,
              );
              setRefresh((v) => v + 1);
              window.dispatchEvent(new Event("cinema:management-changed"));
            } catch (e) {
              setError((e as Error).message);
              throw e;
            }
          }}
        />
      )}
    </section>
  );
}
function useManagement<T>(resource: string) {
  const { session } = useAuth();
  const result = useQuery(resource, (s) =>
    managementApi.list<T>(resource, session.accessToken, s),
  );
  useManagementRefresh(result.refetch);
  return result;
}
function useManagementPage<T>(
  resource: string,
  filters: Record<string, string | number | boolean | undefined>,
) {
  const { session } = useAuth();
  const key = `${resource}:${JSON.stringify(filters)}`;
  const result = useQuery(key, (signal) =>
    managementApi.page<T>(resource, session.accessToken, filters, signal),
  );
  useManagementRefresh(result.refetch);
  return result;
}
import { useEffect } from "react";
function useManagementRefresh(refetch: () => void) {
  useEffect(() => {
    window.addEventListener("cinema:management-changed", refetch);
    return () =>
      window.removeEventListener("cinema:management-changed", refetch);
  }, [refetch]);
}
const rowify = <T,>(rows: T[] | undefined) => (rows ?? []) as unknown as Row[];
export function MoviesPage() {
  const { session } = useAuth();
  const [keyword, setKeyword] = useState(""),
    [page, setPage] = useState(0),
    [active, setActive] = useState("");
  const result = useQuery(`manage-movies:${keyword}:${page}:${active}`, (s) =>
    catalogApi.movies(
      session.accessToken,
      { keyword, page, active: active === "" ? undefined : active === "true" },
      s,
    ),
  );
  useManagementRefresh(result.refetch);
  return (
    <>
      <div className="toolbar">
        <label>
          Tìm phim
          <input
            value={keyword}
            onChange={(e) => {
              setKeyword(e.target.value);
              setPage(0);
            }}
          />
        </label>
        <label>
          Phát hành
          <select
            value={active}
            onChange={(e) => {
              setActive(e.target.value);
              setPage(0);
            }}
          >
            <option value="">Tất cả</option>
            <option value="true">Đang phát hành</option>
            <option value="false">Ngừng phát hành</option>
          </select>
        </label>
      </div>
      <Feedback {...result} retry={result.refetch} />
      <MasterTable
        title="Phim"
        rows={rowify(result.data?.data)}
        resource="movies"
        fields={commonMovie}
        columns={[
          { key: "title", label: "Tên phim" },
          { key: "ageRating", label: "Tuổi" },
          { key: "durationMinutes", label: "Phút" },
          { key: "active", label: "Đang phát hành" },
        ]}
      />
      {result.data && <Pagination meta={result.data.meta} onPage={setPage} />}
    </>
  );
}
export function AuditoriumsPage() {
  const result = useManagement<Auditorium>("auditoriums");
  return (
    <>
      <Feedback {...result} retry={result.refetch} />
      <MasterTable
        title="Phòng chiếu"
        resource="auditoriums"
        rows={rowify(result.data)}
        fields={[
          { key: "name", label: "Tên phòng" },
          {
            key: "auditoriumType",
            label: "Định dạng",
            type: "select",
            options: ["STANDARD_2D", "3D", "IMAX"].map((value) => ({
              value,
              label: value,
            })),
          },
          { key: "totalSeats", label: "Số ghế", type: "number", min: 1 },
          { key: "active", label: "Hoạt động", type: "checkbox" },
        ]}
        columns={[
          { key: "name", label: "Tên phòng" },
          { key: "auditoriumType", label: "Loại" },
          { key: "totalSeats", label: "Số ghế" },
        ]}
        extra={(r) => <Link to={`/auditoriums/${r.id}/seats`}>Sơ đồ ghế</Link>}
      />
    </>
  );
}
export function SeatLayoutPage() {
  const id = Number(useParams().id),
    { session } = useAuth();
  const result = useManagement<Seat>(`auditoriums/${id}/seats`);
  const [error, setError] = useState(""),
    [editor, setEditor] = useState<Row | null>(null),
    [left, setLeft] = useState(""),
    [right, setRight] = useState(""),
    [busy, setBusy] = useState(false),
    [pairConfirm, setPairConfirm] = useState<boolean | null>(null);
  const leftSeat = result.data?.find((s) => s.seatId === Number(left));
  const rightSeat = result.data?.find((s) => s.seatId === Number(right));
  const canPair = leftSeat && rightSeat && leftSeat.seatId !== rightSeat.seatId && leftSeat.row === rightSeat.row && Math.abs(leftSeat.number - rightSeat.number) === 1 && leftSeat.seatType === "SWEETBOX" && rightSeat.seatType === "SWEETBOX" && !leftSeat.pairSeatId && !rightSeat.pairSeatId;
  async function pair(remove = false) {
    setBusy(true);
    setError("");
    try {
      await managementApi.action(
        remove ? `/seats/sweetbox-pairs/${left}` : "/seats/sweetbox-pairs",
        remove ? undefined : { seatId: Number(left), pairSeatId: Number(right) },
        session.accessToken,
        remove ? "DELETE" : "POST",
      );
      result.refetch();
      setPairConfirm(null);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <section>
      <h2>Cấu hình ghế phòng {id}</h2>
      <Feedback
        {...result}
        error={error || result.error}
        retry={result.refetch}
      />
      <button onClick={() => setEditor({})}>Thêm ghế</button>
      <PhysicalSeatLayout seats={result.data ?? []} onEdit={(seat) => setEditor({ id: seat.seatId, row: seat.row, number: seat.number, seatType: seat.seatType })} />
      <div className="toolbar">
        <label>
          Ghế trái
          <select value={left} onChange={(e) => setLeft(e.target.value)}>
            <option value="">Chọn ghế</option>
            {result.data?.map((s) => (
              <option key={s.seatId} value={s.seatId}>
                {s.row}
                {s.number}
              </option>
            ))}
          </select>
        </label>
        <label>
          Ghế phải
          <select value={right} onChange={(e) => setRight(e.target.value)}>
            <option value="">Chọn ghế</option>
            {result.data?.map((s) => (
              <option key={s.seatId} value={s.seatId}>
                {s.row}
                {s.number}
              </option>
            ))}
          </select>
        </label>
        <button disabled={busy || !canPair} onClick={() => setPairConfirm(false)}>
          Ghép Sweetbox
        </button>
        <button disabled={busy || !leftSeat?.pairSeatId} onClick={() => setPairConfirm(true)}>
          Tách cặp
        </button>
      </div>
      {pairConfirm !== null && <ConfirmDialog title={pairConfirm ? "Tách cặp Sweetbox?" : "Ghép cặp Sweetbox?"} onClose={() => setPairConfirm(null)}><p>{leftSeat?.row}{leftSeat?.number} {pairConfirm ? "và ghế cùng cặp" : `và ${rightSeat?.row}${rightSeat?.number}`}</p><Feedback error={error} /><button disabled={busy} onClick={() => pair(pairConfirm)}>Xác nhận {pairConfirm ? "tách" : "ghép"} cặp</button></ConfirmDialog>}
      {editor && (
        <ResourceEditor
          title="Ghế"
          initial={editor}
          fields={[
            { key: "row", label: "Hàng" },
            { key: "number", label: "Số ghế", type: "number", min: 1 },
            {
              key: "seatType",
              label: "Loại ghế",
              type: "select",
              options: ["STANDARD", "VIP", "SWEETBOX"].map((value) => ({
                value,
                label: value,
              })),
            },
          ]}
          onClose={() => setEditor(null)}
          onSave={async (body) => {
            await managementApi.save(
              editor.id ? "seats" : `auditoriums/${id}/seats`,
              body,
              session.accessToken,
              editor.id as number | undefined,
            );
            result.refetch();
          }}
        />
      )}
    </section>
  );
}
export function ShowtimesPage() {
  const { session } = useAuth();
  const [date, setDate] = useState(today()),
    [room, setRoom] = useState(""),
    [movie, setMovie] = useState("");
  const shows = useQuery(`management-showtimes:${date}:${movie}`, (s) =>
    catalogApi.showtimes(
      session.accessToken,
      { date, movieId: movie ? Number(movie) : undefined },
      s,
    ),
  );
  useManagementRefresh(shows.refetch);
  const rooms = useManagement<Auditorium>("auditoriums");
  const movies = useQuery("showtime-movie-options", (s) =>
    catalogApi.movieOptions(session.accessToken, s),
  );
  useManagementRefresh(movies.refetch);
  return (
    <>
      <div className="toolbar">
        <label>
          Ngày chiếu
          <input
            type="date"
            value={date}
            onChange={(e) => setDate(e.target.value)}
          />
        </label>
        <label>
          Phòng
          <select value={room} onChange={(e) => setRoom(e.target.value)}>
            <option value="">Tất cả</option>
            {rooms.data?.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          Phim
          <select value={movie} onChange={(e) => setMovie(e.target.value)}>
            <option value="">Tất cả</option>
            {movies.data?.map((m) => (
              <option key={m.id} value={m.id}>
                {m.title}
              </option>
            ))}
          </select>
        </label>
      </div>
      <Feedback {...shows} retry={shows.refetch} />
      <MasterTable
        title="Lịch chiếu"
        resource="showtimes"
        rows={rowify(
          shows.data?.filter((s) => !room || s.auditoriumId === Number(room)),
        )}
        fields={[
          {
            key: "movieId",
            label: "Phim",
            type: "select",
            options: movies.data?.map((m) => ({
              value: String(m.id),
              label: m.title,
            })),
          },
          {
            key: "auditoriumId",
            label: "Phòng",
            type: "select",
            options: rooms.data?.map((r) => ({
              value: String(r.id),
              label: r.name,
            })),
          },
          {
            key: "startTime",
            label: "Bắt đầu (giờ Việt Nam)",
            type: "datetime-local",
          },
        ]}
        columns={[
          { key: "movieTitle", label: "Phim" },
          { key: "auditoriumName", label: "Phòng" },
          { key: "startTime", label: "Bắt đầu" },
          { key: "endTime", label: "Kết thúc từ máy chủ" },
        ]}
      />
    </>
  );
}
export { PricingPage } from "./pricing/PricingPage";
export function ConcessionsPage() {
  const { session } = useAuth();
  const [keyword, setKeyword] = useState(""),
    [active, setActive] = useState(""),
    [combo, setCombo] = useState(""),
    [page, setPage] = useState(0);
  const result = useManagementPage<Concession>("concessions", {
    keyword,
    active,
    combo,
    page,
    size: 20,
  });
  const ingredients = useQuery("concessions:active-single-items", (signal) =>
    catalogApi.concessionOptions(session.accessToken, signal),
  );
  return (
    <>
      <div className="toolbar">
        <label>Tìm món<input value={keyword} onChange={(e) => { setKeyword(e.target.value); setPage(0); }} /></label>
        <label>Trạng thái<select value={active} onChange={(e) => { setActive(e.target.value); setPage(0); }}><option value="">Tất cả</option><option value="true">Đang bán</option><option value="false">Ngừng bán</option></select></label>
        <label>Loại<select value={combo} onChange={(e) => { setCombo(e.target.value); setPage(0); }}><option value="">Món và combo</option><option value="false">Món lẻ</option><option value="true">Combo</option></select></label>
      </div>
      <Feedback {...result} empty={result.data?.data.length === 0} retry={result.refetch} />
      <Feedback {...ingredients} retry={ingredients.refetch} />
      <MasterTable
        title="F&B"
        resource="concessions"
        rows={rowify(result.data?.data)}
        items={ingredients.data}
        fields={[
          { key: "name", label: "Tên món / combo" },
          { key: "price", label: "Giá bán", type: "number" },
          { key: "active", label: "Đang bán", type: "checkbox" },
          { key: "combo", label: "Combo", type: "checkbox" },
          {
            key: "components",
            label: "Thành phần combo (số lượng)",
            type: "components",
          },
        ]}
        columns={[
          { key: "name", label: "Món" },
          { key: "price", label: "Giá" },
          { key: "combo", label: "Combo" },
          { key: "active", label: "Đang bán" },
        ]}
      />
      {result.data && <Pagination meta={result.data.meta} onPage={setPage} />}
    </>
  );
}
export function InventoryPage() {
  const { session } = useAuth();
  const [keyword, setKeyword] = useState(() => new URLSearchParams(location.search).get("keyword") ?? ""),
    [status, setStatus] = useState(""),
    [page, setPage] = useState(0);
  const result = useManagementPage<Inventory>("inventory", { keyword, status, page, size: 20 });
  const [item, setItem] = useState<Inventory | null>(null);
  return (
    <section>
      <h2>Tồn kho</h2>
      <Link to="/inventory/history">Lịch sử nhập kho</Link>
      <div className="toolbar">
        <label>Tìm mặt hàng<input value={keyword} onChange={(e) => { setKeyword(e.target.value); setPage(0); }} /></label>
        <label>Trạng thái<select value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}><option value="">Tất cả</option><option value="IN_STOCK">Còn hàng</option><option value="LOW_STOCK">Sắp hết</option><option value="OUT_OF_STOCK">Hết hàng</option></select></label>
      </div>
      <Feedback {...result} empty={result.data?.data.length === 0} retry={result.refetch} />
      {Boolean(result.data?.data.length) && (
        <div className="table-scroll">
          <table className="inventory-table">
            <thead>
              <tr>
                <th>Mặt hàng</th>
                <th>Tồn kho</th>
                <th>Ngưỡng tối thiểu</th>
                <th>Trạng thái</th>
                <th>Thao tác</th>
              </tr>
            </thead>
            <tbody>
              {result.data?.data.map((i) => {
                const status = i.stockQuantity <= 0
                  ? { label: "Hết hàng", tone: "out" }
                  : i.stockQuantity <= i.lowStockThreshold
                    ? { label: "Sắp hết", tone: "low" }
                    : { label: "Đủ hàng", tone: "ready" };
                return (
                  <tr key={i.itemId}>
                    <td><strong>{i.itemName}</strong></td>
                    <td>{i.stockQuantity}</td>
                    <td>{i.lowStockThreshold}</td>
                    <td><span className={`stock-status ${status.tone}`}>{status.label}</span></td>
                    <td>
                      <div className="inventory-actions">
                        <button onClick={() => setItem(i)}>Nhập kho</button>
                        <Link to={`/inventory/history?itemId=${i.itemId}&name=${encodeURIComponent(i.itemName)}`}>Lịch sử</Link>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {result.data && <Pagination meta={result.data.meta} onPage={setPage} />}
      {item && (
        <ResourceEditor
          title={"Nhập kho " + item.itemName}
          fields={[
            { key: "quantity", label: "Số lượng nhập", type: "number", min: 1 },
            { key: "note", label: "Ghi chú" },
          ]}
          onClose={() => setItem(null)}
          onSave={async (body) => {
            await managementApi.action(
              `/inventory/${item.itemId}/restocks`,
              body,
              session.accessToken,
            );
            result.refetch();
            window.dispatchEvent(new Event("cinema:management-changed"));
          }}
        />
      )}
    </section>
  );
}
export function RestockHistoryPage() {
  const { session } = useAuth();
  const [item, setItem] = useState(() => new URLSearchParams(location.search).get("itemId") ?? ""),
    [manager, setManager] = useState(""),
    [from, setFrom] = useState(""),
    [to, setTo] = useState(""),
    [page, setPage] = useState(0);
  const result = useQuery(
    `restocks:${item}:${manager}:${from}:${to}:${page}`,
    (s) =>
      item && !manager && !from && !to
        ? managementApi.history(Number(item), session.accessToken, page, s)
        : managementApi.restocks(session.accessToken, { itemId: item, managerId: manager, from, to, page }, s),
  );
  useManagementRefresh(result.refetch);
  return (
    <section>
      <h2>Lịch sử nhập kho</h2>
      <div className="toolbar">
        <label>
          Mã mặt hàng (ID)
          <input
            value={item}
            onChange={(e) => {
              setItem(e.target.value);
              setPage(0);
            }}
          />
        </label>
        <label>
          Mã Manager (ID)
          <input
            value={manager}
            onChange={(e) => {
              setManager(e.target.value);
              setPage(0);
            }}
          />
        </label>
        <label>
          Từ
          <input
            type="date"
            value={from}
            onChange={(e) => { setFrom(e.target.value); setPage(0); }}
          />
        </label>
        <label>
          Đến
          <input
            type="date"
            value={to}
            onChange={(e) => { setTo(e.target.value); setPage(0); }}
          />
        </label>
      </div>
      <Feedback
        {...result}
        empty={result.data?.data.length === 0}
        retry={result.refetch}
      />
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th>Hàng</th>
              <th>Trước → Sau</th>
              <th>Nhập</th>
              <th>Manager</th>
              <th>Ghi chú</th>
              <th>Thời gian</th>
            </tr>
          </thead>
          <tbody>
            {result.data?.data.map((r) => (
              <tr key={r.movementId}>
                <td>{r.itemName}</td>
                <td>
                  {r.beforeQuantity} → {r.afterQuantity}
                </td>
                <td>{r.quantity}</td>
                <td>{r.managerName}</td>
                <td>{r.note}</td>
                <td>{dateTime(r.createdAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {result.data && <Pagination meta={result.data.meta} onPage={setPage} />}
    </section>
  );
}
export function StaffPage() {
  const { session } = useAuth();
  const [keyword, setKeyword] = useState(""),
    [filterStatus, setFilterStatus] = useState(""),
    [page, setPage] = useState(0);
  const result = useManagementPage<Staff>("staff", { keyword, status: filterStatus, page, size: 20 });
  const [reset, setReset] = useState<Staff | null>(null),
    [status, setStatus] = useState<Staff | null>(null),
    [error, setError] = useState("");
  return (
    <>
      <div className="toolbar">
        <label>Tìm nhân viên<input value={keyword} onChange={(e) => { setKeyword(e.target.value); setPage(0); }} /></label>
        <label>Trạng thái<select value={filterStatus} onChange={(e) => { setFilterStatus(e.target.value); setPage(0); }}><option value="">Tất cả</option><option value="ACTIVE">Đang hoạt động</option><option value="INACTIVE">Ngừng hoạt động</option></select></label>
      </div>
      <Feedback
        {...result}
        error={error || result.error}
        retry={result.refetch}
      />
      <MasterTable
        title="Nhân viên"
        resource="staff"
        rows={rowify(result.data?.data)}
        fields={[
          { key: "username", label: "Tên đăng nhập" },
          { key: "fullName", label: "Họ và tên" },
          { key: "email", label: "Email", type: "email" },
          { key: "phone", label: "Số điện thoại" },
          {
            key: "password",
            label: "Mật khẩu (khi tạo mới)",
            type: "password",
            required: false,
          },
        ]}
        columns={[
          { key: "username", label: "Tài khoản" },
          { key: "fullName", label: "Tên" },
          { key: "status", label: "Trạng thái" },
        ]}
        extra={(r) => (
          <>
            <button onClick={() => setStatus(r as unknown as Staff)}>
              {r.status === "ACTIVE" ? "Ngừng hoạt động" : "Kích hoạt"}
            </button>
            <button onClick={() => setReset(r as unknown as Staff)}>
              Reset mật khẩu
            </button>
          </>
        )}
      />
      {result.data && <Pagination meta={result.data.meta} onPage={setPage} />}
      {reset && <ResourceEditor title={`Đặt lại mật khẩu ${reset.username}`} fields={[{ key: "newPassword", label: "Mật khẩu mới", type: "password" }]} onClose={() => setReset(null)} onSave={async (body) => {
        await managementApi.action(`/staff/${reset.id}/reset-password`, body, session.accessToken);
        result.refetch();
      }} />}
      {status && (
        <ConfirmDialog
          title="Xác nhận trạng thái nhân viên"
          onClose={() => setStatus(null)}
        >
          <p>{status.status === "ACTIVE" ? "Ngừng hoạt động tài khoản" : "Kích hoạt tài khoản"} {status.username}?</p>
          <button
            onClick={async () => {
              try {
                await managementApi.action(
                  `/staff/${status.id}/status`,
                  { active: status.status !== "ACTIVE" },
                  session.accessToken,
                  "PATCH",
                );
                setStatus(null);
                result.refetch();
              } catch (e) {
                setError((e as Error).message);
              }
            }}
          >
            Xác nhận
          </button>
        </ConfirmDialog>
      )}
    </>
  );
}
export { ReportsPage } from "./reports/ReportsPage";
export function DashboardPage() {
  const { session } = useAuth();
  const exceptions = useQuery("payment-exceptions", (signal) =>
    reportApi.paymentExceptions(session.accessToken, signal),
  );
  return (
    <>
      <h2>Tổng quan rạp</h2>
      <div className="toolbar">
        <Link to="/inventory">Nhập kho</Link>
        <Link to="/showtimes">Lập lịch chiếu</Link>
        <Link to="/reports">Báo cáo theo kỳ</Link>
      </div>
      <section className="card" aria-labelledby="payment-exceptions-title">
        <h3 id="payment-exceptions-title">Thanh toán đã nhận, đơn cần xử lý</h3>
        <p>Đơn đã thu tiền nhưng chưa thể phát hành vé. Quản lý cần liên hệ quầy để hỗ trợ khách.</p>
        <Feedback {...exceptions} retry={exceptions.refetch} />
        {exceptions.data && exceptions.data.length === 0 && <p>Không có đơn thanh toán ngoại lệ đang chờ.</p>}
        {exceptions.data?.map((item) => (
          <article className="card" key={item.bookingId}>
            <h4>{item.bookingCode}: {item.movieTitle}</h4>
            <p>{item.auditoriumName}, suất {dateTime(item.showtimeStartsAt)}</p>
            <p>Đã nhận {money(item.amount)} lúc {dateTime(item.paidAt)}. Mã lỗi {item.reasonCode}</p>
          </article>
        ))}
      </section>
      <ReportsPage compact />
    </>
  );
}
