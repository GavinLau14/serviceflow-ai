import { expect, test, type Page } from "@playwright/test";
const id="2d21c5c2-19ac-41f7-a27b-66b03cfe4278";
const published={id,knowledgeId:8,version:1,title:"公开排查摘要",text:"可面向全部客户公开的通用摘要。",status:"ACTIVE",indexState:"FAILED",cleanupState:"NONE"};
async function mount(page:Page,role:string){
  let current:typeof published|null=null;
  const calls:string[]=[];
  await page.route("**/api/**",async route=>{
    const path=new URL(route.request().url()).pathname;calls.push(path);
    if(route.request().method()==="GET")return route.fulfill({json:{latest:current,history:current?[current]:[]}});
    const body=route.request().postDataJSON();
    if(path.endsWith("/withdraw")){expect(body).toEqual({publicationId:id,confirmed:true});current={...published,status:"WITHDRAWN",cleanupState:"DONE"};}
    else if(path.endsWith("/sync")){expect(body).toEqual({publicationId:id,confirmed:true});current={...published,indexState:"SYNCED"};}
    else{expect(body).toEqual({expectedVersion:0,title:published.title,text:published.text,redacted:true,confirmed:true});current={...published};}
    return route.fulfill({json:current});
  });
  await page.route("**/publication-contract",route=>route.fulfill({contentType:"text/html",body:`<html lang="zh-CN"><head><link rel="stylesheet" href="/node_modules/element-plus/dist/index.css"></head><body><div id="app"></div><script type="module">import{createApp}from'/node_modules/.vite/deps/vue.js';import C from'/src/components/PublicationPanel.vue';createApp(C,{knowledgeId:8,user:{id:6,role:'${role}',username:'fixture',displayName:'测试'}}).mount('#app');</script></body></html>`}));
  await page.goto('/publication-contract');return calls;
}
test('publication requires separate public content and both consents, then supports retry and confirmed withdrawal',async({page})=>{
  const calls=await mount(page,'ADMIN');await page.getByText('AI 知识发布',{exact:true}).click();
  await page.getByLabel('公开标题',{exact:true}).fill(published.title);await page.getByLabel('公开摘要',{exact:true}).fill(published.text);
  const publish=page.getByRole('button',{name:'发布公开摘要',exact:true});await expect(publish).toBeDisabled();
  await page.getByText('我已人工脱敏，摘要不含客户或工单私密信息',{exact:true}).click();await expect(publish).toBeDisabled();
  await page.getByText('我确认面向全部已登录客户发布',{exact:true}).click();await publish.click();
  await expect(page.getByText('索引失败，尚不可引用',{exact:true})).toBeVisible();await page.getByRole('button',{name:'重试索引',exact:true}).click();
  await expect(page.getByText('已同步，可用于检索',{exact:true})).toBeVisible();await page.getByRole('button',{name:'撤回当前版本',exact:true}).click();
  await page.getByRole('button',{name:'确认撤回',exact:true}).click();await expect(page.getByText('已撤回，禁止后续引用',{exact:true})).toBeVisible();
  expect(calls.filter(x=>x.endsWith('/withdraw'))).toHaveLength(1);
});
test('team staff can inspect publication state but cannot publish',async({page})=>{
  await mount(page,'SUPERVISOR');await page.getByText('AI 知识发布',{exact:true}).click();
  await expect(page.getByText('仅管理员可面向全部客户发布或撤回',{exact:true})).toBeVisible();
  await expect(page.getByLabel('公开摘要',{exact:true})).toHaveCount(0);
});
