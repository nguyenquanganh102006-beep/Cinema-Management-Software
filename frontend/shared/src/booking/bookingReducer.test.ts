import { describe, it, expect } from "vitest";
import { bookingReducer, initialBooking } from "./bookingReducer";
import { fixtures } from "../mocks/fixtures";
const show = fixtures().showtimes[0],
  hold = {
    holdToken: "hold-1",
    showtimeId: show.id,
    seatIds: [1, 2],
    serverTime: new Date().toISOString(),
    holdUntil: new Date(Date.now() + 300000).toISOString(),
    extensionUsed: false,
  };
describe("booking flow isolation", () => {
  it("retains the exact creation intent across expiry until readback or explicit reset", () => {
    const intent = { showtimeId: show.id, holdToken: hold.holdToken, concessions: [{ itemId: 1, quantity: 2 }] };
    let state = bookingReducer({ ...initialBooking, showtime: show, hold }, { type: "CREATE_INTENT", value: intent });
    state = bookingReducer(state, { type: "EXPIRE" });
    expect(state.creation).toEqual(intent);
    expect(state.hold).toBeNull();
    expect(bookingReducer(state, { type: "CART", value: [] }).creation).toEqual(intent);
    expect(bookingReducer(state, { type: "RESET" }).creation).toBeNull();
  });
  it("clears seats, basket, quote and hold when changing showtime", () => {
    let state = bookingReducer(initialBooking, {
      type: "SHOWTIME",
      value: show,
    });
    state = bookingReducer(state, {
      type: "CART",
      value: [{ itemId: 1, quantity: 2 }],
    });
    state = bookingReducer(state, { type: "HOLD", value: hold });
    state = bookingReducer(state, {
      type: "SHOWTIME",
      value: { ...show, id: 99 },
    });
    expect(state).toEqual({ ...initialBooking, showtime: { ...show, id: 99 } });
  });
  it("locks local selection while holding and clears dependent values at expiry", () => {
    let state = bookingReducer(initialBooking, { type: "HOLD", value: hold });
    expect(
      bookingReducer(state, { type: "SELECT", value: [3] }).selected,
    ).toEqual([1, 2]);
    state = bookingReducer(state, { type: "EXPIRE" });
    expect(state.hold).toBeNull();
    expect(state.selected).toEqual([]);
    expect(state.cart).toEqual([]);
  });
  it("keeps quote while polling the same hold", () => {
    let state = bookingReducer(initialBooking, { type: "HOLD", value: hold });
    state = bookingReducer(state, {
      type: "QUOTE",
      value: { tickets: [], total: 70000 },
    });
    expect(
      bookingReducer(state, { type: "HOLD", value: hold }).quote?.total,
    ).toBe(70000);
  });
});
