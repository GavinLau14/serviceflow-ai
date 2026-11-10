import { test, expect, Page } from "@playwright/test";
const password = process.env.SERVICEFLOW_DEMO_PASSWORD;
if (!password)
  throw new Error("Set SERVICEFLOW_DEMO_PASSWORD from the local .env");
async function login(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("账号", { exact: true }).fill(user);
  await page.getByLabel("密码", { exact: true }).fill(password!);
  await page.getByRole("button", { name: "登录工作台", exact: true }).click();
  await expect(page.getByRole("button", { name: "退出登录" })).toBeVisible();
}
async function logout(page: Page) {
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(page.getByLabel("账号", { exact: true })).toBeVisible();
}
test("客户提交 → 主管分派 → 客服处理 → 客户确认关闭", async ({ page }) => {
  const title = "浏览器售后闭环 " + Date.now();
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await login(page, "alice");
  await page
    .getByRole("button", { name: "提交售后问题", exact: true })
    .first()
    .click();
  await page.getByLabel("问题标题", { exact: true }).fill(title);
  await page
    .getByLabel("问题描述", { exact: true })
    .fill("设备无法开机，已检查插座和电源连接，请协助处理。");
  await page.getByRole("button", { name: "确认提交", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: title, exact: true }),
  ).toBeVisible();
  await logout(page);
  await login(page, "supervisor");
  await page.getByRole("button", { name: title, exact: true }).click();
  await page.getByRole("button", { name: "分派客服", exact: true }).click();
  await page.getByLabel("负责客服", { exact: true }).press("ArrowDown");
  await page.getByRole("option").filter({ hasText: "苏晴" }).click();
  await page.getByText("我确认将此工单分派给所选客服", { exact: true }).click();
  await page.getByRole("button", { name: "确认分派", exact: true }).click();
  await expect(
    page.getByText("已分派给 苏晴 · 客服", { exact: true }),
  ).toBeVisible();
  await logout(page);
  await login(page, "agent");
  await page.getByRole("button", { name: title, exact: true }).click();
  await page.getByRole("button", { name: "开始处理", exact: true }).click();
  await page
    .getByLabel("处理说明", { exact: true })
    .fill("已排查并更换异常电源模块，设备开机测试正常。");
  await page.getByRole("button", { name: "提交解决方案", exact: true }).click();
  await expect(
    page.getByText("等待客户确认", { exact: true }).first(),
  ).toBeVisible();
  await logout(page);
  await login(page, "alice");
  await page.getByRole("button", { name: title, exact: true }).click();
  await page
    .getByRole("button", { name: "确认解决并关闭", exact: true })
    .click();
  await page.getByRole("button", { name: "确认关闭", exact: true }).click();
  await expect(
    page.getByText("此工单已完成，感谢你的确认。", { exact: true }),
  ).toBeVisible();
  expect(errors).toEqual([]);
  await expect(page.getByRole("dialog")).toBeHidden();
  await page.screenshot({
    path: "test-results/lifecycle-desktop.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: "test-results/lifecycle-mobile.png",
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBeTruthy();
});
