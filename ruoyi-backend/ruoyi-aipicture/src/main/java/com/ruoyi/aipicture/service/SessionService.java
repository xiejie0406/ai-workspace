package com.ruoyi.aipicture.service;

import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.aipicture.config.*;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.persistence.*;
import com.ruoyi.aipicture.tool.*;

@Service @AiPictureEnabled
public class SessionService {
    private final AiPictureRepository db;private final AssetService assets;private final TransactionTemplate tx;private final ToolRegistry registry;private final CommandIdempotency commands;
    public SessionService(AiPictureRepository db,AssetService assets,@Qualifier("aipictureTransactionTemplate") TransactionTemplate tx,ToolRegistry registry,CommandIdempotency commands) { this.db=db;this.assets=assets;this.tx=tx;this.registry=registry;this.commands=commands; }
    public Map<String,Object> create(long owner,Map<String,Object> request) {
        registry.versions(request);String current=Json.uuid(request.get("current_asset_id"));var asset=assets.record(owner,current);
        var initial=new LinkedHashSet<String>();initial.add(current);for(Object id:Json.list(request.get("asset_ids"))) initial.add(Json.uuid(id));
        if(initial.size()>12) throw AiPictureException.invalid("初始候选最多 12 张");for(String id:initial) assets.record(owner,id);
        String title=title(request.get("title")==null?"未命名图片":request.get("title")),id=Json.id();var document=DocumentCommandService.documentOf(asset);registry.document(document);
        return commands.execute(owner,"session:create",request,()->tx.execute(status->{
            long now=System.currentTimeMillis();
            db.execute("INSERT INTO aip_session(id,user_id,title,original_asset_id,current_asset_id,document,history_seq,history_floor,created_at,updated_at) VALUES(:id,:owner,:title,:asset,:asset,:document,1,1,:now,:now)",Json.of("id",id,"owner",owner,"title",title,"asset",current,"document",Json.write(document),"now",now));
            int position=0;for(String item:initial) db.execute("INSERT INTO aip_session_asset(session_id,asset_id,user_id,position,latest_document) VALUES(:session,:asset,:owner,:position,:document)",Json.of("session",id,"asset",item,"owner",owner,"position",position++,"document",Json.write(item.equals(current)?document:DocumentCommandService.documentOf(assets.record(owner,item)))));
            // 原创建事件供记录显示，固定为非撤销下界，不增长画布或命令版本。
            var result=Json.of("asset_id",current,"document",document,"current_asset_id",current,"revision",0);
            db.execute("INSERT INTO aip_history(id,session_id,user_id,seq,action,params,result,before_document,after_document,before_asset_id,after_asset_id,created_at) VALUES(:history,:session,:owner,1,'create_session',:params,:result,:document,:document,:asset,:asset,:now)",Json.of("history",Json.id(),"session",id,"owner",owner,"params",Json.write(Map.of()),"result",Json.write(result),"document",Json.write(document),"asset",current,"now",now));
            return detail(owner,id);
        }));
    }
    public String title(Object value) {
        String title=Json.string(value).strip().replaceAll("\\s+"," ");
        if(title.isEmpty()||title.codePointCount(0,title.length())>80) throw AiPictureException.invalid("会话名需要 1–80 字符");return title;
    }
    public Map<String,Object> lock(long owner,String id,long expected) {
        var s=db.owned("aip_session",id,owner,true);if(Json.number(s.get("row_version"))!=expected) throw AiPictureException.conflict(Json.number(s.get("row_version")));return s;
    }
    public Map<String,Object> detail(long owner,String id) {
        var s=db.owned("aip_session",id,owner,false);s.remove("user_id");s.put("assets",assets.wall(owner,id));
        long seq=Json.number(s.get("history_seq")),floor=Json.number(s.get("history_floor"));
        s.put("can_undo",seq>floor);s.put("can_redo",db.count("SELECT COUNT(*) FROM aip_history WHERE session_id=:id AND user_id=:owner AND seq=:seq",Json.of("id",id,"owner",owner,"seq",seq+1))>0);
        var previous=db.one("SELECT before_document FROM aip_history WHERE session_id=:id AND user_id=:owner AND seq=:seq AND seq>:floor",Json.of("id",id,"owner",owner,"seq",seq,"floor",floor));
        s.put("previous_document",previous==null?null:previous.get("before_document"));return s;
    }
    public List<Map<String,Object>> list(long owner,int offset,int limit) { return db.query("SELECT id,title,original_asset_id,current_asset_id,revision,row_version,history_seq,created_at,updated_at FROM aip_session WHERE user_id=:owner ORDER BY updated_at DESC,id LIMIT :limit OFFSET :offset",Json.of("owner",owner,"offset",offset,"limit",limit)); }
    public long count(long owner) { return db.count("SELECT COUNT(*) FROM aip_session WHERE user_id=:owner",Json.of("owner",owner)); }
    public Map<String,Object> patch(long owner,String id,Map<String,Object> p) {
        registry.versions(p);return commands.execute(owner,"session:patch:"+id,p,()->tx.execute(status->{
            var s=lock(owner,id,Json.expected(p));ensureIdle(s);
            if(p.get("title")!=null) db.execute("UPDATE aip_session SET title=:title,row_version=row_version+1,updated_at=:now WHERE id=:id AND user_id=:owner",Json.of("title",title(p.get("title")),"id",id,"owner",owner,"now",System.currentTimeMillis()));
            if(p.get("current_asset_id")!=null) {
                String target=Json.uuid(p.get("current_asset_id"));var asset=assets.record(owner,target);
                Map<String,Object> document;
                if(Objects.equals(target,s.get("current_asset_id"))) document=Json.map(s.get("document"));
                else {
                    var snapshot=db.one("SELECT latest_document FROM aip_session_asset WHERE session_id=:session AND asset_id=:asset AND user_id=:owner",Json.of("session",id,"asset",target,"owner",owner));
                    if(snapshot==null) throw AiPictureException.invalid("素材不在此会话图片墙中");document=Json.map(snapshot.get("latest_document"));
                }
                // 沿源规则：单底图裁剪后点回该图恢复原画幅，多层与自定义层仍读独立快照。
                if(croppedBase(document,asset)) document=DocumentCommandService.documentOf(asset);
                saveDocument(owner,s,document,target,"switch_current",Json.of("current_asset_id",target));
            }
            return detail(owner,id);
        }));
    }
    private boolean croppedBase(Map<String,Object> document,Map<String,Object> asset) {
        var layers=Json.list(document.get("layers"));if(layers.size()!=1) return false;var layer=Json.map(layers.get(0));
        return "base".equals(layer.get("id"))&&Objects.equals(layer.get("asset_id"),asset.get("id"))
            &&(Json.number(document.get("width"))<Json.number(asset.get("width"))||Json.number(document.get("height"))<Json.number(asset.get("height")));
    }
    public void ensureIdle(Map<String,Object> s) { if(s.get("active_run_id")!=null) throw new AiPictureException(409,"SESSION_BUSY","画布有在途像素任务，请等待或取消"); }
    public void saveDocument(long owner,Map<String,Object> s,Map<String,Object> document,String current,String action,Map<String,Object> params) {
        registry.document(document);validateAssetReferences(owner,document);
        if(Json.equivalent(s.get("document"),document)&&Objects.equals(s.get("current_asset_id"),current)) return;
        appendHistory(owner,s,document,current,action,params,Map.of(),true);
    }
    /** 图片墙新增结果也留历史，但不改变画布修订或已有选区；空附加不截断重做。 */
    public void recordResult(long owner,Map<String,Object> s,List<String> assetIds,String action,Map<String,Object> params,Map<String,Object> result) {
        if(addWall(owner,Json.string(s.get("id")),assetIds)==0) return;
        var snapshot=Json.copy(result);snapshot.put("document",s.get("document"));snapshot.put("current_asset_id",s.get("current_asset_id"));snapshot.put("revision",s.get("revision"));
        appendHistory(owner,s,Json.map(s.get("document")),Json.string(s.get("current_asset_id")),action,params,snapshot,false);
    }
    private void appendHistory(long owner,Map<String,Object> s,Map<String,Object> document,String current,String action,Map<String,Object> params,Map<String,Object> result,boolean canvasChanged) {
        String id=Json.string(s.get("id"));long seq=Json.number(s.get("history_seq"))+1,now=System.currentTimeMillis(),floor=Math.max(Json.number(s.get("history_floor")),seq-20);
        db.execute("DELETE FROM aip_history WHERE session_id=:id AND user_id=:owner AND seq>:seq",Json.of("id",id,"owner",owner,"seq",seq-1));
        db.execute("INSERT INTO aip_history(id,session_id,user_id,seq,action,params,result,before_document,after_document,before_asset_id,after_asset_id,created_at) VALUES(:history,:id,:owner,:seq,:action,:params,:result,:before,:after,:beforeAsset,:afterAsset,:now)",Json.of("history",Json.id(),"id",id,"owner",owner,"seq",seq,"action",action,"params",Json.write(params),"result",Json.write(result),"before",Json.write(s.get("document")),"after",Json.write(document),"beforeAsset",s.get("current_asset_id"),"afterAsset",current,"now",now));
        db.execute("UPDATE aip_session SET document=:document,current_asset_id=:asset,"+(canvasChanged?"revision=revision+1,":"")+"row_version=row_version+1,history_seq=:seq,history_floor=:floor,updated_at=:now WHERE id=:id AND user_id=:owner",Json.of("document",Json.write(document),"asset",current,"seq",seq,"floor",floor,"now",now,"id",id,"owner",owner));
        if(canvasChanged) db.execute("UPDATE aip_session_asset SET latest_document=:document,document_version=document_version+1 WHERE session_id=:session AND asset_id=:asset AND user_id=:owner",Json.of("document",Json.write(document),"session",id,"asset",current,"owner",owner));
        // 记录展示窗口与撤销下界分别计算：初始事件在第 21 条时才被淘汰。
        db.execute("DELETE FROM aip_history WHERE session_id=:id AND user_id=:owner AND seq<=:floor",Json.of("id",id,"owner",owner,"floor",seq-20));
        if(canvasChanged) clearSelection(owner,id);
    }
    public void validateAssetReferences(long owner,Map<String,Object> doc) { for(Object object:Json.list(doc.get("layers"))) { var layer=Json.map(object);if(layer.get("asset_id")!=null) assets.record(owner,Json.uuid(layer.get("asset_id"))); } }
    public Map<String,Object> historyMove(long owner,String id,Map<String,Object> p,boolean undo) {
        registry.versions(p);return commands.execute(owner,(undo?"session:undo:":"session:redo:")+id,p,()->tx.execute(status->{
            var s=lock(owner,id,Json.expected(p));ensureIdle(s);long seq=Json.number(s.get("history_seq"));
            if(undo&&seq<=Json.number(s.get("history_floor"))) throw new AiPictureException(409,"HISTORY_BOUNDARY","没有可撤销的操作，已到 20 条历史窗口边界");
            var h=db.one("SELECT * FROM aip_history WHERE session_id=:id AND user_id=:owner AND seq=:seq",Json.of("id",id,"owner",owner,"seq",undo?seq:seq+1));
            if(h==null) throw new AiPictureException(409,"HISTORY_BOUNDARY",undo?"没有可撤销操作":"没有可重做操作");
            Object document=h.get(undo?"before_document":"after_document"),asset=h.get(undo?"before_asset_id":"after_asset_id");
            db.execute("UPDATE aip_session SET document=:document,current_asset_id=:asset,history_seq=:seq,revision=revision+1,row_version=row_version+1,updated_at=:now WHERE id=:id AND user_id=:owner",Json.of("document",Json.write(document),"asset",asset,"seq",undo?seq-1:seq+1,"now",System.currentTimeMillis(),"id",id,"owner",owner));
            db.execute("UPDATE aip_session_asset SET latest_document=:document,document_version=document_version+1 WHERE session_id=:id AND asset_id=:asset AND user_id=:owner",Json.of("document",Json.write(document),"id",id,"asset",asset,"owner",owner));clearSelection(owner,id);return detail(owner,id);
        }));
    }
    public List<Map<String,Object>> history(long owner,String id) { db.owned("aip_session",id,owner,false);return db.query("SELECT * FROM aip_history WHERE session_id=:id AND user_id=:owner ORDER BY seq DESC",Json.of("id",id,"owner",owner)); }
    public Map<String,Object> selection(long owner,String id) {
        var s=db.owned("aip_session",id,owner,false);var value=db.one("SELECT * FROM aip_selection WHERE session_id=:id AND user_id=:owner AND revision=:revision AND expires_at>:now AND state='ready'",Json.of("id",id,"owner",owner,"revision",s.get("revision"),"now",System.currentTimeMillis()));
        if(value==null) return null;value.put("mask",assets.get(owner,Json.string(value.get("mask_asset_id"))));value.remove("user_id");return value;
    }
    public void clearSelection(long owner,String id) { db.owned("aip_session",id,owner,false);db.execute("DELETE FROM aip_selection WHERE session_id=:id AND user_id=:owner",Json.of("id",id,"owner",owner)); }
    public int addWall(long owner,String id,List<String> assetIds) {
        int position=(int)db.count("SELECT COALESCE(MAX(position),-1)+1 FROM aip_session_asset WHERE session_id=:id AND user_id=:owner",Json.of("id",id,"owner",owner));
        int attached=0;
        for(String asset:assetIds) {
            var image=assets.record(owner,asset);attached+=db.execute("INSERT IGNORE INTO aip_session_asset(session_id,asset_id,user_id,position,latest_document) VALUES(:session,:asset,:owner,:position,:document)",Json.of("session",id,"asset",asset,"owner",owner,"position",position++,"document",Json.write(DocumentCommandService.documentOf(image))));
        }
        return attached;
    }
}
