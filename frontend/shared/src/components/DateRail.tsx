import { useRef } from "react";
import { today } from "../utils/format";

type DateRailProps = {
  value: string;
  onChange: (date: string) => void;
  showtimeCounts?: Readonly<Record<string, number>>;
  days?: number;
  disabled?: boolean;
};

function addDays(value: string, offset: number) {
  const date = new Date(`${value}T12:00:00Z`);
  date.setUTCDate(date.getUTCDate() + offset);
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}-${String(date.getUTCDate()).padStart(2, "0")}`;
}

export function showtimeDateKey(value: string) {
  const date = new Date(value);
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Ho_Chi_Minh",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(date);
  const part = (type: string) => parts.find((item) => item.type === type)?.value ?? "";
  return `${part("year")}-${part("month")}-${part("day")}`;
}

export function DateRail({
  value,
  onChange,
  showtimeCounts = {},
  days = 30,
  disabled = false,
}: DateRailProps) {
  const list = useRef<HTMLDivElement>(null);
  const firstDay = today();
  const dates = Array.from({ length: days }, (_, index) => addDays(firstDay, index));

  function scroll(direction: -1 | 1) {
    list.current?.scrollBy({ left: direction * Math.max(280, list.current.clientWidth * 0.8), behavior: "smooth" });
  }

  return (
    <section className="date-rail" aria-label="Chọn ngày chiếu">
      <div className="date-rail-heading">
        <div>
          <h3>Chọn ngày chiếu</h3>
          <p>Vuốt ngang hoặc dùng mũi tên để xem thêm ngày.</p>
        </div>
        <div className="date-rail-controls">
          <button type="button" aria-label="Xem các ngày trước" disabled={disabled} onClick={() => scroll(-1)}>‹</button>
          <button type="button" aria-label="Xem các ngày tiếp theo" disabled={disabled} onClick={() => scroll(1)}>›</button>
        </div>
      </div>
      <div className="date-rail-list" ref={list} role="group" aria-label="Danh sách ngày chiếu">
        {dates.map((date) => {
          const localDate = new Date(`${date}T12:00:00Z`);
          const count = showtimeCounts[date] ?? 0;
          const isToday = date === firstDay;
          const weekday = new Intl.DateTimeFormat("vi-VN", { weekday: "short", timeZone: "UTC" }).format(localDate);
          const month = new Intl.DateTimeFormat("vi-VN", { month: "short", timeZone: "UTC" }).format(localDate);
          const label = `${isToday ? "Hôm nay, " : ""}${new Intl.DateTimeFormat("vi-VN", { dateStyle: "full", timeZone: "UTC" }).format(localDate)}${count ? `, ${count} suất chiếu` : ", chưa có suất chiếu"}`;
          return (
            <button
              className={`date-rail-day${date === value ? " selected" : ""}${count ? " has-shows" : ""}`}
              key={date}
              type="button"
              aria-label={label}
              aria-pressed={date === value}
              disabled={disabled}
              onClick={() => onChange(date)}
            >
              <span className="date-rail-weekday">{isToday ? "Hôm nay" : weekday}</span>
              <strong>{localDate.getUTCDate()}</strong>
              <span className="date-rail-month">{month}</span>
              <span className="date-rail-availability">
                {count > 0 ? <><i aria-hidden="true" />{count} suất</> : <span>—</span>}
              </span>
            </button>
          );
        })}
      </div>
    </section>
  );
}
