import { test, expect, login, chooseSeat } from "./fixtures/mock-api";
test("VNPay opens another tab; only a server confirmation issues tickets", async ({
  page,
  api,
  context,
}) => {
  await login(page, "customer");
  await chooseSeat(page, false);
  await page.getByRole("button", { name: "Tạo liên kết VNPay" }).click();
  const popup = await Promise.all([
    context.waitForEvent("page"),
    page.getByRole("link", { name: "Mở VNPay" }).click(),
  ]).then(([p]) => p);
  await expect(
    popup.getByRole("heading", { name: "Kết quả thanh toán" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "In vé / hóa đơn" }),
  ).toHaveCount(0);
  const id = Number(new URL(popup.url()).searchParams.get("mockBooking"));
  api.completeOnline(id);
  await expect(
    page.getByRole("button", { name: "In vé / hóa đơn" }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Tài khoản", exact: true }),
  ).toBeVisible();
});
for (const width of [360, 768, 1280])
  test(
    "Customer layout at " + width + "px has a usable seat map",
    async ({ page, api }) => {
      await page.setViewportSize({ width, height: 900 });
      await login(page, "customer");
      await page.getByRole("link", { name: "Chuyến tàu ánh sáng" }).click();
      await page.getByRole("button", { name: "Chọn suất" }).click();
      await expect(
        page.getByRole("button", { name: "B5 · SWEETBOX · Trống" }),
      ).toBeVisible();
      await page.getByRole("button", { name: "B5 · SWEETBOX · Trống" }).click();
      await expect(
        page.getByRole("button", { name: "B6 · SWEETBOX · Đã chọn" }),
      ).toHaveAttribute("aria-pressed", "true");
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth + 1,
        ),
      ).toBeTruthy();
    },
  );
test("payment exception shows manual assistance without tickets", async ({
  page,
  api,
}) => {
  await login(page, "customer");
  await chooseSeat(page, false);
  api.setScenario("exception");
  await page.getByRole("button", { name: "Tạo liên kết VNPay" }).click();
  const href = await page
    .getByRole("link", { name: "Mở VNPay" })
    .getAttribute("href");
  api.completeOnline(Number(new URL(href!).searchParams.get("mockBooking")));
  await expect(page.getByRole("alert").first()).toContainText("xử lý thủ công");
  await expect(
    page.getByRole("button", { name: "In vé / hóa đơn" }),
  ).toHaveCount(0);
});
