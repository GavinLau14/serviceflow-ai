<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { ElAlert, ElButton, ElCheckbox, ElMessageBox, ElPagination } from 'element-plus';
import { api, time, type User } from '../api';
interface Item { id:string; knowledgeId:number; version:number; title:string; status:string; indexState:string; cleanupState:string; checkState:string; checkedAt:string|null }
interface Summary { active:number; synced:number; pending:number; failed:number; cleanupPending:number }
interface Result { items:Item[]; total:number; page:number; size:number; summary:Summary }
const props=defineProps<{user:User}>();
const emit=defineEmits<{changed:[]}>();
const expanded=ref(false),page=ref(1),problemsOnly=ref(false),loading=ref(false),busy=ref(false),error=ref('');
const result=ref<Result|null>(null);
const checkNames:Record<string,string>={UNKNOWN:'尚未核验',CURRENT:'最近核验一致',MISSING:'向量点缺失',INVALID:'索引内容不符',UNAVAILABLE:'核验服务不可达'};
const indexNames:Record<string,string>={SYNCED:'已确认索引',PENDING:'等待索引确认',FAILED:'索引未确认'};
let generation=0,operation=0;
onBeforeUnmount(()=>{generation++;operation++;});
watch(()=>[props.user.id,props.user.role],()=>{generation++;operation++;result.value=null;error.value='';busy.value=loading.value=false;expanded.value=false;page.value=1;});
async function load(){
 if(props.user.role!=='ADMIN'||!expanded.value)return;
 const current=++generation;loading.value=true;error.value='';result.value=null;
 try{const r=await api<Result>(`/knowledge/index-operations?page=${page.value}&size=20&problemsOnly=${problemsOnly.value}`);if(current!==generation)return;
  const last=Math.max(1,Math.ceil(r.total/20));if(page.value>last){page.value=last;await load();return;}result.value=r;
 }catch(e){if(current===generation)error.value=e instanceof Error?e.message:'索引状态读取失败';}
 finally{if(current===generation)loading.value=false;}
}
function toggle(event:Event){expanded.value=(event.target as HTMLDetailsElement).open;if(expanded.value)load();}
function filter(){page.value=1;load();}
async function action(row:Item,kind:'check'|'repair'|'withdraw'){
 if(props.user.role!=='ADMIN'||loading.value||busy.value)return;
 const current=generation,write=++operation;busy.value=true;error.value='';
 try{
  if(kind!=='check'){
   try{await ElMessageBox.confirm(kind==='repair'?'只重建这个已经授权的公开摘要版本，不新增公开版本，也不会恢复已撤回知识。修复确认前暂停此版本引用。':'只重试清理已撤回版本的残留向量，不恢复公开授权。',kind==='repair'?'修复公开知识索引':'重试向量清理',{confirmButtonText:kind==='repair'?'确认修复':'确认清理',cancelButtonText:'取消',type:'warning'});}catch{return;}
  }
  if(current!==generation)return;
  await api(`/knowledge/${row.knowledgeId}/publication/${kind}`,{publicationId:row.id,confirmed:true});
  if(current===generation){emit('changed');await load();}
 }catch(e){if(current===generation)error.value=e instanceof Error?e.message:'操作未确认，请刷新后重试';}
 finally{if(write===operation)busy.value=false;}
}
defineExpose({refresh:load});
</script>

<template>
 <details v-if="user.role==='ADMIN'" class="index-operations" :open="expanded" @toggle="toggle">
  <summary>索引运行状态</summary>
  <p class="index-help">按公开版本核验或修复。下列状态来自数据库记录，最近一次确认不代表服务持续可用；修复不会新增公开授权。</p>
  <div class="index-tools"><el-checkbox v-model="problemsOnly" :disabled="loading||busy" @change="filter">只看待处理</el-checkbox><el-button :loading="loading" :disabled="busy" @click="load">刷新索引状态</el-button></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
  <p v-if="loading" role="status">正在读取索引记录…</p>
  <template v-if="result">
   <dl class="index-summary" aria-label="索引汇总"><div><dt>有效授权</dt><dd>{{result.summary.active}}</dd></div><div><dt>已确认索引</dt><dd>{{result.summary.synced}}</dd></div><div><dt>等待索引</dt><dd>{{result.summary.pending}}</dd></div><div><dt>索引失败</dt><dd>{{result.summary.failed}}</dd></div><div><dt>待清理</dt><dd>{{result.summary.cleanupPending}}</dd></div></dl>
   <p v-if="!result.items.length" class="index-help">当前筛选没有索引记录。</p>
   <ul v-else class="index-list">
    <li v-for="row in result.items" :key="row.id">
     <div class="index-facts"><strong>{{row.title}}</strong><span>v{{row.version}} · {{row.status==='ACTIVE'?indexNames[row.indexState]:'已撤回，禁止引用'}}</span><span v-if="row.status==='ACTIVE'">{{checkNames[row.checkState]||'核验状态未知'}}</span><small v-if="row.checkedAt">最近核验 {{time(row.checkedAt)}}</small><small v-if="row.status==='WITHDRAWN'">{{row.cleanupState==='DONE'?'向量清理已确认':'向量清理尚未确认'}}</small></div>
     <div class="index-actions" v-if="row.status==='ACTIVE'"><el-button :disabled="loading||busy" @click="action(row,'check')">核验此版本</el-button><el-button :disabled="loading||busy" @click="action(row,'repair')">修复此版本</el-button></div>
     <el-button v-else-if="['PENDING','FAILED'].includes(row.cleanupState)" :disabled="loading||busy" @click="action(row,'withdraw')">重试清理</el-button>
    </li>
   </ul>
   <el-pagination v-if="result.total>20" class="index-pages" prev-text="上一页" next-text="下一页" layout="prev, pager, next" :pager-count="5" :current-page="page" :page-size="20" :total="result.total" :disabled="loading||busy" @current-change="value=>{page=value;load();}" />
  </template>
 </details>
</template>

<style scoped>
.index-operations{border-block:1px solid var(--line,#e3e6df);padding:16px 0;margin:16px 0 24px;font-size:13px;line-height:1.7;}
summary{font-weight:600;cursor:pointer;width:fit-content;}summary:focus-visible{outline:2px solid var(--brand,#b3442b);outline-offset:4px;}
.index-help{color:var(--muted,#6b736b);max-width:75ch;}
.index-tools,.index-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap;}.index-tools{justify-content:space-between;margin:14px 0;}
.index-summary{display:flex;flex-wrap:wrap;gap:14px 28px;margin:18px 0;}.index-summary>div{display:flex;gap:10px;}.index-summary dt{color:var(--muted,#6b736b);}.index-summary dd{margin:0;font-variant-numeric:tabular-nums;font-weight:600;}
.index-list{list-style:none;padding:0;margin:0;}.index-list>li{padding:16px 0;border-top:1px solid var(--line,#e3e6df);display:flex;align-items:center;justify-content:space-between;gap:16px;}
.index-facts{min-width:0;display:flex;flex-direction:column;overflow-wrap:anywhere;}.index-facts small{color:var(--muted,#6b736b);}.index-pages{margin-top:16px;}
@media(max-width:760px){.index-list>li{align-items:stretch;flex-direction:column;}.index-actions{gap:8px;}.index-actions :deep(.el-button+.el-button){margin-left:0;}.index-summary{gap:8px 18px;}}
</style>
