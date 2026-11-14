<script setup lang="ts">
import{computed,onBeforeUnmount,ref,watch}from'vue';
import{ElAlert,ElButton,ElCheckbox,ElOption,ElPagination,ElSelect,ElSkeleton}from'element-plus';
import{api,time,type User}from'../api';
interface Item{id:string;mode:string;latencyMs:number|null;citationCount:number|null;storedProposal:boolean;ticketId:number|null;handoffRequested:boolean;createdAt:string}
interface Result{items:Item[];total:number;page:number;size:number;hours:number;asOf:string;summary:{total:number;rag:number;retrievalOnly:number;disabled:number;error:number;unknown:number;linkedTickets:number;handoffRequests:number;withProposal:number;averageLatencyMs:number|null}}
interface Health{status:'NOT_CONFIGURED'|'REACHABLE'|'UNAVAILABLE';mode:string|null;retrievalReady:boolean|null;llmConfigured:boolean|null;checkedAt:string}
const props=defineProps<{user:User}>(),emit=defineEmits<{'open-ticket':[id:number]}>();
const admin=computed(()=>props.user.role==='ADMIN');
const result=ref<Result|null>(null),health=ref<Health|null>(null),loading=ref(false),healthLoading=ref(false),error=ref(''),healthError=ref('');
const hours=ref(24),mode=ref(''),linkedOnly=ref(false),page=ref(1);
const modes:Record<string,string>={rag:'知识辅助生成',retrieval_only:'仅检索原文',disabled:'未启用',error:'调用异常',unknown:'未识别记录'};
let request=0,healthRequest=0;onBeforeUnmount(()=>{request++;healthRequest++;});
async function load(){
 if(!admin.value)return;const current=++request;loading.value=true;error.value='';result.value=null;
 try{const params=new URLSearchParams({hours:String(hours.value),page:String(page.value),size:'20',mode:mode.value,linkedOnly:String(linkedOnly.value)});const r=await api<Result>('/operations/ai-runs?'+params);if(current!==request)return;
  const last=Math.max(1,Math.ceil(r.total/20));if(page.value>last){page.value=last;await load();return;}result.value=r;
 }catch(e){if(current===request)error.value=e instanceof Error?e.message:'调用记录暂不可用';}finally{if(current===request)loading.value=false;}
}
async function checkHealth(){
 if(!admin.value)return;const current=++healthRequest;healthLoading.value=true;health.value=null;healthError.value='';
 try{const r=await api<Health>('/operations/ai-health');if(current===healthRequest)health.value=r;}
 catch(e){if(current===healthRequest)healthError.value=e instanceof Error?e.message:'健康核验暂不可用';}
 finally{if(current===healthRequest)healthLoading.value=false;}
}
function filter(){page.value=1;load();}
watch(()=>[props.user.id,props.user.role],()=>{request++;healthRequest++;result.value=health.value=null;error.value=healthError.value='';loading.value=healthLoading.value=false;page.value=1;hours.value=24;mode.value='';linkedOnly.value=false;if(admin.value){load();checkHealth();}},{immediate:true});
</script>

<template>
 <section class="ai-operations" aria-label="AI运行观测">
  <el-alert v-if="!admin" title="AI运行观测仅向管理员开放" type="info" :closable="false" />
  <template v-else>
   <header class="ai-ops-heading"><div><h1>AI运行记录</h1><p>从调用记录定位降级与建单关联。这里只展示元数据，不展示客户咨询、回答或引用正文。</p></div><el-button :loading="loading" @click="load">刷新运行记录</el-button></header>
   <section class="ai-health" aria-label="AI服务核验"><div class="ai-health-heading"><h2>服务核验</h2><el-button :loading="healthLoading" @click="checkHealth">检查AI服务</el-button></div>
    <el-alert v-if="healthError" :title="healthError" type="error" :closable="false" role="alert" />
    <p v-if="healthLoading" role="status">正在检查健康接口…</p>
    <template v-if="health"><strong>{{health.status==='REACHABLE'?'健康接口可达':health.status==='NOT_CONFIGURED'?'后端尚未启用AI接入':'健康接口未确认可达'}}</strong><div class="ai-health-facts"><span v-if="health.mode">服务报告模式：{{modes[health.mode]||'未识别'}}</span><span>{{health.retrievalReady===null?'检索状态未确认':health.retrievalReady?'检索服务报告就绪':'检索服务未就绪'}}</span><span>{{health.llmConfigured===null?'模型配置状态未确认':health.llmConfigured?'语言模型已配置':'未配置语言模型'}}</span><span>核验 {{time(health.checkedAt)}}</span></div></template>
    <p class="ai-ops-note">健康核验不调用语言模型，也不验证服务鉴权或问答质量。历史统计不代表模型准确率。</p>
   </section>
   <div class="ai-ops-filters"><div><label for="ai-window">统计时间范围</label><el-select id="ai-window" v-model="hours" aria-label="统计时间范围" :disabled="loading" @change="filter"><el-option label="近1小时" :value="1"/><el-option label="近24小时" :value="24"/><el-option label="近7天" :value="168"/></el-select></div><div><label for="ai-mode">当时模式</label><el-select id="ai-mode" v-model="mode" placeholder="全部模式" aria-label="当时模式" :disabled="loading" @change="filter"><el-option label="全部模式" value=""/><el-option v-for="(label,value) in modes" :key="value" :label="label" :value="value"/></el-select></div><el-checkbox v-model="linkedOnly" :disabled="loading" @change="filter">仅看已关联工单</el-checkbox></div>
   <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
   <el-skeleton v-if="loading" :rows="6" animated />
   <template v-if="result">
    <dl class="ai-ops-summary" aria-label="时间窗口运行汇总"><div><dt>记录</dt><dd>{{result.summary.total}}</dd></div><div><dt>知识生成</dt><dd>{{result.summary.rag}}</dd></div><div><dt>仅检索</dt><dd>{{result.summary.retrievalOnly}}</dd></div><div><dt>未启用</dt><dd>{{result.summary.disabled}}</dd></div><div><dt>异常</dt><dd>{{result.summary.error}}</dd></div><div><dt>未识别</dt><dd>{{result.summary.unknown}}</dd></div><div><dt>关联工单</dt><dd>{{result.summary.linkedTickets}}</dd></div><div><dt>记录平均耗时</dt><dd>{{result.summary.averageLatencyMs===null?'暂无记录':result.summary.averageLatencyMs+' ms'}}</dd></div></dl>
    <p class="ai-ops-note">截至 {{time(result.asOf)}} · {{result.total}} 条筛选结果。汇总基于所选时间窗口，不受模式和工单筛选影响。原始提案记录不代表当前可执行。</p>
    <p v-if="!result.items.length" class="ai-ops-empty">当前筛选没有调用记录。</p>
    <ol v-else class="ai-run-list"><li v-for="run in result.items" :key="run.id"><div class="ai-run-main"><strong>{{run.id}}</strong><span>{{time(run.createdAt)}} · {{modes[run.mode]||'未识别记录'}}</span><span>{{run.latencyMs===null?'耗时未记录':run.latencyMs+' ms'}} · {{run.citationCount===null?'引用数未记录':run.citationCount+' 条引用'}} · {{run.storedProposal?'原始日志含提案':'原始日志无提案'}}</span><span v-if="run.handoffRequested">已提交人工接管</span></div><el-button v-if="run.ticketId!==null" text @click="emit('open-ticket',run.ticketId)">查看工单 #{{run.ticketId}}</el-button><span v-else class="ai-ops-note">暂无工单关联</span></li></ol>
    <el-pagination class="ai-ops-pages" layout="prev, pager, next" prev-text="上一页" next-text="下一页" :pager-count="5" :current-page="page" :page-size="20" :total="result.total" :disabled="loading" @current-change="value=>{page=value;load();}" />
   </template>
  </template>
 </section>
</template>

<style scoped>
.ai-operations{width:100%;box-sizing:border-box;max-width:1320px;margin:0 auto;padding:30px 32px;}.ai-ops-heading,.ai-health-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:20px;}.ai-ops-heading h1{font-size:25px;font-weight:600;margin:0 0 10px;}.ai-ops-heading p,.ai-ops-note{color:var(--muted,#6b736b);font-size:13px;line-height:1.8;max-width:85ch;}.ai-health{border-block:1px solid var(--line,#e3e6df);padding:20px 0;margin:22px 0;}.ai-health-heading{align-items:center;margin-bottom:14px;}.ai-health h2{font-size:15px;font-weight:600;margin:0;}.ai-health>strong{font-size:14px;font-weight:500;}.ai-health-facts{display:flex;flex-wrap:wrap;gap:8px 24px;margin-top:8px;font-size:12px;color:var(--muted,#6b736b);}
.ai-ops-filters{display:flex;align-items:end;gap:16px;flex-wrap:wrap;margin:24px 0;}.ai-ops-filters>div{width:180px;}.ai-ops-filters label{display:block;font-size:12px;margin-bottom:8px;color:var(--muted,#6b736b);}.ai-ops-summary{display:flex;flex-wrap:wrap;gap:12px 26px;margin:20px 0;font-size:13px;}.ai-ops-summary>div{display:flex;gap:10px;}.ai-ops-summary dt{color:var(--muted,#6b736b);}.ai-ops-summary dd{margin:0;font-weight:600;font-variant-numeric:tabular-nums;}
.ai-run-list{list-style:none;padding:0;margin:20px 0;}.ai-run-list>li{border-top:1px solid var(--line,#e3e6df);padding:20px 0;display:flex;justify-content:space-between;align-items:center;gap:18px;}.ai-run-main{display:flex;flex-direction:column;gap:8px;min-width:0;overflow-wrap:anywhere;}.ai-run-main strong{font-size:14px;font-weight:500;}.ai-run-main span{font-size:12px;color:var(--muted,#6b736b);}.ai-ops-empty{padding:40px 0;font-size:14px;color:var(--muted,#6b736b);}.ai-ops-pages{margin-top:24px;}
@media(max-width:760px){.ai-operations{padding:22px 16px;}.ai-ops-heading{flex-direction:column;gap:8px;}.ai-ops-heading h1{font-size:23px;}.ai-ops-filters{gap:12px;}.ai-ops-filters>div{width:calc(50% - 6px);}.ai-run-list>li{flex-direction:column;align-items:flex-start;}.ai-ops-summary{gap:10px 18px;}}
</style>
