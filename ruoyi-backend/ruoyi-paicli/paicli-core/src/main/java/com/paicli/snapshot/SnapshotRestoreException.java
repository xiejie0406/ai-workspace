package com.paicli.snapshot;

import java.io.IOException;
import java.util.List;

/** 已发生恢复失败；保留备份、实际尝试与补偿结果，不能当作未执行重试。 */
public final class SnapshotRestoreException extends IOException {
    private final String targetCommitId,backupCommitId;
    private final List<String> attemptedFiles,compensatedFiles,unrestoredFiles;
    public SnapshotRestoreException(String targetCommitId,String backupCommitId,List<String> attemptedFiles,List<String> compensatedFiles,List<String> unrestoredFiles,IOException cause) {
        super("快照恢复失败，已补偿 "+compensatedFiles.size()+" 项，未恢复 "+unrestoredFiles.size()+" 项；备份 "+backupCommitId,cause);
        this.targetCommitId=targetCommitId;this.backupCommitId=backupCommitId;
        this.attemptedFiles=List.copyOf(attemptedFiles);this.compensatedFiles=List.copyOf(compensatedFiles);this.unrestoredFiles=List.copyOf(unrestoredFiles);
    }
    public String targetCommitId(){return targetCommitId;}
    public String backupCommitId(){return backupCommitId;}
    public List<String> attemptedFiles(){return attemptedFiles;}
    public List<String> compensatedFiles(){return compensatedFiles;}
    public List<String> unrestoredFiles(){return unrestoredFiles;}
    public java.util.Map<String,Object> evidence(){return java.util.Map.of("status","RESTORE_FAILED","targetCommitId",targetCommitId,"backupCommitId",backupCommitId,"attemptedFiles",attemptedFiles,"compensatedFiles",compensatedFiles,"unrestoredFiles",unrestoredFiles);}
}
