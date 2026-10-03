package com.ruoyi.quartz.task;

import com.ruoyi.paiflow.service.RunLeaseService;
import com.ruoyi.paiflow.service.CheckpointService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 沿用若依Quartz任务扩展包与白名单；不新增定时器、不自动创建或启用sys_job。 */
@Component("paiflowRunTask")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class PaiflowRunTask {
    private final RunLeaseService runs;private final CheckpointService checkpoints;
    public PaiflowRunTask(RunLeaseService runs,CheckpointService checkpoints) { this.runs=runs;this.checkpoints=checkpoints; }
    public void reconcile() { runs.reconcileExpiredRuns();checkpoints.reconcileEnded(); }
}
