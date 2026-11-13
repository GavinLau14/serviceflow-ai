import{test,expect,type Page}from'@playwright/test';
async function mount(page:Page,role='SUPERVISOR'){
 let fail=false;const urls:string[]=[];
 await page.route('**/api/**',async r=>{
  const u=new URL(r.request().url());urls.push(u.search);if(fail)return r.fulfill({status:503,json:{message:'服务时限查询暂不可用'}});
  const current=Number(u.searchParams.get('page')||1),filter=u.searchParams.get('filter');
  return r.fulfill({json:{items:[{id:42,reference:'SF-2042',title:'虚构设备故障',status:filter==='WAITING_CUSTOMER'?'WAITING_CUSTOMER':'IN_PROGRESS',priority:'HIGH',assigneeName:'客服甲',version:2,nextDue:'2030-01-01T00:00:00',firstResponseBreached:false,resolutionBreached:filter==='BREACHED',resolutionPaused:filter==='WAITING_CUSTOMER',pausedSeconds:120}],total:21,page:current,size:20,asOf:'2030-01-01T00:10:00',dueSoonMinutes:30,summary:{active:21,breached:4,dueSoon:3,waitingCustomer:2,unassigned:1}}});
 });
 await page.route('**/sla-inbox-contract',r=>r.fulfill({contentType:'text/html',body:`<html lang="zh-CN"><head><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/node_modules/element-plus/dist/index.css"></head><body><div id="app"></div><output id="selected"></output><script type="module">import{createApp}from'/node_modules/.vite/deps/vue.js';import C from'/src/components/SlaInbox.vue';createApp(C,{user:{id:5,role:'${role}',teamId:1},onOpenTicket:id=>document.getElementById('selected').textContent=id}).mount('#app');</script></body></html>`}));
 await page.goto('/sla-inbox-contract');return{urls,fail:()=>{fail=true;}};
}
test('SLA risk filters and pagination use server results and open the original ticket',async({page})=>{
 const f=await mount(page);await expect(page.getByText('虚构设备故障',{exact:true})).toBeVisible();expect(f.urls[0]).toContain('filter=BREACHED');
 await page.getByRole('button',{name:'下一页',exact:true}).click();await expect.poll(()=>f.urls.some(u=>u.includes('page=2'))).toBeTruthy();
 await page.getByRole('button',{name:'30分钟内到期',exact:true}).click();await expect.poll(()=>f.urls.at(-1)).toContain('filter=DUE_SOON');expect(f.urls.at(-1)).toContain('page=1');
 await page.getByLabel('检索时限工单',{exact:true}).fill('%_');await page.getByRole('button',{name:'检索',exact:true}).click();await expect.poll(()=>f.urls.at(-1)).toContain('q=%25_');
 await page.getByRole('button',{name:/虚构设备故障/}).click();await expect(page.locator('#selected')).toHaveText('42');
});
test('SLA query failures clear stale rows and customer never queries internal SLA',async({page})=>{
 const f=await mount(page);await expect(page.getByText('虚构设备故障',{exact:true})).toBeVisible();f.fail();await page.getByRole('button',{name:'刷新时限队列',exact:true}).click();await expect(page.getByText('服务时限查询暂不可用',{exact:true})).toBeVisible();await expect(page.getByText('虚构设备故障',{exact:true})).toHaveCount(0);
});
test('customer has no internal risk data requests',async({page})=>{
 const f=await mount(page,'CUSTOMER');await expect(page.getByRole('button',{name:'刷新时限队列',exact:true})).toHaveCount(0);expect(f.urls).toHaveLength(0);
});
test('phone risk queue retains filters and ticket access without horizontal overflow',async({page})=>{
 await page.setViewportSize({width:390,height:844});await mount(page);await expect(page.getByText('虚构设备故障',{exact:true})).toBeVisible();expect(await page.locator('.sla-inbox').evaluate(e=>e.scrollWidth<=e.clientWidth)).toBeTruthy();await page.getByRole('button',{name:'等待客户',exact:true}).click();await expect(page.getByText('等待客户，恢复后顺延',{exact:true})).toBeVisible();
});

test('ticket SLA explains accumulated wait and removes old facts after a failed refresh',async({page})=>{
 let failed=false;
 await page.route('**/api/**',r=>r.request().url().endsWith('/audit')?r.fulfill({json:[]}):failed?r.fulfill({status:503,json:{message:'时限记录暂不可用'}}):r.fulfill({json:{ticketId:42,firstResponseDue:'2030-01-01T01:00:00',resolutionDue:'2030-01-02T00:00:00',firstRespondedAt:'2030-01-01T00:00:00',resolutionPaused:true,resolutionPausedAt:'2030-01-01T00:00:00',pausedSeconds:120,asOf:'2030-01-01T00:02:00',firstResponseBreached:false,resolutionBreached:false,escalatedAt:null}}));
 await page.route('**/sla-detail-contract',r=>r.fulfill({contentType:'text/html',body:`<html lang="zh-CN"><body><div id="app"></div><script type="module">import{createApp}from'/node_modules/.vite/deps/vue.js';import C from'/src/components/TicketOperations.vue';createApp(C,{id:42,user:{id:3,role:'AGENT'},status:'WAITING_CUSTOMER'}).mount('#app');</script></body></html>`}));
 await page.goto('/sla-detail-contract');await expect(page.getByText(/累计等待客户 120 秒/)).toBeVisible();await expect(page.getByText('当前显示已结算截止时间，恢复处理时按本轮等待时长顺延。',{exact:true})).toBeVisible();failed=true;await page.getByRole('button',{name:'刷新记录',exact:true}).click();await expect(page.getByText('时限记录暂不可用',{exact:true})).toBeVisible();await expect(page.getByText(/累计等待客户 120 秒/)).toHaveCount(0);
});

test('actual app phone navigation reaches SLA queue through the persistent tool rail',async({page})=>{
 await page.setViewportSize({width:390,height:844});await page.addInitScript(()=>sessionStorage.setItem('sf_token','fixture-sla-navigation'));
 await page.route('**/api/**',r=>{const path=new URL(r.request().url()).pathname;return r.fulfill({json:path==='/api/auth/me'?{id:5,username:'supervisor',displayName:'测试主管',role:'SUPERVISOR',teamId:1}:path==='/api/capabilities'?{milestone:'M4',aiMode:'disabled',ragEnabled:false,message:'fixture'}:path==='/api/tickets'?{items:[],total:0,page:1,size:20}:path==='/api/operations/sla-inbox'?{items:[],total:0,page:1,size:20,asOf:'2030-01-01T00:00:00',dueSoonMinutes:30,summary:{active:0,breached:0,dueSoon:0,waitingCustomer:0,unassigned:0}}:[]});});
 await page.goto('/');const entry=page.locator('.tool-rail').getByRole('button',{name:'服务时限',exact:true});await expect(entry).toBeVisible();await entry.click();await expect(page.locator('.sla-inbox').getByRole('heading',{name:'服务时限',exact:true})).toBeVisible();expect(await page.locator('.service-shell').evaluate(e=>e.scrollWidth<=e.clientWidth)).toBeTruthy();
});
