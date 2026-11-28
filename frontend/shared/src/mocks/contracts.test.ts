import { afterEach, describe, expect, it, vi } from "vitest";
import { createMockApi } from "./core";
afterEach(() => vi.useRealTimers());

async function setup() {
  const api = createMockApi();
  const call = (path: string, token = "", method = "GET", body?: unknown) => api.handle(new Request("http://localhost/api/v1" + path, {
    method, headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  }));
  const login = async (username: string) => (await (await call("/auth/login", "", "POST", { username, password: "Reference123!" })).json()).data.accessToken as string;
  return { api, call, manager: await login("manager01"), staff: await login("staff01"), customer: await login("customer01") };
}

describe("mock contracts matched to backend DTOs", () => {
  it("returns a same-origin mock payment link regardless of the browser hostname", async () => {
    const { call, customer } = await setup();
    const hold = (await (await call("/showtimes/1/holds", customer, "POST", { seatIds: [1] })).json()).data;
    const booking = (await (await call("/bookings", customer, "POST", { showtimeId: 1, holdToken: hold.holdToken, concessions: [], ageConfirmed: true })).json()).data;
    const response = await call(`/bookings/${booking.bookingId}/payments/vnpay`, customer, "POST", {});
    expect(response.status).toBe(200);
    const { paymentUrl } = (await response.json()).data;
    for (const origin of ["http://127.0.0.1:3003", "http://localhost:3003"]) {
      expect(new URL(paymentUrl, origin).origin).toBe(origin);
    }
  });
  it("matches pricing quote roles, validation and seat ordering", async () => {
    const { call, manager, staff, customer } = await setup();
    for (const token of [manager, staff, customer]) {
      const response = await call("/pricing/quote", token, "POST", { showtimeId: 1, seatIds: [2, 1] });
      expect(response.status).toBe(200);
      const data = (await response.json()).data;
      expect(data.showtimeId).toBe(1);
      expect(data.seats.map((ticket: { seatId: number }) => ticket.seatId)).toEqual([1, 2]);
      expect(data.ticketTotal).toBeGreaterThan(0);
    }
    for (const body of [{ showtimeId: 1 }, { showtimeId: 1, seatIds: [] }, { showtimeId: 1, seatIds: [1, 1] }, { showtimeId: 0, seatIds: [1] }, { showtimeId: 1, seatIds: Array(101).fill(1) }]) {
      const response = await call("/pricing/quote", staff, "POST", body);
      expect(response.status).toBe(400);
      expect((await response.json()).code).toBe("VALIDATION_ERROR");
    }
  });
  it("paginates concessions and hides inactive items for sales roles", async () => {
    const { api, call, manager, staff, customer } = await setup();
    api.db.concessions[0].active = false;
    for (const token of [staff, customer]) {
      const result = await (await call("/concessions?active=false&page=0&size=1", token)).json();
      expect(result.data.items).toHaveLength(1);
      expect(result.data.items[0].active).toBe(true);
      expect(result.data.size).toBe(1);
      expect(result.data.totalElements).toBe(api.db.concessions.length - 1);
      expect((await call(`/concessions/${api.db.concessions[0].id}`, token)).status).toBe(404);
    }
    const hidden = await (await call("/concessions?active=false", manager)).json();
    expect(hidden.data.items).toHaveLength(1);
  });

  it("returns current availability after item/combo mutation", async () => {
    const { api, call, manager } = await setup();
    const item = api.db.concessions.find((i) => !i.combo)!;
    api.db.inventory.find((i) => i.itemId === item.id)!.stockQuantity = 24;
    const updated = await (await call(`/concessions/${item.id}`, manager, "PATCH", { name: item.name, price: 22000 })).json();
    expect(updated.data.availableQuantity).toBe(24);
    const created = await call("/concessions/combos", manager, "POST", { name: "Contract combo", price: 40000, components: [{ itemId: item.id, quantity: 2 }] });
    expect(created.status).toBe(201);
    expect((await created.json()).data.availableQuantity).toBe(12);
  });

  it("rejects legacy gate/status payloads and invalid reset passwords", async () => {
    const { call, manager, staff } = await setup();
    for (const [path, token, method, body] of [
      ["/tickets/validate", staff, "POST", { ticketCode: "TK-example", ageDocumentChecked: true }],
      ["/staff/2/status", manager, "PATCH", { status: "LOCKED" }],
      ["/staff/2/reset-password", manager, "POST", { newPassword: "short" }],
    ] as const) {
      const response = await call(path, token, method, body);
      expect(response.status).toBe(400);
      expect((await response.json()).code).toBe("VALIDATION_ERROR");
    }
  });

  it("validates approval scope and matches the backend's sorted, session-bound refund intent", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    const { call, staff } = await setup();
    const hold = (await (await call("/showtimes/1/holds", staff, "POST", { seatIds: [1, 2] })).json()).data;
    const booking = (await (await call("/bookings", staff, "POST", { showtimeId: 1, holdToken: hold.holdToken, concessions: [], ageConfirmed: true })).json()).data;
    expect((await call(`/bookings/${booking.bookingId}/payments/cash`, staff, "POST", { cashReceived: 500000, ageDocumentChecked: true })).status).toBe(200);
    const eligibility = (await (await call(`/refunds/eligibility?bookingCode=${booking.bookingCode}`, staff)).json()).data;
    expect(eligibility.tickets).toHaveLength(2);
    const intent = { bookingId: eligibility.bookingId, ticketIds: eligibility.tickets.map((t: { ticketId: number }) => t.ticketId), reason: " Contract refund " };
    const approval = { ...intent, username: "manager01", pin: "123456", scope: "REFUND_APPROVAL" };
    expect((await call("/manager-overrides", staff, "POST", { ...approval, scope: "OLD_SCOPE" })).status).toBe(400);
    expect((await call("/manager-overrides", staff, "POST", { ...approval, ticketIds: [...intent.ticketIds, intent.ticketIds[0]] })).status).toBe(400);
    const approvedAt = Date.now();
    const approved = (await (await call("/manager-overrides", staff, "POST", approval)).json()).data;
    expect(approved.overrideToken).toMatch(/^[a-f0-9]{64}$/);
    expect(Date.parse(approved.expiresAt) - approvedAt).toBe(180000);
    const confirmation = await call("/manager-overrides/cash-confirmation", staff, "POST", { overrideToken: approved.overrideToken });
    expect(confirmation.status).toBe(200);
    const payload = { ...intent, reason: intent.reason.trim(), ticketIds: [...intent.ticketIds].reverse(), overrideToken: approved.overrideToken };
    const mismatch = await call("/refunds", staff, "POST", { ...payload, reason: "Changed intent" });
    expect(mismatch.status).toBe(403);
    expect((await mismatch.json()).code).toBe("MANAGER_OVERRIDE_MISMATCH");
    const refunded = await call("/refunds", staff, "POST", payload);
    expect(refunded.status).toBe(200);
    const result = await refunded.json();
    expect(result.data).toMatchObject({ bookingId: intent.bookingId, refundStatus: "FULL", status: "COMPLETED" });
    vi.setSystemTime(Date.now() + 181000);
    expect(await (await call("/refunds", staff, "POST", payload)).json()).toEqual(result);
  });

  it("expires an unused approval and returns zero refundable value for an ineligible ticket", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    const { call, staff } = await setup();
    const eligibility = (await (await call("/refunds/eligibility?bookingCode=BK-POS-DEMO", staff)).json()).data;
    const intent = { bookingId: eligibility.bookingId, ticketIds: [eligibility.tickets[0].ticketId], reason: "Contract refund" };
    const approved = (await (await call("/manager-overrides", staff, "POST", { ...intent, username: "manager01", pin: "123456", scope: "REFUND_APPROVAL" })).json()).data;
    vi.setSystemTime(Date.now() + 180000);
    const expired = await call("/manager-overrides/cash-confirmation", staff, "POST", { overrideToken: approved.overrideToken });
    expect(expired.status).toBe(403);
    expect((await expired.json()).code).toBe("MANAGER_OVERRIDE_EXPIRED");
    vi.setSystemTime(Date.now() + 3600000);
    const late = (await (await call("/refunds/eligibility?bookingCode=BK-POS-DEMO", staff)).json()).data;
    expect(late.tickets[0]).toMatchObject({ eligible: false, refundableAmount: 0, reasonCode: "REFUND_DEADLINE_PASSED" });
  });
});
