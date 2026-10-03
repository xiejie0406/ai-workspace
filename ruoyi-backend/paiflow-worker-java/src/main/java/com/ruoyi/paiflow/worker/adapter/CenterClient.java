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
