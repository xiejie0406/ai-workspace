package com.ruoyi.paismart.application.jobs;
import java.util.Map;
/** 中心任务执行适配；只有中心更新任务真相，外部解析进程不持有数据库。 */
public interface PaismartJobHandler {
    boolean supports(String type);
    Map<String,Object> processJob(JobService.Job job) throws Exception;
    default void onCancelled(JobService.Job job) { }
    default void onRetry(JobService.Job job) { }
    /** 与中心任务失败在同一事务内同步业务终态，不执行外部调用或退还未知用量。 */
    default void onFailed(JobService.Job job) { }
}
