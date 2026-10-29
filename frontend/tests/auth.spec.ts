import { test, expect, type Page } from "@playwright/test";
const password = process.env.DEMO_PASSWORD;
test.beforeAll(() => {
  if (!password)
    throw new Error(
      "Set DEMO_PASSWORD and run a local-profile backend before E2E.",
    );
});
async function login(page: Page, username: string) {
  await page.getByLabel("Tên đăng nhập hoặc email").fill(username);
  await page.getByLabel("Mật khẩu", { exact: true }).fill(password!);
  await page.getByRole("button", { name: "Đăng nhập", exact: false }).click();
}
for (const [role, port, username] of [
  ["Quản lý rạp", 3001, "manager01"],
  ["Nhân viên", 3002, "staff01"],
  ["Khách hàng", 3003, "customer01"],
] as const) {
  test(`${role}: login, profile and logout`, async ({ page }) => {
    await page.goto(`http://localhost:${port}`);
    await login(page, username);
    await expect(
      page.getByRole("heading", { name: `Xin chào, ${username}.` }),
    ).toBeVisible();
    await page
      .getByRole("button", { name: "Kiểm tra phiên đăng nhập" })
      .click();
    await expect(page.getByRole("status")).toContainText("đã được cập nhật");
    await page.getByRole("button", { name: "Đăng xuất" }).click();
    await expect(
      page.getByRole("heading", { name: "Chào mừng trở lại" }),
    ).toBeVisible();
    await expect(page.getByRole("status")).toContainText("thu hồi phiên");
  });
}
test("Customer registers then logs in; reload requires authentication", async ({
  page,
}) => {
  const suffix = Date.now().toString();
  await page.goto("/");
  await page.getByRole("button", { name: "Đăng ký", exact: true }).click();
  await page.getByLabel("Tên đăng nhập", { exact: true }).fill("e2e_" + suffix);
  await page.getByLabel("Họ và tên").fill("Khách kiểm thử");
  await page
    .getByLabel("Email", { exact: true })
    .fill(`e2e_${suffix}@example.test`);
  await page.getByLabel("Số điện thoại").fill(suffix);
  await page.getByLabel("Mật khẩu", { exact: true }).fill(password!);
  await page.getByRole("button", { name: "Tạo tài khoản" }).click();
  await expect(page.getByRole("status")).toContainText(
    "Tạo tài khoản thành công",
  );
  await login(page, "e2e_" + suffix);
  await expect(
    page.getByRole("heading", { name: "Xin chào, Khách kiểm thử." }),
  ).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Chào mừng trở lại" }),
  ).toBeVisible();
});
for (const [source, target, username] of [
  [3003, 3001, "manager01"],
  [3003, 3002, "staff01"],
  [3001, 3003, "customer01"],
] as const) {
  test(`login on ${source} automatically dispatches ${username} to ${target}`, async ({
    page,
  }) => {
    const navigationUrls: string[] = [];
    page.on("framenavigated", (frame) => {
      if (frame === page.mainFrame()) navigationUrls.push(frame.url());
    });
    await page.goto(`http://localhost:${source}`);
    await login(page, username);
    await expect(
      page.getByRole("heading", { name: `Xin chào, ${username}.` }),
    ).toBeVisible();
    await expect(page).toHaveURL(`http://localhost:${target}/`);
    expect(
      navigationUrls.every(
        (url) =>
          !/[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/.test(
            new URL(url).hash,
          ),
      ),
    ).toBeTruthy();
    await page
      .getByRole("button", { name: "Kiểm tra phiên đăng nhập" })
      .click();
    await expect(page.getByRole("status")).toContainText("đã được cập nhật");
    await page.getByRole("button", { name: "Đăng xuất" }).click();
    await expect(
      page.getByRole("heading", { name: "Chào mừng trở lại" }),
    ).toBeVisible();
  });
}
test("invalid transfer clears the code from URL and offers login", async ({
  page,
}) => {
  await page.goto("http://localhost:3001/#auth-transfer=" + "a".repeat(64));
  await expect(page.getByRole("alert")).toContainText(
    "Mã chuyển phiên không hợp lệ",
  );
  await expect(page).toHaveURL("http://localhost:3001/");
  await expect(
    page.getByRole("heading", { name: "Chào mừng trở lại" }),
  ).toBeVisible();
});
test("wrong credentials show server error", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("Tên đăng nhập hoặc email").fill("customer01");
  await page.getByLabel("Mật khẩu", { exact: true }).fill("wrong-password");
  await page.getByRole("button", { name: "Đăng nhập", exact: false }).click();
  await expect(page.getByRole("alert")).toContainText("không đúng");
});
test("logout transport failure keeps session for retry", async ({ page }) => {
  await page.goto("/");
  await login(page, "customer01");
  await expect(
    page.getByRole("heading", { name: "Xin chào, customer01." }),
  ).toBeVisible();
  await page.route("**/auth/logout", (route) => route.abort());
  await page.getByRole("button", { name: "Đăng xuất" }).click();
  await expect(page.getByRole("alert")).toContainText(
    "Chưa xác nhận thu hồi phiên",
  );
  await expect(
    page.getByRole("heading", { name: "Xin chào, customer01." }),
  ).toBeVisible();
  await page.unroute("**/auth/logout");
  await page.getByRole("button", { name: "Đăng xuất" }).click();
  await expect(
    page.getByRole("heading", { name: "Chào mừng trở lại" }),
  ).toBeVisible();
});
test("401 clears auth; 403 and server errors keep the session", async ({
  page,
}) => {
  await page.goto("/");
  await login(page, "customer01");
  await expect(
    page.getByRole("heading", { name: "Xin chào, customer01." }),
  ).toBeVisible();
  await page.route("**/auth/me", (route) =>
    route.fulfill({
      status: 403,
      contentType: "application/json",
      body: JSON.stringify({ message: "Denied", code: "ACCESS_DENIED" }),
    }),
  );
  await page.getByRole("button", { name: "Kiểm tra phiên đăng nhập" }).click();
  await expect(page.getByRole("alert")).toContainText("không có quyền");
  await page.unroute("**/auth/me");
  await page.route("**/auth/me", (route) =>
    route.fulfill({
      status: 500,
      contentType: "application/json",
      body: JSON.stringify({
        message: "Server failure",
        code: "INTERNAL_ERROR",
      }),
    }),
  );
  await page.getByRole("button", { name: "Kiểm tra phiên đăng nhập" }).click();
  await expect(page.getByRole("alert")).toContainText("Server failure");
  await expect(
    page.getByRole("heading", { name: "Xin chào, customer01." }),
  ).toBeVisible();
  await page.unroute("**/auth/me");
  await page.route("**/auth/me", (route) =>
    route.fulfill({
      status: 401,
      contentType: "application/json",
      body: JSON.stringify({ message: "Expired", code: "AUTH_TOKEN_EXPIRED" }),
    }),
  );
  await page.getByRole("button", { name: "Kiểm tra phiên đăng nhập" }).click();
  await expect(
    page.getByRole("heading", { name: "Chào mừng trở lại" }),
  ).toBeVisible();
});
test("mobile login fits the viewport", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await expect(
    page.getByRole("button", { name: "Đăng nhập", exact: false }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBeTruthy();
  await page.screenshot({
    path: "test-results/customer-mobile.png",
    fullPage: true,
  });
});
test("refreshing profile does not extend the token expiry countdown", async ({
  page,
}) => {
  await page.clock.install();
  await page.route("**/auth/login", async (route) => {
    const response = await route.fetch();
    const body = await response.json();
    body.data.expiresIn = 2;
    await route.fulfill({ response, json: body });
  });
  await page.goto("/");
  await login(page, "customer01");
  await expect(
    page.getByRole("heading", { name: "Xin chào, customer01." }),
  ).toBeVisible();
  await page.clock.fastForward(1000);
  await page.getByRole("button", { name: "Kiểm tra phiên đăng nhập" }).click();
  await expect(page.getByRole("status")).toContainText("đã được cập nhật");
  await page.clock.fastForward(1001);
  await expect(
    page.getByRole("heading", { name: "Chào mừng trở lại" }),
  ).toBeVisible();
});
test("desktop portal visual smoke", async ({ page }) => {
  await page.goto("http://localhost:3001");
  await page.screenshot({
    path: "test-results/manager-login.png",
    fullPage: true,
  });
  await login(page, "manager01");
  await expect(
    page.getByRole("heading", { name: "Xin chào, manager01." }),
  ).toBeVisible();
  await page.screenshot({
    path: "test-results/manager-home.png",
    fullPage: true,
  });
});
