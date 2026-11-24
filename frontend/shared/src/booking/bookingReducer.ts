import type { Showtime, CartLine, Hold, Quote, Booking, BookingInput } from "../types";
export interface BookingState {
  showtime: Showtime | null;
  selected: number[];
  cart: CartLine[];
  hold: Hold | null;
  quote: Quote | null;
  booking: Booking | null;
  creation: BookingInput | null;
}
export const initialBooking: BookingState = {
  showtime: null,
  selected: [],
  cart: [],
  hold: null,
  quote: null,
  booking: null,
  creation: null,
};
export type BookingAction =
  | { type: "SHOWTIME"; value: Showtime }
  | { type: "SELECT"; value: number[] }
  | { type: "CART"; value: CartLine[] }
  | { type: "HOLD"; value: Hold }
  | { type: "QUOTE"; value: Quote }
  | { type: "BOOKING"; value: Booking }
  | { type: "CREATE_INTENT"; value: BookingInput | null }
  | { type: "RELEASE" }
  | { type: "EXPIRE" }
  | { type: "RESET" };
export function bookingReducer(
  state: BookingState,
  action: BookingAction,
): BookingState {
  switch (action.type) {
    case "SHOWTIME":
      return state.showtime?.id === action.value.id
        ? state
        : { ...initialBooking, showtime: action.value };
    case "SELECT":
      return state.hold || state.creation
        ? state
        : { ...state, selected: action.value, quote: null, booking: null };
    case "CART":
      return state.creation ? state : { ...state, cart: action.value };
    case "HOLD":
      return {
        ...state,
        hold: action.value,
        selected: action.value.seatIds,
        quote:
          state.hold?.holdToken === action.value.holdToken ||
          state.selected.join() === action.value.seatIds.join()
            ? state.quote
            : null,
      };
    case "QUOTE":
      return { ...state, quote: action.value };
    case "BOOKING":
      return { ...state, booking: action.value, creation: null };
    case "CREATE_INTENT":
      return { ...state, creation: action.value };
    case "RELEASE":
      return { ...state, hold: null, selected: [], quote: null, booking: null, creation: null };
    case "EXPIRE":
      return {
        ...state,
        hold: null,
        selected: [],
        quote: null,
        cart: [],
        booking: state.booking?.status === "PAID" ? state.booking : null,
      };
    case "RESET":
      return { ...initialBooking };
  }
}
