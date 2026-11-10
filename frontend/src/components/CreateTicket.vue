<script setup lang="ts">
import { ref } from "vue";
import {
  ElDrawer,
  ElForm,
  ElFormItem,
  ElInput,
  ElSelect,
  ElOption,
  ElButton,
  ElAlert,
} from "element-plus";
import { api, type Ticket } from "../api";
const model = defineModel<boolean>({ required: true });
const emit = defineEmits<{ created: [ticket: Ticket] }>();
const title = ref(""),
  description = ref(""),
  category = ref("DEVICE"),
  priority = ref("NORMAL"),
  busy = ref(false),
  error = ref("");
async function submit() {
  if (busy.value) return;
  if (!title.value.trim() || !description.value.trim()) {
    error.value = "请填写问题标题和描述";
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    const t = await api<Ticket>("/tickets", {
      title: title.value,
      description: description.value,
      category: category.value,
      priority: priority.value,
    });
    model.value = false;
    title.value = "";
    description.value = "";
    emit("created", t);
  } catch (e) {
    error.value = e instanceof Error ? e.message : "网络异常";
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <el-drawer
    v-model="model"
    title="提交售后问题"
    size="min(560px, 100vw)"
    :close-on-click-modal="!busy"
    :close-on-press-escape="!busy"
    ><p class="muted">描述实际遇到的问题，服务团队会继续跟进。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form label-position="top" @submit.prevent="submit"
      ><el-form-item label="问题标题" for="new-title" required
        ><el-input
          id="new-title"
          v-model="title"
          maxlength="160"
          show-word-limit
          placeholder="例如：设备接通电源后无法启动" /></el-form-item
      ><el-form-item label="问题描述" for="new-description" required
        ><el-input
          id="new-description"
          v-model="description"
          type="textarea"
          :rows="7"
          maxlength="10000"
          show-word-limit
          placeholder="请说明故障现象、发生时间和已经尝试的处理方法。请勿提交密码或支付信息。"
      /></el-form-item>
      <div class="two-fields">
        <el-form-item label="产品分类" for="new-category"
          ><el-select id="new-category" v-model="category"
            ><el-option label="数码设备" value="DEVICE" /><el-option
              label="家用电器"
              value="APPLIANCE" /></el-select></el-form-item
        ><el-form-item label="优先级" for="new-priority"
          ><el-select id="new-priority" v-model="priority"
            ><el-option label="低" value="LOW" /><el-option
              label="普通"
              value="NORMAL" /><el-option label="高" value="HIGH" /><el-option
              label="紧急"
              value="URGENT" /></el-select
        ></el-form-item>
      </div>
      <el-button type="primary" native-type="submit" :loading="busy"
        >确认提交</el-button
      ></el-form
    ></el-drawer
  >
</template>
