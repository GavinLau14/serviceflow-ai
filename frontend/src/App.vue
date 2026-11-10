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
} from "@element-plus/icons-vue";
import {
  api,
  type User,
  type Ticket,
  type TicketPage,
  type Status,
  statusNames,
  priorityNames,
  roleNames,
  time,
} from "./api";
import LoginPanel from "./components/LoginPanel.vue";
import CreateTicket from "./components/CreateTicket.vue";
import TicketDetail from "./components/TicketDetail.vue";
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
    }
  } catch (e) {
    if (n === request)
      error.value = e instanceof Error ? e.message : "连接失败，请检查服务";
  } finally {
    if (n === request) busy.value = false;
  }
}
function resetSession() {
  sessionStorage.removeItem("sf_token");
  user.value = null;
  selected.value = null;
  items.value = [];
  total.value = 0;
  request++;
  busy.value = false;
}
async function loggedIn(u: User) {
  user.value = u;
  q.value = "";
  status.value = "";
  page.value = 1;
  selected.value = null;
  await load();
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
  page.value = 1;
  load();
}
function created(t: Ticket) {
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
      await load();
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
    正在连接 ServiceFlow…
  </div>
  <LoginPanel v-else-if="!user" @login="loggedIn" />
  <div v-else class="app-shell">
    <aside class="sidebar">
      <a class="wordmark" href="/" aria-label="ServiceFlow 首页"
        ><span class="brand-mark">S</span>ServiceFlow <small>AI</small></a
      >
      <div class="workspace-label">售后协作空间</div>
      <div class="nav-item active">
        <el-icon><Files /></el-icon><span>{{ heading }}</span>
      </div>
      <div class="sidebar-bottom">
        <div class="ai-indicator">
          <el-icon><Connection /></el-icon>
          <div>
            <strong>AI 助手尚未接入</strong>
            <p>当前工单由人工处理</p>
          </div>
        </div>
        <div class="profile">
          <span class="avatar">{{ user.displayName[0] }}</span>
          <div>
            <strong>{{ user.displayName }}</strong
            ><small>{{ roleNames[user.role] }}</small>
          </div>
        </div>
      </div>
    </aside>
    <div class="main-shell">
      <header class="topbar">
        <span>服务中心 <span class="slash">/</span> 工单工作台</span>
        <div>
          <span class="environment-badge">本地演示 · M1</span
          ><el-button
            :icon="SwitchButton"
            text
            aria-label="退出登录"
            @click="logout"
            >退出登录</el-button
          >
        </div>
      </header>
      <main>
        <div class="page-heading">
          <div>
            <h1>{{ heading }}</h1>
            <p>{{ scope }}</p>
          </div>
          <el-button
            v-if="user.role === 'CUSTOMER'"
            type="primary"
            :icon="Plus"
            @click="createOpen = true"
            >提交售后问题</el-button
          >
        </div>
        <div class="workspace" :class="{ 'has-selection': selected !== null }">
          <section class="queue-panel">
            <div class="queue-toolbar">
              <form role="search" @submit.prevent="filter">
                <el-input
                  v-model="q"
                  aria-label="搜索工单"
                  placeholder="搜索标题或工单编号"
                  :prefix-icon="Search"
                  clearable
                  maxlength="100"
                  @clear="filter"
                /><el-button native-type="submit">搜索</el-button>
              </form>
              <div class="queue-filters">
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
                ><el-button
                  :icon="Refresh"
                  aria-label="刷新工单"
                  :loading="busy"
                  @click="load"
                />
              </div>
            </div>
            <el-alert
              v-if="error"
              :title="error"
              type="error"
              :closable="false"
              role="alert"
            />
            <div class="queue-summary">
              <span>工单队列</span><span>共 {{ total }} 条</span>
            </div>
            <el-skeleton
              v-if="busy && !items.length"
              :rows="6"
              animated
              class="queue-skeleton"
            />
            <div v-else-if="!items.length" class="empty-state">
              <el-icon><Files /></el-icon>
              <h2>{{ q || status ? "未找到匹配的工单" : "队列已就绪" }}</h2>
              <p>
                {{
                  q || status
                    ? "试试其他关键词，或切换工单状态。"
                    : user.role === "CUSTOMER"
                      ? "提交第一个售后问题，后续进展会显示在这里。"
                      : user.role === "AGENT"
                        ? "分派给你的工单会显示在这里。"
                        : "客户提交的售后问题将出现在团队队列中。"
                }}
              </p>
              <el-button
                v-if="q || status"
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
            <div v-else class="ticket-list" :aria-busy="busy">
              <article
                v-for="t in items"
                :key="t.id"
                class="ticket-row"
                :class="{ selected: selected === t.id }"
              >
                <div class="ticket-row-meta">
                  <span class="reference">{{ t.reference }}</span
                  ><el-tag
                    :class="'state-' + t.status"
                    effect="plain"
                    size="small"
                    >{{ statusNames[t.status] }}</el-tag
                  >
                </div>
                <button class="ticket-title" @click="selected = t.id">
                  {{ t.title }}
                </button>
                <p class="ticket-preview">{{ t.description }}</p>
                <div class="ticket-row-footer">
                  <span class="priority" :class="'priority-' + t.priority"
                    ><span class="priority-dot"></span
                    >{{ priorityNames[t.priority] }}</span
                  ><span>{{ t.assigneeName || "未分派" }}</span
                  ><time>{{ time(t.updatedAt) }}</time>
                </div>
              </article>
            </div>
            <div class="pagination">
              <el-pagination
                v-model:current-page="page"
                :page-size="12"
                :total="total"
                layout="prev, pager, next"
                small
                @current-change="load"
              />
            </div>
          </section>
          <div v-if="selected !== null" class="detail-wrap">
            <el-button
              class="back-button"
              :icon="ArrowLeft"
              text
              @click="selected = null"
              >返回队列</el-button
            ><TicketDetail :id="selected" @updated="load" />
          </div>
          <section v-else class="queue-companion">
            <span class="companion-symbol">↗</span>
            <h2>把问题，推进一步。</h2>
            <p>从队列选择一张工单，查看完整上下文、处理轨迹与下一步操作。</p>
            <div class="flow-preview">
              <span>受理</span><span>处理</span><span>确认</span>
            </div>
            <p class="companion-footnote">每次流转，都保留清晰记录。</p>
          </section>
        </div>
      </main>
      <footer class="app-footer">
        ServiceFlow AI <span>让每次售后协作都有迹可循</span>
      </footer>
    </div>
    <CreateTicket v-model="createOpen" @created="created" />
  </div>
</template>
