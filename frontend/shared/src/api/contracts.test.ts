import { afterEach, describe, expect, it, vi } from "vitest";
import { bookingsApi, normalizeTicket } from "./bookings";
import { requestPage } from "../api";
import { catalogApi } from "./catalog";
import { managementApi } from "./management";
const ok = (data: unknown) => Response.json({ data });
afterEach(() => vi.unstubAllGlobals());
describe("API contracts and recovery", () => {
  it("rejects malformed lists rather than reporting an empty success", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(ok({ error: "bad shape" })));
    await expect(requestPage("/movies")).rejects.toThrow();
  });
  it("does not convert malformed management list data to an empty success", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(ok({ error: "bad shape" })));
    await expect(managementApi.list("auditoriums", "token")).rejects.toThrow(/định dạng/);
  });
  it("loads all catalog pages for selection controls", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const page = Number(new URL(url).searchParams.get("page") ?? 0);
      return ok({ items: [{ id: page + 1, title: "Movie", active: true }], page, size: 1, totalPages: 2, totalElements: 2 });
    }));
    expect((await catalogApi.movieOptions("token")).map((m: { id: number }) => m.id)).toEqual([1, 2]);
  });
  it("requires an issued ticket identity and never invents a code or status", () => {
    expect(() => normalizeTicket({ ticketId: 1, seatId: 2 })).toThrow();
  });
  it("reads a pending booking through the basic booking endpoint", async () => {
    const fetch = vi.fn().mockResolvedValue(ok({ bookingId: 3, bookingCode: "BK-3", status: "PENDING", totalAmount: 80000, tickets: [{ seatId: 2, price: 80000 }], concessions: [], holdUntil: "2026-11-19T12:05:00Z" }));
    vi.stubGlobal("fetch", fetch);
    const booking = await bookingsApi.read(3, "token");
    expect(booking.status).toBe("PENDING");
    expect(booking.tickets).toEqual([]);
    expect(fetch.mock.calls[0][0]).toMatch(/\/bookings\/3$/);
  });
  it("does not swallow issued-ticket read failures", async () => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation(async (url: string) => url.endsWith("/tickets")
      ? Response.json({ code: "BROKEN", message: "Tickets unavailable" }, { status: 503 })
      : ok({ bookingId: 3, bookingCode: "BK-3", status: "PAID", tickets: [] })));
    await expect(bookingsApi.get(3, "token", false)).rejects.toThrow("Tickets unavailable");
  });
  it("recovers a duplicate booking using its stored seats, cart and showtime metadata", async () => {
    const fetch = vi.fn(async (url: string, options?: RequestInit) => {
      if (options?.method === "POST") return Response.json({ code: "BOOKING_ALREADY_EXISTS", message: "Exists", details: { bookingId: "3" } }, { status: 409 });
      if (url.includes("/concessions?")) return ok({ items: [{ id: 5, name: "Stored snack", price: 99000, active: true }], page: 0, size: 100, totalPages: 1, totalElements: 1 });
      if (url.endsWith("/showtimes/9/seats")) return ok({ seats: [{ seatId: 2, row: "A", number: 2 }] });
      if (url.endsWith("/showtimes/9")) return ok({ id: 9, movieTitle: "Stored film", ageRating: "T18", movieId: 1, auditoriumId: 2, startTime: "2030-01-01T10:00:00Z" });
      if (url.endsWith("/me/bookings/3")) return ok({ bookingId: 3, bookingCode: "BK-3", channel: "ONLINE", showtimeId: 9, showtime: { id: 9, movieTitle: "Stored film", ageRating: "T18", movieId: 1, auditoriumId: 2, auditoriumName: "Stored room", auditoriumType: "IMAX", startTime: "2030-01-01T10:00:00Z" }, status: "PENDING", seats: [{ seatId: 2, seatRow: "A", seatNumber: 2, quotedPrice: 70000 }], concessions: [{ itemId: 5, name: "Stored snack", quantity: 2, unitPriceSnapshot: 42000 }], totalAmount: 154000 });
      return ok({ bookingId: 3, bookingCode: "BK-3", showtimeId: 9, status: "PENDING", tickets: [{ seatId: 2, price: 70000 }], concessions: [{ itemId: 5, quantity: 2, unitPriceSnapshot: 42000 }], totalAmount: 154000 });
    });
    vi.stubGlobal("fetch", fetch);
    const result = await bookingsApi.create({ showtimeId: 9, holdToken: "original", concessions: [] }, "token");
    expect(result.showtime).toMatchObject({ id: 9, movieTitle: "Stored film", ageRating: "T18" });
    expect(result.plannedSeats).toEqual([{ seatId: 2, seatLabel: "A2", price: 70000 }]);
    expect(result.concessions).toEqual([{ itemId: 5, name: "Stored snack", quantity: 2, unitPriceSnapshot: 42000 }]);
    expect(result.totalAmount).toBe(154000);
  });
  it("does not recover from an unowned conflict or a malformed recovery ID", async () => {
    for (const details of [{}, { bookingId: "3oops" }, { bookingId: "9007199254740992" }]) {
      const fetch = vi.fn().mockResolvedValue(Response.json({ code: "BOOKING_ALREADY_EXISTS", message: "Exists", details }, { status: 409 }));
      vi.stubGlobal("fetch", fetch);
      await expect(bookingsApi.create({ showtimeId: 9, holdToken: "original", concessions: [] }, "token")).rejects.toThrow("Exists");
      expect(fetch).toHaveBeenCalledTimes(1);
    }
  });
  it("hydrates issued tickets when a recovered booking has already been paid", async () => {
    const fetch = vi.fn(async (url: string, options?: RequestInit) => {
      if (options?.method === "POST") return Response.json({ code: "BOOKING_ALREADY_EXISTS", message: "Exists", details: { bookingId: "3" } }, { status: 409 });
      if (url.endsWith("/pos/bookings/3")) return ok({ bookingId: 3, bookingCode: "BK-3", channel: "POS", status: "PAID", showtime: { id: 9, movieTitle: "Stored film", ageRating: "T18" }, tickets: [{ ticketId: 4, ticketCode: "TK-4", status: "VALID", seatId: 2, priceSnapshot: 70000 }] });
      return ok({ bookingId: 3, bookingCode: "BK-3", channel: "POS", showtimeId: 9, status: "PAID", tickets: [{ seatId: 2, price: 70000 }] });
    });
    vi.stubGlobal("fetch", fetch);
    const result = await bookingsApi.create({ showtimeId: 9, holdToken: "original", concessions: [] }, "token");
    expect(result.status).toBe("PAID");
    expect(result.tickets[0]).toMatchObject({ ticketCode: "TK-4", status: "VALID", movieTitle: "Stored film", ageRating: "T18" });
  });
  it("surfaces a status failure after successful metadata save", async () => {
    const fetch = vi.fn().mockResolvedValueOnce(ok({ id: 3, active: true })).mockResolvedValueOnce(Response.json({ message: "Status conflict" }, { status: 409 }));
    vi.stubGlobal("fetch", fetch);
    await expect(managementApi.save("movies", { title: "Movie", active: false }, "token", 3)).rejects.toThrow(/Status conflict/);
    expect(fetch).toHaveBeenCalledTimes(2);
  });
  it("maps the physical seat editor to the backend update DTO", async () => {
    const fetch = vi.fn().mockResolvedValue(ok({ id: 7, row: "C", number: 2, type: "VIP" }));
    vi.stubGlobal("fetch", fetch);
    await managementApi.save("seats", { row: "c", number: 2, seatType: "VIP" }, "manager", 7);
    expect(fetch.mock.calls[0][0]).toMatch(/\/seats\/7$/);
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ row: "C", number: 2, type: "VIP" });
  });
});

import { reportApi } from "./reports";
import { refundsApi } from "./refunds";
const period = { from: "2026-11-19", to: "2026-11-19", timezone: "Asia/Ho_Chi_Minh", basis: "PAYMENT_DATE", asOf: "2026-11-19T10:00:00Z" };
const args = { token: "test", from: period.from, to: period.to };
it("rejects a ranking array and a mismatched report period", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(ok([])).mockResolvedValueOnce(ok({ period: { ...period, from: "2026-11-18" } })));
  await expect(reportApi.ranking(args, "DESC")).rejects.toThrow();
  await expect(reportApi.revenue(args)).rejects.toThrow(/kỳ/);
});
it("does not subtract refunds twice from report data", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(ok({ period, direction: "ASC", movies: [{ movieId: 1, title: "Phim", ticketCount: 1, totalRevenue: 160000, refundAmount: 70000, revenue: 90000 }] })));
  expect((await reportApi.ranking(args, "ASC")).movies[0].revenue).toBe(90000);
});
it("sends exactly one lookup type and maps backend eligibility without invented refund amount", async () => {
  const fetch = vi.fn().mockImplementation(async () => ok({ bookingId: 1, bookingCode: "BK-ONE", channel: "ONLINE", showtimeId: 3, startsAt: "2026-11-19T12:00:00Z", serverTime: "2026-11-19T11:00:00Z", tickets: [{ ticketId: 9, ticketCode: "TK-9", seatId: 2, row: "C", seatNumber: 2, status: "USED", priceSnapshot: 100000, eligible: false, reasonCode: "TICKET_NOT_REFUNDABLE", refundableAmount: 0 }] }));
  vi.stubGlobal("fetch", fetch);
  const result = await refundsApi.eligibility(" BK-ONE ", "bookingCode", "staff");
  expect(fetch.mock.calls[0][0]).toMatch(/eligibility\?bookingCode=BK-ONE$/);
  expect(result.tickets[0]).toMatchObject({ seatLabel: "C2", refundableAmount: 0, eligible: false });
});
it("records Manager cash confirmation before refund and retries the identical refund intent", async () => {
  const fetch = vi.fn().mockImplementation(async () => ok({ refundId: 1, overrideToken: "a".repeat(64), cashConfirmedAt: "2030-01-01T00:00:00Z" }));
  vi.stubGlobal("fetch", fetch);
  const intent = { bookingId: 1, ticketIds: [2, 3], reason: "Khách yêu cầu" };
  await refundsApi.override({ ...intent, username: "manager", pin: "123456" }, "staff");
  expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ ...intent, username: "manager", pin: "123456", scope: "REFUND_APPROVAL" });
  const overrideToken = "a".repeat(64);
  await refundsApi.confirmCash(overrideToken, "staff");
  expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({ overrideToken });
  const payload = { ...intent, overrideToken };
  await refundsApi.refund(payload, "staff"); await refundsApi.refund(payload, "staff");
  expect(fetch.mock.calls[2][1].body).toBe(fetch.mock.calls[3][1].body);
});
