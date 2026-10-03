"""对已读取的压缩格式服务做定点替换，匹配数异常则停止；无业务操作。"""
from pathlib import Path
root=Path(__file__).resolve().parents[5]
path=root/'ruoyi-backend/ruoyi-paicli/paicli-api/src/main/java/com/ruoyi/paicli/service/PcWechatService.java'
text=path.read_text(encoding='utf-8')
replacements=[
 ('"configVersion",binding.getVersion());', '"configVersion",p.getOrDefault("configurationVersion",binding.getVersion()));'),
 ('Set.of("BOUND","RUNNING","LOGIN_REQUIRED").contains(binding.getState())', 'Set.of("BOUND","RUNNING","LOGIN_REQUIRED","PAUSED").contains(binding.getState())'),
 ('String unique="wechat:"+binding.getId()+":"+PcJson.digest(messageId);', 'if(binding.getState().equals("PAUSED"))throw PcException.conflict("微信通道已暂停，请先恢复再提交文字任务");String unique="wechat:"+binding.getId()+":"+PcJson.digest(messageId);'),
 ('String name=PcJson.text(input,"name");if(Set.of("pause","stop").contains(name))', 'String name=PcJson.text(input,"name");if(name.equals("resume")){if(!binding.getState().equals("PAUSED")&&!binding.getState().equals("RUNNING"))throw PcException.conflict("仅暂停或运行通道可恢复");binding.setState("RUNNING");store.save(PcTable.WECHAT_BINDING,binding,p);return Map.of("status","RUNNING","message","微信中心通道已恢复");}if(Set.of("pause","stop").contains(name))'),
]
for old,new in replacements:
    if text.count(old)!=1:raise SystemExit('替换前提改变，停止受影响写入')
    text=text.replace(old,new,1)
text=text.replace('"model","pause","stop"','"model","pause","resume","stop"')
path.write_text(text,encoding='utf-8',newline='')
print('微信服务定点修正完成；未调用微信或数据库')
