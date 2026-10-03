package com.ruoyi.paismart.application.operations;

import com.ruoyi.paismart.application.jobs.JobService;
import com.ruoyi.paismart.application.spaces.SpaceService;
import com.ruoyi.paismart.configuration.PaismartEnabled;
import com.ruoyi.paismart.infrastructure.Jsons;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** 运维按钮只允许动作；清单、对象键和任务正文仍按当前业务管理范围裁决。 */
@Service
@PaismartEnabled
public class OperationAccessService {
    private final JdbcTemplate db;private final SpaceService spaces;private com.ruoyi.paismart.application.documents.UploadService uploads;
    public OperationAccessService(@Qualifier("paismartJdbc")JdbcTemplate db,SpaceService spaces){this.db=db;this.spaces=spaces;}
    @org.springframework.beans.factory.annotation.Autowired
    public void uploadAccess(com.ruoyi.paismart.application.documents.UploadService uploads){this.uploads=uploads;}
    public boolean document(SpaceService.Scope actor,String id){
        var rows=db.queryForList("SELECT user_id,space_id,visibility FROM ps_document WHERE id=?",id);
        if(rows.isEmpty())return false;var r=rows.get(0);
        return spaces.canManageDocument(actor,((Number)r.get("user_id")).longValue(),(String)r.get("space_id"),(String)r.get("visibility"));
    }
    public boolean plan(SpaceService.Scope actor,Map<String,Object> record){
        if(actor.administrator())return true;
        Map<String,Object> manifest=Jsons.map(record.get("payload_json").toString());
        boolean hasUploads=false;
        if(manifest.containsKey("uploads")) {
            if(!(manifest.get("uploads") instanceof List<?> uploadRows)) return false;
            hasUploads=!uploadRows.isEmpty();
            if(uploadRows.size()>1000 || (hasUploads&&uploads==null)) return false;
            for(Object item:uploadRows) {
                if(!(item instanceof Map<?,?> row)) return false;
                try {uploads.requireCleanupAccess(actor,Objects.toString(row.get("uploadId"),""));}
                catch(RuntimeException denied){return false;}
            }
        }
        boolean importFiles="bootstrap-import".equals(record.get("type"));Object raw=manifest.get(importFiles?"files":"documents");
        if(!(raw instanceof List<?> entries))return false;
        if(entries.isEmpty())return hasUploads || ((Number)record.get("created_by")).longValue()==actor.userId();
        for(Object item:entries){if(!(item instanceof Map<?,?> row))return false;
            if(row.containsKey("uploadId")) {
                if(uploads==null)return false;
                try {uploads.requireCleanupAccess(actor,Objects.toString(row.get("uploadId"),""));}catch(RuntimeException denied){return false;}
                continue;
            }
            if(importFiles){String space=Objects.toString(row.get("spaceId"),"");if(space.isBlank()){if(((Number)record.get("created_by")).longValue()!=actor.userId())return false;}else if(!spaces.canManageSpace(actor,space))return false;}
            else {String id=Objects.toString(row.containsKey("documentId")?row.get("documentId"):row.get("id"),"");if(!document(actor,id))return false;}
        }
        return true;
    }
    public boolean job(SpaceService.Scope actor,JobService.Job job){
        if(actor.administrator())return true;
        if("OPERATION_EXECUTE".equals(job.type())){String plan=Objects.toString(job.payload().get("planId"),"");var rows=db.queryForList("SELECT * FROM ps_operation_plan WHERE id=?",plan);return !rows.isEmpty()&&plan(actor,rows.get(0));}
        if(job.documentId()!=null)return document(actor,job.documentId());
        return job.userId()==actor.userId();
    }
    public void requirePlan(SpaceService.Scope actor,Map<String,Object> row){if(!plan(actor,row))throw new AccessDeniedException("无权管理该计划中的业务资源");}
    public void requireJob(SpaceService.Scope actor,JobService.Job job){if(!job(actor,job))throw new AccessDeniedException("无权管理该任务的业务资源");}
}
