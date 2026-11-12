import { expect, test, type Page, type Route } from "@playwright/test";

const lead = { id: 5, username: "supervisor", displayName: "测试主管", role: "SUPERVISOR", teamId: 1 };
const entry = { id: 8, ticketId: 21, teamId: 1, title: "电源排查", content: "断电后检查连接，并记录复测现象。", status: "DRAFT", authorId: 3, authorName: "测试客服", reviewerId: null, reviewerName: null, reviewNote: null, ticketTitle: "笔记本充电接口接触不良", ticketReference: "SF-FIXTURE", createdAt: "2026-11-12T08:00:00", reviewedAt: null };
type Handler = (route: Route, url: URL) => Promise<void>;
async function mount(page: Page, user: object, handler: Handler) {
  await page.route("**/api/**", r => handler(r, new URL(r.request().url())));
  await page.route("**/knowledge-contract", route => route.fulfill({ contentType: "text/html", body: `<!doctype html><html lang="zh-CN"><head><link rel="stylesheet" href="/node_modules/element-plus/dist/index.css"></head><body><div id="app"></div><script type="module">import {createApp} from '/node_modules/.vite/deps/vue.js'; import C from '/src/components/OperationsView.vue'; createApp(C,{user:${JSON.stringify(user)},view:'knowledge',onOpenTicket:id=>document.body.dataset.ticket=String(id)}).mount('#app');</script></body></html>` }));
  await page.goto("/knowledge-contract");
}

test("rejection requires a reason, refreshes the pending queue and retains an escaped review record", async ({ page }) => {
  let rejected = false, writes = 0;
  const note = "<img src=x onerror=alert(1)> 请补充实际复测结果。";
  await mount(page, lead, async (route, url) => {
    if (url.pathname.endsWith("/review")) {
      writes++;
      expect(route.request().postDataJSON()).toEqual({ approved: false, confirmed: true, note });
      rejected = true;
      return route.fulfill({ json: { ...entry, status: "REJECTED", reviewNote: note } });
    }
    expect(url.pathname).toBe("/api/knowledge/inbox");
    const rows = !rejected ? [entry] : url.searchParams.get("status") === "REJECTED"
      ? [{ ...entry, status: "REJECTED", reviewNote: note, reviewerName: "测试主管", reviewerId: 5, reviewedAt: "2026-11-12T09:00:00" }] : [];
    return route.fulfill({ json: { items: rows, total: rows.length, pendingTotal: rejected ? 0 : 1, page: 1, size: 20 } });
  });
  await expect(page.getByRole("heading", { name: "电源排查", exact: true })).toBeVisible();
  await expect(page.getByText(/提交人：测试客服/)).toBeVisible();
  const reject = page.getByRole("button", { name: "退回草稿", exact: true });
  await expect(reject).toBeDisabled();
  await page.getByText("我已审核内容，确认提交审核结果", { exact: true }).click();
  await expect(reject).toBeDisabled();
  await page.getByLabel("审核意见（退回必填）", { exact: true }).fill(note);
  expect(writes).toBe(0);
  await reject.click();
  await expect(page.getByRole("heading", { name: "当前筛选暂无记录", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "已退回", exact: true }).click();
  await expect(page.getByText(note, { exact: true })).toBeVisible();
  await expect(page.locator("img")).toHaveCount(0);
  await page.getByRole("button", { name: "来源工单 #21", exact: true }).click();
  expect(await page.locator("body").getAttribute("data-ticket")).toBe("21");
  expect(writes).toBe(1);
});

test("knowledge pages recover after the last item is approved and search preserves scoped pending counts", async ({ page }) => {
  const queries: URLSearchParams[] = [];
  let reviewed = false;
  await mount(page, lead, async (route, url) => {
    if (url.pathname.endsWith("/review")) { reviewed = true; return route.fulfill({ json: { ...entry, status: "PUBLISHED" } }); }
    queries.push(url.searchParams);
    const p = Number(url.searchParams.get("page")), q = url.searchParams.get("q");
    const rows = q ? [{ ...entry, title: "百分号%_知识" }] : p === 2 ? reviewed ? [] : [{ ...entry, id: 28, title: "末页知识" }] : Array.from({ length: 20 }, (_, i) => ({ ...entry, id: i + 8, title: "知识" + i }));
    return route.fulfill({ json: { items: rows, total: q ? 1 : reviewed ? 20 : 21, pendingTotal: reviewed ? 20 : 21, page: p, size: 20 } });
  });
  await page.getByRole("button", { name: "下一页", exact: true }).click();
  await expect(page.getByRole("heading", { name: "末页知识", exact: true })).toBeVisible();
  await page.getByText("我已审核内容，确认提交审核结果", { exact: true }).click();
  await page.getByRole("button", { name: "审核通过", exact: true }).click();
  await expect(page.getByRole("heading", { name: "知识0", exact: true })).toBeVisible();
  await page.getByLabel("检索知识", { exact: true }).fill("  %_  ");
  await page.getByRole("button", { name: "检索", exact: true }).click();
  await expect(page.getByRole("heading", { name: "百分号%_知识", exact: true })).toBeVisible();
  await expect(page.getByText("可见范围内 20 条待审核", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "全部", exact: true }).click();
  await expect.poll(() => queries.at(-1)?.get("status")).toBe("");
  expect(queries.at(-1)?.get("q")).toBe("%_");
  expect(queries.at(-1)?.get("page")).toBe("1");
});

test("failed review retains the note and a failed new filter clears the previous content", async ({ page }) => {
  let failQuery = false;
  await mount(page, lead, async (route, url) => {
    if (url.pathname.endsWith("/review")) return route.fulfill({ status: 503, json: { message: "审核暂不可用" } });
    if (failQuery) return route.fulfill({ status: 503, json: { message: "知识查询暂不可用" } });
    return route.fulfill({ json: { items: [entry], total: 1, pendingTotal: 1, page: 1, size: 20 } });
  });
  await page.getByLabel("审核意见（退回必填）", { exact: true }).fill("请补充检测证据");
  await page.getByText("我已审核内容，确认提交审核结果", { exact: true }).click();
  await page.getByRole("button", { name: "退回草稿", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("审核暂不可用");
  await expect(page.getByLabel("审核意见（退回必填）", { exact: true })).toHaveValue("请补充检测证据");
  failQuery = true;
  await page.getByRole("button", { name: "已通过", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("知识查询暂不可用");
  await expect(page.getByRole("heading", { name: "电源排查", exact: true })).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "当前筛选暂无记录", exact: true })).toHaveCount(0);
});

test("phone staff can read and return to the queue while customers never query knowledge", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  let calls = 0;
  await mount(page, { ...lead, id: 3, role: "AGENT" }, async (route, url) => {
    calls++; expect(url.searchParams.get("status")).toBe("");
    return route.fulfill({ json: { items: [entry], total: 1, pendingTotal: 1, page: 1, size: 20 } });
  });
  await page.getByRole("button", { name: /电源排查/ }).click();
  await expect(page.getByRole("heading", { name: "电源排查", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "审核通过", exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "返回知识列表", exact: true }).click();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  const before = calls;
  await mount(page, { ...lead, id: 1, role: "CUSTOMER" }, async () => { calls++; throw new Error("Customer must not query knowledge"); });
  await expect(page.getByText("知识记录仅向服务团队开放", { exact: true })).toBeVisible();
  expect(calls).toBe(before);
});
