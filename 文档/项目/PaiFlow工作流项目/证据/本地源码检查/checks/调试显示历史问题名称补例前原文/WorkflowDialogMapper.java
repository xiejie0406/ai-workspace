package com.ruoyi.paiflow.mapper;

import com.ruoyi.paiflow.domain.WorkflowDialogRecord;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkflowDialogMapper {
    int insert(WorkflowDialogRecord row);
    List<WorkflowDialogRecord> list(@Param("space") long space, @Param("workflow") long workflow,
                                   @Param("owner") long owner, @Param("type") int type);
    int clear(@Param("space") long space, @Param("workflow") long workflow,
              @Param("owner") long owner, @Param("type") int type);
}
