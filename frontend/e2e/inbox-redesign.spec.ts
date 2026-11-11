import { test, expect } from "@playwright/test";
// Deterministic design/interaction fixtures; not evidence of backend or AI accuracy.
test("service inbox preserves context, keyboard selection and mobile navigation", async ({
  page,
}) => {
  const user = {
    id: 5,
    username: "supervisor",
    displayName: "许宁 · 主管",
    role: "SUPERVISOR",
    teamId: 1,
  };
  const base = {
    reference: "SF-UI-01",
    description: "充电后左耳仍然无声，已尝试重新配对。",
    category: "DEVICE",
    priority: "NORMAL",
    status: "IN_PROGRESS",
    customerId: 1,
    teamId: 1,
    assigneeId: 3,
    version: 2,
    createdAt: "2026-11-11T04:00:00",
    updatedAt: "2026-11-11T05:00:00",
    customerName: "林晓 · 客户",
    assigneeName: "苏晴 · 客服",
  };
  const tickets = [
    { ...base, id: 101, title: "蓝牙耳机左侧无声" },
    { ...base, id: 102, title: "显示器间歇黑屏" },
  ];
  await page.addInitScript(() =>
    sessionStorage.setItem("sf_token", "fake-ui-contract"),
  );
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const found = tickets.find((t) => path === "/api/tickets/" + t.id);
    const data =
      path === "/api/auth/me"
        ? user
        : path === "/api/capabilities"
          ? {
              milestone: "M4",
              aiMode: "disabled",
              ragEnabled: false,
              message: "未配置",
            }
          : found
            ? { ticket: found, comments: [], events: [], allowedActions: [] }
            : path === "/api/tickets"
              ? { items: tickets, total: 2, page: 1, size: 12 }
              : path.endsWith("/sla")
                ? {}
                : [];
    await route.fulfill({ json: data });
  });
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "蓝牙耳机左侧无声", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".case-properties")).toBeVisible();
  await page
    .getByRole("button", { name: "显示器间歇黑屏", exact: true })
    .focus();
  await page.keyboard.press("Enter");
  await expect(
    page.getByRole("heading", { name: "显示器间歇黑屏", exact: true }),
  ).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: "返回队列", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "蓝牙耳机左侧无声", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "蓝牙耳机左侧无声", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "蓝牙耳机左侧无声", exact: true }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
  await expect(
    page.getByRole("button", { name: "退出登录", exact: true }),
  ).toBeVisible();
});
