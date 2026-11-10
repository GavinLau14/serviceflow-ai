<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElAlert, ElButton, ElCheckbox, ElSkeleton, ElTag } from "element-plus";
import { api, time, type User } from "../api";

interface Notice { id: number; ticketId: number; title: string; body: string; read: boolean; createdAt: string }
interface Knowledge {
  id: number; ticketId: number; teamId: number; title: string; content: string;
  status: "DRAFT" | "PUBLISHED" | "REJECTED"; authorId: number; reviewerId: number | null;
  createdAt: string; reviewedAt: string | null;
}
const props = defineProps<{ user: User; view: "notifications" | "knowledge" }>();
const emit = defineEmits<{ "open-ticket": [id: number] }>();
const notices = ref<Notice[]>([]), knowledge = ref<Knowledge[]>([]);
const loading = ref(false), error = ref(""), busyId = ref<number | null>(null);
const confirmed = ref<Record<number, boolean>>({});
const staff = computed(() => props.user.role !== "CUSTOMER");
const reviewer = computed(() => ["SUPERVISOR", "ADMIN"].includes(props.user.role));
const labels = { DRAFT: "待审核", PUBLISHED: "已通过", REJECTED: "已退回" };
let request = 0;
async function load() {
  const current = ++request;
  loading.value = true;
  error.value = "";
  try {
    if (props.view === "notifications") {
      const rows = await api<Notice[]>("/notifications");
      if (current === request) notices.value = rows;
    } else if (staff.value) {
      const rows = await api<Knowledge[]>("/knowledge");
      if (current === request) knowledge.value = rows;
    }
  } catch (e) {
    if (current === request) error.value = e instanceof Error ? e.message : "加载失败，请重试";
  } finally {
    if (current === request) loading.value = false;
  }
}
watch(() => [props.view, props.user.id, props.user.role], () => {
  notices.value = [];
  knowledge.value = [];
  confirmed.value = {};
  load();
}, { immediate: true });
async function markRead(notice: Notice) {
  if (busyId.value !== null) return;
  const current = request;
  busyId.value = notice.id;
  error.value = "";
  try {
    await api(`/notifications/${notice.id}/read`, {});
    if (current === request) notice.read = true;
  } catch (e) {
    if (current === request) error.value = e instanceof Error ? e.message : "标记失败，请重试";
  } finally { busyId.value = null; }
}
async function review(item: Knowledge, approved: boolean) {
  if (!reviewer.value || !confirmed.value[item.id] || busyId.value !== null) return;
  const current = request;
  busyId.value = item.id;
  error.value = "";
  try {
    const updated = await api<Knowledge>(`/knowledge/${item.id}/review`, { approved, confirmed: true });
    if (current === request) {
      knowledge.value = knowledge.value.map(row => row.id === item.id ? updated : row);
      confirmed.value[item.id] = false;
    }
  } catch (e) {
    if (current === request) error.value = e instanceof Error ? e.message : "审核失败，请刷新后重试";
  } finally { busyId.value = null; }
}
</script>

<template>
  <section class="operations-view" :aria-label="view === 'notifications' ? '消息通知' : '知识审核'" :aria-busy="loading">
    <header class="operations-heading">
      <div><p class="eyebrow">{{ view === 'notifications' ? 'INBOX' : 'TEAM KNOWLEDGE' }}</p><h1>{{ view === 'notifications' ? '消息通知' : '知识审核' }}</h1><p class="view-description">{{ view === 'notifications' ? '查看与你相关的工单动态，及时跟进下一步。' : '将已解决的问题整理为知识，经人工审核后保留。' }}</p></div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <el-skeleton v-if="loading" :rows="6" animated />
    <template v-else-if="view === 'notifications'">
      <div v-if="!notices.length && !error" class="empty-state"><h2>暂无消息</h2><p>工单有新进展时，会在这里通知你。</p></div>
      <ul v-else class="operation-list">
        <li v-for="notice in notices" :key="notice.id" class="notice-item" :class="{ unread: !notice.read }">
          <div class="item-heading"><h2>{{ notice.title }}</h2><el-tag :type="notice.read ? 'info' : 'primary'" size="small" effect="plain">{{ notice.read ? '已读' : '未读' }}</el-tag></div>
          <p class="preserve-text">{{ notice.body }}</p>
          <div class="item-footer"><time>{{ time(notice.createdAt) }}</time><div class="item-actions"><el-button size="small" @click="emit('open-ticket', notice.ticketId)">查看工单 #{{ notice.ticketId }}</el-button><el-button v-if="!notice.read" size="small" :loading="busyId === notice.id" :disabled="busyId !== null && busyId !== notice.id" @click="markRead(notice)">标为已读</el-button></div></div>
        </li>
      </ul>
    </template>
    <template v-else-if="staff">
      <p class="knowledge-note">仅显示你有权限查看的记录。审核通过后保存在知识库中，不会自动加入 AI 检索索引。</p>
      <div v-if="!knowledge.length && !error" class="empty-state"><h2>暂无知识记录</h2><p>工单关闭后，可在工单详情中手动整理解决方法并提交审核。</p></div>
      <ul v-else class="operation-list">
        <li v-for="item in knowledge" :key="item.id" class="knowledge-item">
          <div class="item-heading"><h2>{{ item.title }}</h2><el-tag :type="item.status === 'PUBLISHED' ? 'success' : item.status === 'REJECTED' ? 'info' : 'warning'" effect="plain">{{ labels[item.status] }}</el-tag></div>
          <p class="knowledge-content preserve-text">{{ item.content }}</p>
          <div class="item-footer"><time>提交于 {{ time(item.createdAt) }}<template v-if="item.reviewedAt"> · 审核于 {{ time(item.reviewedAt) }}</template></time><el-button size="small" @click="emit('open-ticket', item.ticketId)">来源工单 #{{ item.ticketId }}</el-button></div>
          <div v-if="reviewer && item.status === 'DRAFT'" class="review-actions"><el-checkbox v-model="confirmed[item.id]" :disabled="busyId !== null">我已审核内容，确认提交审核结果</el-checkbox><div class="item-actions"><el-button type="primary" :disabled="!confirmed[item.id] || busyId !== null" @click="review(item, true)">审核通过</el-button><el-button :disabled="!confirmed[item.id] || busyId !== null" @click="review(item, false)">退回草稿</el-button></div></div>
        </li>
      </ul>
    </template>
    <el-alert v-else title="知识记录仅向服务团队开放" type="info" :closable="false" />
  </section>
</template>

<style scoped>
.operations-view { max-width: 1080px; margin: 0 auto; padding: 30px; color: #1e293b; }
.operations-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 28px; }
.eyebrow { margin: 0 0 8px; color: #64748b; font-size: 11px; letter-spacing: .12em; font-weight: 700; }
h1 { margin: 0; font-size: 26px; letter-spacing: -.03em; }
.view-description,.knowledge-note { color: #64748b; line-height: 1.7; font-size: 14px; }
.view-description { margin: 8px 0 0; }
.knowledge-note { margin: 0 0 18px; }
.operation-list { padding: 0; margin: 18px 0 0; list-style: none; display: grid; gap: 14px; }
.notice-item,.knowledge-item { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 22px; min-width: 0; }
.notice-item.unread { border-left: 3px solid #2563eb; }
.item-heading,.item-footer { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
h2 { font-size: 16px; line-height: 1.5; margin: 0; overflow-wrap: anywhere; }
.preserve-text { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; font-size: 14px; }
.knowledge-content { margin: 16px 0 20px; }
.item-footer time { color: #64748b; font-size: 12px; line-height: 1.6; }
.item-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.item-actions :deep(.el-button + .el-button) { margin-left: 0; }
.review-actions { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; margin-top: 20px; padding-top: 16px; border-top: 1px solid #e2e8f0; }
.review-actions :deep(.el-checkbox) { height: auto; white-space: normal; }
.review-actions :deep(.el-checkbox__label) { white-space: normal; line-height: 1.6; }
.empty-state { padding: 70px 24px; text-align: center; border: 1px dashed #cbd5e1; border-radius: 12px; background: #fff; margin-top: 20px; }
.empty-state p { color: #64748b; font-size: 14px; line-height: 1.8; }
@media (max-width: 640px) { .operations-view { padding: 22px 16px; } .operations-heading { align-items: flex-start; } h1 { font-size: 23px; } .notice-item,.knowledge-item { padding: 18px; } .item-footer { align-items: flex-start; flex-direction: column; } .item-heading { align-items: flex-start; } }
</style>
