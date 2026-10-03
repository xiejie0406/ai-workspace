package com.ruoyi.aipicture.service;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.aipicture.config.*;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.persistence.*;

/** 仅包裹短事务命令；Provider 调用不能放进此事务。 */
@Service @AiPictureEnabled
public class CommandIdempotency {
    private final AiPictureRepository db;private final TransactionTemplate tx;
    public CommandIdempotency(AiPictureRepository db,@Qualifier("aipictureTransactionTemplate") TransactionTemplate tx) {this.db=db;this.tx=tx;}
    public Map<String,Object> execute(long owner,String command,Map<String,Object> request,Supplier<Map<String,Object>> action) {
        String key=Json.idem(request),hash=RunService.hash(request);
        return AiPictureTransactions.execute(tx,status->{
            var parameters=Json.of("owner",owner,"command",command,"key",key,"hash",hash,"now",System.currentTimeMillis());
            var old=db.one("SELECT * FROM aip_command WHERE user_id=:owner AND command=:command AND idempotency_key=:key FOR UPDATE",parameters);
            if(old!=null) {if(!Objects.equals(hash,old.get("request_hash"))) throw new AiPictureException(409,"IDEMPOTENCY_CONFLICT","幂等标识已用于不同命令内容");return Json.map(old.get("result"));}
            db.execute("INSERT INTO aip_command(user_id,command,idempotency_key,request_hash,result,created_at) VALUES(:owner,:command,:key,:hash,'{}',:now)",parameters);
            var result=action.get();parameters.put("result",Json.write(result));db.execute("UPDATE aip_command SET result=:result WHERE user_id=:owner AND command=:command AND idempotency_key=:key",parameters);return result;
        });
    }
}
