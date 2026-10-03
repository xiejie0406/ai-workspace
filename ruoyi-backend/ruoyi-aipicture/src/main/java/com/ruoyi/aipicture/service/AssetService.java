package com.ruoyi.aipicture.service;

import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.aipicture.config.*;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.persistence.*;
import com.ruoyi.aipicture.storage.*;

@Service @AiPictureEnabled
public class AssetService {
    private final AiPictureRepository db; private final AiPictureStorage storage;private final TransactionTemplate tx;
    public AssetService(AiPictureRepository db,AiPictureStorage storage,@Qualifier("aipictureTransactionTemplate") TransactionTemplate tx) { this.db=db;this.storage=storage;this.tx=tx; }
    public Map<String,Object> upload(long owner,byte[] bytes) throws IOException {
        return tx.execute(status->{
            Map<String,Object> asset;
            try {asset=storage.image(bytes,null,"assets/"+owner);} catch(IOException error) {throw new AiPictureException(503,"STORAGE_UNAVAILABLE","图片保存失败");}
            asset.put("kind","original");asset.put("source","upload");asset.put("user_id",owner);asset.put("created_at",System.currentTimeMillis());asset.put("status","ready");
            insert(asset,null,null,null);return dto(asset);
        });
    }
    public void insert(Map<String,Object> asset,String run,Integer attempt,Integer index) {
        var p=new LinkedHashMap<>(asset);p.put("run_id",run);p.put("attempt",attempt);p.put("artifact_index",index);p.put("provider",Json.write(asset.getOrDefault("provider",Map.of())));
        db.execute("INSERT INTO aip_asset(id,user_id,kind,source,object_key,sha256,format,width,height,size_bytes,has_alpha,provider,created_at,status,run_id,attempt,artifact_index) VALUES(:id,:user_id,:kind,:source,:object_key,:sha256,:format,:width,:height,:size_bytes,:has_alpha,:provider,:created_at,:status,:run_id,:attempt,:artifact_index)",p);
    }
    public Map<String,Object> get(long owner,String id) { return dto(record(owner,id)); }
    /** owner 与正式采用状态共同决定可用素材；私有产物重交直接返回 dto，不经过此入口。 */
    public Map<String,Object> record(long owner,String id) {
        var asset=db.owned("aip_asset",id,owner,false);
        if(!"ready".equals(asset.get("status"))) throw AiPictureException.missing();return asset;
    }
    public List<Map<String,Object>> list(long owner,int offset,int limit) {
        return db.query("SELECT * FROM aip_asset WHERE user_id=:owner AND status='ready' AND kind<>'mask' AND format IN ('png','jpg','webp') ORDER BY created_at DESC,id LIMIT :limit OFFSET :offset",Json.of("owner",owner,"limit",limit,"offset",offset)).stream().map(this::dto).toList();
    }
    public long count(long owner) { return db.count("SELECT COUNT(*) FROM aip_asset WHERE user_id=:owner AND status='ready' AND kind<>'mask' AND format IN ('png','jpg','webp')",Json.of("owner",owner)); }
    public List<Map<String,Object>> library(long owner) {return library(owner,50);}
    public List<Map<String,Object>> library(long owner,int limit) {
        if(limit<1||limit>200) throw AiPictureException.invalid("素材数量需要 1–200");
        var groups=db.query("SELECT id,title,current_asset_id,updated_at FROM aip_session WHERE user_id=:owner ORDER BY updated_at DESC,id LIMIT :limit",Json.of("owner",owner,"limit",limit));
        for(var group:groups) {var visible=wall(owner,Json.string(group.get("id"))).stream().filter(a->!Set.of("mask","subject","background").contains(Json.string(a.get("kind")))).toList();var cover=get(owner,Json.string(group.get("current_asset_id")));group.put("assets",visible.isEmpty()?List.of(cover):visible);group.put("session_id",group.get("id"));group.put("cover",cover);}
        var orphan=db.query("SELECT a.* FROM aip_asset a LEFT JOIN aip_session_asset sa ON sa.asset_id=a.id AND sa.user_id=:owner WHERE a.user_id=:owner AND sa.asset_id IS NULL AND a.kind NOT IN ('mask','subject','background') AND a.format IN ('png','jpg','webp') AND a.status='ready' ORDER BY a.created_at DESC,a.id LIMIT :limit",Json.of("owner",owner,"limit",limit));
        if(!orphan.isEmpty()) groups.add(Json.of("id",null,"session_id",null,"title","未分组素材","updated_at",orphan.get(0).get("created_at"),"cover",dto(orphan.get(0)),"assets",orphan.stream().map(this::dto).toList()));return groups;
    }
    public List<Map<String,Object>> wall(long owner,String session) {
        return db.query("SELECT a.* FROM aip_session_asset sa JOIN aip_asset a ON a.id=sa.asset_id AND a.user_id=sa.user_id WHERE sa.session_id=:session AND sa.user_id=:owner AND a.status='ready' ORDER BY sa.position,sa.asset_id",Json.of("session",session,"owner",owner)).stream().map(this::dto).toList();
    }
    public byte[] download(long owner,String id) throws IOException { return storage.read(Json.string(record(owner,id).get("object_key"))); }
    public Map<String,Object> dto(Map<String,Object> asset) {
        var result=new LinkedHashMap<>(asset);result.remove("object_key");result.remove("user_id");result.remove("lease_token");
        result.put("image_format","jpg".equals(asset.get("format"))?"JPEG":Json.string(asset.get("format")).toUpperCase(Locale.ROOT));
        if(asset.get("created_at") instanceof Number) result.put("created_at",java.time.Instant.ofEpochMilli(Json.number(asset.get("created_at"))).toString());
        result.put("url","/aipicture/assets/"+asset.get("id")+"/download");return result;
    }
}
