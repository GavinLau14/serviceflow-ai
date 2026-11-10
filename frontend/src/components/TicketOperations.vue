<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElAlert, ElButton, ElCheckbox, ElInput, ElSkeleton, ElTag } from "element-plus";
import { api, time, type User } from "../api";

interface Sla { ticketId: number; firstResponseDue: string | null; resolutionDue: string | null; firstRespondedAt: string | null; escalatedAt: string | null; firstResponseBreached: boolean; resolutionBreached: boolean; resolutionPaused: boolean }
interface Audit { id: number; ticketId: number; actorId: number | null; actorName: string | null; action: string; message: string; createdAt: string }
const props = defineProps<{ id: number; user: User; status: string }>();
const emit = defineEmits<{ updated: [] }>();
const sla = ref<Sla | null>(null), audit = ref<Audit[]>([]);
const loading = ref(false), slaError = ref(""), auditError = ref("");
const title = ref(""), content = ref(""), confirmed = ref(false), submitting = ref(false), draftError = ref(""), submitted = ref(false);
const staff = computed(() => props.user.role !== "CUSTOMER");
const canDraft = computed(() => staff.value && props.status === "CLOSED");
let request = 0;
const displayTime = (value: string | null) => value ? time(value) : "暂未记录";
async function load() {
  const current = ++request;
  if (!staff.value) {
    loading.value = false;
    return;
  }
  loading.value = true;
  slaError.value = auditError.value = "";
  const results = await Promise.allSettled([
    api<Sla>(`/tickets/${props.id}/sla`),
    staff.value ? api<Audit[]>(`/tickets/${props.id}/audit`) : Promise.resolve([] as Audit[]),
  ]);
  if (current !== request) return;
  const [slaResult, auditResult] = results;
  if (slaResult.status === "fulfilled") sla.value = slaResult.value;
  else slaError.value = slaResult.reason instanceof Error ? slaResult.reason.message : "服务时限加载失败";
  if (auditResult.status === "fulfilled") audit.value = auditResult.value;
  else auditError.value = auditResult.reason instanceof Error ? auditResult.reason.message : "审计记录加载失败";
  loading.value = false;
}
watch(() => [props.id, props.user.id, props.user.role, props.status], ([id, userId], previous) => {
  if (!previous || id !== previous[0] || userId !== previous[1]) {
    title.value = content.value = draftError.value = "";
    confirmed.value = submitted.value = false;
  }
  sla.value = null;
  audit.value = [];
  load();
}, { immediate: true });
async function submitDraft() {
  if (!canDraft.value || !confirmed.value || !title.value.trim() || !content.value.trim() || submitting.value) return;
  const current = request;
  submitting.value = true;
  draftError.value = "";
  try {
    await api(`/tickets/${props.id}/knowledge`, { title: title.value.trim(), content: content.value.trim(), confirmed: true });
    if (current === request) {
      submitted.value = true;
      confirmed.value = false;
      emit("updated");
    }
  } catch (e) {
    if (current === request) draftError.value = e instanceof Error ? e.message : "提交失败，请重试";
  } finally { submitting.value = false; }
}
</script>

<template>
  <div v-if="staff" class="ticket-operations">
    <section class="operations-section" aria-label="服务时限" :aria-busy="loading">
      <div class="section-heading"><h3>服务时限</h3><el-button text size="small" :loading="loading" @click="load">刷新记录</el-button></div>
      <el-alert v-if="slaError" :title="slaError" type="error" :closable="false" role="alert" />
      <el-skeleton v-if="loading" :rows="2" animated />
      <template v-else-if="sla">
        <dl class="sla-facts"><div><dt>首次响应截止</dt><dd>{{ displayTime(sla.firstResponseDue) }}</dd><span v-if="sla.firstRespondedAt" class="sla-note">已响应 · {{ time(sla.firstRespondedAt) }}</span><el-tag v-else-if="sla.firstResponseBreached" size="small" type="danger" effect="plain">首次响应已超时</el-tag></div><div><dt>解决截止</dt><dd>{{ displayTime(sla.resolutionDue) }}</dd><el-tag v-if="sla.resolutionPaused" size="small" type="info" effect="plain">等待客户，暂停超时升级</el-tag><el-tag v-else-if="sla.resolutionBreached" size="small" type="danger" effect="plain">解决已超时</el-tag></div></dl>
        <p v-if="sla.escalatedAt" class="sla-escalated">已于 {{ time(sla.escalatedAt) }} 触发超时升级。</p>
      </template>
      <p v-else-if="!slaError" class="empty-note">暂无服务时限记录。</p>
    </section>
    <section v-if="staff" class="operations-section" aria-label="操作审计">
      <h3>操作审计</h3>
      <el-alert v-if="auditError" :title="auditError" type="error" :closable="false" role="alert" />
      <el-skeleton v-if="loading" :rows="2" animated />
      <p v-else-if="!audit.length && !auditError" class="empty-note">暂无审计记录。</p>
      <ol v-else class="audit-list"><li v-for="item in audit" :key="item.id"><div class="audit-heading"><strong>{{ item.actorName || '系统' }}</strong><time>{{ time(item.createdAt) }}</time></div><p>{{ item.message }}</p><span class="audit-action">{{ item.action }}</span></li></ol>
    </section>
    <section v-if="canDraft" class="operations-section knowledge-draft" aria-label="整理解决方法">
      <h3>整理解决方法</h3>
      <p class="draft-description">将这次处理的通用方法整理为知识草稿。请只填写解决步骤与验证结果，去除姓名、联系方式等客户信息。提交后由主管审核，不会自动加入 AI 检索索引。</p>
      <el-alert v-if="submitted" title="知识草稿已提交，等待主管审核。" type="success" :closable="false" role="status" />
      <el-alert v-if="draftError" :title="draftError" type="error" :closable="false" role="alert" />
      <form v-if="!submitted" class="draft-form" @submit.prevent="submitDraft">
        <label :for="'knowledge-title-' + id">知识标题</label><el-input :id="'knowledge-title-' + id" v-model="title" maxlength="160" placeholder="概括适用问题与解决方法" :disabled="submitting" />
        <label :for="'knowledge-content-' + id">解决方法</label><el-input :id="'knowledge-content-' + id" v-model="content" type="textarea" :rows="5" maxlength="10000" placeholder="手动填写通用解决步骤与验证结果" :disabled="submitting" />
        <el-checkbox v-model="confirmed" :disabled="submitting">我已检查内容，不含客户个人信息，确认提交审核</el-checkbox>
        <div><el-button native-type="submit" type="primary" :loading="submitting" :disabled="!title.trim() || !content.trim() || !confirmed">提交知识草稿</el-button></div>
      </form>
    </section>
  </div>
</template>

<style scoped>
.ticket-operations { color: #334155; }
.operations-section { padding: 24px 0; border-top: 1px solid #e2e8f0; }
h3 { margin: 0 0 16px; font-size: 15px; color: #1e293b; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.section-heading h3 { margin: 0; }
.sla-facts { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 20px; margin: 0; }
.sla-facts dt { font-size: 12px; color: #64748b; margin-bottom: 7px; }
.sla-facts dd { margin: 0 0 8px; font-size: 14px; }
.sla-note,.sla-escalated,.empty-note { font-size: 12px; color: #64748b; line-height: 1.7; }
.sla-note { display: block; }
.sla-escalated { margin: 14px 0 0; }
.audit-list { list-style: none; padding: 0; margin: 0; }
.audit-list li { padding: 12px 0; border-bottom: 1px solid #f1f5f9; }
.audit-list li:last-child { border: 0; }
.audit-heading { display: flex; justify-content: space-between; gap: 12px; font-size: 12px; }
.audit-heading time,.audit-action { color: #64748b; font-size: 11px; }
.audit-list p { font-size: 13px; line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; margin: 8px 0; }
.audit-action { overflow-wrap: anywhere; }
.draft-description { font-size: 13px; line-height: 1.8; color: #64748b; margin: 0 0 18px; }
.draft-form { display: grid; gap: 10px; }
.draft-form label { margin-top: 6px; font-size: 13px; font-weight: 500; }
.draft-form :deep(.el-checkbox) { height: auto; white-space: normal; margin: 6px 0; }
.draft-form :deep(.el-checkbox__label) { white-space: normal; line-height: 1.6; }
.knowledge-draft :deep(.el-alert) { margin-bottom: 14px; }
@media (max-width: 540px) { .sla-facts { grid-template-columns: 1fr; gap: 16px; } .audit-heading { flex-wrap: wrap; } }
</style>
