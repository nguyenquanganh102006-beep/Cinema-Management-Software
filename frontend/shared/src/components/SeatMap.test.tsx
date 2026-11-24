import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { it, expect, vi } from "vitest";
import { SeatMap } from "./SeatMap";
import { fixtures } from "../mocks/fixtures";
it("selects both Sweetbox seats from a single keyboard action", async () => {
  const select = vi.fn();
  render(
    <SeatMap
      seats={fixtures().seats}
      selected={[]}
      locked={false}
      onSelect={select}
    />,
  );
  const seat = screen.getByRole("button", { name: "B5 · SWEETBOX · Trống" });
  seat.focus();
  await userEvent.keyboard("{Enter}");
  expect(select).toHaveBeenCalledWith([11, 12]);
});
it("blocks sold seats and an incomplete Sweetbox pair", () => {
  const seats = fixtures().seats;
  seats[0].status = "BOOKED";
  seats[11].status = "HOLDING";
  render(
    <SeatMap seats={seats} selected={[]} locked={false} onSelect={() => {}} />,
  );
  expect(
    screen.getByRole("button", { name: "A1 · STANDARD · Đã bán" }),
  ).toBeDisabled();
  expect(screen.getByRole("button", { name: "A1 · STANDARD · Đã bán" })).toHaveTextContent("×");
  expect(
    screen.getByRole("button", { name: "B5 · SWEETBOX · Trống" }),
  ).toBeDisabled();
});
