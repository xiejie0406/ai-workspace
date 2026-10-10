package com.ruoyi.paicli.service;

import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.paicli.domain.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 原终端命令使用中心相同服务，不回退本地控制面；未知命令不会送模型。 */
public class PcCommandService {
    private final PcStore store;private final PcAccess access;private final PcIdempotency idem;private final PcSessionService sessions;private final PcKnowledgeService knowledge;private final PcConnectionService connections;private final PcToolService tools;
    public PcCommandService(PcStore s,PcAccess a,PcIdempotency i,PcSessionService sess,PcKnowledgeService k,PcConnectionService c,PcToolService t){store=s;access=a;idem=i;sessions=sess;knowledge=k;connections=c;tools=t;}
    private void permission(String value){if(!SecurityUtils.hasPermi(value))throw PcException.deny("没有命令所需权限："+value);}
    @Transactional("paicliTransactionManager")
    public Map<String,Object> command(long user,long sessionId,String key,String name,String raw){
        PcRecord session=access.resource(PcTable.SESSION,sessionId,user,true,true);String args=raw==null?"":raw.strip();if(args.length()>65536)throw PcException.invalid("命令参数超过64KB");String[] words=args.isEmpty()?new String[0]:args.split("\\s+");long workspace=session.getDirectoryContextId();String sub=words.length==0?"list":words[0].toLowerCase(Locale.ROOT);
        String required=switch(name){case "memory","mem","index","search","graph","save","init"->"paicli:knowledge:read";case "snapshot","restore"->"paicli:snapshot:read";case "model","config","mcp","skill","browser","wechat"->"paicli:connection:read";case "audit"->"paicli:audit:read";case "task"->"paicli:task:read";case "export"->"paicli:session:export";default->"paicli:session:read";};permission(required);
        if(Set.of("help","status","context","ctx","policy","config","memory","mem","task","snapshot","model","mcp","skill","browser","audit","export").contains(name)&&args.isEmpty())return result("中心当前状态",read(name,session,user));
        if(!session.getState().equals("ACTIVE"))throw new PcException(409,"PC_SESSION_READ_ONLY","历史会话仅供查看，请新建当前绑定会话");if(name.equals("skill")&&Set.of("on","off","enable","disable","start","stop").contains(sub)){permission("paicli:connection:manage");access.admin(workspace,user);}return idem.run("user:"+user+":command:"+sessionId,key,Map.of("name",name,"arguments",args),()->{
            switch(name){
                case "help":return result("支持的原命令与批准入口",help());
                case "status":return result("当前会话与任务",sessions.detail(sessionId,user));
                case "exit","quit":return result("可退出终端；中心任务与账本保留",Map.of("exit",true));
                case "cancel":{permission("paicli:task:cancel");var tasks=store.mapper.list(PcTable.TASK,workspace,user,null,null).stream().filter(t->Objects.equals(t.getSessionId(),sessionId)&&!PcSessionService.terminal(t.getState())).toList();if(tasks.isEmpty())return result("没有运行中的任务",Map.of());PcRecord t=tasks.get(0);return result("已请求取消，请等待实际结果确认",sessions.cancel(t.getId(),user,t.getVersion(),key+":cancel"));}
                case "mode","hitl":{String choice=name.equals("hitl")?(args.equals("on")?"ask":args.equals("default")?"auto":args):args;if(!Set.of("auto","plan","ask","REACT","PLAN","TEAM").contains(choice))throw PcException.invalid("mode只支持auto/plan/ask，hitl支持on/default");if(store.mapper.activeTasks(null,sessionId,null,null)>0)throw PcException.conflict("活跃轮次结束后才能切換模式");Map<String,Object> p=store.data(session);String mode=choice.equals("plan")||choice.equals("PLAN")?"PLAN":choice.equals("TEAM")?"TEAM":"REACT";p.put("mode",mode);p.put("approvalMode",choice.equals("auto")?"AUTO":choice.equals("plan")?"PLAN":"ASK");store.save(PcTable.SESSION,session,p);return result("会话模式已更新，硬安全规则继续生效",Map.of("mode",choice.toLowerCase(Locale.ROOT),"executionMode",mode,"approvalMode",p.get("approvalMode")));}
                case "plan","team":{if(args.isEmpty())throw PcException.invalid("请提供任务文字；现有计划用 /plan execute <id> 或 revise <id> <反馈>");if(name.equals("plan")&&Set.of("execute","revise","cancel").contains(sub)){if(words.length<2)throw PcException.invalid("缺少计划ID");if(sub.equals("cancel"))permission("paicli:task:cancel");long id=parseId(words[1]);PcRecord p=access.resource(PcTable.PLAN,id,user,true,false);Map<String,Object> command=new LinkedHashMap<>();command.put("version",p.getVersion());command.put("feedback",words.length>2?args.substring(args.indexOf(words[2])):"");return result("计划决定已保存",tools.planAction(user,id,p.getVersion(),key+":plan",sub,command));}Map<String,Object> input=new LinkedHashMap<>(store.data(session));input.put("text",args);input.put("mode",name.equals("team")?"TEAM":"PLAN");return result("任务已进入中心队列",sessions.turn(sessionId,user,key+":turn",session.getVersion(),input));}
                case "clear","compact":return result("上下文操作已登记",sessions.contextAction(sessionId,user,session.getVersion(),key+":context",name.equals("clear")?"CLEAR":"COMPRESS"));
                case "history":if(!args.equals("clear"))throw PcException.invalid("仅支持 /history clear");return result("输入历史清理标记已保存，正式账本保留",sessions.contextAction(sessionId,user,session.getVersion(),key+":history","CLEAR_INPUT_HISTORY"));
                case "context","ctx":return result("上下文预算和原始引用",sessions.context(sessionId,user));
                case "policy":return result("目录当前策略",store.view(store.policy(workspace,false)));
                case "export":permission("paicli:session:export");return result("中心会话导出",sessions.exportData(sessionId,user,SecurityUtils.hasPermi("paicli:context:raw")));
                case "init":if(!args.isEmpty()&&!args.equals("--force"))throw PcException.invalid("用法 /init [--force]");return queued(user,session,"INIT_PROJECT_MEMORY",Map.of("force",args.equals("--force")));
                case "index":return queued(user,session,"INDEX_BUILD",args.isEmpty()?Map.of():Map.of("path",args));
                case "search":if(args.isBlank())throw PcException.invalid("请输入仓库检索内容");return queued(user,session,"REPOSITORY_SEARCH",Map.of("query",args,"mode","SEMANTIC","limit",50));
                case "graph":return queued(user,session,"AST_GRAPH",Map.of("query",args));
                case "save":{permission("paicli:knowledge:manage");var parsed=PcTerminalCommandArguments.save(args);return result("记忆保存为待核实，用户可明确验证",knowledge.save(user,key+":save",Map.of("directoryContextId",workspace,"scope",parsed.scope(),"content",parsed.fact(),"source","USER_COMMAND","allowConflict",parsed.force())));}
                case "memory","mem":return memory(user,session,key,args,words,sub);
                case "task":{permission("paicli:task:read");if(sub.equals("list"))return result("中心后台任务",Map.of("tasks",sessions.tasks(workspace,user,null).stream().map(store::view).limit(words.length>1?taskListCount(words[1]):20).toList()));if(sub.equals("log")&&words.length==2)return result("任务账本",sessions.task(parseId(words[1]),user));if(sub.equals("cancel")&&words.length==2){permission("paicli:task:cancel");PcRecord t=access.resource(PcTable.TASK,parseId(words[1]),user,true,false);return result("取消请求已保存",sessions.cancel(t.getId(),user,t.getVersion(),key+":task-cancel"));}if(sub.equals("add")&&words.length>1){permission("paicli:task:write");Map<String,Object> data=new LinkedHashMap<>(store.data(session));data.put("text",args.substring(4));data.put("directoryContextId",workspace);data.put("runnerId",session.getRunnerId());data.put("title",data.get("text"));return result("后台任务已排队",sessions.background(user,key+":task-add",data));}throw PcException.invalid("task用法:list [N]/add <任务>/log <id>/cancel <id>");}
                case "files":{if(words.length>1)throw PcException.invalid("files只接受单一仓库相对路径");String path=args.isBlank()?".":args;if(!path.equals("."))path=PcPromptService.normalize(path);return queued(user,session,"REPOSITORY_LIST",Map.of("path",path));}
                case "snapshot":{permission("paicli:snapshot:read");if(sub.equals("list"))return result("绑定Runner快照元数据",Map.of("snapshots",knowledge.snapshots(sessionId,user)));if(sub.equals("status")){if(words.length!=1)throw PcException.invalid("snapshot status不接受额外参数");return queued(user,session,"SNAPSHOT_STATUS",Map.of());}if(sub.equals("clean")){permission("paicli:snapshot:restore");String range=words.length>1?words[1]:"current";if(words.length>2||!Set.of("current","all").contains(range))throw PcException.invalid("snapshot clean只接受current或all");if(range.equals("all"))return result("各目录清理任务已登记",knowledge.cleanAll(user,key+":clean-all"));return result("当前目录缓存先预览再批准清理",knowledge.cleanCurrent(user,workspace,session.getRunnerId(),session.getId()));}if(sub.equals("preview")&&words.length==2){PcRecord snap=snapshotSelection(session,user,words[1]);return queued(user,session,"SNAPSHOT_PREVIEW",Map.of("snapshotId",store.data(snap).getOrDefault("commitId",store.data(snap).getOrDefault("snapshotId",String.valueOf(snap.getId()))),"snapshotMetadataId",String.valueOf(snap.getId()),"snapshot",store.data(snap)));}throw PcException.invalid("snapshot用法:list/status/clean/preview <id>");}
                case "restore":{permission("paicli:snapshot:restore");if(words.length!=1)throw PcException.invalid("restore需要快照列表序号或id:<中心记录ID>");PcRecord snap=snapshotSelection(session,user,words[0]);Map<String,Object> preview;try{preview=knowledge.preview(user,snap.getId());}catch(PcException missing){if(missing.status()!=503)throw missing;Map<String,Object> pending=new LinkedHashMap<>(knowledge.requestPreview(user,snap.getId(),snap.getVersion(),key+":preview"));pending.put("previewRequired",true);pending.put("previewTaskId",pending.get("id"));pending.put("taskId",pending.get("id"));pending.put("snapshotMetadataId",String.valueOf(snap.getId()));return result("先由Runner核对当前文件差异，再按新指纹批准恢复",pending);}return result("快照恢复需当前文件哈希批准",knowledge.restore(user,snap.getId(),snap.getVersion(),key+":restore",Map.of("currentFingerprint",preview.get("currentFingerprint"))));}
                case "model":return model(user,session,key,args,words,sub);
                case "config":return providerConfig(user,session,key,PcTerminalCommandArguments.configWords(args).toArray(String[]::new));
                case "mcp","skill","browser","wechat":return connection(user,session,key,name,args,words,sub);
                case "better-harness":{
                    permission("paicli:evaluation:run");String depth="NORMAL";boolean inline=false;
                    for(String option:words)switch(option.toLowerCase(Locale.ROOT)){
                        case "quick","--quick"->depth="QUICK";
                        case "normal","--normal"->depth="NORMAL";
                        case "inline","--inline","--no-files"->inline=true;
                        default->throw PcException.invalid("未知参数："+option+"；用法 /better-harness [quick|normal] [--inline]");
                    }
                    return result("Harness已排队（不代表正式评测）",connections.harness(user,key+":harness",Map.of("sessionId",sessionId,"mode",inline?"INLINE":depth,"depth",depth,"inline",inline)));
                }
                case "audit":permission("paicli:audit:read");return result("中心追加审计",Map.of("records",store.mapper.visible(PcTable.SECURITY_AUDIT,user,workspace,false).stream().map(this::auditMetadata).limit(auditCount(args)).toList()));
                case "approval":return approval(user,session,key,args,words);
                case "approve","reject":{permission("paicli:approval:decide");if(words.length<1)throw PcException.invalid("缺少审批ID");PcRecord approval=access.resource(PcTable.APPROVAL,parseId(words[0]),user,false,false);return result("审批决定已持久化",tools.decision(user,approval.getId(),approval.getVersion(),key+":approval",Map.of("decision",name.equals("approve")?"APPROVE":"REJECT","inputHash",store.data(approval).get("argsHash"),"feedback",words.length>1?args.substring(args.indexOf(words[1])):"")));}
                default:throw PcException.invalid("未知命令 /"+name+"，没有发送模型或执行工具");
            }
        });
    }
    private Map<String,Object> queued(long user,PcRecord session,String operation,Map<String,Object> args){if(operation.startsWith("MCP_")||operation.startsWith("BROWSER_")||operation.startsWith("WECHAT_")||operation.startsWith("SKILLS_"))permission("paicli:connection:manage");return result("已在中心登记受限Runner操作，请查看任务/批准结果",sessions.control(user,session.getDirectoryContextId(),session.getRunnerId(),session.getId(),operation,args));}
    private List<PcRecord> visibleMemories(long user,long workspace){
        Map<Long,PcRecord> unique=new LinkedHashMap<>();
        for(PcRecord row:knowledge.memories(user,workspace,"PROJECT"))unique.put(row.getId(),row);
        for(PcRecord row:knowledge.memories(user,workspace,"PERSONAL"))unique.put(row.getId(),row);
        return List.copyOf(unique.values());
    }
    private int taskListCount(String raw){try{return Math.max(1,Math.min(100,Integer.parseInt(raw)));}catch(NumberFormatException invalid){return 20;}}
    private int auditCount(String value){if(value==null||value.isBlank())return 10;try{return Math.max(1,Math.min(100,Integer.parseInt(value.strip())));}catch(NumberFormatException invalid){return 10;}}
    private Map<String,Object> memory(long user,PcRecord session,String key,String args,String[] words,String sub){
        permission("paicli:knowledge:read");long workspace=session.getDirectoryContextId();
        List<PcRecord> rows=visibleMemories(user,workspace);
        if(sub.equals("list"))return result("当前目录及本人全局记忆",Map.of("entries",rows.stream().map(knowledge::entry).toList()));
        if(sub.equals("search")){
            String query=args.substring(Math.min(7,args.length())).strip();if(query.isBlank())throw PcException.invalid("memory search需要关键词");
            return result("当前目录及本人全局匹配记忆",Map.of("entries",rows.stream().filter(r->PcJson.text(store.data(r),"content").contains(query)).limit(20).map(knowledge::entry).toList()));
        }
        permission("paicli:knowledge:manage");
        if(sub.equals("clear")){
            String scope=words.length==1?"PROJECT":words.length==2&&words[1].equals("--global")?"PERSONAL":words.length==2&&words[1].equals("--project")?"PROJECT":null;
            if(scope==null)throw PcException.invalid("memory clear [--project|--global]，不会跨项目清理");
            for(PcRecord r:knowledge.memories(user,workspace,scope))knowledge.action(user,r.getId(),r.getVersion(),key+":clear:"+r.getId(),"delete",Map.of("id",r.getId()));
            return result("指定scope记忆已标记删除，历史仍可审计",Map.of("cleared",true,"scope",scope));
        }
        if(words.length<2||!Set.of("verify","delete","replace").contains(sub))throw PcException.invalid("memory用法list/search/delete/clear/verify/replace");
        PcRecord row=access.resource(PcTable.MEMORY,parseId(words[1]),user,false,false);
        if(!rows.stream().anyMatch(r->r.getId().equals(row.getId())))throw PcException.deny("记忆不属于当前目录或本人全局作用域");
        String[] parts=args.split("\\s+",3);String content=parts.length>2?parts[2]:"";
        if(sub.equals("replace")&&content.isBlank())throw PcException.invalid("memory replace需要新事实正文");
        if(!sub.equals("replace")&&words.length!=2)throw PcException.invalid("memory verify/delete只接受单个ID");
        return result("记忆操作已保存",knowledge.action(user,row.getId(),row.getVersion(),key+":memory",sub,Map.of("content",content,"version",row.getVersion())));
    }
    private Map<String,Object> model(long user,PcRecord session,String key,String args,String[] words,String sub){
        permission("paicli:connection:read");
        var models=connections.list("models",user,session.getDirectoryContextId());
        if(sub.equals("list")){
            if(words.length>2)throw PcException.invalid("model list只接受可选provider");
            if(words.length==2){var provider=findName("providers",session,user,words[1]);models=models.stream().filter(m->Objects.toString(m.get("providerId"),"").equals(Objects.toString(provider.get("id"),""))).toList();}
            return result("登记模型",Map.of("models",models));
        }
        if(sub.equals("info")){
            if(words.length>2)throw PcException.invalid("model info只接受可选模型ID");
            String requested=words.length>1?words[1]:PcJson.text(store.data(session),"modelId");
            return result("模型完整配置",selectModel(user,session,models,requested));
        }
        if(sub.equals("refresh")){
            if(words.length!=2)throw PcException.invalid("model refresh需要provider");
            permission("paicli:connection:manage");var provider=findName("providers",session,user,words[1]);
            return result("仅列表刷新，不切换当前模型",connections.action("providers",PcJson.number(provider,"id"),user,key+":refresh",PcJson.number(provider,"version"),Map.of("action","REFRESH")));
        }
        if(sub.equals("add")){
            permission("paicli:connection:manage");if(words.length<3)throw PcException.invalid("model add需要provider和完整模型ID");
            var provider=findName("providers",session,user,words[1]);String providerId=Objects.toString(provider.get("id"),"");
            String canonicalProvider=PcTerminalCommandArguments.provider(PcJson.text(provider,"provider"));
            String requested=PcTerminalCommandArguments.model(canonicalProvider,words[2]);
            if(!requested.matches("[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,199}"))throw PcException.invalid("模型ID非法");
            var candidates=models.stream().filter(m->providerId.equals(Objects.toString(m.get("providerId"),""))).toList();
            var existing=candidates.stream().filter(m->requested.equals(m.get("modelId"))).toList();
            if(existing.size()>1)throw PcException.conflict("同供应商模型登记有歧义，请先整理重复登记");
            Map<String,Object> p=existing.isEmpty()?new LinkedHashMap<>():new LinkedHashMap<>(existing.get(0));
            Map<String,String> options=PcTerminalCommandArguments.modelOptions(words);
            if(options.containsKey("like")){
                String like=PcTerminalCommandArguments.model(canonicalProvider,options.get("like"));
                var templates=candidates.stream().filter(m->like.equals(m.get("modelId"))||like.equals(m.get("id"))).toList();
                if(templates.size()!=1)throw PcException.invalid("找不到唯一同供应商已登记模板模型");
                var template=templates.get(0);
                for(String field:List.of("contextWindow","supportsImages","imageInput","supportsThinking","supportsTools","capabilityKnown","thinking","reasoningEffort","reasoningHistory","dsml","maxOutputTokens")){
                    if(template.containsKey(field))p.put(field,template.get(field));else p.remove(field);
                }
                p.put("template",template.get("template")==null?template.get("modelId"):template.get("template"));
            }
            p.put("directoryContextId",session.getDirectoryContextId());p.put("providerId",provider.get("id"));p.put("modelId",requested);
            p.putIfAbsent("contextWindow",128000);p.putIfAbsent("supportsImages",false);p.putIfAbsent("supportsThinking",false);p.putIfAbsent("enabled",true);p.put("source","manual");
            if(options.containsKey("context"))p.put("contextWindow",PcTerminalCommandArguments.positive(options.get("context")));
            if(options.containsKey("max-output"))p.put("maxOutputTokens",PcTerminalCommandArguments.positive(options.get("max-output")));
            if(options.containsKey("vision")){boolean vision=PcTerminalCommandArguments.bool(options.get("vision"));p.put("supportsImages",vision);p.put("imageInput",vision);}
            if(options.containsKey("thinking")){
                String value=options.get("thinking");if(!Set.of("auto","enabled","disabled","on","off").contains(value))throw PcException.invalid("thinking选项非法");
                if(value.equals("auto"))p.put("thinking",null);else{boolean enabled=Set.of("enabled","on").contains(value);p.put("thinking",enabled?"enabled":"disabled");p.put("supportsThinking",enabled);if(!enabled&&!options.containsKey("effort"))p.put("reasoningEffort",null);}
            }
            if(options.containsKey("effort")){String value=options.get("effort");if(!Set.of("auto","low","medium","high","max").contains(value))throw PcException.invalid("effort选项非法");p.put("reasoningEffort",value.equals("auto")?null:value);}
            if(options.containsKey("reasoning-history"))p.put("reasoningHistory",PcTerminalCommandArguments.bool(options.get("reasoning-history")));
            if(options.containsKey("dsml")){boolean dsml=PcTerminalCommandArguments.bool(options.get("dsml"));if(dsml&&!canonicalProvider.equals("deepseek"))throw PcException.invalid("原DSML兼容仅支持deepseek");p.put("dsml",dsml);}
            return result("模型配置已保存，当前会话选择保持原值",connections.save("models",user,key+":add",existing.isEmpty()?0:PcJson.number(existing.get(0),"version"),p));
        }
        return result("模型切换已保存",connections.switchModel(session.getId(),user,key+":switch",session.getVersion(),PcJson.number(selectModel(user,session,models,args),"id")));
    }
    private Map<String,Object> selectModel(long user,PcRecord session,List<Map<String,Object>> models,String selection){
        String modelId=selection;Map<String,Object> provider=null;int slash=selection.indexOf('/');
        if(slash>0){
            String prefix=PcTerminalCommandArguments.provider(selection.substring(0,slash));
            var providers=connections.list("providers",user,session.getDirectoryContextId()).stream().filter(p->prefix.equals(PcTerminalCommandArguments.provider(PcJson.text(p,"provider")))||selection.substring(0,slash).equals(p.get("name"))).toList();
            if(providers.size()>1)throw PcException.invalid("供应商别名有歧义，请先明确唯一登记");
            if(providers.size()==1){provider=providers.get(0);modelId=selection.substring(slash+1);}
        }
        if(provider!=null)modelId=PcTerminalCommandArguments.model(PcTerminalCommandArguments.provider(PcJson.text(provider,"provider")),modelId);
        String requested=modelId,providerId=provider==null?null:Objects.toString(provider.get("id"),"");
        var matches=models.stream().filter(m->(requested.equals(m.get("id"))||requested.equals(m.get("modelId")))&&(providerId==null||providerId.equals(Objects.toString(m.get("providerId"),"")))).toList();
        if(matches.isEmpty()&&provider==null){
            String alias=PcTerminalCommandArguments.model("deepseek",selection);
            if(!alias.equals(selection)){
                var deepseek=connections.list("providers",user,session.getDirectoryContextId()).stream().filter(p->"deepseek".equals(PcJson.text(p,"provider"))).map(p->Objects.toString(p.get("id"),"")).toList();
                matches=models.stream().filter(m->alias.equals(m.get("modelId"))&&deepseek.contains(Objects.toString(m.get("providerId"),""))).toList();
            }
        }
        if(matches.isEmpty()){
            if(provider!=null)throw PcException.invalid("指定供应商未登记该模型，不能静默切到默认模型");
            if(provider==null)provider=findName("providers",session,user,selection);
            String startup=PcJson.text(provider,"model");if(startup.isBlank())throw PcException.invalid("供应商未登记默认启动模型");
            String bound=Objects.toString(provider.get("id"),""),configured=PcTerminalCommandArguments.model(PcJson.text(provider,"provider"),startup);
            matches=models.stream().filter(m->bound.equals(Objects.toString(m.get("providerId"),""))&&configured.equals(m.get("modelId"))).toList();
        }
        if(matches.size()!=1)throw PcException.invalid("模型ID不存在或有歧义，请使用provider/模型ID或中心记录ID");
        return matches.get(0);
    }
    private Map<String,Object> connection(long user,PcRecord session,String key,String name,String args,String[] words,String sub){permission("paicli:connection:read");if(name.equals("wechat")&&sub.equals("daemon")){if(words.length<2)throw PcException.invalid("wechat daemon start|stop|restart|status|logs [绑定ID]");args=args.substring(args.indexOf(words[1]));words=args.split("\\s+");sub=words[0];}if(name.equals("wechat")&&sub.equals("logs")){var bindings=connections.list("wechat",user,session.getDirectoryContextId());Map<String,Object> selected;if(words.length>1)selected=findName("wechat",session,user,words[1]);else{if(bindings.size()!=1)throw PcException.invalid("请明确微信绑定记录ID");selected=bindings.get(0);}long bindingId=PcJson.number(selected,"id");List<Map<String,Object>> logs=new ArrayList<>();for(PcRecord task:store.mapper.list(PcTable.TASK,session.getDirectoryContextId(),user,null,null)){Map<String,Object> data=store.data(task);boolean matches=Objects.toString(data.get("wechatBindingId"),"").equals(String.valueOf(bindingId));if(data.get("arguments") instanceof Map<?,?> frozen)matches|=Objects.toString(frozen.get("configId"),"").equals(String.valueOf(bindingId))&&PcJson.text(data,"operation").startsWith("WECHAT_");if(matches)logs.add(Map.of("taskId",String.valueOf(task.getId()),"status",task.getState(),"eventEndpoint","/paicli/v1/tasks/"+task.getId()+"/events"));if(logs.size()>=200)break;}return result("微信正式任务日志入口",Map.of("bindingId",String.valueOf(bindingId),"logs",logs,"hasMore",logs.size()>=200));}String kind=name.equals("skill")?"skills":name;long workspace=session.getDirectoryContextId();if(Set.of("list","status").contains(sub)){Map<String,Object> view=new LinkedHashMap<>();var registered=connections.list(kind,user,workspace);view.put("connections",registered);if(name.equals("browser")&&sub.equals("status")&&!registered.isEmpty()){if(registered.size()!=1)throw PcException.invalid("请先登记唯一浏览器配置");PcRecord browser=store.require(PcTable.BROWSER_CONFIG,PcJson.number(registered.get(0),"id"),false);var pending=queued(user,session,"BROWSER_PROBE",Map.of("configId",String.valueOf(browser.getId()),"configVersion",connections.definition(browser),"config",connections.browserConfiguration(browser)));view.putAll((Map<String,Object>)pending.get("data"));return result("登记状态已列出，实际探活任务已排队",view);}if(name.equals("wechat"))view.put("runners",store.mapper.list(PcTable.RUNNER,workspace,null,null,null).stream().map(r->Map.of("id",String.valueOf(r.getId()),"status",r.getState(),"lastHeartbeatAt",Objects.toString(store.data(r).get("lastHeartbeatAt"),"未上报"))).toList());return result("当前登记连接与设备状态",view);}if(name.equals("wechat")&&Set.of("setup","bind").contains(sub)){permission("paicli:connection:manage");return result("微信初始化任务已登记，请在若依绑定身份",connections.setupWechat(user,key+":setup",workspace));}if(name.equals("browser")){if(Set.of("tabs","disconnect").contains(sub)){var configs=connections.list(kind,user,workspace);if(configs.size()!=1)throw PcException.invalid("请先登记唯一浏览器配置");PcRecord browser=store.require(PcTable.BROWSER_CONFIG,PcJson.number(configs.get(0),"id"),false);return queued(user,session,sub.equals("tabs")?"BROWSER_TABS":"BROWSER_DISCONNECT",Map.of("configId",String.valueOf(browser.getId()),"configVersion",connections.definition(browser),"config",connections.browserConfiguration(browser)));}if(sub.equals("connect")){permission("paicli:connection:manage");var configs=connections.list(kind,user,workspace);if(configs.size()!=1)throw PcException.invalid("请先在中心登记浏览器及批准chrome-devtools绑定");Map<String,Object> config=new LinkedHashMap<>(configs.get(0));if(words.length>1){String mode=words[1];config.put("mode",mode.equals("shared")?"SHARED":"ISOLATED");config.put("confirmShared",mode.equals("shared"));if(mode.matches("[0-9]+"))config.put("endpoint","http://127.0.0.1:"+mode);config=connections.browser(user,key+":browser-config",PcJson.number(config,"version"),config);}return result("浏览器连接操作已排队，须批准并等实际结果",connections.action("browser",PcJson.number(config,"id"),user,key+":browser-connect",PcJson.number(config,"version"),Map.of("action","CONNECT")));}}

        if(name.equals("skill")&&Set.of("on","off","enable","disable","start","stop").contains(sub)){permission("paicli:connection:manage");if(words.length!=2)throw PcException.invalid("skill on/off需要已登记的完整名称");boolean enabled=Set.of("on","enable","start").contains(sub);return result(enabled?"Skill名称已启用，按三层批准配置选择":"Skill名称已停用，全部同名层及后来登记均不可加载",connections.skillName(user,workspace,key+":skill-name",words[1],enabled));}
        if(name.equals("skill")&&sub.equals("reload")){permission("paicli:connection:manage");return result("Skill重新加载任务已登记",connections.reloadSkills(user,key+":skill-reload",workspace));}Map<String,Object> config;if(words.length<2&&name.equals("wechat")){var bindings=connections.list(kind,user,workspace);if(bindings.size()!=1)throw PcException.invalid("请明确唯一微信绑定记录ID");config=bindings.get(0);}else{if(words.length<2)throw PcException.invalid("命令缺少登记连接名称");config=findName(kind,session,user,words[1]);}long id=PcJson.number(config,"id"),version=PcJson.number(config,"version");if(name.equals("skill")&&sub.equals("show"))return result("中心已批准Skill全文",config);if(name.equals("mcp")&&sub.equals("logs"))return result("MCP日志",Map.of("logs",config.getOrDefault("logs",List.of())));if(name.equals("mcp")&&Set.of("resources","prompts").contains(sub)){if(words.length>2)return queued(user,session,sub.equals("resources")?"MCP_READ_RESOURCE":"MCP_READ_PROMPT",Map.of("configId",config.get("id"),"configVersion",connections.definition(store.require(PcTable.MCP_CONFIG,id,false)),"config",connections.configuration(store.require(PcTable.MCP_CONFIG,id,false)),"reference",words[2]));return result("MCP元数据",Map.of(sub,config.getOrDefault(sub,List.of())));}permission("paicli:connection:manage");String action=switch(sub){case "on","enable","start"->"START";case "off","disable","stop"->"STOP";case "restart"->"RESTART";case "pause"->"PAUSE";case "resume"->"RESUME";default->throw PcException.invalid("未知连接命令");};if(name.equals("skill"))return result("Skill启用配置已保存",connections.action(kind,id,user,key+":skill",version,Map.of("enabled",action.equals("START"),"action","RELOAD")));return result("连接操作已排队",connections.action(kind,id,user,key+":connection",version,Map.of("action",action)));}
    private PcRecord snapshotSelection(PcRecord session,long user,String selector){PcRecord snapshot;if(selector.startsWith("id:"))snapshot=access.resource(PcTable.SNAPSHOT,parseId(selector.substring(3)),user,true,false);else{long ordinal=parseId(selector);var values=knowledge.snapshots(session.getId(),user).stream().filter(v->"PRE_TURN".equals(PcJson.text(v,"phase").toUpperCase(Locale.ROOT).replace('-','_'))).toList();if(ordinal>values.size())throw PcException.invalid("轮次前快照序号不在当前会话列表，请先snapshot list");snapshot=access.resource(PcTable.SNAPSHOT,PcJson.number(values.get((int)ordinal-1),"id"),user,true,false);}if(!Objects.equals(snapshot.getSessionId(),session.getId()))throw PcException.deny("快照不属于当前会话");return snapshot;}
    private boolean booleanOption(String value){if(!Set.of("on","off","true","false").contains(value))throw PcException.invalid("布尔选项须为on/off或true/false");return value.equals("on")||value.equals("true");}
    private Map<String,Object> providerConfig(long user,PcRecord session,String key,String[] words){
        permission("paicli:connection:manage");
        if(words.length<2||!words[0].equalsIgnoreCase("provider"))throw PcException.invalid("config provider <provider> [--secret-ref alias] [--base-url URL] [--model ID] [--lora-id ID] [--default]");
        Map<String,Object> config=new LinkedHashMap<>(findName("providers",session,user,words[1]));
        if(words.length==2)return result("供应商配置已登记；立即切换请使用model命令，当前会话保持原模型",config);
        boolean makeDefault=false;
        for(int i=2;i<words.length;i++){
            String flag=words[i];if(flag.equalsIgnoreCase("--default")||flag.equalsIgnoreCase("--set-default")){makeDefault=true;continue;}
            int equals=flag.indexOf('=');String value;
            if(equals>0){value=flag.substring(equals+1);flag=flag.substring(0,equals);}
            else{if(i+1>=words.length)throw PcException.invalid("供应商选项缺值");value=words[++i];}
            switch(PcTerminalCommandArguments.configKey(flag)){
                case "secret-ref"->config.put("secretRef",value);
                case "base-url"->config.put("baseUrl",value);
                case "model"->config.put("model",value);
                case "lora-id"->{if(!PcTerminalCommandArguments.provider(PcJson.text(config,"provider")).equals("xfyun"))throw PcException.invalid("lora-id仅支持xfyun provider");config.put("loraId",value);}
                case "api-key"->throw PcException.invalid("网页托管终端只接受中心secret-ref，请在若依安全配置密钥");
                default->throw PcException.invalid("供应商配置选项不受支持");
            }
        }
        Map<String,Object> saved=connections.save("providers",user,key+":provider-config",PcJson.number(config,"version"),config);
        if(makeDefault)connections.defaultProvider(user,session.getDirectoryContextId(),PcJson.number(saved,"id"));
        return result("供应商设置已保存，默认设置用于后续选择，当前会话模型保留",saved);
    }

    private Map<String,Object> approval(long user,PcRecord session,String key,String args,String[] words){permission("paicli:approval:decide");if(words.length<2||!Set.of("show","decide").contains(words[0]))throw PcException.invalid("approval show <id> / decide <id> APPROVE|REJECT|SKIP|REVISE [JSON]");PcRecord row=access.resource(PcTable.APPROVAL,parseId(words[1]),user,false,false);access.approve(row.getDirectoryContextId(),user);if(!Objects.equals(row.getDirectoryContextId(),session.getDirectoryContextId()))throw PcException.deny("审批不属于当前目录");if(words[0].equals("show")){Map<String,Object> view=store.view(row);view.remove("permitCipher");view.remove("permitHash");return result("授权范围内的完整审批参数与绑定",view);}if(words.length<3)throw PcException.invalid("缺少审批决定");Map<String,Object> input=new LinkedHashMap<>();if(words.length>3){String json=args.substring(args.indexOf(words[3]));input.putAll(store.json.read(json));}input.put("decision",words[2].toUpperCase(Locale.ROOT));input.put("inputHash",store.data(row).get("argsHash"));input.putIfAbsent("feedback","");return result("中心审批决定已保存；修改参数需新调用与新批准",tools.decision(user,row.getId(),row.getVersion(),key+":approval-decide",input));}
    private Map<String,Object> findName(String kind,PcRecord session,long user,String name){return connections.list(kind,user,session.getDirectoryContextId()).stream().filter(p->name.equals(p.get("name"))||name.equals(p.get("provider"))||name.equals(p.get("id"))||kind.equals("providers")&&PcTerminalCommandArguments.provider(name).equals(PcTerminalCommandArguments.provider(PcJson.text(p,"provider")))).findFirst().orElseThrow(()->PcException.invalid("没有登记该连接"));}
    private Map<String,Object> read(String name,PcRecord s,long user){return switch(name){case "help"->help();case "context","ctx"->sessions.context(s.getId(),user);case "policy"->store.view(store.policy(s.getDirectoryContextId(),false));case "memory","mem"->Map.of("entries",visibleMemories(user,s.getDirectoryContextId()).stream().map(knowledge::entry).toList());case "audit"->Map.of("records",store.mapper.visible(PcTable.SECURITY_AUDIT,user,s.getDirectoryContextId(),false).stream().map(this::auditMetadata).limit(10).toList());case "task"->Map.of("tasks",sessions.tasks(s.getDirectoryContextId(),user,null).stream().map(store::view).toList());case "snapshot"->Map.of("snapshots",knowledge.snapshots(s.getId(),user));case "model"->Map.of("models",connections.list("models",user,s.getDirectoryContextId()));case "mcp","skill","browser","config"->Map.of("connections",connections.list(name.equals("skill")?"skills":name.equals("config")?"providers":name,user,s.getDirectoryContextId()));default->sessions.detail(s.getId(),user);};}
    private Map<String,Object> auditMetadata(PcRecord row){Map<String,Object> result=store.view(row);result.remove("details");return result;}
    private Map<String,Object> help(){return Map.of("commands",List.of("help","status","exit","quit","cancel","clear","history clear","compact","export","init [--force]","plan <任务>|execute <id>|revise <id> <反馈>","team <任务>","mode auto|plan|ask","hitl on|default","policy","model list|info|refresh|add|<id>","config","memory list|search|delete|clear|verify|replace","save [--global|--project] <fact>","index [path]","search <query>","graph <class>","context","audit","snapshot list|status|clean|preview","restore <列表序号>|id:<中心记录ID>","files [仓库相对路径]","mcp restart|logs|enable|disable|resources|prompts","browser status|connect|disconnect|tabs","skill list|show|on|off|reload","task list|add|log|cancel","better-harness quick|normal [--inline]","wechat setup|start|stop|restart","approval show <id>|decide <id> APPROVE|REJECT|SKIP|REVISE [JSON]","approve <id>","reject <id> [feedback]"));}
    private Map<String,Object> result(String message,Map<String,Object> data){return Map.of("successful",true,"message",message,"data",data);}
    private long parseId(String value){try{long id=Long.parseLong(value);if(id<1)throw new NumberFormatException();return id;}catch(NumberFormatException e){throw PcException.invalid("ID或数值必须为正整数");}}
}
