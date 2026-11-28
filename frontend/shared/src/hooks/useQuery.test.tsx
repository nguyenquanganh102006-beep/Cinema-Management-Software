import { act, renderHook, waitFor } from "@testing-library/react";
import { it, expect } from "vitest";
import { useQuery } from "./useQuery";
it("aborts obsolete reads and ignores a late result from a previous route", async () => {
  let firstSignal: AbortSignal | undefined;
  let finish: ((value: string) => void) | undefined;
  const { result, rerender } = renderHook(
    ({ id }) =>
      useQuery(id, (signal) =>
        id === "old"
          ? new Promise<string>((resolve) => {
              firstSignal = signal;
              finish = resolve;
            })
          : Promise.resolve("new result"),
      ),
    { initialProps: { id: "old" } },
  );
  rerender({ id: "new" });
  await waitFor(() => expect(result.current.data).toBe("new result"));
  expect(firstSignal?.aborted).toBe(true);
  await act(async () => {
    finish?.("obsolete result");
  });
  expect(result.current.data).toBe("new result");
});
