package com.ruoyi.aipicture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.ruoyi.aipicture.config.*;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.storage.*;
import com.ruoyi.aipicture.worker.*;

class StorageContractTest {
    @TempDir Path directory;
    @Test void realWebpBytesAndUppercasePillowMetadataKeepWebpAcceptedAndHashStable() throws Exception {
        // Pillow 生成的真实 40×60 WebP，覆盖本机 JDK 无 WebP reader 的私有 inspect 边界。
        byte[] bytes=Base64.getDecoder().decode("UklGRkwAAABXRUJQVlA4IEAAAADQAwCdASooADwAPm02mEkkIyKhIqgAgA2JZwB2APwAAC//NPhCRYAA/uuOX//5Z8/hs6nP//mwK5rn3NAAAAAA");
        var p=new AiPictureProperties();p.setStorageRoot(directory.toAbsolutePath().toString());var client=mock(AiPictureWorkerClient.class);when(client.call(eq("compute"),anyMap())).thenReturn(Json.of("result",Json.of("format","WEBP","width",40,"height",60,"has_alpha",false)));
        var storage=new AiPictureStorage(p,client);String id=Json.id();var image=storage.image(bytes,id,"fixture");assertEquals("webp",image.get("format"));assertEquals(WorkerSignatures.sha256(bytes),image.get("sha256"));assertArrayEquals(bytes,storage.read(Json.string(image.get("object_key"))));assertTrue(Json.string(image.get("object_key")).endsWith(".webp"));
    }
    @Test void failedDatabaseCommitDoesNotMakeIdenticalZipReuploadFail() throws Exception {
        var p=new AiPictureProperties();p.setStorageRoot(directory.toAbsolutePath().toString());var storage=new AiPictureStorage(p,mock(AiPictureWorkerClient.class));String id=Json.id();byte[] bytes="controlled-zip-receipt".getBytes();var first=storage.binary(bytes,id,"zip","fixture");var repeated=storage.binary(bytes,id,"zip","fixture");assertEquals(first.get("sha256"),repeated.get("sha256"));assertThrows(AiPictureException.class,()->storage.binary("different".getBytes(),id,"zip","fixture"));
    }
    @Test void tinyObjectLayerKeepsExactPixelsWhilePublicUploadStillRequires32() throws Exception {
        var p=new AiPictureProperties();p.setStorageRoot(directory.toAbsolutePath().toString());var storage=new AiPictureStorage(p,mock(AiPictureWorkerClient.class));
        var image=new BufferedImage(8,10,BufferedImage.TYPE_INT_ARGB);image.setRGB(2,3,0xffabccdd);var buffer=new ByteArrayOutputStream();ImageIO.write(image,"PNG",buffer);byte[] bytes=buffer.toByteArray();
        assertThrows(AiPictureException.class,()->storage.image(bytes,Json.id(),"upload"));
        var artifact=storage.artifactImage(bytes,Json.id(),"staging");assertEquals(8,artifact.get("width"));assertEquals(10,artifact.get("height"));assertArrayEquals(bytes,storage.read(Json.string(artifact.get("object_key"))));
        new com.ruoyi.aipicture.tool.ToolRegistry(p).document(com.ruoyi.aipicture.tool.DocumentCommandService.documentOf(artifact));
    }
}
