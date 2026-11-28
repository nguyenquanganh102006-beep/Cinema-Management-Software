export function HoldCountdown({
  seconds,
  pos,
  extensionUsed,
  waiting,
  onExtend,
  busy,
}: {
  seconds: number;
  pos: boolean;
  extensionUsed: boolean;
  waiting: boolean;
  onExtend: () => void;
  busy?: boolean;
}) {
  return (
    <div
      className={seconds === 0 ? "alert error" : seconds <= 60 ? "alert warning" : "alert success"}
      role="status"
    >
      <strong>
        Giữ ghế {Math.floor(seconds / 60)}:
        {String(seconds % 60).padStart(2, "0")}
      </strong>
      {seconds === 0
        ? ". Hết hạn, vui lòng chọn lại."
        : seconds <= 60
          ? ". Sắp hết hạn."
          : ""}
      {pos && waiting && !extensionUsed && seconds > 0 && seconds <= 60 && (
        <button disabled={busy} onClick={onExtend}>
          Gia hạn +2 phút
        </button>
      )}
    </div>
  );
}
