package com.ruoyi.paismart.application.documents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paismart.application.MembershipService;
import com.ruoyi.paismart.application.billing.RateLimitService;
import com.ruoyi.paismart.application.jobs.JobService;
import com.ruoyi.paismart.application.spaces.SpaceService;
import com.ruoyi.paismart.application.spaces.SpaceService.Scope;
import com.ruoyi.paismart.infrastructure.storage.DocumentObjectStore;
import java.io.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** 只使用本进程 H2 与内存对象，绝不连接用户数据库、MinIO、模型或支付。 */
class DocumentSpaceWorkflowTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private SpaceService spaces;
    private DocumentService docs;
    private UploadService uploads;
    private JobService jobs;
    private MemoryObjects objects;
    private final Scope admin=new Scope(1,true),alice=new Scope(2,false),bob=new Scope(3,false),carol=new Scope(4,false);
    @BeforeEach void setup() {
        JdbcDataSource source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE sys_user(user_id BIGINT PRIMARY KEY,del_flag CHAR(1) DEFAULT '0')");
        for(long id=1;id<=4;id++) jdbc.update("INSERT INTO sys_user(user_id) VALUES(?)",id);
        new ResourceDatabasePopulator(new ClassPathResource("db/paismart/V001__control_billing_jobs.sql"),new ClassPathResource("db/paismart/V002__documents_spaces.sql"),new ClassPathResource("db/paismart/V005__evaluation_datasets.sql")).execute(source);
        MembershipService memberships=mock(MembershipService.class);jobs=mock(JobService.class);objects=new MemoryObjects();
        spaces=new SpaceService(jdbc);docs=new DocumentService(jdbc,spaces,memberships,objects,jobs);
        uploads=new UploadService(jdbc,spaces,memberships,docs,objects,jobs,512L*1024*1024);
    }
    private SpaceService.Space space(String parent,String name,int limit) {return tx.execute(s->spaces.save(admin,null,parent,name,null,limit,null));}
    @Test void rootPrivateReadRecordsOnlyMetadataWhileDeniedMemberRecordsNothing(){
        var doc=tx.execute(s->docs.createText(alice,"私有测试","不可写入审计的私密正文",null,"PRIVATE","private-audit"));
        assertThrows(ServiceException.class,()->docs.getReadable(bob,doc.id()));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ps_audit_event WHERE action='PRIVATE_DOCUMENT_READ'",Integer.class));
        assertEquals(doc.id(),docs.getReadable(admin,doc.id()).id());var event=jdbc.queryForMap("SELECT * FROM ps_audit_event WHERE action='PRIVATE_DOCUMENT_READ'");assertEquals(1L,((Number)event.get("user_id")).longValue());assertEquals(doc.id(),event.get("resource_id"));assertFalse(event.toString().contains("不可写入审计的私密正文"));
    }
    private UploadService.Upload begin(Scope who,String filename,byte[] data,String space,String visibility,String key) {
        return tx.execute(s->uploads.create(who,new UploadService.Create(filename,data.length,DocumentService.sha256(data),65536,space,visibility,key)));
    }
    private void pieces(Scope who,UploadService.Upload u,byte[] data) {
        for(int n=0;n<u.totalChunks();n++) {
            byte[] piece=Arrays.copyOfRange(data,n*u.chunkSize(),Math.min(data.length,(n+1)*u.chunkSize()));int index=n;
            tx.executeWithoutResult(s->uploads.chunk(who,u.uploadId(),index,DocumentService.sha256(piece),new ByteArrayInputStream(piece)));
        }
    }
    @Test void organizationAncestorsAreReadableButChildrenAndSiblingsAreNot() {
        var research=space(null,"研发",100);var child=space(research.id(),"研发子组",100);var finance=space(null,"财务",100);
        tx.executeWithoutResult(s->{spaces.setMember(admin,child.id(),2,true,false);spaces.setMember(admin,research.id(),3,true,false);spaces.setMember(admin,finance.id(),4,true,false);});
        assertEquals(Set.of(research.id(),child.id()),spaces.visibleSpaceIds(alice));
        assertTrue(spaces.canRead(alice,3,research.id(),"ORGANIZATION"));
        assertFalse(spaces.canRead(bob,2,child.id(),"ORGANIZATION"));
        assertFalse(spaces.canRead(carol,2,child.id(),"ORGANIZATION"));
        assertFalse(spaces.canRead(bob,2,child.id(),"PRIVATE"));
        assertTrue(spaces.canRead(bob,2,child.id(),"PUBLIC"));
        tx.executeWithoutResult(s->spaces.setMember(admin,child.id(),2,false,true));
        assertFalse(spaces.canRead(alice,3,research.id(),"ORGANIZATION"));
    }
    @Test void cyclesPrimarySpaceAndReferencedDeletionAreControlled() {
        var parent=space(null,"父空间",100);var child=space(parent.id(),"子空间",100);
        assertThrows(ServiceException.class,()->tx.execute(s->spaces.save(admin,parent.id(),child.id(),"环",null,100,parent.rowVersion())));
        tx.executeWithoutResult(s->{spaces.setMember(admin,parent.id(),2,true,false);spaces.setMember(admin,child.id(),2,true,false);spaces.primary(alice,parent.id());spaces.primary(alice,child.id());});
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ps_member_space WHERE user_id=2 AND primary_flag=TRUE",Integer.class));
        assertEquals(child.id(),spaces.memberships(2).stream().filter(SpaceService.Member::primary).findFirst().orElseThrow().spaceId());
        assertThrows(ServiceException.class,()->tx.executeWithoutResult(s->spaces.delete(admin,parent.id())));
        assertThrows(ServiceException.class,()->tx.executeWithoutResult(s->spaces.primary(carol,child.id())));
    }
    @Test void personalCreationIsExplicitPrivateAndCannotBecomeAnOrganization() {
        assertTrue(spaces.list(alice).isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ps_space",Integer.class));
        var personal=tx.execute(s->spaces.personal(alice));long version=spaces.membershipVersion(2);
        assertEquals(personal.id(),tx.execute(s->spaces.personal(alice)).id());assertEquals(version,spaces.membershipVersion(2));
        assertEquals(1,version);assertEquals("INELIGIBLE",jdbc.queryForObject("SELECT status FROM ps_member WHERE user_id=2",String.class));
        assertTrue(spaces.memberships(2).stream().anyMatch(member->member.spaceId().equals(personal.id())&&member.primary()&&member.uploadAllowed()));
        tx.executeWithoutResult(s->spaces.primary(alice,personal.id()));
        assertEquals(1,spaces.list(alice).size());assertTrue(spaces.list(bob).isEmpty());
        assertThrows(ServiceException.class,()->spaces.requireUpload(bob,personal.id()));
        assertThrows(ServiceException.class,()->spaces.validateVisibility(personal.id(),"PUBLIC"));
        assertThrows(ServiceException.class,()->tx.execute(s->spaces.save(admin,personal.id(),null,"非私有",null,100,personal.rowVersion())));
    }
    @Test void personalRepairPreservesOrganizationPrimaryAndInvalidatesStaleMembershipRevision() {
        var organization=space(null,"原有主组织",-1);
        tx.executeWithoutResult(s->{spaces.setMember(admin,organization.id(),2,true,false);spaces.primary(alice,organization.id());});
        long version=spaces.membershipVersion(2);var personal=tx.execute(s->spaces.personal(alice));
        assertEquals(version+1,spaces.membershipVersion(2));
        assertEquals(organization.id(),spaces.memberships(2).stream().filter(SpaceService.Member::primary).findFirst().orElseThrow().spaceId());
        assertThrows(ServiceException.class,()->tx.executeWithoutResult(s->spaces.setMemberAssignments(admin,2,List.of(new SpaceService.Assignment(organization.id(),false,false)),null,version)));
        assertTrue(spaces.memberships(2).stream().anyMatch(member->member.spaceId().equals(organization.id())&&member.uploadAllowed()));
        jdbc.update("DELETE FROM ps_member_space WHERE user_id=2 AND space_id=?",personal.id());
        long repairVersion=spaces.membershipVersion(2);assertEquals(personal.id(),tx.execute(s->spaces.personal(alice)).id());assertEquals(repairVersion+1,spaces.membershipVersion(2));
        assertThrows(ServiceException.class,()->tx.executeWithoutResult(s->spaces.primary(bob,personal.id())));
        assertEquals(organization.id(),spaces.memberships(2).stream().filter(SpaceService.Member::primary).findFirst().orElseThrow().spaceId());
    }
    @Test void creatingOrganizationChangesCreatorMembershipRevisionWithoutActivatingEligibility() {
        long version=spaces.membershipVersion(2);var created=tx.execute(s->spaces.save(alice,null,null,"新建组织",null,-1,null));
        assertEquals(version+1,spaces.membershipVersion(2));assertEquals("INELIGIBLE",jdbc.queryForObject("SELECT status FROM ps_member WHERE user_id=2",String.class));
        assertTrue(spaces.memberships(2).stream().anyMatch(member->member.spaceId().equals(created.id())&&member.managerAllowed()));
    }
    @Test void managerTrashShowsOnlyOwnScopeAndNeverOthersPrivateDocuments() {
        var organization=space(null,"研发管理",-1);
        tx.executeWithoutResult(s->{spaces.setMember(admin,organization.id(),2,true,true,false);spaces.setMember(admin,organization.id(),3,true,false);});
        var visible=tx.execute(s->docs.createText(bob,"组织正文","组织资料",organization.id(),"ORGANIZATION","org-trash"));
        var secret=tx.execute(s->docs.createText(bob,"私人正文","私人资料",organization.id(),"PRIVATE","private-trash"));
        tx.executeWithoutResult(s->{docs.delete(bob,visible.id(),visible.rowVersion());docs.delete(bob,secret.id(),secret.rowVersion());});
        assertEquals(List.of(visible.id()),docs.list(alice,null,null,null,false,true,1,20).rows().stream().map(DocumentService.Document::id).toList());
        assertEquals(2,docs.list(bob,null,null,null,true,true,1,20).total());
        assertEquals(0,docs.list(carol,null,null,null,false,true,1,20).total());
    }
    @Test void cancelRetryHooksOnlyAffectCurrentJobAndPreserveContentAndPaidUsage() {
        when(jobs.submit(eq(2L),eq("DOCUMENT_PROCESS"),anyString(),anyString())).thenAnswer(inv->new JobService.Job("current-job",2,inv.getArgument(2),"DOCUMENT_PROCESS","RUNNING",Map.of("contentVersion",1),Map.of(),"lease",java.time.Instant.now().plusSeconds(600),1,null,1));
        var d=tx.execute(s->docs.createText(alice,"任务资料","已保存原文",null,"PRIVATE","job-hooks"));
        docs.markProcessingResult(d.id(),1,"INDEXING",null,null,37);
        var current=new JobService.Job("current-job",2,d.id(),"DOCUMENT_PROCESS","CANCELED",Map.of("contentVersion",1),Map.of(),null,null,1,null,2);
        docs.onProcessingCancelled(current);
        assertEquals("CANCELED",docs.getForProcessing(d.id()).state());assertEquals(37,docs.getForProcessing(d.id()).actualTokens());assertEquals("已保存原文",docs.content(alice,d.id()));
        assertThrows(ServiceException.class,()->docs.markProcessingResult(d.id(),1,"READY","迟到结果",null,0));
        assertThrows(ServiceException.class,()->docs.markPageCount(d.id(),1,1));
        docs.onProcessingRetry(current);assertEquals("QUEUED",docs.getForProcessing(d.id()).state());assertTrue(docs.isCurrentProcessingJob(current));
        jdbc.update("UPDATE ps_document SET process_job_id='new-job',version=2,status='READY' WHERE id=?",d.id());
        docs.onProcessingCancelled(current);docs.onProcessingRetry(current);
        assertEquals("READY",docs.getForProcessing(d.id()).state());assertFalse(docs.isCurrentProcessingJob(current));
    }
    @Test void staleReprocessingVersionIsRejectedBeforeSchedulingAnotherCostlyJob() {
        var d=tx.execute(s->docs.createText(alice,"重处理","正文",null,"PRIVATE","reprocess-version"));
        docs.markProcessingResult(d.id(),1,"READY",null,null,31);
        assertThrows(ServiceException.class,()->tx.execute(s->docs.reprocess(alice,d.id(),"DOCUMENT_PROCESS","stale-request",d.rowVersion())));
        assertEquals("READY",docs.getForProcessing(d.id()).state());
        long current=docs.getForProcessing(d.id()).rowVersion();
        var queued=tx.execute(s->docs.reprocess(alice,d.id(),"DOCUMENT_PROCESS","new-request",current));
        assertEquals("QUEUED",queued.state());assertEquals(2,queued.contentVersion());assertEquals(0,queued.actualTokens());
        verify(jobs,times(1)).enqueue(eq(2L),eq("DOCUMENT_PROCESS"),eq(Map.of("documentId",d.id(),"contentVersion",2)),anyString());
    }
    @Test void configuredFileBytesAndSpaceBytesAreEnforcedInService() {
        var limit=mock(RateLimitService.class);when(limit.configuration()).thenReturn(Map.of("maxFileBytes",4L));
        var controlled=new UploadService(jdbc,spaces,mock(MembershipService.class),docs,objects,jobs,limit,100);
        byte[] data="hello".getBytes();
        assertThrows(ServiceException.class,()->controlled.create(admin,new UploadService.Create("notes.txt",5,DocumentService.sha256(data),65536,null,"PRIVATE","size-too-large")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ps_upload",Integer.class));
        when(limit.configuration()).thenReturn(Map.of("maxFileBytes",1000L));
        var constrained=new UploadService(jdbc,spaces,mock(MembershipService.class),docs,objects,jobs,limit,4);
        assertThrows(ServiceException.class,()->constrained.create(admin,new UploadService.Create("notes.txt",5,DocumentService.sha256(data),65536,null,"PRIVATE","static-too-large")));
        var organization=tx.execute(s->spaces.save(admin,null,null,"字节限制",null,-1,null,4L));
        tx.executeWithoutResult(s->spaces.setMember(admin,organization.id(),2,true,false));
        assertThrows(ServiceException.class,()->begin(alice,"notes.txt",data,organization.id(),"ORGANIZATION","space-size"));
        tx.executeWithoutResult(s->spaces.setMember(admin,organization.id(),2,true,true,false));
        assertDoesNotThrow(()->begin(alice,"notes.txt",data,organization.id(),"ORGANIZATION","manager-size"));
    }
    @Test void invitationAppendsOnlyItsOrganizationAndKeepsExistingPrimary() {
        var first=space(null,"原组织",100);var second=space(null,"邀请组织",100);
        tx.executeWithoutResult(s->{spaces.setMember(admin,first.id(),2,true,false);spaces.primary(alice,first.id());spaces.grantInvitedMembership(2,second.id());spaces.grantInvitedMembership(2,second.id());});
        assertEquals(2,spaces.memberships(2).size());
        assertEquals(first.id(),spaces.memberships(2).stream().filter(SpaceService.Member::primary).findFirst().orElseThrow().spaceId());
    }
    @Test void scopedManagersCanGovernDescendantsButNeverPrivateDocumentsOrSiblingSpaces() {
        var research=space(null,"研发",100);var child=space(research.id(),"子组",100);var finance=space(null,"财务",100);
        tx.executeWithoutResult(s->{spaces.setMember(admin,research.id(),2,true,true,false);spaces.setMember(admin,child.id(),3,true,false);});
        assertTrue(spaces.canManageSpace(alice,child.id()));assertFalse(spaces.canManageSpace(alice,finance.id()));
        assertThrows(ServiceException.class,()->tx.execute(s->spaces.save(bob,child.id(),research.id(),"越权",null,100,child.rowVersion())));
        assertThrows(ServiceException.class,()->tx.execute(s->spaces.save(alice,finance.id(),null,"越权",null,100,finance.rowVersion())));
        byte[] data="scope evidence".getBytes();var shared=begin(bob,"shared.txt",data,child.id(),"ORGANIZATION","shared");var privateFile=begin(bob,"private.txt",data,child.id(),"PRIVATE","private");
        pieces(bob,shared,data);pieces(bob,privateFile,data);var sharedDoc=tx.execute(s->uploads.merge(bob,shared.uploadId()));var privateDoc=tx.execute(s->uploads.merge(bob,privateFile.uploadId()));
        assertEquals(sharedDoc.id(),docs.getReadable(alice,sharedDoc.id()).id());
        assertThrows(ServiceException.class,()->docs.getReadable(alice,privateDoc.id()));
        assertThrows(ServiceException.class,()->tx.execute(s->docs.delete(alice,privateDoc.id(),privateDoc.rowVersion())));
        var deleted=tx.execute(s->docs.delete(alice,sharedDoc.id(),sharedDoc.rowVersion()));assertNotNull(deleted.deletedAt());
        var restored=tx.execute(s->docs.restore(alice,sharedDoc.id(),deleted.rowVersion()));assertNull(restored.deletedAt());
        tx.executeWithoutResult(s->spaces.setMember(admin,research.id(),2,true,false,false));
        assertFalse(spaces.canManageSpace(alice,child.id()));assertThrows(ServiceException.class,()->docs.getReadable(alice,sharedDoc.id()));
    }
    @Test void assignmentChangesPreserveMembersAndPrimaryOutsideManagersScope() {
        var research=space(null,"研发",100);var child=space(research.id(),"子组",100);var finance=space(null,"财务",100);
        tx.executeWithoutResult(s->{spaces.setMember(admin,research.id(),2,true,true,false);spaces.setMember(admin,finance.id(),3,true,false);spaces.primary(bob,finance.id());});
        tx.executeWithoutResult(s->spaces.setMemberAssignments(alice,3,List.of(new SpaceService.Assignment(child.id(),true,true)),null));
        assertEquals(2,spaces.memberships(3).size());assertEquals(finance.id(),spaces.memberships(3).stream().filter(SpaceService.Member::primary).findFirst().orElseThrow().spaceId());
        assertThrows(ServiceException.class,()->tx.executeWithoutResult(s->spaces.setMemberAssignments(alice,3,List.of(new SpaceService.Assignment(child.id(),true,true)),child.id())));
        assertThrows(ServiceException.class,()->tx.executeWithoutResult(s->spaces.setMemberAssignments(alice,3,List.of(new SpaceService.Assignment(finance.id(),true,true)),null)));
    }
    @Test void resumableUploadChecksManifestChecksumsAndMergesExactlyOnce() {
        byte[] data=new byte[70000];Arrays.fill(data,(byte)'a');
        var upload=begin(alice,"资料.txt",data,null,"PRIVATE","request-1");
        var repeated=begin(alice,"资料.txt",data,null,"PRIVATE","request-1");assertEquals(upload.uploadId(),repeated.uploadId());
        assertThrows(ServiceException.class,()->tx.execute(s->uploads.merge(alice,upload.uploadId())));
        byte[] first=Arrays.copyOf(data,65536);
        assertThrows(ServiceException.class,()->tx.execute(s->uploads.chunk(alice,upload.uploadId(),0,"0".repeat(64),new ByteArrayInputStream(first))));
        tx.executeWithoutResult(s->uploads.chunk(alice,upload.uploadId(),0,DocumentService.sha256(first),new ByteArrayInputStream(first)));
        // 重新创建服务实例模拟缓存全丢；恢复以数据库 manifest 为准。
        var restored=new UploadService(jdbc,spaces,mock(MembershipService.class),docs,objects,jobs,512L*1024*1024);
        assertEquals(List.of(0),restored.status(alice,upload.uploadId()).completedChunks());
        pieces(alice,upload,data);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM ps_upload_chunk",Integer.class));
        assertThrows(ServiceException.class,()->uploads.status(bob,upload.uploadId()));
        DocumentService.Document doc=tx.execute(s->uploads.merge(alice,upload.uploadId()));
        var again=tx.execute(s->uploads.merge(alice,upload.uploadId()));assertEquals(doc.id(),again.id());
        assertArrayEquals(data,objects.data.get(doc.objectKey()));
        verify(jobs,times(1)).submit(eq(2L),eq("DOCUMENT_PROCESS"),eq(doc.id()),anyString());
        assertThrows(ServiceException.class,()->docs.getReadable(bob,doc.id()));
    }
    @Test void corruptionAndFakedFormatsCannotProduceReadyDocuments() {
        assertThrows(ServiceException.class,()->UploadService.sniff("fake.pdf","text".getBytes()));
        assertThrows(ServiceException.class,()->begin(alice,"photo.png","text".getBytes(),null,"PRIVATE","unsupported"));
        byte[] data="原始内容".getBytes(java.nio.charset.StandardCharsets.UTF_8);var upload=begin(alice,"资料.txt",data,null,"PRIVATE","corruption");pieces(alice,upload,data);
        objects.data.put("paismart/uploads/"+upload.uploadId()+"/0","malicious".getBytes());
        assertThrows(ServiceException.class,()->tx.execute(s->uploads.merge(alice,upload.uploadId())));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ps_document",Integer.class));verifyNoInteractions(jobs);
    }
    @Test void deletionRetainsObjectAndRestoreInvalidatesOldProcessingVersion() {
        byte[] data="retain source".getBytes();var upload=begin(alice,"notes.txt",data,null,"PRIVATE","delete");pieces(alice,upload,data);
        var doc=tx.execute(s->uploads.merge(alice,upload.uploadId()));
        docs.markProcessingResult(doc.id(),doc.contentVersion(),"READY","retain source",null,12);
        var ready=docs.getForProcessing(doc.id());var deleted=tx.execute(s->docs.delete(alice,doc.id(),ready.rowVersion()));
        assertNotNull(deleted.deletedAt());assertTrue(objects.data.containsKey(doc.objectKey()));
        assertThrows(ServiceException.class,()->docs.getReadable(alice,doc.id()));
        var restored=tx.execute(s->docs.restore(alice,doc.id(),deleted.rowVersion()));assertEquals(doc.contentVersion()+1,restored.contentVersion());
        assertThrows(ServiceException.class,()->docs.markProcessingResult(doc.id(),doc.contentVersion(),"READY","stale",null,1));
        assertEquals("QUEUED",docs.getReadable(alice,doc.id()).state());
    }
    @Test void sameHashDifferentUsersDoesNotShareResourceIdentityAndLimitCannotBeBypassed() {
        var team=space(null,"团队",1);tx.executeWithoutResult(s->{spaces.setMember(admin,team.id(),2,true,false);spaces.setMember(admin,team.id(),3,true,false);});
        byte[] data="same bytes".getBytes();var a=begin(alice,"a.txt",data,team.id(),"PRIVATE","a");var b=begin(bob,"b.txt",data,team.id(),"PRIVATE","b");
        pieces(alice,a,data);pieces(bob,b,data);var ad=tx.execute(s->uploads.merge(alice,a.uploadId()));var bd=tx.execute(s->uploads.merge(bob,b.uploadId()));
        assertNotEquals(ad.id(),bd.id());assertThrows(ServiceException.class,()->docs.getReadable(bob,ad.id()));
        assertThrows(ServiceException.class,()->begin(alice,"c.txt","distinct bytes".getBytes(),team.id(),"PRIVATE","c"));
        assertEquals(1,docs.list(alice,null,null,null,false,false,1,20).total());
    }
    @Test void descriptionUsesRealOptimisticVersionAndDefaultOrganizationCannotBeDeleted() {
        var d=tx.execute(s->spaces.save(admin,null,null,"默认组织",null,-1,null,null,"业务简介"));
        assertEquals("业务简介",spaces.list(admin).stream().filter(space->space.id().equals(d.id())).findFirst().orElseThrow().description());
        var renamed=tx.execute(s->spaces.save(admin,d.id(),null,"更新",null,-1,d.rowVersion(),null,null));assertEquals("业务简介",renamed.description());
        assertThrows(ServiceException.class,()->tx.execute(s->spaces.save(admin,d.id(),null,"失效",null,-1,d.rowVersion(),null,"不应落库")));
        assertEquals("业务简介",spaces.get(d.id()).description());
        jdbc.update("DELETE FROM ps_member_space WHERE space_id=?",d.id());jdbc.update("UPDATE ps_space SET system_key='DEFAULT' WHERE id=?",d.id());
        assertThrows(ServiceException.class,()->tx.executeWithoutResult(s->spaces.delete(admin,d.id())));
    }
    @Test void resendRepairsLostAndCorruptPiecesAndStatusDoesNotClaimMissingObjectsComplete() {
        byte[] bytes="resumable text".getBytes();var u=begin(alice,"a.txt",bytes,null,"PRIVATE","recover");pieces(alice,u,bytes);
        String key="paismart/uploads/"+u.uploadId()+"/0";objects.data.remove(key);
        assertTrue(uploads.status(alice,u.uploadId()).completedChunks().isEmpty());
        pieces(alice,u,bytes);assertEquals(List.of(0),uploads.status(alice,u.uploadId()).completedChunks());
        objects.data.put(key,"bad".getBytes());pieces(alice,u,bytes);
        assertArrayEquals(bytes,objects.data.get(key));var d=tx.execute(s->uploads.merge(alice,u.uploadId()));assertArrayEquals(bytes,objects.data.get(d.objectKey()));
    }
    @Test void contentDiscoveryRetainsEveryRequestHashAndNeverMergesDifferentOwnersOrScopes() {
        byte[] bytes="content match".getBytes();var first=begin(alice,"first.txt",bytes,null,"PRIVATE","first-key");pieces(alice,first,bytes);
        var second=begin(alice,"renamed.txt",bytes,null,"PRIVATE","second-key");assertEquals(first.uploadId(),second.uploadId());assertEquals("first.txt",second.filename());
        assertThrows(ServiceException.class,()->begin(alice,"renamed.txt","modified".getBytes(),null,"PRIVATE","second-key"));
        assertNotEquals(first.uploadId(),begin(bob,"renamed.txt",bytes,null,"PRIVATE","second-key").uploadId());
        var team=space(null,"范围",-1);tx.executeWithoutResult(s->spaces.setMember(admin,team.id(),2,true,false));
        assertNotEquals(first.uploadId(),begin(alice,"first.txt",bytes,team.id(),"ORGANIZATION","org-key").uploadId());
        var d=tx.execute(s->uploads.merge(alice,first.uploadId()));assertEquals(d.id(),begin(alice,"same.txt",bytes,null,"PRIVATE","third-key").documentId());
        verify(jobs,times(1)).submit(eq(2L),eq("DOCUMENT_PROCESS"),eq(d.id()),anyString());
    }
    @Test void uploadListIsOwnerScopedPaginatedAndFindsContentAfterProcessRestart() {
        byte[] a="alpha".getBytes(),b="beta".getBytes();var first=begin(alice,"alpha.txt",a,null,"PRIVATE","list-a");
        begin(alice,"beta.txt",b,null,"PRIVATE","list-b");begin(bob,"alpha.txt",a,null,"PRIVATE","list-other");
        assertEquals(2,uploads.list(alice,"UPLOADING",null,null,null,null,1,1).total());
        assertEquals(1,uploads.list(alice,"UPLOADING",DocumentService.sha256(a),null,"PRIVATE",null,1,20).total());
        assertEquals(first.uploadId(),uploads.list(alice,"UPLOADING",null,null,null,"alpha",1,20).rows().get(0).uploadId());
        jdbc.update("UPDATE ps_upload SET expires_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),first.uploadId());
        assertEquals(1,uploads.list(alice,"EXPIRED",null,null,null,null,1,20).total());
    }
    @Test void organizationRevocationStopsSubsequentChunkWritesBeforeAnyObjectChange() {
        var team=space(null,"续传组织",-1);tx.executeWithoutResult(s->spaces.setMember(admin,team.id(),2,true,false));
        byte[] bytes="allowed at start".getBytes();var u=begin(alice,"team.txt",bytes,team.id(),"ORGANIZATION","revoke-piece");
        tx.executeWithoutResult(s->spaces.setMember(admin,team.id(),2,false,true));
        assertThrows(ServiceException.class,()->pieces(alice,u,bytes));assertTrue(objects.data.isEmpty());
    }
    @Test void declaredUnicodeTextFormatsAcceptUtf16ButRejectBinaryControls() {
        for(String ext:List.of("txt","xml","csv","html","md","json")) {
            assertDoesNotThrow(()->UploadService.sniff("text."+ext,"hello 世界".getBytes(java.nio.charset.StandardCharsets.UTF_16)));
            assertDoesNotThrow(()->UploadService.sniff("text."+ext,"hello 世界".getBytes(java.nio.charset.StandardCharsets.UTF_16LE)));
        }
        assertThrows(ServiceException.class,()->UploadService.sniff("binary.txt",new byte[]{'h',0,1,0}));
        assertThrows(ServiceException.class,()->UploadService.sniff("binary.txt",new byte[]{1,0,2,0,3,0}));
    }
    @Test void mergedTemporaryPiecesRecycleOnlyAfterCommitAndFailureRetainsRetryReceipts() {
        uploads.configureCleanupTransactions(tx);byte[] body="temporary pieces".getBytes();var u=begin(alice,"parts.txt",body,null,"PRIVATE","auto-clean");pieces(alice,u,body);
        String temporary="paismart/uploads/"+u.uploadId()+"/0";
        tx.executeWithoutResult(s->{uploads.merge(alice,u.uploadId());assertTrue(objects.data.containsKey(temporary));});
        assertFalse(objects.data.containsKey(temporary));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ps_upload_chunk WHERE upload_id=?",Integer.class,u.uploadId()));
        assertTrue(objects.data.containsKey(docs.getForProcessing(u.uploadId()).objectKey()));
        byte[] next="cleanup retry".getBytes();var failed=begin(alice,"retry.txt",next,null,"PRIVATE","retry-clean");pieces(alice,failed,next);objects.failRemoval=true;
        tx.executeWithoutResult(s->uploads.merge(alice,failed.uploadId()));assertEquals("MERGED",uploads.status(alice,failed.uploadId()).state());
        assertEquals(1,uploads.cleanupCandidates(alice).size());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ps_upload_chunk WHERE upload_id=?",Integer.class,failed.uploadId()));
        objects.failRemoval=false;
    }
    @Test void rolledBackMergeRetainsPiecesAndDoesNotRunAfterCommitCleanup() {
        uploads.configureCleanupTransactions(tx);byte[] body="rollback pieces".getBytes();var u=begin(alice,"rollback.txt",body,null,"PRIVATE","rollback-clean");pieces(alice,u,body);
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{uploads.merge(alice,u.uploadId());throw new IllegalStateException("forced rollback");}));
        assertEquals("UPLOADING",uploads.status(alice,u.uploadId()).state());assertEquals(List.of(0),uploads.status(alice,u.uploadId()).completedChunks());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM ps_document WHERE id=?",Integer.class,u.uploadId()));
    }
    private static class MemoryObjects implements DocumentObjectStore {
        final Map<String,byte[]> data=new HashMap<>();
        boolean failRemoval;
        public void put(String key,InputStream bytes,long length,String mime) {
            try {byte[] body=bytes.readAllBytes();assertEquals(length,body.length);data.put(key,body);} catch(IOException e) {throw new UncheckedIOException(e);}
        }
        public InputStream open(String key) {byte[] body=data.get(key);if(body==null) throw new IllegalArgumentException("missing");return new ByteArrayInputStream(body);}
        public long size(String key) {return data.get(key).length;}
        public boolean exists(String key) {return data.containsKey(key);}
        public void remove(String key) {if(failRemoval)throw new IllegalStateException("fixture removal failure");data.remove(key);}
    }
}
