import { act, renderHook } from "@testing-library/react";
import { it, expect, vi } from "vitest";
import { useServerCountdown } from "./useServerCountdown";
it("uses server elapsed time rather than the client wall clock", async () => {
  vi.useFakeTimers();
  try {
    const { result, unmount } = renderHook(() =>
      useServerCountdown("2030-01-01T00:00:00Z", "2030-01-01T00:05:00Z"),
    );
    expect(result.current).toBe(300);
    await act(() => vi.advanceTimersByTimeAsync(240000));
    expect(result.current).toBe(60);
    await act(() => vi.advanceTimersByTimeAsync(60000));
    expect(result.current).toBe(0);
    unmount();
  } finally {
    vi.useRealTimers();
  }
});
