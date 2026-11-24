import type { Concession, CartLine } from "../types";
import { money } from "../utils/format";
export function ConcessionPicker({
  items,
  cart,
  onChange,
  disabled = false,
}: {
  items: Concession[];
  cart: CartLine[];
  onChange: (cart: CartLine[]) => void;
  disabled?: boolean;
}) {
  return (
    <section>
      <h2>Bắp nước & combo</h2>
      <p className="muted">Hàng được kiểm tra lại khi thanh toán.</p>
      {items
        .filter((i) => i.active)
        .map((item) => (
          <label className="concession" key={item.id}>
            <span>
              {item.name} · {money(item.price)}{" "}
              {item.availableQuantity === 0
                ? " · Hết hàng"
                : item.availableQuantity === undefined
                  ? " · Chưa xác minh tồn kho"
                : item.lowStock
                  ? " · Sắp hết"
                  : ""}
            </span>
            <input
              type="number"
              aria-label={`Số lượng ${item.name}`}
              min={0}
              max={item.availableQuantity}
              value={cart.find((c) => c.itemId === item.id)?.quantity ?? 0}
              disabled={disabled || item.availableQuantity === undefined || item.availableQuantity === 0}
              onChange={(e) => {
                const quantity = Math.max(
                  0,
                  Math.min(item.availableQuantity ?? 0, Number(e.target.value)),
                );
                onChange([
                  ...cart.filter((c) => c.itemId !== item.id),
                  ...(quantity ? [{ itemId: item.id, quantity }] : []),
                ]);
              }}
            />
          </label>
        ))}
    </section>
  );
}
