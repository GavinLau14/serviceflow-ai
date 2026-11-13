<script setup lang="ts">
import { computed,onBeforeUnmount,ref,watch } from 'vue';
import { ElAlert,ElButton,ElInput,ElPagination,ElSkeleton } from 'element-plus';
import { api,time,statusNames,priorityNames,type User,type Status } from '../api';
type Filter='BREACHED'|'DUE_SOON'|'WAITING_CUSTOMER'|'ALL';
interface Item {id:number;reference:string;title:string;status:Status;priority:string;assigneeName:string|null;nextDue:string|null;firstResponseBreached:boolean;resolutionBreached:boolean;resolutionPaused:boolean;pausedSeconds:number;escalatedAt:string|null}
interface Result {items:Item[];total:number;page:number;size:number;asOf:string;dueSoonMinutes:number;summary:{active:number;breached:number;dueSoon:number;waitingCustomer:number;unassigned:number}}
const props=defineProps<{user:User}>();const emit=defineEmits<{'open-ticket':[id:number]}>();
const staff=computed(()=>['AGENT','SUPERVISOR','ADMIN'].includes(props.user.role));
const result=ref<Result|null>(null),filter=ref<Filter>('BREACHED'),page=ref(1),input=ref(''),query=ref(''),loading=ref(false),error=ref('');
const filters:{value:Filter;label:string}[]=[{value:'BREACHED',label:'已超时'},{value:'DUE_SOON',label:'30分钟内到期'},{value:'WAITING_CUSTOMER',label:'等待客户'},{value:'ALL',label:'全部处理中'}];
let generation=0;onBeforeUnmount(()=>generation++);
async function load(){
 if(!staff.value)return;
 const current=++generation;loading.value=true;error.value='';result.value=null;
 try{const params=new URLSearchParams({page:String(page.value),size:'20',filter:filter.value,q:query.value});const r=await api<Result>('/operations/sla-inbox?'+params);if(current!==generation)return;
  const last=Math.max(1,Math.ceil(r.total/20));if(page.value>last){page.value=last;await load();return;}result.value=r;
 }catch(e){if(current===generation)error.value=e instanceof Error?e.message:'服务时限查询失败';}
 finally{if(current===generation)loading.value=false;}
}
watch(()=>[props.user.id,props.user.role,props.user.teamId],()=>{generation++;result.value=null;filter.value='BREACHED';page.value=1;input.value=query.value='';error.value='';loading.value=false;load();},{immediate:true});
function choose(value:Filter){if(loading.value)return;filter.value=value;page.value=1;load();}
function search(){page.value=1;query.value=input.value.trim();load();}
</script>

<template>
 <section class="sla-inbox" aria-label="服务时限队列" :aria-busy="loading">
  <el-alert v-if="!staff" title="服务时限仅向授权服务人员开放" type="info" :closable="false" />
  <template v-else>
   <header class="sla-heading"><div><h1>服务时限</h1><p>先处理最早到期的问题。队列和计数仅包含你有权查看的未关闭工单。</p></div><el-button :loading="loading" @click="load">刷新时限队列</el-button></header>
   <dl v-if="result" class="sla-summary" aria-label="当前范围服务时限汇总"><div><dt>处理中</dt><dd>{{result.summary.active}}</dd></div><div><dt>已超时</dt><dd>{{result.summary.breached}}</dd></div><div><dt>30分钟临期</dt><dd>{{result.summary.dueSoon}}</dd></div><div><dt>等待客户</dt><dd>{{result.summary.waitingCustomer}}</dd></div><div><dt>待分派</dt><dd>{{result.summary.unassigned}}</dd></div></dl>
   <div class="sla-filter" role="group" aria-label="时限状态筛选"><el-button v-for="option in filters" :key="option.value" :type="filter===option.value?'primary':'default'" :aria-pressed="filter===option.value" :disabled="loading" @click="choose(option.value)">{{option.label}}</el-button></div>
   <form class="sla-search" @submit.prevent="search"><el-input v-model="input" aria-label="检索时限工单" placeholder="标题或工单编号" maxlength="100" clearable :disabled="loading"/><el-button native-type="submit" :disabled="loading">检索</el-button></form>
   <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
   <el-skeleton v-if="loading" :rows="6" animated />
   <template v-if="result">
    <p class="sla-asof">截至 {{time(result.asOf)}} · {{result.total}} 条筛选结果。汇总不受关键词影响，记录不会自动实时刷新。</p>
    <p v-if="!result.items.length" class="sla-empty">当前筛选没有工单。可以切换状态或检索条件。</p>
    <ol v-else class="sla-list">
     <li v-for="row in result.items" :key="row.id"><button class="sla-row" @click="emit('open-ticket',row.id)">
      <div class="sla-case"><span class="sla-reference">{{row.reference}} · {{statusNames[row.status]}}</span><strong>{{row.title}}</strong><span>{{row.assigneeName||'待分派'}} · {{priorityNames[row.priority]||row.priority}}优先级</span></div>
      <div class="sla-deadline"><strong v-if="row.firstResponseBreached||row.resolutionBreached" class="sla-breach">{{row.firstResponseBreached?'首次响应已超时':'解决已超时'}}</strong><strong v-else-if="row.resolutionPaused">等待客户，恢复后顺延</strong><strong v-else>下一截止 {{row.nextDue?time(row.nextDue):'暂未记录'}}</strong><span v-if="row.nextDue&&(row.firstResponseBreached||row.resolutionBreached)">截止 {{time(row.nextDue)}}</span><span v-if="row.escalatedAt">已触发超时升级</span><span class="sla-open">查看工单 →</span></div>
     </button></li>
    </ol>
    <el-pagination class="sla-pages" prev-text="上一页" next-text="下一页" layout="prev, pager, next" :pager-count="5" :current-page="page" :page-size="20" :total="result.total" :disabled="loading" @current-change="value=>{page=value;load();}" />
   </template>
  </template>
 </section>
</template>

<style scoped>
.sla-inbox{width:100%;box-sizing:border-box;padding:30px 32px;max-width:1320px;margin:0 auto;}.sla-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:20px;margin-bottom:24px;}.sla-heading h1{font-size:25px;margin:0 0 10px;font-weight:600;}.sla-heading p,.sla-asof{font-size:13px;color:var(--muted,#6b736b);line-height:1.8;max-width:75ch;}
.sla-summary{display:flex;flex-wrap:wrap;gap:12px 30px;border-block:1px solid var(--line,#e3e6df);padding:16px 0;margin:0 0 22px;font-size:13px;}.sla-summary>div{display:flex;gap:10px;}.sla-summary dt{color:var(--muted,#6b736b);}.sla-summary dd{margin:0;font-weight:600;font-variant-numeric:tabular-nums;}
.sla-filter{display:flex;gap:10px;flex-wrap:wrap;}.sla-filter :deep(.el-button+.el-button){margin-left:0;}.sla-search{display:flex;gap:12px;margin:18px 0;max-width:600px;}.sla-search :deep(.el-input){min-width:0;}
.sla-list{list-style:none;padding:0;margin:20px 0;}.sla-list>li{border-top:1px solid var(--line,#e3e6df);}.sla-row{width:100%;border:0;background:transparent;color:inherit;text-align:left;padding:20px 12px;display:flex;gap:22px;justify-content:space-between;font:inherit;cursor:pointer;}.sla-row:hover{background:var(--selected,#f1f4ec);}.sla-row:focus-visible{outline:2px solid var(--brand,#b3442b);outline-offset:-2px;}.sla-case,.sla-deadline{display:flex;flex-direction:column;gap:8px;min-width:0;overflow-wrap:anywhere;}.sla-case strong{font-size:16px;font-weight:600;}.sla-case span,.sla-deadline span{font-size:12px;color:var(--muted,#6b736b);}.sla-deadline{flex-shrink:0;text-align:right;font-size:13px;}.sla-deadline strong{font-weight:500;}.sla-deadline .sla-breach{color:#ab3b2b;}.sla-deadline .sla-open{color:var(--brand,#b3442b);}.sla-empty{padding:50px 0;color:var(--muted,#6b736b);font-size:14px;}.sla-pages{margin-top:24px;}
@media(max-width:760px){.sla-inbox{padding:22px 16px;}.sla-heading{flex-direction:column;gap:4px;}.sla-heading h1{font-size:23px;}.sla-heading p{margin-bottom:10px;}.sla-row{flex-direction:column;gap:16px;padding:18px 4px;}.sla-deadline{flex-shrink:1;text-align:left;}.sla-summary{gap:10px 20px;}.sla-filter{gap:8px;}}
</style>
