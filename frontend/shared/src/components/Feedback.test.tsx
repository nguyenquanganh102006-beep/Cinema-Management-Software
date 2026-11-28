import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { Feedback } from "./Feedback";
import { ConcessionPicker } from "./ConcessionPicker";
it("offers an accessible retry for a failed read", async () => {
  const retry = vi.fn(); render(<Feedback error="Không tải được phim" retry={retry} />);
  expect(screen.getByRole("alert")).toHaveTextContent("Không tải được phim");
  await userEvent.click(screen.getByRole("button", { name: "Thử lại" })); expect(retry).toHaveBeenCalledOnce();
});
it("locks unknown and out-of-stock items while keeping a low-stock combo usable", () => {
  render(<ConcessionPicker cart={[]} onChange={vi.fn()} items={[{ id: 1, name: "Chưa biết", active: true, price: 20000, combo: false }, { id: 2, name: "Hết", active: true, price: 20000, combo: false, availableQuantity: 0 }, { id: 3, name: "Combo", active: true, price: 40000, combo: true, availableQuantity: 2, lowStock: true }]} />);
  expect(screen.getByLabelText("Số lượng Chưa biết")).toBeDisabled();
  expect(screen.getByLabelText("Số lượng Hết")).toBeDisabled();
  expect(screen.getByLabelText("Số lượng Combo")).toBeEnabled();
  expect(screen.getByLabelText("Số lượng Combo")).toHaveAttribute("max", "2");
});
