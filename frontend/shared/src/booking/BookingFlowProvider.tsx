import {
  createContext,
  useContext,
  useReducer,
  type ReactNode,
  type Dispatch,
} from "react";
import {
  bookingReducer,
  initialBooking,
  type BookingAction,
  type BookingState,
} from "./bookingReducer";
const Context = createContext<{
  state: BookingState;
  dispatch: Dispatch<BookingAction>;
} | null>(null);
export function BookingFlowProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(bookingReducer, initialBooking);
  return (
    <Context.Provider value={{ state, dispatch }}>{children}</Context.Provider>
  );
}
export function useBookingFlow() {
  const value = useContext(Context);
  if (!value) throw new Error("Booking flow required");
  return value;
}
