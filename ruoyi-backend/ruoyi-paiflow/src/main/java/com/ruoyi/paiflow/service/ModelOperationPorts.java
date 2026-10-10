package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONObject;
import java.time.Instant;

/** 所有端口无默认实现。批准/引用核对只读本地中心事实；SDK派发/停止在事务之外。 */
public final class ModelOperationPorts {
    private ModelOperationPorts() {}
    public record RunCandidate(String runId,long ownerUserId,long spaceId,long workflowVersionId,String dslHash,
        String inputJson,String resourcesJson,String executionJson,Instant deadline,String engineProfile,String fingerprint) {
        public RunCandidate(String runId,long ownerUserId,long spaceId,long workflowVersionId,String dslHash,String inputJson,String resourcesJson,String executionJson,Instant deadline,String fingerprint){this(runId,ownerUserId,spaceId,workflowVersionId,dslHash,inputJson,resourcesJson,executionJson,deadline,"python",fingerprint);}
    }
    public record RunPermit(String fingerprint,String approvalRef,Instant expiresAt) {}
    /** 真实中心输入、固定节点、已落事件供批准方核对来源；Worker自报messages/hash不授予权限。 */
    public record CallCandidate(String runId,String leaseId,int attempt,long ownerUserId,long spaceId,long workflowVersionId,
        String dslHash,String nodeId,String iterationId,String resourceId,String resourceVersion,String provider,
        String parametersJson,String parametersHash,String inputJson,String nodeJson,String eventsJson,
        String runApprovalRef,Instant deadline,String checkpointSourcesJson,String verifiedInputsJson,String javaHistoryJson,String pythonHistoryJson,String engineProfile,String fingerprint) {
        public CallCandidate(String runId,String leaseId,int attempt,long ownerUserId,long spaceId,long workflowVersionId,String dslHash,String nodeId,String iterationId,String resourceId,String resourceVersion,String provider,String parametersJson,String parametersHash,String inputJson,String nodeJson,String eventsJson,String runApprovalRef,Instant deadline,String checkpointSourcesJson,String verifiedInputsJson,String javaHistoryJson,String engineProfile,String fingerprint){this(runId,leaseId,attempt,ownerUserId,spaceId,workflowVersionId,dslHash,nodeId,iterationId,resourceId,resourceVersion,provider,parametersJson,parametersHash,inputJson,nodeJson,eventsJson,runApprovalRef,deadline,checkpointSourcesJson,verifiedInputsJson,javaHistoryJson,null,engineProfile,fingerprint);}
        public CallCandidate(String runId,String leaseId,int attempt,long ownerUserId,long spaceId,long workflowVersionId,String dslHash,String nodeId,String iterationId,String resourceId,String resourceVersion,String provider,String parametersJson,String parametersHash,String inputJson,String nodeJson,String eventsJson,String runApprovalRef,Instant deadline,String checkpointSourcesJson,String verifiedInputsJson,String engineProfile,String fingerprint){this(runId,leaseId,attempt,ownerUserId,spaceId,workflowVersionId,dslHash,nodeId,iterationId,resourceId,resourceVersion,provider,parametersJson,parametersHash,inputJson,nodeJson,eventsJson,runApprovalRef,deadline,checkpointSourcesJson,verifiedInputsJson,null,engineProfile,fingerprint);}
        public CallCandidate(String runId,String leaseId,int attempt,long ownerUserId,long spaceId,long workflowVersionId,String dslHash,String nodeId,String iterationId,String resourceId,String resourceVersion,String provider,String parametersJson,String parametersHash,String inputJson,String nodeJson,String eventsJson,String runApprovalRef,Instant deadline,String checkpointSourcesJson,String verifiedInputsJson,String fingerprint){this(runId,leaseId,attempt,ownerUserId,spaceId,workflowVersionId,dslHash,nodeId,iterationId,resourceId,resourceVersion,provider,parametersJson,parametersHash,inputJson,nodeJson,eventsJson,runApprovalRef,deadline,checkpointSourcesJson,verifiedInputsJson,"python",fingerprint);}
        public CallCandidate(String runId,String leaseId,int attempt,long ownerUserId,long spaceId,long workflowVersionId,String dslHash,String nodeId,String iterationId,String resourceId,String resourceVersion,String provider,String parametersJson,String parametersHash,String inputJson,String nodeJson,String eventsJson,String runApprovalRef,Instant deadline,String checkpointSourcesJson,String fingerprint){this(runId,leaseId,attempt,ownerUserId,spaceId,workflowVersionId,dslHash,nodeId,iterationId,resourceId,resourceVersion,provider,parametersJson,parametersHash,inputJson,nodeJson,eventsJson,runApprovalRef,deadline,checkpointSourcesJson,null,fingerprint);}
        public CallCandidate(String runId,String leaseId,int attempt,long ownerUserId,long spaceId,long workflowVersionId,String dslHash,String nodeId,String iterationId,String resourceId,String resourceVersion,String provider,String parametersJson,String parametersHash,String inputJson,String nodeJson,String eventsJson,String runApprovalRef,Instant deadline,String fingerprint){this(runId,leaseId,attempt,ownerUserId,spaceId,workflowVersionId,dslHash,nodeId,iterationId,resourceId,resourceVersion,provider,parametersJson,parametersHash,inputJson,nodeJson,eventsJson,runApprovalRef,deadline,"[]",fingerprint);}
    }
    public record CallPermit(String fingerprint,String approvalRef,Instant expiresAt) {}
    public interface InvocationPolicy {
        RunPermit approveRun(RunCandidate candidate);
        boolean currentRun(RunCandidate candidate,RunPermit permit);
        CallPermit approveCall(CallCandidate candidate);
        boolean currentCall(CallCandidate candidate,CallPermit permit);
    }
    public interface CredentialMaterialPort {
        /** 本地只读核对准确版本引用可用，不提取或返回明文。 */
        boolean available(ModelRuntimeCatalog.FixedModel model);
        /** 仅受控私有内存桥接；不接受任意env/文件读取；调用时没有数据库事务。 */
        PrivateMaterial resolve(ModelRuntimeCatalog.FixedModel model);
    }
    public static final class PrivateMaterial {
        private final String credentialRef,credentialVersion,baseUrl,apiKey,appId,apiSecret;
        public PrivateMaterial(String ref,String version,String base,String key){this(ref,version,base,key,null,null);}
        public PrivateMaterial(String ref,String version,String base,String key,String app,String secret){credentialRef=ref;credentialVersion=version;baseUrl=base;apiKey=key;appId=app;apiSecret=secret;}
        public String credentialRef(){return credentialRef;} public String credentialVersion(){return credentialVersion;}
        public String baseUrl(){return baseUrl;} public String apiKey(){return apiKey;}
        public String appId(){return appId;} public String apiSecret(){return apiSecret;}
        @Override public String toString(){return "PrivateMaterial[redacted]";}
    }
    public interface ProducerBridge {
        /** 仅检查受控SDK/出口桥已装配，不发请求、不返回假Producer。 */
        boolean available(ModelRuntimeCatalog.FixedModel model);
        /** 在任何SDK发送前调用assertActive；每帧落中心并引用真实结束帧，不以本地close证明停止。 */
        DispatchAck submit(PrivateGrant grant,ProducerCallbacks callbacks);
        StopEvidence stop(String operationId,String grantId,String reason);
    }
    public record DispatchAck(String operationId,String grantId,boolean accepted) {}
    public record StopEvidence(String operationId,String grantId,boolean stopped,String proofId) {}
    /** 保全原Producer失败事实；原cancel在中心映射为公开cancelled，不据布尔自报授予停止证明。 */
    public record FailureFacts(String kind,String errorCode,boolean sendAttempted,boolean localClosed,boolean providerStopConfirmed) {}
    /** 无Bean getter，无自动JSON表示；parametersBase64固定中心原字节，明文只在privateMaterial内。 */
    public static final class PrivateGrant {
        private final String operationId,grantId,token,parametersJson,parametersHash,optionsJson,providerUid,engineProfile;
        private final JSONObject binding;
        private final Instant deadline;
        private final PrivateMaterial material;
        PrivateGrant(String operation,String grant,String token,JSONObject binding,String parameters,String hash,String options,Instant deadline,PrivateMaterial material){
            this(operation,grant,token,binding,parameters,hash,options,deadline,material,"");
        }
        PrivateGrant(String operation,String grant,String token,JSONObject binding,String parameters,String hash,String options,Instant deadline,PrivateMaterial material,String providerUid){
            this(operation,grant,token,binding,parameters,hash,options,deadline,material,providerUid,"python");
        }
        PrivateGrant(String operation,String grant,String token,JSONObject binding,String parameters,String hash,String options,Instant deadline,PrivateMaterial material,String providerUid,String engineProfile){
            if(!java.util.Set.of("python","java").contains(engineProfile))throw ModelOperationJson.error("模型私有执行引擎无效",409);
            operationId=operation;grantId=grant;this.token=token;this.binding=ModelOperationJson.copy(binding);parametersJson=parameters;parametersHash=hash;optionsJson=options;this.deadline=deadline;this.material=material;this.providerUid=java.util.Objects.requireNonNull(providerUid);this.engineProfile=engineProfile;
        }
        public String operationId(){return operationId;} public String grantId(){return grantId;} String token(){return token;}
        public JSONObject binding(){return ModelOperationJson.copy(binding);} public String parametersJson(){return parametersJson;}
        public String parametersHash(){return parametersHash;} public String optionsJson(){return optionsJson;}
        public Instant deadline(){return deadline;} public PrivateMaterial privateMaterial(){return material;}
        String providerUid(){return providerUid;}
        String engineProfile(){return engineProfile;}
        @Override public String toString(){return "PrivateGrant[redacted]";}
    }
    public interface ProducerCallbacks {
        JSONObject assertActive(PrivateGrant grant);
        JSONObject commitFrame(PrivateGrant grant,long sequence,JSONObject rawFrame);
        JSONObject finishStream(PrivateGrant grant,long lastSequence,long finishSequence,String finishReason);
        JSONObject confirmStop(PrivateGrant grant,StopEvidence evidence);
        void recordFailure(PrivateGrant grant,FailureFacts facts);
    }
}
