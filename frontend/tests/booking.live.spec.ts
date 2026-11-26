import { test, expect } from "@playwright/test";
const password = process.env.DEMO_PASSWORD;
const base = "http://localhost:8081/api/v1";
test("live POS and Customer compete for one PostgreSQL inventory seat", async ({
  request,
}) => {
  if (!password)
    throw new Error("Set DEMO_PASSWORD for the isolated local backend.");
  const login = async (username: string) => {
    const response = await request.post(base + "/auth/login", {
      data: { username, password },
    });
    expect(response.status()).toBe(200);
    return (await response.json()).data.accessToken as string;
  };
  const [staff, customer] = await Promise.all([
    login("staff01"),
    login("customer01"),
  ]);
  const headers = (token: string) => ({ Authorization: "Bearer " + token });
  const movies = await request.get(
    base + "/movies?keyword=Reference%20seat%20holding%20demo",
    { headers: headers(staff) },
  );
  const moviesJson = (await movies.json()).data;
  const movie = Array.isArray(moviesJson) ? moviesJson[0] : moviesJson.items?.[0];
  expect(movie).toBeTruthy();
  const shows = await request.get(base + "/showtimes?movieId=" + movie.id, {
    headers: headers(staff),
  });
  const showsJson = (await shows.json()).data;
  const show = Array.isArray(showsJson) ? showsJson[0] : showsJson.items?.[0];
  const map = await request.get(base + `/showtimes/${show.id}/seats`, {
    headers: headers(staff),
  });
  const seat = (await map.json()).data.seats.find(
    (s: { status: string; seatType: string }) =>
      s.status === "AVAILABLE" && s.seatType === "STANDARD",
  );
  expect(seat).toBeTruthy();
  const results = await Promise.all(
    [staff, customer].map((token) =>
      request.post(base + `/showtimes/${show.id}/holds`, {
        headers: headers(token),
        data: { seatIds: [seat.seatId] },
      }),
    ),
  );
  const statuses = results.map((r) => (r.status() === 201 ? 200 : r.status())).sort();
  expect(statuses).toEqual([200, 409]);
  const index = results.findIndex((r) => r.status() === 200 || r.status() === 201),
    hold = (await results[index].json()).data;
  const stranger = await request.get(base + `/showtimes/${show.id}/seats`, {
    headers: headers([staff, customer][1 - index]),
  });
  expect(JSON.stringify(await stranger.json())).not.toContain(hold.holdToken);
  expect(
    (
      await request.delete(base + "/holds/" + hold.holdToken, {
        headers: headers([staff, customer][index]),
      })
    ).status(),
  ).toBe(204);
});
test("live Customer browses a real showtime and holds a Sweetbox pair", async ({
  page,
}) => {
  await page.goto("http://localhost:3003/");
  await page.getByLabel("Tên đăng nhập hoặc email").fill("customer01");
  await page.getByLabel("Mật khẩu", { exact: true }).fill(password!);
  await page.getByRole("button", { name: "Đăng nhập", exact: false }).click();
  await page.getByLabel("Tìm phim").fill("Reference seat holding demo");
  await page
    .getByRole("link", { name: "Reference seat holding demo", exact: true })
    .click();
  await page.getByRole("button", { name: "Chọn suất" }).first().click();
  await page.getByRole("button", { name: "C1, SWEETBOX, Trống" }).click();
  await expect(
    page.getByRole("button", { name: "C2, SWEETBOX, Đã chọn" }),
  ).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: "Giữ các ghế đã chọn" }).click();
  await expect(
    page.getByRole("link", { name: "Tiếp tục thanh toán" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Đổi ghế", exact: true }).click();
  await page
    .getByRole("button", { name: "Giải phóng ghế", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "C1, SWEETBOX, Trống" }),
  ).toBeEnabled();
});

test("live Manager creates master data, Staff sells POS cash ticket, and validates at gate", async ({
  page,
}) => {
  const suffix = Date.now().toString().slice(-6);
  const movieTitle = "Phim Live " + suffix;
  const roomName = "Phòng Live " + suffix;
  const snackName = "Bắp Live " + suffix;

  await page.goto("http://localhost:3001/");
  await page.getByLabel("Tên đăng nhập hoặc email").fill("manager01");
  await page.getByLabel("Mật khẩu", { exact: true }).fill(password!);
  await page.getByRole("button", { name: "Đăng nhập", exact: false }).click();

  await page.getByRole("link", { name: "Phim", exact: true }).click();
  await page.getByRole("button", { name: "Thêm mới", exact: true }).click();
  await page.getByLabel("Tên phim", { exact: true }).fill(movieTitle);
  await page.getByLabel("Thời lượng (phút)").fill("100");
  await page.getByLabel("Ngày phát hành").fill("2026-11-11");
  await page.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(
    page.getByRole("cell", { name: movieTitle, exact: true }),
  ).toBeVisible();

  await page.getByRole("link", { name: "Phòng & ghế", exact: true }).click();
  await page.getByRole("button", { name: "Thêm mới", exact: true }).click();
  await page.getByLabel("Tên phòng", { exact: true }).fill(roomName);
  await page.getByLabel("Số ghế", { exact: true }).fill("10");
  await page.getByRole("button", { name: "Lưu", exact: true }).click();
  const roomRow = page.getByRole("row").filter({ hasText: roomName });
  await expect(roomRow).toBeVisible();
  await roomRow.getByRole("link", { name: "Sơ đồ ghế" }).click();

  await page.getByRole("button", { name: "Thêm ghế", exact: true }).click();
  await page.getByLabel("Hàng", { exact: true }).fill("A");
  await page.getByLabel("Số ghế", { exact: true }).fill("1");
  await page.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(page.getByRole("button", { name: "Sửa ghế A1, STANDARD", exact: true })).toBeVisible();

  await page.getByRole("link", { name: "F&B", exact: true }).click();
  await page.getByRole("button", { name: "Thêm mới", exact: true }).click();
  await page.getByLabel("Tên món / combo").fill(snackName);
  await page.getByLabel("Giá bán").fill("45000");
  await page.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(
    page.getByRole("cell", { name: snackName, exact: true }),
  ).toBeVisible();

  await page.getByRole("link", { name: "Tồn kho", exact: true }).click();
  const snackCard = page.locator("article.card").filter({ hasText: snackName });
  await snackCard.getByRole("button", { name: "Nhập kho" }).click();
  await page.getByLabel("Số lượng nhập").fill("20");
  await page.getByLabel("Ghi chú").fill("Nhập lô live");
  await page.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(snackCard).toContainText("Tồn 20");

  await page.getByRole("link", { name: "Lịch chiếu", exact: true }).click();
  await page.getByRole("button", { name: "Thêm mới", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await dialog.locator('select[name="movieId"]').selectOption({ label: movieTitle });
  await dialog.locator('select[name="auditoriumId"]').selectOption({ label: roomName });
  const startLocal = new Date(
    Date.now() + 30 * 60000 - new Date().getTimezoneOffset() * 60000,
  )
    .toISOString()
    .slice(0, 16);
  await dialog.locator('input[name="startTime"]').fill(startLocal);
  await dialog.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(
    page.getByRole("cell", { name: movieTitle }).first(),
  ).toBeVisible();

  await page.goto("http://localhost:3002/");
  await page.getByLabel("Tên đăng nhập hoặc email").fill("staff01");
  await page.getByLabel("Mật khẩu", { exact: true }).fill(password!);
  await page.getByRole("button", { name: "Đăng nhập", exact: false }).click();

  await page.getByLabel("Phim").selectOption({ label: movieTitle });
  const showCard = page.locator("article.card").filter({ hasText: movieTitle }).first();
  await showCard.getByRole("button", { name: "Chọn suất" }).click();

  await page.getByRole("button", { name: "A1, STANDARD, Trống" }).click();
  await page.getByLabel(`Số lượng ${snackName}`).fill("1");
  await page.getByRole("button", { name: "Giữ các ghế đã chọn" }).click();
  await page.getByRole("link", { name: "Tiếp tục thanh toán" }).click();

  await page.getByRole("button", { name: "Tạo đơn, lấy tổng thanh toán" }).click();
  await page.getByLabel("Tiền khách đưa").fill("500000");
  await page.getByRole("button", { name: "Thu tiền mặt" }).click();

  await expect(page).toHaveURL(/\/pos\/bookings\/\d+\/receipt$/);
  await expect(
    page.getByRole("button", { name: "In vé / hóa đơn" }),
  ).toBeVisible();
  const ticketText = await page.locator("article.ticket").first().textContent();
  const ticketCode = ticketText?.match(/TK-[A-Za-z0-9_-]+/)?.[0];
  expect(ticketCode).toBeTruthy();

  await page.getByRole("link", { name: "Giao dịch POS" }).click();
  await expect(page.getByRole("table")).toContainText("CASH");

  await page.getByRole("link", { name: "Soát vé" }).click();
  const gateSelect = page.getByLabel("Suất chiếu tại cửa");
  const optionVal = await gateSelect
    .locator("option", { hasText: movieTitle })
    .getAttribute("value");
  await gateSelect.selectOption(optionVal!);
  await page.getByLabel("Mã vé / Booking").fill(ticketCode!);
  await page.getByRole("button", { name: "Tra cứu vé" }).click();
  await page.getByRole("button", { name: /Chấp nhận vé A1/ }).click();
  await expect(page.getByRole("status")).toContainText("Đã soát vé");
});
