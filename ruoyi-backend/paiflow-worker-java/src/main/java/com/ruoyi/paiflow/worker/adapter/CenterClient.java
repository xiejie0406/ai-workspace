package com.ruoyi.paiflow.worker.adapter;

import com.ruoyi.paiflow.worker.domain.WorkerProtocol.ArtifactGrant;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Assignment;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Effect;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.EffectGrant;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.EffectCompletion;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Event;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Heartbeat;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Lease;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Result;

public interface CenterClient {
    /** 中心按固定节点开轮并冻结旧历史，不接收Worker声明的消息或答案。 */
    default java.util.Map<String, Object> openJavaHistory(Lease lease, String nodeId) {
        throw new com.ruoyi.paiflow.worker.service.WorkerException("RESOURCE_UNAVAILABLE", "中心Java历史端口未配置");
    }
    default java.util.Map<String, Object> resource(Lease lease, String resourceId) {
        throw new com.ruoyi.paiflow.worker.service.WorkerException("RESOURCE_UNAVAILABLE", "中心资源端口未配置");
    }
    default java.util.Map<String, Object> operation(Lease lease, String nodeId, String operation,
            java.util.Map<String, Object> parameters, String idempotencyKey) {
        throw new com.ruoyi.paiflow.worker.service.WorkerException("RESOURCE_UNAVAILABLE", "中心操作端口未配置");
    }
    /** 读取已登记模型操作的原帧，不重新发起供应商调用。 */
    default java.util.Map<String, Object> operationFrames(Lease lease, String operationId, long afterCursor, int limit) {
        throw new com.ruoyi.paiflow.worker.service.WorkerException("RESOURCE_UNAVAILABLE", "中心模型原帧端口未配置");
    }
    /** 回读同一工具操作的状态，不重新派发工具。 */
    default java.util.Map<String, Object> operationStatus(Lease lease, String operationId) {
        throw new com.ruoyi.paiflow.worker.service.WorkerException("RESOURCE_UNAVAILABLE", "中心工具状态端口未配置");
    }
    /** 请求停止并回读真实证明；stopped=false不能解释为供应商已停止。 */
    default java.util.Map<String, Object> cancelOperation(Lease lease, String operationId) {
        throw new com.ruoyi.paiflow.worker.service.WorkerException("RESOURCE_UNAVAILABLE", "中心操作停止端口未配置");
    }
    Lease claim();
    Assignment verify(Lease lease);
    /** 中心一次性执行许可；任何失败都不得进入引擎或再次请求同租约。 */
    void beginExecution(Lease lease, Assignment assignment);
    Heartbeat heartbeat(Lease lease);
    void event(Event event);
    void result(Lease lease, Result result);
    EffectGrant authorizeEffect(Lease lease, Effect effect);
    void completeEffect(Lease lease, EffectCompletion completion);
    ArtifactGrant prepareArtifact(Lease lease, String objectKey, String contentType, long size);
    void commitArtifact(Lease lease, String artifactId, String sha256, long size);
}
