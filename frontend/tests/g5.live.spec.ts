import { test, expect, type Page } from "@playwright/test";
import { createHmac } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
const base = "http://localhost:8081/api/v1";
const password = process.env.DEMO_PASSWORD;
const imageRoot = "../docs/testing/screenshots/g5";
const captures: string[] = [];
const responsiveCaptures: string[] = [];
async function shot(page: Page, id: string) {
  await mkdir(imageRoot, { recursive: true });
  await expect(page.locator(".loading-state")).toHaveCount(0);
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: `${imageRoot}/${id}.png`, fullPage: true, timeout: 10000 }); captures.push(id);
  const original = page.viewportSize();
  try {
    for (const width of [320, 390, 768, 1024, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      await page.evaluate(() => document.fonts.ready);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `${id} at ${width}px`).toBe(true);
      const name = `${id}-${width}`;
      await page.screenshot({ path: `${imageRoot}/${name}.png`, timeout: 10000 });
      responsiveCaptures.push(name);
    }
  } finally { if (original) await page.setViewportSize(original); }
}
async function login(page: Page, portal: "manager" | "staff" | "customer", screen?: string) {
  await page.goto(`http://localhost:${portal === "manager" ? 3001 : portal === "staff" ? 3002 : 3003}/`);
  if (screen) await shot(page, screen);
  await page.getByLabel("Tên đăng nhập hoặc email").fill(portal + "01");
  await page.getByLabel("Mật khẩu", { exact: true }).fill(password!);
  await page.getByRole("button", { name: "Đăng nhập", exact: false }).click();
  await expect(page.getByRole("link", { name: "Tài khoản", exact: true })).toBeVisible();
}
test("G5 live: partial refund, gate, cash/VietQR, signed online payment, reload and reports reconcile", async ({ browser, request }) => {
  test.setTimeout(180000);
  if (!password || !process.env.TEST_VNPAY_HASH_SECRET) throw new Error("Set DEMO_PASSWORD and TEST_VNPAY_HASH_SECRET for the isolated backend.");
  const timing: { path: string; ms: number; method: string }[] = [];
  async function token(username: string) { const r = await request.post(base + "/auth/login", { data: { username, password } }); expect(r.ok()).toBeTruthy(); return (await r.json()).data.accessToken as string; }
  const [manager, staff, customer] = await Promise.all([token("manager01"), token("staff01"), token("customer01")]);
  const headers = (token: string) => ({ Authorization: "Bearer " + token });
  async function post(path: string, data: unknown, auth = manager) {
    const start = performance.now(); const r = await request.post(base + path, { headers: headers(auth), data }); timing.push({ path, ms: performance.now() - start, method: "POST" });
    expect(r.ok(), `${path}: ${await r.text()}`).toBeTruthy(); return (await r.json()).data;
  }
  async function get(path: string, auth = manager) { const start = performance.now(); const r = await request.get(base + path, { headers: headers(auth) }); timing.push({ path, ms: performance.now() - start, method: "GET" }); expect(r.ok()).toBeTruthy(); return (await r.json()).data; }
  const reportDate = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Ho_Chi_Minh", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
  const reportQuery = `?from=${reportDate}&to=${reportDate}`;
  const beforeRevenue = await get("/reports/revenue" + reportQuery);
  const beforeCount = await get("/reports/ticket-count" + reportQuery);
  const suffix = Date.now().toString().slice(-7);
  const movie = await post("/movies", { title: "G5 Live " + suffix, durationMinutes: 100, releaseDate: "2026-11-19", ageRating: "P" });
  const room = await post("/auditoriums", { name: "G5 Room " + suffix, type: "STANDARD_2D", totalSeats: 8 });
  const seats = await post(`/auditoriums/${room.id}/seats`, { seats: [{ row: "A", number: 1, type: "STANDARD" }, { row: "A", number: 2, type: "STANDARD" }, { row: "B", number: 1, type: "SWEETBOX" }, { row: "B", number: 2, type: "SWEETBOX" }, { row: "C", number: 1, type: "VIP" }] });
  const seatId = (label: string) => seats.find((s: { row: string; number: number }) => s.row + s.number === label).id;
  await post("/seats/sweetbox-pairs", { seatId: seatId("B1"), pairSeatId: seatId("B2") });
  const snack = await post("/concessions", { name: "G5 Snack " + suffix, price: 45000 });
  await post(`/inventory/${snack.id}/restocks`, { quantity: 20, note: "G5 isolated live restock" });
  const show = await post("/showtimes", { movieId: movie.id, auditoriumId: room.id, startTime: new Date(Date.now() + 45 * 60000).toISOString() });
  expect((await get(`/concessions/${snack.id}`, customer)).availableQuantity).toBe(20);
  const staffContext = await browser.newContext(), managerContext = await browser.newContext(), customerContext = await browser.newContext();
  const staffPage = await staffContext.newPage(), managerPage = await managerContext.newPage(), customerPage = await customerContext.newPage();
  const errors: string[] = [];
  const consoleIssues: { type: string; text: string }[] = [];
  const networkIssues: { path: string; status?: number; failure?: string }[] = [];
  for (const page of [staffPage, managerPage, customerPage]) page.on("pageerror", (e) => errors.push(e.message));
  for (const page of [staffPage, managerPage, customerPage]) {
    page.on("console", (message) => { if (["warning", "error"].includes(message.type())) consoleIssues.push({ type: message.type(), text: message.text() }); });
    page.on("response", (response) => { if (response.status() >= 400 && response.url().startsWith(base)) networkIssues.push({ path: new URL(response.url()).pathname.replace(/\/holds\/[^/]+/, "/holds/[redacted]"), status: response.status() }); });
    page.on("requestfailed", (req) => { if (req.url().startsWith(base) && req.failure()?.errorText !== "net::ERR_ABORTED") networkIssues.push({ path: new URL(req.url()).pathname.replace(/\/holds\/[^/]+/, "/holds/[redacted]"), failure: req.failure()?.errorText }); });
  }
  await login(staffPage, "staff", "S01");
  await expect(staffPage.getByRole("combobox", { name: "Phim", exact: true })).toBeVisible(); await staffPage.getByRole("combobox", { name: "Phim", exact: true }).selectOption(String(movie.id)); await shot(staffPage, "S02");
  await staffPage.getByRole("button", { name: "Chọn suất", exact: true }).click();
  await staffPage.getByRole("button", { name: "A1, STANDARD, Trống", exact: true }).click();
  await staffPage.getByRole("button", { name: "A2, STANDARD, Trống", exact: true }).click();
  await staffPage.getByLabel(`Số lượng ${snack.name}`).fill("1"); await shot(staffPage, "S03");
  await staffPage.getByRole("button", { name: "Giữ các ghế đã chọn" }).click();
  await staffPage.getByRole("link", { name: "Tiếp tục thanh toán" }).click();
  await staffPage.getByRole("button", { name: "Tạo đơn, lấy tổng thanh toán" }).click();
  await expect(staffPage.getByLabel("Tiền khách đưa")).toBeVisible(); await shot(staffPage, "S04");
  const bookingId = Number(new URL(staffPage.url()).searchParams.get("booking")); expect(bookingId).toBeGreaterThan(0);
  // Reload requires authentication but restores only a public booking ID; hold token is fetched afresh.
  await staffPage.reload();
  await staffPage.getByLabel("Tên đăng nhập hoặc email").fill("staff01"); await staffPage.getByLabel("Mật khẩu", { exact: true }).fill(password!); await staffPage.getByRole("button", { name: "Đăng nhập", exact: false }).click();
  await expect(staffPage.getByLabel("Tiền khách đưa")).toBeVisible();
  await staffPage.getByLabel("Tiền khách đưa").fill("500000"); await staffPage.getByRole("button", { name: "Thu tiền mặt", exact: true }).click();
  await expect(staffPage).toHaveURL(/\/receipt$/); await expect(staffPage.locator("article.ticket")).toHaveCount(2); await shot(staffPage, "S05");
  const paid = await get(`/pos/bookings/${bookingId}`, staff); expect(paid.tickets).toHaveLength(2);
  await staffPage.getByRole("link", { name: "Giao dịch POS", exact: true }).click(); await staffPage.getByLabel("Mã đơn").fill(paid.bookingCode); await expect(staffPage.getByRole("link", { name: paid.bookingCode, exact: true })).toBeVisible(); await shot(staffPage, "S06");
  await staffPage.getByRole("link", { name: paid.bookingCode, exact: true }).click(); await shot(staffPage, "S07");
  await staffPage.getByRole("link", { name: "Yêu cầu hoàn vé tại quầy" }).click();
  await staffPage.getByRole("button", { name: "Kiểm tra điều kiện hoàn" }).click();
  await staffPage.getByRole("checkbox").first().check(); await staffPage.getByLabel("Lý do hoàn").fill("G5 partial refund");
  await staffPage.getByRole("checkbox", { name: "Xác nhận đã bàn giao đủ tiền mặt cho khách trước khi xin duyệt" }).check();
  await shot(staffPage, "S08"); await staffPage.getByRole("button", { name: "Yêu cầu Manager duyệt", exact: true }).click();
  await staffPage.getByLabel("Tài khoản Manager").fill("manager01"); await staffPage.getByLabel("PIN Manager").fill(process.env.DEMO_MANAGER_PIN ?? "123456"); await staffPage.getByRole("button", { name: "Xác thực Manager" }).click();
  await staffPage.getByRole("button", { name: "Hoàn các vé đã chọn" }).click(); await expect(staffPage.getByRole("status")).toContainText("Hoàn vé thành công");
  const refunded = await get(`/pos/bookings/${bookingId}`, staff); expect(refunded.refundStatus).toBe("PARTIAL");
  expect(refunded.tickets.filter((t: { status: string }) => t.status === "CANCELLED")).toHaveLength(1);
  await staffPage.getByRole("link", { name: "Soát vé", exact: true }).click(); await staffPage.getByLabel("Suất chiếu tại cửa").selectOption(String(show.id)); await staffPage.getByLabel("Mã vé / Booking").fill(paid.bookingCode); await staffPage.getByRole("button", { name: "Tra cứu vé" }).click();
  const valid = refunded.tickets.find((t: { status: string }) => t.status === "VALID");
  const accept = staffPage.getByRole("button", { name: `Chấp nhận vé ${refunded.seats.find((s: { seatId: number }) => s.seatId === valid.seatId).seatRow}${refunded.seats.find((s: { seatId: number }) => s.seatId === valid.seatId).seatNumber}`, exact: true });
  await accept.click(); await expect(staffPage.getByRole("status")).toContainText("Đã soát vé"); await expect(accept).toBeDisabled(); await shot(staffPage, "S09");
  // Online purchase: a signed test callback exercises the unchanged production verifier.
  await customerPage.goto("http://localhost:3003/"); await shot(customerPage, "C01"); await customerPage.getByRole("button", { name: "Đăng ký", exact: true }).click(); await shot(customerPage, "C02");
  await login(customerPage, "customer"); await customerPage.getByLabel("Tìm phim").fill(movie.title); await shot(customerPage, "C03"); await customerPage.getByRole("link", { name: movie.title, exact: true }).click(); await shot(customerPage, "C04");
  await customerPage.getByRole("button", { name: "Chọn suất", exact: true }).click(); await customerPage.getByRole("button", { name: "B1, SWEETBOX, Trống", exact: true }).click(); await expect(customerPage.getByRole("button", { name: "B2, SWEETBOX, Đã chọn" })).toHaveAttribute("aria-pressed", "true"); await customerPage.getByLabel(`Số lượng ${snack.name}`).fill("1"); await shot(customerPage, "C05");
  await customerPage.getByRole("button", { name: "Giữ các ghế đã chọn" }).click(); await customerPage.getByRole("link", { name: "Tiếp tục thanh toán" }).click(); await customerPage.getByRole("button", { name: "Tạo đơn, lấy tổng thanh toán" }).click(); await shot(customerPage, "C06");
  await customerPage.getByRole("button", { name: "Tạo liên kết VNPay" }).click(); const paymentLink = customerPage.getByRole("link", { name: "Mở VNPay", exact: true }); await expect(paymentLink).toBeVisible();
  const payUrl = new URL((await paymentLink.getAttribute("href"))!); const onlineId = Number(new URL(customerPage.url()).searchParams.get("booking"));
  await expect(customerPage.locator("article.ticket")).toHaveCount(0);
  const params = new URLSearchParams({ vnp_TmnCode: "G5TEST", vnp_TxnRef: payUrl.searchParams.get("vnp_TxnRef")!, vnp_Amount: payUrl.searchParams.get("vnp_Amount")!, vnp_ResponseCode: "00", vnp_TransactionStatus: "00", vnp_TransactionNo: "G5" + suffix, vnp_OrderInfo: payUrl.searchParams.get("vnp_OrderInfo")!, vnp_PayDate: "20261119180000", vnp_BankCode: "NCB" }); params.sort(); params.set("vnp_SecureHash", createHmac("sha512", process.env.TEST_VNPAY_HASH_SECRET!).update(params.toString()).digest("hex"));
  const ipn = await request.get(base + "/payments/vnpay/ipn?" + params); expect((await ipn.json()).RspCode).toBe("00");
  const repeat = await request.get(base + "/payments/vnpay/ipn?" + params); expect(["00", "02"]).toContain((await repeat.json()).RspCode);
  await expect(customerPage.locator("article.ticket")).toHaveCount(2);
  const returnPage = await customerContext.newPage(); await returnPage.goto("http://localhost:3003/payments/vnpay/return?" + params); await expect(returnPage.getByRole("status")).toContainText("đã xác nhận"); await shot(returnPage, "C07"); await returnPage.close();
  await customerPage.getByRole("link", { name: "Lịch sử đặt vé", exact: true }).click(); await shot(customerPage, "C09"); const online = await get(`/me/bookings/${onlineId}`, customer); await customerPage.getByRole("link", { name: online.bookingCode, exact: true }).click(); await expect(customerPage.locator("article.ticket")).toHaveCount(2); await shot(customerPage, "C08");
  // Third cash channel: manual bank confirmation cannot issue a ticket before the explicit check.
  const hold = await post(`/showtimes/${show.id}/holds`, { seatIds: [seatId("C1")] }, staff); const qrBooking = await post("/bookings", { showtimeId: show.id, holdToken: hold.holdToken }, staff); const qr = await post(`/bookings/${qrBooking.bookingId}/payments/vietqr`, { ageDocumentChecked: true }, staff); expect(qr.paymentStatus).toBe("PENDING"); expect(await get(`/bookings/${qrBooking.bookingId}/tickets`, staff)).toHaveLength(0); await post(`/bookings/${qrBooking.bookingId}/payments/vietqr/confirm`, undefined, staff);
  const afterRevenue = await get("/reports/revenue" + reportQuery);
  const breakdown = await get("/reports/revenue-breakdown" + reportQuery);
  const byChannel = await get("/reports/revenue-by-channel" + reportQuery);
  const count = await get("/reports/ticket-count" + reportQuery);
  const refundedTicket = refunded.tickets.find((t: { status: string }) => t.status === "CANCELLED");
  const grossDelta = Number(paid.totalAmount) + Number(online.totalAmount) + Number(qrBooking.totalAmount);
  const refundDelta = Number(refundedTicket.priceSnapshot);
  expect(Number(afterRevenue.totalRevenue) - Number(beforeRevenue.totalRevenue)).toBe(grossDelta);
  expect(Number(afterRevenue.refundAmount) - Number(beforeRevenue.refundAmount)).toBe(refundDelta);
  expect(Number(afterRevenue.netRevenue) - Number(beforeRevenue.netRevenue)).toBe(grossDelta - refundDelta);
  expect(Number(breakdown.netRevenue)).toBe(Number(afterRevenue.netRevenue));
  expect(Number(breakdown.netTicketRevenue) + Number(breakdown.concessionRevenue)).toBe(Number(afterRevenue.netRevenue));
  expect(byChannel.channels.reduce((sum: number, c: { netRevenue: number }) => sum + Number(c.netRevenue), 0)).toBe(Number(afterRevenue.netRevenue));
  expect(count.grossTicketCount - beforeCount.grossTicketCount).toBe(5);
  expect(count.cancelledTicketCount - beforeCount.cancelledTicketCount).toBe(1);
  expect(count.ticketCount - beforeCount.ticketCount).toBe(4);
  const showDate = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Ho_Chi_Minh", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date(show.startTime));
  const occupancy = await get(`/reports/occupancy?from=${showDate}&to=${showDate}`);
  expect(occupancy.showtimes.find((s: { showtimeId: number }) => s.showtimeId === show.id)).toMatchObject({ seatsSold: 4, totalSeats: 5 });
  for (const direction of ["ASC", "DESC"]) {
    const ranking = await get(`/reports/movies/ranking${reportQuery}&direction=${direction}&limit=100`);
    expect(ranking.direction).toBe(direction);
    const movieRank = ranking.movies.find((m: { movieId: number }) => m.movieId === movie.id);
    expect(Number(movieRank.revenue)).toBe(grossDelta - refundDelta);
  }
  await login(managerPage, "manager", "M01");
  for (const [id, name] of [["M03", "Phim"], ["M04", "Phòng & ghế"], ["M05", "Lịch chiếu"], ["M06", "Bảng giá"], ["M07", "F&B"], ["M08", "Tồn kho"], ["M09", "Nhân viên"]]) { await managerPage.getByRole("link", { name, exact: true }).click(); await expect(managerPage.locator("main h1")).toHaveText(name); await expect(managerPage.locator('.loading-state')).toHaveCount(0); await shot(managerPage, id); }
  await managerPage.getByRole("link", { name: "Báo cáo", exact: true }).click(); const revenue = managerPage.getByRole("region", { name: "Doanh thu", exact: true }); await expect(revenue.getByText("Doanh thu ròng", { exact: true })).toBeVisible(); await expect(managerPage.locator('.loading-state')).toHaveCount(0); await shot(managerPage, "M02");
  const currency = (amount: number) => new Intl.NumberFormat("vi-VN", { style: "currency", currency: "VND" }).format(amount);
  await expect(revenue.locator("dd").nth(0)).toHaveText(currency(Number(afterRevenue.totalRevenue)));
  await expect(revenue.locator("dd").nth(1)).toHaveText(currency(Number(afterRevenue.refundAmount)));
  await expect(revenue.locator("dd").nth(2)).toHaveText(currency(Number(afterRevenue.netRevenue)));
  expect(await managerPage.getByRole("alert").count()).toBe(0);
  expect(errors).toEqual([]);
  await writeFile(`${imageRoot}/live-evidence.json`, JSON.stringify({ environment: "isolated PostgreSQL (disposable G5 test database)", capturedAt: new Date().toISOString(), browserVersion: browser.version(), captures, responsiveCaptures, timings: timing, maxReadMs: Math.max(...timing.filter((t) => t.method === "GET").map((t) => t.ms)), maxWriteMs: Math.max(...timing.filter((t) => t.method === "POST").map((t) => t.ms)), bookingId, onlineId, refundStatus: refunded.refundStatus, reconciliation: { grossDelta, refundDelta, netDelta: grossDelta - refundDelta, grossTicketDelta: 5, cancelledTicketDelta: 1, netTicketDelta: 4, occupiedSeats: 4 }, pageErrors: errors, consoleIssues, networkIssues }, null, 2));
  await Promise.all([staffContext.close(), customerContext.close(), managerContext.close()]);
});
