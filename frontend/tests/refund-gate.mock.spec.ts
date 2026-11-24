import { test, expect, login } from "./fixtures/mock-api";
test("Wrong Manager PIN preserves Staff session, then refund requires cash handover", async ({
  page,
  api,
}) => {
  await login(page, "staff");
  await page
    .getByRole("link", { name: "Yêu cầu hoàn vé", exact: true })
    .click();
  await page.getByLabel("Booking Code", { exact: true }).fill("BK-POS-DEMO");
  await page.getByRole("button", { name: "Kiểm tra điều kiện hoàn" }).click();
  await page.getByRole("checkbox").first().check();
  await page.getByLabel("Lý do hoàn").fill("Khách đổi kế hoạch");
  const requestApproval = page.getByRole("button", { name: "Yêu cầu Manager duyệt" });
  await expect(requestApproval).toBeDisabled();
  await page.getByRole("checkbox", { name: "Xác nhận đã bàn giao đủ tiền mặt cho khách trước khi xin duyệt" }).check();
  await requestApproval.click();
  await page.getByLabel("Tài khoản Manager").fill("manager01");
  await page.getByLabel("PIN Manager").fill("000000");
  await page.getByRole("button", { name: "Xác thực Manager" }).click();
  await expect(page.getByRole("dialog").getByRole("alert")).toContainText(
    "PIN Manager",
  );
  await expect(page.getByLabel("PIN Manager")).toHaveValue("");
  await page.getByLabel("PIN Manager").fill("123456");
  await page.getByRole("button", { name: "Xác thực Manager" }).click();
  const refund = page.getByRole("button", { name: "Hoàn các vé đã chọn" });
  await expect(refund).toBeEnabled();
  await refund.click();
  await expect(page.getByRole("status")).toContainText("Hoàn vé thành công");
});
test("Gate verifies showtime and age, then prevents reuse", async ({
  page,
  api,
}) => {
  await login(page, "staff");
  await page.getByRole("link", { name: "Soát vé", exact: true }).click();
  await page.getByLabel("Suất chiếu tại cửa").selectOption("1");
  await page.getByLabel("Mã vé / Booking").fill("TK-DEMO-1");
  await page.getByRole("button", { name: "Tra cứu vé" }).click();
  const accept = page.getByRole("button", { name: "Chấp nhận vé A5" });
  await expect(accept).toBeDisabled();
  await page.getByRole("checkbox", { name: "Đã kiểm tra giấy tờ" }).check();
  await accept.click();
  await expect(page.getByRole("status")).toContainText("Đã soát vé");
  await expect(accept).toBeDisabled();
});

test("Lost refund response retries the identical approval without changing the Staff session", async ({ page, api }) => {
  const payloads: string[] = [];
  await page.route("**/api/v1/refunds", async (route) => {
    const req = route.request();
    if (req.method() !== "POST") return route.fallback();
    payloads.push(req.postData()!);
    if (payloads.length > 1) return route.fallback();
    const committed = await api.handle(new Request(req.url(), { method: "POST", headers: req.headers(), body: req.postData() }));
    expect(committed.ok).toBe(true);
    await route.abort("connectionreset");
  });
  await login(page, "staff");
  await page.getByRole("link", { name: "Yêu cầu hoàn vé", exact: true }).click();
  await page.getByLabel("Booking Code", { exact: true }).fill("BK-POS-DEMO");
  await page.getByRole("button", { name: "Kiểm tra điều kiện hoàn" }).click();
  await page.getByRole("checkbox").first().check();
  await page.getByLabel("Lý do hoàn").fill("Read back committed refund");
  await page.getByRole("checkbox", { name: "Xác nhận đã bàn giao đủ tiền mặt cho khách trước khi xin duyệt" }).check();
  await page.getByRole("button", { name: "Yêu cầu Manager duyệt" }).click();
  await page.getByLabel("Tài khoản Manager").fill("manager01");
  await page.getByLabel("PIN Manager").fill("123456");
  await page.getByRole("button", { name: "Xác thực Manager" }).click();
  const commit = page.getByRole("button", { name: "Hoàn các vé đã chọn" });
  await commit.click();
  await expect(page.getByRole("dialog").getByRole("alert")).toContainText("thử lại cùng yêu cầu");
  await commit.click();
  await expect(page.getByRole("status")).toContainText("Hoàn vé thành công");
  expect(payloads).toHaveLength(2);
  expect(payloads[0]).toBe(payloads[1]);
  await expect(page.locator(".sidebar")).toContainText("Nhân viên mẫu");
  await page.getByRole("link", { name: "Tài khoản", exact: true }).click();
  await expect(page.locator("main")).toContainText("staff01");
});

test("Expired approval asks for Manager authentication again and clears the PIN", async ({ page, api }) => {
  let first = true;
  await page.route("**/api/v1/refunds", async (route) => {
    if (route.request().method() !== "POST" || !first) return route.fallback();
    first = false;
    await route.fulfill({ status: 403, contentType: "application/json", headers: { "Access-Control-Allow-Origin": "*" }, body: JSON.stringify({ code: "MANAGER_OVERRIDE_EXPIRED", message: "Expired", details: {} }) });
  });
  await login(page, "staff");
  await page.getByRole("link", { name: "Yêu cầu hoàn vé", exact: true }).click();
  await page.getByLabel("Booking Code", { exact: true }).fill("BK-POS-DEMO");
  await page.getByRole("button", { name: "Kiểm tra điều kiện hoàn" }).click();
  await page.getByRole("checkbox").first().check();
  await page.getByLabel("Lý do hoàn").fill("Expired approval");
  await page.getByRole("checkbox", { name: "Xác nhận đã bàn giao đủ tiền mặt cho khách trước khi xin duyệt" }).check();
  await page.getByRole("button", { name: "Yêu cầu Manager duyệt" }).click();
  await page.getByLabel("Tài khoản Manager").fill("manager01");
  await page.getByLabel("PIN Manager").fill("123456");
  await page.getByRole("button", { name: "Xác thực Manager" }).click();
  await page.getByRole("button", { name: "Hoàn các vé đã chọn" }).click();
  await expect(page.getByRole("dialog").getByRole("alert")).toContainText("xác thực Manager lại");
  await expect(page.getByLabel("PIN Manager")).toHaveValue("");
  await expect(page.getByRole("button", { name: "Hoàn các vé đã chọn" })).toHaveCount(0);
  await page.getByLabel("PIN Manager").fill("123456");
  await page.getByRole("button", { name: "Xác thực Manager" }).click();
  await page.getByRole("button", { name: "Hoàn các vé đã chọn" }).click();
  await expect(page.getByRole("status")).toContainText("Hoàn vé thành công");
});
