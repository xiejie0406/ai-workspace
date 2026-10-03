package com.ruoyi.aippt.application;
import com.ruoyi.aippt.domain.AipptException;
import com.ruoyi.aippt.infrastructure.storage.LocalAipptStorage;
import java.io.*;
import java.nio.file.Path;
import java.util.zip.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class AssetValidationTest  {
    @TempDir Path directory;
    @Test void actualDecodedPngPassesAndSpoofedHeadersFail()throws Exception  {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(20,20,BufferedImage.TYPE_INT_RGB),"png",bytes);
        assertEquals("image/png",AssetService.validate(bytes.toByteArray(),"图.png","image"));
        assertThrows(AipptException.class,()->AssetService.validate(new byte[]  {
            (byte)137,'P','N','G',0,0,0,0,0,0,0,0
        }
        ,"假.png","image"));
        assertThrows(AipptException.class,()->AssetService.validate("RIFF0000WEBPVP8X0000000000000000".getBytes(),"假.webp","image"));
    }
    @Test void docxNeedsDocumentXmlAndZipTraversalIsRejected()throws Exception  {
        assertThrows(AipptException.class,()->AssetService.validate("%PDF-1".getBytes(),"假.docx","source"));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes))  {
            zip.putNextEntry(new ZipEntry("../word/document.xml"));
            zip.write("bad".getBytes());
            zip.closeEntry();
        }
        assertThrows(AipptException.class,()->AssetService.validate(bytes.toByteArray(),"恶意.docx","source"));
    }
    @Test void localStorageRejectsTraversalAndPreservesBytes()throws Exception  {
        LocalAipptStorage storage=new LocalAipptStorage(directory.toString());
        String key="00000000-0000-0000-0000-000000000001/00000000-0000-0000-0000-000000000002";
        byte[] original=  {
            1,2,3
        }
        ;
        storage.put(key,original,"application/octet-stream");
        assertArrayEquals(original,storage.get(key));
        assertThrows(IOException.class,()->storage.get("../outside"));
        storage.delete(key);
        assertThrows(IOException.class,()->storage.get(key));
    }
}
