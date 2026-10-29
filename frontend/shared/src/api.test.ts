import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, authApi } from "./api";

afterEach(() => vi.unstubAllGlobals());
describe("Auth HTTP client", () => {
  it("unwraps success and sends bearer only in the header", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(
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
      vi
        .fn()
        .mockResolvedValue(
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
