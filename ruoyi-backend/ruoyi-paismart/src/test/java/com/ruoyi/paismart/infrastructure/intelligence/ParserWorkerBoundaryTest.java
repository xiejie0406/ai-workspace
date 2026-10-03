package com.ruoyi.paismart.infrastructure.intelligence;

import com.ruoyi.paismart.infrastructure.Jsons;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import static org.junit.jupiter.api.Assertions.*;

/** 本地真实文件解析；直接调用Worker主逻辑，不启动服务、CLI或任何OCR进程。 */
class ParserWorkerBoundaryTest {
    @TempDir Path directory;
    Map<String,Object> parse(Path input,int pages,int chars)throws Exception{Path output=directory.resolve(UUID.randomUUID()+".json");ParserWorker.main(new String[]{input.toString(),output.toString(),Integer.toString(pages),Integer.toString(chars)});return Jsons.map(Files.readString(output));}
    @Test void parsesChinesePlainTextWithRealTika()throws Exception{Path input=directory.resolve("sample.txt");Files.writeString(input,"批准样本：知识库文字解析。\n第二行123");var result=parse(input,10,1000);assertTrue(Values.text(Values.map(Values.list(result.get("pages")).get(0)).get("text")).contains("知识库文字解析"));}
    @Test void htmlExtractsVisibleTextWithoutScripts()throws Exception{Path input=directory.resolve("sample.html");Files.writeString(input,"<html><body><p>Visible evidence</p><script>window.location='https://example.invalid'</script></body></html>");String text=Values.text(Values.map(Values.list(parse(input,10,1000).get("pages")).get(0)).get("text"));assertTrue(text.contains("Visible evidence"));assertFalse(text.contains("window.location"));}
    @Test void xmlCannotReadLocalExternalEntity()throws Exception{Path secret=directory.resolve("private.txt");Files.writeString(secret,"LOCAL_ENTITY_MUST_NOT_APPEAR");Path input=directory.resolve("sample.xml");Files.writeString(input,"<?xml version='1.0'?><!DOCTYPE r [<!ENTITY x SYSTEM '"+secret.toUri()+"'>]><r>&x;</r>");assertThrows(Exception.class,()->parse(input,10,1000));}
    @Test void blankPdfPagesAreCountedAndTextIsExtracted()throws Exception{Path pdf=directory.resolve("sample.pdf");try(var document=new PDDocument()){var page=new PDPage();document.addPage(page);document.addPage(new PDPage());try(var stream=new PDPageContentStream(document,page)){stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);stream.newLineAtOffset(30,700);stream.showText("Approved PDF evidence");stream.endText();}document.save(pdf.toFile());}var pages=Values.list(parse(pdf,2,1000).get("pages"));assertEquals(2,pages.size());assertTrue(Values.text(Values.map(pages.get(0)).get("text")).contains("Approved PDF evidence"));assertTrue(Values.text(Values.map(pages.get(1)).get("text")).isBlank());assertThrows(IllegalArgumentException.class,()->parse(pdf,1,1000));}
    @Test void textAndBrokenPdfBudgetsRejectInsteadOfReportingSuccess()throws Exception{Path text=directory.resolve("long.txt");Files.writeString(text,"content ".repeat(100));assertThrows(Exception.class,()->parse(text,10,30));Path pdf=directory.resolve("broken.pdf");Files.writeString(pdf,"%PDF- broken");assertThrows(Exception.class,()->parse(pdf,10,1000));}
    @Test void safeContextDisablesEmbeddedAndOcr()throws Exception{var context=ParserWorker.safeContext();assertFalse(context.get(EmbeddedDocumentExtractor.class).shouldParseEmbedded(new Metadata()));assertTrue(context.get(TesseractOCRConfig.class).isSkipOcr());assertFalse(context.get(TesseractOCRConfig.class).isEnableImagePreprocessing());assertTrue(context.get(javax.xml.parsers.SAXParser.class).getXMLReader().getFeature("http://apache.org/xml/features/disallow-doctype-decl"));}
    @Test void nonpositiveBudgetsNeverEnableUnlimitedParsing()throws Exception{Path input=directory.resolve("sample.txt");Files.writeString(input,"Approved evidence");for(int[] budget:List.of(new int[]{0,100},new int[]{10,0},new int[]{10,-1}))assertThrows(IllegalArgumentException.class,()->parse(input,budget[0],budget[1]));try(var files=Files.list(directory)){assertFalse(files.anyMatch(path->path.getFileName().toString().endsWith(".json")));}}
}
