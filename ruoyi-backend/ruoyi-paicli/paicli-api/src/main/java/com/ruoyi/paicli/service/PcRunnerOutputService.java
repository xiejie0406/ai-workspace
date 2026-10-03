package com.ruoyi.paicli.service;
import com.ruoyi.paicli.domain.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 原工具offload保存完整文本于私密产物；虚拟read_file仍须正式许可消费。 */
public class PcRunnerOutputService {
    private final PcStore store;private final PcRunnerService runner;private final PcToolService tools;private final PcArtifactService artifacts;private final PcIdempotency idem;
    public PcRunnerOutputService(PcStore s,PcRunnerService r,PcToolService t,PcArtifactService a,PcIdempotency i){store=s;runner=r;tools=t;artifacts=a;idem=i;}
    @Transactional("paicliTransactionManager")
    public Map<String,Object> upload(PcRunnerPrincipal actor,String key,Map<String,Object> input){return idem.run("runner:"+actor.runnerId()+":tool-output",key,input,()->{PcRecord task=runner.lease(actor,input,false),call=tools.consumedTool(actor,input);if(!call.getKind().equals(PcJson.text(input,"toolName")))throw PcException.deny("输出工具类型与已消费许可不符");String content=PcJson.text(input,"content");if(!PcJson.digest(content).equals(PcJson.text(input,"sha256")))throw PcException.conflict("完整工具输出SHA-256不一致");runner.outputBudget(task,content,false);Map<String,Object> result=new LinkedHashMap<>(artifacts.storeRunnerOutput(task.getWorkspaceId(),task.getOwnerId(),task.getSessionId(),task.getId(),PcJson.text(input,"toolCallId"),content));result.put("artifactId",result.get("id"));return result;});}
    @Transactional("paicliTransactionManager")
    public Map<String,Object> image(PcRunnerPrincipal actor,String key,Map<String,Object> input){return idem.run("runner:"+actor.runnerId()+":tool-image",key,input,()->{PcRecord task=runner.lease(actor,input,false);tools.consumedTool(actor,input);Map<String,Object> part=PcJson.object(input,"part");runner.outputBudget(task,part,false);Map<String,Object> stored=artifacts.storeLedgerImages(Map.of("part",part),task.getWorkspaceId(),task.getOwnerId(),task.getSessionId(),task.getId());return PcJson.object(stored,"part");});}
    @Transactional("paicliTransactionManager")
    public Map<String,Object> begin(PcRunnerPrincipal actor,String key,Map<String,Object> input){
        PcRecord task=runner.lease(actor,input,false),call=tools.consumedTool(actor,input);outputCall(call,input);
        return idem.run("runner:"+actor.runnerId()+":output-begin",key,input,()->{
            long size=PcJson.number(input,"size");String uploadKey="output:"+task.getId()+":"+PcJson.text(input,"toolCallId");boolean fresh=store.mapper.findKey(PcTable.ARTIFACT,uploadKey,true)==null;
            Map<String,Object> result=artifacts.beginRunnerOutput(task.getWorkspaceId(),task.getOwnerId(),task.getSessionId(),task.getId(),PcJson.text(input,"toolCallId"),PcJson.number(input,"leaseId"),PcJson.number(input,"fencingToken"),size,PcJson.text(input,"sha256"));
            if(fresh){PcRecord lease=store.mapper.findKey(PcTable.TASK_LEASE,"task:"+task.getId(),true);Map<String,Object> d=store.data(lease);long used=d.get("artifactBytes") instanceof Number n?n.longValue():0;if(used+size>512L*1024*1024)throw new PcException(413,"PC_TASK_ARTIFACT_LIMIT","单任务分块产物超过512MB");d.put("artifactBytes",used+size);store.save(PcTable.TASK_LEASE,lease,d);}
            return result;
        });
    }
    @Transactional("paicliTransactionManager")
    public Map<String,Object> chunk(PcRunnerPrincipal actor,String key,long id,Map<String,Object> input){
        PcRecord task=runner.lease(actor,input,false),call=tools.consumedTool(actor,input);outputCall(call,input);
        String encoded=PcJson.text(input,"base64");if(encoded.length()>((PcArtifactService.OUTPUT_CHUNK_BYTES+2L)/3)*4)throw new PcException(413,"PC_OUTPUT_CHUNK_SIZE","输出分块超过4MB");byte[] bytes;try{bytes=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException invalid){throw PcException.invalid("输出分块base64非法");}
        String hash=PcArtifactService.digest(bytes);if(!hash.equals(PcJson.text(input,"sha256")))throw PcException.conflict("输出分块摘要不一致");Map<String,Object> fingerprint=new LinkedHashMap<>(input);fingerprint.remove("base64");fingerprint.put("uploadId",id);
        long index=PcJson.number(input,"index");if(index<0||index>=32)throw PcException.invalid("输出分块序号应为0至31");
        return idem.run("runner:"+actor.runnerId()+":output-chunk",key,fingerprint,()->artifacts.appendRunnerOutput(id,task.getWorkspaceId(),task.getOwnerId(),task.getSessionId(),task.getId(),PcJson.text(input,"toolCallId"),PcJson.number(input,"leaseId"),PcJson.number(input,"fencingToken"),(int)index,bytes,hash));
    }
    @Transactional("paicliTransactionManager")
    public Map<String,Object> complete(PcRunnerPrincipal actor,String key,long id,Map<String,Object> input){
        PcRecord task=runner.lease(actor,input,false),call=tools.consumedTool(actor,input);outputCall(call,input);Map<String,Object> fingerprint=new LinkedHashMap<>(input);fingerprint.put("uploadId",id);
        return idem.run("runner:"+actor.runnerId()+":output-complete",key,fingerprint,()->artifacts.completeRunnerOutput(id,task.getWorkspaceId(),task.getOwnerId(),task.getSessionId(),task.getId(),PcJson.text(input,"toolCallId"),PcJson.number(input,"leaseId"),PcJson.number(input,"fencingToken")));
    }
    private void outputCall(PcRecord call,Map<String,Object> input){if(!call.getKind().equals(PcJson.text(input,"toolName")))throw PcException.deny("输出工具类型与已消费许可不符");}
    @Transactional("paicliTransactionManager")
    public Map<String,Object> read(PcRunnerPrincipal actor,long id,Map<String,Object> input){PcRecord task=runner.lease(actor,input,false);if(Boolean.TRUE.equals(input.get("metadataOnly")))return artifacts.runnerOutputMetadata(id,task.getWorkspaceId(),task.getOwnerId(),task.getSessionId());PcRecord call=tools.consumedTool(actor,input);Map<String,Object> args=PcJson.object(store.data(call),"arguments");if(!call.getKind().equals("read_file")||!PcJson.text(args,"path").equals(".paicli/tool-outputs/center/"+id+".txt"))throw PcException.deny("输出读取必须消费指定虚拟read_file许可");int offset=input.containsKey("offset")?(int)PcJson.number(input,"offset"):1,limit=input.containsKey("limit")?(int)PcJson.number(input,"limit"):2000;long expectedOffset=args.containsKey("offset")?PcJson.number(args,"offset"):1,expectedLimit=args.containsKey("limit")?PcJson.number(args,"limit"):2000;if(offset!=expectedOffset||limit!=expectedLimit)throw PcException.conflict("输出分段范围与已批准参数不同");return artifacts.runnerOutputText(id,task.getWorkspaceId(),task.getOwnerId(),task.getSessionId(),offset,limit);}
}
