import { expect, test, type Page } from "@playwright/test";

// Isolated, deterministic UI contracts. Fixtures do not verify Java or live services.
const agent = { id: 2, username: "agent", displayName: "测试客服", role: "AGENT", teamId: 1 };
const supervisor = { ...agent, id: 3, role: "SUPERVISOR" };
const knowledge = { id: 8, ticketId: 21, teamId: 1, title: "电源排查", content: "断电后重新连接并验证。", status: "DRAFT", authorId: 2, reviewerId: null, createdAt: "2026-11-10T08:00:00", reviewedAt: null };
async function mount(page: Page, component: string, props: object, fixtures: Record<string, unknown>) {
  await page.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname.replace("/api", "");
    await route.fulfill({ json: fixtures[path] ?? [] });
  });
  await page.route("**/component-contract", route => route.fulfill({ contentType: "text/html", body: `<!doctype html><html lang="zh-CN"><head><link rel="stylesheet" href="/node_modules/element-plus/dist/index.css"></head><body><div id="app"></div><script type="module">import {createApp} from '/node_modules/.vite/deps/vue.js'; import Component from '/src/components/${component}.vue'; createApp(Component, ${JSON.stringify(props)}).mount('#app');</script></body></html>` }));
  await page.goto("/component-contract");
}

test("notifications mark read only on request and render escaped content", async ({ page }) => {
  await mount(page, "OperationsView", { user: agent, view: "notifications" }, { "/notifications": [{ id: 7, ticketId: 21, title: "工单已分派", body: "<img src=x onerror=alert(1)>", read: false, createdAt: "2026-11-10T08:00:00" }] });
  let reads = 0;
  await page.route("**/api/notifications/7/read", async route => { reads++; expect(route.request().postDataJSON()).toEqual({}); await route.fulfill({ status: 204 }); });
  await expect(page.getByText("工单已分派", { exact: true })).toBeVisible();
  await expect(page.locator("img")).toHaveCount(0);
  expect(reads).toBe(0);
  await page.getByRole("button", { name: "标为已读" }).click();
  await expect(page.getByText("已读", { exact: true })).toBeVisible();
  expect(reads).toBe(1);
});

test("knowledge review requires explicit confirmation and scopes actions by role", async ({ page }) => {
  await mount(page, "OperationsView", { user: supervisor, view: "knowledge" }, { "/knowledge": [knowledge] });
  let reviews = 0;
  await page.route("**/api/knowledge/8/review", async route => { reviews++; expect(route.request().postDataJSON()).toEqual({ approved: true, confirmed: true }); await route.fulfill({ json: { ...knowledge, status: "PUBLISHED" } }); });
  const approve = page.getByRole("button", { name: "审核通过" });
  await expect(approve).toBeDisabled();
  expect(reviews).toBe(0);
  await page.getByText("我已审核内容，确认提交审核结果", { exact: true }).click();
  await approve.click();
  await expect(page.getByText("已通过", { exact: true })).toBeVisible();
  expect(reviews).toBe(1);
  await mount(page, "OperationsView", { user: agent, view: "knowledge" }, { "/knowledge": [knowledge] });
  await expect(page.getByText("电源排查", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "审核通过" })).toHaveCount(0);
});

test("closed ticket knowledge uses manual confirmed content and renders SLA and audit", async ({ page }) => {
  await mount(page, "TicketOperations", { id: 21, user: agent, status: "CLOSED" }, {
    "/tickets/21/sla": { ticketId: 21, firstResponseDue: "2026-11-10T09:00:00", resolutionDue: "2026-11-11T08:00:00", firstRespondedAt: "2026-11-10T08:20:00", escalatedAt: null, firstResponseBreached: false, resolutionBreached: false, resolutionPaused: false },
    "/tickets/21/audit": [{ id: 1, ticketId: 21, actorId: 2, actorName: "测试客服", action: "TRANSITION", message: "工单完成", createdAt: "2026-11-10T08:00:00" }],
  });
  let drafts = 0;
  await page.route("**/api/tickets/21/knowledge", async route => { drafts++; expect(route.request().postDataJSON()).toEqual({ title: "电源排查", content: "断电后重新连接并验证。", confirmed: true }); await route.fulfill({ status: 201, json: knowledge }); });
  await expect(page.getByRole("heading", { name: "服务时限" })).toBeVisible();
  await page.getByLabel("知识标题").fill("电源排查");
  await page.getByLabel("解决方法", { exact: true }).fill("断电后重新连接并验证。");
  const submit = page.getByRole("button", { name: "提交知识草稿" });
  await expect(submit).toBeDisabled();
  expect(drafts).toBe(0);
  await page.getByText("我已检查内容，不含客户个人信息，确认提交审核", { exact: true }).click();
  await submit.click();
  await expect(page.getByText("知识草稿已提交，等待主管审核。", { exact: true })).toBeVisible();
  expect(drafts).toBe(1);
});

test("notification errors recover through refresh and empty state stays truthful", async ({ page }) => {
  await mount(page, "OperationsView", { user: agent, view: "notifications" }, { "/notifications": [] });
  await expect(page.getByRole("heading", { name: "暂无消息" })).toBeVisible();
  await page.route("**/api/notifications", route => route.fulfill({ status: 503, json: { message: "通知服务暂不可用" } }));
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("通知服务暂不可用");
  await expect(page.getByRole("heading", { name: "暂无消息" })).toHaveCount(0);
  await page.route("**/api/notifications", route => route.fulfill({ json: [] }));
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await expect(page.getByRole("heading", { name: "暂无消息" })).toBeVisible();
  await expect(page.getByRole("alert")).toHaveCount(0);
});

test("customer ticket operations never request staff endpoints or expose their controls", async ({ page }) => {
  let staffRequests = 0;
  page.on("request", request => { if (/\/(audit|sla)$/.test(request.url())) staffRequests++; });
  await page.setViewportSize({ width: 390, height: 844 });
  await mount(page, "TicketOperations", { id: 21, user: { ...agent, role: "CUSTOMER" }, status: "CLOSED" }, {
    "/tickets/21/sla": { ticketId: 21, firstResponseDue: "2026-11-10T09:00:00", resolutionDue: "2026-11-11T08:00:00", firstRespondedAt: null, escalatedAt: null, firstResponseBreached: false, resolutionBreached: false, resolutionPaused: false },
  });
  await page.waitForLoadState("networkidle");
  await expect(page.getByRole("region", { name: "服务时限" })).toHaveCount(0);
  await expect(page.getByRole("region", { name: "操作审计" })).toHaveCount(0);
  await expect(page.getByLabel("知识标题")).toHaveCount(0);
  expect(staffRequests).toBe(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
});
