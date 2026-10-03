package com.paicli.memory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 中心已限定用户和工作区的记忆适配。错误必须抛出，不能回退本机持久化。 */
public interface CentralMemoryStore {
    List<MemoryEntry> list();
    MemoryWriteResult write(MemoryEntry entry, String replaceId, boolean allowConflict, String projectKey);
    boolean proposeAutomatic(MemoryEntry entry);
    Optional<MemoryEntry> verify(String id, Instant verifiedAt);
    boolean delete(String id);
    void clear();
}
