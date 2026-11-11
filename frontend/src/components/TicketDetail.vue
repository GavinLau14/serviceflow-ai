<script setup lang="ts">
import { ref, watch } from "vue";
import {
  ElButton,
  ElInput,
  ElSelect,
  ElOption,
  ElCheckbox,
  ElFormItem,
  ElAlert,
  ElMessage,
  ElMessageBox,
  ElTag,
  ElSkeleton,
} from "element-plus";
import {
  api,
  ApiError,
  type Detail,
  type User,
  statusNames,
  priorityNames,
  time,
} from "../api";
import TicketOperations from "./TicketOperations.vue";
const props = defineProps<{ id: number; user: User }>();
const emit = defineEmits<{ updated: [] }>();
const detail = ref<Detail | null>(null),
  loading = ref(false),
  busy = ref(false),
  error = ref(""),
  reply = ref(""),
  internal = ref(false),
  solution = ref(""),
  assignOpen = ref(false),
  agents = ref<User[]>([]),
  assignee = ref<number>(),
  consent = ref(false);
let request = 0;
async function load() {
  const n = ++request;
  loading.value = true;
  error.value = "";
  try {
    const result = await api<Detail>("/tickets/" + props.id);
    if (n === request) detail.value = result;
  } catch (e) {
    if (n === request)
      error.value = e instanceof Error ? e.message : "加载失败";
  } finally {
    if (n === request) loading.value = false;
  }
}
watch(
  () => props.id,
  () => {
    detail.value = null;
    reply.value = "";
    solution.value = "";
    internal.value = false;
    assignOpen.value = false;
    load();
  },
  { immediate: true },
);
function can(action: string) {
  return detail.value?.allowedActions.includes(action) ?? false;
}
async function mutate(path: string, body: object) {
  if (!detail.value || busy.value) return false;
  busy.value = true;
  error.value = "";
  try {
    await api("/tickets/" + props.id + path, {
      ...body,
      version: detail.value.ticket.version,
    });
    await load();
    emit("updated");
    return true;
  } catch (e) {
    error.value = e instanceof Error ? e.message : "操作失败";
    if (e instanceof ApiError && e.status === 409) {
      const text = error.value;
      await load();
      error.value = text;
    }
    return false;
  } finally {
    busy.value = false;
  }
}
async function openAssign() {
  if (!detail.value) return;
  try {
    agents.value = await api<User[]>(
      "/agents?teamId=" + detail.value.ticket.teamId,
    );
    assignee.value = detail.value.ticket.assigneeId ?? undefined;
    consent.value = false;
    assignOpen.value = true;
  } catch (e) {
    error.value = e instanceof Error ? e.message : "无法获取客服";
  }
}
async function assign() {
  if (!assignee.value || !consent.value) return;
  if (
    await mutate("/assign", {
      assigneeId: assignee.value,
      confirmed: consent.value,
    })
  )
    assignOpen.value = false;
}
async function comment() {
  if (!reply.value.trim()) return;
  if (
    await mutate("/comments", { body: reply.value, internal: internal.value })
  ) {
    reply.value = "";
    ElMessage.success("回复已保存");
  }
}
async function transition(target: string) {
  let message = target === "IN_PROGRESS" ? "客服开始处理问题" : solution.value;
  const confirmed =
    target === "CLOSED" || (can("REJECT") && target === "IN_PROGRESS");
  if (target === "WAITING_CUSTOMER" && !message.trim()) {
    error.value = "请填写具体解决方案后再提交";
    return;
  }
  if (confirmed) {
    try {
      await ElMessageBox.confirm(
        target === "CLOSED"
          ? "确认问题已经解决？确认后工单将关闭。"
          : "确认问题仍未解决？工单将交回客服继续处理。",
        target === "CLOSED" ? "确认解决" : "继续处理",
        {
          confirmButtonText: target === "CLOSED" ? "确认关闭" : "确认退回",
          cancelButtonText: "取消",
          type: "warning",
        },
      );
    } catch {
      return;
    }
    message =
      target === "CLOSED"
        ? "客户确认问题已解决"
        : "客户反馈问题未解决，请继续处理";
  }
  if (await mutate("/transition", { target, confirmed, message }))
    solution.value = "";
}
</script>
<template>
  <section class="detail-panel" aria-label="工单详情" :aria-busy="loading">
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      role="alert"
      class="detail-error"
    />
    <el-skeleton v-if="loading && !detail" :rows="9" animated />
    <template v-else-if="detail"
      ><header class="detail-header">
        <div class="detail-topline">
          <span class="reference" :title="detail.ticket.reference"
            >工单 #{{ detail.ticket.id }}</span
          ><el-tag :class="'state-' + detail.ticket.status" effect="plain">{{
            statusNames[detail.ticket.status]
          }}</el-tag>
        </div>
        <h2>{{ detail.ticket.title }}</h2>
        <div class="metadata">
          <span>{{ detail.ticket.customerName }}</span
          ><span>{{ time(detail.ticket.createdAt) }}</span
          ><span>{{ priorityNames[detail.ticket.priority] }}优先级</span>
        </div>
      </header>
      <div class="case-grid">
        <div class="detail-body">
          <section class="issue-section">
            <div class="message-author">
              <span class="person-avatar">{{
                detail.ticket.customerName[0]
              }}</span>
              <div>
                <strong>{{ detail.ticket.customerName.split(" · ")[0] }}</strong
                ><small>客户提交的问题</small>
              </div>
              <time>{{ time(detail.ticket.createdAt) }}</time>
            </div>
            <p class="preserve-text">{{ detail.ticket.description }}</p>
          </section>
          <section v-if="detail.ticket.status !== 'CLOSED'" class="next-action">
            <div class="section-title">
              <h3>下一步处理</h3>
              <el-button
                v-if="can('ASSIGN')"
                size="small"
                :disabled="busy"
                @click="openAssign"
                >分派客服</el-button
              >
            </div>
            <p v-if="detail.ticket.status === 'OPEN'" class="muted">
              {{
                can("ASSIGN")
                  ? "请将问题分派给本团队的客服。"
                  : "服务团队将查看问题并安排客服跟进。"
              }}
            </p>
            <p v-else-if="detail.ticket.status === 'ASSIGNED'" class="muted">
              {{
                can("START")
                  ? "该问题已由你负责，准备好后开始处理。"
                  : "已分派客服，等待开始处理。"
              }}
            </p>
            <p v-else-if="detail.ticket.status === 'IN_PROGRESS'" class="muted">
              客服正在跟进；你可以在下方补充问题信息。
            </p>
            <p v-else class="muted">解决方案已提交，等待客户确认处理结果。</p>
            <el-button
              v-if="can('START')"
              type="primary"
              :loading="busy"
              @click="transition('IN_PROGRESS')"
              >开始处理</el-button
            >
            <template v-if="can('RESOLVE')"
              ><el-form-item label="处理说明" for="solution"
                ><el-input
                  id="solution"
                  v-model="solution"
                  type="textarea"
                  :rows="3"
                  maxlength="2000"
                  placeholder="记录排查过程、实际处理措施与验证结果" /></el-form-item
              ><el-button
                type="primary"
                :loading="busy"
                @click="transition('WAITING_CUSTOMER')"
                >提交解决方案</el-button
              ></template
            >
            <div v-if="can('CONFIRM')" class="button-row">
              <el-button
                type="primary"
                :loading="busy"
                @click="transition('CLOSED')"
                >确认解决并关闭</el-button
              ><el-button :disabled="busy" @click="transition('IN_PROGRESS')"
                >仍未解决</el-button
              >
            </div>
            <div v-if="assignOpen" class="assign-form">
              <el-form-item label="负责客服" for="assign-agent"
                ><el-select
                  id="assign-agent"
                  v-model="assignee"
                  placeholder="选择本团队客服"
                  ><el-option
                    v-for="agent in agents"
                    :key="agent.id"
                    :label="agent.displayName"
                    :value="agent.id" /></el-select
              ></el-form-item>
              <p v-if="!agents.length">当前团队没有可分派客服。</p>
              <el-checkbox v-model="consent"
                >我确认将此工单分派给所选客服</el-checkbox
              >
              <div class="button-row">
                <el-button
                  type="primary"
                  :loading="busy"
                  :disabled="!assignee || !consent"
                  @click="assign"
                  >确认分派</el-button
                ><el-button :disabled="busy" @click="assignOpen = false"
                  >取消</el-button
                >
              </div>
            </div>
          </section>
          <p v-else class="closed-message">此工单已完成，感谢你的确认。</p>

          <section class="comments-section">
            <h3>
              沟通记录 <span class="subtle">{{ detail.comments.length }}</span>
            </h3>
            <p v-if="!detail.comments.length" class="muted">还没有补充消息。</p>
            <article
              v-for="comment in detail.comments"
              :key="comment.id"
              class="comment"
            >
              <div class="event-head">
                <strong>{{ comment.authorName }}</strong
                ><time>{{ time(comment.createdAt) }}</time
                ><el-tag v-if="comment.internal" size="small" type="warning"
                  >内部备注</el-tag
                >
              </div>
              <p class="preserve-text">{{ comment.body }}</p>
            </article>
            <div v-if="can('COMMENT')" class="reply-form">
              <label for="reply">{{
                internal ? "内部备注 · 仅服务团队可见" : "补充消息"
              }}</label
              ><el-input
                id="reply"
                v-model="reply"
                type="textarea"
                :rows="3"
                maxlength="5000"
                placeholder="补充信息或回复处理进展"
              />
              <div class="reply-footer">
                <el-checkbox v-if="can('INTERNAL_NOTE')" v-model="internal"
                  >仅内部可见</el-checkbox
                ><el-button
                  type="primary"
                  :disabled="!reply.trim()"
                  :loading="busy"
                  @click="comment"
                  >发送回复</el-button
                >
              </div>
            </div>
          </section>
          <section class="activity-section">
            <h3>
              处理轨迹 <span class="subtle">{{ detail.events.length }}</span>
            </h3>
            <ol class="timeline">
              <li v-for="event in detail.events" :key="event.id">
                <div class="event-head">
                  <strong>{{ event.actorName }}</strong
                  ><time>{{ time(event.createdAt) }}</time>
                </div>
                <p>{{ event.message }}</p>
                <span class="event-state"
                  >{{
                    event.fromStatus
                      ? statusNames[event.fromStatus] + " → "
                      : ""
                  }}{{ statusNames[event.toStatus] }}</span
                >
              </li>
            </ol>
          </section>
        </div>
        <aside class="case-properties">
          <h3>工单信息</h3>
          <dl>
            <div>
              <dt>当前状态</dt>
              <dd>
                <span
                  class="ticket-state"
                  :class="'state-' + detail.ticket.status"
                  ><span></span>{{ statusNames[detail.ticket.status] }}</span
                >
              </dd>
            </div>
            <div>
              <dt>产品分类</dt>
              <dd>
                {{
                  detail.ticket.category === "DEVICE" ? "数码设备" : "家用电器"
                }}
              </dd>
            </div>
            <div>
              <dt>优先级</dt>
              <dd>{{ priorityNames[detail.ticket.priority] }}</dd>
            </div>
            <div>
              <dt>负责客服</dt>
              <dd>
                {{ detail.ticket.assigneeName?.split(" · ")[0] || "等待分派" }}
              </dd>
            </div>
            <div>
              <dt>服务团队</dt>
              <dd>
                {{ detail.ticket.teamId === 1 ? "数码服务组" : "家电服务组" }}
              </dd>
            </div>
            <div>
              <dt>最近更新</dt>
              <dd>{{ time(detail.ticket.updatedAt) }}</dd>
            </div>
          </dl>
          <h3 class="customer-context-title">客户</h3>
          <div class="context-person">
            <span class="person-avatar">{{
              detail.ticket.customerName[0]
            }}</span
            ><strong>{{ detail.ticket.customerName.split(" · ")[0] }}</strong>
          </div>
          <p class="context-note">仅向有权限的服务成员展示此工单及相关记录。</p>
          <details v-if="user.role !== 'CUSTOMER'" class="case-tools">
            <summary>服务时限与审计</summary>
            <TicketOperations
              :id="id"
              :user="user"
              :status="detail.ticket.status"
              @updated="load"
            />
          </details>
        </aside></div></template
    ><el-button v-else-if="!loading" @click="load">重新加载详情</el-button>
  </section>
</template>
