import { request } from "../api";
import type { SeatMap, Hold } from "../types";
export const holdsApi = {
  map: (id: number, token: string, signal?: AbortSignal) =>
    request<SeatMap>(`/showtimes/${id}/seats`, { token, signal }),
  hold: (id: number, seatIds: number[], token: string) =>
    request<Hold>(`/showtimes/${id}/holds`, {
      method: "POST",
      body: { seatIds },
      token,
    }),
  extend: (holdToken: string, token: string) =>
    request<Hold>(`/holds/${encodeURIComponent(holdToken)}/extend`, {
      method: "POST",
      body: { waitingForTransfer: true },
      token,
    }),
  release: (holdToken: string, token: string) =>
    request<void>(`/holds/${encodeURIComponent(holdToken)}`, {
      method: "DELETE",
      token,
    }),
};
