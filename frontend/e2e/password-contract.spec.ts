import { expect, test, type Page, type Route } from "@playwright/test";

// UI-only fixtures. Actual password and session invalidation are tested in Java.
async function mount(page: Page, password: (route: Route) => Promise<void>) {
  await page.addInitScript(() => sessionStorage.setItem("sf_token", "fake-password-ui"));
  await page.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/auth/password") return password(route);
    const value = path === "/api/auth/me"
      ? { id: 1, username: "alice", displayName: "测试客户", role: "CUSTOMER", teamId: null }
      : path === "/api/tickets" ? { items: [], total: 0, page: 1, size: 12 }
      : { aiMode: "disabled", ragEnabled: false, message: "未配置" };
    await route.fulfill({ json: value });
  });
  await page.goto("/");
  await page.getByRole("button", { name: "账号安全", exact: true }).click();
}

test("password change requires matching input and explicit consent, then clears the local session", async ({ page }) => {
  let calls = 0;
  await mount(page, async route => {
    calls++;
    expect(route.request().postDataJSON()).toEqual({ currentPassword: "Current-Test-Password!", newPassword: "New-Test-Password-123!", confirmed: true });
    await route.fulfill({ status: 204 });
  });
  const submit = page.getByRole("button", { name: "修改密码并重新登录", exact: true });
  await expect(submit).toBeDisabled();
  await page.getByLabel("当前密码", { exact: true }).fill("Current-Test-Password!");
  await page.getByLabel("新密码", { exact: true }).fill("密".repeat(25));
  await page.getByLabel("再次输入新密码", { exact: true }).fill("密".repeat(25));
  await page.getByText("我确认修改密码，并退出全部已登录设备", { exact: true }).click();
  await expect(submit).toBeDisabled();
  await page.getByLabel("新密码", { exact: true }).fill("New-Test-Password-123!");
  await expect(submit).toBeDisabled();
  await page.getByLabel("再次输入新密码", { exact: true }).fill("New-Test-Password-123!");
  expect(calls).toBe(0);
  await submit.click();
  await expect(page.getByRole("heading", { name: "登录服务台", exact: true })).toBeVisible();
  expect(await page.evaluate(() => sessionStorage.getItem("sf_token"))).toBeNull();
  expect(calls).toBe(1);
});

test("failed password changes keep the session and closing the panel clears sensitive fields", async ({ page }) => {
  await mount(page, route => route.fulfill({ status: 400, json: { message: "当前密码不正确" } }));
  await page.getByLabel("当前密码", { exact: true }).fill("Wrong-Test-Password!");
  await page.getByLabel("新密码", { exact: true }).fill("New-Test-Password-123!");
  await page.getByLabel("再次输入新密码", { exact: true }).fill("New-Test-Password-123!");
  await page.getByText("我确认修改密码，并退出全部已登录设备", { exact: true }).click();
  await page.getByRole("button", { name: "修改密码并重新登录", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("当前密码不正确");
  expect(await page.evaluate(() => sessionStorage.getItem("sf_token"))).toBe("fake-password-ui");
  await page.getByRole("button", { name: "取消修改", exact: true }).click();
  await page.getByRole("button", { name: "账号安全", exact: true }).click();
  await expect(page.getByLabel("当前密码", { exact: true })).toHaveValue("");
  await expect(page.getByLabel("新密码", { exact: true })).toHaveValue("");
  await expect(page.getByLabel("再次输入新密码", { exact: true })).toHaveValue("");
});

test("account security is reachable on a phone without horizontal overflow", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mount(page, async () => { throw new Error("No write expected"); });
  await expect(page.getByLabel("当前密码", { exact: true })).toBeVisible();
  await expect.poll(() => page.getByRole("dialog", { name: "账号安全", exact: true }).evaluate(el => {
    const rect = el.getBoundingClientRect();
    return rect.left >= -1 && rect.right <= innerWidth + 1 && rect.width <= innerWidth + 1;
  })).toBeTruthy();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
});
