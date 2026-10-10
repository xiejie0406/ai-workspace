package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import java.math.BigInteger;

/** 原ast.literal_eval的可传输常量；解析Python字符串、数值及容器，不执行调用或计算表达式。 */
final class PythonLiteralValue {
    private static final java.util.Map<String,Integer> NAME_ALIASES=nameAliases();
    /** tuple经JSON传输为数组，但原CUSTOM str仍须保留圆括号与单元素逗号。 */
    static final class Tuple extends java.util.ArrayList<Object> {}
    private final String source;private int cursor;private boolean unaryOperand;
    private PythonLiteralValue(String source){this.source=source;}
    static Object parse(String source) {
        if(source.length()>RunProtocolLimits.INPUT_BYTES)throw invalid();
        var parser=new PythonLiteralValue(source);var result=parser.value(0);int after=parser.cursor;parser.space();
        if(parser.cursor<source.length() && source.charAt(parser.cursor)==','){
            if(unescapedNewline(source.substring(after,parser.cursor)))throw invalid();parser.cursor++;var tuple=new Tuple();tuple.add(result);
            while(true){int before=parser.cursor;parser.space();if(parser.cursor==source.length())break;if(unescapedNewline(source.substring(before,parser.cursor)))throw invalid();tuple.add(parser.value(0));after=parser.cursor;parser.space();if(parser.cursor==source.length())break;if(source.charAt(parser.cursor)!=',' || unescapedNewline(source.substring(after,parser.cursor)))throw invalid();parser.cursor++;}result=tuple;
        }
        if(parser.cursor!=source.length())throw invalid();return result;
    }
    private static com.ruoyi.common.exception.ServiceException invalid(){return DataResourceService.fail("原Python字面量不是可传输常量",422);}
    private static java.util.Map<String,Integer> nameAliases() {
        var names=new java.util.HashMap<String,Integer>();
        try(var input=PythonLiteralValue.class.getResourceAsStream("/paiflow/unicode/NameAliases-15.0.0.txt")) {
            if(input==null)throw new IllegalStateException("缺少与原Python一致的Unicode名称别名资源");
            for(String line:new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                if(line.isBlank() || line.startsWith("#"))continue;String[] parts=line.split(";");
                if(parts.length!=3 || names.put(parts[1],Integer.parseInt(parts[0],16))!=null)throw new IllegalStateException("Unicode名称别名资源损坏");
            }
        }catch(java.io.IOException ex){throw new IllegalStateException("Unicode名称别名资源不可读取",ex);}
        return java.util.Map.copyOf(names);
    }
    private void space(){while(cursor<source.length()) {char c=source.charAt(cursor);if(c==' ' || c=='\t' || c=='\f' || c=='\n' || c=='\r'){cursor++;continue;}if(c=='#'){while(cursor<source.length() && source.charAt(cursor)!='\n' && source.charAt(cursor)!='\r')cursor++;continue;}if(c=='\\' && cursor+1<source.length() && (source.charAt(cursor+1)=='\n' || source.charAt(cursor+1)=='\r')){cursor+=2;if(source.charAt(cursor-1)=='\r' && cursor<source.length() && source.charAt(cursor)=='\n')cursor++;continue;}break;}}
    private boolean take(char c){space();if(cursor<source.length() && source.charAt(cursor)==c){cursor++;return true;}return false;}
    private void require(char c){if(!take(c))throw invalid();}
    private Object value(int depth) {
        if(depth>48)throw invalid();space();if(cursor>=source.length())throw invalid();char c=source.charAt(cursor);
        if(stringStart()) {var text=new StringBuilder();do{text.append(string());int before=cursor;space();if(!stringStart() || depth==0 && unescapedNewline(source.substring(before,cursor))){cursor=before;break;}}while(stringStart());return text.toString();}
        if(c=='+' || c=='-') {
            if(unaryOperand)throw invalid();
            cursor++;int before=cursor;space();if(depth==0 && unescapedNewline(source.substring(before,cursor)))throw invalid();if(cursor>=source.length() || source.charAt(cursor)=='+' || source.charAt(cursor)=='-')throw invalid();
            Object number;unaryOperand=true;try{number=value(depth+1);}finally{unaryOperand=false;}if(!(number instanceof Number))throw invalid();
            if(c=='+')return number;
            if(number instanceof BigInteger integer)return integer.negate();return -((Number)number).doubleValue();
        }
        if(c=='[' || c=='(') {
            cursor++;char end=c=='['?']':')';java.util.List<Object> list=c=='('?new Tuple():new JSONArray();if(take(end))return list;
            Object first=value(depth+1);list.add(first);
            if(c=='(' && take(')'))return first;
            while(take(',')){if(take(end))return list;list.add(value(depth+1));}
            require(end);return list;
        }
        if(c=='{') {
            cursor++;var object=new JSONObject();if(take('}'))return object;
            do {Object key=value(depth+1);if(!(key instanceof String text))throw invalid();require(':');object.put(text,value(depth+1));if(take('}'))return object;}while(take(',') && !take('}'));
            if(cursor>0 && source.charAt(cursor-1)=='}')return object;throw invalid();
        }
        for(String token:new String[]{"True","False","None"})if(source.startsWith(token,cursor)) {
            cursor+=token.length();return "None".equals(token)?null:"True".equals(token);
        }
        int begin=cursor;
        while(cursor<source.length() && ",]}):# \t\r\n".indexOf(source.charAt(cursor))<0)cursor++;
        String number=source.substring(begin,cursor);
        String digitsPattern="[0-9](?:_?[0-9])*";
        if(!number.matches("(?:0[xX]_?[0-9a-fA-F](?:_?[0-9a-fA-F])*|0[oO]_?[0-7](?:_?[0-7])*|0[bB]_?[01](?:_?[01])*|(?:"+digitsPattern+"(?:\\.(?:"+digitsPattern+")?)?|\\."+digitsPattern+")(?:[eE][+-]?"+digitsPattern+")?)"))throw invalid();
        String clean=number.replace("_","");
        try {
            if(clean.contains(".") || clean.matches(".*[eE].*") && !clean.matches("[+-]?0[xX].*")) {
                double floating=Double.parseDouble(clean);if(!Double.isFinite(floating))throw invalid();return floating;
            }
            int sign=clean.startsWith("-")?-1:1;String digits=clean.startsWith("-") || clean.startsWith("+")?clean.substring(1):clean;int radix=10;
            if(digits.startsWith("0x") || digits.startsWith("0X")){radix=16;digits=digits.substring(2);}
            else if(digits.startsWith("0o") || digits.startsWith("0O")){radix=8;digits=digits.substring(2);}
            else if(digits.startsWith("0b") || digits.startsWith("0B")){radix=2;digits=digits.substring(2);}
            else if(digits.length()>1 && digits.charAt(0)=='0' && !digits.matches("0+"))throw invalid();
            var integer=new BigInteger(digits,radix);return sign<0?integer.negate():integer;
        }catch(NumberFormatException ex){throw invalid();}
    }
    private boolean stringStart() {
        if(cursor>=source.length())return false;char c=source.charAt(cursor);
        return c=='\'' || c=='"' || "rRuU".indexOf(c)>=0 && cursor+1<source.length() && (source.charAt(cursor+1)=='\'' || source.charAt(cursor+1)=='"');
    }
    private static boolean unescapedNewline(String text) {
        for(int i=0;i<text.length();i++){char c=text.charAt(i);if(c=='\\' && i+1<text.length() && (text.charAt(i+1)=='\r' || text.charAt(i+1)=='\n')){i++;if(text.charAt(i)=='\r' && i+1<text.length() && text.charAt(i+1)=='\n')i++;}else if(c=='\r' || c=='\n')return true;}return false;
    }
    private String string() {
        boolean raw=false;char first=source.charAt(cursor);
        if("rRuU".indexOf(first)>=0){raw=first=='r' || first=='R';cursor++;}
        char quote=source.charAt(cursor++);boolean triple=cursor+1<source.length() && source.charAt(cursor)==quote && source.charAt(cursor+1)==quote;
        if(triple)cursor+=2;var out=new StringBuilder();
        while(cursor<source.length()) {
            char c=source.charAt(cursor++);
            if(c==quote){if(!triple)return out.toString();if(cursor+1<source.length() && source.charAt(cursor)==quote && source.charAt(cursor+1)==quote){cursor+=2;return out.toString();}out.append(c);continue;}
            if(c=='\n' || c=='\r'){if(!triple)throw invalid();if(c=='\r' && cursor<source.length() && source.charAt(cursor)=='\n')cursor++;out.append('\n');continue;}
            if(c!='\\'){out.append(c);continue;}
            if(cursor>=source.length())throw invalid();char escaped=source.charAt(cursor++);
            if(raw){out.append('\\');if(escaped=='\r'){if(cursor<source.length() && source.charAt(cursor)=='\n')cursor++;out.append('\n');}else out.append(escaped);continue;}
            switch(escaped) {
                case '\\','\'','"'->out.append(escaped);case 'n'->out.append('\n');case 'r'->out.append('\r');case 't'->out.append('\t');
                case 'b'->out.append('\b');case 'f'->out.append('\f');case 'a'->out.append((char)7);case 'v'->out.append((char)11);case '\n'->{}
                case '\r'->{if(cursor<source.length() && source.charAt(cursor)=='\n')cursor++;}
                case 'N'->{if(cursor>=source.length() || source.charAt(cursor++)!='{')throw invalid();int end=source.indexOf('}',cursor);if(end<0)throw invalid();String name=source.substring(cursor,end);if(!name.equals(name.trim()))throw invalid();Integer alias=NAME_ALIASES.get(name.toUpperCase(java.util.Locale.ROOT));try{out.appendCodePoint(alias==null?Character.codePointOf(name):alias);}catch(IllegalArgumentException ex){throw invalid();}cursor=end+1;}
                case 'x','u','U'->{int count=escaped=='x'?2:escaped=='u'?4:8;if(cursor+count>source.length())throw invalid();String hex=source.substring(cursor,cursor+count);if(!hex.matches("[0-9a-fA-F]{"+count+"}"))throw invalid();try{int point=Integer.parseUnsignedInt(hex,16);if(!Character.isValidCodePoint(point))throw invalid();out.appendCodePoint(point);cursor+=count;}catch(NumberFormatException ex){throw invalid();}}
                default->{if(escaped>='0' && escaped<='7'){int point=escaped-'0',count=1;while(count<3 && cursor<source.length() && source.charAt(cursor)>='0' && source.charAt(cursor)<='7'){point=point*8+source.charAt(cursor++)-'0';count++;}out.append((char)point);}else out.append('\\').append(escaped);}
            }
        }
        throw invalid();
    }
}
