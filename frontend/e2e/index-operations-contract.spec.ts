import {expect,test,type Page} from '@playwright/test';
const id='2d21c5c2-19ac-41f7-a27b-66b03cfe4278';
async function mount(page:Page,role='ADMIN'){
 let state='SYNCED',checkState='UNKNOWN',fail=false;const calls:string[]=[];
 await page.route('**/api/**',async r=>{
  const url=new URL(r.request().url());calls.push(r.request().method()+' '+url.pathname);
  if(r.request().method()==='GET'){
   if(fail)return r.fulfill({status:503,json:{message:'索引状态暂不可用'}});
   return r.fulfill({json:{items:[{id,knowledgeId:8,version:2,title:'公开光栅记录',status:'ACTIVE',indexState:state,cleanupState:'NONE',checkState,checkedAt:checkState==='UNKNOWN'?null:'2026-11-13T06:00:00'}],total:21,page:Number(url.searchParams.get('page')),size:20,summary:{active:1,synced:state==='SYNCED'?1:0,pending:0,failed:state==='FAILED'?1:0,cleanupPending:0}}});
  }
  expect(r.request().postDataJSON()).toEqual({publicationId:id,confirmed:true});
  if(url.pathname.endsWith('/check')){state='FAILED';checkState='MISSING';}else if(url.pathname.endsWith('/repair')){state='SYNCED';checkState='UNKNOWN';}
  return r.fulfill({json:{id,indexState:state}});
 });
 await page.route('**/index-operations-contract',r=>r.fulfill({contentType:'text/html',body:`<html lang="zh-CN"><head><link rel="stylesheet" href="/node_modules/element-plus/dist/index.css"></head><body><div id="app"></div><script type="module">import{createApp}from'/node_modules/.vite/deps/vue.js';import C from'/src/components/IndexOperations.vue';createApp(C,{user:{id:6,role:'${role}'}}).mount('#app');</script></body></html>`}));
 await page.goto('/index-operations-contract');return{calls,fail:()=>{fail=true;}};
}
test('inspection detects missing points and repair requires confirmation without republishing',async({page})=>{
 const f=await mount(page);expect(f.calls).toHaveLength(0);await page.getByText('索引运行状态',{exact:true}).click();
 await expect(page.getByText('尚未核验',{exact:true})).toBeVisible();await page.getByRole('button',{name:'核验此版本',exact:true}).click();
 await expect(page.getByText('向量点缺失',{exact:true})).toBeVisible();await page.getByRole('button',{name:'修复此版本',exact:true}).click();
 await page.getByRole('button',{name:'取消',exact:true}).click();expect(f.calls.filter(x=>x.endsWith('/repair'))).toHaveLength(0);
 await page.getByRole('button',{name:'修复此版本',exact:true}).click();await page.getByRole('button',{name:'确认修复',exact:true}).click();await expect(page.getByText('尚未核验',{exact:true})).toBeVisible();
 expect(f.calls.filter(x=>x.endsWith('/repair'))).toHaveLength(1);expect(f.calls.some(x=>x==='POST /api/knowledge/8/publication')).toBeFalsy();
});
test('failed refresh clears stale operations and non-admin never requests metadata',async({page})=>{
 const f=await mount(page);await page.getByText('索引运行状态',{exact:true}).click();await expect(page.getByText('公开光栅记录',{exact:true})).toBeVisible();f.fail();await page.getByRole('button',{name:'刷新索引状态',exact:true}).click();await expect(page.getByText('索引状态暂不可用',{exact:true})).toBeVisible();await expect(page.getByText('公开光栅记录',{exact:true})).toHaveCount(0);
});
test('team staff cannot see or operate the global index console',async({page})=>{
 const f=await mount(page,'SUPERVISOR');await expect(page.getByText('索引运行状态',{exact:true})).toHaveCount(0);expect(f.calls).toHaveLength(0);
});
