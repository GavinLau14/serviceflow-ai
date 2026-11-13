<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { ElAlert, ElButton, ElCheckbox, ElInput, ElMessageBox } from "element-plus";
import { api, type User } from "../api";
interface Publication { id:string; version:number; title:string; text:string; status:string; indexState:string; cleanupState:string }
interface History { latest:Publication|null; history:Publication[] }
const props=defineProps<{knowledgeId:number;user:User}>();
const admin=computed(()=>props.user.role==='ADMIN');
const staff=computed(()=>props.user.role!=='CUSTOMER');
const expanded=ref(false),loading=ref(false),busy=ref(false),error=ref('');
const data=ref<History>({latest:null,history:[]});
const title=ref(''),text=ref(''),redacted=ref(false),confirmed=ref(false);
const state=computed(()=>!data.value.latest?'尚未授权发布':data.value.latest.status==='WITHDRAWN'?'已撤回，禁止后续引用':data.value.latest.indexState==='SYNCED'?'已同步，可用于检索':data.value.latest.indexState==='FAILED'?'索引失败，尚不可引用':'索引尚未确认，暂不可引用');
const ready=computed(()=>admin.value&&!loading.value&&!busy.value&&!!title.value.trim()&&!!text.value.trim()&&redacted.value&&confirmed.value);
let generation=0,operation=0;
onBeforeUnmount(()=>{generation++;operation++;});
async function load(){
  if(!staff.value)return;
  const current=++generation;loading.value=true;error.value='';
  try{const result=await api<History>(`/knowledge/${props.knowledgeId}/publication`);if(current===generation)data.value=result;}
  catch(e){if(current===generation)error.value=e instanceof Error?e.message:'发布记录暂不可用';}
  finally{if(current===generation)loading.value=false;}
}
watch(()=>[props.knowledgeId,props.user.id,props.user.role],()=>{
  generation++;operation++;data.value={latest:null,history:[]};title.value=text.value='';redacted.value=confirmed.value=false;error.value='';busy.value=false;
  if(expanded.value)load();
});
function toggle(event:Event){expanded.value=(event.target as HTMLDetailsElement).open;if(expanded.value)load();}
async function publish(){
  if(!ready.value)return;
  const current=generation,write=++operation;busy.value=true;error.value='';
  try{await api(`/knowledge/${props.knowledgeId}/publication`,{expectedVersion:data.value.latest?.version??0,title:title.value.trim(),text:text.value.trim(),redacted:true,confirmed:true});
    if(current===generation){redacted.value=confirmed.value=false;await load();}}
  catch(e){if(current===generation)error.value=e instanceof Error?e.message:'发布失败，请刷新后重试';}
  finally{if(write===operation)busy.value=false;}
}
async function action(kind:'sync'|'withdraw'){
  const latest=data.value.latest;if(!admin.value||!latest||busy.value||loading.value)return;
  const current=generation,write=++operation;busy.value=true;error.value='';
  try{
    if(kind==='withdraw'){
      try{await ElMessageBox.confirm('撤回后停止后续引用，并禁止引用该版本的旧 AI 草稿执行。已经展示或发送给模型的内容无法收回。','撤回公开知识',{confirmButtonText:'确认撤回',cancelButtonText:'取消',type:'warning'});}catch{return;}
    }
    if(current!==generation)return;
    await api(`/knowledge/${props.knowledgeId}/publication/${kind}`,{publicationId:latest.id,confirmed:true});
    if(current===generation)await load();
  }catch(e){if(current===generation)error.value=e instanceof Error?e.message:'操作未确认，请刷新后重试';}
  finally{if(write===operation)busy.value=false;}
}
</script>

<template>
  <details v-if="staff" class="publication-panel" @toggle="toggle">
    <summary>AI 知识发布</summary>
    <p class="publication-policy">审核通过与客户可检索分开管理。只有独立填写并授权的公开摘要会进入检索，内部正文和审核意见不会自动发送。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <p aria-live="polite"><span>{{ loading ? '正在读取发布状态…' : state }}</span><span v-if="data.latest"> · v{{ data.latest.version }}</span></p>
    <div v-if="data.latest" class="public-snapshot"><strong>{{ data.latest.title }}</strong><p>{{ data.latest.text }}</p></div>
    <p v-if="!admin" class="publication-policy">仅管理员可面向全部客户发布或撤回</p>
    <template v-else>
      <div v-if="data.latest" class="publication-actions">
        <el-button v-if="data.latest.status==='ACTIVE' && data.latest.indexState!=='SYNCED'" :disabled="busy||loading" @click="action('sync')">重试索引</el-button>
        <el-button v-if="data.latest.status==='ACTIVE'" :disabled="busy||loading" @click="action('withdraw')">撤回当前版本</el-button>
        <el-button v-if="data.latest.status==='WITHDRAWN' && data.latest.cleanupState==='FAILED'" :disabled="busy||loading" @click="action('withdraw')">重试清理</el-button>
      </div>
      <p v-if="data.latest?.cleanupState==='FAILED'" class="publication-policy">该版本已被禁止引用，后台向量清理尚未确认，可重试清理。</p>
      <form class="publication-form" @submit.prevent="publish">
        <p>另填可面向全部已登录客户展示的通用摘要；修改内容会创建新版本并撤回旧版。</p>
        <label :for="'publication-title-'+knowledgeId">公开标题</label><el-input :id="'publication-title-'+knowledgeId" v-model="title" maxlength="160" :disabled="busy||loading" />
        <label :for="'publication-text-'+knowledgeId">公开摘要</label><el-input :id="'publication-text-'+knowledgeId" v-model="text" type="textarea" :rows="5" maxlength="1500" show-word-limit :disabled="busy||loading" placeholder="不要包含姓名、联系方式、工单/订单号、密码或内部备注。" />
        <el-checkbox v-model="redacted" :disabled="busy||loading">我已人工脱敏，摘要不含客户或工单私密信息</el-checkbox>
        <el-checkbox v-model="confirmed" :disabled="busy||loading">我确认面向全部已登录客户发布</el-checkbox>
        <el-button type="primary" native-type="submit" :disabled="!ready" :loading="busy">发布公开摘要</el-button>
      </form>
    </template>
    <ol v-if="data.history.length" class="publication-versions"><li v-for="p in data.history" :key="p.id">v{{ p.version }} · {{ p.title }} · {{ p.status==='ACTIVE'?'当前授权':'已撤回' }}</li></ol>
  </details>
</template>

<style scoped>
.publication-panel { border-top:1px solid var(--line,#e3e6df);margin-top:24px;padding-top:20px;font-size:13px;line-height:1.8; }
summary { cursor:pointer;font-weight:600; } .publication-policy { color:var(--muted,#6b736b); }
.public-snapshot { padding:12px 0;border-block:1px solid var(--line,#e3e6df);overflow-wrap:anywhere; } .public-snapshot p { white-space:pre-wrap; }
.publication-form { display:flex;flex-direction:column;gap:12px;margin-top:22px; } .publication-form p { margin:0;color:var(--muted,#6b736b); }
.publication-form :deep(.el-checkbox) { white-space:normal;height:auto;margin:0; } .publication-form :deep(.el-checkbox__label) { white-space:normal;line-height:1.7; }
.publication-actions { display:flex;flex-wrap:wrap;gap:8px;margin-top:16px; }.publication-actions :deep(.el-button+.el-button){margin-left:0;}
.publication-versions { padding-left:20px;color:var(--muted,#6b736b);overflow-wrap:anywhere; }
</style>
