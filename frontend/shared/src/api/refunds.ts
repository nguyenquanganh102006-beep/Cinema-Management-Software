import { request } from "../api";
import type { Eligibility, Refund } from "../types";
export const refundsApi = {
  eligibility: (code: string, type: "bookingCode" | "ticketCode", token: string) => {
    const value = code.trim();
    return request<{
      bookingId: number;
      bookingCode: string;
      channel: "POS" | "ONLINE";
      showtimeId: number;
      startsAt: string;
      serverTime: string;
      tickets: {
        ticketId: number;
        ticketCode: string;
        seatId: number;
        row: string;
        seatNumber: number;
        status: "VALID" | "USED" | "CANCELLED";
        priceSnapshot: number;
        eligible: boolean;
        reasonCode?: string;
        refundableAmount: number;
      }[];
    }>("/refunds/eligibility?" + new URLSearchParams({ [type]: value }), { token })
      .then((raw): Eligibility => ({
        bookingId: raw.bookingId,
        bookingCode: raw.bookingCode,
        channel: raw.channel,
        showtimeId: raw.showtimeId,
        startsAt: raw.startsAt,
        serverTime: raw.serverTime,
        tickets: raw.tickets.map((ticket) => ({
          ticketId: ticket.ticketId,
          ticketCode: ticket.ticketCode,
          seatId: ticket.seatId,
          seatLabel: `${ticket.row}${ticket.seatNumber}`,
          priceSnapshot: ticket.priceSnapshot,
          status: ticket.status,
          eligible: ticket.eligible,
          reason: ticket.reasonCode,
          refundableAmount: ticket.refundableAmount,
        })),
      }));
  },
  override: (
    input: { username: string; pin: string; bookingId: number; ticketIds: number[]; reason: string },
    token: string,
  ) =>
    request<{ overrideToken: string; expiresAt: string; approvedRefundAmount: number }>(
      "/manager-overrides",
      {
        method: "POST",
        body: { ...input, scope: "REFUND_APPROVAL", cashConfirmed: true },
        token,
      },
    ),
  refund: (
    body: {
      bookingId: number;
      ticketIds: number[];
      reason: string;
      overrideToken: string;
      cashConfirmed: boolean;
    },
    token: string,
  ) => request<Refund>("/refunds", { method: "POST", body, token }),
};
