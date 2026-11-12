<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { ElAlert, ElButton, ElCheckbox, ElDrawer, ElForm, ElFormItem, ElInput } from "element-plus";
import { api, type User } from "../api";

defineProps<{ user: User }>();
const open = defineModel<boolean>({ default: false });
const emit = defineEmits<{ changed: [] }>();
const currentPassword = ref(""), newPassword = ref(""), repeat = ref("");
const confirmed = ref(false), busy = ref(false), error = ref("");
const bytes = (value: string) => new TextEncoder().encode(value).length;
const problem = computed(() => {
  if (bytes(currentPassword.value) > 72) return "当前密码不可超过72个UTF-8字节。";
  if (!newPassword.value) return "";
  if (Array.from(newPassword.value).length < 12) return "新密码至少需要12个字符。";
  if (bytes(newPassword.value) > 72) return "新密码不可超过72个UTF-8字节，中文和表情会占用多个字节。";
  if (newPassword.value === currentPassword.value) return "新密码不能与当前密码相同。";
  if (repeat.value && repeat.value !== newPassword.value) return "两次输入的新密码不一致。";
  return "";
});
const ready = computed(() => !!currentPassword.value.trim() && !!newPassword.value.trim()
  && newPassword.value === repeat.value && !problem.value && confirmed.value && !busy.value);
let generation = 0;
function clear() {
  currentPassword.value = newPassword.value = repeat.value = "";
  confirmed.value = false;
  error.value = "";
}
watch(open, () => { generation++; clear(); busy.value = false; });
onBeforeUnmount(() => { generation++; clear(); });
async function submit() {
  if (!ready.value) return;
  const active = ++generation;
  busy.value = true;
  error.value = "";
  try {
    await api("/auth/password", { currentPassword: currentPassword.value, newPassword: newPassword.value, confirmed: true });
    if (active !== generation) return;
    clear();
    open.value = false;
    emit("changed");
  } catch (e) {
    if (active === generation) error.value = e instanceof Error ? e.message : "修改失败，请重试";
  } finally { if (active === generation) busy.value = false; }
}
</script>

<template>
  <el-drawer v-model="open" title="账号安全" size="min(440px, 100vw)" :show-close="!busy"
    :close-on-click-modal="!busy" :close-on-press-escape="!busy">
    <p class="account-name">当前账号 <strong>{{ user.username }}</strong></p>
    <p class="account-note">修改成功后，此账号所有设备上的旧登录会话将失效，需要使用新密码重新登录。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" class="password-error" />
    <el-form label-position="top" class="password-form" @submit.prevent="submit">
      <el-form-item label="当前密码" for="account-current-password" required>
        <el-input id="account-current-password" v-model="currentPassword" type="password" show-password autocomplete="current-password" :disabled="busy" maxlength="128" />
      </el-form-item>
      <el-form-item label="新密码" for="account-new-password" required>
        <el-input id="account-new-password" v-model="newPassword" type="password" show-password autocomplete="new-password" :disabled="busy" maxlength="128" aria-describedby="account-password-policy" />
      </el-form-item>
      <p id="account-password-policy" class="password-policy">至少12个字符，最多72个UTF-8字节；不要使用其他网站的密码。</p>
      <el-form-item label="再次输入新密码" for="account-repeat-password" required>
        <el-input id="account-repeat-password" v-model="repeat" type="password" show-password autocomplete="new-password" :disabled="busy" maxlength="128" />
      </el-form-item>
      <p v-if="problem" class="password-problem" aria-live="polite">{{ problem }}</p>
      <el-checkbox v-model="confirmed" :disabled="busy" class="password-confirm">我确认修改密码，并退出全部已登录设备</el-checkbox>
      <div class="password-actions">
        <el-button :disabled="busy" @click="open = false">取消修改</el-button>
        <el-button type="primary" native-type="submit" :loading="busy" :disabled="!ready">修改密码并重新登录</el-button>
      </div>
    </el-form>
  </el-drawer>
</template>

<style scoped>
.account-name { margin: 0 0 18px; color: var(--muted, #6b736b); font-size: 14px; }
.account-name strong { margin-left: 8px; color: var(--ink, #252724); }
.account-note { padding: 12px 14px; border-left: 2px solid var(--line, #e3e6df); color: var(--muted, #6b736b); font-size: 13px; line-height: 1.8; }
.password-form,.password-error { margin-top: 22px; }
.password-policy { margin: -8px 0 22px; font-size: 12px; line-height: 1.7; color: var(--muted, #6b736b); }
.password-problem { color: #94361e; font-size: 13px; line-height: 1.7; }
.password-confirm { height: auto; white-space: normal; }
.password-confirm :deep(.el-checkbox__label) { white-space: normal; line-height: 1.7; }
.password-actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: 10px; margin-top: 26px; }
.password-actions :deep(.el-button + .el-button) { margin-left: 0; }
</style>
