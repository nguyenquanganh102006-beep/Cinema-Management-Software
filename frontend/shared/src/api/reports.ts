import { request, query } from "../api";
import type { ReportPeriod, Reports } from "../types";

type Revenue = Reports["revenue"];
type Breakdown = Reports["breakdown"];
type Channels = Reports["channels"];
type TicketCount = Reports["count"];
type Occupancy = Reports["occupancy"];
type InventoryAlert = Reports["alerts"][number];
type Ranking = Reports["ranking"];
export async function reports(
  token: string,
  from: string,
  to: string,
  signal?: AbortSignal,
): Promise<Reports> {
  const path = (name: string) => `/reports/${name}?${query({ from, to })}`;
  const [revenue, breakdown, channels, count, occupancy, alerts, ranking, rankingLow] = await Promise.all([
    request<Revenue>(path("revenue"), { token, signal }),
    request<Breakdown>(path("revenue-breakdown"), { token, signal }),
    request<Channels>(path("revenue-by-channel"), { token, signal }),
    request<TicketCount>(path("ticket-count"), { token, signal }),
    request<Occupancy>(path("occupancy"), { token, signal }),
    request<InventoryAlert[]>(path("inventory-alerts"), { token, signal }),
    request<Ranking>(path("movies/ranking") + "&direction=DESC&limit=5", { token, signal }),
    request<Ranking>(path("movies/ranking") + "&direction=ASC&limit=5", { token, signal }),
  ]);

  const expectedPeriod: Pick<ReportPeriod, "from" | "to" | "timezone"> = {
    from,
    to,
    timezone: "Asia/Ho_Chi_Minh",
  };
  const assertPeriod = (period: ReportPeriod) => {
    if (period.from !== from || period.to !== to || period.timezone !== expectedPeriod.timezone) {
      throw new Error("Máy chủ trả báo cáo không đúng kỳ hoặc múi giờ đã chọn.");
    }
  };
    [revenue.period, breakdown.period, channels.period, count.period, occupancy.period, ranking.period, rankingLow.period]
    .forEach(assertPeriod);
  return {
    revenue,
    breakdown,
    channels,
    count,
    occupancy,
    alerts,
    ranking,
    rankingLow,
  };
}

type Args = { token: string; from: string; to: string };
async function read<K extends keyof Reports>(name: string, key: K, args: Args, signal?: AbortSignal): Promise<Reports[K]> {
  const data = await request<Reports[K]>(`/reports/${name}${name === "inventory-alerts" ? "" : "?" + query({ from: args.from, to: args.to })}`, { token: args.token, signal });
  if (key === "alerts") {
    if (!Array.isArray(data)) throw new Error("Báo cáo tồn kho không đúng định dạng.");
  } else {
    const value = data as Exclude<Reports[keyof Reports], Reports["alerts"]>;
    if (!value || !value.period || value.period.from !== args.from || value.period.to !== args.to || value.period.timezone !== "Asia/Ho_Chi_Minh") throw new Error("Báo cáo trả về không đúng kỳ đã chọn.");
    const arrays: Partial<Record<keyof Reports, string>> = { ranking: "movies", rankingLow: "movies", occupancy: "showtimes", channels: "channels" };
    if (arrays[key] && !Array.isArray((value as unknown as Record<string, unknown>)[arrays[key]!])) throw new Error("Báo cáo không đúng định dạng.");
  }
  return data;
}
export const reportApi = {
  revenue: (a: Args, s?: AbortSignal) => read("revenue", "revenue", a, s),
  breakdown: (a: Args, s?: AbortSignal) => read("revenue-breakdown", "breakdown", a, s),
  channels: (a: Args, s?: AbortSignal) => read("revenue-by-channel", "channels", a, s),
  count: (a: Args, s?: AbortSignal) => read("ticket-count", "count", a, s),
  occupancy: (a: Args, s?: AbortSignal) => read("occupancy", "occupancy", a, s),
  alerts: (a: Args, s?: AbortSignal) => read("inventory-alerts", "alerts", a, s),
  ranking: async (a: Args, direction: "ASC" | "DESC", signal?: AbortSignal) => {
    const r = await request<Reports["ranking"]>(`/reports/movies/ranking?${query({ from: a.from, to: a.to, direction, limit: 5 })}`, { token: a.token, signal });
    if (!r || !Array.isArray(r.movies) || r.direction !== direction || r.period?.from !== a.from || r.period?.to !== a.to) throw new Error("Xếp hạng phim không đúng kỳ hoặc định dạng.");
    return r;
  },
};
