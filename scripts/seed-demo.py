#!/usr/bin/env python3
"""Idempotent local demo fixtures, exclusively through authorized Java REST endpoints."""
import json, os, urllib.request, urllib.parse
base=os.environ.get('SERVICEFLOW_API_URL','http://127.0.0.1:18081')+'/api'
password=os.environ['SERVICEFLOW_DEMO_PASSWORD']
def call(path,body=None,token=None):
    req=urllib.request.Request(base+path,data=json.dumps(body).encode() if body is not None else None,headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+token} if token else {})})
    with urllib.request.urlopen(req,timeout=10) as r:
        return json.load(r) if r.status!=204 else None
sessions={u:call('/auth/login',{'username':u,'password':password})['token'] for u in ['alice','bob','supervisor','agent']}
fixtures=[('alice','耳机充电后仍无法开机','已更换充电线，指示灯没有亮起。','HIGH','OPEN'),('alice','显示器间歇性黑屏','使用约半小时后出现黑屏，重新插线后恢复。','NORMAL','IN_PROGRESS'),('alice','键盘蓝牙连接频繁中断','两台电脑都出现此问题，已检查电池。','NORMAL','WAITING_CUSTOMER'),('bob','平板无法连接无线网络','其他设备可以正常联网，已重启路由器。','HIGH','ASSIGNED'),('bob','扩展坞接口识别异常','连接移动硬盘时无法识别设备。','LOW','OPEN')]
for customer,title,desc,priority,state in fixtures:
    token=sessions[customer]
    if call('/tickets?q='+urllib.parse.quote(title),token=token)['total']:
        continue
    t=call('/tickets',{'title':title,'description':desc,'category':'DEVICE','priority':priority},token)
    path='/tickets/'+str(t['id'])
    if state!='OPEN':
        t=call(path+'/assign',{'assigneeId':3,'version':t['version'],'confirmed':True},sessions['supervisor'])
    if state in ['IN_PROGRESS','WAITING_CUSTOMER']:
        t=call(path+'/transition',{'target':'IN_PROGRESS','version':t['version'],'confirmed':False,'message':'客服开始排查，已联系客户补充设备信息。'},sessions['agent'])
    if state=='WAITING_CUSTOMER':
        call(path+'/transition',{'target':state,'version':t['version'],'confirmed':False,'message':'已指导客户重新配对并完成连接测试，请客户确认是否恢复。'},sessions['agent'])
print('Demo fixtures are ready; existing matching fixtures were preserved.')
