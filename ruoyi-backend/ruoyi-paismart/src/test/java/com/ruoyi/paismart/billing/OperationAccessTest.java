package com.ruoyi.paismart.billing;
import com.ruoyi.paismart.application.operations.OperationAccessService;
import com.ruoyi.paismart.application.spaces.SpaceService;
import com.ruoyi.paismart.application.jobs.JobService;
import com.ruoyi.paismart.infrastructure.Jsons;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class OperationAccessTest {
    JdbcTemplate db;SpaceService spaces;OperationAccessService access;
    SpaceService.Scope member=new SpaceService.Scope(2,false);
    @BeforeEach void setup()throws Exception{var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");db=new JdbcTemplate(source);
        try(var c=source.getConnection()){for(String script:List.of("V001__control_billing_jobs.sql","V002__documents_spaces.sql","V005__evaluation_datasets.sql"))org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.ClassPathResource("db/paismart/"+script));}
        for(String visibility:List.of("PRIVATE","ORGANIZATION"))db.update("INSERT INTO ps_document(id,user_id,space_id,visibility,file_name,sha256,object_key,byte_size,mime_type) VALUES(?,3,'space',?,'test.txt',?,'paismart/test',20,'text/plain')",visibility.equals("PRIVATE")?"private":"shared",visibility,"a".repeat(64));
        spaces=mock(SpaceService.class);when(spaces.canManageDocument(member,3L,"space","ORGANIZATION")).thenReturn(true);access=new OperationAccessService(db,spaces);}
    @Test void buttonDoesNotExposeOtherPrivateJob(){assertFalse(access.job(member,job("DOCUMENT_PROCESS","private",Map.of())));assertTrue(access.job(member,job("DOCUMENT_PROCESS","shared",Map.of())));assertFalse(access.job(member,job("CHAT_GENERATE",null,Map.of("question","其他用户内容"))));}
    @Test void creatorDoesNotOverrideRevokedDocumentScope(){var record=Map.<String,Object>of("type","cleanup-plans","created_by",2L,"payload_json",Jsons.write(Map.of("documents",List.of(Map.of("id","private")))));assertFalse(access.plan(member,record));assertThrows(org.springframework.security.access.AccessDeniedException.class,()->access.requirePlan(member,record));assertTrue(access.plan(new SpaceService.Scope(1,true),record));}
    @Test void mixedPlanRequiresEveryObject(){var record=Map.<String,Object>of("type","object-migrations","created_by",3L,"payload_json",Jsons.write(Map.of("documents",List.of(Map.of("documentId","shared"),Map.of("documentId","private")))));assertFalse(access.plan(member,record));}
    @Test void personalImportRequiresCreatorAndOrganizationRequiresManagement(){var personal=Map.<String,Object>of("type","bootstrap-import","created_by",3L,"payload_json",Jsons.write(Map.of("files",List.of(Map.of("fileName","test.txt")))));assertFalse(access.plan(member,personal));var org=Map.<String,Object>of("type","bootstrap-import","created_by",2L,"payload_json",Jsons.write(Map.of("files",List.of(Map.of("spaceId","other")))));assertFalse(access.plan(member,org));}
    @Test void emptyDocumentsCannotBypassUploadScopeByBeingPlanCreator(){var uploads=mock(com.ruoyi.paismart.application.documents.UploadService.class);access.uploadAccess(uploads);doThrow(new org.springframework.security.access.AccessDeniedException("upload outside actor scope")).when(uploads).requireCleanupAccess(member,"other-upload");var record=Map.<String,Object>of("type","cleanup-plans","created_by",2L,"payload_json",Jsons.write(Map.of("documents",List.of(),"uploads",List.of(Map.of("uploadId","other-upload")))));assertFalse(access.plan(member,record));verify(uploads).requireCleanupAccess(member,"other-upload");}
    JobService.Job job(String type,String document,Map<String,Object> payload){return new JobService.Job("job",3,document,type,"QUEUED",payload,Map.of(),null,null,0,null,1);}
}
