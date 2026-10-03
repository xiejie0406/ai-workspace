package com.ruoyi.aipicture.service;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.aipicture.config.*;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.persistence.*;
import com.ruoyi.aipicture.tool.*;
import com.ruoyi.aipicture.worker.*;

@Service @AiPictureEnabled
public class PlanService {
    private final AiPictureRepository db;private final RunService runs;private final SessionService sessions;private final ToolRegistry registry;private final AiPictureWorkerClient worker;private final TransactionTemplate tx;private final CommandIdempotency commands;
    public PlanService(AiPictureRepository db,RunService runs,SessionService sessions,ToolRegistry registry,AiPictureWorkerClient worker,@Qualifier("aipictureTransactionTemplate") TransactionTemplate tx,CommandIdempotency commands) {this.db=db;this.runs=runs;this.sessions=sessions;this.registry=registry;this.worker=worker;this.tx=tx;this.commands=commands;}
    public List<Map<String,Object>> list(long owner,String session) {
        db.owned("aip_session",session,owner,false);return db.query("SELECT * FROM aip_agent_turn WHERE session_id=:session AND user_id=:owner ORDER BY created_at,id",Json.of("session",session,"owner",owner)).stream().map(this::dto).toList();
    }
    private Map<String,Object> turn(long owner,String session,String id,boolean lock) {
        db.owned("aip_session",session,owner,false);var turn=db.one("SELECT * FROM aip_agent_turn WHERE id=:id AND session_id=:session AND user_id=:owner"+(lock?" FOR UPDATE":""),Json.of("id",Json.uuid(id),"session",session,"owner",owner));if(turn==null) throw AiPictureException.missing();return turn;
    }
    private Map<String,Object> dto(Map<String,Object> turn) {
        var result=new LinkedHashMap<>(turn);result.remove("user_id");result.put("revision",turn.get("base_revision"));
        var failure=db.one("SELECT r.message FROM aip_plan_step s JOIN aip_run r ON r.id=s.run_id WHERE s.turn_id=:turn AND s.status IN ('failed','external_result_unknown') ORDER BY s.position LIMIT 1",Json.of("turn",turn.get("id")));result.put("error",failure==null?turn.get("message"):failure.get("message"));
        var steps=db.query("SELECT step_id AS id,tool,params,dependencies AS depends_on,status,run_id,attempt FROM aip_plan_step WHERE turn_id=:turn ORDER BY position",Json.of("turn",turn.get("id")));
        for(var step:steps) step.put("label",registry.tool(Json.string(step.get("tool"))).get("label"));result.put("steps",steps);return result;
    }
    public Map<String,Object> send(long owner,String session,Map<String,Object> request) {
        registry.versions(request);String goal=Json.string(request.get("text")).strip();if(goal.isEmpty()||goal.length()>1000) throw AiPictureException.invalid("指令需 1–1000 字符");
        var s=db.owned("aip_session",session,owner,false);
        String commandKey=Json.idem(request),requestHash=RunService.hash(request),candidate=Json.id();
        var reservation=tx.execute(status->{
            var existing=db.one("SELECT * FROM aip_agent_turn WHERE user_id=:owner AND command_key=:key FOR UPDATE",Json.of("owner",owner,"key",commandKey));
            if(existing!=null) {if(!requestHash.equals(existing.get("request_hash"))) throw new AiPictureException(409,"IDEMPOTENCY_CONFLICT","此幂等标识已用于另一计划指令");return Json.of("id",existing.get("id"),"existing",true);}
            if(Json.number(s.get("row_version"))!=Json.expected(request)) throw AiPictureException.conflict(Json.number(s.get("row_version")));
            long now=System.currentTimeMillis();
            db.execute("INSERT INTO aip_agent_turn(id,user_id,session_id,goal,reply,status,base_revision,base_version,steps,command_key,request_hash,created_at,updated_at) VALUES(:id,:owner,:session,:goal,'','planning',:revision,:version,'[]',:key,:hash,:now,:now)",Json.of("id",candidate,"owner",owner,"session",session,"goal",goal,"revision",s.get("revision"),"version",s.get("row_version"),"key",commandKey,"hash",requestHash,"now",now));return Json.of("id",candidate,"existing",false);
        });
        if(Json.bool(reservation.get("existing"),false)) return dto(turn(owner,session,Json.string(reservation.get("id")),false));
        try {
        // 规划调用不持有数据库事务，也不修改图像。冻结文档版本再次核对后才保存。
        var response=worker.call("plan",Json.of("schema_version","1","tool_registry_version","2026.10-r2","run_id",candidate,"attempt",1,"goal",goal,"context",describe(owner,s)));
        var plan=Json.map(response.getOrDefault("result",response));var steps=new PlanValidator(registry).validate(Json.list(plan.getOrDefault("steps",plan.get("plan"))));
        for(Object step:steps) ToolPermissionGuard.check(Json.string(Json.map(step).get("tool")));
        String id=candidate,hash=RunService.hash(steps);
        return tx.execute(status->{
            // 与取消共用 turn→session 锁序；事务外规划的迟到响应不能恢复已取消的正式计划。
            var current=turn(owner,session,id,true);
            if(!"planning".equals(current.get("status"))) return dto(current);
            sessions.lock(owner,session,Json.expected(request));long now=System.currentTimeMillis();String state=steps.size()>1?"waiting_confirmation":steps.isEmpty()?"succeeded":"executing";
            var selection=sessions.selection(owner,session);var doc=Json.map(s.get("document"));
            db.execute("UPDATE aip_agent_turn SET reply=:reply,status=:status,plan_hash=:hash,steps=:steps,mask_asset_id=:mask,mask_width=:width,mask_height=:height,updated_at=:now WHERE id=:id AND user_id=:owner AND session_id=:session",Json.of("id",id,"owner",owner,"session",session,"reply",Json.string(plan.get("reply")),"status",state,"hash",hash,"steps",Json.write(steps),"mask",selection==null?null:selection.get("mask_asset_id"),"width",selection==null?null:doc.get("width"),"height",selection==null?null:doc.get("height"),"now",now));
            int position=0;for(Object object:steps) {var step=Json.map(object);db.execute("INSERT INTO aip_plan_step(turn_id,step_id,position,tool,params,dependencies,status) VALUES(:turn,:id,:position,:tool,:params,:dependencies,'pending')",Json.of("turn",id,"id",step.get("id"),"position",position++,"tool",step.get("tool"),"params",Json.write(step.get("params")),"dependencies",Json.write(step.get("depends_on"))));}
            if(steps.size()==1) runs.continuePlan(id,owner);return dto(turn(owner,session,id,false));
        });
        } catch(AiPictureException e) {
            var settled=tx.execute(status->{
                var current=turn(owner,session,candidate,true);
                if(!"planning".equals(current.get("status"))) return dto(current);
                db.execute("UPDATE aip_agent_turn SET status='failed',error_code=:code,message=:message,updated_at=:now WHERE id=:id AND user_id=:owner",Json.of("code",e.errorCode(),"message",e.getMessage(),"now",System.currentTimeMillis(),"id",candidate,"owner",owner));return null;
            });
            if(settled!=null) return settled;throw e;
        }
    }
    public Map<String,Object> action(long owner,String session,String id,String action,Map<String,Object> request) {
        registry.versions(request);return commands.execute(owner,"plan:"+action+":"+id,request,()->tx.execute(status->{
            var turn=turn(owner,session,id,true);var s=db.owned("aip_session",session,owner,true);
            if(action.equals("confirm")) {
                for(Object step:Json.list(turn.get("steps"))) ToolPermissionGuard.check(Json.string(Json.map(step).get("tool")));
                if(!"waiting_confirmation".equals(turn.get("status"))) throw new AiPictureException(409,"PLAN_STATE","没有待确认计划");
                if(!Objects.equals(turn.get("plan_hash"),request.getOrDefault("plan_hash",request.get("planHash")))) throw new AiPictureException(409,"PLAN_CHANGED","确认必须绑定 plan_hash，请刷新计划");
                if(Json.number(s.get("row_version"))!=Json.expected(request)||Json.number(s.get("revision"))!=Json.number(turn.get("base_revision"))) throw AiPictureException.conflict(Json.number(s.get("row_version")));
                db.execute("UPDATE aip_agent_turn SET status='executing',updated_at=:now WHERE id=:id",Json.of("id",id,"now",System.currentTimeMillis()));runs.continuePlan(id,owner);
            } else if(action.equals("cancel")) {
                // 核对行锁取得后的正式状态，迟到取消不能覆盖已结束的结果或失败重试入口。
                if(Set.of("succeeded","failed","cancelled","external_result_unknown").contains(Json.string(turn.get("status")))) throw new AiPictureException(409,"PLAN_STATE","当前没有可取消的步骤");
                db.execute("UPDATE aip_agent_turn SET status='cancelled',updated_at=:now WHERE id=:id",Json.of("id",id,"now",System.currentTimeMillis()));
                var active=db.query("SELECT run_id FROM aip_plan_step WHERE turn_id=:id AND status IN ('queued','running')",Json.of("id",id));
                for(var step:active) if(step.get("run_id")!=null) runs.cancel(owner,Json.string(step.get("run_id")));
                db.execute("UPDATE aip_plan_step SET status='cancelled' WHERE turn_id=:id AND status IN ('pending','queued','running')",Json.of("id",id));
            } else if(action.equals("retry")) {
                if(!"failed".equals(turn.get("status"))) throw new AiPictureException(409,"PLAN_STATE","只有失败计划可重试");
                for(Object step:Json.list(turn.get("steps"))) ToolPermissionGuard.check(Json.string(Json.map(step).get("tool")));
                if(db.count("SELECT COUNT(*) FROM aip_plan_step WHERE turn_id=:id",Json.of("id",id))==0) throw new AiPictureException(409,"PLAN_NOT_CREATED","规划未成功产生步骤，请重新发送指令");
                if("STALE_SELECTION".equals(turn.get("error_code"))) throw new AiPictureException(409,"STALE_SELECTION","计划选区画幅已失效，请重新规划");
                var retrySession=sessions.lock(owner,session,Json.expected(request));
                // 未知付费结果不进入无提示自动重试；普通失败由用户显式触发新尝试。
                if(db.count("SELECT COUNT(*) FROM aip_plan_step WHERE turn_id=:id AND status='external_result_unknown'",Json.of("id",id))>0) throw new AiPictureException(409,"EXTERNAL_RESULT_UNKNOWN","外部调用结果待核对，不能重复提交");
                runs.resumePlanBatches(id,owner,request,retrySession);
                db.execute("UPDATE aip_plan_step SET status='pending',run_id=NULL,attempt=attempt+1 WHERE turn_id=:id AND status='failed'",Json.of("id",id));
                db.execute("UPDATE aip_agent_turn SET status='executing',updated_at=:now WHERE id=:id",Json.of("id",id,"now",System.currentTimeMillis()));runs.continuePlan(id,owner);
            } else throw AiPictureException.invalid("未知计划动作");return dto(turn(owner,session,id,false));
        }));
    }
    private String describe(long owner,Map<String,Object> session) {
        var document=Json.map(session.get("document"));var names=new ArrayList<String>();
        for(Object object:Json.list(document.get("layers"))) {
            var layer=Json.map(object);String value=Json.string(layer.get("text"));String name="text".equals(layer.get("kind"))&&!value.isBlank()?"文字「"+value.substring(0,value.offsetByCodePoints(0,Math.min(8,value.codePointCount(0,value.length()))))+"」":Json.string(layer.get("name"));
            names.add(layer.get("id")+"="+name+(Json.bool(layer.get("visible"),true)?"":"·隐藏"));
        }
        var parts=new ArrayList<String>();parts.add("画幅 "+document.get("width")+"×"+document.get("height"));parts.add("图层 "+names.size()+" 个，自下而上（"+String.join("、",names)+"）");parts.add("layer_id 填图层 id 或名字；不填则作用在选区下最上层图像，换背景作用在背景层");parts.add("修订号 "+session.get("revision"));
        var current=db.owned("aip_asset",Json.string(session.get("current_asset_id")),owner,false);parts.add("当前图 "+current.get("format")+(Json.bool(current.get("has_alpha"),false)?"，含透明通道":""));
        var selected=sessions.selection(owner,Json.string(session.get("id")));int markers=selected==null?0:Json.list(selected.get("markers")).size();parts.add(selected==null?"当前无选区，区域工具会作用在整个图层":(markers>0?"已有选区，"+markers+" 个标点":"已有笔刷选区")+"（选区只限定区域，不限定图层；与 layer_id 同时给出时取交集）");
        String summary=String.join("；",parts);if(summary.length()>20000) throw AiPictureException.invalid("画布摘要超出规划上下文上限，请减少本轮目标涉及的图层");return summary;
    }
}
