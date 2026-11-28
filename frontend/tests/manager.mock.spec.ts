import { test, expect, login } from "./fixtures/mock-api";
test("Manager creates a movie and sees the saved record", async ({
  page,
  api,
}) => {
  await login(page, "manager");
  await page.getByRole("link", { name: "Phim", exact: true }).click();
  await page.getByRole("button", { name: "Thêm mới", exact: true }).click();
  await page
    .getByLabel("Tên phim", { exact: true })
    .fill("Phim tham chiếu mới");
  await page.getByLabel("Thời lượng (phút)").fill("100");
  await page.getByLabel("Ngày phát hành").fill("2026-10-30");
  await page.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(
    page.getByRole("cell", { name: "Phim tham chiếu mới", exact: true }),
  ).toBeVisible();
});
test("Manager restocks and reviews immutable history", async ({
  page,
  api,
}) => {
  await login(page, "manager");
  await page.getByRole("link", { name: "Tồn kho", exact: true }).click();
  await page
    .getByRole("button", { name: "Nhập kho", exact: true })
    .first()
    .click();
  await page.getByLabel("Số lượng nhập").fill("7");
  await page.getByLabel("Ghi chú").fill("Nhập hàng kiểm thử");
  await page.getByRole("button", { name: "Lưu", exact: true }).click();
  await page
    .getByRole("link", { name: "Lịch sử nhập kho", exact: true })
    .first()
    .click();
  await expect(
    page.getByRole("cell", { name: "Nhập hàng kiểm thử", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("cell", { name: "50 → 57", exact: true }),
  ).toBeVisible();
});
test("Schedule conflict is shown in the form", async ({ page, api }) => {
  await login(page, "manager");
  api.setScenario("schedule");
  await page.getByRole("link", { name: "Lịch chiếu", exact: true }).click();
  await page.getByRole("button", { name: "Thêm mới", exact: true }).click();
  await page.getByLabel("Bắt đầu (giờ Việt Nam)").fill("2026-10-30T19:00");
  await page.getByRole("button", { name: "Lưu", exact: true }).click();
  await expect(page.getByRole("dialog").getByRole("alert")).toContainText(
    "trùng phòng",
  );
});
test("Report rejects an inverted date range", async ({ page, api }) => {
  await login(page, "manager");
  await page.getByRole("link", { name: "Báo cáo", exact: true }).click();
  await expect(page).toHaveURL(/\/reports$/);
  await page.getByLabel("Từ ngày").fill("2030-01-02");
  await page.getByLabel("Đến ngày").fill("2030-01-01");
  await expect(page.getByRole("alert").first()).toContainText("Ngày bắt đầu");
});
