import { request } from "../api";
import type { Quote } from "../types";
export async function quote(
  showtimeId: number,
  seatIds: number[],
  token: string,
  signal?: AbortSignal,
): Promise<Quote> {
  const raw = await request<{
    tickets?: Quote["tickets"];
    seats?: Quote["tickets"];
    total?: number;
    ticketTotal?: number;
  }>("/pricing/quote", {
    method: "POST",
    body: { showtimeId, seatIds },
    token,
    signal,
  });
  return {
    tickets: raw.tickets ?? raw.seats ?? [],
    total: Number(raw.total ?? raw.ticketTotal ?? 0),
  };
}
