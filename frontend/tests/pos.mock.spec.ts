import { test, expect, login, chooseSeat } from "./fixtures/mock-api";

test("POS recovers a committed booking after losing its create response", async ({ page, api }) => {
  await login(page, "staff");
  let lost = false;
  await page.route("**/api/v1/bookings", async (route) => {
    const req = route.request();
    if (req.method() !== "POST" || lost) return route.fallback();
    lost = true;
    const response = await api.handle(new Request(req.url(), { method: req.method(), headers: req.headers(), body: req.postData() }));
    expect(response.ok).toBe(true);
    await route.abort();
  });
  await chooseSeat(page, true);
  await expect(page.getByRole("alert").first()).toContainText("Không kết nối");
  await page.getByRole("button", { name: "Đọc lại kết quả tạo đơn", exact: true }).click();
  await expect(page.getByLabel("Tiền khách đưa")).toBeVisible();
  await expect(page.locator("main h1")).toHaveText("Thanh toán");
  await expect(page).toHaveURL(/booking=\d+/);
});
test("POS can read back a lost creation after hold expiry without issuing tickets", async ({ page, api }) => {
  await login(page, "staff");
  api.setScenario("expiry");
  let lost = false;
  await page.route("**/api/v1/bookings", async (route) => {
    const req = route.request();
    if (req.method() !== "POST" || lost) return route.fallback();
    lost = true;
    const response = await api.handle(new Request(req.url(), { method: req.method(), headers: req.headers(), body: req.postData() }));
    expect(response.ok).toBe(true);
    await route.abort();
  });
  await chooseSeat(page, true);
  await expect(page.getByText(/Giữ ghế 0:00/)).toBeVisible({ timeout: 10000 });
  // The map poll dispatches EXPIRE after the countdown reaches zero. Wait for
  // the hold to leave reducer state, rather than retrying with an expired object.
  await expect(page.getByText(/Giữ ghế 0:00/)).toHaveCount(0, { timeout: 10000 });
  const recover = page.getByRole("button", { name: "Đọc lại kết quả tạo đơn", exact: true });
  await expect(recover).toBeEnabled();
  await recover.click();
  await expect(page).toHaveURL(/booking=\d+/);
  await expect(page.getByRole("button", { name: "Thu tiền mặt", exact: true })).toBeDisabled();
  await expect(page.locator("article.ticket")).toHaveCount(0);
});
test("POS sells a paid ticket and prints the stored receipt", async ({
  page,
  api,
}) => {
  await login(page, "staff");
  await chooseSeat(page, true);
  await page.getByLabel("Tiền khách đưa").fill("500000");
  await page.getByRole("button", { name: "Thu tiền mặt", exact: true }).click();
  await expect(page).toHaveURL(/\/receipt$/);
  await expect(
    page.getByRole("button", { name: "In vé / hóa đơn" }),
  ).toBeVisible();
  await expect(page.getByText(/TK-\d+, VALID/)).toBeVisible();
});
test("VietQR requires manual confirmation and preserves the Staff session", async ({
  page,
  api,
}) => {
  await login(page, "staff");
  await chooseSeat(page, true);
  await page.getByRole("button", { name: "Tạo VietQR", exact: true }).click();
  const confirm = page.getByRole("button", {
    name: "Xác nhận đã nhận tiền",
    exact: true,
  });
  await expect(confirm).toBeDisabled();
  await page
    .getByRole("checkbox", { name: "Đã kiểm tra tiền vào tài khoản" })
    .check();
  await confirm.click();
  await expect(page).toHaveURL(/\/receipt$/);
});
test("stock failure preserves pending booking and offers readback without unsupported edits", async ({ page, api }) => {
  await login(page, "staff");
  await chooseSeat(page, true);
  api.setScenario("stock");
  await page.getByLabel("Tiền khách đưa").fill("500000");
  await page.getByRole("button", { name: "Thu tiền mặt", exact: true }).click();
  await expect(page.getByRole("alert").first()).toContainText("Không đủ F&B");
  await expect(page.getByRole("button", { name: "Đọc lại trạng thái đơn" })).toBeEnabled();
  await expect(page.getByRole("button", { name: "Sửa F&B", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Hủy đơn chờ" })).toHaveCount(0);
});
