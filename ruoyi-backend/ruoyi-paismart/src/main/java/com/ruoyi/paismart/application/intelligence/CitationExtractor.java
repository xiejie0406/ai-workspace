package com.ruoyi.paismart.application.intelligence;
import java.util.*;
import java.util.regex.*;
/** 来源格式和 [n] 编号都按被引用的句子核验，支持句号之后及相邻的引用。 */
public final class CitationExtractor {
    private static final Pattern GROUP=Pattern.compile("[(（][^()（）\\n]*?来源#\\d+[^()（）\\n]*[)）]");
    private static final Pattern NUMBER=Pattern.compile("来源#(\\d+)");
    private static final Pattern BRACKET=Pattern.compile("\\[\\s*\\d+(?:\\s*[,，;；]\\s*\\d+)*\\s*]");
    private static final Pattern DIGITS=Pattern.compile("\\d+");
    private static final Pattern BARE=Pattern.compile("来源#\\d+(?::[^，。；;！？!?\\n]*)?");
    private static final Pattern NOISE=Pattern.compile("^[\\s，,、：:；;。）)\\]]+|^(?:[-*•>#]+|\\d+[.、)])\\s*");
    private static final String TERMINATORS="。！？!?；;";
    private CitationExtractor(){}
    public record CitedClaim(String claim,List<Integer> referenceNumbers){}
    private record Span(int start,int end,List<Integer> references){}
    public static List<CitedClaim> extract(String answer,int maxClaimChars){
        if(answer==null||answer.isBlank())return List.of();
        if(maxClaimChars<1)throw new IllegalArgumentException("核验句子长度须为正数");
        List<Span> spans=new ArrayList<>();Matcher group=GROUP.matcher(answer);
        while(group.find())spans.add(new Span(group.start(),group.end(),numbers(group.group(),NUMBER)));
        Matcher brackets=BRACKET.matcher(answer);
        while(brackets.find())spans.add(new Span(brackets.start(),brackets.end(),numbers(brackets.group(),DIGITS)));
        Matcher bare=NUMBER.matcher(answer);
        while(bare.find()){int start=bare.start();boolean covered=spans.stream().anyMatch(s->start>=s.start()&&start<s.end());if(!covered)spans.add(new Span(start,bare.end(),numbers(bare.group(),NUMBER)));}
        spans.sort(Comparator.comparingInt(Span::start));
        Map<String,Set<Integer>> claims=new LinkedHashMap<>();String previousClaim=null;int previousEnd=0;
        for(Span span:spans){
            String claim=claimBefore(answer,span.start,previousEnd,maxClaimChars);
            if(claim.isBlank()){if(previousClaim!=null&&answer.substring(previousEnd,span.start).isBlank())claims.get(previousClaim).addAll(span.references);}
            else{claims.computeIfAbsent(claim,ignored->new LinkedHashSet<>()).addAll(span.references);previousClaim=claim;}
            previousEnd=span.end;
        }
        return claims.entrySet().stream().map(e->new CitedClaim(e.getKey(),List.copyOf(e.getValue()))).toList();
    }
    private static List<Integer> numbers(String text,Pattern pattern){
        Set<Integer> out=new LinkedHashSet<>();Matcher matcher=pattern.matcher(text);
        while(matcher.find())try{int number=Integer.parseInt(matcher.groupCount()>0?matcher.group(1):matcher.group());if(number>0)out.add(number);}catch(NumberFormatException ignored){}
        return List.copyOf(out);
    }
    private static String claimBefore(String answer,int citationStart,int previousEnd,int max){
        int cursor=citationStart-1;while(cursor>=previousEnd&&Character.isWhitespace(answer.charAt(cursor)))cursor--;
        if(cursor>=previousEnd&&TERMINATORS.indexOf(answer.charAt(cursor))>=0)cursor--;
        int start=previousEnd;for(int i=cursor;i>=previousEnd;i--)if(answer.charAt(i)=='\n'||TERMINATORS.indexOf(answer.charAt(i))>=0){start=i+1;break;}
        String claim=answer.substring(start,citationStart);claim=GROUP.matcher(claim).replaceAll("");claim=BARE.matcher(claim).replaceAll("");claim=BRACKET.matcher(claim).replaceAll("");
        claim=claim.replace("**","").replace(String.valueOf((char)96),"").trim();String previous;
        do{previous=claim;claim=NOISE.matcher(claim).replaceFirst("").trim();}while(!claim.equals(previous));
        return claim.length()>max?claim.substring(claim.length()-max):claim;
    }
}
