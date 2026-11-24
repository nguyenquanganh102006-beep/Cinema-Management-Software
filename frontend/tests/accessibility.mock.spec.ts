import { mkdir } from "node:fs/promises";
import { test, expect, login } from "./fixtures/mock-api";

const screenshots = "../docs/testing/screenshots/responsive";
test("Form controls have readable text and a contrasting boundary", async ({ page, api }) => {
  await page.goto("http://localhost:3003/");
  const input = page.getByLabel("Tên đăng nhập hoặc email");
  const style = await input.evaluate((element) => {
    const value = getComputedStyle(element);
    return { border: value.borderTopColor, background: value.backgroundColor, text: value.color };
  });
  const luminance = (rgb: string) => {
    const channels = rgb.match(/[\d.]+/g)!.slice(0, 3).map(Number).map((c) => c / 255).map((c) => c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
    return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
  };
  const contrast = (a: string, b: string) => (Math.max(luminance(a), luminance(b)) + 0.05) / (Math.min(luminance(a), luminance(b)) + 0.05);
  expect(contrast(style.border, style.background)).toBeGreaterThanOrEqual(3);
  expect(contrast(style.text, style.background)).toBeGreaterThanOrEqual(4.5);
});
for (const width of [320, 390, 768, 1024, 1440]) {
  for (const portal of ["customer", "staff", "manager"] as const) {
    test(`${portal} viewport ${width}: contained layout and visible controls`, async ({ page, api }) => {
      await page.setViewportSize({ width, height: 900 });
      await page.emulateMedia({ reducedMotion: "reduce" });
      const errors: string[] = [];
      page.on("pageerror", (error) => errors.push(error.message));
      await login(page, portal);
      if (portal === "customer") {
        await page.getByRole("link", { name: "Chuyến tàu ánh sáng" }).click();
        await page.getByRole("button", { name: "Chọn suất" }).click();
        await expect(page.locator("main h1")).toHaveText("Chọn ghế và F&B");
        await expect(page.getByRole("button", { name: "Giữ các ghế đã chọn" })).toBeVisible();
        if (width <= 768) await expect(page.getByRole("button", { name: "Giữ các ghế đã chọn" })).toBeInViewport();
      } else if (portal === "staff") {
        await page.getByRole("button", { name: "Chọn suất", exact: true }).first().click();
        await expect(page.getByRole("button", { name: "Giữ các ghế đã chọn" })).toBeVisible();
      } else {
        await page.getByRole("link", { name: "Báo cáo", exact: true }).click();
        await expect(page.getByRole("region", { name: "Doanh thu", exact: true }).getByText("Doanh thu ròng", { exact: true })).toBeVisible();
      }
      await expect(page.locator(".loading-state")).toHaveCount(0);
      await page.evaluate(() => document.fonts.ready);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
      expect(errors).toEqual([]);
      await mkdir(screenshots, { recursive: true });
      await page.screenshot({ path: `${screenshots}/${portal}-${width}-${test.info().project.name}.png` });
    });
  }
}

test("Keyboard skip link, modal containment, Escape and return focus", async ({ page, api }) => {
  await login(page, "manager");
  // A fresh route preserves auth while resetting the focus to the page body.
  await page.getByRole("link", { name: "Phim", exact: true }).click();
  await page.getByRole("link", { name: "Bỏ qua điều hướng" }).focus();
  await page.keyboard.press("Enter");
  await expect(page.locator("main")).toBeFocused();
  const opener = page.getByRole("button", { name: "Thêm mới", exact: true });
  await opener.click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  for (let i = 0; i < 18; i++) {
    await page.keyboard.press("Tab");
    expect(await dialog.evaluate((element) => element.contains(document.activeElement) || document.activeElement === document.body)).toBe(true);
  }
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await expect(opener).toBeFocused();
});

test("Customer mobile summary leaves concession inputs unobscured", async ({ page, api }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await login(page, "customer");
  await page.getByRole("link", { name: "Chuyến tàu ánh sáng" }).click();
  await page.getByRole("button", { name: "Chọn suất" }).click();
  const input = page.getByLabel("Số lượng Bắp rang");
  // Click must be able to scroll the field above the fixed action bar, then
  // typing must work without any overlay intercepting the interaction.
  await input.click();
  await input.fill("1");
  await expect(input).toHaveValue("1");
  const unobscured = await input.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2) === element;
  });
  expect(unobscured).toBe(true);
});

test("Receipt print hides navigation and controls while retaining ticket QR", async ({ page, api }) => {
  await login(page, "staff");
  await page.getByRole("link", { name: "Giao dịch POS", exact: true }).click();
  await page.getByRole("link", { name: "BK-POS-DEMO", exact: true }).click();
  await expect(page.getByRole("img", { name: "Mã QR vé" })).toBeVisible();
  await page.emulateMedia({ media: "print" });
  await expect(page.locator(".sidebar")).toBeHidden();
  await expect(page.getByRole("button", { name: "In vé / hóa đơn" })).toBeHidden();
  await expect(page.getByRole("link", { name: "Yêu cầu hoàn vé tại quầy" })).toBeHidden();
  await expect(page.locator("article.ticket")).toBeVisible();
  await expect(page.getByRole("img", { name: "Mã QR vé" })).toBeVisible();
  await expect(page.locator("article.ticket")).toHaveCSS("break-inside", "avoid");
});

test("Receipt contains a long issued ticket code at 320px", async ({ page, api }) => {
  await page.setViewportSize({ width: 320, height: 900 });
  await page.route("**/api/v1/pos/bookings/1", async (route) => {
    const req = route.request();
    if (req.method() !== "GET") return route.fallback();
    const response = await api.handle(new Request(req.url(), { headers: req.headers() }));
    const body = await response.json();
    body.data.tickets[0].ticketCode = "TK-" + "aB3xYz7Q".repeat(5);
    await route.fulfill({ status: response.status, contentType: "application/json", headers: { "Access-Control-Allow-Origin": "*" }, body: JSON.stringify(body) });
  });
  await login(page, "staff");
  await page.getByRole("link", { name: "Giao dịch POS", exact: true }).click();
  await page.getByRole("link", { name: "BK-POS-DEMO", exact: true }).click();
  await expect(page.getByRole("img", { name: "Mã QR vé" })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
});

test("Refund selection wraps a long ticket code at 320px", async ({ page, api }) => {
  await page.setViewportSize({ width: 320, height: 900 });
  await page.route("**/api/v1/refunds/eligibility?*", async (route) => {
    const req = route.request();
    if (req.method() !== "GET") return route.fallback();
    const response = await api.handle(new Request(req.url(), { headers: req.headers() }));
    const body = await response.json();
    body.data.tickets[0].ticketCode = "TK-" + "aB3xYz7Q".repeat(5);
    await route.fulfill({ status: response.status, contentType: "application/json", headers: { "Access-Control-Allow-Origin": "*" }, body: JSON.stringify(body) });
  });
  await login(page, "staff");
  await page.getByRole("link", { name: "Yêu cầu hoàn vé", exact: true }).click();
  await page.getByLabel("Booking Code", { exact: true }).fill("BK-POS-DEMO");
  await page.getByRole("button", { name: "Kiểm tra điều kiện hoàn" }).click();
  const ticket = page.getByRole("checkbox").first();
  await ticket.check();
  await expect(ticket).toBeChecked();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
});
