import { useState, type FormEvent } from "react";
import { useAuth } from "../auth/AuthProvider";
import { posApi } from "../api/pos";
import { refundsApi } from "../api/refunds";
import { catalogApi } from "../api/catalog";
import { useQuery } from "../hooks/useQuery";
import { Feedback } from "../components/Feedback";
import { AgeConfirmation } from "../components/AgeConfirmation";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { money, dateTime, today } from "../utils/format";
import type { Eligibility, Ticket } from "../types";
import { ApiError } from "../api";
const refundReasonLabel: Record<string, string> = {
  REFUND_DEADLINE_PASSED: "Đã qua hạn hoàn",
  TICKET_NOT_REFUNDABLE: "Vé không đủ điều kiện",
  TICKET_ALREADY_CANCELLED: "Vé đã hủy",
};
export function ManagerOverrideDialog({
  bookingId,
  ticketIds,
  reason,
  cashConfirmed,
  onClose,
  onDone,
}: {
  bookingId: number;
  ticketIds: number[];
  reason: string;
  cashConfirmed: boolean;
  onClose: () => void;
  onDone: (amount: number) => void;
}) {
  const { session } = useAuth();
  const [username, setUsername] = useState(""),
    [pin, setPin] = useState(""),
    [override, setOverride] = useState<{
      overrideToken: string;
      expiresAt: string;
      approvedRefundAmount: number;
    } | null>(null),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  async function approve(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError("");
    const submittedPin = pin;
    setPin("");
    try {
      setOverride(await refundsApi.override({
        username,
        pin: submittedPin,
        bookingId,
        ticketIds,
        reason,
      }, session.accessToken));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function refund() {
    if (!override || busy) return;
    setBusy(true);
    setError("");
    try {
      const result = await refundsApi.refund(
        {
          bookingId,
          ticketIds,
          reason,
          overrideToken: override.overrideToken,
          cashConfirmed,
        },
        session.accessToken,
      );
      onDone(result.refundAmount);
    } catch (e) {
      if (e instanceof ApiError && ["MANAGER_OVERRIDE_EXPIRED", "MANAGER_OVERRIDE_REQUIRED", "MANAGER_PIN_INVALID"].includes(e.code)) {
        setOverride(null);
        setError("Phê duyệt hết hạn hoặc không còn hợp lệ. Vui lòng xác thực Manager lại.");
        return;
      }
      setError((e as Error).message + " Nếu phản hồi bị gián đoạn, hãy thử lại cùng yêu cầu này.");
    } finally {
      setBusy(false);
    }
  }
  return (
    <ConfirmDialog
      title="Manager duyệt hoàn vé"
      onClose={() => {
        setPin("");
        setOverride(null);
        onClose();
      }}
    >
      <Feedback error={error} />
      {!override ? (
        <form onSubmit={approve}>
          <label>
            Tài khoản Manager
            <input
              required
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="off"
            />
          </label>
          <label>
            PIN Manager
            <input
              type="password"
              inputMode="numeric"
              required
              value={pin}
              onChange={(e) => setPin(e.target.value)}
              autoComplete="off"
            />
          </label>
          <button disabled={busy}>Xác thực Manager</button>
        </form>
      ) : (
        <>
          <p>Phê duyệt có hạn đến {dateTime(override.expiresAt)}. Số tiền được duyệt: {money(override.approvedRefundAmount)}.</p>
          <button disabled={busy} onClick={refund}>
            Hoàn các vé đã chọn
          </button>
        </>
      )}
    </ConfirmDialog>
  );
}
export function RefundPage() {
  const { session } = useAuth();
  const [code, setCode] = useState(() => new URLSearchParams(location.search).get("bookingCode") ?? ""),
    [codeType, setCodeType] = useState<"bookingCode" | "ticketCode">("bookingCode"),
    [eligibility, setEligibility] = useState<Eligibility>(),
    [selected, setSelected] = useState<number[]>([]),
    [reason, setReason] = useState(""),
    [cashConfirmed, setCashConfirmed] = useState(false),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [busy, setBusy] = useState(false),
    [dialog, setDialog] = useState(false);
  async function lookup(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError("");
    setNotice("");
    setSelected([]);
    setEligibility(undefined);
    setCashConfirmed(false);
    try {
      setEligibility(await refundsApi.eligibility(code, codeType, session.accessToken));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <section>
      <h2>Yêu cầu hoàn vé tại POS</h2>
      <p>
        Chỉ hoàn Ticket đủ điều kiện. F&B không hoàn, không hồi tồn kho. Hoàn
        tiền mặt với Manager phê duyệt.
      </p>
      <form onSubmit={lookup}>
        <label>
          Loại mã cần tra
          <select value={codeType} onChange={(e) => {
            setCodeType(e.target.value as "bookingCode" | "ticketCode");
            setEligibility(undefined);
            setSelected([]);
            setCashConfirmed(false);
          }}>
            <option value="bookingCode">Booking Code</option>
            <option value="ticketCode">Ticket Code</option>
          </select>
        </label>
        <label>
          {codeType === "bookingCode" ? "Booking Code" : "Ticket Code"}
          <input
            required
            value={code}
            onChange={(e) => {
              setCode(e.target.value);
              setEligibility(undefined);
              setSelected([]);
              setCashConfirmed(false);
            }}
          />
        </label>
        <button disabled={busy}>Kiểm tra điều kiện hoàn</button>
      </form>
      <Feedback loading={busy} error={error} />
      {notice && (
        <p role="status" className="alert success">
          {notice}
        </p>
      )}
      {eligibility && (
        <>
          <p>
            Đơn {eligibility.bookingCode} · {eligibility.channel === "POS" ? "Tại quầy" : "Trực tuyến"} · Suất {dateTime(eligibility.startsAt)}
          </p>
          {eligibility.tickets.map((t) => (
            <label className="checkbox" key={t.ticketId}>
              <input
                type="checkbox"
                disabled={!t.eligible}
                checked={selected.includes(t.ticketId)}
                onChange={(e) =>
                  {
                    setCashConfirmed(false);
                    setSelected(
                      e.target.checked
                        ? [...selected, t.ticketId]
                        : selected.filter((id) => id !== t.ticketId),
                    );
                  }
                }
              />
              {t.seatLabel} · {t.ticketCode} · {t.status} ·{" "}
              {money(t.refundableAmount)} {t.reason && `· ${refundReasonLabel[t.reason] ?? t.reason}`}
            </label>
          ))}
          <p>
            Giá vé đã chọn:{" "}
            {money(
              eligibility.tickets
                .filter((t) => selected.includes(t.ticketId))
                .reduce((n, t) => n + t.refundableAmount, 0),
            )}
          </p>
          <label>
            Lý do hoàn
            <input
              maxLength={255}
              value={reason}
              onChange={(e) => {
                setReason(e.target.value);
                setCashConfirmed(false);
              }}
            />
          </label>
          <label className="checkbox">
            <input
              type="checkbox"
              checked={cashConfirmed}
              onChange={(e) => setCashConfirmed(e.target.checked)}
            />
            Xác nhận đã bàn giao đủ tiền mặt cho khách trước khi xin duyệt
          </label>
          <button
            disabled={!selected.length || !reason.trim() || !cashConfirmed}
            onClick={() => setDialog(true)}
          >
            Yêu cầu Manager duyệt
          </button>
        </>
      )}
      {dialog && eligibility && (
        <ManagerOverrideDialog
          bookingId={eligibility.bookingId}
          ticketIds={selected}
          reason={reason}
          cashConfirmed={cashConfirmed}
          onClose={() => setDialog(false)}
          onDone={(amount) => {
            setDialog(false);
            setEligibility(undefined);
            setSelected([]);
            setNotice("Hoàn vé thành công: " + money(amount));
          }}
        />
      )}
    </section>
  );
}
export function TicketValidationPage() {
  const { session } = useAuth();
  const [code, setCode] = useState(""),
    [showtimeId, setShowtimeId] = useState(""),
    [tickets, setTickets] = useState<Ticket[]>([]),
    [ageChecked, setAgeChecked] = useState<number[]>([]),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [notice, setNotice] = useState("");
  const shows = useQuery("gate-showtimes", (s) =>
    catalogApi.showtimes(session.accessToken, { date: today() }, s),
  );
  async function lookup(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError("");
    setNotice("");
    setTickets([]);
    setAgeChecked([]);
    try {
      setTickets(await posApi.lookup(code, session.accessToken));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function validate(ticket: Ticket) {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      const value = await posApi.validate(
        ticket.ticketCode,
        Number(showtimeId),
        ageChecked.includes(ticket.ticketId),
        session.accessToken,
      );
      setTickets((current) =>
        current.map((t) => (t.ticketId === value.ticketId ? value : t)),
      );
      setAgeChecked((ids) => ids.filter((id) => id !== ticket.ticketId));
      setNotice("Đã soát vé " + value.ticketCode);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <section>
      <h2>Soát vé</h2>
      <p>
        Quét bằng máy USB hoặc nhập mã. Kiểm tra giấy tờ tại cửa với phim hạn
        chế tuổi.
      </p>
      <Feedback {...shows} retry={shows.refetch} />
      <label>
        Suất chiếu tại cửa
        <select
          value={showtimeId}
          onChange={(e) => {
            setShowtimeId(e.target.value);
            setTickets([]);
            setAgeChecked([]);
          }}
        >
          <option value="">Chọn suất</option>
          {shows.data?.map((s) => (
            <option key={s.id} value={s.id}>
              {s.movieTitle} · {s.auditoriumName} · {dateTime(s.startTime)}
            </option>
          ))}
        </select>
      </label>
      <form onSubmit={lookup}>
        <label>
          Mã vé / Booking
          <input
            required
            value={code}
            onChange={(e) => { setCode(e.target.value); setTickets([]); setAgeChecked([]); }}
            autoComplete="off"
          />
        </label>
        <button disabled={busy || !showtimeId}>Tra cứu vé</button>
      </form>
      <Feedback error={error} />
      {notice && <p role="status">{notice}</p>}
      {tickets.map((t) => (
        <article className="card" key={t.ticketId}>
          <h3>
            {t.movieTitle} · {t.seatLabel}
          </h3>
          <p>
            {t.ticketCode} · {t.status} · {dateTime(t.startTime)}
          </p>
          {Number(showtimeId) !== t.showtimeId && (
            <p role="status" className="alert error">Vé này thuộc suất chiếu khác với cửa đang chọn.</p>
          )}
          <AgeConfirmation
            rating={t.ageRating}
            pos
            value={ageChecked.includes(t.ticketId)}
            onChange={(checked) => setAgeChecked((ids) => checked ? [...ids, t.ticketId] : ids.filter((id) => id !== t.ticketId))}
          />
          <button
            disabled={
              busy ||
              t.status !== "VALID" ||
              !showtimeId ||
              Number(showtimeId) !== t.showtimeId ||
              (t.ageRating !== "P" && !ageChecked.includes(t.ticketId))
            }
            onClick={() => validate(t)}
          >
            Chấp nhận vé {t.seatLabel}
          </button>
        </article>
      ))}
    </section>
  );
}
