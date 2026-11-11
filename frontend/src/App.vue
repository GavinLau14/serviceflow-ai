<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from "vue";
import {
  ElButton,
  ElInput,
  ElSelect,
  ElOption,
  ElPagination,
  ElTag,
  ElAlert,
  ElSkeleton,
  ElMessage,
  ElIcon,
} from "element-plus";
import {
  Search,
  Plus,
  Refresh,
  SwitchButton,
  Files,
  ArrowLeft,
  Connection,
  Bell,
  Reading,
  ArrowRight,
  ChatDotRound,
  Monitor,
  Tools,
} from "@element-plus/icons-vue";
import {
  api,
  type User,
  type Ticket,
  type TicketPage,
  type Status,
  type Capabilities,
  statusNames,
  priorityNames,
  roleNames,
  time,
} from "./api";
import LoginPanel from "./components/LoginPanel.vue";
import CreateTicket from "./components/CreateTicket.vue";
import TicketDetail from "./components/TicketDetail.vue";
import AiAssist from "./components/AiAssist.vue";
import OperationsView from "./components/OperationsView.vue";
const user = ref<User | null>(null),
  initializing = ref(true),
  items = ref<Ticket[]>([]),
  total = ref(0),
  page = ref(1),
  status = ref<Status | "">(""),
  q = ref(""),
  selected = ref<number | null>(null),
  createOpen = ref(false),
  busy = ref(false),
  error = ref("");
const assistOpen = ref(false),
  capabilities = ref<Capabilities | null>(null),
  capabilityError = ref(""),
  indexing = ref(false),
  indexError = ref(""),
  indexResult = ref("");
const view = ref<"tickets" | "notifications" | "knowledge">("tickets");
const viewTitle = computed(() =>
  view.value === "notifications"
    ? "通知中心"
    : view.value === "knowledge"
      ? "服务知识库"
      : heading.value,
);
function selectQueue(state: Status | "") {
  view.value = "tickets";
  status.value = state;
  filter();
}
function openTicket(id: number) {
  view.value = "tickets";
  selected.value = id;
}
let session = 0;
const aiLabel = computed(() =>
  capabilityError.value
    ? "助手状态暂不可用"
    : !capabilities.value
      ? "正在获取助手状态"
      : capabilities.value.aiMode === "enabled"
        ? "AI 助手已配置"
        : "AI 服务未启用",
);
async function loadCapabilities() {
  const n = session;
  capabilityError.value = "";
  try {
    const result = await api<Capabilities>("/capabilities");
    if (n === session) capabilities.value = result;
  } catch (e) {
    if (n === session)
      capabilityError.value = e instanceof Error ? e.message : "状态获取失败";
  }
}
async function indexKnowledge() {
  if (indexing.value || user.value?.role !== "ADMIN") return;
  const n = session;
  indexing.value = true;
  indexError.value = "";
  indexResult.value = "";
  try {
    const result = await api<Record<string, unknown>>("/ai/index", {});
    if (n === session) indexResult.value = JSON.stringify(result, null, 2);
  } catch (e) {
    if (n === session)
      indexError.value =
        e instanceof Error ? e.message : "索引请求失败，请重试";
  } finally {
    if (n === session) indexing.value = false;
  }
}
const heading = computed(() =>
  user.value?.role === "CUSTOMER"
    ? "我的售后问题"
    : user.value?.role === "AGENT"
      ? "我的处理队列"
      : "团队工单",
);
const scope = computed(() =>
  user.value?.role === "CUSTOMER"
    ? "跟进你提交的问题与处理进展"
    : user.value?.role === "AGENT"
      ? "仅展示分派给你的工单"
      : user.value?.role === "SUPERVISOR"
        ? "查看与分派本团队的售后问题"
        : "全局工单管理与协作",
);
let request = 0;
async function load() {
  const n = ++request;
  busy.value = true;
  error.value = "";
  try {
    const r = await api<TicketPage>(
      "/tickets?" +
        new URLSearchParams({
          page: String(page.value),
          size: "12",
          ...(status.value ? { status: status.value } : {}),
          ...(q.value.trim() ? { q: q.value.trim() } : {}),
        }),
    );
    if (n === request) {
      items.value = r.items;
      total.value = r.total;
      if (selected.value === null && r.items.length)
        selected.value = r.items[0].id;
    }
  } catch (e) {
    if (n === request)
      error.value = e instanceof Error ? e.message : "连接失败，请检查服务";
  } finally {
    if (n === request) busy.value = false;
  }
}
function resetSession() {
  session++;
  view.value = "tickets";
  sessionStorage.removeItem("sf_token");
  user.value = null;
  selected.value = null;
  items.value = [];
  total.value = 0;
  request++;
  busy.value = false;
  assistOpen.value = false;
  createOpen.value = false;
  capabilities.value = null;
  capabilityError.value = "";
  indexResult.value = "";
  indexError.value = "";
  indexing.value = false;
}
async function loggedIn(u: User) {
  session++;
  user.value = u;
  q.value = "";
  status.value = "";
  page.value = 1;
  selected.value = null;
  await Promise.all([load(), loadCapabilities()]);
}
async function logout() {
  try {
    await api("/auth/logout", {});
    resetSession();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : "退出失败，请重试");
  }
}
function filter() {
  selected.value = null;
  page.value = 1;
  load();
}
function created(t: Ticket) {
  view.value = "tickets";
  selected.value = t.id;
  page.value = 1;
  status.value = "";
  q.value = "";
  load();
  ElMessage.success("售后问题已提交");
}
onMounted(async () => {
  window.addEventListener("sf-session-expired", resetSession);
  try {
    if (sessionStorage.getItem("sf_token")) {
      user.value = await api<User>("/auth/me");
      await Promise.all([load(), loadCapabilities()]);
    }
  } catch (e) {
    resetSession();
  } finally {
    initializing.value = false;
  }
});
onUnmounted(() =>
  window.removeEventListener("sf-session-expired", resetSession),
);
</script>
<template>
  <div v-if="initializing" class="initializing" role="status">
    正在打开服务收件箱…
  </div>
  <LoginPanel v-else-if="!user" @login="loggedIn" />
  <div v-else class="service-shell">
    <aside class="tool-rail" aria-label="应用导航">
      <a href="/" class="flow-logo" aria-label="ServiceFlow 首页"
        ><span></span><span></span
      ></a>
      <div class="rail-tools">
        <button
          :class="{ active: view === 'tickets' }"
          aria-label="服务收件箱"
          @click="view = 'tickets'"
        >
          <el-icon><ChatDotRound /></el-icon></button
        ><button
          :class="{ active: view === 'notifications' }"
          aria-label="通知中心"
          @click="view = 'notifications'"
        >
          <el-icon><Bell /></el-icon></button
        ><button
          v-if="user.role !== 'CUSTOMER'"
          :class="{ active: view === 'knowledge' }"
          aria-label="服务知识库"
          @click="view = 'knowledge'"
        >
          <el-icon><Reading /></el-icon>
        </button>
      </div>
      <button class="rail-logout" aria-label="退出登录" @click="logout">
        <el-icon><SwitchButton /></el-icon></button
      ><span class="rail-avatar" :title="user.displayName">{{
        user.displayName[0]
      }}</span>
    </aside>
    <aside class="queue-navigation">
      <header class="workspace-identity">
        <strong>ServiceFlow<span>服务台</span></strong
        ><span class="workspace-version">售后协作空间</span>
      </header>
      <button
        class="new-case"
        v-if="user.role === 'CUSTOMER'"
        @click="createOpen = true"
      >
        <el-icon><Plus /></el-icon>提交售后问题
      </button>
      <div class="nav-caption">{{ roleNames[user.role] }}工作空间</div>
      <nav class="queue-links" aria-label="工作队列">
        <button
          :class="{ chosen: view === 'tickets' && !status }"
          @click="selectQueue('')"
        >
          <el-icon><Files /></el-icon>{{ heading }}</button
        ><button
          :class="{ chosen: view === 'tickets' && status === 'OPEN' }"
          @click="selectQueue('OPEN')"
        >
          <span class="queue-dot open"></span>待分派</button
        ><button
          :class="{ chosen: view === 'tickets' && status === 'IN_PROGRESS' }"
          @click="selectQueue('IN_PROGRESS')"
        >
          <span class="queue-dot working"></span>处理中</button
        ><button
          :class="{
            chosen: view === 'tickets' && status === 'WAITING_CUSTOMER',
          }"
          @click="selectQueue('WAITING_CUSTOMER')"
        >
          <span class="queue-dot waiting"></span>等待客户</button
        ><button
          :class="{ chosen: view === 'tickets' && status === 'CLOSED' }"
          @click="selectQueue('CLOSED')"
        >
          <span class="queue-dot closed"></span>已关闭
        </button>
      </nav>
      <div class="nav-caption resource-caption">服务资源</div>
      <nav class="queue-links">
        <button
          :class="{ chosen: view === 'notifications' }"
          @click="view = 'notifications'"
        >
          <el-icon><Bell /></el-icon>通知中心</button
        ><button
          v-if="user.role !== 'CUSTOMER'"
          :class="{ chosen: view === 'knowledge' }"
          @click="view = 'knowledge'"
        >
          <el-icon><Reading /></el-icon>服务知识库</button
        ><button v-if="user.role === 'CUSTOMER'" @click="assistOpen = true">
          <el-icon><Connection /></el-icon>自助排查
        </button>
      </nav>
      <div class="navigation-bottom">
        <span class="role-initial">{{ user.displayName[0] }}</span>
        <div>
          <strong>{{ user.displayName.split(" · ")[0] }}</strong
          ><small>{{
            user.teamId === 2
              ? "家电服务组"
              : user.teamId === 1
                ? "数码服务组"
                : roleNames[user.role]
          }}</small>
        </div>
        <el-button
          text
          :icon="SwitchButton"
          aria-label="退出登录"
          @click="logout"
        />
      </div>
    </aside>
    <div class="service-main">
      <header class="service-topbar">
        <div>
          <span class="topbar-section">服务台</span
          ><el-icon><ArrowRight /></el-icon>
          <h1>{{ view === "tickets" ? "服务收件箱" : viewTitle }}</h1>
        </div>
        <div class="topbar-actions">
          <span class="local-marker">本地演示</span
          ><el-button
            v-if="user.role === 'ADMIN'"
            :loading="indexing"
            size="small"
            @click="indexKnowledge"
            >更新演示知识库索引</el-button
          ><el-button
            v-if="user.role === 'CUSTOMER'"
            size="small"
            :icon="Connection"
            @click="assistOpen = true"
            >售后 AI 助手</el-button
          ><el-button
            v-if="user.role === 'CUSTOMER'"
            type="primary"
            size="small"
            :icon="Plus"
            @click="createOpen = true"
            >提交售后问题</el-button
          >
        </div>
      </header>
      <div v-if="indexError || indexResult" class="index-result">
        <el-alert
          v-if="indexError"
          :title="indexError"
          type="error"
          :closable="false"
        />
        <div v-else>
          <p>演示知识索引已更新。实际检索状态以咨询结果为准。</p>
          <details>
            <summary>查看索引结果</summary>
            <pre>{{ indexResult }}</pre>
          </details>
        </div>
      </div>
      <OperationsView
        v-if="view !== 'tickets'"
        :user="user"
        :view="view"
        @open-ticket="openTicket"
      />
      <main
        v-else
        class="inbox-workspace"
        :class="{ 'has-selection': selected !== null }"
      >
        <section class="inbox-column" aria-label="工单队列">
          <header class="inbox-heading">
            <div>
              <h2>{{ status ? statusNames[status] : heading }}</h2>
              <span class="queue-total">{{ total }}</span>
            </div>
            <el-button
              text
              :icon="Refresh"
              aria-label="刷新工单"
              :loading="busy"
              @click="load"
            />
          </header>
          <form class="inbox-search" role="search" @submit.prevent="filter">
            <el-input
              v-model="q"
              aria-label="搜索工单"
              :prefix-icon="Search"
              placeholder="搜索问题或工单编号"
              clearable
              maxlength="100"
              @clear="filter"
            /><button type="submit" class="search-submit">搜索</button>
          </form>
          <div class="list-utilities">
            <el-select
              v-model="status"
              aria-label="筛选状态"
              placeholder="全部状态"
              clearable
              @change="filter"
              ><el-option
                v-for="(label, key) in statusNames"
                :key="key"
                :label="label"
                :value="key" /></el-select
            ><span>最新提交优先</span>
          </div>
          <el-alert
            v-if="error"
            :title="error"
            type="error"
            :closable="false"
            role="alert"
          /><el-skeleton
            v-if="busy && !items.length"
            :rows="5"
            animated
            class="queue-skeleton"
          />
          <div v-else-if="!items.length" class="empty-list">
            <el-icon><Files /></el-icon>
            <h3>{{ q || status ? "没有匹配的问题" : "暂时没有工单" }}</h3>
            <p>
              {{
                q || status
                  ? "试试其他关键词或状态。"
                  : "新的售后问题会显示在这里。"
              }}
            </p>
            <el-button
              v-if="q || status"
              text
              @click="
                q = '';
                status = '';
                filter();
              "
              >清除筛选</el-button
            ><el-button
              v-else-if="user.role === 'CUSTOMER'"
              type="primary"
              @click="createOpen = true"
              >提交售后问题</el-button
            >
          </div>
          <div v-else class="inbox-list" :aria-busy="busy">
            <article
              v-for="t in items"
              :key="t.id"
              class="inbox-ticket"
              :class="{ selected: selected === t.id }"
            >
              <div class="ticket-person">
                <span
                  class="person-avatar"
                  :class="{ appliance: t.category === 'APPLIANCE' }"
                  >{{ t.customerName[0] }}</span
                ><strong>{{ t.customerName.split(" · ")[0] }}</strong
                ><time>{{ time(t.createdAt) }}</time>
              </div>
              <button
                class="ticket-title"
                :aria-pressed="selected === t.id"
                @click="selected = t.id"
              >
                {{ t.title }}
              </button>
              <p class="ticket-excerpt">{{ t.description }}</p>
              <div class="ticket-meta">
                <span class="ticket-state" :class="'state-' + t.status"
                  ><span></span>{{ statusNames[t.status] }}</span
                ><span class="ticket-assignee">{{
                  t.assigneeName?.split(" · ")[0] || "未分派"
                }}</span
                ><span
                  v-if="t.priority === 'HIGH' || t.priority === 'URGENT'"
                  class="priority-label"
                  >{{ priorityNames[t.priority] }}优先级</span
                >
              </div>
            </article>
          </div>
          <footer class="inbox-pagination">
            <span>12 条 / 页</span
            ><el-pagination
              v-model:current-page="page"
              :page-size="12"
              :total="total"
              layout="prev, pager, next"
              small
              @current-change="load"
            />
          </footer>
        </section>
        <section v-if="selected !== null" class="case-column">
          <div class="mobile-case-back">
            <el-button text :icon="ArrowLeft" @click="selected = null"
              >返回队列</el-button
            >
          </div>
          <TicketDetail :id="selected" :user="user" @updated="load" />
        </section>
        <section v-else class="no-case">
          <div class="no-case-symbol">
            <el-icon><ChatDotRound /></el-icon>
          </div>
          <h2>选择一条售后问题</h2>
          <p>查看客户描述、处理记录和下一步操作。</p>
          <small>{{ scope }}</small>
        </section>
      </main>
    </div>
    <CreateTicket v-model="createOpen" @created="created" /><AiAssist
      v-if="user.role === 'CUSTOMER'"
      v-model="assistOpen"
      @created="created"
      @manual="
        assistOpen = false;
        createOpen = true;
      "
    />
  </div>
</template>
