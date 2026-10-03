package com.ruoyi.paismart.infrastructure.intelligence;

import com.ruoyi.paismart.infrastructure.Jsons;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** 固定公开iWork13文件核对真实正文；独立协议夹具覆盖新旧单元格及受控拒绝。 */
class IworkBodyExtractorTest {
    @TempDir Path directory;
    static Stream<Arguments> modernContainers() {
        return Stream.of(
            Arguments.of("testKeynote2013.key",List.of("A sample presentation","Some random text","Cell one","Cell eight")),
            Arguments.of("testPages2013.pages",List.of("Sample pages document","A second page","A text box with text.","Cell nine")),
            Arguments.of("testNumbers2013.numbers",List.of("Checking Account: 300545668","Groceries","-226","-137.5","4650","2009-10-01T00:00:00Z"))
        );
    }
    @ParameterizedTest(name="{0}固定正文及表格预期") @MethodSource("modernContainers")
    void modernPublicSamplesYieldStoredBody(String name,List<String> expected)throws Exception {
        Path input=directory.resolve("input");
        try(InputStream in=getClass().getResourceAsStream("/paismart/format-samples/"+name)){assertNotNull(in);Files.copy(in,input);}
        String body=IworkBodyExtractor.extract(input,200000).orElseThrow();
        for(String marker:expected)assertTrue(body.contains(marker),name+" 缺预期正文: "+marker);
        assertFalse(body.contains("Title Text"),"母版默认标题不应作为正文");
        assertFalse(body.contains("Body Level One"),"母版占位文字不应作为正文");
    }
    @Test void legacyAndNonZipFilesRemainWithOriginalParser()throws Exception {
        Path plain=directory.resolve("plain");Files.writeString(plain,"Approved text");
        assertTrue(IworkBodyExtractor.extract(plain,1000).isEmpty());
        Path zip=container(Map.of("index.apxl","<slide>Approved old body</slide>".getBytes(StandardCharsets.UTF_8)));
        assertTrue(IworkBodyExtractor.extract(zip,1000).isEmpty());
    }
    @Test void nonIwaOfficeContainerAboveIworkEntryLimitStillUsesOriginalTika()throws Exception {
        Map<String,byte[]> entries=new LinkedHashMap<>();
        try(InputStream in=getClass().getResourceAsStream("/paismart/format-samples/testWORD.docx")) {
            assertNotNull(in);try(ZipInputStream zip=new ZipInputStream(in)){ZipEntry entry;while((entry=zip.getNextEntry())!=null)entries.put(entry.getName(),zip.readAllBytes());}
        }
        for(int i=0;i<4097;i++)entries.put("word/unused-part-"+i+".xml","<root/>".getBytes(StandardCharsets.UTF_8));
        Path input=container(entries);assertTrue(entries.size()>4096);assertTrue(IworkBodyExtractor.extract(input,200000).isEmpty());
        Path output=directory.resolve("office-output.json");ParserWorker.main(new String[]{input.toString(),output.toString(),"100","200000"});
        String body=Values.list(Jsons.map(Files.readString(output)).get("pages")).stream().map(page->Values.text(Values.map(page).get("text"))).reduce("",(all,text)->all+text);
        assertTrue(body.contains("Sample Word Document Title"),"非IWA DOCX必须仍进入原Tika正文解析");
    }
    @Test void bodyUsesKnownTextTypesAndSkipsPreviewMetadataAndMasterTemplate()throws Exception {
        byte[] body=concat(object(1,2005,storage(0,"批准正文",true)),object(2,7000,string(3,"Metadata marker")),object(3,6005,concat(number(1,1),bytes(3,concat(number(1,5),string(3,"Unreferenced cell"))))));
        Path input=container(Map.of("Index/Document.iwa",frame(body),"Index/MasterSlide.iwa",frame(object(4,2001,storage(3,"Template marker",true))),"Metadata/Properties.plist","Preview marker".getBytes(StandardCharsets.UTF_8)));
        assertEquals("批准正文\n",IworkBodyExtractor.extract(input,1000).orElseThrow());
    }
    @Test void unreferencedOrNonDocumentStringsNeverBecomeBody()throws Exception {
        Path input=container(Map.of("Index/Document.iwa",frame(concat(object(1,2001,storage(0,"Dormant text",false)),object(2,7000,string(3,"Only metadata"))))));
        rejected(input,IworkBodyExtractor.Limits.defaults(1000),"未找到");
    }
    @Test void oneNestedIndexZipIsSupportedWithoutExtractingFiles()throws Exception {
        byte[] inner=zip(Map.of("Index/Document.iwa",frame(object(1,2001,storage(0,"Nested body",true)))));
        assertEquals("Nested body\n",IworkBodyExtractor.extract(container(Map.of("Index.zip",inner)),1000).orElseThrow());
        byte[] nested=zip(Map.of("Index/another.zip",new byte[]{1}));
        rejected(container(Map.of("Index.zip",nested)),IworkBodyExtractor.Limits.defaults(1000),"嵌套ZIP");
        rejected(container(Map.of("Index.zip",new byte[]{1,2,3})),IworkBodyExtractor.Limits.defaults(1000),"未找到");
    }
    @Test void overlappingSnappyCopiesProduceTheStoredBody()throws Exception {
        byte[] raw=object(1,2001,storage(0,"ababab",true));
        // 字符串后仍有in_document字段。字面量含首个ab，再以offset=2复制其余4字节。
        int at=indexOf(raw,"ababab".getBytes(StandardCharsets.UTF_8));
        for(byte[] copy:List.of(new byte[]{1,2},new byte[]{14,2,0},new byte[]{15,2,0,0,0})) {
            byte[] compressed=concat(varint(raw.length),literal(Arrays.copyOfRange(raw,0,at+2)),copy,literal(Arrays.copyOfRange(raw,at+6,raw.length)));
            assertEquals("ababab\n",IworkBodyExtractor.extract(container(Map.of("Index/Document.iwa",framed(compressed))),1000).orElseThrow());
        }
    }
    @Test void bothOldAndNewTableCellsResolveValuesAndRichTextReferences()throws Exception {
        for(boolean newer:new boolean[]{false,true}) {
            byte[] cells=concat(cell(newer,3,newer?8:16,little(20,4)),cell(newer,2,newer?2:32,little(Double.doubleToLongBits(-12.5),8)),cell(newer,6,newer?2:32,little(Double.doubleToLongBits(1),8)),cell(newer,5,newer?4:64,little(0,8)),cell(newer,9,newer?16:512,little(30,4)));
            int factor=newer?4:1;
            byte[] offsets=concat(little(0,2),little(16/factor,2),little(36/factor,2),little(56/factor,2),little(76/factor,2));
            byte[] data=tableObjects(newer,cells,offsets);
            String text=IworkBodyExtractor.extract(container(Map.of("Index/Tables.iwa",frame(data))),1000).orElseThrow();
            assertEquals("Values\nApproved cell\t-12.5\ttrue\t2001-01-01T00:00:00Z\tRich cell\n",text);
        }
    }
    @Test void decimal128MantissaAndSignedExponentAreReadAsStoredNumbers()throws Exception {
        byte[] decimal=concat(little(123,14),little(12346,2)); // 123 * 10^-3
        byte[] data=tableObjects(true,cell(true,2,1,decimal),little(0,2));
        assertTrue(IworkBodyExtractor.extract(container(Map.of("Index/Tables.iwa",frame(data))),1000).orElseThrow().contains("0.123"));
    }
    @Test void tableNamesAndUnusedStringPoolsCannotMakeAnEmptyTableBody()throws Exception {
        byte[] empty=tableObjects(false,new byte[0],new byte[0]);
        rejected(container(Map.of("Index/Tables.iwa",frame(empty))),IworkBodyExtractor.Limits.defaults(1000),"未找到");
        byte[] document=concat(empty,object(9,2001,storage(0,"Approved body",true)));
        assertEquals("Approved body\n",IworkBodyExtractor.extract(container(Map.of("Index/Document.iwa",frame(document))),1000).orElseThrow());
    }
    @Test void leadingMiddleAndTrailingEmptyColumnsKeepTheirPositions()throws Exception {
        byte[] cells=concat(cell(true,3,8,little(20,4)),cell(true,3,8,little(20,4)));
        byte[] offsets=concat(little(65535,2),little(0,2),little(65535,2),little(4,2),little(65535,2));
        byte[] data=tableObjects(true,cells,offsets);
        assertEquals("Values\n\tApproved cell\t\tApproved cell\t\n",IworkBodyExtractor.extract(container(Map.of("Index/Tables.iwa",frame(data))),1000).orElseThrow());
    }
    @Test void extremelyWideEmptyRowsCannotBypassTheCharacterBudget()throws Exception {
        byte[] offsets=new byte[22];Arrays.fill(offsets,(byte)255);
        Path input=container(Map.of("Index/Tables.iwa",frame(tableObjects(false,new byte[0],offsets))));
        rejected(input,IworkBodyExtractor.Limits.defaults(10),"列数");
    }
    @Test void entryCompressedExpandedObjectFieldAndCharacterBudgetsAreEnforced()throws Exception {
        Path input=container(Map.of("Index/Document.iwa",frame(object(1,2001,storage(0,"Approved body",true))),"Metadata/Properties.plist",new byte[]{0}));
        rejected(input,new IworkBodyExtractor.Limits(1,10000,10000,100,1000,1000),"条目");
        rejected(input,new IworkBodyExtractor.Limits(10,1,10000,100,1000,1000),"ZIP解压");
        rejected(input,new IworkBodyExtractor.Limits(10,10000,1,100,1000,1000),"Snappy展开");
        Path two=container(Map.of("Index/Document.iwa",frame(concat(object(1,2001,storage(0,"One",true)),object(2,2001,storage(0,"Two",true))))));
        rejected(two,new IworkBodyExtractor.Limits(10,10000,10000,1,1000,1000),"对象");
        rejected(input,new IworkBodyExtractor.Limits(10,10000,10000,100,1,1000),"字段");
        rejected(input,new IworkBodyExtractor.Limits(10,10000,10000,100,1000,5),"字符");
    }
    @Test void unsafeNamesAndDuplicateZipEntriesAreRejected()throws Exception {
        byte[] content=frame(object(1,2001,storage(0,"Body",true)));
        // 具备现代iWork入口后，容器内任意危险路径都必须拒绝；不把未知ZIP夹具当IWA。
        rejected(container(Map.of("Index/Document.iwa",content,"../Index/Document.iwa",content)),IworkBodyExtractor.Limits.defaults(1000),"路径");
        byte[] duplicated=zip(new LinkedHashMap<>(Map.of("Index/A.iwa",content,"Index/B.iwa",content)));
        byte[] old="Index/B.iwa".getBytes(StandardCharsets.UTF_8),replacement="Index/A.iwa".getBytes(StandardCharsets.UTF_8);
        for(int i=0;i<=duplicated.length-old.length;i++)if(Arrays.equals(Arrays.copyOfRange(duplicated,i,i+old.length),old))System.arraycopy(replacement,0,duplicated,i,replacement.length);
        Path input=directory.resolve(UUID.randomUUID()+".zip");Files.write(input,duplicated);
        rejected(input,IworkBodyExtractor.Limits.defaults(1000),"重复名称");
    }
    @Test void malformedCompressionTruncatedArchiveAndDuplicateObjectIdsReject()throws Exception {
        for(byte[] compressed:List.of(new byte[]{4,1,0},new byte[]{4,1,1},new byte[]{2,0,65},new byte[]{1,3,1,0,0,0})) {
            rejected(container(Map.of("Index/Document.iwa",framed(compressed))),IworkBodyExtractor.Limits.defaults(1000),"Snappy");
        }
        rejected(container(Map.of("Index/Document.iwa",new byte[]{1,0,0,0})),IworkBodyExtractor.Limits.defaults(1000),"帧类型");
        rejected(container(Map.of("Index/Document.iwa",frame(new byte[]{2,8}))),IworkBodyExtractor.Limits.defaults(1000),"截断");
        byte[] duplicate=concat(object(1,2001,storage(0,"One",true)),object(1,2001,storage(0,"Two",true)));
        rejected(container(Map.of("Index/Document.iwa",frame(duplicate))),IworkBodyExtractor.Limits.defaults(1000),"标识重复");
    }
    @Test void malformedUtf8UnsupportedWireAndMissingCellReferenceReject()throws Exception {
        byte[] invalidText=concat(number(1,0),bytes(3,new byte[]{(byte)0xc3,0x28}),number(10,1));
        rejected(container(Map.of("Index/Document.iwa",frame(object(1,2001,invalidText)))),IworkBodyExtractor.Limits.defaults(1000),"UTF8");
        rejected(container(Map.of("Index/Document.iwa",frame(object(1,2001,new byte[]{11})))),IworkBodyExtractor.Limits.defaults(1000),"wire");
        byte[] missing=tableObjects(false,cell(false,3,16,little(999,4)),little(0,2));
        rejected(container(Map.of("Index/Tables.iwa",frame(missing))),IworkBodyExtractor.Limits.defaults(1000),"引用缺失");
    }
    @Test void cellOffsetsAndNonFiniteStoredNumbersReject()throws Exception {
        byte[] data=tableObjects(true,cell(true,2,2,little(Double.doubleToLongBits(1),8)),little(100,2));
        rejected(container(Map.of("Index/Tables.iwa",frame(data))),IworkBodyExtractor.Limits.defaults(1000),"offset越界");
        byte[] nonFinite=tableObjects(false,cell(false,2,32,little(Double.doubleToLongBits(Double.NaN),8)),little(0,2));
        rejected(container(Map.of("Index/Tables.iwa",frame(nonFinite))),IworkBodyExtractor.Limits.defaults(1000),"非有限");
    }
    private void rejected(Path input,IworkBodyExtractor.Limits limits,String reason) {
        IOException error=assertThrows(IOException.class,()->IworkBodyExtractor.extract(input,limits));
        assertTrue(error.getMessage().startsWith("IWORK_BODY_INVALID:"),error.toString());
        assertTrue(error.getMessage().contains(reason),error.toString());
    }
    private Path container(Map<String,byte[]> entries)throws IOException {
        Path input=directory.resolve(UUID.randomUUID()+".zip");Files.write(input,zip(entries));return input;
    }
    private static byte[] zip(Map<String,byte[]> entries)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(out)) {for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}
        return out.toByteArray();
    }
    private static byte[] storage(int kind,String text,boolean inDocument){return concat(number(1,kind),string(3,text),number(10,inDocument?1:0));}
    private static byte[] object(long id,int type,byte[] body) {
        byte[] descriptor=concat(number(1,type),number(3,body.length));byte[] info=concat(number(1,id),bytes(2,descriptor));return concat(varint(info.length),info,body);
    }
    private static byte[] frame(byte[] raw){return framed(concat(varint(raw.length),literal(raw)));}
    private static byte[] framed(byte[] snappy){return concat(new byte[]{0},little(snappy.length,3),snappy);}
    private static byte[] literal(byte[] raw) {
        if(raw.length==0)return new byte[0];int count=raw.length-1;
        if(count<60)return concat(new byte[]{(byte)(count<<2)},raw);
        int width=count<=255?1:count<=65535?2:count<=16777215?3:4;
        return concat(new byte[]{(byte)((59+width)<<2)},little(count,width),raw);
    }
    private static byte[] tableObjects(boolean newer,byte[] cells,byte[] offsets) {
        byte[] row=concat(number(1,0),bytes(newer?6:3,cells),bytes(newer?7:4,offsets),newer?number(8,1):new byte[0]);
        byte[] tiles=bytes(1,concat(number(1,0),bytes(2,number(1,4))));
        byte[] store=concat(bytes(3,tiles),bytes(4,number(1,3)),bytes(17,number(1,5)));
        return concat(object(2,6001,concat(bytes(4,store),string(8,"Values"))),object(3,6005,concat(number(1,1),bytes(3,concat(number(1,20),string(3,"Approved cell"))))),object(4,6002,bytes(5,row)),object(5,6005,concat(number(1,8),bytes(3,concat(number(1,30),bytes(9,number(1,6)))))),object(6,6218,bytes(1,number(1,7))),object(7,2001,storage(5,"Rich cell",true)));
    }
    private static byte[] cell(boolean newer,int type,int flags,byte[] payload) {
        byte[] header=new byte[12];header[0]=(byte)(newer?5:4);header[1]=(byte)type;
        byte[] encoded=little(flags,newer?4:2);System.arraycopy(encoded,0,header,newer?8:4,encoded.length);return concat(header,payload);
    }
    private static byte[] number(int field,long value){return concat(varint((long)field<<3),varint(value));}
    private static byte[] bytes(int field,byte[] value){return concat(varint(((long)field<<3)|2),varint(value.length),value);}
    private static byte[] string(int field,String value){return bytes(field,value.getBytes(StandardCharsets.UTF_8));}
    private static byte[] little(long value,int size) {
        byte[] bytes=new byte[size];for(int i=0;i<size;i++)bytes[i]=(byte)(i<8?value>>>(8*i):0);return bytes;
    }
    private static byte[] varint(long value) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();while((value&~127L)!=0){out.write((int)(value&127)|128);value>>>=7;}out.write((int)value);return out.toByteArray();
    }
    private static byte[] concat(byte[]... chunks) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] chunk:chunks)out.writeBytes(chunk);return out.toByteArray();
    }
    private static int indexOf(byte[] data,byte[] part) {
        for(int i=0;i<=data.length-part.length;i++)if(Arrays.equals(Arrays.copyOfRange(data,i,i+part.length),part))return i;throw new IllegalArgumentException("缺测试压缩段");
    }
}
