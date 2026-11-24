import { useEffect, useRef, useState, useCallback } from "react";
import { holdsApi } from "../api/holds";
import { useAuth } from "../auth/AuthProvider";
import { useBookingFlow } from "../booking/BookingFlowProvider";
import { useServerCountdown } from "./useServerCountdown";
import type { SeatMap } from "../types";
export function useSeatHold(showtimeId: number) {
  const { session } = useAuth();
  const { state, dispatch } = useBookingFlow();
  const [map, setMap] = useState<SeatMap>();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const generation = useRef(0),
    submitting = useRef(false),
    selection = useRef(state.selected);
  selection.current = state.selected;
  const [version, setVersion] = useState(0);
  const refetch = useCallback(() => setVersion((v) => v + 1), []);
  useEffect(() => {
    const current = ++generation.current;
    let controller: AbortController | undefined;
    let reading = false;
    setMap(undefined);
    setLoading(true);
    const read = async () => {
      if (reading || document.visibilityState === "hidden") return;
      reading = true;
      controller = new AbortController();
      try {
        const next = await holdsApi.map(
          showtimeId,
          session.accessToken,
          controller.signal,
        );
        if (current !== generation.current) return;
        setMap(next);
        setError("");
        const own = next.ownHolds.find(
          (h) =>
            selection.current.length === 0 ||
            selection.current.every((id) => h.seatIds.includes(id)),
        );
        if (own) dispatch({ type: "HOLD", value: own });
      } catch (e) {
        if (current === generation.current && !controller.signal.aborted)
          setError(e instanceof Error ? e.message : "Không tải được ghế");
      } finally {
        reading = false;
        if (current === generation.current) setLoading(false);
      }
    };
    void read();
    const timer = setInterval(read, 5000);
    window.addEventListener("focus", read);
    document.addEventListener("visibilitychange", read);
    return () => {
      generation.current++;
      controller?.abort();
      clearInterval(timer);
      window.removeEventListener("focus", read);
      document.removeEventListener("visibilitychange", read);
    };
  }, [showtimeId, session.accessToken, dispatch, version]);
  const active = state.hold?.showtimeId === showtimeId ? state.hold : null;
  const seconds = useServerCountdown(
    map?.serverTime ?? active?.serverTime,
    active?.holdUntil,
  );
  useEffect(() => {
    if (
      active &&
      new Date(active.holdUntil).getTime() <=
        new Date(map?.serverTime ?? active.serverTime).getTime()
    ) {
      dispatch({ type: "EXPIRE" });
      setError("Phiên giữ ghế đã hết hạn. Vui lòng chọn lại.");
    }
  }, [active, map?.serverTime, dispatch]);
  async function mutate(action: "hold" | "extend" | "release") {
    if (submitting.current) return;
    submitting.current = true;
    setBusy(true);
    setError("");
    const current = generation.current;
    try {
      if (action === "release" && active) {
        await holdsApi.release(active.holdToken, session.accessToken);
        if (current === generation.current) dispatch({ type: "RELEASE" });
      } else {
        const result =
          action === "hold"
            ? await holdsApi.hold(
                showtimeId,
                state.selected,
                session.accessToken,
              )
            : await holdsApi.extend(active!.holdToken, session.accessToken);
        if (current === generation.current)
          dispatch({ type: "HOLD", value: result });
      }
    } catch (e) {
      if (current === generation.current)
        setError(
          (e instanceof Error ? e.message : "Thao tác thất bại") +
            " Làm mới sơ đồ để kiểm tra ghế đã giữ.",
        );
    } finally {
      submitting.current = false;
      setBusy(false);
      refetch();
    }
  }
  return {
    map,
    error,
    busy,
    loading,
    seconds,
    hold: active,
    refetch,
    create: () => mutate("hold"),
    extend: () => mutate("extend"),
    release: () => mutate("release"),
  };
}
