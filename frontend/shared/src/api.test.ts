import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, authApi, requestPage } from "./api";

afterEach(() => vi.unstubAllGlobals());
describe("Auth HTTP client", () => {
  it("unwraps success and sends bearer only in the header", async () => {
    const fetch = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ data: { username: "customer" } }), {
        status: 200,
      }),
    );
    vi.stubGlobal("fetch", fetch);
    expect(await authApi.me("private-token")).toEqual({ username: "customer" });
    expect(fetch.mock.calls[0][0]).not.toContain("private-token");
    expect(fetch.mock.calls[0][1]).toMatchObject({
      credentials: "omit",
      headers: { Authorization: "Bearer private-token" },
    });
  });
  it("accepts empty 204 logout response", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 204 })),
    );
    expect(await authApi.logout("token")).toBeUndefined();
  });
  it("preserves backend error status and fields", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: "ACCESS_DENIED",
            message: "Denied",
            details: { role: "STAFF" },
          }),
          { status: 403 },
        ),
      ),
    );
    await expect(authApi.me("token")).rejects.toMatchObject({
      status: 403,
      code: "ACCESS_DENIED",
      details: { role: "STAFF" },
    });
  });
  it("distinguishes transport failure from unauthorized", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("offline")));
    await expect(authApi.me("token")).rejects.toBeInstanceOf(ApiError);
    await expect(authApi.me("token")).rejects.toMatchObject({
      status: 0,
      code: "NETWORK_ERROR",
    });
  });
});

describe("pagination, timeout and cancellation", () => {
  it("preserves pagination metadata", async () => {
    const payload = {
      data: [{ id: 1 }],
      meta: { page: 2, size: 20, totalPages: 4, totalElements: 70 },
    };
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(payload)));
    expect(await requestPage("/movies")).toEqual(payload);
  });
  it("distinguishes an external cancellation from a network failure", async () => {
    const controller = new AbortController();
    vi.stubGlobal(
      "fetch",
      vi.fn(
        (_url, options) =>
          new Promise((_resolve, reject) =>
            options.signal.addEventListener("abort", () =>
              reject(new DOMException("cancelled", "AbortError")),
            ),
          ),
      ),
    );
    const pending = requestPage("/movies", { signal: controller.signal });
    controller.abort();
    await expect(pending).rejects.toMatchObject({ name: "AbortError" });
  });
  it("still times out when an external signal is supplied", async () => {
    vi.useFakeTimers();
    try {
      vi.stubGlobal(
        "fetch",
        vi.fn(
          (_url, options) =>
            new Promise((_resolve, reject) =>
              options.signal.addEventListener("abort", () =>
                reject(new DOMException("cancelled", "AbortError")),
              ),
            ),
        ),
      );
      const pending = requestPage("/movies", {
        signal: new AbortController().signal,
      });
      const check = expect(pending).rejects.toMatchObject({
        code: "REQUEST_TIMEOUT",
      });
      await vi.advanceTimersByTimeAsync(15000);
      await check;
    } finally {
      vi.useRealTimers();
    }
  });
});
