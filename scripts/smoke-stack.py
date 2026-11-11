#!/usr/bin/env python3
"""Authenticated stack smoke; prints/saves no credentials. Requires a demo-only instance."""
import argparse,json,os,urllib.request
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--url',default='http://127.0.0.1:18080');parser.add_argument('--with-ai',action='store_true');parser.add_argument('--output');parser.add_argument('--verify-previous');parser.add_argument('--expect-ai-error',action='store_true');args=parser.parse_args()
settings={}
if Path('.env').exists():
 settings=dict(l.split('=',1) for l in Path('.env').read_text().splitlines() if '=' in l and not l.startswith('#'))
password=os.environ.get('SERVICEFLOW_DEMO_PASSWORD') or settings['SERVICEFLOW_DEMO_PASSWORD']
def call(path,body=None,token=None):
 request=urllib.request.Request(args.url+'/api'+path,data=json.dumps(body).encode() if body is not None else None,headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+token} if token else {})})
 with urllib.request.urlopen(request,timeout=100) as r:return json.load(r)
tokens={u:call('/auth/login',{'username':u,'password':password})['token'] for u in ['alice','agent','supervisor','admin']}
customer=tokens['alice'];lead=tokens['supervisor'];agent=tokens['agent']
if args.verify_previous:
 previous=json.loads(Path(args.verify_previous).read_text());persisted=call('/tickets/'+str(previous['ticketId']),token=customer);assert persisted['ticket']['status']=='CLOSED'
index=call('/ai/index',{},tokens['admin']) if args.with_ai else None
run=call('/ai/assist',{'message':'电脑无法开机，需要人工协助'},customer)
if args.expect_ai_error:assert run['mode']=='error' and run['proposal'] is None
if args.with_ai:assert run['mode']=='retrieval_only' and run['citations'],run['mode']
ticket=call('/ai/runs/'+run['id']+'/handoff',{'category':'DEVICE','confirmed':True},customer)
again=call('/ai/runs/'+run['id']+'/handoff',{'category':'DEVICE','confirmed':True},customer);assert ticket['id']==again['id']
path='/tickets/'+str(ticket['id'])
if ticket['assigneeId']!=3 or ticket['status']=='OPEN':ticket=call(path+'/assign',{'assigneeId':3,'version':ticket['version'],'confirmed':True},lead)
for who,state in [(agent,'IN_PROGRESS'),(agent,'WAITING_CUSTOMER'),(customer,'CLOSED')]:
 ticket=call(path+'/transition',{'target':state,'version':ticket['version'],'confirmed':True,'message':'演示验证：检查连接并完成复测。'},who)
knowledge=call(path+'/knowledge',{'title':'容器演示排查方法','content':'确认设备连接并记录实际复测结果，涉及安全风险由专业人员处理。','confirmed':True},agent)
knowledge=call('/knowledge/'+str(knowledge['id'])+'/review',{'approved':True,'confirmed':True},lead);assert knowledge['status']=='PUBLISHED'
scan=call('/operations/scan',{'confirmed':True},lead)
notices=call('/notifications',token=customer);assert notices
result={'restartPersistenceVerified':bool(args.verify_previous),'aiOutageFallbackVerified':args.expect_ai_error,'baseUrl':args.url,'status':'PASS','aiMode':run['mode'],'realRetrievalIndexed':index,'ticketId':ticket['id'],'ticketStatus':ticket['status'],'handoffIdempotent':True,'knowledgeId':knowledge['id'],'knowledgeStatus':knowledge['status'],'customerNotices':len(notices),'scan':scan}
if args.output:Path(args.output).write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps(result,ensure_ascii=False))
