package com.paicli.runtime.center;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** 联机终端与中心的唯一业务入口；实现不得以本地任务库或本地审批替代中心。 */
public interface CenterCommandGateway extends AutoCloseable {
    record OpenSession(String workspaceId, String runnerId, String sessionId) {}
    record Session(String id, String workspaceId, String runnerId, String mode,
                   String provider, String model, String modelId, long lastSequence) {
        public Session(String id, String workspaceId, String runnerId, String mode,
                       String provider, String model, long lastSequence) {
            this(id, workspaceId, runnerId, mode, provider, model, null, lastSequence);
        }
    }
    record Attachment(String name, String mimeType, String base64Data) {}
    record Turn(String id, String status) {}
    record Event(long sequence, String type, Map<String, Object> payload, boolean terminal) {}
    record CommandResult(boolean successful, String message, Map<String, Object> data) {}

    Session open(OpenSession request) throws IOException;

    Turn submit(String sessionId, String text, String mode, List<Attachment> attachments) throws IOException;

    /** name 不含前导斜杠；参数保留用户原文。未知/无权限命令由中心明确拒绝。 */
    CommandResult command(String sessionId, String name, String arguments) throws IOException;

    /** 完整导出按固定截止分页取原账本；旧测试Gateway在实际调用时明确报缺能力。 */
    default CommandResult readLedger(String sessionId, long afterSequence, long endSequence, int limit) throws IOException {
        throw new IOException("Gateway未实现中心原账本分页，不能把部分记录当作完整导出");
    }

    /** 当前用户按字节边界读取本会话工具产物；不得使用Runner许可替代用户身份。 */
    default CommandResult readArtifactText(String sessionId, String artifactId, long offset, int limit) throws IOException {
        throw new IOException("Gateway未实现当前会话产物分页，不能把首段当作完整结果");
    }

    /** 按确认序号增量续查；重复事件可由消费者依 sequence 去重。 */
    List<Event> events(String sessionId, long afterSequence, int limit) throws IOException;

    /** 多工作区控制任务按自己的归属查询；不能借当前会话事件流等待其他任务。 */
    default List<Event> taskEvents(String taskId, long afterSequence, int limit) throws IOException {
        throw new IOException("Gateway未实现任务归属事件查询，不能确认其他工作区任务已完成");
    }

    void cancel(String sessionId, String turnId) throws IOException;

    @Override
    default void close() throws IOException {}
}
