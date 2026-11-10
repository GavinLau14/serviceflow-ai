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
const assistOpen = ref(false), capabilities = ref<Capabilities | null>(null),
  capabilityError = ref(""), indexing = ref(false), indexError = ref(""), indexResult = ref("");
const view = ref<"tickets"|"notifications"|"knowledge">("tickets");
const viewTitle = computed(()=>view.value==="notifications"?"通知中心":view.value==="knowledge"?"服务知识库":heading.value);
function openTicket(id:number){view.value="tickets";selected.value=id;}
let session = 0;
const aiLabel = computed(() => capabilityError.value ? "助手状态暂不可用" : !capabilities.value ? "正在获取助手状态" : capabilities.value.aiMode === "enabled" ? "AI 助手已配置" : "AI 服务未启用");
async function loadCapabilities() {
  const n = session;
  capabilityError.value = "";
  try {
    const result = await api<Capabilities>("/capabilities");
    if (n === session) capabilities.value = result;
  } catch (e) {
    if (n === session) capabilityError.value = e instanceof Error ? e.message : "状态获取失败";
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
    if (n === session) indexError.value = e instanceof Error ? e.message : "索引请求失败，请重试";
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
  view.value="tickets";
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
  page.value = 1;
  load();
}
function created(t: Ticket) {
  view.value="tickets";
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
<div v-if="initializing" class="initializing" role="status">正在连接 ServiceFlow…</div>
<LoginPanel v-else-if="!user" @login="loggedIn"/>
<div v-else class="app-shell">
  <aside class="sidebar">
    <a class="wordmark" href="/" aria-label="ServiceFlow 首页"><span class="brand-mark">S</span>ServiceFlow <small>AI</small></a>
    <div class="workspace-switch"><span class="workspace-dot"></span><div><strong>售后协作空间</strong><small>Service workspace</small></div></div>
    <div class="workspace-label">工作空间</div>
    <nav class="main-nav" aria-label="工作空间导航">
      <button class="nav-item" :class="{active:view==='tickets'}" @click="view='tickets'"><el-icon><Files/></el-icon><span>{{heading}}</span></button>
      <button class="nav-item" :class="{active:view==='notifications'}" @click="view='notifications'"><el-icon><Bell/></el-icon><span>通知中心</span></button>
      <button v-if="user.role!=='CUSTOMER'" class="nav-item" :class="{active:view==='knowledge'}" @click="view='knowledge'"><el-icon><Reading/></el-icon><span>服务知识库</span></button>
    </nav>
    <div class="sidebar-bottom"><div class="ai-indicator"><el-icon><Connection/></el-icon><div><strong>{{aiLabel}}</strong><p>{{capabilityError || capabilities?.message || '正在连接服务'}}</p></div></div><div class="profile"><span class="avatar">{{user.displayName[0]}}</span><div><strong>{{user.displayName}}</strong><small>{{roleNames[user.role]}} · {{user.teamId===2?'家电服务':user.teamId===1?'数码设备':'个人空间'}}</small></div></div></div>
  </aside>
  <div class="main-shell">
    <header class="topbar"><div class="breadcrumb">服务中心 <el-icon><ArrowRight/></el-icon><strong>{{viewTitle}}</strong></div><div class="topbar-tools"><span class="environment-badge"><span></span>本地演示 · {{capabilities?.milestone || '…'}}</span><el-button :icon="SwitchButton" text aria-label="退出登录" @click="logout">退出登录</el-button></div></header>
    <main>
      <div class="page-heading"><div><h1>{{viewTitle}}</h1><p>{{view==='tickets'?scope:view==='notifications'?'与你相关的工单动态和服务提醒':'从已解决的问题中沉淀经验，经审核后供团队查阅'}}</p></div>
        <div v-if="user.role==='CUSTOMER' && view==='tickets'" class="page-actions"><el-button :icon="Connection" @click="assistOpen=true">售后 AI 助手</el-button><el-button type="primary" :icon="Plus" @click="createOpen=true">提交售后问题</el-button></div>
        <el-button v-if="user.role==='ADMIN'" :loading="indexing" :icon="Refresh" @click="indexKnowledge">更新演示知识库索引</el-button>
      </div>
      <div v-if="user.role==='ADMIN'&&(indexError||indexResult)" class="index-result" aria-live="polite"><el-alert v-if="indexError" :title="indexError" type="error" :closable="false" role="alert"/><template v-else><p class="muted">知识库索引服务返回：</p><pre>{{indexResult}}</pre></template></div>
      <OperationsView v-if="view!=='tickets'" :user="user" :view="view" @open-ticket="openTicket"/>
      <div v-else class="workspace" :class="{'has-selection':selected!==null}">
        <section class="queue-panel" aria-label="工单队列">
          <nav class="status-tabs" aria-label="工单状态"><button :class="{chosen:!status}" @click="status='';filter()">全部工单</button><button :class="{chosen:status==='OPEN'}" @click="status='OPEN';filter()">待分派</button><button :class="{chosen:status==='IN_PROGRESS'}" @click="status='IN_PROGRESS';filter()">处理中</button><button :class="{chosen:status==='WAITING_CUSTOMER'}" @click="status='WAITING_CUSTOMER';filter()">待确认</button><button :class="{chosen:status==='CLOSED'}" @click="status='CLOSED';filter()">已关闭</button></nav>
          <div class="queue-toolbar"><form role="search" @submit.prevent="filter"><el-input v-model="q" aria-label="搜索工单" placeholder="搜索标题或工单编号…" :prefix-icon="Search" clearable maxlength="100" @clear="filter"/><el-button native-type="submit">搜索</el-button></form><div class="queue-filters"><el-select v-model="status" aria-label="筛选状态" placeholder="全部状态" clearable @change="filter"><el-option v-for="(label,key) in statusNames" :key="key" :label="label" :value="key"/></el-select><el-button :icon="Refresh" aria-label="刷新工单" :loading="busy" @click="load"/></div></div>
          <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert"/>
          <div class="queue-summary"><span><strong>{{total}}</strong> 条{{status?'匹配':'可访问'}}工单</span><span>按最近提交排序</span></div>
          <el-skeleton v-if="busy&&!items.length" :rows="6" animated class="queue-skeleton"/>
          <div v-else-if="!items.length" class="empty-state"><div class="empty-icon"><el-icon><Files/></el-icon></div><h2>{{q||status?'没有匹配的工单':'队列已就绪'}}</h2><p>{{q||status?'调整关键词或状态，查找其他工单。':user.role==='CUSTOMER'?'遇到了问题？提交售后需求，我们会在这里跟进。':user.role==='AGENT'?'新的服务任务分派后，会出现在你的队列中。':'客户提交的问题会出现在这里，你可以安排团队继续跟进。'}}</p><el-button v-if="q||status" @click="q='';status='';filter()">清除筛选</el-button><el-button v-else-if="user.role==='CUSTOMER'" type="primary" @click="createOpen=true">提交售后问题</el-button></div>
          <div v-else class="ticket-list" :aria-busy="busy"><table class="queue-table"><thead><tr><th class="subject-col">工单 / 问题</th><th class="state-col">状态</th><th class="priority-col">优先级</th><th class="customer-col">提交客户</th><th class="assignee-col">负责客服</th><th class="date-col">更新时间</th></tr></thead><tbody><tr v-for="t in items" :key="t.id" class="ticket-row" :class="{selected:selected===t.id}"><td class="subject-col"><span class="reference">{{t.reference}}</span><button class="ticket-title" @click="selected=t.id">{{t.title}}</button><p class="ticket-preview">{{t.description}}</p><div class="compact-meta"><el-tag :class="'state-'+t.status" effect="plain" size="small">{{statusNames[t.status]}}</el-tag><span>{{t.assigneeName||'未分派'}}</span></div></td><td class="state-col"><el-tag :class="'state-'+t.status" effect="plain" size="small">{{statusNames[t.status]}}</el-tag></td><td class="priority-col"><span class="priority" :class="'priority-'+t.priority"><span class="priority-dot"></span>{{priorityNames[t.priority]}}</span></td><td class="customer-col"><span class="person-name"><span class="mini-avatar">{{t.customerName[0]}}</span>{{t.customerName.split(' · ')[0]}}</span></td><td class="assignee-col"><span class="assignee-name">{{t.assigneeName?.split(' · ')[0]||'—'}}</span></td><td class="date-col"><time>{{time(t.updatedAt)}}</time></td></tr></tbody></table></div>
          <div class="pagination"><span>每页 12 条</span><el-pagination v-model:current-page="page" :page-size="12" :total="total" layout="prev, pager, next" small @current-change="load"/></div>
        </section>
        <div v-if="selected!==null" class="detail-wrap"><div class="detail-navigation"><span>工单详情</span><el-button :icon="ArrowLeft" text @click="selected=null">返回队列</el-button></div><TicketDetail :id="selected" :user="user" @updated="load"/></div>
      </div>
    </main>
    <footer class="app-footer"><span>ServiceFlow AI</span><span>售后协同 · 每一步处理都有据可查</span></footer>
  </div>
  <CreateTicket v-model="createOpen" @created="created"/><AiAssist v-if="user.role==='CUSTOMER'" v-model="assistOpen" @created="created" @manual="assistOpen=false;createOpen=true"/>
</div>
</template>
