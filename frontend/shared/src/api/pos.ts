import { request } from "../api";
import type { Payment, Ticket } from "../types";
import { normalizeTicket } from "./bookings";

export const posApi = {
  cash: async (
    id: number,
    cashReceived: number,
    ageDocumentChecked: boolean,
    token: string,
  ): Promise<Payment> => {
    const raw = await request<
      Payment & { totalAmount?: number }
    >(`/bookings/${id}/payments/cash`, {
      method: "POST",
      body: { cashReceived, ageDocumentChecked },
      token,
    });
    return {
      ...raw,
      paymentStatus: raw.paymentStatus ?? "PAID",
      paymentMethod: raw.paymentMethod ?? "CASH",
      amount: Number(raw.amount ?? raw.totalAmount ?? 0),
    };
  },
  vietqr: async (
    id: number,
    ageDocumentChecked: boolean,
    token: string,
  ): Promise<Payment> => {
    const raw = await request<Payment>(`/bookings/${id}/payments/vietqr`, {
      method: "POST",
      body: { ageDocumentChecked },
      token,
    });
    return {
      ...raw,
      paymentStatus: raw.paymentStatus ?? "PENDING",
      paymentMethod: raw.paymentMethod ?? "VIETQR",
      amount: Number(raw.amount ?? 0),
    };
  },
  confirm: async (id: number, token: string): Promise<Payment> => {
    const raw = await request<Payment>(
      `/bookings/${id}/payments/vietqr/confirm`,
      {
        method: "POST",
        token,
      },
    );
    return {
      ...raw,
      paymentStatus: raw.paymentStatus ?? "PAID",
      paymentMethod: raw.paymentMethod ?? "VIETQR",
      amount: Number(raw.amount ?? 0),
    };
  },
  lookup: async (code: string, token: string): Promise<Ticket[]> => {
    const clean = code.trim();
    const rows = await request<Ticket[]>(
      `/tickets/lookup?code=${encodeURIComponent(clean)}`,
      { token },
    );
    return rows.map((t) => normalizeTicket(t));
  },
  validate: async (
    ticketCode: string,
    showtimeId: number,
    ageDocumentChecked: boolean,
    token: string,
  ): Promise<Ticket> => {
    const raw = await request<
      Ticket & { seatRow?: string; seatNumber?: number }
    >("/tickets/validate", {
      method: "POST",
      body: { ticketCode, showtimeId, ageDocumentChecked },
      token,
    });
    const ticket = await request<Ticket & { seatRow?: string; seatNumber?: number }>(`/tickets/${encodeURIComponent(ticketCode)}`, { token });
    return normalizeTicket(ticket);
  },
};
