<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { ElAlert, ElButton, ElCheckbox, ElInput, ElPagination, ElSkeleton, ElTag } from "element-plus";
import { api, time, type User } from "../api";

type State = "DRAFT" | "PUBLISHED" | "REJECTED";
interface Knowledge {
  id: number; ticketId: number; title: string; content: string; status: State;
  authorId: number; authorName?: string | null; reviewerId?: number | null; reviewerName?: string | null;
  reviewNote?: string | null; ticketReference?: string | null; ticketTitle?: string | null;
  createdAt: string; reviewedAt?: string | null;
}
interface Result { items: Knowledge[]; total: number; pendingTotal: number; page: number; size: number }
const props = defineProps<{ user: User }>();
const emit = defineEmits<{ "open-ticket": [id: number] }>();
const staff = computed(() => ["AGENT", "SUPERVISOR", "ADMIN"].includes(props.user.role));
const reviewer = computed(() => ["SUPERVISOR", "ADMIN"].includes(props.user.role));
const items = ref<Knowledge[]>([]), selectedId = ref<number | null>(null);
const selected = computed(() => items.value.find(row => row.id === selectedId.value) ?? null);
const page = ref(1), total = ref(0), pending = ref(0), loaded = ref(false);
const status = ref<State | "">(""), searchInput = ref(""), query = ref("");
const loading = ref(false), busy = ref<number | null>(null), error = ref(""), mobileDetail = ref(false);
const notes = ref<Record<number, string>>({}), confirmed = ref<Record<number, boolean>>({});
const labels = { DRAFT: "待审核", PUBLISHED: "已通过", REJECTED: "已退回" };
const filters: { value: State | ""; label: string }[] = [
  { value: "DRAFT", label: "待审核" }, { value: "", label: "全部" },
  { value: "PUBLISHED", label: "已通过" }, { value: "REJECTED", label: "已退回" },
];
let request = 0, mutation = 0;
onBeforeUnmount(() => { request++; mutation++; });
async function load() {
  if (!staff.value) return;
  const active = ++request, previous = selectedId.value;
  loading.value = true;
  error.value = "";
  items.value = [];
  try {
    const params = new URLSearchParams({ page: String(page.value), size: "20", status: status.value, q: query.value });
    const result = await api<Result>("/knowledge/inbox?" + params);
    if (active !== request) return;
    items.value = result.items;
    total.value = result.total;
    pending.value = result.pendingTotal;
    loaded.value = true;
    const last = Math.max(1, Math.ceil(result.total / 20));
    if (page.value > last) { page.value = last; await load(); return; }
    selectedId.value = result.items.some(row => row.id === previous) ? previous : result.items[0]?.id ?? null;
    if (!selectedId.value) mobileDetail.value = false;
  } catch (e) {
    if (active === request) error.value = e instanceof Error ? e.message : "知识查询失败，请重试";
  } finally { if (active === request) loading.value = false; }
}
watch(() => [props.user.id, props.user.role, props.user.teamId], () => {
  request++;
  mutation++;
  busy.value = null;
  items.value = [];
  selectedId.value = null;
  notes.value = {};
  confirmed.value = {};
  page.value = 1;
  total.value = pending.value = 0;
  status.value = reviewer.value ? "DRAFT" : "";
  searchInput.value = query.value = "";
  loaded.value = false;
  mobileDetail.value = false;
  error.value = "";
  loading.value = false;
  load();
}, { immediate: true });
function search() {
  if (loading.value || busy.value !== null) return;
  query.value = searchInput.value.trim();
  page.value = 1;
  mobileDetail.value = false;
  load();
}
function filter(value: State | "") { status.value = value; search(); }
function changePage(next: number) {
  if (searchInput.value.trim() !== query.value) return search();
  page.value = next;
  mobileDetail.value = false;
  load();
}
function refresh() { if (searchInput.value.trim() !== query.value) search(); else load(); }
function select(row: Knowledge) { selectedId.value = row.id; mobileDetail.value = true; }
async function review(approved: boolean) {
  const row = selected.value;
  if (!row || !reviewer.value || row.status !== "DRAFT" || !confirmed.value[row.id] || busy.value !== null || loading.value) return;
  const note = (notes.value[row.id] ?? "").trim();
  if (!approved && !note) return;
  const active = request;
  const write = ++mutation;
  busy.value = row.id;
  error.value = "";
  try {
    await api(`/knowledge/${row.id}/review`, { approved, confirmed: true, ...(note ? { note } : {}) });
    if (active === request) {
      delete notes.value[row.id];
      delete confirmed.value[row.id];
      await load();
    }
  } catch (e) {
    if (active === request) error.value = e instanceof Error ? e.message : "审核失败，请刷新后重试";
  } finally { if (write === mutation) busy.value = null; }
}
</script>

<template>
  <section class="kb-workbench" aria-label="知识审核" :aria-busy="loading">
    <el-alert v-if="!staff" title="知识记录仅向服务团队开放" type="info" :closable="false" />
    <template v-else>
      <header class="kb-heading"><div><p class="kb-eyebrow">TEAM KNOWLEDGE</p><h1>知识审核</h1><p class="kb-description">从已解决的工单整理经验，审核内容与来源，保留每一次处理结论。</p></div><el-button :loading="loading" :disabled="busy !== null" @click="refresh">刷新</el-button></header>
      <div class="kb-toolbar"><div class="kb-filters" role="group" aria-label="知识状态筛选"><el-button v-for="option in filters" :key="option.value" :aria-pressed="status === option.value" :type="status === option.value ? 'primary' : 'default'" :disabled="loading || busy !== null" @click="filter(option.value)">{{ option.label }}</el-button></div><span v-if="loaded" class="kb-count" aria-live="polite" title="按当前数据权限统计，不受检索关键词和状态筛选影响">可见范围内 {{ pending }} 条待审核</span></div>
      <form class="kb-search" @submit.prevent="search"><el-input v-model="searchInput" aria-label="检索知识" placeholder="标题或内容，回车检索" maxlength="100" clearable :disabled="loading || busy !== null" /><el-button native-type="submit" :disabled="loading || busy !== null">检索</el-button></form>
      <p class="kb-boundary">审核通过后保存在知识库中，不会自动加入 AI 检索索引。</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
      <el-skeleton v-if="loading" :rows="8" animated />
      <div v-else-if="!items.length && !error" class="kb-empty"><h2>当前筛选暂无记录</h2><p>可以切换状态或关键词；工单经客户关闭后，服务人员可提交整理好的知识草稿。</p></div>
      <div v-else-if="items.length" class="kb-layout" :class="{ 'mobile-detail': mobileDetail }">
        <aside class="kb-queue" aria-label="知识条目"><p class="kb-queue-caption">筛选结果 · {{ total }} 条</p><ul><li v-for="row in items" :key="row.id"><button :class="{ chosen: selectedId === row.id }" :aria-current="selectedId === row.id ? 'true' : undefined" :disabled="busy !== null" @click="select(row)"><span class="kb-row-state">{{ labels[row.status] }}</span><strong>{{ row.title }}</strong><span class="kb-row-meta">{{ row.authorName || '账号 #' + row.authorId }} · {{ time(row.createdAt) }}</span></button></li></ul></aside>
        <section v-if="selected" class="kb-detail" aria-label="知识内容">
          <el-button class="kb-mobile-back" text :disabled="busy !== null" @click="mobileDetail = false">返回知识列表</el-button>
          <div class="kb-title"><h2>{{ selected.title }}</h2><el-tag :type="selected.status === 'PUBLISHED' ? 'success' : selected.status === 'REJECTED' ? 'warning' : 'info'" effect="plain">{{ labels[selected.status] }}</el-tag></div>
          <p class="kb-byline">提交人：{{ selected.authorName || '账号 #' + selected.authorId }}<span> · {{ time(selected.createdAt) }}</span></p>
          <div class="kb-origin"><el-button text :disabled="busy !== null" :title="selected.ticketReference || undefined" @click="emit('open-ticket', selected.ticketId)">来源工单 #{{ selected.ticketId }}</el-button><p v-if="selected.ticketTitle">{{ selected.ticketTitle }}</p></div>
          <div class="kb-body preserve-text">{{ selected.content }}</div>
          <form v-if="reviewer && selected.status === 'DRAFT'" class="kb-review" @submit.prevent="review(true)">
            <label :for="'kb-note-' + selected.id">审核意见（退回必填）</label><el-input :id="'kb-note-' + selected.id" v-model="notes[selected.id]" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="说明需要补充的证据或修改内容；通过时可选填。" :disabled="busy !== null" />
            <el-checkbox v-model="confirmed[selected.id]" :disabled="busy !== null">我已审核内容，确认提交审核结果</el-checkbox>
            <div class="kb-actions"><el-button type="primary" :loading="busy === selected.id" :disabled="!confirmed[selected.id] || busy !== null" native-type="submit">审核通过</el-button><el-button :disabled="!confirmed[selected.id] || !(notes[selected.id] || '').trim() || busy !== null" @click="review(false)">退回草稿</el-button></div>
          </form>
          <div v-else-if="selected.status !== 'DRAFT'" class="kb-conclusion"><h3>审核记录</h3><p>{{ selected.reviewerName || (selected.reviewerId ? '账号 #' + selected.reviewerId : '未记录审核人') }}<template v-if="selected.reviewedAt"> · {{ time(selected.reviewedAt) }}</template></p><p class="preserve-text">{{ selected.reviewNote || (selected.status === 'REJECTED' ? '此条记录未保存退回理由。' : '未填写审核意见。') }}</p><p v-if="selected.status === 'REJECTED'" class="kb-waiting">修正内容后，可打开来源工单提交新草稿，本条审核记录会保留。</p></div>
          <p v-else class="kb-waiting">草稿已提交，等待主管审核。</p>
        </section>
      </div>
      <nav v-if="total && !error && !loading" class="kb-pagination" aria-label="知识分页"><span>共 {{ total }} 条记录</span><el-pagination :current-page="page" :page-size="20" :total="total" :pager-count="5" layout="prev, pager, next" prev-text="上一页" next-text="下一页" :disabled="busy !== null" @current-change="changePage" /></nav>
    </template>
  </section>
</template>

<style scoped>
.kb-workbench { box-sizing: border-box; max-width: 1180px; margin: 0 auto; padding: 30px; color: var(--ink, #252724); }
.kb-heading,.kb-toolbar,.kb-title,.kb-pagination { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.kb-heading { margin-bottom: 24px; }
.kb-eyebrow { margin: 0 0 8px; font-size: 11px; font-weight: 700; letter-spacing: .12em; color: var(--muted, #6b736b); }
h1 { font-size: 26px; margin: 0; letter-spacing: -.03em; } h2 { font-size: 19px; line-height: 1.5; margin: 0; overflow-wrap: anywhere; } h3 { margin: 0; font-size: 14px; }
.kb-description,.kb-boundary { font-size: 13px; color: var(--muted, #6b736b); line-height: 1.8; }
.kb-description { margin: 8px 0 0; } .kb-boundary { margin: 14px 0 22px; }
.kb-toolbar { flex-wrap: wrap; } .kb-filters { display: flex; flex-wrap: wrap; gap: 8px; }
.kb-filters :deep(.el-button + .el-button),.kb-actions :deep(.el-button + .el-button) { margin-left: 0; }
.kb-count,.kb-pagination { font-size: 12px; color: var(--muted, #6b736b); }
.kb-search { display: flex; gap: 10px; max-width: 560px; margin-top: 16px; }
.kb-layout { display: grid; grid-template-columns: 260px minmax(0,1fr); border: 1px solid var(--line, #e3e6df); min-height: 560px; }
.kb-queue { min-width: 0; border-right: 1px solid var(--line, #e3e6df); background: #f7f8f5; }
.kb-queue-caption { font-size: 12px; margin: 0; padding: 16px; color: var(--muted, #6b736b); border-bottom: 1px solid var(--line, #e3e6df); }
.kb-queue ul { padding: 0; margin: 0; list-style: none; max-height: 620px; overflow-y: auto; }
.kb-queue button { width: 100%; padding: 16px; border: 0; border-bottom: 1px solid var(--line, #e3e6df); background: transparent; color: inherit; cursor: pointer; font: inherit; text-align: left; }
.kb-queue button:hover { background: #eef1ea; } .kb-queue button.chosen { background: #e8eee2; box-shadow: inset 3px 0 #61765a; }
.kb-queue button:focus-visible { outline: 2px solid var(--brand, #b5482b); outline-offset: -2px; }
.kb-queue strong { display: block; font-size: 14px; line-height: 1.6; margin: 7px 0; overflow-wrap: anywhere; }
.kb-row-state,.kb-row-meta { font-size: 11px; color: var(--muted, #6b736b); }
.kb-detail { padding: 26px; min-width: 0; background: #fff; }
.kb-title { align-items: flex-start; } .kb-byline { font-size: 12px; color: var(--muted, #6b736b); line-height: 1.8; }
.kb-origin { margin: 18px 0; padding-bottom: 18px; border-bottom: 1px solid var(--line, #e3e6df); }
.kb-origin :deep(.el-button) { padding: 0; height: auto; } .kb-origin p { margin: 10px 0 0; font-size: 13px; color: var(--muted, #6b736b); overflow-wrap: anywhere; }
.preserve-text { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.9; font-size: 14px; }
.kb-body { max-height: 360px; max-width: 75ch; overflow-y: auto; padding-right: 8px; }
.kb-review,.kb-conclusion { margin-top: 26px; border-top: 1px solid var(--line, #e3e6df); padding-top: 22px; }
.kb-review label { display: block; margin-bottom: 10px; font-size: 13px; }
.kb-review :deep(.el-checkbox) { margin-top: 16px; height: auto; white-space: normal; }
.kb-review :deep(.el-checkbox__label) { white-space: normal; line-height: 1.7; }
.kb-actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 18px; }
.kb-conclusion > p:first-of-type,.kb-waiting { color: var(--muted, #6b736b); font-size: 12px; line-height: 1.8; }
.kb-pagination { margin-top: 22px; flex-wrap: wrap; }
.kb-empty { margin-top: 20px; padding: 70px 20px; text-align: center; border-top: 1px solid var(--line, #e3e6df); }
.kb-empty p { font-size: 13px; line-height: 1.8; color: var(--muted, #6b736b); }
.kb-mobile-back { display: none; }
@media (max-width: 760px) {
  .kb-workbench { padding: 22px 16px; } .kb-heading { align-items: flex-start; } h1 { font-size: 23px; }
  .kb-layout { display: block; min-height: 0; } .kb-detail { display: none; padding: 20px; }
  .kb-layout.mobile-detail .kb-queue { display: none; } .kb-layout.mobile-detail .kb-detail { display: block; }
  .kb-queue { border-right: 0; } .kb-queue ul { max-height: none; } .kb-mobile-back { display: inline-flex; margin: 0 0 18px; padding: 0; }
  .kb-title { gap: 10px; } .kb-pagination { justify-content: center; }
  .kb-pagination :deep(.el-pagination) { --el-pagination-button-width: 27px; gap: 0; }
}
</style>
