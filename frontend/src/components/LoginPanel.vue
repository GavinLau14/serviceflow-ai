<script setup lang="ts">
import { ref } from "vue";
import { ElButton, ElInput, ElForm, ElFormItem, ElAlert } from "element-plus";
import type { User } from "../api";
import { api } from "../api";
const emit = defineEmits<{ login: [user: User] }>();
const username = ref("alice"),
  password = ref(""),
  busy = ref(false),
  error = ref("");
async function login() {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    const r = await api<{ token: string; user: User }>("/auth/login", {
      username: username.value,
      password: password.value,
    });
    sessionStorage.setItem("sf_token", r.token);
    emit("login", r.user);
  } catch (e) {
    error.value = e instanceof Error ? e.message : "网络连接失败";
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="login-layout">
    <section class="login-story">
      <a class="wordmark" href="/" aria-label="ServiceFlow 首页"
        ><span class="brand-mark">S</span>ServiceFlow <small>AI</small></a
      >
      <div>
        <p class="intro-label">售后服务，从问题到解决</p>
        <h1>连接每次服务，<br />推进每一个解决。</h1>
        <p>
          让客户、客服与服务团队在同一条处理链路中协作。保留问题的上下文，也让每次交接有据可查。
        </p>
        <ol class="login-flow">
          <li>客户提交问题</li>
          <li>团队协作处理</li>
          <li>客户确认解决</li>
        </ol>
      </div>
      <span class="story-footer">ServiceFlow AI · 智能售后工单协同平台</span>
    </section>
    <section class="login-form">
      <div>
        <p class="intro-label">欢迎回来</p>
        <h2>登录工作台</h2>
        <p class="muted">使用你的服务账号继续。</p>
        <el-alert
          v-if="error"
          :title="error"
          type="error"
          :closable="false"
          show-icon
          role="alert"
        />
        <el-form label-position="top" @submit.prevent="login"
          ><el-form-item label="账号" for="username"
            ><el-input
              id="username"
              v-model="username"
              autocomplete="username"
              maxlength="50" /></el-form-item
          ><el-form-item label="密码" for="password"
            ><el-input
              id="password"
              v-model="password"
              type="password"
              show-password
              autocomplete="current-password"
              maxlength="128" /></el-form-item
          ><el-button
            type="primary"
            native-type="submit"
            :loading="busy"
            class="login-submit"
            >登录工作台</el-button
          ></el-form
        >
        <details class="demo-guide">
          <summary>本地演示账号说明</summary>
          <p>
            客户 alice / bob；客服 agent；主管 supervisor；管理员
            admin。家电组使用 otheragent / otherlead。
          </p>
          <p>
            密码由本地初始化脚本生成，位于项目 .env 的
            SERVICEFLOW_DEMO_PASSWORD，不在页面预填。
          </p>
        </details>
      </div>
      <p class="login-note">ServiceFlow · 安全协作与智能检索</p>
    </section>
  </div>
</template>
