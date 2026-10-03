package com.ruoyi.aden.configuration;

import com.ruoyi.aden.migration.AdenFlywayFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 与 contracts/aden/configuration/current-v1.json 对齐的 Aden 类型化配置。 */
@ConfigurationProperties(prefix = "aden")
public class AdenProperties implements InitializingBean {
    private boolean enabled = true;
    private final Schema schema = new Schema();
    private final Workspace workspace = new Workspace();
    private final Runner runner = new Runner();
    private final Stream stream = new Stream();
    private final Outbox outbox = new Outbox();
    private final Idempotency idempotency = new Idempotency();
    private final Event event = new Event();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Schema getSchema() { return schema; }
    public Workspace getWorkspace() { return workspace; }
    public Runner getRunner() { return runner; }
    public Stream getStream() { return stream; }
    public Outbox getOutbox() { return outbox; }
    public Idempotency getIdempotency() { return idempotency; }
    public Event getEvent() { return event; }

    @Override
    public void afterPropertiesSet() {
        range("runner.session.ttl-seconds", runner.session.ttlSeconds, 3, 86400);
        range("runner.session.heartbeat-interval-seconds", runner.session.heartbeatIntervalSeconds, 1, 300);
        range("runner.delivery.lease-ttl-seconds", runner.delivery.leaseTtlSeconds, 2, 3600);
        range("runner.claim.max-batch-size", runner.claim.maxBatchSize, 1, 16);
        range("runner.session.max-capacity", runner.session.maxCapacity, 1, 32);
        range("stream.heartbeat-interval-seconds", stream.heartbeatIntervalSeconds, 1, 60);
        range("stream.connection-ttl-seconds", stream.connectionTtlSeconds, 3, 300);
        range("stream.replay-batch-size", stream.replayBatchSize, 1, 1000);
        range("stream.per-connection-queue-capacity", stream.perConnectionQueueCapacity, 1, 4096);
        range("stream.send-timeout-seconds", stream.sendTimeoutSeconds, 1, 60);
        range("outbox.claim-ttl-seconds", outbox.claimTtlSeconds, 1, 300);
        range("outbox.retry.initial-delay-milliseconds", outbox.retry.initialDelayMilliseconds, 10, 60000);
        range("outbox.retry.max-delay-milliseconds", outbox.retry.maxDelayMilliseconds, 10, 3600000);
        range("outbox.retry.max-attempts", outbox.retry.maxAttempts, 1, 100);
        range("idempotency.in-progress-ttl-seconds", idempotency.inProgressTtlSeconds, 1, 3600);
        range("idempotency.retention-seconds", idempotency.retentionSeconds, 5, 2678400);
        range("event.retention-seconds", event.retentionSeconds, 10, 2678400);
        require(runner.session.ttlSeconds >= 3 * runner.session.heartbeatIntervalSeconds,
                "runner session TTL 必须至少为心跳间隔的 3 倍");
        require(runner.delivery.leaseTtlSeconds >= 2 * runner.session.heartbeatIntervalSeconds,
                "delivery lease TTL 必须至少为心跳间隔的 2 倍");
        require(stream.connectionTtlSeconds >= 3 * stream.heartbeatIntervalSeconds,
                "stream connection TTL 必须至少为心跳间隔的 3 倍");
        require(stream.sendTimeoutSeconds < stream.connectionTtlSeconds,
                "stream send timeout 必须小于 connection TTL");
        require(outbox.retry.maxDelayMilliseconds >= outbox.retry.initialDelayMilliseconds,
                "outbox 最大重试延迟不得小于初始延迟");
        require(idempotency.retentionSeconds >= runner.delivery.leaseTtlSeconds,
                "幂等事实保留时间不得短于 delivery lease TTL");
        require(event.retentionSeconds >= stream.connectionTtlSeconds,
                "事件保留时间不得短于 stream connection TTL");
        if (enabled && schema.expectedDatabase.isBlank()) {
            throw new IllegalStateException("启用 Aden schema guard 时必须配置 aden.schema.expected-database");
        }
    }

    private static void range(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalStateException("aden." + name + " 必须在 " + minimum + ".." + maximum + " 范围内");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public static final class Workspace {
        private String defaultWorkspaceId = "";
        public String getDefaultWorkspaceId() { return defaultWorkspaceId; }
        public void setDefaultWorkspaceId(String value) { defaultWorkspaceId = value == null ? "" : value.trim(); }
    }

    public static final class Schema {
        private String expectedDatabase = "";
        public String getExpectedDatabase() { return expectedDatabase; }
        public void setExpectedDatabase(String value) { this.expectedDatabase = value == null ? "" : value.trim(); }
        public int getExpectedVersion() { return Integer.parseInt(AdenFlywayFactory.EXPECTED_VERSION); }
    }

    public static final class Runner {
        private final Session session = new Session();
        private final Delivery delivery = new Delivery();
        private final Claim claim = new Claim();
        public Session getSession() { return session; }
        public Delivery getDelivery() { return delivery; }
        public Claim getClaim() { return claim; }
    }

    public static final class Session {
        private int ttlSeconds = 900;
        private int heartbeatIntervalSeconds = 15;
        private int maxCapacity = 32;
        public int getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(int value) { this.ttlSeconds = value; }
        public int getHeartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }
        public void setHeartbeatIntervalSeconds(int value) { this.heartbeatIntervalSeconds = value; }
        public int getMaxCapacity() { return maxCapacity; }
        public void setMaxCapacity(int value) { this.maxCapacity = value; }
    }

    public static final class Delivery {
        private int leaseTtlSeconds = 60;
        public int getLeaseTtlSeconds() { return leaseTtlSeconds; }
        public void setLeaseTtlSeconds(int value) { this.leaseTtlSeconds = value; }
    }

    public static final class Claim {
        private int maxBatchSize = 8;
        public int getMaxBatchSize() { return maxBatchSize; }
        public void setMaxBatchSize(int value) { this.maxBatchSize = value; }
    }

    public static final class Stream {
        private int heartbeatIntervalSeconds = 15;
        private int connectionTtlSeconds = 300;
        private int replayBatchSize = 100;
        private int perConnectionQueueCapacity = 256;
        private int sendTimeoutSeconds = 10;
        public int getHeartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }
        public void setHeartbeatIntervalSeconds(int value) { this.heartbeatIntervalSeconds = value; }
        public int getConnectionTtlSeconds() { return connectionTtlSeconds; }
        public void setConnectionTtlSeconds(int value) { this.connectionTtlSeconds = value; }
        public int getReplayBatchSize() { return replayBatchSize; }
        public void setReplayBatchSize(int value) { this.replayBatchSize = value; }
        public int getPerConnectionQueueCapacity() { return perConnectionQueueCapacity; }
        public void setPerConnectionQueueCapacity(int value) { this.perConnectionQueueCapacity = value; }
        public int getSendTimeoutSeconds() { return sendTimeoutSeconds; }
        public void setSendTimeoutSeconds(int value) { this.sendTimeoutSeconds = value; }
    }

    public static final class Outbox {
        private int claimTtlSeconds = 30;
        private final Retry retry = new Retry();
        public int getClaimTtlSeconds() { return claimTtlSeconds; }
        public void setClaimTtlSeconds(int value) { this.claimTtlSeconds = value; }
        public Retry getRetry() { return retry; }
    }

    public static final class Retry {
        private int initialDelayMilliseconds = 1000;
        private int maxDelayMilliseconds = 60000;
        private int maxAttempts = 10;
        public int getInitialDelayMilliseconds() { return initialDelayMilliseconds; }
        public void setInitialDelayMilliseconds(int value) { this.initialDelayMilliseconds = value; }
        public int getMaxDelayMilliseconds() { return maxDelayMilliseconds; }
        public void setMaxDelayMilliseconds(int value) { this.maxDelayMilliseconds = value; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int value) { this.maxAttempts = value; }
    }

    public static final class Idempotency {
        private int inProgressTtlSeconds = 120;
        private int retentionSeconds = 604800;
        public int getInProgressTtlSeconds() { return inProgressTtlSeconds; }
        public void setInProgressTtlSeconds(int value) { this.inProgressTtlSeconds = value; }
        public int getRetentionSeconds() { return retentionSeconds; }
        public void setRetentionSeconds(int value) { this.retentionSeconds = value; }
    }

    public static final class Event {
        private int retentionSeconds = 604800;
        public int getRetentionSeconds() { return retentionSeconds; }
        public void setRetentionSeconds(int value) { this.retentionSeconds = value; }
    }
}
