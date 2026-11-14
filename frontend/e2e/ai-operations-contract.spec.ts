import{test,expect,type Page}from'@playwright/test';
const id='2d21c5c2-19ac-41f7-a27b-66b03cfe4278';
const records={items:[{id,mode:'retrieval_only',latencyMs:120,citationCount:2,storedProposal:false,ticketId:91,handoffRequested:true,createdAt:'2030-01-02T00:00:00'}],total:21,page:1,size:20,hours:24,asOf:'2030-01-02T00:00:00',summary:{total:21,rag:0,retrievalOnly:20,disabled:0,error:1,unknown:0,linkedTickets:1,handoffRequests:1,withProposal:0,averageLatencyMs:120}};
const health={status:'REACHABLE',mode:'retrieval_only',retrievalReady:true,llmConfigured:false,checkedAt:'2030-01-02T00:00:00'};
async function fixture(page:Page,role='ADMIN',app=false){
 let failRecords=false,failHealth=false;const urls:string[]=[];
 await page.route('**/api/**',r=>{const u=new URL(r.request().url());
  if(u.pathname==='/api/operations/ai-runs'){urls.push(u.search);return failRecords?r.fulfill({status:503,json:{message:'调用记录暂不可用'}}):r.fulfill({json:{...records,hours:Number(u.searchParams.get('hours')||24),page:Number(u.searchParams.get('page')||1)}});}
  if(u.pathname==='/api/operations/ai-health')return r.fulfill({json:failHealth?{status:'UNAVAILABLE',mode:null,retrievalReady:null,llmConfigured:null,checkedAt:health.checkedAt}:health});
  return r.fulfill({json:u.pathname==='/api/auth/me'?{id:6,username:'fixture',displayName:'测试账号',role,teamId:1}:u.pathname==='/api/capabilities'?{milestone:'M4',aiMode:'enabled',ragEnabled:true,message:'fixture'}:u.pathname==='/api/tickets'?{items:[],total:0,page:1,size:20}:[]});
 });
 if(app){await page.addInitScript(()=>sessionStorage.setItem('sf_token','fixture-ai-ops'));await page.goto('/');}
 else{await page.route('**/ai-operations-contract',r=>r.fulfill({contentType:'text/html',body:`<html lang="zh-CN"><head><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/node_modules/element-plus/dist/index.css"></head><body><div id="app"></div><output id="selected"></output><script type="module">import{createApp}from'/node_modules/.vite/deps/vue.js';import C from'/src/components/AiOperations.vue';createApp(C,{user:{id:6,role:'${role}'},onOpenTicket:id=>document.getElementById('selected').textContent=id}).mount('#app');</script></body></html>`}));await page.goto('/ai-operations-contract');}
 return{urls,failRecords:()=>failRecords=true,failHealth:(fail:boolean)=>failHealth=fail};
}
test('AI operations uses server filters/pages and opens only linked tickets',async({page})=>{
 const f=await fixture(page);
 await expect(page.locator('.ai-run-list').getByText(id,{exact:true})).toBeVisible();
 await page.getByRole('button',{name:'下一页',exact:true}).click();
 await expect.poll(()=>f.urls.at(-1)).toContain('page=2');
 async function choose(label:string,option:string){
  const box=page.getByRole('combobox',{name:label,exact:true});
  await expect(box).toBeEnabled();
  await page.locator('.el-select').filter({has:box}).click();
  await page.getByRole('option',{name:option,exact:true}).click();
 }
 await choose('统计时间范围','近7天');
 await expect.poll(()=>f.urls.at(-1)).toContain('hours=168');
 await choose('当时模式','调用异常');
 await expect.poll(()=>f.urls.at(-1)).toContain('mode=error');
 await expect(page.getByRole('checkbox',{name:'仅看已关联工单',exact:true})).toBeEnabled();
 await page.getByText('仅看已关联工单',{exact:true}).click();
 await expect.poll(()=>f.urls.at(-1)).toContain('linkedOnly=true');
 expect(f.urls.at(-1)).toContain('page=1');
 await page.getByRole('button',{name:'查看工单 #91',exact:true}).click();
 await expect(page.locator('#selected')).toHaveText('91');
});
test('health failures remain separate from historical records and do not claim model quality',async({page})=>{
 const f=await fixture(page);await expect(page.getByText('健康接口可达',{exact:true})).toBeVisible();await expect(page.getByText('未配置语言模型',{exact:true})).toBeVisible();await expect(page.getByText('服务报告模式：仅检索原文',{exact:true})).toBeVisible();f.failHealth(true);await page.getByRole('button',{name:'检查AI服务',exact:true}).click();await expect(page.getByText('健康接口未确认可达',{exact:true})).toBeVisible();await expect(page.locator('.ai-run-list').getByText(id,{exact:true})).toBeVisible();f.failHealth(false);await page.getByRole('button',{name:'检查AI服务',exact:true}).click();await expect(page.getByText('健康接口可达',{exact:true})).toBeVisible();
});
test('failed record refresh clears old metadata and non-admin performs no global queries',async({page})=>{
 const f=await fixture(page);await expect(page.locator('.ai-run-list').getByText(id,{exact:true})).toBeVisible();f.failRecords();await page.getByRole('button',{name:'刷新运行记录',exact:true}).click();await expect(page.getByText('调用记录暂不可用',{exact:true})).toBeVisible();await expect(page.locator('.ai-run-list')).toHaveCount(0);
});
test('team staff cannot load the global AI operations console',async({page})=>{
 const f=await fixture(page,'SUPERVISOR');await expect(page.getByRole('button',{name:'检查AI服务',exact:true})).toHaveCount(0);expect(f.urls).toHaveLength(0);
});
test('actual app has an admin phone entry and metadata fits the viewport',async({page})=>{
 await page.setViewportSize({width:390,height:844});await fixture(page,'ADMIN',true);const entry=page.locator('.tool-rail').getByRole('button',{name:'AI运行',exact:true});await expect(entry).toBeVisible();await entry.click();await expect(page.locator('.ai-run-list').getByText(id,{exact:true})).toBeVisible();expect(await page.locator('.ai-operations').evaluate(e=>e.scrollWidth<=e.clientWidth)).toBeTruthy();
});

test('late responses from a previous account cannot replace the new account loading state',async({page})=>{
 let oldRelease!:()=>void,newRelease!:()=>void;const oldGate=new Promise<void>(r=>oldRelease=r),newGate=new Promise<void>(r=>newRelease=r);const counts={runs:0,health:0};
 await page.route('**/api/**',async route=>{const isRuns=new URL(route.request().url()).pathname.endsWith('/ai-runs');const n=++counts[isRuns?'runs':'health'];await(n===1?oldGate:newGate);await route.fulfill({json:isRuns?{...records,items:[{...records.items[0],id:n===1?'old-account-run':'new-account-run'}]}:n===1?{...health,status:'UNAVAILABLE'}:health});});
 await page.route('**/ai-account-race',r=>r.fulfill({contentType:'text/html',body:`<html><body><div id="app"></div><script type="module">import{createApp,h,reactive}from'/node_modules/.vite/deps/vue.js';import C from'/src/components/AiOperations.vue';const user=reactive({id:6,role:'ADMIN'});window.changeUser=(id,role)=>{user.id=id;user.role=role};createApp({render:()=>h(C,{user})}).mount('#app');</script></body></html>`}));
 await page.goto('/ai-account-race');await expect.poll(()=>counts.runs+counts.health).toBe(2);await page.evaluate(()=>{(window as any).changeUser(5,'SUPERVISOR')});await expect(page.getByRole('button',{name:'检查AI服务',exact:true})).toHaveCount(0);await page.evaluate(()=>{(window as any).changeUser(9,'ADMIN')});await expect.poll(()=>counts.runs+counts.health).toBe(4);
 const oldRuns=page.waitForResponse(r=>r.url().includes('/ai-runs')),oldHealth=page.waitForResponse(r=>r.url().endsWith('/ai-health'));oldRelease();for(const r of await Promise.all([oldRuns,oldHealth]))await r.finished();await page.evaluate(()=>new Promise<void>(resolve=>requestAnimationFrame(()=>requestAnimationFrame(()=>resolve()))));
 await expect(page.getByText('old-account-run',{exact:true})).toHaveCount(0);await expect(page.getByText('正在检查健康接口…',{exact:true})).toBeVisible();await expect(page.locator('.ai-run-list')).toHaveCount(0);newRelease();await expect(page.getByText('new-account-run',{exact:true})).toBeVisible();await expect(page.getByText('健康接口可达',{exact:true})).toBeVisible();
});
