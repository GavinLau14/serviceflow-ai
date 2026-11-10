import { expect, test, type Page } from "@playwright/test";

// Deterministic UI contract tests only: these routes are fake fixtures.
// They do not test Java, embeddings, retrieval quality, or a live language model.
const ticket = {
  id: 91, reference: "SF-FIXTURE-91", title: "设备无法启动", description: "已核对的故障描述",
  category: "DEVICE", priority: "NORMAL", status: "OPEN", customerId: 1, teamId: 1,
  assigneeId: null, version: 0, createdAt: "2026-11-10T08:00:00", updatedAt: "2026-11-10T08:00:00",
  customerName: "测试客户", assigneeName: null,
};
const retrieval = {
  id: "10", mode: "retrieval_only", intent: "knowledge_lookup", answer: "<img src=x onerror=alert('unsafe')>\n请检查电源连接。",
  confidence: 0.6, handoff: true, citations: [{ id: "demo-power", title: "演示电源检查", source: "javascript:alert('unsafe')", text: "<script>window.fixtureXss = true</script>先断开电源。", score: 0.6 }],
  proposal: null, status: "NO_ACTION", ticketId: null,
};
const draft = {
  ...retrieval, id: "11", mode: "rag", intent: "create_ticket", answer: "已整理故障情况，请核对工单草稿。",
  handoff: false, proposal: { title: ticket.title, description: ticket.description, category: "DEVICE", priority: "NORMAL" }, status: "PENDING",
};
async function setup(page: Page, role = "CUSTOMER") {
  await page.addInitScript(() => sessionStorage.setItem("sf_token", "fake-contract-token"));
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const body = path === "/api/auth/me"
      ? { id: 1, username: "fixture", displayName: "测试用户", role, teamId: null }
      : path === "/api/capabilities"
        ? { milestone: "M2", aiMode: "enabled", ragEnabled: false, message: "已配置；实际模式以运行结果为准" }
        : path === "/api/tickets/91"
          ? { ticket, comments: [], events: [], allowedActions: [] }
          : path === "/api/ai/runs" ? [] : { items: [], total: 0, page: 1, size: 12 };
    await route.fulfill({ json: body });
  });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "退出登录" })).toBeVisible();
}
async function openAssist(page: Page) {
  await page.getByRole("button", { name: "售后 AI 助手", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "售后 AI 助手" })).toBeVisible();
}

test("fake contract: retrieval extracts stay escaped and cannot execute; request errors recover", async ({ page }) => {
  await setup(page);
  let attempts = 0;
  await page.route("**/api/ai/assist", async (route) => {
    expect(route.request().postDataJSON()).toEqual({ message: "无法开机" });
    attempts++;
    await route.fulfill(attempts === 1 ? { status: 503, json: { message: "检索服务暂不可用" } } : { json: retrieval });
  });
  await openAssist(page);
  await page.getByLabel("遇到了什么问题？", { exact: true }).fill("无法开机");
  await page.getByRole("button", { name: "查找帮助", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("检索服务暂不可用");
  await page.getByRole("button", { name: "查找帮助", exact: true }).click();
  const result = page.getByRole("region", { name: "助手结果" });
  await expect(result).toContainText("仅检索原文 · retrieval_only");
  await expect(result).toContainText("未调用语言模型");
  await expect(result).toContainText("建议由人工继续跟进");
  await expect(result.locator("img, script, a")).toHaveCount(0);
  await result.locator("summary").click();
  await expect(result).toContainText("<script>window.fixtureXss = true</script>");
  await expect(page.getByRole("button", { name: "确认创建工单", exact: true })).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole("dialog", { name: "售后 AI 助手" })).toHaveCSS("width", "390px");
  await expect(page.getByLabel("遇到了什么问题？", { exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy();
  await page.screenshot({ path: "test-results/ai-contract-mobile.png", fullPage: true, animations: "disabled" });
});

test("fake contract: persisted drafts require explicit confirmation and emit created ticket", async ({ page }) => {
  await setup(page);
  await page.route("**/api/ai/runs", (route) => route.fulfill({ json: [draft, retrieval] }));
  let executions = 0;
  await page.route("**/api/ai/runs/11/execute", async (route) => {
    executions++;
    expect(route.request().method()).toBe("POST");
    expect(route.request().postDataJSON()).toEqual({ confirmed: true });
    await route.fulfill({ json: ticket });
  });
  await openAssist(page);
  await page.getByRole("button").filter({ hasText: "#11 ·" }).click();
  const execute = page.getByRole("button", { name: "确认创建工单", exact: true });
  await expect(execute).toBeDisabled();
  expect(executions).toBe(0);
  await page.getByText("我已核对草稿，确认创建售后工单", { exact: true }).click();
  await page.getByRole("button").filter({ hasText: "#10 ·" }).click();
  await page.getByRole("button").filter({ hasText: "#11 ·" }).click();
  await expect(execute).toBeDisabled();
  await page.getByText("我已核对草稿，确认创建售后工单", { exact: true }).click();
  await execute.click();
  await expect(page.getByRole("heading", { name: ticket.title, exact: true })).toBeVisible();
  expect(executions).toBe(1);
  await openAssist(page);
  await expect(page.getByRole("region", { name: "助手结果" })).toContainText("已创建工单 #91");
  await expect(execute).toHaveCount(0);
});

test("fake contract: disabled and error modes offer manual recovery", async ({ page }) => {
  await setup(page);
  let attempts = 0;
  await page.route("**/api/ai/assist", (route) => route.fulfill({ json: { ...retrieval, id: 12 + attempts, mode: attempts++ === 0 ? "disabled" : "error", answer: "暂时不能提供帮助", citations: [] } }));
  await openAssist(page);
  await page.getByLabel("遇到了什么问题？", { exact: true }).fill("设备故障");
  await page.getByRole("button", { name: "查找帮助", exact: true }).click();
  await expect(page.getByRole("region", { name: "助手结果" })).toContainText("服务未启用 · disabled");
  await page.getByRole("button", { name: "查找帮助", exact: true }).click();
  await expect(page.getByRole("region", { name: "助手结果" })).toContainText("服务暂不可用 · error");
  await page.getByRole("button", { name: "提交给人工", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "提交售后问题" })).toBeVisible();
});

test("fake contract: expired history cannot execute a proposal", async ({ page }) => {
  await setup(page);
  await page.route("**/api/ai/runs", (route) => route.fulfill({ json: [{ ...draft, status: "EXPIRED" }] }));
  await openAssist(page);
  await page.getByRole("button").filter({ hasText: "#11 ·" }).click();
  await expect(page.getByRole("region", { name: "助手结果" })).toContainText("此草稿已过期");
  await expect(page.getByRole("button", { name: "确认创建工单", exact: true })).toHaveCount(0);
});

test("fake contract: admin alone sees index action and service status", async ({ page }) => {
  await setup(page);
  await expect(page.getByRole("button", { name: "更新演示知识库索引" })).toHaveCount(0);
  await setup(page, "ADMIN");
  await page.route("**/api/ai/index", async (route) => {
    expect(route.request().postDataJSON()).toEqual({});
    await route.fulfill({ json: { status: "indexed", indexed: 6, collection: "fake-ui-contract" } });
  });
  await expect(page.getByRole("button", { name: "售后 AI 助手", exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "更新演示知识库索引" }).click();
  await expect(page.locator(".index-result")).toContainText('"status": "indexed"');
});
