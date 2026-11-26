import type { Concession, CartLine } from "../types";
import { money } from "../utils/format";

const apiOrigin = new URL(import.meta.env.VITE_API_URL ?? "http://localhost:8081/api/v1").origin;
function imageUrl(value?: string) {
  if (!value) return undefined;
  try { return new URL(value, apiOrigin).href; } catch { return value; }
}

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
  const sortedItems = items
    .filter((item) => item.active)
    .sort((left, right) => Number(right.combo) - Number(left.combo) || left.name.localeCompare(right.name, "vi"));

  return (
    <section className="concession-picker">
      <h2>Bắp nước & combo</h2>
      <p className="muted">Chọn combo trước để được giá tiết kiệm hơn.</p>
      <div className="concession-grid">
        {sortedItems.map((item) => {
          const quantity = cart.find((line) => line.itemId === item.id)?.quantity ?? 0;
          const soldOut = item.availableQuantity === 0;
          const src = imageUrl(item.imageUrl);
          return (
            <article className={`concession-card${item.combo ? " is-combo" : ""}`} key={item.id}>
              <div className="concession-image-wrap">
                {src ? <img className="concession-image" src={src} alt={item.name} loading="lazy" onError={(event) => { event.currentTarget.style.display = "none"; }} />
                  : <div className="concession-image-placeholder" aria-hidden="true">CINEMA SNACKS</div>}
                {item.combo && <span className="concession-badge">Combo tiết kiệm</span>}
              </div>
              <div className="concession-card-copy">
                <h3>{item.name}</h3>
                <strong className="concession-price">{money(item.price)}</strong>
                <p className={`concession-stock${soldOut ? " is-empty" : ""}`}>
                  {soldOut ? "Tạm hết hàng" : item.availableQuantity === undefined ? "Đang kiểm tra tồn kho" : item.lowStock ? `Còn ít: ${item.availableQuantity}` : `Còn ${item.availableQuantity}`}
                </p>
                <label className="concession-quantity">
                  <span>Số lượng</span>
                  <input
                    type="number"
                    aria-label={`Số lượng ${item.name}`}
                    min={0}
                    max={item.availableQuantity}
                    value={quantity}
                    disabled={disabled || item.availableQuantity === undefined || soldOut}
                    onChange={(event) => {
                      const next = Math.max(0, Math.min(item.availableQuantity ?? 0, Number(event.target.value)));
                      onChange([...cart.filter((line) => line.itemId !== item.id), ...(next ? [{ itemId: item.id, quantity: next }] : [])]);
                    }}
                  />
                </label>
              </div>
            </article>
          );
        })}
      </div>
    </section>
  );
}
