<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from "vue";
import { ElDrawer, ElForm, ElFormItem, ElInput, ElButton, ElAlert, ElTag, ElCheckbox } from "element-plus";
import { api, type AiRun, type AiMode, type Ticket, priorityNames } from "../api";
const model = defineModel<boolean>({ required: true });
const emit = defineEmits<{ created: [ticket: Ticket]; manual: [] }>();
const message = ref("");
const current = ref<AiRun | null>(null);
const history = ref<AiRun[]>([]);
const busy = ref(false), executing = ref(false), historyBusy = ref(false);
const error = ref(""), historyError = ref(""), confirmed = ref(false);
let active = true;
onUnmounted(() => { active = false; });
const modeNames: Record<AiMode, string> = {
  rag: "知识库辅助回答 · rag",
  retrieval_only: "仅检索原文 · retrieval_only",
  disabled: "服务未启用 · disabled",
  error: "服务暂不可用 · error",
};
const canExecute = computed(() => current.value?.mode === "rag" && current.value.status === "PENDING" && !!current.value.proposal && !current.value.ticketId);
const pending = computed(() => busy.value || executing.value);
async function loadHistory() {
  if (historyBusy.value) return;
  historyBusy.value = true;
  historyError.value = "";
  try {
    const runs = await api<AiRun[]>("/ai/runs");
    if (active) {
      const refreshed = runs.find((run) => run.id === current.value?.id);
      if (current.value?.status === "PENDING" && refreshed && refreshed.status !== "PENDING") {
        current.value = refreshed;
        confirmed.value = false;
      }
      history.value = current.value && !runs.some((run) => run.id === current.value?.id)
        ? [current.value, ...runs].slice(0, 20) : runs;
    }
  } catch (e) {
    if (active) historyError.value = e instanceof Error ? e.message : "历史记录加载失败";
  } finally {
    if (active) historyBusy.value = false;
  }
}
watch(model, (open) => { if (open) loadHistory(); });
function select(run: AiRun) {
  current.value = run;
  confirmed.value = false;
  error.value = "";
}
async function submit() {
  if (pending.value) return;
  if (!message.value.trim()) {
    error.value = "请先描述遇到的问题";
    return;
  }
  busy.value = true;
  error.value = "";
  current.value = null;
  confirmed.value = false;
  try {
    const run = await api<AiRun>("/ai/assist", { message: message.value.trim() });
    if (active) {
      current.value = run;
      // Update locally as well, so a temporary history error never hides this result.
      history.value = [run, ...history.value.filter((r) => r.id !== run.id)].slice(0, 20);
    }
  } catch (e) {
    if (active) error.value = e instanceof Error ? e.message : "请求失败，请稍后重试";
  } finally {
    if (active) busy.value = false;
  }
}
async function execute() {
  if (pending.value || !confirmed.value || !canExecute.value || !current.value) return;
  const run = current.value;
  executing.value = true;
  error.value = "";
  try {
    const ticket = await api<Ticket>(`/ai/runs/${run.id}/execute`, { confirmed: true });
    if (!active) return;
    const completed: AiRun = { ...run, status: "COMPLETED", ticketId: ticket.id };
    current.value = completed;
    history.value = history.value.map((r) => r.id === run.id ? completed : r);
    confirmed.value = false;
    model.value = false;
    emit("created", ticket);
  } catch (e) {
    if (active) {
      error.value = e instanceof Error ? e.message : "提交失败，请重试";
      // Execution may have expired or succeeded before a network interruption.
      await loadHistory();
      const refreshed = history.value.find((r) => r.id === run.id);
      if (refreshed) current.value = refreshed;
      confirmed.value = false;
    }
  } finally {
    if (active) executing.value = false;
  }
}
function manual() {
  model.value = false;
  emit("manual");
}
</script>
<template>
  <el-drawer v-model="model" title="售后 AI 助手" size="min(640px, 100vw)" :close-on-click-modal="!pending" :close-on-press-escape="!pending">
    <div class="assist-content">
      <p class="muted">描述故障，先查阅知识库。资料为演示内容；需要人工跟进时，可提交售后问题。</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="遇到了什么问题？" for="assist-message" required>
          <el-input id="assist-message" v-model="message" type="textarea" :rows="4" maxlength="5000" show-word-limit placeholder="例如：设备无法开机，已检查插座与电源连接。请勿填写密码或支付信息。" :disabled="pending" />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="busy" :disabled="executing">查找帮助</el-button>
        <el-button :disabled="pending" @click="manual">提交给人工</el-button>
      </el-form>
      <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
      <p v-if="busy" role="status" class="muted">正在查找相关资料，请稍候…</p>
      <section v-if="current" class="assist-result" aria-label="助手结果" aria-live="polite">
        <div class="section-title"><h3>本次结果</h3><el-tag effect="plain">{{ modeNames[current.mode] }}</el-tag></div>
        <p v-if="current.mode === 'retrieval_only'" class="muted">当前仅返回知识库检索原文，未调用语言模型，也不会生成工单草稿。</p>
        <p v-if="current.mode === 'disabled' || current.mode === 'error'" class="muted">可稍后重试，或提交售后问题由人工跟进。</p>
        <p class="preserve-text assist-answer">{{ current.answer || "未返回可用回答，请提交给人工处理。" }}</p>
        <el-alert v-if="current.handoff" title="建议由人工继续跟进" type="warning" :closable="false" description="助手未能确认完整处理方案。你可以提交售后问题，补充实际情况。" />
        <div v-if="current.citations.length" class="assist-citations">
          <h3>参考资料 · {{ current.citations.length }} 条</h3>
          <details v-for="citation in current.citations" :key="citation.id">
            <summary>{{ citation.title }}</summary>
            <p class="muted assist-source">来源：{{ citation.source }} · {{ citation.id }}</p>
            <p class="preserve-text">{{ citation.text }}</p>
          </details>
        </div>
        <section v-if="canExecute && current.proposal" class="next-action assist-proposal">
          <h3>待确认的工单草稿</h3>
          <p class="muted">草稿尚未提交。请核对内容；草稿在生成 30 分钟后过期。</p>
          <dl class="assist-draft">
            <dt>标题</dt><dd>{{ current.proposal.title }}</dd>
            <dt>问题描述</dt><dd class="preserve-text">{{ current.proposal.description }}</dd>
            <dt>产品分类 / 优先级</dt><dd>{{ current.proposal.category === 'DEVICE' ? '数码设备' : '家用电器' }} / {{ priorityNames[current.proposal.priority] }}</dd>
          </dl>
          <el-checkbox v-model="confirmed" :disabled="pending">我已核对草稿，确认创建售后工单</el-checkbox>
          <div class="button-row"><el-button type="primary" :disabled="!confirmed || pending" :loading="executing" @click="execute">确认创建工单</el-button></div>
        </section>
        <p v-if="current.ticketId" class="closed-message">此结果已创建工单 #{{ current.ticketId }}，可在工单队列查看处理进展。</p>
        <el-alert v-else-if="current.status === 'EXPIRED'" title="此草稿已过期，请重新查找帮助或提交给人工" type="warning" :closable="false" />
        <p v-else-if="current.status === 'PENDING' && !canExecute" class="muted">此结果当前不可创建工单，请重新查找或提交给人工。</p>
      </section>
      <section class="assist-history" aria-label="我的助手历史">
        <div class="section-title"><h3>我的助手历史 <span class="subtle">最近 20 次</span></h3><el-button text :loading="historyBusy" :disabled="pending" @click="loadHistory">刷新历史</el-button></div>
        <el-alert v-if="historyError" :title="historyError" type="error" :closable="false" role="alert" />
        <p v-if="!history.length && !historyBusy" class="muted">{{ historyError ? '可点击刷新历史重试。' : '还没有助手记录。' }}</p>
        <ol v-else class="assist-history-list">
          <li v-for="run in history" :key="run.id"><button :disabled="pending" :aria-pressed="current?.id === run.id" @click="select(run)"><span>#{{ run.id }} · {{ modeNames[run.mode] }}</span><strong>{{ run.proposal?.title || run.answer || '查看结果' }}</strong><small>{{ run.ticketId ? '已创建工单 #' + run.ticketId : run.status === 'PENDING' ? '草稿待确认' : run.status === 'EXPIRED' ? '草稿已过期' : '查看助手结果' }}</small></button></li>
        </ol>
      </section>
    </div>
  </el-drawer>
</template>
