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
      <a class="login-brand" href="/" aria-label="ServiceFlow 首页"
        ><span class="flow-logo"><span></span><span></span></span
        ><strong>ServiceFlow</strong><span>服务台</span></a
      >
      <div class="login-overview">
        <h1>售后服务工作台</h1>
        <p>接住客户的问题，让每一次处理有迹可循。</p>
        <ol class="login-process">
          <li>
            <span class="process-node"></span>
            <div>
              <strong>受理问题</strong>
              <p>保留客户原话与问题上下文</p>
            </div>
          </li>
          <li>
            <span class="process-node"></span>
            <div>
              <strong>协同处理</strong>
              <p>分派、回复与交接，在同一处接续</p>
            </div>
          </li>
          <li>
            <span class="process-node"></span>
            <div>
              <strong>确认解决</strong>
              <p>由客户确认结果，再沉淀服务经验</p>
            </div>
          </li>
        </ol>
      </div>
      <footer>ServiceFlow AI <span>客户服务协作平台</span></footer>
    </section>
    <section class="login-form">
      <div>
        <h2>登录服务台</h2>
        <p class="muted">输入你的账号，继续处理售后事务。</p>
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
      <p class="login-note">本地演示环境</p>
    </section>
  </div>
</template>
