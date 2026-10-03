package com.ruoyi.paicli.service;

import com.ruoyi.paicli.domain.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;

/** 必须运行于业务事务中。唯一键仲裁并发，重复请求不重新产生任务或副作用。 */
public final class PcIdempotency {
    private final PcStore store;private final PcCrypto crypto;
    public PcIdempotency(PcStore store,PcCrypto crypto){this.store=store;this.crypto=crypto;}
    public Map<String,Object> run(String scope,String key,Object input,Supplier<Map<String,Object>> action){
        if(key==null||!key.matches("[A-Za-z0-9._:-]{8,128}"))throw PcException.invalid("需提供8至128位Idempotency-Key");
        String id=PcJson.digest(scope+":"+key), fingerprint=store.json.hash(input);
        PcRecord old=store.mapper.findKey(PcTable.IDEMPOTENCY,id,true);
        if(old!=null)return replay(old,id,fingerprint);
        PcRecord row;
        try{row=store.insert(PcTable.IDEMPOTENCY,null,null,null,null,null,"REQUEST","IN_PROGRESS",id,Map.of("fingerprint",fingerprint));}
        catch(DuplicateKeyException e){throw PcException.conflict("同一幂等请求正在执行，等待后以相同键重试");}
        Map<String,Object> output=action.get();row.setState("COMPLETED");row.setExpiresAt(store.clock.instant().plusSeconds(86400*90L));
        store.save(PcTable.IDEMPOTENCY,row,Map.of("fingerprint",fingerprint,"response",crypto.seal(id,store.json.write(output))));return output;
    }
    private Map<String,Object> replay(PcRecord r,String id,String fingerprint){
        Map<String,Object> p=store.data(r);if(!fingerprint.equals(p.get("fingerprint")))throw new PcException(409,"PC_IDEMPOTENCY_REUSED","相同幂等键不能用于不同内容");
        if(!"COMPLETED".equals(r.getState()))throw PcException.conflict("请求仍在处理中，请稍后用相同键重试");
        if(r.getExpiresAt()!=null&&!r.getExpiresAt().isAfter(store.clock.instant()))throw new PcException(410,"PC_IDEMPOTENCY_EXPIRED","幂等记录已过期，请先核对原操作结果");
        return store.json.read(crypto.open(id,PcJson.text(p,"response")));
    }
}
