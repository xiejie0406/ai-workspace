package com.ruoyi.paismart.application.intelligence;
import java.util.*;
import java.text.BreakIterator;
import java.util.regex.*;

/** 原语义分块、尾部重叠和小块合并规则，使用JDK标点/词边界避免迁入全局HanLP状态。 */
public final class TextChunker {
    private TextChunker(){}
    /** 相邻原文拼接去掉重叠；识别失败保留原文，不以猜测丢内容。 */
    public static String merge(List<String> chunks){if(chunks==null||chunks.isEmpty())return "";StringBuilder result=new StringBuilder();String previous=null;for(String chunk:chunks){if(chunk==null||chunk.isBlank())continue;String text=chunk.trim(),addition=previous==null?text:stripOverlap(previous,text);if(!addition.isEmpty()){if(!result.isEmpty())result.append("\n\n");result.append(addition);}previous=text;}return result.toString();}
    public static String stripOverlap(String previous,String next){for(String separator:List.of("\n\n","\n")){int at=next.indexOf(separator);if(at>0){String head=next.substring(0,at).replaceAll("\\s+","");if(!head.isEmpty()&&previous.replaceAll("\\s+","").endsWith(head))return next.substring(at+separator.length()).trim();}}int probe=Math.min(Math.min(previous.length(),next.length()-1),600);for(int n=probe;n>=12;n--)if(previous.endsWith(next.substring(0,n)))return next.substring(n).trim();return next;}
    public static List<String> split(String text,int size,int overlap,int min) {
        if(size<32||overlap<0||overlap>=size||min<0||min>size)throw new IllegalArgumentException("分块参数无效");
        if(text==null||text.isBlank())return List.of();
        List<String> base=new ArrayList<>();StringBuilder pending=new StringBuilder();
        for(String raw:text.split("(?:\\r?\\n){2,}")) {
            String paragraph=raw.trim();if(paragraph.isEmpty())continue;
            if(paragraph.length()>size) {
                flush(base,pending);splitParagraph(paragraph,size,base);
            } else if(pending.length()+(pending.isEmpty()?0:2)+paragraph.length()>size) {
                flush(base,pending);pending.append(paragraph);
            } else {if(!pending.isEmpty())pending.append("\n\n");pending.append(paragraph);}
        }
        flush(base,pending);
        List<String> merged=mergeSmall(base,size,overlap,min),result=new ArrayList<>();
        for(int i=0;i<merged.size();i++) {
            String prefix=i==0?"":semanticTail(merged.get(i-1),overlap);
            result.add(prefix.isBlank()?merged.get(i):prefix+"\n\n"+merged.get(i));
        }
        return List.copyOf(result);
    }
    private static void flush(List<String> chunks,StringBuilder pending) {
        if(!pending.isEmpty()){String text=pending.toString().trim();if(!text.isEmpty())chunks.add(text);pending.setLength(0);}
    }
    private static void splitParagraph(String paragraph,int size,List<String> chunks) {
        StringBuilder pending=new StringBuilder();
        for(String sentence:paragraph.split("(?<=[。！？；])|(?<=[.!?;])\\s+")) {
            if(sentence.length()>size) {flush(chunks,pending);splitWords(sentence,size,chunks);}
            else {if(pending.length()+sentence.length()>size)flush(chunks,pending);pending.append(sentence);}
        }
        flush(chunks,pending);
    }
    /** 保留完整词与原空白；仅单个词本身超上限时按Unicode安全边界切分。 */
    private static void splitWords(String sentence,int size,List<String> chunks) {
        BreakIterator words=BreakIterator.getWordInstance(Locale.ROOT);words.setText(sentence);
        StringBuilder pending=new StringBuilder();int from=words.first();
        for(int to=words.next();to!=BreakIterator.DONE;from=to,to=words.next()) {
            String token=sentence.substring(from,to);
            if(token.length()>size) {
                flush(chunks,pending);
                for(int start=0;start<token.length();) {int end=safeEnd(token,start,Math.min(token.length(),start+size));chunks.add(token.substring(start,end));start=end;}
            } else {if(pending.length()+token.length()>size)flush(chunks,pending);pending.append(token);}
        }
        flush(chunks,pending);
    }
    static List<String> mergeSmall(List<String> base,int size,int overlap,int min) {
        List<String> merged=new ArrayList<>();
        for(String chunk:base) {
            if(!merged.isEmpty()) {
                int last=merged.size()-1;String previous=merged.get(last);
                if((previous.length()<min||chunk.length()<min)&&previous.length()+2+chunk.length()<=size+overlap) {
                    merged.set(last,previous+"\n\n"+chunk);continue;
                }
            }
            merged.add(chunk);
        }
        return merged;
    }
    static String semanticTail(String text,int limit) {
        if(limit==0||text.isBlank())return "";
        List<String> sentences=new ArrayList<>();Matcher matcher=Pattern.compile("[^。！？；.!?;]+[。！？；.!?;]?").matcher(text);
        while(matcher.find())sentences.add(matcher.group());
        StringBuilder suffix=new StringBuilder();
        for(int i=sentences.size()-1;i>=0;i--) {
            String sentence=sentences.get(i);
            if(sentence.length()+suffix.length()>limit) {if(suffix.isEmpty())return wordTail(sentence,limit).trim();break;}
            suffix.insert(0,sentence);
        }
        return suffix.toString().trim();
    }
    private static String wordTail(String text,int limit) {
        BreakIterator words=BreakIterator.getWordInstance(Locale.ROOT);words.setText(text);int end=words.last(),start=end;
        for(int previous=words.previous();previous!=BreakIterator.DONE;previous=words.previous()) {
            if(end-previous>limit)break;start=previous;
        }
        // 超长单词不截成伪单词重叠；基础分块已保存全文。
        return text.substring(start,end);
    }
    private static int safeEnd(String text,int start,int end) {
        if(end<text.length()&&end>start&&Character.isHighSurrogate(text.charAt(end-1)))end--;
        return end;
    }
}
