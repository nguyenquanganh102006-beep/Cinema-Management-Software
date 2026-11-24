import { ApiError, request, requestPage, query } from "../api";
import { catalogApi } from "./catalog";
import { holdsApi } from "./holds";
import type { Booking, BookingInput, Payment, Ticket } from "../types";

type RawBooking = Omit<Partial<Booking>, "tickets" | "showtime" | "payment" | "concessions" | "plannedSeats"> & {
  bookingId: number;
  bookingCode: string;
  status?: Booking["status"];
  paymentStatus?: Payment["paymentStatus"];
  paymentMethod?: Payment["paymentMethod"];
  paidAt?: string;
  totalAmount?: number;
  holdUntil?: string;
  serverTime?: string;
  showtimeId?: number;
  showtime?: Partial<Booking["showtime"]> & { showtimeId?: number };
  plannedSeats?: { seatId: number; seatLabel?: string; price: number }[];
  seats?: {
    seatId: number;
    seatRow?: string;
    seatNumber?: number;
    quotedPrice?: number;
    price?: number;
  }[];
  tickets?: (Partial<Ticket> & {
    ticketId?: number;
    ticketCode?: string;
    seatId?: number;
    seatRow?: string;
    seatNumber?: number;
    seatLabel?: string;
    priceSnapshot?: number;
    price?: number;
  })[];
  concessions?: {
    itemId: number;
    name?: string;
    itemName?: string;
    quantity: number;
    unitPriceSnapshot?: number;
    unitPrice?: number;
  }[];
  payment?: Partial<Payment> & {
    method?: Payment["paymentMethod"];
    status?: Payment["paymentStatus"];
  };
};

export function normalizeTicket(
  t: NonNullable<RawBooking["tickets"]>[number],
  show?: Partial<Booking["showtime"]> & { showtimeId?: number },
): Ticket {
  if (!t.ticketCode || !t.ticketId || !t.status) throw new Error("Dữ liệu vé phát hành không hợp lệ.");
  const code = t.ticketCode;
  const seatLabel =
    t.seatLabel ??
    (t.seatRow !== undefined && t.seatNumber !== undefined
      ? `${t.seatRow}${t.seatNumber}`
      : `Ghế #${t.seatId ?? ""}`);
  return {
    ticketId: Number(t.ticketId ?? t.seatId ?? 0),
    ticketCode: code,
    seatId: Number(t.seatId ?? 0),
    seatLabel,
    priceSnapshot: Number(t.priceSnapshot ?? t.price ?? 0),
    status: t.status as Ticket["status"],
    showtimeId: Number(t.showtimeId ?? show?.id ?? show?.showtimeId ?? 0),
    movieTitle: t.movieTitle ?? show?.movieTitle ?? "",
    auditoriumName: t.auditoriumName ?? show?.auditoriumName ?? "",
    startTime: t.startTime ?? show?.startTime ?? "",
    ageRating: t.ageRating ?? show?.ageRating ?? "P",
    qrPayload: t.qrPayload ?? code,
  };
}

export function normalizeBooking(
  raw: RawBooking,
  fallback?: Partial<Booking>,
): Booking {
  const showRaw = (raw.showtime ?? fallback?.showtime) as
    | (Partial<Booking["showtime"]> & { showtimeId?: number })
    | undefined;
  const showtime: Booking["showtime"] = {
    id: Number(showRaw?.id ?? showRaw?.showtimeId ?? raw.showtimeId ?? 0),
    movieId: Number(showRaw?.movieId ?? 0),
    movieTitle: showRaw?.movieTitle ?? fallback?.showtime?.movieTitle ?? "",
    ageRating: showRaw?.ageRating ?? fallback?.showtime?.ageRating ?? "P",
    durationMinutes: Number(
      showRaw?.durationMinutes ?? fallback?.showtime?.durationMinutes ?? 0,
    ),
    auditoriumId: Number(showRaw?.auditoriumId ?? 0),
    auditoriumName:
      showRaw?.auditoriumName ?? fallback?.showtime?.auditoriumName ?? "",
    auditoriumType:
      showRaw?.auditoriumType ??
      fallback?.showtime?.auditoriumType ??
      "STANDARD_2D",
    startTime:
      showRaw?.startTime ??
      fallback?.showtime?.startTime ??
      "",
    endTime:
      showRaw?.endTime ??
      fallback?.showtime?.endTime ??
      "",
    basePrice: Number(
      showRaw?.basePrice ?? fallback?.showtime?.basePrice ?? 0,
    ),
  };

  const seatLines =
    raw.plannedSeats ??
    raw.seats?.map((s) => ({
      seatId: s.seatId,
      seatLabel:
        s.seatRow !== undefined && s.seatNumber !== undefined
          ? `${s.seatRow}${s.seatNumber}`
          : `Ghế ${s.seatId}`,
      price: Number(s.quotedPrice ?? s.price ?? 0),
    })) ??
    (raw.tickets && raw.tickets.every((t) => !t.ticketCode)
      ? raw.tickets.map((t) => ({
          seatId: Number(t.seatId ?? 0),
          seatLabel: t.seatLabel ?? `Ghế ${t.seatId}`,
          price: Number(
            (t as { price?: number }).price ?? t.priceSnapshot ?? 0,
          ),
        }))
      : undefined) ??
    fallback?.plannedSeats ??
    [];

  const issuedTickets = (raw.tickets ?? [])
    .filter((t) => Boolean(t.ticketCode))
    .map((t) => normalizeTicket(t, showtime));

  const seatLabelById = new Map<number, string>();
  for (const s of seatLines) {
    if (s.seatLabel) seatLabelById.set(s.seatId, s.seatLabel);
  }
  for (const t of issuedTickets) {
    if (t.seatLabel && !t.seatLabel.startsWith("Ghế #"))
      seatLabelById.set(t.seatId, t.seatLabel);
  }

  const plannedSeats = (
    seatLines.length
      ? seatLines
      : issuedTickets.map((t) => ({
          seatId: t.seatId,
          seatLabel: t.seatLabel,
          price: t.priceSnapshot,
        }))
  ).map((s) => ({
    seatId: Number(s.seatId),
    seatLabel:
      seatLabelById.get(Number(s.seatId)) ?? s.seatLabel ?? `Ghế ${s.seatId}`,
    price: Number(s.price),
  }));

  for (const t of issuedTickets) {
    const label = seatLabelById.get(t.seatId);
    if (label) t.seatLabel = label;
  }

  const concessions = (
    (raw.concessions ?? fallback?.concessions ?? []) as {
      itemId: number;
      name?: string;
      itemName?: string;
      quantity: number;
      unitPriceSnapshot?: number;
      unitPrice?: number;
    }[]
  ).map((c) => ({
    itemId: Number(c.itemId),
    name: c.name ?? c.itemName ?? `Món #${c.itemId}`,
    quantity: Number(c.quantity),
    unitPriceSnapshot: Number(c.unitPriceSnapshot ?? c.unitPrice ?? 0),
  }));

  const rawPayment = (raw.payment ?? fallback?.payment) as
    | (Partial<Payment> & {
        method?: Payment["paymentMethod"];
        status?: Payment["paymentStatus"];
      })
    | undefined;
  const paymentMethod =
    rawPayment?.paymentMethod ?? rawPayment?.method ?? raw.paymentMethod;
  const paymentStatus =
    rawPayment?.paymentStatus ?? rawPayment?.status ?? raw.paymentStatus;
  const payment: Payment | undefined =
    rawPayment || paymentMethod
      ? {
          paymentStatus: paymentStatus ?? "PENDING",
          paymentMethod: paymentMethod ?? "CASH",
          amount: Number(rawPayment?.amount ?? raw.totalAmount ?? 0),
          paidAt: rawPayment?.paidAt ?? raw.paidAt,
          cashReceived:
            rawPayment?.cashReceived !== undefined
              ? Number(rawPayment.cashReceived)
              : undefined,
          change:
            rawPayment?.change !== undefined
              ? Number(rawPayment.change)
              : rawPayment?.cashReceived !== undefined
                ? Math.max(
                    0,
                    Number(rawPayment.cashReceived) -
                      Number(rawPayment?.amount ?? raw.totalAmount ?? 0),
                  )
                : undefined,
          qrPayload: rawPayment?.qrPayload,
          bankName: rawPayment?.bankName,
          bankAccount: rawPayment?.bankAccount,
          transferContent: rawPayment?.transferContent,
        }
      : undefined;

  return {
    bookingId: Number(raw.bookingId),
    bookingCode: raw.bookingCode,
    status:
      raw.status ??
      (raw.paymentStatus === "PAID" ? "PAID" : undefined) ??
      fallback?.status ??
      "PENDING",
    channel: raw.channel ?? fallback?.channel ?? "ONLINE",
    showtime,
    holdUntil:
      raw.holdUntil ?? fallback?.holdUntil ?? "",
    serverTime: raw.serverTime ?? "",
    plannedSeats,
    tickets: issuedTickets.length ? issuedTickets : (fallback?.tickets ?? []),
    concessions,
    totalAmount: Number(raw.totalAmount ?? fallback?.totalAmount ?? 0),
    payment,
    exceptionReason: raw.exceptionReason ?? fallback?.exceptionReason,
    refunds: raw.refunds ?? fallback?.refunds,
    refundStatus: raw.refundStatus,
  };
}

export const bookingsApi = {
  read: async (id: number, token: string, signal?: AbortSignal, context?: Partial<Booking>) => {
    const raw = await request<RawBooking>(`/bookings/${id}`, { token, signal });
    return normalizeBooking(raw, context);
  },
  create: async (
    body: BookingInput,
    token: string,
    context?: Partial<Booking>,
  ): Promise<Booking> => {
    const payload: Record<string, unknown> = {
      showtimeId: body.showtimeId,
      holdToken: body.holdToken,
      concessions: body.concessions,
    };
    if (body.ageConfirmed !== undefined) {
      payload.ageConfirmed = body.ageConfirmed;
    }
    try {
      const raw = await request<RawBooking>("/bookings", { method: "POST", body: payload, token });
      return normalizeBooking(raw, context);
    } catch (error) {
      const id = error instanceof ApiError ? error.details.bookingId : undefined;
      if (!(error instanceof ApiError) || error.status !== 409 || error.code !== "BOOKING_ALREADY_EXISTS"
        || typeof id !== "string" || !/^[1-9]\d*$/.test(id) || !Number.isSafeInteger(Number(id))) throw error;
      const raw = await request<RawBooking>(`/bookings/${id}`, { token });
      if (raw.status !== "PENDING") return bookingsApi.get(Number(id), token, raw.channel === "POS" || context?.channel === "POS");
      const showtimeId = Number(raw.showtimeId ?? raw.showtime?.id ?? raw.showtime?.showtimeId);
      const [showtime, map, items] = await Promise.all([
        catalogApi.showtime(showtimeId, token), holdsApi.map(showtimeId, token), catalogApi.concessions(token),
      ]);
      const result = normalizeBooking(raw, { showtime });
      result.plannedSeats = result.plannedSeats.map((seat) => {
        const physical = map.seats.find((s) => s.seatId === seat.seatId);
        return { ...seat, seatLabel: physical ? `${physical.row}${physical.number}` : seat.seatLabel };
      });
      result.concessions = result.concessions.map((line) => ({ ...line, name: items.find((item) => item.id === line.itemId)?.name ?? line.name }));
      return result;
    }
  },
  get: async (
    id: number,
    token: string,
    pos = false,
    signal?: AbortSignal,
    fallback?: Partial<Booking>,
  ): Promise<Booking> => {
    const raw = await request<RawBooking>(
      `${pos ? "/pos" : "/me"}/bookings/${id}`,
      { token, signal },
    );
    const normalized = normalizeBooking(raw, fallback);
    if (normalized.status === "PAID" && normalized.tickets.length === 0) {
      const tickets = await request<NonNullable<RawBooking["tickets"]>>(
        `/bookings/${id}/tickets`,
        { token, signal },
      );
      normalized.tickets = (Array.isArray(tickets) ? tickets : []).map((t) =>
        normalizeTicket(t, normalized.showtime),
      );
    }
    return normalized;
  },
  list: async (
    token: string,
    pos = false,
    filters: Record<string, string | number | undefined> = {},
    signal?: AbortSignal,
  ) => {
    const page = await requestPage<RawBooking>(
      `${pos ? "/pos" : "/me"}/bookings?${query(filters)}`,
      { token, signal },
    );
    return {
      data: page.data.map((b) =>
        normalizeBooking(b, { channel: pos ? "POS" : "ONLINE" }),
      ),
      meta: page.meta,
    };
  },
  vnpay: (id: number, token: string) =>
    request<{ paymentUrl: string; expiresAt: string }>(
      `/bookings/${id}/payments/vnpay`,
      { method: "POST", token },
    ),
};
