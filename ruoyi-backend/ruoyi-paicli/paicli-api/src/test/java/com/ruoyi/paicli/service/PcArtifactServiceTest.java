package com.ruoyi.paicli.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import com.ruoyi.paicli.domain.PcException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import com.ruoyi.paicli.domain.PcRecord;
import com.ruoyi.paicli.domain.PcTable;
import com.ruoyi.paicli.mapper.PcLedgerMapper;
import com.ruoyi.system.secret.ManagedSecretService;
import tools.jackson.databind.json.JsonMapper;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class PcArtifactServiceTest {
    Path temporary;
    @Test void personalUserFilePreviewNeedsNoSessionAndKeepsCompleteUtf8BytesAcrossPages(){
        Fixture f=new Fixture(temporary);String original="首行\r\n<script>原文而非执行</script>\t中🙂尾\n";
        byte[] bytes=original.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<String,Object> stored=f.service.upload(10,"personal-preview",(Long)null,"USER_FILE","资料.html","text/html",bytes);
        long id=Long.parseLong(stored.get("id").toString()),offset=0;StringBuilder restored=new StringBuilder();
        while(offset<bytes.length){
            Map<String,Object> page=f.service.userFilePreviewText(10,id,offset,7);
            assertEquals(java.util.Set.of("artifactId","fileName","purpose","sha256","size","offset","nextOffset","hasMore","text"),page.keySet());
            assertEquals(String.valueOf(id),page.get("artifactId"));assertEquals("资料.html",page.get("fileName"));assertEquals("USER_FILE",page.get("purpose"));
            assertEquals(PcArtifactService.digest(bytes),page.get("sha256"));assertEquals(bytes.length,((Number)page.get("size")).longValue());
            assertEquals(offset,((Number)page.get("offset")).longValue());String text=page.get("text").toString();long next=((Number)page.get("nextOffset")).longValue();
            assertEquals(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,next-offset);assertTrue(next>offset);assertTrue(next-offset<=7);
            restored.append(text);offset=next;
        }
        assertEquals(original,restored.toString());Map<String,Object> end=f.service.userFilePreviewText(10,id,offset,4);
        assertEquals("",end.get("text"));assertEquals(false,end.get("hasMore"));assertFalse(end.containsKey("sessionId"));
        assertEquals(403,assertThrows(PcException.class,()->f.service.outputText(10,id,300,0,7)).status());
    }
    @Test void userFilePreviewAboveAnalysisLimitStillWorksWithoutChangingAnalysisLimit(){
        Fixture f=new Fixture(temporary);byte[] bytes=new byte[1024*1024+1];Arrays.fill(bytes,(byte)'x');
        Map<String,Object> stored=f.service.upload(10,"large-preview",(Long)null,"USER_FILE","large.txt","text/plain",bytes);long id=Long.parseLong(stored.get("id").toString());
        assertEquals(false,stored.get("supportedAnalysis"));assertEquals("PC_FILE_ANALYSIS_SIZE",assertThrows(PcException.class,()->f.service.userFile(10,id)).code());
        Map<String,Object> first=f.service.userFilePreviewText(10,id,0,65536),last=f.service.userFilePreviewText(10,id,bytes.length-1,4);
        assertEquals(65536,first.get("text").toString().length());assertEquals(65536L,((Number)first.get("nextOffset")).longValue());assertEquals(true,first.get("hasMore"));
        assertEquals("x",last.get("text"));assertEquals(false,last.get("hasMore"));
    }
    @Test void userFilePreviewAcceptsUploadBoundaryAndPreservesFiftyMiBUploadLimit(){
        Fixture f=new Fixture(temporary);byte[] bytes=new byte[PcArtifactService.MAX_DATA_BYTES];Arrays.fill(bytes,(byte)'a');
        Map<String,Object> stored=f.service.upload(10,"boundary-preview",(Long)null,"USER_FILE","boundary.txt","application/octet-stream",bytes);long id=Long.parseLong(stored.get("id").toString());
        Map<String,Object> page=f.service.userFilePreviewText(10,id,bytes.length-4,4);assertEquals("aaaa",page.get("text"));assertEquals(false,page.get("hasMore"));
        assertEquals(PcArtifactService.MAX_DATA_BYTES,((Number)page.get("size")).longValue());assertEquals(PcArtifactService.digest(bytes),page.get("sha256"));
        assertEquals("PC_ARTIFACT_SIZE",assertThrows(PcException.class,()->f.service.upload(10,"boundary-overflow",(Long)null,"USER_FILE","too-large.txt","text/plain",new byte[PcArtifactService.MAX_DATA_BYTES+1])).code());
    }
    @Test void previewRejectsInvalidUtf8AndBinaryControlsAnywhereInFileButLeavesDownloadAvailable(){
        Fixture f=new Fixture(temporary);
        byte[][] invalid={{(byte)0xff,65},{65,(byte)0xc3},{65,0,66},{65,1,66},{65,11,66}};
        for(int i=0;i<invalid.length;i++){
            byte[] bytes=invalid[i];long id=Long.parseLong(f.service.upload(10,"invalid-preview-"+i,(Long)null,"USER_FILE","binary-"+i+".txt","text/plain",bytes).get("id").toString());
            PcException denied=assertThrows(PcException.class,()->f.service.userFilePreviewText(10,id,0,4));assertEquals(422,denied.status());assertEquals("PC_FILE_ANALYSIS_UNSUPPORTED",denied.code());
            assertEquals(PcArtifactService.digest(bytes),f.service.content(10,id).sha256());
        }
        byte[] late=new byte[65537];Arrays.fill(late,(byte)'a');late[late.length-1]=0;
        long id=Long.parseLong(f.service.upload(10,"late-invalid-preview",(Long)null,"USER_FILE","late.txt","text/plain",late).get("id").toString());
        assertEquals(422,assertThrows(PcException.class,()->f.service.userFilePreviewText(10,id,0,4)).status());
    }
    @Test void userFilePreviewRejectsOtherOwnerPurposeInvalidDirectoryAndInvalidByteRanges(){
        Fixture f=new Fixture(temporary);long id=Long.parseLong(f.service.upload(10,"preview-scope",1,"USER_FILE","scope.txt","text/plain","A中文🙂".getBytes(java.nio.charset.StandardCharsets.UTF_8)).get("id").toString());
        assertEquals(403,assertThrows(PcException.class,()->f.service.userFilePreviewText(20,id,0,7)).status());
        for(long offset:new long[]{-1,2,100})assertEquals(400,assertThrows(PcException.class,()->f.service.userFilePreviewText(10,id,offset,7)).status());
        for(int limit:new int[]{0,3,1024*1024+1})assertEquals(400,assertThrows(PcException.class,()->f.service.userFilePreviewText(10,id,0,limit)).status());
        long output=Long.parseLong(f.service.storeRunnerOutput(1,10,300,400,"preview-wrong-purpose","safe").get("id").toString());
        assertEquals(403,assertThrows(PcException.class,()->f.service.userFilePreviewText(10,output,0,4)).status());
        assertEquals("safe",f.service.outputText(10,output,300,0,4).get("text"));
        f.rows.get(PcTable.DIRECTORY_CONTEXT).get(1L).setState("INVALID");assertEquals("PC_DIRECTORY_NOT_READY",assertThrows(PcException.class,()->f.service.userFilePreviewText(10,id,0,7)).code());
    }
    @Test void userFilePreviewRejectsExpiredAndChangedDiskBytes()throws Exception{
        Fixture f=new Fixture(temporary);long id=Long.parseLong(f.service.upload(10,"preview-lifecycle",(Long)null,"USER_FILE","life.txt","text/plain","abcd".getBytes(java.nio.charset.StandardCharsets.UTF_8)).get("id").toString());
        PcRecord row=f.rows.get(PcTable.ARTIFACT).get(id);var expiry=row.getExpiresAt();row.setExpiresAt(f.clock.instant().minusSeconds(1));
        assertEquals("PC_ARTIFACT_EXPIRED",assertThrows(PcException.class,()->f.service.userFilePreviewText(10,id,0,4)).code());row.setExpiresAt(expiry);
        Path file=f.service.content(10,id).file();Files.writeString(file,"dcba");assertEquals(409,assertThrows(PcException.class,()->f.service.userFilePreviewText(10,id,0,4)).status());
    }
    @Test void userFilePreviewRejectsBroadenedPrivateAclWithoutRepairingIt()throws Exception{
        Fixture f=new Fixture(temporary);long id=Long.parseLong(f.service.upload(10,"preview-private",(Long)null,"USER_FILE","private.txt","text/plain","safe".getBytes(java.nio.charset.StandardCharsets.UTF_8)).get("id").toString());
        Path file=f.service.content(10,id).file();Object original=permissions(file);
        try{broaden(file);Object broad=permissions(file);assertEquals(503,assertThrows(PcException.class,()->f.service.userFilePreviewText(10,id,0,4)).status());assertEquals(broad,permissions(file));}
        finally{restore(file,original);}
    }
    @Test void projectRuleSourcePreservesLargeUtf8BytesAndRejectsInvalidTextBeforePersistence()throws Exception {
        Fixture f=new Fixture(temporary);String text="# 项目规则\r\n"+"保留中文原文和来源\r\n".repeat(40000);
        byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);assertTrue(bytes.length>1024*1024);
        Map<String,Object> source=f.service.upload(10,"pai-source-large",1,"PAI_SOURCE","项目规则.md","application/octet-stream",bytes);
        long id=Long.parseLong(String.valueOf(source.get("id")));var content=f.service.content(10,id);
        assertEquals("text/plain; charset=utf-8",content.mimeType());assertEquals(PcArtifactService.digest(bytes),content.sha256());assertArrayEquals(bytes,Files.readAllBytes(content.file()));
        assertEquals("PAI_SOURCE",source.get("kind"));assertEquals("UTF-8",source.get("encoding"));
        assertThrows(PcException.class,()->f.service.content(20,id));
        assertEquals(400,assertThrows(PcException.class,()->f.service.upload(10,"pai-source-invalid",1,"PAI_SOURCE","坏规则.md","text/plain",new byte[]{(byte)0xff,65})).status());
    }
    @Test void approvalScopedTextRequiresExactFrozenArtifactProvenanceAndKeepsOwnerBoundary()throws Exception {
        Fixture f=new Fixture(temporary);String text="中ab😀ef";
        Map<String,Object> stored=f.service.storeRunnerOutput(1,10,300,400,"approved-preview",text);
        long id=Long.parseLong(String.valueOf(stored.get("id"))),size=text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        String sha=PcArtifactService.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String,Object> page=f.service.scopedOutputText(id,1,10,300,400,"approved-preview",sha,size,0,5);
        assertEquals("中ab",page.get("text"));assertEquals(5L,((Number)page.get("nextOffset")).longValue());assertEquals(true,page.get("hasMore"));
        assertEquals(403,assertThrows(PcException.class,()->f.service.outputText(20,id,300,0,5)).status());
        assertEquals(403,assertThrows(PcException.class,()->f.service.scopedOutputText(id,2,10,300,400,"approved-preview",sha,size,0,5)).status());
        assertEquals(403,assertThrows(PcException.class,()->f.service.scopedOutputText(id,1,20,300,400,"approved-preview",sha,size,0,5)).status());
        assertEquals(403,assertThrows(PcException.class,()->f.service.scopedOutputText(id,1,10,301,400,"approved-preview",sha,size,0,5)).status());
        assertEquals(403,assertThrows(PcException.class,()->f.service.scopedOutputText(id,1,10,300,401,"approved-preview",sha,size,0,5)).status());
        assertEquals(403,assertThrows(PcException.class,()->f.service.scopedOutputText(id,1,10,300,400,"other-call",sha,size,0,5)).status());
        assertEquals(403,assertThrows(PcException.class,()->f.service.scopedOutputText(id,1,10,300,400,"approved-preview","0".repeat(64),size,0,5)).status());
        assertEquals(403,assertThrows(PcException.class,()->f.service.scopedOutputText(id,1,10,300,400,"approved-preview",sha,size+1,0,5)).status());
        assertEquals(400,assertThrows(PcException.class,()->f.service.scopedOutputText(id,1,10,300,400,"approved-preview",sha,size,1,5)).status());
        Files.writeString(f.service.content(10,id).file(),"被篡改的正文");
        assertEquals(409,assertThrows(PcException.class,()->f.service.scopedOutputText(id,1,10,300,400,"approved-preview",sha,size,0,5)).status());
    }
    @BeforeEach void createActualPrivateFixtureParent()throws Exception {
        Path home=Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        temporary=home.resolve(".paicli-artifact-fixture-"+java.util.UUID.randomUUID());
        if(Files.getFileAttributeView(home,PosixFileAttributeView.class)!=null)Files.createDirectory(temporary,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        else {UserPrincipal owner=home.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));var entries=java.util.List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(java.util.EnumSet.allOf(AclEntryPermission.class)).setFlags(AclEntryFlag.FILE_INHERIT,AclEntryFlag.DIRECTORY_INHERIT).build());Files.createDirectory(temporary,new FileAttribute<java.util.List<AclEntry>>(){public String name(){return "acl:acl";}public java.util.List<AclEntry> value(){return entries;}});}
    }
    @AfterEach void removeOnlyCreatedFixture()throws Exception {
        if(temporary==null||!Files.exists(temporary,LinkOption.NOFOLLOW_LINKS))return;
        assertEquals(Path.of(System.getProperty("user.home")).toAbsolutePath().normalize(),temporary.getParent());
        assertTrue(temporary.getFileName().toString().startsWith(".paicli-artifact-fixture-"));
        try(var paths=Files.walk(temporary)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    @Test void defaultArtifactRootIsOutsidePublicProfileMapping(){
        Path publicProfile=temporary.resolve("public-profile").toAbsolutePath().normalize();
        Path privateArtifacts=PcArtifactService.privateRoot(publicProfile.toString());
        assertFalse(privateArtifacts.startsWith(publicProfile));
        assertEquals(publicProfile.resolveSibling("public-profile.paicli-private").resolve("artifacts"),privateArtifacts);
        assertThrows(IllegalStateException.class,()->PcArtifactService.privateRoot(null));
        assertThrows(IllegalStateException.class,()->PcArtifactService.privateRoot(" "));
        assertThrows(IllegalStateException.class,()->PcArtifactService.privateRoot(publicProfile.getRoot().toString()));
    }
    @Test void chunkedOutputReconstructsSplitUtf8AndKeepsExecutionBinding()throws Exception{
        Fixture f=new Fixture(temporary);String text="a".repeat(PcArtifactService.OUTPUT_CHUNK_BYTES-1)+"中\r\n末行\n";byte[] all=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);String sha=PcArtifactService.digest(all);
        Map<String,Object> begin=f.service.beginRunnerOutput(1,10,9,8,"call-large",7,6,all.length,sha);long id=Long.parseLong(String.valueOf(begin.get("uploadId")));
        assertEquals(id,Long.parseLong(String.valueOf(f.service.beginRunnerOutput(1,10,9,8,"call-large",7,6,all.length,sha).get("uploadId"))));
        assertThrows(PcException.class,()->f.service.completeRunnerOutput(id,1,10,9,8,"call-large",7,6));
        for(int offset=0,index=0;offset<all.length;offset+=PcArtifactService.OUTPUT_CHUNK_BYTES,index++){byte[] part=Arrays.copyOfRange(all,offset,Math.min(all.length,offset+PcArtifactService.OUTPUT_CHUNK_BYTES));String hash=PcArtifactService.digest(part);f.service.appendRunnerOutput(id,1,10,9,8,"call-large",7,6,index,part,hash);f.service.appendRunnerOutput(id,1,10,9,8,"call-large",7,6,index,part,hash);}
        Map<String,Object> complete=f.service.completeRunnerOutput(id,1,10,9,8,"call-large",7,6);assertEquals(String.valueOf(id),complete.get("artifactId"));assertEquals(sha,complete.get("sha256"));assertArrayEquals(all,Files.readAllBytes(f.service.content(10,id).file()));assertEquals("末行",f.service.runnerOutputText(id,1,10,9,2,10).get("text"));
        assertEquals(sha,f.service.completeRunnerOutput(id,1,10,9,8,"call-large",7,6).get("sha256"));assertThrows(PcException.class,()->f.service.completeRunnerOutput(id,1,10,9,8,"call-large",7,5));assertThrows(PcException.class,()->f.service.runnerOutputMetadata(id,1,20,9));
    }
    @Test void chunkedOutputRejectsOrderChangedHashAndInvalidUtf8()throws Exception{
        Fixture f=new Fixture(temporary);byte[] all={(byte)0xff,65};String sha=PcArtifactService.digest(all);long id=Long.parseLong(String.valueOf(f.service.beginRunnerOutput(1,10,9,8,"call-invalid",7,6,all.length,sha).get("uploadId")));
        assertThrows(PcException.class,()->f.service.appendRunnerOutput(id,1,10,9,8,"call-invalid",7,6,1,all,sha));assertThrows(PcException.class,()->f.service.appendRunnerOutput(id,1,10,9,8,"call-invalid",7,6,0,all,"0".repeat(64)));assertThrows(PcException.class,()->f.service.beginRunnerOutput(1,10,9,8,"call-invalid",7,6,all.length,"0".repeat(64)));
        f.service.appendRunnerOutput(id,1,10,9,8,"call-invalid",7,6,0,all,sha);assertThrows(PcException.class,()->f.service.completeRunnerOutput(id,1,10,9,8,"call-invalid",7,6));assertEquals("UPLOADING",f.rows.get(PcTable.ARTIFACT).get(id).getState());assertThrows(PcException.class,()->f.service.content(10,id));assertThrows(PcException.class,()->f.service.beginRunnerOutput(1,10,9,8,"call-too-large",7,6,PcArtifactService.MAX_TOOL_OUTPUT_BYTES+1,sha));
    }
    @Test void outputReadBoundsMemoryAndPreservesOriginalLineSemantics()throws Exception{
        String text="前言\r\n\r\n最后\n";assertEquals("\n最后",PcArtifactService.boundedOutputLines(new java.io.StringReader(text),2,2));assertEquals("",PcArtifactService.boundedOutputLines(new java.io.StringReader(text),20,2));assertEquals("最后",PcArtifactService.boundedOutputLines(new java.io.StringReader(text),3,2));
        assertThrows(PcException.class,()->PcArtifactService.boundedOutputLines(new java.io.StringReader("x".repeat(2*1024*1024+1)),1,1));assertEquals("后续",PcArtifactService.boundedOutputLines(new java.io.StringReader("x".repeat(3*1024*1024)+"\n后续"),2,1));
    }
    @Test void transparentImageIsDecodedAndStoredWithoutLosingAlpha()throws Exception{
        BufferedImage source=new BufferedImage(20,10,BufferedImage.TYPE_INT_ARGB);source.setRGB(1,1,0x40223344);
        ByteArrayOutputStream output=new ByteArrayOutputStream();ImageIO.write(source,"png",output);
        var result=PcArtifactService.normalizeImage(output.toByteArray(),"image/png");
        assertEquals("image/png",result.mimeType());assertEquals(20,result.width());assertEquals(10,result.height());
        assertEquals(0x40223344,ImageIO.read(new java.io.ByteArrayInputStream(result.bytes())).getRGB(1,1));
    }
    @Test void mimeMismatchAndBrokenImageCannotEnterArtifactStore()throws Exception{
        BufferedImage source=new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);ByteArrayOutputStream output=new ByteArrayOutputStream();ImageIO.write(source,"png",output);
        assertThrows(PcException.class,()->PcArtifactService.normalizeImage(output.toByteArray(),"image/jpeg"));
        assertThrows(PcException.class,()->PcArtifactService.normalizeImage("not an image".getBytes(),"image/png"));
        assertThrows(PcException.class,()->PcArtifactService.normalizeImage(new byte[PcArtifactService.MAX_API_IMAGE_BYTES+1],"image/png"));
    }
    @Test void downloadNamesCannotContainPathsOrHeaderControls(){
        assertEquals("截图.png",PcArtifactService.safeName("截图.png"));
        for(String input:new String[]{"../a","a/b","a\\b","a\r\nHeader: bad",""})assertThrows(PcException.class,()->PcArtifactService.safeName(input));
    }
    @Test void persistedArtifactDownloadsWithOwnerAndDigestButDoesNotExposeStorageKey()throws Exception{
        Fixture f=new Fixture(temporary);byte[] bytes="脱敏历史资料".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<String,Object> uploaded=f.service.upload(10,"artifact-same-owner",1,"LEGACY_IMPORT","资料.json","application/json",bytes);
        long id=Long.parseLong(String.valueOf(uploaded.get("id")));var download=f.service.content(10,id);
        assertArrayEquals(bytes,Files.readAllBytes(download.file()));assertEquals(PcArtifactService.digest(bytes),download.sha256());
        assertEquals("资料.json",download.fileName());assertFalse(uploaded.containsKey("storageKey"));
        assertEquals(id,Long.parseLong(String.valueOf(f.service.upload(10,"artifact-same-owner",1,"LEGACY_IMPORT","资料.json","application/json",bytes).get("id"))));
    }
    @Test void workspaceOutsiderAndDifferentOwnerCannotDownloadEvenWhenMember()throws Exception{
        Fixture f=new Fixture(temporary);long id=f.upload("test.json","{}");
        PcException outsider=assertThrows(PcException.class,()->f.service.content(30,id));assertEquals(403,outsider.status());
        // 20 与 10 同为工作区成员，下载仍要求上传者归属。
        PcException otherOwner=assertThrows(PcException.class,()->f.service.content(20,id));assertEquals(403,otherOwner.status());
        assertThrows(PcException.class,()->f.service.runnerImage(id,2,10));
    }
    @Test void injectedStorageKeyCannotReadAPathOutsideArtifactDirectory()throws Exception{
        Fixture f=new Fixture(temporary);long id=f.upload("test.json","{}");PcRecord row=f.rows.get(PcTable.ARTIFACT).get(id);
        Map<String,Object> payload=f.store.data(row);payload.put("storageKey","../../outside.data");row.setPayload(f.json.write(payload));
        PcException failure=assertThrows(PcException.class,()->f.service.content(10,id));assertEquals(403,failure.status());
    }
    @Test void sameSizeTamperingIsDetectedByDigestAndSizeTamperingIsAlsoRejected()throws Exception{
        Fixture f=new Fixture(temporary);long id=f.upload("test.json","abcd");Path file=f.service.content(10,id).file();
        Files.writeString(file,"dcba");PcException digest=assertThrows(PcException.class,()->f.service.content(10,id));assertEquals(409,digest.status());assertTrue(digest.getMessage().contains("摘要"));
        Files.writeString(file,"longer");PcException size=assertThrows(PcException.class,()->f.service.content(10,id));assertEquals(409,size.status());assertTrue(size.getMessage().contains("大小"));
    }
    @Test void expiresAndRevocationDoNotLeaveADownloadableArtifact()throws Exception{
        Fixture f=new Fixture(temporary);long id=f.upload("test.json","{}");PcRecord row=f.rows.get(PcTable.ARTIFACT).get(id);
        row.setExpiresAt(f.clock.instant().minusSeconds(1));assertEquals(410,assertThrows(PcException.class,()->f.service.content(10,id)).status());
        row.setExpiresAt(f.clock.instant().plusSeconds(60));row.setState("REVOKED");assertEquals(410,assertThrows(PcException.class,()->f.service.content(10,id)).status());
    }
    @Test void pngHeaderPixelBombIsRejectedBeforeImageBodyDecode()throws Exception{
        BufferedImage source=new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB);ByteArrayOutputStream output=new ByteArrayOutputStream();ImageIO.write(source,"png",output);byte[] header=output.toByteArray();
        ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).putInt(16,100_000).putInt(20,100_000);CRC32 crc=new CRC32();crc.update(header,12,17);ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).putInt(29,(int)crc.getValue());
        PcException failure=assertThrows(PcException.class,()->PcArtifactService.normalizeImage(header,"image/png"));assertEquals(413,failure.status());assertEquals("PC_IMAGE_PIXELS",failure.code());
    }
    @Test void transparentUploadStaysAnImageAndCannotBeHydratedForAnotherOwner()throws Exception{
        Fixture f=new Fixture(temporary);BufferedImage image=new BufferedImage(3,2,BufferedImage.TYPE_INT_ARGB);image.setRGB(1,1,0x40223344);ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);
        var uploaded=f.service.upload(10,"transparent-upload",1,"TURN_IMAGE","透明.png","image/png",bytes.toByteArray());long id=Long.parseLong(String.valueOf(uploaded.get("id")));
        var transport=f.service.runnerImage(id,1,10);assertEquals("image/png",transport.get("mimeType"));assertNotNull(transport.get("base64Data"));
        assertThrows(PcException.class,()->f.service.runnerImage(id,1,20));assertEquals(transport,f.service.runnerImage(id,2,10),"本人输入图片允许在有显式任务引用的其他执行目录读取");
        assertEquals(0x40223344,ImageIO.read(f.service.content(10,id).file().toFile()).getRGB(1,1));
    }
    @Test void toolOutputIsStoredWholeWithStableDigestAndSegmentedByOriginalLines()throws Exception{
        Fixture f=new Fixture(temporary);String text="首行\r\nsecond\r\n尾行\n";
        Map<String,Object> stored=f.service.storeRunnerOutput(1,10,300,400,"call-1",text);long id=Long.parseLong(stored.get("id").toString());
        assertEquals(text,Files.readString(f.service.content(10,id).file()));assertEquals(PcArtifactService.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),stored.get("sha256"));assertEquals(3L,((Number)stored.get("lines")).longValue());
        Map<String,Object> metadata=f.service.runnerOutputMetadata(id,1,10,300);assertEquals(stored.get("sha256"),metadata.get("sha256"));assertFalse(metadata.containsKey("storageKey"));
        Map<String,Object> segment=f.service.runnerOutputText(id,1,10,300,2,2);assertEquals("second\n尾行",segment.get("text"));assertEquals(stored.get("sha256"),segment.get("sha256"));assertEquals(String.valueOf(id),segment.get("artifactId"));
        assertEquals("",f.service.runnerOutputText(id,1,10,300,4,10).get("text"));
    }
    @Test void toolOutputCannotBeReadFromAnotherWorkspaceOwnerOrSession()throws Exception{
        Fixture f=new Fixture(temporary);long id=Long.parseLong(f.service.storeRunnerOutput(1,10,300,400,"call-2","private fixture").get("id").toString());
        for(long[] scope:new long[][]{{2,10,300},{1,20,300},{1,10,301}}){
            assertEquals(403,assertThrows(PcException.class,()->f.service.runnerOutputMetadata(id,scope[0],scope[1],scope[2])).status());
            assertEquals(403,assertThrows(PcException.class,()->f.service.runnerOutputText(id,scope[0],scope[1],scope[2],1,1)).status());
        }
    }
    @Test void userOutputPagesPreserveUtf8AndOriginalNewlinesWithACompleteDigest()throws Exception{
        Fixture f=new Fixture(temporary);String original="A中文🙂Z\r\n"+"多字节单行🙂".repeat(40);
        long id=Long.parseLong(f.service.storeRunnerOutput(1,10,300,400,"user-output",original).get("id").toString());
        long offset=0;StringBuilder restored=new StringBuilder();String sha=PcArtifactService.digest(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        do{
            Map<String,Object> page=f.service.outputText(10,id,300,offset,7);
            assertEquals(sha,page.get("sha256"));assertEquals(offset,((Number)page.get("offset")).longValue());
            assertEquals(String.valueOf(id),page.get("artifactId"));assertEquals("300",page.get("sessionId"));
            String text=page.get("text").toString();long next=((Number)page.get("nextOffset")).longValue();
            assertEquals(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,next-offset);
            assertTrue(next>offset);assertTrue(next-offset<=7);restored.append(text);offset=next;
            if(Boolean.FALSE.equals(page.get("hasMore")))break;
        }while(true);
        assertEquals(original,restored.toString());assertEquals(original.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,offset);
        var end=f.service.outputText(10,id,300,offset,4);assertEquals("",end.get("text"));assertEquals(false,end.get("hasMore"));
    }
    @Test void userOutputPagesRejectForeignScopeInvalidByteBoundaryAndChangedArtifact()throws Exception{
        Fixture f=new Fixture(temporary);long id=Long.parseLong(f.service.storeRunnerOutput(1,10,300,400,"user-scope","A中文🙂").get("id").toString());
        for(long user:new long[]{20,30})assertEquals(403,assertThrows(PcException.class,()->f.service.outputText(user,id,300,0,7)).status());
        assertEquals(403,assertThrows(PcException.class,()->f.service.outputText(10,id,301,0,7)).status());
        for(long offset:new long[]{-1,2,100})assertEquals(400,assertThrows(PcException.class,()->f.service.outputText(10,id,300,offset,7)).status());
        for(int limit:new int[]{0,3,1024*1024+1})assertEquals(400,assertThrows(PcException.class,()->f.service.outputText(10,id,300,0,limit)).status());
        long ordinary=f.upload("legacy.json","{}");assertEquals(403,assertThrows(PcException.class,()->f.service.outputText(10,ordinary,300,0,7)).status());
        Path file=f.service.content(10,id).file();Files.writeString(file,"B中文🙂");
        assertEquals(409,assertThrows(PcException.class,()->f.service.outputText(10,id,300,0,7)).status());
    }
    @Test void toolOutputRangeAndIdentifierRejectBeforeReadingAndOversizedLineCannotBeReturned()throws Exception{
        Fixture f=new Fixture(temporary);long id=Long.parseLong(f.service.storeRunnerOutput(1,10,300,400,"call-3","one\ntwo").get("id").toString());
        for(int[] range:new int[][]{{0,1},{-1,1},{1,0},{1,2001}})assertEquals(400,assertThrows(PcException.class,()->f.service.runnerOutputText(id,1,10,300,range[0],range[1])).status());
        assertThrows(PcException.class,()->f.service.storeRunnerOutput(1,10,300,400,"../outside","safe"));
        long huge=Long.parseLong(f.service.storeRunnerOutput(1,10,300,400,"call-big","x".repeat(2*1024*1024+1)).get("id").toString());
        assertEquals(413,assertThrows(PcException.class,()->f.service.runnerOutputText(huge,1,10,300,1,1)).status());
    }
    @Test void changedToolOutputDigestIsRejectedByBothMetadataAndChunkReads()throws Exception{
        Fixture f=new Fixture(temporary);long id=Long.parseLong(f.service.storeRunnerOutput(1,10,300,400,"call-4","abcd").get("id").toString());Path file=f.service.content(10,id).file();Files.writeString(file,"dcba");
        assertEquals(409,assertThrows(PcException.class,()->f.service.runnerOutputMetadata(id,1,10,300)).status());
        assertEquals(409,assertThrows(PcException.class,()->f.service.runnerOutputText(id,1,10,300,1,1)).status());
    }
    @Test void diskArtifactsAndTheirRootHaveRealOwnerOnlyPermissions()throws Exception {
        Fixture f=new Fixture(temporary);long id=f.upload("private.json","private fixture");Path file=f.service.content(10,id).file();
        PcArtifactService.requirePrivate(file.getParent(),true);PcArtifactService.requirePrivate(file,false);
        if(Files.getFileAttributeView(file,PosixFileAttributeView.class)!=null){assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(file.getParent()));assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(file));}
        else {UserPrincipal owner=Files.getOwner(file);for(Path path:new Path[]{file.getParent(),file})for(AclEntry entry:Files.getFileAttributeView(path,AclFileAttributeView.class).getAcl())if(entry.type()==AclEntryType.ALLOW)assertEquals(owner,entry.principal());}
    }
    @Test void broadenedRootIsRejectedOnUploadAndReadWithoutSilentlyChangingExistingAcl()throws Exception {
        Fixture f=new Fixture(temporary);long id=f.upload("original.json","retained");Path root=f.service.content(10,id).file().getParent();Object original=permissions(root);
        try {broaden(root);Object broad=permissions(root);assertEquals(503,assertThrows(PcException.class,()->f.upload("rejected.json","new private data")).status());assertEquals(503,assertThrows(PcException.class,()->f.service.content(10,id)).status());assertEquals(broad,permissions(root));try(var files=Files.list(root)){assertEquals(1,files.filter(path->path.toString().endsWith(".data")).count());}}
        finally {restore(root,original);}
    }
    @Test void broadenedFileIsRejectedEvenWhenItsDigestStillMatches()throws Exception {
        Fixture f=new Fixture(temporary);long id=f.upload("private.json","unchanged");Path file=f.service.content(10,id).file();Object original=permissions(file);
        try {broaden(file);assertEquals(503,assertThrows(PcException.class,()->f.service.content(10,id)).status());assertEquals("unchanged",Files.readString(file));}
        finally {restore(file,original);}
    }
    @Test void writablePrivateContainerParentIsRejectedForUploadAndReadWithoutChangingItsPermissions()throws Exception {
        Path container=Files.createDirectory(temporary.resolve("private-container"));Fixture f=new Fixture(temporary,container.resolve("artifacts"));long id=f.upload("existing.json","retained");Object original=permissions(container);
        try {broadenMutation(container);Object broad=permissions(container);assertEquals(503,assertThrows(PcException.class,()->f.upload("blocked.json","private data")).status());assertEquals(503,assertThrows(PcException.class,()->f.service.content(10,id)).status());assertEquals(broad,permissions(container));}
        finally {restore(container,original);}
        assertEquals("retained",Files.readString(f.service.content(10,id).file()));
    }
    @Test void writableGrandparentCannotBeHiddenByTwoPrivateChildDirectories()throws Exception {
        Path ancestor=Files.createDirectory(temporary.resolve("ancestor")),parent=Files.createDirectory(ancestor.resolve("private-parent"));Fixture f=new Fixture(temporary,parent.resolve("artifacts"));long id=f.upload("existing.json","retained");Object original=permissions(ancestor);
        try {broadenMutation(ancestor);assertEquals(503,assertThrows(PcException.class,()->f.service.content(10,id)).status());assertEquals(503,assertThrows(PcException.class,()->f.upload("blocked.json","new")).status());}
        finally {restore(ancestor,original);}
    }
    @Test void parentReparseRedirectCannotCreateOrReadArtifactsInAnotherDirectory()throws Exception {
        Path outside=Files.createDirectory(temporary.resolve("outside")),link=temporary.resolve("linked-parent");directoryLink(link,outside);
        try {Fixture f=new Fixture(temporary,link.resolve("artifacts"));assertEquals(503,assertThrows(PcException.class,()->f.upload("blocked.json","private")).status());try(var children=Files.list(outside)){assertEquals(0,children.count());}}
        finally {Files.delete(link);}
        Fixture f=new Fixture(temporary);long id=f.upload("existing.json","retained");Path root=f.service.content(10,id).file().getParent(),moved=temporary.resolve("moved-artifacts");Files.move(root,moved);directoryLink(root,moved);
        try {assertEquals(503,assertThrows(PcException.class,()->f.service.content(10,id)).status());}
        finally {Files.delete(root);Files.move(moved,root);}
    }
    private static Object permissions(Path path)throws Exception {return Files.getFileAttributeView(path,PosixFileAttributeView.class)!=null?Files.getPosixFilePermissions(path):Files.getFileAttributeView(path,AclFileAttributeView.class).getAcl();}
    @SuppressWarnings("unchecked") private static void restore(Path path,Object value)throws Exception {if(value instanceof java.util.Set<?>)Files.setPosixFilePermissions(path,(java.util.Set<PosixFilePermission>)value);else Files.getFileAttributeView(path,AclFileAttributeView.class).setAcl((java.util.List<AclEntry>)value);}
    private static void broaden(Path path)throws Exception {
        if(Files.getFileAttributeView(path,PosixFileAttributeView.class)!=null){var mode=new java.util.HashSet<>(Files.getPosixFilePermissions(path));mode.add(PosixFilePermission.GROUP_READ);Files.setPosixFilePermissions(path,mode);return;}
        AclFileAttributeView view=Files.getFileAttributeView(path,AclFileAttributeView.class);UserPrincipal everyone=null;for(String name:new String[]{"Everyone","所有人","S-1-1-0"})try{everyone=path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(name);break;}catch(UserPrincipalNotFoundException ignored){}assertNotNull(everyone);var acl=new java.util.ArrayList<>(view.getAcl());acl.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(everyone).setPermissions(AclEntryPermission.READ_DATA,AclEntryPermission.READ_ATTRIBUTES).build());view.setAcl(acl);
    }
    private static void broadenMutation(Path path)throws Exception {
        if(Files.getFileAttributeView(path,PosixFileAttributeView.class)!=null){var mode=new java.util.HashSet<>(Files.getPosixFilePermissions(path));mode.add(PosixFilePermission.GROUP_WRITE);Files.setPosixFilePermissions(path,mode);return;}
        AclFileAttributeView view=Files.getFileAttributeView(path,AclFileAttributeView.class);UserPrincipal everyone=null;for(String name:new String[]{"Everyone","所有人","S-1-1-0"})try{everyone=path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(name);break;}catch(UserPrincipalNotFoundException ignored){}assertNotNull(everyone);var acl=new java.util.ArrayList<>(view.getAcl());acl.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(everyone).setPermissions(AclEntryPermission.DELETE_CHILD,AclEntryPermission.WRITE_ACL).build());view.setAcl(acl);
    }
    private static void directoryLink(Path link,Path target)throws Exception {
        if(Files.getFileAttributeView(target,PosixFileAttributeView.class)!=null){Files.createSymbolicLink(link,target);return;}
        String command="mklink /J \""+link.toAbsolutePath()+"\" \""+target.toAbsolutePath()+"\"";assertFalse(command.matches(".*[&|<>%\r\n].*"));Process process=new ProcessBuilder(Path.of(System.getenv("SystemRoot"),"System32","cmd.exe").toString(),"/d","/c",command).redirectErrorStream(true).start();try{assertTrue(process.waitFor(3,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue());}finally{process.destroyForcibly();}
    }
    /** 真实 Store/Access/Idempotency，只有 MyBatis 传输和审计依赖为 fixture。 */
    private static final class Fixture {
        final Clock clock=Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"),ZoneOffset.UTC);
        final PcJson json=new PcJson(JsonMapper.builder().build());
        final Map<PcTable,Map<Long,PcRecord>> rows=new EnumMap<>(PcTable.class);
        final AtomicLong ids=new AtomicLong(100);
        final PcStore store;
        final PcArtifactService service;
        Fixture(Path directory){this(directory,directory.resolve("artifacts"));}
        Fixture(Path directory,Path storage){
            PcLedgerMapper mapper=mock(PcLedgerMapper.class);for(PcTable table:PcTable.values())rows.put(table,new HashMap<>());
            when(mapper.find(any(PcTable.class),anyLong(),anyBoolean())).thenAnswer(call->rows.get(call.getArgument(0,PcTable.class)).get(call.getArgument(1,Long.class)));
            when(mapper.findKey(any(PcTable.class),anyString(),anyBoolean())).thenAnswer(call->rows.get(call.getArgument(0,PcTable.class)).values().stream().filter(row->call.getArgument(1,String.class).equals(row.getUniqueKey())).findFirst().orElse(null));
            when(mapper.insert(any(PcTable.class),any(PcRecord.class))).thenAnswer(call->{PcRecord row=call.getArgument(1);row.setId(ids.incrementAndGet());rows.get(call.getArgument(0,PcTable.class)).put(row.getId(),row);return 1;});
            when(mapper.update(any(PcTable.class),any(PcRecord.class),anyLong())).thenReturn(1);
            store=new PcStore(mapper,json,clock);PcAccess access=new PcAccess(store);
            PcRecord workspace=new PcRecord();workspace.setId(1L);workspace.setOwnerId(10L);workspace.setState("VERIFIED");workspace.setPayload("{}");rows.get(PcTable.DIRECTORY_CONTEXT).put(1L,workspace);PcRecord policy=new PcRecord();policy.setId(2L);policy.setOwnerId(10L);policy.setState("ACTIVE");policy.setUniqueKey("owner:10");policy.setPayload("{\"retentionDays\":90}");rows.get(PcTable.POLICY_VERSION).put(2L,policy);
            for(long owner:new long[]{10,20}){PcRecord member=new PcRecord();member.setId(owner);member.setOwnerId(owner);member.setWorkspaceId(1L);member.setState("ACTIVE");member.setUniqueKey("workspace:1:user:"+owner);member.setPayload("{\"role\":\"DEVELOPER\"}");rows.get(PcTable.WORKSPACE_MEMBER).put(owner,member);}
            PcCrypto crypto=new PcCrypto(mock(ManagedSecretService.class),json){@Override protected byte[] key(){return new byte[32];}};
            service=new PcArtifactService(store,access,new PcIdempotency(store,crypto),mock(PcEvidence.class),storage);
        }
        long upload(String name,String text){return Long.parseLong(String.valueOf(service.upload(10,"artifact-"+name,1,"LEGACY_IMPORT",name,"application/json",text.getBytes(java.nio.charset.StandardCharsets.UTF_8)).get("id")));}
    }
}
