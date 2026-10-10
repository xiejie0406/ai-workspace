package com.ruoyi.paismart.infrastructure.intelligence;
import com.ruoyi.paismart.infrastructure.Jsons;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.parsers.SAXParser;
import org.xml.sax.*;

/** 独立受限解析进程；不启动Spring、访问业务库或装配密钥，OS低权限由部署运行用户落实。 */
public final class ParserWorker {
    private ParserWorker(){}
    public static void main(String[] args)throws Exception{if(args.length!=4)throw new IllegalArgumentException("解析worker参数不完整");Path input=Path.of(args[0]),output=Path.of(args[1]);int maxPages=Integer.parseInt(args[2]),maxChars=Integer.parseInt(args[3]);if(maxPages<=0||maxChars<=0)throw new IllegalArgumentException("解析页数和字符预算必须为正整数");List<Map<String,Object>> pages=new ArrayList<>();boolean pdf;try(InputStream in=Files.newInputStream(input)){pdf=Arrays.equals(in.readNBytes(5),"%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
        if(pdf){try(var document=Loader.loadPDF(input.toFile())){if(document.getNumberOfPages()>maxPages)throw new IllegalArgumentException("PDF超过页数上限");int count=0;PDFTextStripper stripper=new PDFTextStripper();for(int page=1;page<=document.getNumberOfPages();page++){stripper.setStartPage(page);stripper.setEndPage(page);String text=stripper.getText(document);if((count+=text.length())>maxChars)throw new IllegalArgumentException("PDF超过文本预算");pages.add(Map.of("number",page,"text",text));}}}
        else{Optional<String> iwork=IworkBodyExtractor.extract(input,maxChars);
            if(iwork.isPresent()){pages.add(Map.of("number",1,"text",iwork.get()));}
            else{BodyContentHandler handler=new BodyContentHandler(maxChars);AutoDetectParser parser=safeParser();Metadata metadata=new Metadata();
            // 业务执行器的临时输入没有扩展名，容器识别必须以实际字节为依据。
            String type;
            try(TikaInputStream in=TikaInputStream.get(input)){
                type=parser.getDetector().detect(in,metadata).toString();
                // EPUB内部标准XHTML含DOCTYPE；只允许其声明，仍禁用所有外部实体和DTD读取。
                parser.parse(in,handler,metadata,safeContext("application/epub+zip".equals(type)));
            }
            String text=handler.toString();
            // Tika对没有显式段落控制字的有效RTF可能只返回换行；JDK读入器补正文而不启动UI。
            if(text.isBlank() && Set.of("application/rtf","text/rtf").contains(type)) text=rtfBody(input,maxChars);
            pages.add(Map.of("number",1,"text",text));}}
        Files.writeString(output,Jsons.write(Map.of("pages",pages)),StandardOpenOption.CREATE_NEW);
    }
    static String rtfBody(Path input,int maxChars)throws Exception {
        var document=new javax.swing.text.DefaultStyledDocument(){
            @Override public void insertString(int offset,String value,javax.swing.text.AttributeSet attributes)throws javax.swing.text.BadLocationException {
                // RTFReader会把BadLocationException包装成InternalError；预算拒绝使用可控异常直接传播。
                if(value!=null && (long)getLength()+value.length()>maxChars)throw new IllegalArgumentException("RTF超过文本预算");
                super.insertString(offset,value,attributes);
            }
        };
        try(InputStream in=Files.newInputStream(input)){new javax.swing.text.rtf.RTFEditorKit().read(in,document,0);}
        return document.getText(0,document.getLength());
    }
    static AutoDetectParser safeParser()throws Exception{String xml="<properties><parsers><parser class=\"org.apache.tika.parser.DefaultParser\"><parser-exclude class=\"org.apache.tika.parser.ocr.TesseractOCRParser\"/><parser-exclude class=\"org.apache.tika.parser.external.ExternalParser\"/><parser-exclude class=\"org.apache.tika.parser.pdf.PDFParser\"/></parser></parsers></properties>";return new AutoDetectParser(new TikaConfig(new ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))));}
    static ParseContext safeContext()throws Exception{return safeContext(false);}
    static ParseContext safeContext(boolean epubDoctype)throws Exception{ParseContext context=new ParseContext();
        context.set(EmbeddedDocumentExtractor.class,new EmbeddedDocumentExtractor(){public boolean shouldParseEmbedded(Metadata m){return false;}public void parseEmbedded(InputStream i,ContentHandler h,Metadata m,boolean html)throws SAXException{throw new SAXException("嵌入附件不在本次解析授权内");}});
        TesseractOCRConfig ocr=new TesseractOCRConfig();ocr.setSkipOcr(true);ocr.setEnableImagePreprocessing(false);context.set(TesseractOCRConfig.class,ocr);
        SAXParserFactory factory=SAXParserFactory.newInstance();factory.setNamespaceAware(true);factory.setXIncludeAware(false);factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",!epubDoctype);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
        SAXParser sax=factory.newSAXParser();sax.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD,"");sax.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");sax.getXMLReader().setEntityResolver((publicId,systemId)->{throw new SAXException("禁止解析外部实体");});context.set(SAXParser.class,sax);context.set(SAXParserFactory.class,factory);return context;
    }
}
