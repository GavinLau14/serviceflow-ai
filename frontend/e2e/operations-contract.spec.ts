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
  const notice = { id: 7, ticketId: 21, title: "工单已分派", body: "<img src=x onerror=alert(1)>", read: false, createdAt: "2026-11-10T08:00:00" };
  const inbox = { items: [notice], total: 1, unreadTotal: 1, page: 1, size: 20 };
  await mount(page, "OperationsView", { user: agent, view: "notifications" }, { "/notifications/inbox": inbox });
  let reads = 0;
  await page.route("**/api/notifications/7/read", async route => { reads++; notice.read = true; inbox.unreadTotal = 0; expect(route.request().postDataJSON()).toEqual({}); await route.fulfill({ status: 204 }); });
  await expect(page.getByText("工单已分派", { exact: true })).toBeVisible();
  await expect(page.locator("img")).toHaveCount(0);
  expect(reads).toBe(0);
  await page.getByRole("button", { name: "标为已读", exact: true }).click();
  await expect(page.getByText("已读", { exact: true })).toBeVisible();
  expect(reads).toBe(1);
});

test("notification pagination and unread filter use server counts and recover from an empty last page", async ({ page }) => {
  const notice = { id: 7, ticketId: 21, title: "末页通知", body: "处理进展", read: false, createdAt: "2026-11-10T08:00:00" };
  await mount(page, "OperationsView", { user: agent, view: "notifications" }, {
    "/notifications/inbox": { items: [notice], total: 21, unreadTotal: 21, page: 1, size: 20 },
  });
  await expect(page.getByText("21 条未读", { exact: true })).toBeVisible();
  const queries: string[] = [];
  let read = false;
  await page.route("**/api/notifications/inbox?**", async route => {
    const query = new URL(route.request().url()).searchParams;
    queries.push(query.toString());
    const current = Number(query.get("page"));
    await route.fulfill({ json: { items: current === 2 && read ? [] : [{ ...notice, title: current === 2 ? "末页通知" : "第一页通知" }], total: read ? 20 : 21, unreadTotal: read ? 20 : 21, page: current, size: 20 } });
  });
  await page.getByRole("button", { name: "只看未读", exact: true }).click();
  await page.getByRole("button", { name: "下一页", exact: true }).click();
  await expect(page.getByRole("heading", { name: "末页通知", exact: true })).toBeVisible();
  await page.route("**/api/notifications/7/read", async route => { read = true; await route.fulfill({ status: 204 }); });
  await page.getByRole("button", { name: "标为已读", exact: true }).click();
  await expect(page.getByRole("heading", { name: "第一页通知", exact: true })).toBeVisible();
  expect(queries.some(q => q.includes("unreadOnly=true") && q.includes("page=2"))).toBeTruthy();
  await expect(page.getByText("20 条未读", { exact: true })).toBeVisible();
});

test("read-all needs confirmation and failed updates leave messages unread", async ({ page }) => {
  const notice = { id: 7, ticketId: 21, title: "工单更新", body: "处理进展", read: false, createdAt: "2026-11-10T08:00:00" };
  const inbox = { items: [notice], total: 1, unreadTotal: 1, page: 1, size: 20 };
  await mount(page, "OperationsView", { user: agent, view: "notifications" }, { "/notifications/inbox": inbox });
  let writes = 0, fail = true;
  await page.route("**/api/notifications/read-all", async route => {
    writes++;
    expect(route.request().postDataJSON()).toEqual({ confirmed: true });
    if (fail) await route.fulfill({ status: 503, json: { message: "暂时无法标记，请重试" } });
    else { notice.read = true; inbox.unreadTotal = 0; await route.fulfill({ json: { updated: 1 } }); }
  });
  await page.getByRole("button", { name: "全部标为已读", exact: true }).click();
  await page.getByRole("button", { name: "取消", exact: true }).click();
  expect(writes).toBe(0);
  await page.getByRole("button", { name: "全部标为已读", exact: true }).click();
  await page.getByRole("button", { name: "确认标记", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("暂时无法标记");
  await expect(page.getByText("1 条未读", { exact: true })).toBeVisible();
  fail = false;
  await page.getByRole("button", { name: "全部标为已读", exact: true }).click();
  await page.getByRole("button", { name: "确认标记", exact: true }).click();
  await expect(page.getByText("0 条未读", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "全部标为已读", exact: true })).toBeDisabled();
  expect(writes).toBe(2);
});

test("a failed unread filter never presents the previous all-messages list as filtered results", async ({ page }) => {
  await mount(page, "OperationsView", { user: agent, view: "notifications" }, {
    "/notifications/inbox": { items: [{ id: 7, ticketId: 21, title: "历史已读消息", body: "处理进展", read: true, createdAt: "2026-11-10T08:00:00" }], total: 1, unreadTotal: 0, page: 1, size: 20 },
  });
  await expect(page.getByRole("heading", { name: "历史已读消息", exact: true })).toBeVisible();
  await page.route("**/api/notifications/inbox?**", route => route.fulfill({ status: 503, json: { message: "筛选暂不可用" } }));
  await page.getByRole("button", { name: "只看未读", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("筛选暂不可用");
  await expect(page.getByRole("heading", { name: "历史已读消息", exact: true })).toHaveCount(0);
  await expect(page.getByRole("navigation", { name: "通知分页" })).toHaveCount(0);
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
  await mount(page, "OperationsView", { user: agent, view: "notifications" }, { "/notifications/inbox": { items: [], total: 0, unreadTotal: 0, page: 1, size: 20 } });
  await expect(page.getByRole("heading", { name: "暂无消息" })).toBeVisible();
  await page.route("**/api/notifications/inbox?**", route => route.fulfill({ status: 503, json: { message: "通知服务暂不可用" } }));
  await page.getByRole("button", { name: "刷新", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("通知服务暂不可用");
  await expect(page.getByRole("heading", { name: "暂无消息" })).toHaveCount(0);
  await page.route("**/api/notifications/inbox?**", route => route.fulfill({ json: { items: [], total: 0, unreadTotal: 0, page: 1, size: 20 } }));
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
