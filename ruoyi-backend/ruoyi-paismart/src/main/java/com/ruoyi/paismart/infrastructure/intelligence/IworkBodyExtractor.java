package com.ruoyi.paismart.infrastructure.intelligence;

import java.io.*;
import java.math.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

/**
 * iWork13已知正文类型的有界读取，不读取预览、plist、样式名或嵌入媒体。
 * 协议依据：MIT obriensp/iWorkFileFormat 的 IWA/TSWP/TST，Google Snappy公开格式；
 * 单元格字段布局另核对 LibreOffice libetonyek IWAParser。独立实现，不加载原生库。
 * 不重建视觉分页、图表绘图或公式；表格使用已存的单元格值。
 */
public final class IworkBodyExtractor {
    public record Limits(int entries,int zipBytes,int decodedBytes,int objects,int fields,int chars) {
        public Limits {if(entries<1||zipBytes<1||decodedBytes<1||objects<1||fields<1||chars<1)throw new IllegalArgumentException("iWork预算必须为正数");}
        public static Limits defaults(int maxChars){return new Limits(4096,32*1024*1024,32*1024*1024,50000,250000,maxChars);}
    }
    private record Slice(byte[] data,int start,int size) {
        Cursor cursor(){return new Cursor(data,start,start+size);}
    }
    private record Field(int wire,long value,Slice bytes) {}
    private record Message(Map<Integer,List<Field>> fields) {
        List<Field> all(int field){return fields.getOrDefault(field,List.of());}
        long number(int field,long fallback)throws IOException {var values=all(field);if(values.isEmpty())return fallback;if(values.size()!=1||values.get(0).wire()!=0)throw bad("整数类型或重复字段无效");return values.get(0).value();}
        Slice bytes(int field)throws IOException {var values=all(field);if(values.isEmpty())return null;if(values.size()!=1||values.get(0).wire()!=2)throw bad("字节类型或重复字段无效");return values.get(0).bytes();}
    }
    private record ObjectData(long id,int type,String source,Message body) {}
    private record CellPosition(int column,int start) {}
    private static final class Cursor {
        final byte[] data;final int end;int at;
        Cursor(byte[] data,int start,int end){this.data=data;this.at=start;this.end=end;}
        int remaining(){return end-at;}
        void need(long n)throws IOException {if(n<0||n>remaining())throw bad("结构长度越界或截断");}
        int octet()throws IOException {need(1);return data[at++]&255;}
        long varint()throws IOException {
            long result=0;
            for(int shift=0;shift<=63;shift+=7){int b=octet();if(shift==63&&(b&254)!=0)throw bad("varint溢出");result|=(long)(b&127)<<shift;if((b&128)==0){if(result<0)throw bad("不支持超出有符号范围的标识");return result;}}
            throw bad("varint过长");
        }
        Slice slice(int n)throws IOException {need(n);var result=new Slice(data,at,n);at+=n;return result;}
        long little(int n)throws IOException {need(n);long value=0;for(int i=0;i<n;i++)value|=(long)octet()<<(8*i);return value;}
        double floating()throws IOException {double value=Double.longBitsToDouble(little(8));if(!Double.isFinite(value))throw bad("单元格数值非有限");return value;}
    }
    private final Limits limits;
    private int entries,zipBytes,decodedBytes,objectCount,fieldCount;
    private boolean modern;
    private final Map<Long,ObjectData> objects=new TreeMap<>();
    private final StringBuilder output=new StringBuilder();
    private IworkBodyExtractor(Limits limits){this.limits=limits;}
    /** 非IWA容器返回empty交原解析器；已识别但损坏/超限/无正文时明确拒绝。 */
    public static Optional<String> extract(Path input,int maxChars)throws IOException {return extract(input,Limits.defaults(maxChars));}
    public static Optional<String> extract(Path input,Limits limits)throws IOException {
        byte[] magic;try(InputStream in=Files.newInputStream(input)){magic=in.readNBytes(4);}
        if(magic.length<4||magic[0]!='P'||magic[1]!='K'||magic[2]!=3||magic[3]!=4)return Optional.empty();
        IworkBodyExtractor reader=new IworkBodyExtractor(limits);
        try(ZipFile zip=new ZipFile(input.toFile())) {
            // 先辨识IWA容器；DOCX/EPUB等旧解析入口不承受iWork专用条目预算。
            boolean recognized=false;var candidates=zip.entries();
            while(candidates.hasMoreElements()) {
                ZipEntry entry=candidates.nextElement();String name=entry.getName();
                if(!entry.isDirectory()&&(name.equals("Index.zip")||(name.startsWith("Index/")&&name.endsWith(".iwa")))){recognized=true;break;}
            }
            if(!recognized)return Optional.empty();reader.modern=true;
            Set<String> names=new HashSet<>();var records=zip.entries();
            while(records.hasMoreElements()) {
                ZipEntry entry=records.nextElement();reader.entry(entry.getName(),names);if(entry.isDirectory())continue;
                if(entry.getName().endsWith(".iwa")&&entry.getName().startsWith("Index/")) {
                    try(InputStream in=zip.getInputStream(entry)){reader.archive(reader.read(in),entry.getName());}
                } else if(entry.getName().equals("Index.zip")) {
                    try(InputStream in=zip.getInputStream(entry)){reader.nested(reader.read(in));}
                }
            }
        }
        if(!reader.modern)return Optional.empty();
        reader.body();if(reader.output.toString().isBlank())throw bad("现代iWork未找到已支持的正文对象");
        return Optional.of(reader.output.toString());
    }
    private static IOException bad(String reason){return new IOException("IWORK_BODY_INVALID:"+reason);}
    private void entry(String name,Set<String> names)throws IOException {
        if(++entries>limits.entries())throw bad("ZIP条目超过预算");
        if(name.startsWith("/")||name.indexOf('\\')>=0||name.indexOf(':')>=0||Arrays.asList(name.split("/")).contains("..")||!names.add(name))throw bad("容器条目路径或重复名称无效");
    }
    private byte[] read(InputStream in)throws IOException {
        ByteArrayOutputStream result=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=in.read(buffer))!=-1){if(n==0)continue;if(n>limits.zipBytes()-zipBytes)throw bad("ZIP解压总量超过预算");zipBytes+=n;result.write(buffer,0,n);}
        return result.toByteArray();
    }
    private void nested(byte[] bytes)throws IOException {
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;Set<String> names=new HashSet<>();
            while((entry=zip.getNextEntry())!=null) {
                entry(entry.getName(),names);
                // 内层仅允许IWA；读取所有条目也计解压预算，不能用跳过条目绕开ZIP炸弹。
                byte[] content=read(zip);
                if(entry.getName().startsWith("Index/")&&entry.getName().endsWith(".iwa"))archive(content,entry.getName());
                if(entry.getName().endsWith(".zip"))throw bad("Index内不允许再次嵌套ZIP");
            }
        }
    }
    private void archive(byte[] framed,String source)throws IOException {
        modern=true;Cursor in=new Cursor(framed,0,framed.length);ByteArrayOutputStream raw=new ByteArrayOutputStream();
        while(in.remaining()>0) {
            int kind=in.octet(),length=(int)in.little(3);if(kind!=0)throw bad("未知IWA压缩帧类型");
            byte[] block=snappy(in.slice(length).cursor());raw.write(block);
        }
        byte[] data=raw.toByteArray();Cursor stream=new Cursor(data,0,data.length);
        while(stream.remaining()>0) {
            if(++objectCount>limits.objects())throw bad("IWA对象超过预算");
            long size=stream.varint();if(size>65536)throw bad("ArchiveInfo超过预算");Message info=message(stream.slice((int)size));long id=info.number(1,-1);
            if(id<0||info.all(2).isEmpty())throw bad("ArchiveInfo缺标识或消息");
            for(Field f:info.all(2)) {
                if(f.wire()!=2)throw bad("MessageInfo类型无效");Message descriptor=message(f.bytes());long type=descriptor.number(1,-1),length=descriptor.number(3,-1);
                if(type<0||type>Integer.MAX_VALUE||length<0||length>Integer.MAX_VALUE)throw bad("消息类型或长度无效");
                Slice payload=stream.slice((int)length);
                if(Set.of(2001,2005,6001,6002,6005,6218).contains((int)type)) {
                    ObjectData object=new ObjectData(id,(int)type,source,message(payload));
                    if(objects.putIfAbsent(id,object)!=null)throw bad("正文对象标识重复，无法确定版本");
                }
            }
        }
    }
    private byte[] snappy(Cursor in)throws IOException {
        long declared=in.varint();if(declared>limits.decodedBytes()-decodedBytes)throw bad("Snappy展开总量超过预算");
        decodedBytes+=(int)declared;byte[] out=new byte[(int)declared];int at=0;
        while(in.remaining()>0) {
            int tag=in.octet(),kind=tag&3,length;long offset;
            if(kind==0) {
                int code=tag>>>2;long value=code<60?code+1:in.little(code-59)+1;
                if(value>out.length-at)throw bad("Snappy字面量越界");length=(int)value;Slice literal=in.slice(length);System.arraycopy(literal.data(),literal.start(),out,at,length);at+=length;
            } else {
                if(kind==1){length=((tag>>>2)&7)+4;offset=((tag&224)<<3)|in.octet();}
                else {length=(tag>>>2)+1;offset=in.little(kind==2?2:4);}
                if(offset==0||offset>at||length>out.length-at)throw bad("Snappy回指越界");
                for(int i=0;i<length;i++){out[at]=out[at-(int)offset];at++;}
            }
        }
        if(at!=out.length)throw bad("Snappy展开长度不符");return out;
    }
    private Message message(Slice bytes)throws IOException {
        Cursor in=bytes.cursor();Map<Integer,List<Field>> fields=new HashMap<>();
        while(in.remaining()>0) {
            if(++fieldCount>limits.fields())throw bad("protobuf字段超过预算");long tag=in.varint(),number=tag>>>3;int wire=(int)(tag&7);
            if(number<1||number>536870911)throw bad("protobuf字段编号无效");Field field;
            switch(wire) {
                case 0 -> field=new Field(wire,in.varint(),null);
                case 1 -> field=new Field(wire,in.little(8),null);
                case 5 -> field=new Field(wire,in.little(4),null);
                case 2 -> {long size=in.varint();if(size>Integer.MAX_VALUE)throw bad("protobuf字段长度溢出");field=new Field(wire,0,in.slice((int)size));}
                default -> throw bad("不支持protobuf group或未知wire类型");
            }
            fields.computeIfAbsent((int)number,key->new ArrayList<>()).add(field);
        }
        return new Message(fields);
    }
    private String text(Slice bytes)throws IOException {
        if(bytes==null)return "";
        if(bytes.size()>4L*limits.chars())throw bad("正文UTF8字节超过预算");
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.data(),bytes.start(),bytes.size())).toString().replace('\uFFFC','\n');}
        catch(CharacterCodingException invalid){throw bad("正文UTF8编码无效");}
    }
    private void append(String value)throws IOException {
        if(value.isBlank())return;if((long)output.length()+value.length()+1>limits.chars())throw bad("正文字符超过预算");output.append(value).append('\n');
    }
    private void body()throws IOException {
        for(ObjectData data:objects.values())if((data.type()==2001||data.type()==2005)&&!data.source().contains("MasterSlide")&&data.body().number(10,0)==1&&data.body().number(1,3)!=5)append(storage(data));
        for(ObjectData data:objects.values())if(data.type()==6001)table(data);
    }
    private String storage(ObjectData data)throws IOException {
        if(data==null||(data.type()!=2001&&data.type()!=2005))throw bad("单元格正文引用类型不符");StringBuilder text=new StringBuilder();
        for(Field field:data.body().all(3)){if(field.wire()!=2)throw bad("正文字符串类型无效");String part=text(field.bytes());if((long)text.length()+part.length()>limits.chars())throw bad("正文字符超过预算");text.append(part);}
        return text.toString();
    }
    private long reference(Slice bytes)throws IOException {if(bytes==null)return -1;return message(bytes).number(1,-1);}
    private ObjectData get(long id,int type)throws IOException {ObjectData object=objects.get(id);if(object==null||object.type()!=type)throw bad("表格对象引用缺失或类型不符");return object;}
    private Map<Long,String> strings(long id)throws IOException {
        if(id<0)return Map.of();ObjectData object=get(id,6005);if(object.body().number(1,-1)!=1)throw bad("表格字符串池类型不符");Map<Long,String> result=new HashMap<>();
        for(Field f:object.body().all(3)){if(f.wire()!=2)throw bad("字符串池条目类型无效");Message entry=message(f.bytes());long key=entry.number(1,-1);if(key<0||result.putIfAbsent(key,text(entry.bytes(3)))!=null)throw bad("字符串池key无效或重复");}
        return result;
    }
    private Map<Long,Long> rich(long id)throws IOException {
        if(id<0)return Map.of();ObjectData object=get(id,6005);if(object.body().number(1,-1)!=8)throw bad("富文本池类型不符");Map<Long,Long> result=new HashMap<>();
        for(Field f:object.body().all(3)){if(f.wire()!=2)throw bad("富文本池条目无效");Message entry=message(f.bytes());long key=entry.number(1,-1),target=reference(entry.bytes(9));if(key<0||target<0||result.putIfAbsent(key,target)!=null)throw bad("富文本池key无效或重复");}
        return result;
    }
    private void table(ObjectData table)throws IOException {
        String title=text(table.body().bytes(8));Slice storeBytes=table.body().bytes(4);if(storeBytes==null)throw bad("表格缺少DataStore");Message store=message(storeBytes);
        Map<Long,String> strings=strings(reference(store.bytes(4)));Map<Long,Long> rich=rich(reference(store.bytes(17)));
        Slice tilesBytes=store.bytes(3);if(tilesBytes==null)throw bad("表格缺少tile索引");Message tiles=message(tilesBytes);TreeMap<Long,Long> ordered=new TreeMap<>();
        for(Field f:tiles.all(1)){if(f.wire()!=2)throw bad("tile索引类型无效");Message entry=message(f.bytes());long ordinal=entry.number(1,-1),id=reference(entry.bytes(2));if(ordinal<0||id<0||ordered.putIfAbsent(ordinal,id)!=null)throw bad("tile索引重复或无效");}
        boolean started=false;for(long id:ordered.values())started=tile(get(id,6002),strings,rich,title,started);
    }
    private boolean tile(ObjectData tile,Map<Long,String> strings,Map<Long,Long> rich,String title,boolean started)throws IOException {
        TreeMap<Long,Message> rows=new TreeMap<>();
        for(Field f:tile.body().all(5)){if(f.wire()!=2)throw bad("tile行类型无效");Message row=message(f.bytes());long index=row.number(1,-1);if(index<0||rows.putIfAbsent(index,row)!=null)throw bad("tile行重复或无效");}
        for(Message row:rows.values()) {
            boolean newer=row.bytes(6)!=null&&row.bytes(7)!=null;Slice cells=row.bytes(newer?6:3),offsets=row.bytes(newer?7:4);if(cells==null||offsets==null||offsets.size()%2!=0)throw bad("单元格数据或offsets无效");
            int factor=newer&&row.number(8,0)==1?4:1,columns=offsets.size()/2;
            if(columns>limits.chars())throw bad("表格列数超过字符预算");
            Cursor indexes=offsets.cursor();List<CellPosition> starts=new ArrayList<>();int column=0;
            while(indexes.remaining()>0){int offset=(int)indexes.little(2),index=column++;if(offset==65535)continue;offset*=factor;if(offset>=cells.size()||(!starts.isEmpty()&&offset<=starts.get(starts.size()-1).start()))throw bad("单元格offset越界或次序无效");starts.add(new CellPosition(index,offset));}
            StringBuilder line=new StringBuilder();int current=0;
            for(column=0;column<columns;column++) {
                if(column>0)line.append('\t');String value="";
                if(current<starts.size()&&starts.get(current).column()==column){int start=starts.get(current).start(),end=current+1<starts.size()?starts.get(current+1).start():cells.size();value=cell(new Slice(cells.data(),cells.start()+start,end-start),newer,strings,rich);current++;}
                if((long)line.length()+value.length()+1>limits.chars())throw bad("表格行字符超过预算");line.append(value);
            }
            // 表名不能使空表被判为正文；只有已存单元格含值时才输出表名。
            if(!line.toString().isBlank()){if(!started){append(title);started=true;}append(line.toString());}
        }
        return started;
    }
    private String cell(Slice bytes,boolean newer,Map<Long,String> strings,Map<Long,Long> rich)throws IOException {
        Cursor in=bytes.cursor();in.need(12);in.octet();int type=in.octet();long flags,textId=-1,richId=-1;String number="";
        if(newer) {
            in.at=bytes.start()+8;flags=in.little(4);
            if((flags&1)!=0){byte[] mantissa=new byte[15];for(int i=0;i<14;i++)mantissa[14-i]=(byte)in.octet();int exponent=(int)in.little(2);BigInteger significand=new BigInteger(mantissa);if((exponent&1)!=0)significand=significand.setBit(112);if((exponent&32768)!=0)significand=significand.negate();int power=((exponent&32766)-12352)/2;number=new BigDecimal(significand).scaleByPowerOfTen(power).stripTrailingZeros().toEngineeringString();}
            if((flags&2)!=0)number=decimal(in.floating(),type);
            if((flags&4)!=0)number=date(in.floating());
            if((flags&8)!=0)textId=in.little(4);
            if((flags&16)!=0)richId=in.little(4);
        } else {
            in.at=bytes.start()+4;flags=in.little(2);in.slice(6);
            for(int mask:new int[]{2,128,2048,1024,4,8,4096})if((flags&mask)!=0)in.little(4);
            if((flags&16)!=0)textId=in.little(4);
            if((flags&32)!=0)number=decimal(in.floating(),type);
            if((flags&64)!=0)number=date(in.floating());
            if((flags&512)!=0)richId=in.little(4);
        }
        if(textId>=0){String value=strings.get(textId);if(value==null)throw bad("单元格字符串引用缺失");return value;}
        if(richId>=0){Long id=rich.get(richId);if(id==null)throw bad("单元格富文本引用缺失");ObjectData payload=get(id,6218);return storage(objects.get(reference(payload.body().bytes(1))));}
        if(type==0)return "";if(!number.isEmpty())return number;
        if(type==3||type==9)throw bad("正文单元格值缺失");return "";
    }
    private static String decimal(double value,int type){return type==6?(value==0?"false":"true"):BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();}
    private static String date(double value)throws IOException {if(Math.abs(value)>315576000000L)throw bad("日期单元格超范围");return Instant.parse("2001-01-01T00:00:00Z").plusMillis((long)(value*1000)).toString();}
}
