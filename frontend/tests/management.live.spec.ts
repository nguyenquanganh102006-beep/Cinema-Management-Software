import { test, expect, type Page } from "@playwright/test";

const base = "http://localhost:8081/api/v1";
const password = process.env.DEMO_PASSWORD;
async function login(page: Page, username: string, value = password!) {
  await page.getByLabel("Tên đăng nhập hoặc email").fill(username);
  await page.getByLabel("Mật khẩu", { exact: true }).fill(value);
  await page.getByRole("button", { name: "Đăng nhập", exact: false }).click();
}

test("live Staff create/edit/status/reset retains errors and revokes old sessions", async ({ page, context, request }) => {
  test.setTimeout(60000);
  if (!password) throw new Error("Set DEMO_PASSWORD for the isolated backend");
  const suffix = Date.now().toString().slice(-9), username = "live_staff_" + suffix;
  await page.goto("http://localhost:3001/");
  await login(page, "manager01");
  await page.getByRole("link", { name: "Nhân viên", exact: true }).click();
  await page.getByRole("button", { name: "Thêm mới", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Tên đăng nhập", { exact: true }).fill(username);
  await dialog.getByLabel("Họ và tên", { exact: true }).fill("Nhân viên live " + suffix);
  await dialog.getByLabel("Email", { exact: true }).fill(username + "@cinema.test");
  await dialog.getByLabel("Số điện thoại").fill("09" + suffix);
  await dialog.getByLabel("Mật khẩu (khi tạo mới)").fill(password);
  await dialog.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await page.getByLabel("Tìm nhân viên").fill(username);
  const row = page.getByRole("row").filter({ has: page.getByRole("cell", { name: username, exact: true }) });
  await expect(row).toContainText("ACTIVE");
  await row.getByRole("button", { name: "Sửa", exact: true }).click();
  await expect(dialog.getByLabel("Tên đăng nhập", { exact: true })).toBeDisabled();
  await dialog.getByLabel("Họ và tên", { exact: true }).fill("Tên đã cập nhật " + suffix);
  await dialog.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(row).toContainText("Tên đã cập nhật");
  const token = async (value: string) => {
    const response = await request.post(base + "/auth/login", { data: { username, password: value } });
    expect(response.ok()).toBe(true); return (await response.json()).data.accessToken as string;
  };
  const oldToken = await token(password);
  await row.getByRole("button", { name: "Ngừng hoạt động", exact: true }).click();
  await dialog.getByRole("button", { name: "Xác nhận", exact: true }).click();
  await expect(row).toContainText("INACTIVE");
  expect((await request.get(base + "/auth/me", { headers: { Authorization: "Bearer " + oldToken } })).status()).toBe(401);
  const staffPage = await context.newPage();
  await staffPage.goto("http://localhost:3002/");
  await login(staffPage, username);
  await expect(staffPage.getByRole("alert")).toContainText("ngừng hoạt động");
  await row.getByRole("button", { name: "Kích hoạt", exact: true }).click();
  await dialog.getByRole("button", { name: "Xác nhận", exact: true }).click();
  await expect(row).toContainText("ACTIVE");
  const beforeReset = await token(password), newPassword = "Reset-" + suffix + "!";
  await row.getByRole("button", { name: "Reset mật khẩu", exact: true }).click();
  await dialog.getByLabel("Mật khẩu mới").fill(newPassword);
  await dialog.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  expect((await request.get(base + "/auth/me", { headers: { Authorization: "Bearer " + beforeReset } })).status()).toBe(401);
  await login(staffPage, username, newPassword);
  await expect(staffPage.getByRole("link", { name: "Tài khoản", exact: true })).toBeVisible();
  await staffPage.close();
});

test("live Manager paginates Staff beyond page one and resets page on filter", async ({ page, request }) => {
  test.setTimeout(60000);
  if (!password) throw new Error("Set DEMO_PASSWORD for the isolated backend");
  const response = await request.post(base + "/auth/login", { data: { username: "manager01", password } });
  const token = (await response.json()).data.accessToken;
  const prefix = "Page" + Date.now().toString().slice(-7);
  for (let i = 0; i < 21; i++) {
    const suffix = `${prefix.toLowerCase()}_${String(i).padStart(2, "0")}`;
    const created = await request.post(base + "/staff", { headers: { Authorization: "Bearer " + token }, data: {
      username: suffix, fullName: prefix + " " + String(i).padStart(2, "0"), email: suffix + "@cinema.test", phone: "08" + Date.now().toString().slice(-8) + String(i).padStart(2, "0"), password,
    } });
    expect(created.status()).toBe(201);
  }
  await page.goto("http://localhost:3001/");
  await login(page, "manager01");
  await page.getByRole("link", { name: "Nhân viên", exact: true }).click();
  await page.getByLabel("Tìm nhân viên").fill(prefix);
  await expect(page.getByRole("navigation", { name: "Phân trang" })).toContainText("Trang 1 / 2");
  await page.getByRole("button", { name: "Sau", exact: true }).click();
  await expect(page.getByRole("cell", { name: prefix + " 20", exact: true })).toBeVisible();
  await page.getByLabel("Tìm nhân viên").fill(prefix + " 00");
  await expect(page.getByRole("navigation", { name: "Phân trang" })).toContainText("Trang 1 / 1");
  await expect(page.getByRole("cell", { name: prefix + " 00", exact: true })).toBeVisible();
});

test("live Manager pairs physical Sweetbox seats and changes a quote without changing an issued snapshot", async ({ page, request }) => {
  test.setTimeout(60000);
  if (!password) throw new Error("Set DEMO_PASSWORD for the isolated backend");
  const token = async (username: string) => {
    const r = await request.post(base + "/auth/login", { data: { username, password } });
    expect(r.ok()).toBe(true); return (await r.json()).data.accessToken as string;
  };
  const manager = await token("manager01"), staff = await token("staff01");
  async function call(path: string, method = "GET", data?: unknown, auth = manager) {
    const r = await request.fetch(base + path, { method, data, headers: { Authorization: "Bearer " + auth } });
    expect(r.ok(), `${path}: ${await r.text()}`).toBe(true); return (await r.json()).data;
  }
  const suffix = Date.now().toString().slice(-8);
  const movie = await call("/movies", "POST", { title: "Layout pricing " + suffix, durationMinutes: 90, releaseDate: "2026-11-19", ageRating: "P" });
  const room = await call("/auditoriums", "POST", { name: "Layout " + suffix, type: "STANDARD_2D", totalSeats: 4 });
  const seats = await call(`/auditoriums/${room.id}/seats`, "POST", { seats: [{ row: "A", number: 1, type: "STANDARD" }, { row: "B", number: 1, type: "SWEETBOX" }, { row: "B", number: 2, type: "SWEETBOX" }] });
  const [standard, left, right] = [seats.find((s: { row: string }) => s.row === "A"), seats.find((s: { row: string; number: number }) => s.row === "B" && s.number === 1), seats.find((s: { row: string; number: number }) => s.row === "B" && s.number === 2)];
  await page.goto(`http://localhost:3001/auditoriums/${room.id}/seats`);
  await login(page, "manager01");
  await page.getByRole("button", { name: "Thêm ghế", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Hàng", { exact: true }).fill("C");
  await dialog.getByLabel("Số ghế", { exact: true }).fill("3");
  await dialog.getByLabel("Loại ghế", { exact: true }).selectOption("VIP");
  await dialog.getByRole("button", { name: "Lưu", exact: true }).click();
  await page.getByRole("button", { name: "Sửa ghế C3, VIP", exact: true }).click();
  await dialog.getByLabel("Số ghế", { exact: true }).fill("2");
  await dialog.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(page.getByRole("button", { name: "Sửa ghế C2, VIP", exact: true })).toBeVisible();
  await page.getByLabel("Ghế trái").selectOption(String(left.id));
  await page.getByLabel("Ghế phải").selectOption(String(left.id));
  await expect(page.getByRole("button", { name: "Ghép Sweetbox", exact: true })).toBeDisabled();
  await page.getByLabel("Ghế phải").selectOption(String(right.id));
  await page.getByRole("button", { name: "Ghép Sweetbox", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Xác nhận ghép cặp" }).click();
  await expect(page.getByRole("button", { name: "Tách cặp", exact: true })).toBeEnabled();
  expect((await call(`/auditoriums/${room.id}/seats`)).find((s: { id: number }) => s.id === left.id).pairSeatId).toBe(right.id);
  await page.getByRole("button", { name: "Tách cặp", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Xác nhận tách cặp" }).click();
  await expect(page.getByRole("button", { name: "Ghép Sweetbox", exact: true })).toBeEnabled();
  await page.getByRole("button", { name: "Ghép Sweetbox", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Xác nhận ghép cặp" }).click();
  await expect(page.getByRole("button", { name: "Tách cặp", exact: true })).toBeEnabled();
  const show = await call("/showtimes", "POST", { movieId: movie.id, auditoriumId: room.id, startTime: new Date(Date.now() + 7200000).toISOString() });
  const hold = await call(`/showtimes/${show.id}/holds`, "POST", { seatIds: [standard.id] }, staff);
  const booking = await call("/bookings", "POST", { showtimeId: show.id, holdToken: hold.holdToken }, staff);
  await call(`/bookings/${booking.bookingId}/payments/cash`, "POST", { cashReceived: 500000, ageDocumentChecked: true }, staff);
  const issued = await call(`/pos/bookings/${booking.bookingId}`, "GET", undefined, staff);
  const rules = await call("/pricing-rules");
  const rule = rules.find((r: { type: string; ruleType?: string }) => (r.type ?? r.ruleType) === "BASE_PRICE");
  expect(rule).toBeTruthy();
  const before = await call("/pricing/quote", "POST", { showtimeId: show.id, seatIds: [standard.id] });
  await page.getByRole("link", { name: "Bảng giá", exact: true }).click();
  await page.getByRole("button", { name: "Sửa giá " + rule.ruleCode, exact: true }).click();
  await page.getByLabel("Giá mới", { exact: true }).fill(String(Number(rule.amount) + 1234));
  await page.getByRole("checkbox", { name: "Tôi xác nhận áp dụng giá mới cho các giao dịch tiếp theo" }).check();
  try {
    await page.getByRole("button", { name: "Lưu quy tắc", exact: true }).click();
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await page.getByLabel("Suất chiếu kiểm giá").selectOption(String(show.id));
    await page.getByRole("checkbox", { name: "A1, STANDARD", exact: true }).check();
    await page.getByRole("button", { name: "Lấy giá từ máy chủ", exact: true }).click();
    const after = await call("/pricing/quote", "POST", { showtimeId: show.id, seatIds: [standard.id] });
    expect(Number(after.ticketTotal) - Number(before.ticketTotal)).toBe(1234);
    await expect(page.getByText("Tổng " + new Intl.NumberFormat("vi-VN", { style: "currency", currency: "VND" }).format(Number(after.ticketTotal)), { exact: true })).toBeVisible();
    const reread = await call(`/pos/bookings/${booking.bookingId}`, "GET", undefined, staff);
    expect(reread.tickets[0].priceSnapshot).toBe(issued.tickets[0].priceSnapshot);
  } finally { await call(`/pricing-rules/${rule.id}`, "PATCH", { amount: rule.amount, description: rule.description }); }
});
