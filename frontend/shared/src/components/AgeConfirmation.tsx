import type { AgeRating } from "../types";
export function AgeConfirmation({
  rating,
  pos,
  value,
  onChange,
}: {
  rating: AgeRating;
  pos: boolean;
  value: boolean;
  onChange: (value: boolean) => void;
}) {
  return rating === "P" ? null : (
    <label className="checkbox">
      <input
        type="checkbox"
        checked={value}
        onChange={(e) => onChange(e.target.checked)}
      />
      {pos ? "Đã kiểm tra giấy tờ, khách đủ tuổi" : "Tôi cam kết đủ tuổi"} (
      {rating})
    </label>
  );
}
