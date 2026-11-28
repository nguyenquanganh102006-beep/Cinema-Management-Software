import { test as base, expect, type Page } from "@playwright/test";
import { createMockApi } from "../../shared/src/mocks/core";
export const test = base.extend<{ api: ReturnType<typeof createMockApi> }>({
  api: async ({ context }, use) => {
    const api = createMockApi();
    await context.route("**/api/v1/**", async (route) => {
      const req = route.request();
      const cors = {
        "Access-Control-Allow-Origin": req.headers().origin ?? "*",
        "Access-Control-Allow-Headers": "Authorization,Content-Type",
        "Access-Control-Allow-Methods": "GET,POST,PATCH,DELETE,OPTIONS",
      };
      if (req.method() === "OPTIONS") {
        await route.fulfill({ status: 204, headers: cors });
        return;
      }
      const request = new Request(req.url(), {
        method: req.method(),
        headers: req.headers(),
        body: ["GET", "HEAD"].includes(req.method())
          ? undefined
          : req.postData(),
      });
      const response = await api.handle(request);
      await route.fulfill({
        status: response.status,
        headers: { ...Object.fromEntries(response.headers), ...cors },
        body: await response.text(),
      });
    });
    await use(api);
  },
});
export { expect };
export async function login(
  page: Page,
  role: "customer" | "staff" | "manager",
) {
  await page.goto(
    "http://localhost:" +
      (role === "manager" ? 3001 : role === "staff" ? 3002 : 3003),
  );
  await page.getByLabel("Tên đăng nhập hoặc email").fill(role + "01");
  await page.getByLabel("Mật khẩu", { exact: true }).fill("Reference123!");
  await page.getByRole("button", { name: "Đăng nhập", exact: false }).click();
  await expect(
    page.getByRole("link", { name: "Tài khoản", exact: true }),
  ).toBeVisible();
  await expect(page).toHaveURL(
    new RegExp(
      "/" +
        (role === "manager"
          ? "dashboard"
          : role === "staff"
            ? "pos"
            : "movies") +
        "$",
    ),
  );
}
export async function chooseSeat(page: Page, pos: boolean) {
  if (pos)
    await page
      .getByRole("button", { name: "Chọn suất", exact: true })
      .first()
      .click();
  else {
    await page.getByRole("link", { name: "Chuyến tàu ánh sáng" }).click();
    await page.getByRole("button", { name: "Chọn suất" }).click();
  }
  await page
    .getByRole("button", { name: "A1, STANDARD, Trống", exact: true })
    .click();
  await page.getByRole("button", { name: "Giữ các ghế đã chọn" }).click();
  await page.getByRole("link", { name: "Tiếp tục thanh toán" }).click();
  await page
    .getByRole("checkbox", {
      name: pos ? "Đã kiểm tra giấy tờ" : "Tôi cam kết đủ tuổi",
    })
    .check();
  await page
    .getByRole("button", { name: "Tạo đơn, lấy tổng thanh toán" })
    .click();
}
