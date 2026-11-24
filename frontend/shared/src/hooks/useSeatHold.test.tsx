import { StrictMode, useEffect } from "react";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, it, expect, vi } from "vitest";
import { AuthProvider } from "../auth/AuthProvider";
import {
  BookingFlowProvider,
  useBookingFlow,
} from "../booking/BookingFlowProvider";
import { useSeatHold } from "./useSeatHold";
import { createMockApi } from "../mocks/core";
afterEach(() => vi.unstubAllGlobals());
function Probe() {
  const { state, dispatch } = useBookingFlow();
  const hold = useSeatHold(1);
  useEffect(() => {
    dispatch({ type: "SELECT", value: [1] });
  }, [dispatch]);
  return (
    <>
      <p>{hold.loading ? "loading" : "ready"}</p>
      <p data-testid="held">{state.hold?.holdToken ?? "none"}</p>
      <button onClick={hold.create}>hold</button>
      <button onClick={hold.refetch}>read</button>
    </>
  );
}
it("recovers a committed hold after the create response is lost; unmount does not release", async () => {
  const api = createMockApi();
  let lose = true;
  const calls: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, options: RequestInit) => {
      const request = new Request(url, options);
      calls.push(request.method + " " + url);
      const response = await api.handle(request);
      if (url.endsWith("/holds") && request.method === "POST" && lose) {
        lose = false;
        throw new TypeError("response lost");
      }
      return response;
    }),
  );
  const view = render(
    <StrictMode>
      <AuthProvider role="STAFF">
        <BookingFlowProvider>
          <Probe />
        </BookingFlowProvider>
      </AuthProvider>
    </StrictMode>,
  );
  await userEvent.type(
    screen.getByLabelText("Tên đăng nhập hoặc email"),
    "staff01",
  );
  await userEvent.type(
    screen.getByLabelText("Mật khẩu", { exact: true }),
    "Reference123!",
  );
  await userEvent.click(screen.getByRole("button", { name: /Đăng nhập/ }));
  await screen.findByText("ready");
  await userEvent.click(screen.getByRole("button", { name: /^hold$/ }));
  await waitFor(() =>
    expect(screen.getByTestId("held").textContent).not.toBe("none"),
  );
  fireEvent.focus(window);
  view.unmount();
  expect(calls.some((c) => c.startsWith("DELETE "))).toBe(false);
});
