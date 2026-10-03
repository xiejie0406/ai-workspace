package com.ruoyi.paismart.infrastructure.intelligence;

import com.ruoyi.paismart.infrastructure.Jsons;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** 固定公开文件及独立正文预期；真实调用Worker，不用扩展名或exit0代替正文核对。 */
class ParserFormatParityTest {
    @TempDir Path directory;
    static Stream<Arguments> containers() {
        return Stream.of(
            Arguments.of("doc","testWORD.doc","Sample Word Document Title"),
            Arguments.of("docx","testWORD.docx","Sample Word Document Title"),
            Arguments.of("xls","testEXCEL.xls","Sample Excel Worksheet"),
            Arguments.of("xlsx","testEXCEL.xlsx","Sample Excel Worksheet"),
            Arguments.of("ppt","testPPT.ppt","Sample Powerpoint Slide"),
            Arguments.of("pptx","testPPT.pptx","Attachment Test"),
            Arguments.of("odt","testOpenOffice2.odt","This is a sample Open Office document"),
            Arguments.of("ods","testFooter.ods","Here is a footer in the center area"),
            Arguments.of("odp","testMasterFooter.odp","Master footer is here"),
            Arguments.of("pages","testPages.pages","Sample pages document"),
            Arguments.of("numbers","testNumbers.numbers","Checking Account: 300545668"),
            Arguments.of("key","testKeynote.key","A sample presentation"),
            Arguments.of("keynote","testKeynote.key","A sample presentation"),
            Arguments.of("key2013","testKeynote2013.key","Some random text"),
            Arguments.of("pages2013","testPages2013.pages","A second page"),
            Arguments.of("numbers2013","testNumbers2013.numbers","Groceries"),
            Arguments.of("epub","testEPUB.epub","The previous headings were subchapters")
        );
    }
    private byte[] fixture(String name)throws IOException {
        try(InputStream in=getClass().getResourceAsStream("/paismart/format-samples/"+name)) {
            assertNotNull(in,"固定格式样例缺失: "+name);return in.readAllBytes();
        }
    }
    private String parse(Path input,int maxChars)throws Exception {
        Path output=directory.resolve(UUID.randomUUID()+".json");
        ParserWorker.main(new String[]{input.toString(),output.toString(),"100",Integer.toString(maxChars)});
        List<Object> pages=Values.list(Jsons.map(Files.readString(output)).get("pages"));
        return String.join("\n",pages.stream().map(page->Values.text(Values.map(page).get("text"))).toList());
    }
    @ParameterizedTest(name="{0}正文可读，临时输入无扩展名") @MethodSource("containers")
    void declaredContainersYieldExpectedBody(String extension,String sample,String expected)throws Exception {
        Path input=directory.resolve("input");Files.write(input,fixture(sample));
        assertTrue(parse(input,200000).contains(expected),extension+" 未提取独立预期正文");
    }
    @ParameterizedTest @ValueSource(strings={"txt","md","markdown","csv","json","xml","html","htm","rtf"})
    void declaredTextFormatsYieldBody(String extension)throws Exception {
        String marker="Approved format evidence";
        String content=switch(extension) {
            case "json" -> "{\"message\":\""+marker+"\"}";
            case "xml" -> "<?xml version='1.0'?><document><text>"+marker+"</text></document>";
            case "html","htm" -> "<html><body><p>"+marker+"</p></body></html>";
            case "rtf" -> "{\\rtf1\\ansi "+marker+"}";
            case "csv" -> "key,value\nexample,"+marker+"\n";
            case "md","markdown" -> "# Approved\n\n"+marker;
            default -> marker;
        };
        Path input=directory.resolve("sample."+extension);Files.writeString(input,content);
        assertTrue(parse(input,1000).contains(marker),extension+" 正文缺失");
    }
    @Test void epubDoctypeNeverReadsAnExternalEntity()throws Exception {
        Path secret=directory.resolve("private.txt");Files.writeString(secret,"PRIVATE_ENTITY_MUST_NOT_APPEAR");
        Path input=directory.resolve("input");
        try(ZipInputStream source=new ZipInputStream(new ByteArrayInputStream(fixture("testEPUB.epub")));ZipOutputStream output=new ZipOutputStream(Files.newOutputStream(input))) {
            ZipEntry entry;
            while((entry=source.getNextEntry())!=null) {
                output.putNextEntry(new ZipEntry(entry.getName()));byte[] bytes=source.readAllBytes();
                if(entry.getName().endsWith(".html")||entry.getName().endsWith(".xhtml")) {
                    String xml="<?xml version='1.0'?><!DOCTYPE html [<!ENTITY x SYSTEM '"+secret.toUri()+"'>]><html xmlns='http://www.w3.org/1999/xhtml'><body><p>Approved safe text</p><p>&x;</p></body></html>";
                    bytes=xml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                output.write(bytes);output.closeEntry();
            }
        }
        try {assertFalse(parse(input,200000).contains("PRIVATE_ENTITY_MUST_NOT_APPEAR"));}
        catch(org.apache.tika.exception.TikaException rejected) {try(var files=Files.list(directory)){assertFalse(files.anyMatch(path->path.getFileName().toString().endsWith(".json")));}}
    }
    @Test void rtfFallbackHonorsTextBudgetAndUnicode()throws Exception {
        Path input=directory.resolve("sample.rtf");Files.writeString(input,"{\\rtf1\\ansi "+"approved ".repeat(100)+"}");
        assertThrows(Exception.class,()->ParserWorker.rtfBody(input,30));
        Files.writeString(input,"{\\rtf1\\ansi\\uc1 \\u30693?\\u35782? Unicode evidence}");
        assertTrue(parse(input,1000).contains("知识 Unicode evidence"));
    }
}
